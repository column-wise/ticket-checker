package com.ticketchecker.checker

import android.util.Log
import com.google.gson.JsonParser
import com.ticketchecker.model.MelonTarget
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class MelonCheckResult {
    data class Available(val rmdSeatCnt: Int, val chkResult: Int) : MelonCheckResult()
    object NoTickets : MelonCheckResult()
    data class Error(val message: String) : MelonCheckResult()
    object SessionExpired : MelonCheckResult()
}

class MelonChecker(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "MelonChecker"
        private const val API_URL = "https://ticket.melon.com/tktapi/product/seatStateInfo.json"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        private const val CALLBACK = "melonChecker"
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
                .add("scheduleNo", target.scheduleNo)
                .add("seatId", target.seatId)
                .add("volume", target.volume)
                .add("selectedGradeVolume", target.selectedGradeVolume)
                .build()

            val request = Request.Builder()
                .url(urlBuilder)
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Referer", "https://ticket.melon.com/reservation/popup/stepTicket.htm")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "*/*")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .apply {
                    if (cookieHeader.isNotEmpty()) {
                        header("Cookie", cookieHeader)
                    }
                }
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return MelonCheckResult.Error("Empty response")

            Log.d(TAG, "Response code: ${response.code}, body: ${body.take(200)}")

            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    return MelonCheckResult.SessionExpired
                }
                return MelonCheckResult.Error("HTTP ${response.code}")
            }

            if (body.contains("login", ignoreCase = true) || body.contains("로그인")) {
                return MelonCheckResult.SessionExpired
            }

            parseJsonpResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "Check failed", e)
            MelonCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun parseJsonpResponse(jsonp: String): MelonCheckResult {
        return try {
            // Extract JSON from JSONP: melonChecker({...})
            val jsonStart = jsonp.indexOf('(')
            val jsonEnd = jsonp.lastIndexOf(')')
            if (jsonStart == -1 || jsonEnd == -1 || jsonStart >= jsonEnd) {
                return MelonCheckResult.Error("Invalid JSONP format")
            }

            val json = jsonp.substring(jsonStart + 1, jsonEnd)
            val jsonObject = JsonParser.parseString(json).asJsonObject

            val chkResult = jsonObject.get("chkResult")?.asInt ?: 0
            val rmdSeatCnt = jsonObject.get("rmdSeatCnt")?.asInt ?: 0

            Log.d(TAG, "chkResult=$chkResult, rmdSeatCnt=$rmdSeatCnt")

            when {
                chkResult < 0 -> MelonCheckResult.SessionExpired
                rmdSeatCnt > 0 || chkResult > 0 -> MelonCheckResult.Available(rmdSeatCnt, chkResult)
                else -> MelonCheckResult.NoTickets
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSONP parse failed", e)
            MelonCheckResult.Error("Parse error: ${e.message}")
        }
    }
}
