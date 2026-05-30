package com.ticketchecker.checker

import android.util.Log
import com.ticketchecker.model.InterparkTarget
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class InterparkSeatGrade(
    val seatGradeName: String,
    val remainCnt: Int,
    val salesPrice: String
)

sealed class InterparkCheckResult {
    data class Available(val grades: List<InterparkSeatGrade>) : InterparkCheckResult()
    object NoTickets : InterparkCheckResult()
    data class Error(val message: String) : InterparkCheckResult()
    object SessionExpired : InterparkCheckResult()
}

class InterparkChecker(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "InterparkChecker"
        private const val API_URL = "https://poticket.interpark.com/Book/Lib/BookInfoXml.asp"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    }

    fun check(target: InterparkTarget): InterparkCheckResult {
        return try {
            val cookieHeader = target.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            val urlBuilder = API_URL.toHttpUrl().newBuilder()
                .addQueryParameter("Flag", "OrderSeatGrade")
                .addQueryParameter("GoodsCode", target.goodsCode)
                .addQueryParameter("PlaceCode", target.placeCode)
                .addQueryParameter("BizCode", target.bizCode)
                .addQueryParameter("PlaySeq", target.playSeq)
                .addQueryParameter("SessionId", target.sessionId)
                .addQueryParameter("InterlockingGoods", "")
                .build()

            val request = Request.Builder()
                .url(urlBuilder)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/xml, text/xml, */*")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .header("Referer", "https://poticket.interpark.com/")
                .apply {
                    if (cookieHeader.isNotEmpty()) {
                        header("Cookie", cookieHeader)
                    }
                }
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return InterparkCheckResult.Error("Empty response")

            Log.d(TAG, "Response code: ${response.code}, body length: ${body.length}")

            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    return InterparkCheckResult.SessionExpired
                }
                return InterparkCheckResult.Error("HTTP ${response.code}")
            }

            if (body.contains("로그인", ignoreCase = true) && body.contains("session", ignoreCase = true)) {
                return InterparkCheckResult.SessionExpired
            }

            parseXmlResponse(body, target.watchGrades)
        } catch (e: Exception) {
            Log.e(TAG, "Check failed", e)
            InterparkCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    /** API를 호출해 전체 좌석 등급명 목록을 반환합니다 (RemainCnt 무관). */
    fun fetchAllGradeNames(target: InterparkTarget): List<String> {
        return try {
            val cookieHeader = target.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val url = API_URL.toHttpUrl().newBuilder()
                .addQueryParameter("Flag", "OrderSeatGrade")
                .addQueryParameter("GoodsCode", target.goodsCode)
                .addQueryParameter("PlaceCode", target.placeCode)
                .addQueryParameter("BizCode", target.bizCode)
                .addQueryParameter("PlaySeq", target.playSeq)
                .addQueryParameter("SessionId", target.sessionId)
                .addQueryParameter("InterlockingGoods", "")
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/xml, text/xml, */*")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .header("Referer", "https://poticket.interpark.com/")
                .apply { if (cookieHeader.isNotEmpty()) header("Cookie", cookieHeader) }
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            parseAllGradeNames(body)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllGradeNames failed", e)
            emptyList()
        }
    }

    private fun parseAllGradeNames(xml: String): List<String> {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val names = mutableListOf<String>()
            var inTable = false
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "Table") inTable = true
                    }
                    XmlPullParser.TEXT -> {
                        if (inTable && currentTag == "SeatGradeName") {
                            val name = parser.text.trim()
                            if (name.isNotEmpty()) names.add(name)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "Table") { inTable = false; currentTag = "" }
                    }
                }
                eventType = parser.next()
            }
            names
        } catch (e: Exception) {
            Log.e(TAG, "parseAllGradeNames failed", e)
            emptyList()
        }
    }

    private fun parseXmlResponse(xml: String, watchGrades: List<String>): InterparkCheckResult {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val grades = mutableListOf<InterparkSeatGrade>()
            var inTable = false
            var seatGradeName = ""
            var remainCnt = 0
            var salesPrice = ""
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag == "Table") {
                            inTable = true
                            seatGradeName = ""
                            remainCnt = 0
                            salesPrice = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inTable) {
                            val text = parser.text.trim()
                            when (currentTag) {
                                "SeatGradeName" -> seatGradeName = text
                                "RemainCnt" -> remainCnt = text.toIntOrNull() ?: 0
                                "SalesPrice" -> salesPrice = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "Table" && inTable) {
                            inTable = false
                            Log.d(TAG, "등급: $seatGradeName | 잔여: ${remainCnt}석 | 가격: ${salesPrice}원")
                            if (remainCnt > 0) {
                                val gradeMatch = watchGrades.isEmpty() ||
                                        watchGrades.any { it.equals(seatGradeName, ignoreCase = true) }
                                if (gradeMatch) {
                                    grades.add(
                                        InterparkSeatGrade(
                                            seatGradeName = seatGradeName,
                                            remainCnt = remainCnt,
                                            salesPrice = salesPrice
                                        )
                                    )
                                }
                            }
                            currentTag = ""
                        }
                    }
                }
                eventType = parser.next()
            }

            if (grades.isNotEmpty()) {
                InterparkCheckResult.Available(grades)
            } else {
                InterparkCheckResult.NoTickets
            }
        } catch (e: Exception) {
            Log.e(TAG, "XML parse failed", e)
            InterparkCheckResult.Error("XML parse error: ${e.message}")
        }
    }
}
