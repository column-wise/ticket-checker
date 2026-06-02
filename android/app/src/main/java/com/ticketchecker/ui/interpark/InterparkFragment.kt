package com.ticketchecker.ui.interpark

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ticketchecker.checker.InterparkChecker
import com.ticketchecker.databinding.FragmentWebviewBinding
import com.ticketchecker.model.InterparkTarget
import com.ticketchecker.storage.TargetStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class InterparkFragment : Fragment() {

    companion object {
        private const val TAG = "InterparkFragment"
        private const val INTERPARK_URL = "https://tickets.interpark.com/"
        private const val SESSION_TRIGGER_PATH = "BookInfoXml.asp"
        private const val SESSION_TRIGGER_FLAG = "Flag=OrderSeatGrade"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }

    private var _binding: FragmentWebviewBinding? = null
    private val binding get() = _binding!!
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var storage: TargetStorage
    private lateinit var backCallback: OnBackPressedCallback
    private var mobileUA = ""
    private var popupDialog: Dialog? = null
    private var popupWebView: WebView? = null
    private var yanoljaRedirectUrl: String? = null
    private var lastConcertTitle: String = ""
    private var lastConcertStartDate: String = ""
    private var capturedInterparkPlayDate: String = ""
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val interparkChecker = lazy { InterparkChecker(okHttpClient) }

    inner class InterparkJsInterface {
        @JavascriptInterface
        fun onConcertInfoCaptured(name: String, startDate: String) {
            if (name.isBlank()) return
            Log.d(TAG, "JS concert captured: name=$name, startDate=$startDate")
            mainHandler.post {
                lastConcertTitle = name
                // normalize "2026-10-07..." → "20261007"
                val normalized = if (startDate.length >= 10 && startDate[4] == '-')
                    startDate.replace("-", "").take(8)
                else startDate
                if (normalized.matches(Regex("\\d{8}"))) lastConcertStartDate = normalized
            }
        }
    }

    private fun injectConcertCapture(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                var curUrl = location.href;
                if (window.__ipCapturedUrl === curUrl) return;
                function extractFromRaw(raw) {
                    // JSON.parse 실패 시 (Interpark malformed JSON) regex로 추출
                    var nm = raw.match(/"name"\s*:\s*"((?:[^"\\]|\\.)*)"/);
                    var dm = raw.match(/"startDate"\s*:\s*"(\d{8})"/);
                    return { name: nm ? nm[1] : '', startDate: dm ? dm[1] : '' };
                }
                function tryCapture() {
                    var scripts = document.querySelectorAll('script[type="application/ld+json"]');
                    for (var i = 0; i < scripts.length; i++) {
                        var raw = scripts[i].textContent;
                        if (raw.indexOf('"Event"') < 0) continue;
                        var name = '', sd = '';
                        try {
                            var d = JSON.parse(raw);
                            if (d['@type'] === 'Event' && d.name) {
                                name = d.name;
                                sd = d.startDate || '';
                            }
                        } catch(e) {
                            var r = extractFromRaw(raw);
                            name = r.name; sd = r.startDate;
                        }
                        if (!name) continue;
                        window.__ipCapturedUrl = curUrl;
                        if (sd.length >= 10 && sd[4] === '-') sd = sd.replace(/-/g,'').substring(0,8);
                        InterparkAndroid.onConcertInfoCaptured(name, sd);
                        return true;
                    }
                    return false;
                }
                if (!tryCapture()) {
                    var n = 0;
                    var t = setInterval(function() {
                        if (tryCapture() || ++n > 30) clearInterval(t);
                    }, 300);
                }
            })();
        """.trimIndent(), null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWebviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = TargetStorage.getInstance(requireContext())
        setupBackCallback()
        setupWebView()
    }

    private fun setupBackCallback() {
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                binding.webView.goBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        backCallback.isEnabled = !hidden && binding.webView.canGoBack()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView

        mobileUA = webView.settings.userAgentString

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(InterparkJsInterface(), "InterparkAndroid")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                Log.d(TAG, "onCreateWindow called: isDialog=$isDialog, isUserGesture=$isUserGesture")
                return showPopupWebView(resultMsg)
            }

            override fun onCloseWindow(window: WebView) {
                Log.d(TAG, "onCloseWindow called")
                dismissPopup()
            }

            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                Log.d(TAG, "JS [${message.messageLevel()}] ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""

                if (scheme == "http" || scheme == "https") {
                    val host = request.url.host ?: ""
                    val path = request.url.path ?: ""
                    Log.d(TAG, "navigate: $host$path")
                    // Yanolja 로그인 redirect URL 저장 (login-and-service-link 후 즉시 이동용)
                    if (host.contains("accounts.yanolja.com")) {
                        request.url.getQueryParameter("redirect")?.let { yanoljaRedirectUrl = it }
                    }
                    // 예매 도메인(poticket)만 데스크탑 UA — 세션 캡처 + Naver 로그인 콜백 정상화
                    val needsDesktop = host.contains("poticket.interpark.com")
                    val isDesktop = view.settings.userAgentString == DESKTOP_UA
                    if (needsDesktop != isDesktop) {
                        view.settings.userAgentString = if (needsDesktop) DESKTOP_UA else mobileUA
                        view.loadUrl(url)
                        return true
                    }
                    return false
                }

                Log.d(TAG, "Non-http scheme: $scheme url=$url")
                return try {
                    if (scheme == "intent") {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        val dataScheme = intent.data?.scheme
                        // intent:// 안에 http/https URL → WebView에서 직접 로드
                        if (dataScheme == "https" || dataScheme == "http") {
                            view.loadUrl(intent.data.toString())
                            return true
                        }
                        // browser_fallback_url 있으면 앱 대신 WebView에서 로드
                        val fallback = intent.getStringExtra("browser_fallback_url")
                        if (fallback != null) {
                            Log.d(TAG, "Using browser_fallback_url: $fallback")
                            view.loadUrl(fallback)
                            return true
                        }
                        // 앱 딥링크 (네이버 앱 등) → 외부 앱으로
                        if (intent.resolveActivity(requireContext().packageManager) != null) {
                            startActivity(intent)
                        }
                    } else if (scheme == "about" || scheme == "javascript") {
                        // about:blank#blocked 등 WebView 내부 URL — 브라우저 열림 방지
                    } else {
                        // naver://, kakao:// 등
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    }
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No app to handle scheme: $scheme")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "shouldOverrideUrlLoading error", e)
                    true
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                // 신규 예매 시스템: motickets.interpark.com → api-onestop-front 세션 감지
                if (url.contains("api-onestop-front.interpark.com") &&
                    url.contains("/seats/") && url.contains("/init?")) {
                    Log.d(TAG, "New session trigger: $url")
                    mainHandler.post { handleInterparkSessionNew(url) }
                }

                // 유저가 선택한 날짜 캡처: /playseq/{goodsCode}/{placeCode}?playDate=YYYYMMDD
                if (url.contains("api-onestop-front.interpark.com") &&
                    url.contains("/playseq/") && url.contains("playDate=")) {
                    val pd = Uri.parse(url).getQueryParameter("playDate") ?: ""
                    if (pd.matches(Regex("\\d{8}"))) {
                        Log.d(TAG, "Interpark playDate captured: $pd")
                        mainHandler.post { capturedInterparkPlayDate = pd }
                    }
                }

                // 구 예매 시스템(poticket): BookInfoXml.asp 세션 감지 (하위 호환)
                if (url.contains(SESSION_TRIGGER_PATH) && url.contains(SESSION_TRIGGER_FLAG)) {
                    Log.d(TAG, "Legacy session trigger: $url")
                    mainHandler.post { handleInterparkSession(url, view) }
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                backCallback.isEnabled = view.canGoBack()
                // login-and-service-link 로드 완료 → 저장된 redirect URL로 즉시 이동
                if (url.contains("login-and-service-link")) {
                    val redirect = yanoljaRedirectUrl
                    if (redirect != null) {
                        Log.d(TAG, "login-and-service-link done, skipping wait → $redirect")
                        view.loadUrl(redirect)
                    }
                }
                // 인터파크 공연 상세 페이지 → React 렌더 후 JSON-LD 폴링으로 공연명/날짜 캡처
                if (url.contains("tickets.interpark.com") || url.contains("ticket.interpark.com") ||
                    url.contains("mobileticket.interpark.com")) {
                    injectConcertCapture(view)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // Next.js SPA 내비게이션(pushState) 감지 — onPageFinished는 SPA 이동 시 안 불림
                if (!isReload && (url.contains("tickets.interpark.com") || url.contains("ticket.interpark.com") ||
                        url.contains("mobileticket.interpark.com"))) {
                    injectConcertCapture(view)
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }
        }

        webView.loadUrl(INTERPARK_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPopupWebView(resultMsg: Message): Boolean {
        val ctx = context ?: return false

        val popup = WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = mobileUA
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val scheme = request.url.scheme ?: ""
                    if (scheme == "http" || scheme == "https") return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        true
                    } catch (e: Exception) { true }
                }
            }
        }

        popupWebView = popup

        val dialog = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(popup)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (popup.canGoBack()) popup.goBack() else dismiss()
                    true
                } else false
            }
            setOnDismissListener {
                popupWebView?.destroy()
                popupWebView = null
                popupDialog = null
            }
        }
        popupDialog = dialog
        dialog.show()

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun dismissPopup() {
        popupDialog?.dismiss()
    }

    private fun handleInterparkSessionNew(url: String) {
        try {
            val uri = Uri.parse(url)
            // path: /onestop/v1/seats/{goodsCode}/init
            val segments = uri.pathSegments
            val seatsIdx = segments.indexOf("seats")
            val goodsCode = if (seatsIdx >= 0 && seatsIdx + 1 < segments.size)
                segments[seatsIdx + 1] else return

            val placeCode = uri.getQueryParameter("placeCode") ?: ""
            val bizCode = uri.getQueryParameter("bizCode") ?: ""
            val playSeq = uri.getQueryParameter("playSeq") ?: ""
            val sessionId = uri.getQueryParameter("sessionId") ?: return

            val cookieString = CookieManager.getInstance()
                .getCookie("https://motickets.interpark.com/") ?: ""
            val allCookies = parseCookies(cookieString)

            val existing = storage.loadInterparkTarget()
            val isSameConcert = existing?.goodsCode == goodsCode

            // 우선순위: 이전 공연 상세 페이지 타이틀 → 현재 페이지 타이틀 → 기존 저장값
            val titleFromPage = binding.webView.title
                ?.replace(Regex("\\s*[-–|]\\s*(인터파크|NOL).*$", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("NOL 티켓", ignoreCase = true) }
            val extractedName = lastConcertTitle.takeIf { it.isNotEmpty() }
                ?: titleFromPage

            // 우선순위: 유저가 선택한 날짜(playseq API) > JSON-LD startDate > 기존 저장값
            val resolvedDate = capturedInterparkPlayDate.takeIf { it.isNotEmpty() }
                ?: lastConcertStartDate.takeIf { it.isNotEmpty() }
                ?: if (isSameConcert) existing?.playDate ?: "" else ""

            val target = InterparkTarget(
                goodsCode = goodsCode,
                placeCode = placeCode,
                bizCode = bizCode,
                playSeq = playSeq,
                sessionId = sessionId,
                cookies = allCookies,
                goodsName = extractedName
                    ?: if (isSameConcert) existing?.goodsName ?: "" else "",
                playDate = resolvedDate,
                watchGrades = if (isSameConcert) existing?.watchGrades ?: emptyList() else emptyList()
            )

            storage.saveInterparkTarget(target)
            capturedInterparkPlayDate = ""
            lastConcertTitle = ""
            lastConcertStartDate = ""
            Log.i(TAG, "Interpark target saved (new): goodsCode=$goodsCode, name=${target.goodsName}, date=${target.playDate}, sessionId=${sessionId.take(10)}...")
            Toast.makeText(requireContext(), "인터파크 세션 감지됨 ✓", Toast.LENGTH_LONG).show()
            fetchGoodsInfo(goodsCode)
            fetchAndUpdateConcertInfo(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract interpark session (new)", e)
        }
    }

    private fun handleInterparkSession(url: String, webView: WebView) {
        try {
            val uri = Uri.parse(url)

            val goodsCode = uri.getQueryParameter("GoodsCode") ?: return
            val placeCode = uri.getQueryParameter("PlaceCode") ?: ""
            val bizCode = uri.getQueryParameter("BizCode") ?: ""
            val playSeq = uri.getQueryParameter("PlaySeq") ?: ""
            val sessionId = uri.getQueryParameter("SessionId") ?: return

            // Extract cookies from CookieManager
            val cookieString = CookieManager.getInstance().getCookie(url) ?: ""
            val allCookies = parseCookies(cookieString)

            // Also try to get cookies from the base domain
            val baseCookieString = CookieManager.getInstance().getCookie("https://poticket.interpark.com/") ?: ""
            val baseCookies = parseCookies(baseCookieString)
            allCookies.putAll(baseCookies)

            val interestCookieKeys = listOf("pcid", "interparkstamp", "ECCS", "CAPTGM", "ent_token")
            val filteredCookies = allCookies.filter { it.key in interestCookieKeys }
                .toMutableMap()

            // If filtered is empty, include all cookies anyway
            val finalCookies = if (filteredCookies.isNotEmpty()) filteredCookies else allCookies

            // 같은 공연이면 기존 공연명·날짜·watchGrades 유지
            val existing = storage.loadInterparkTarget()
            val isSameConcert = existing?.goodsCode == goodsCode

            val titleFromPage = binding.webView.title
                ?.replace(Regex("\\s*[-–|]\\s*(인터파크|NOL).*$", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("NOL 티켓", ignoreCase = true) }
            val extractedName = lastConcertTitle.takeIf { it.isNotEmpty() }
                ?: titleFromPage

            val resolvedDate = capturedInterparkPlayDate.takeIf { it.isNotEmpty() }
                ?: lastConcertStartDate.takeIf { it.isNotEmpty() }
                ?: if (isSameConcert) existing?.playDate ?: "" else ""

            val target = InterparkTarget(
                goodsCode = goodsCode,
                placeCode = placeCode,
                bizCode = bizCode,
                playSeq = playSeq,
                sessionId = sessionId,
                cookies = finalCookies,
                goodsName = extractedName
                    ?: if (isSameConcert) existing?.goodsName ?: "" else "",
                playDate = resolvedDate,
                watchGrades = if (isSameConcert) existing?.watchGrades ?: emptyList() else emptyList()
            )

            storage.saveInterparkTarget(target)
            capturedInterparkPlayDate = ""
            lastConcertTitle = ""
            lastConcertStartDate = ""
            Log.i(TAG, "Interpark target saved: goodsCode=$goodsCode, name=${target.goodsName}, date=${target.playDate}, sessionId=${sessionId.take(10)}...")
            Toast.makeText(requireContext(), "인터파크 세션 감지됨 ✓", Toast.LENGTH_LONG).show()
            fetchGoodsInfo(goodsCode)
            fetchAndUpdateConcertInfo(target)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract interpark session", e)
        }
    }

    private fun fetchAndUpdateConcertInfo(target: InterparkTarget) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val info = interparkChecker.value.fetchConcertInfo(target) ?: return@launch
            val updated = target.copy(
                goodsName = info.first.ifEmpty { target.goodsName },
                playDate = info.second.ifEmpty { target.playDate }
            )
            if (updated.goodsName == target.goodsName && updated.playDate == target.playDate) return@launch
            storage.saveInterparkTarget(updated)
            withContext(Dispatchers.Main) {
                Log.i(TAG, "Interpark concert info updated: '${updated.goodsName}' / ${updated.playDate}")
            }
        }
    }

    /** 공연 상세 페이지 HTML에서 Event JSON-LD를 파싱해 공연명/날짜 업데이트 */
    private fun fetchGoodsInfo(goodsCode: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // mobileticket은 SSR로 JSON-LD 포함, tickets은 CSR이라 없을 수 있음
                val urls = listOf(
                    "https://mobileticket.interpark.com/goods/$goodsCode",
                    "https://tickets.interpark.com/goods/$goodsCode"
                )
                for (url in urls) {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", DESKTOP_UA)
                        .header("Accept-Language", "ko-KR,ko;q=0.9")
                        .build()
                    val html = okHttpClient.newCall(request).execute().body?.string() ?: continue
                    val result = extractEventInfoFromHtml(html) ?: continue
                    val (name, startDate) = result
                    Log.i(TAG, "fetchGoodsInfo [$url]: name=$name, startDate=$startDate")
                    withContext(Dispatchers.Main) {
                        val cur = storage.loadInterparkTarget() ?: return@withContext
                        if (cur.goodsCode != goodsCode) return@withContext
                        val updatedDate = if (cur.playDate.isEmpty() && startDate.matches(Regex("\\d{8}")))
                            startDate else cur.playDate
                        if (name == cur.goodsName && updatedDate == cur.playDate) return@withContext
                        storage.saveInterparkTarget(cur.copy(goodsName = name, playDate = updatedDate))
                        Log.i(TAG, "Goods info applied: name=$name, date=$updatedDate")
                    }
                    return@launch
                }
                Log.d(TAG, "fetchGoodsInfo: no Event JSON-LD found for $goodsCode")
            } catch (e: Exception) {
                Log.e(TAG, "fetchGoodsInfo failed", e)
            }
        }
    }

    private fun extractEventInfoFromHtml(html: String): Pair<String, String>? {
        val ldJsonRegex = Regex(
            """<script[^>]+type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE
        )
        for (match in ldJsonRegex.findAll(html)) {
            val raw = match.groupValues[1].trim()
            if (!raw.contains("\"Event\"")) continue
            var name = ""
            var startDate = ""
            try {
                val json = org.json.JSONObject(raw)
                if (json.optString("@type") == "Event") {
                    name = json.optString("name")
                    startDate = json.optString("startDate")
                }
            } catch (e: Exception) {
                // Interpark JSON-LD가 malformed인 경우 (organizer.name에 줄바꿈, image 이중따옴표)
                val nm = Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(raw)
                if (nm != null) name = nm.groupValues[1]
                val dm = Regex(""""startDate"\s*:\s*"(\d{8})"""").find(raw)
                if (dm != null) startDate = dm.groupValues[1]
            }
            if (name.isEmpty()) continue
            if (startDate.length >= 10 && startDate[4] == '-') {
                startDate = startDate.replace("-", "").take(8)
            }
            return Pair(name, startDate)
        }
        return null
    }

    private fun parseCookies(cookieString: String): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        if (cookieString.isBlank()) return map
        cookieString.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val key = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                map[key] = value
            }
        }
        return map
    }

    override fun onDestroyView() {
        dismissPopup()
        binding.webView.destroy()
        _binding = null
        super.onDestroyView()
    }
}
