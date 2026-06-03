package com.ticketchecker.checker

import android.util.Log
import com.ticketchecker.model.MelonTarget
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

sealed class MelonCheckResult {
    data class Available(val rmdSeatCnt: Int, val chkResult: Int) : MelonCheckResult()
    data class NoTickets(val gradeSummary: String = "") : MelonCheckResult()
    data class Error(val message: String) : MelonCheckResult()
    object SessionExpired : MelonCheckResult()
}

class MelonChecker(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "MelonChecker"
        private const val API_URL = "https://ticket.melon.com/tktapi/product/summary.json"
        private const val CALLBACK = "melonChecker"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    }

    fun check(target: MelonTarget): MelonCheckResult {
        return try {
            val cookieHeader = target.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            val urlBuilder = API_URL.toHttpUrl().newBuilder()
                .addQueryParameter("v", "1")
                .addQueryParameter("callback", CALLBACK)
                .build()

            val formBody = FormBody.Builder()
                .add("prodId", target.prodId)
                .add("pocCode", target.pocCode)
                .add("scheduleNo", target.scheduleNo)
                .add("perfDate", "")
                .add("corpCodeNo", "")
                .build()

            val request = Request.Builder()
                .url(urlBuilder)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Referer", "https://ticket.melon.com/reservation/popup/stepSeat.htm")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "*/*")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .apply {
                    if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader)
                }
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return MelonCheckResult.Error("Empty response")

            Log.d(TAG, "Response code: ${response.code}, body: ${body.take(300)}")

            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) return MelonCheckResult.SessionExpired
                return MelonCheckResult.Error("HTTP ${response.code}")
            }

            if (body.contains("login", ignoreCase = true) && body.contains("로그인")) {
                return MelonCheckResult.SessionExpired
            }

            parseSummaryResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "Check failed", e)
            MelonCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun parseSummaryResponse(jsonp: String): MelonCheckResult {
        return try {
            val start = jsonp.indexOf('(')
            val end = jsonp.lastIndexOf(')')
            if (start == -1 || end == -1 || start >= end) {
                return MelonCheckResult.Error("Invalid JSONP format")
            }

            val json = JSONObject(jsonp.substring(start + 1, end))
            val code = json.optString("code")

            if (code != "0000") {
                Log.w(TAG, "Non-zero code: $code")
                return MelonCheckResult.SessionExpired
            }

            val summaryArr = json.optJSONArray("summary")
                ?: return MelonCheckResult.Error("No summary array")

            var totalRemaining = 0
            val gradeDetails = mutableListOf<String>()
            for (i in 0 until summaryArr.length()) {
                val item = summaryArr.getJSONObject(i)
                val cnt = item.optInt("realSeatCntlk", 0)
                val gradeName = item.optString("seatGradeName", "").ifEmpty { "등급${i + 1}" }
                totalRemaining += cnt
                gradeDetails.add("$gradeName ${cnt}석")
            }

            Log.d(TAG, "grades: ${gradeDetails.joinToString(", ")}, total=$totalRemaining")

            if (totalRemaining > 0) MelonCheckResult.Available(totalRemaining, 0)
            else MelonCheckResult.NoTickets(gradeDetails.joinToString(", "))
        } catch (e: Exception) {
            Log.e(TAG, "Parse failed", e)
            MelonCheckResult.Error("Parse error: ${e.message}")
        }
    }
}
