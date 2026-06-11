package com.ticketchecker.ui.melon

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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ticketchecker.databinding.FragmentWebviewBinding
import com.ticketchecker.model.MelonTarget
import com.ticketchecker.storage.TargetStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MelonFragment : Fragment() {

    companion object {
        private const val TAG = "MelonFragment"
        private const val MELON_URL = "https://ticket.melon.com/main/index.htm"
        private const val JS_INTERFACE_NAME = "MelonAndroid"
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
    private var capturedPerfDay = ""

    inner class MelonJsInterface {

        @JavascriptInterface
        fun onSeatStateRequest(dataJson: String) {
            Log.d(TAG, "seatStateInfo request intercepted: $dataJson")
            mainHandler.post {
                handleMelonSession(dataJson)
            }
        }

        @JavascriptInterface
        fun onSeatGradeInfo(seatGradeNo: String) {
            Log.d(TAG, "seatGradeNo received: $seatGradeNo")
            mainHandler.post { updateMelonSeatId(seatGradeNo) }
        }

        @JavascriptInterface
        fun onSeatGradeNameCaptured(gradeName: String) {
            if (gradeName.isNotBlank()) {
                Log.d(TAG, "seatGradeName captured: $gradeName")
                mainHandler.post { updateMelonSeatGradeName(gradeName) }
            }
        }

        @JavascriptInterface
        fun onPerfDayCaptured(perfDay: String) {
            if (perfDay.isNotBlank()) {
                Log.d(TAG, "perfDay captured: $perfDay")
                capturedPerfDay = perfDay
                // 이미 세션이 저장된 경우에도 날짜 업데이트 (response 파싱이 request 이후에 오므로)
                mainHandler.post { updateMelonPlayDate(perfDay) }
            }
        }

        @JavascriptInterface
        fun onProductNameCaptured(name: String) {
            if (name.isNotBlank()) {
                Log.d(TAG, "perfMainName captured: $name")
                mainHandler.post { updateMelonName(name) }
            }
        }
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

        mobileUA = webView.settings.userAgentString.replace("; wv", "")

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

        webView.addJavascriptInterface(MelonJsInterface(), JS_INTERFACE_NAME)

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

            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (url.contains("kakao") || url.contains("melon.com") || url.contains("melon.co.kr")) {
                    Log.d(TAG, "req: $url")
                }
                // Kotlin 레벨에서 perfDay 캡처 — ticket.melon.com, tktapi.melon.com 모두 대응
                if (url.contains("melon.com")) {
                    val perfDay = request.url.getQueryParameter("perfDay")
                    if (!perfDay.isNullOrEmpty() && perfDay.matches(Regex("\\d{8}"))) {
                        mainHandler.post {
                            if (capturedPerfDay != perfDay) {
                                capturedPerfDay = perfDay
                                Log.d(TAG, "perfDay captured from request URL: $perfDay")
                            }
                        }
                    }
                }
                // logout 401 → OAuth 흐름 중단 우회: 항상 200 반환
                if (url.contains("kapi.kakao.com/v1/user/logout")) {
                    val origin = request.requestHeaders["Origin"]
                        ?: request.requestHeaders["Referer"]?.let { Uri.parse(it).run { "$scheme://$host" } }
                        ?: "https://accounts.melon.com"
                    return WebResourceResponse(
                        "application/json", "UTF-8", 200, "OK",
                        mapOf(
                            "Access-Control-Allow-Origin" to origin,
                            "Access-Control-Allow-Credentials" to "true"
                        ),
                        ByteArrayInputStream("{}".toByteArray())
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    Log.d(TAG, "navigate: ${request.url.host}${request.url.path}")
                    return false
                }

                Log.d(TAG, "Non-http scheme: $scheme url=${request.url}")
                return try {
                    if (scheme == "intent") {
                        handleIntentScheme(view, request.url.toString())
                    } else if (scheme == "about" || scheme == "javascript") {
                        // 무시
                    } else {
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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                backCallback.isEnabled = view.canGoBack()
                injectXhrInterceptor(view)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                // 페이지 스크립트보다 먼저 주입 → fetch/XHR 캐싱 전에 가로채기
                if (!url.startsWith("about:") &&
                    (url.contains("ticket.melon.com") || url.contains("melon.com"))) {
                    injectXhrInterceptor(view)
                }
            }
        }

        webView.loadUrl(MELON_URL)
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
                        if (scheme == "intent") {
                            handleIntentScheme(view, request.url.toString())
                        } else if (scheme != "about" && scheme != "javascript") {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        }
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

    private fun injectXhrInterceptor(webView: WebView) {
        val js = """
            (function() {
                // XHR/fetch 인터셉터를 주어진 window(메인/iframe 모두)에 설치
                function injectXhrIfNeeded(win) {
                    if (!win || win.__melonXhrInjected) return;
                    win.__melonXhrInjected = true;

                    // ── XHR prototype 패치 ──────────────────────────────────
                    // 생성자 교체 대신 prototype 패치: injection 전에 생성된
                    // XHR 인스턴스도 소급 적용됨 (pre-captured reference 문제 해결)
                    try {
                        var xhrProto = win.XMLHttpRequest.prototype;
                        var origOpen = xhrProto.open;
                        var origSend = xhrProto.send;

                        xhrProto.open = function(method, url) {
                            this.__mUrl = url;
                            this.__mMethod = method ? method.toUpperCase() : 'GET';
                            return origOpen.apply(this, arguments);
                        };

                        xhrProto.send = function(body) {
                            var url = this.__mUrl || '';
                            var method = this.__mMethod || 'GET';

                            if (url.length > 0) {
                                console.log('[MelonChecker] XHR* ' + method + ': ' + url.substring(0, 120) + (body && typeof body === 'string' ? ' body=' + body.substring(0, 150) : ''));
                            }

                            // perfDay 캡처: gradelist.json / timelist.json URL 또는 POST body에서 추출
                            if (url.indexOf('gradelist.json') >= 0 || url.indexOf('timelist.json') >= 0 ||
                                url.indexOf('getPerfSch.json') >= 0 || url.indexOf('getScheduleList.json') >= 0) {
                                var pdM = url.match(/[?&]perfDay=(\d{8})/);
                                if (!pdM && body && typeof body === 'string') {
                                    pdM = body.match(/perfDay=(\d{8})/);
                                }
                                if (pdM) {
                                    try { window.MelonAndroid.onPerfDayCaptured(pdM[1]); } catch(e) {}
                                }
                                // 응답에서도 날짜 추출 시도
                                this.addEventListener('load', function() {
                                    try {
                                        var resp = JSON.parse(this.responseText);
                                        var pd = (resp.perfDay || resp.perfDate || resp.playDate);
                                        if (pd && /^\d{8}$/.test(String(pd))) {
                                            window.MelonAndroid.onPerfDayCaptured(String(pd));
                                        }
                                    } catch(e) {}
                                });
                            }

                            // onestop.htm(메인 프레임)에서 호출됨 → iframe 타이밍 레이스 없음
                            if (url.indexOf('informProdSch.json') >= 0 && method === 'POST') {
                                try {
                                    var params = {};
                                    if (body && typeof body === 'string') {
                                        body.split('&').forEach(function(pair) {
                                            var parts = pair.split('=');
                                            if (parts.length === 2) {
                                                params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1]);
                                            }
                                        });
                                    }
                                    window.MelonAndroid.onSeatStateRequest(JSON.stringify(params));
                                } catch(e) {
                                    console.error('Melon XHR session error:', e);
                                }
                            }

                            if (url.indexOf('informProdSch.json') >= 0) {
                                this.addEventListener('load', function() {
                                    try {
                                        var d = JSON.parse(this.responseText);
                                        var pi = d.prodInform;
                                        if (pi) {
                                            var n = pi.perfMainName;
                                            if (n) window.MelonAndroid.onProductNameCaptured(n);
                                            // 날짜 필드 — perfStartDay 확인됨 (2026-06-02 로그 기준)
                                            var perfDate = pi.perfStartDay || pi.perfDay || pi.perfDate || pi.playDate || pi.perfDt || pi.schDate || pi.scheduleDate;
                                            if (perfDate) {
                                                var ds = String(perfDate).replace(/\D/g,'').substring(0,8);
                                                if (ds.length === 8) window.MelonAndroid.onPerfDayCaptured(ds);
                                            }
                                            console.log('[MelonChecker] prodInform keys: ' + Object.keys(pi).join(', '));
                                        }
                                    } catch(e) {}
                                    console.log('[MelonChecker] resp informProdSch.json: ' + this.responseText.substring(0, 500));
                                });
                            }

                            if (url.indexOf('seatMapList.json') >= 0) {
                                this.addEventListener('load', function() {
                                    console.log('[MelonChecker] resp seatMapList.json: ' + this.responseText.substring(0, 300));
                                });
                            }

                            // 공연 날짜 정보 API — 응답 구조 파악용 로그
                            if (url.indexOf('prodByEventInfo.json') >= 0 || url.indexOf('timelist.json') >= 0 || url.indexOf('gradelist.json') >= 0) {
                                this.addEventListener('load', function() {
                                    console.log('[MelonChecker] DATE-API resp (' + url.split('/').pop().split('?')[0] + '): ' + this.responseText.substring(0, 600));
                                });
                            }

                            if (url.indexOf('summary.json') >= 0) {
                                this.addEventListener('load', function() {
                                    try {
                                        var m = this.responseText.match(/\((\{[\s\S]*\})\)/);
                                        if (m) {
                                            var d = JSON.parse(m[1]);
                                            if (d.summary && d.summary.length > 0) {
                                                var s = d.summary[0];
                                                if (s.seatGradeNo) window.MelonAndroid.onSeatGradeInfo(String(s.seatGradeNo));
                                                var gn = s.seatGradeName || s.gradeName || s.gradeNm || s.gradeNameKo;
                                                if (gn) window.MelonAndroid.onSeatGradeNameCaptured(String(gn));
                                            }
                                        }
                                    } catch(e) {
                                        console.error('[MelonChecker] summary parse error:', e);
                                    }
                                    console.log('[MelonChecker] resp summary.json: ' + this.responseText.substring(0, 500));
                                });
                            }

                            return origSend.apply(this, arguments);
                        };
                    } catch(e) {
                        console.log('[MelonChecker] XHR proto patch error: ' + e);
                    }

                    // ── fetch 교체 ──────────────────────────────────────────
                    // onPageStarted에서 조기 주입하므로 pre-capture 전에 교체 가능
                    try {
                        var origFetch = win.fetch;
                        if (origFetch) {
                            win.fetch = function(resource, init) {
                                var url = '';
                                if (typeof resource === 'string') {
                                    url = resource;
                                } else if (resource && typeof resource.url === 'string') {
                                    url = resource.url;
                                } else if (resource && typeof resource.href === 'string') {
                                    url = resource.href;
                                } else {
                                    try { url = String(resource); } catch(e2) {}
                                }
                                var method = (init && init.method) ? init.method.toUpperCase()
                                           : (resource && resource.method ? resource.method.toUpperCase() : 'GET');
                                var body = (init && init.body) ? init.body
                                         : (resource && resource.body ? resource.body : '');

                                if (url.length > 0) {
                                    console.log('[MelonChecker] fetch* ' + method + ': ' + url.substring(0, 120));
                                }

                                if (url.indexOf('informProdSch.json') >= 0 && method === 'POST') {
                                    try {
                                        var fParams = {};
                                        var bStr = typeof body === 'string' ? body : '';
                                        bStr.split('&').forEach(function(pair) {
                                            var p = pair.split('=');
                                            if (p.length === 2) fParams[decodeURIComponent(p[0])] = decodeURIComponent(p[1]);
                                        });
                                        window.MelonAndroid.onSeatStateRequest(JSON.stringify(fParams));
                                    } catch(e) {
                                        console.error('Melon fetch session error:', e);
                                    }
                                }
                                return origFetch.apply(this, arguments);
                            };
                        }
                    } catch(e) {
                        console.log('[MelonChecker] fetch patch error: ' + e);
                    }

                    console.log('[MelonChecker] injected: ' + win.location.pathname);

                    // 부모 프레임에도 전파 (child에서 parent 방향)
                    try {
                        if (win.parent && win.parent !== win) injectXhrIfNeeded(win.parent);
                    } catch(e) {}

                    // 현재 iframe 요소에 load 리스너 추가 (src 교체 시에도 재주입)
                    try {
                        win.document.querySelectorAll('iframe').forEach(function(iframe) {
                            iframe.addEventListener('load', function() {
                                try { injectXhrIfNeeded(iframe.contentWindow); } catch(e) {}
                            });
                            try { injectXhrIfNeeded(iframe.contentWindow); } catch(e) {}
                        });
                    } catch(e) {}

                    // 새로 추가되는 iframe 감지 → load 리스너 부착 후 주입
                    try {
                        var obs = new win.MutationObserver(function(mutations) {
                            mutations.forEach(function(mutation) {
                                mutation.addedNodes.forEach(function(node) {
                                    if (node.nodeName !== 'IFRAME') return;
                                    node.addEventListener('load', function() {
                                        try { injectXhrIfNeeded(node.contentWindow); } catch(e) {}
                                    });
                                    try { injectXhrIfNeeded(node.contentWindow); } catch(e) {}
                                });
                            });
                        });
                        obs.observe(win.document.documentElement, { childList: true, subtree: true });
                    } catch(e) {}

                    // 폴링: load 이벤트보다 일찍 iframe이 바뀌는 경우 대비 (100ms마다 체크)
                    // seatStateInfo.json은 stepSeat 로드 후 ~600ms에 호출되므로 충분한 여유
                    try {
                        var pollCount = 0;
                        var pollInterval = setInterval(function() {
                            pollCount++;
                            if (pollCount > 300) { clearInterval(pollInterval); return; }
                            try {
                                win.document.querySelectorAll('iframe').forEach(function(iframe) {
                                    try {
                                        var w = iframe.contentWindow;
                                        if (w && !w.__melonXhrInjected) injectXhrIfNeeded(w);
                                    } catch(e) {}
                                });
                            } catch(e) {}
                        }, 100);
                    } catch(e) {}
                }

                // 메인 프레임 주입
                injectXhrIfNeeded(window);

                // --- 메인 프레임 전용 설정 ---

                // window.open 로깅
                var origOpen = window.open;
                window.open = function(url, name, features) {
                    console.log('[MelonChecker] window.open: ' + url);
                    return origOpen.apply(window, arguments);
                };

                // Chrome API stub — WebView 감지 우회
                try {
                    if (!window.chrome) window.chrome = {};
                    if (!window.chrome.runtime) window.chrome.runtime = {};
                } catch(e) {}

                // Kakao SDK polling 패치
                var kakaoCheckCount = 0;
                var kakaoCheckInterval = setInterval(function() {
                    kakaoCheckCount++;
                    if (kakaoCheckCount > 50) { clearInterval(kakaoCheckInterval); return; }
                    if (!window.Kakao) return;
                    clearInterval(kakaoCheckInterval);
                    console.log('[MelonChecker] Kakao SDK found at check #' + kakaoCheckCount);
                    try {
                        if (window.Kakao._env) {
                            console.log('[MelonChecker] Kakao._env: ' + JSON.stringify(window.Kakao._env));
                            window.Kakao._env.inApp = false;
                        }
                        if (window.Kakao.Auth) {
                            var origAuthorize = window.Kakao.Auth.authorize;
                            window.Kakao.Auth.authorize = function(obj) {
                                console.log('[MelonChecker] Kakao.Auth.authorize called: ' + JSON.stringify(obj));
                                return origAuthorize.apply(this, arguments);
                            };
                        }
                    } catch(e) {
                        console.log('[MelonChecker] Kakao patch error: ' + e);
                    }
                }, 100);

            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun handleMelonSession(dataJson: String) {
        try {
            val data = JSONObject(dataJson)

            val prodId = data.optString("prodId").ifEmpty { return }
            val scheduleNo = data.optString("scheduleNo")
            val seatId = data.optString("seatId")
            val pocCode = data.optString("pocCode")
            val volume = data.optString("volume").ifEmpty { "1" }
            val selectedGradeVolume = data.optString("selectedGradeVolume").ifEmpty { "1" }

            // POST 파라미터에 perfDate/perfDay가 있으면 우선 사용, 없으면 capturedPerfDay(URL 캡처값) 사용
            val datePattern = Regex("\\d{8}")
            val playDate = listOf("perfDate", "perfDay", "playDate", "perfDt", "schDate")
                .mapNotNull { data.optString(it).takeIf { s -> s.matches(datePattern) } }
                .firstOrNull() ?: capturedPerfDay
            Log.d(TAG, "Session date: capturedPerfDay=$capturedPerfDay, fromParams=$playDate")

            // Extract cookies
            val cookieString = CookieManager.getInstance()
                .getCookie("https://ticket.melon.com/") ?: ""
            val allCookies = parseCookies(cookieString)

            val interestCookieKeys = listOf("PCID", "JSESSIONID", "keyCookie", "TKT_POC_ID", "NetFunnel_ID")
            val filteredCookies = allCookies.filter { it.key in interestCookieKeys }.toMutableMap()
            val finalCookies = if (filteredCookies.isNotEmpty()) filteredCookies else allCookies

            val existing = storage.loadMelonTarget()
            val target = MelonTarget(
                prodId = prodId,
                scheduleNo = scheduleNo,
                pocCode = pocCode,
                seatId = seatId,
                seatGradeName = existing?.seatGradeName ?: "",
                playDate = playDate,
                volume = volume,
                selectedGradeVolume = selectedGradeVolume,
                cookies = finalCookies,
                name = ""
            )

            storage.saveMelonTarget(target)
            Log.i(TAG, "Melon target saved: prodId=$prodId, scheduleNo=$scheduleNo, pocCode=$pocCode")

            Toast.makeText(requireContext(), "멜론티켓 세션 감지됨 ✓", Toast.LENGTH_LONG).show()
            fetchSeatGradeNo(prodId, scheduleNo, pocCode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract melon session", e)
        }
    }

    private fun fetchSeatGradeNo(prodId: String, scheduleNo: String, pocCode: String) {
        val cookieStr = CookieManager.getInstance().getCookie("https://ticket.melon.com/") ?: ""
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val conn = URL(
                    "https://ticket.melon.com/tktapi/product/summary.json?v=1&callback=getBlockGradeSeatCountCallBack"
                ).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.setRequestProperty("Referer", "https://ticket.melon.com/reservation/popup/stepSeat.htm")
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                conn.setRequestProperty("User-Agent", DESKTOP_UA)
                if (cookieStr.isNotEmpty()) conn.setRequestProperty("Cookie", cookieStr)

                val body = "prodId=$prodId&pocCode=$pocCode&scheduleNo=$scheduleNo&perfDate=&corpCodeNo="
                conn.outputStream.use { it.write(body.toByteArray()) }

                val response = conn.inputStream.bufferedReader().readText()
                Log.d(TAG, "summary.json fetch: ${response.take(400)}")

                val match = Regex("""\(\{[\s\S]*\}\)""").find(response)?.value ?: return@launch
                val json = JSONObject(match.drop(1).dropLast(1))
                val summaryArr = json.optJSONArray("summary") ?: return@launch
                if (summaryArr.length() == 0) return@launch
                val item = summaryArr.getJSONObject(0)
                Log.d(TAG, "summary item[0]: $item")
                val seatGradeNo = item.optString("seatGradeNo")
                val gradeName = listOf("seatGradeName", "gradeName", "gradeNm", "gradeNameKo")
                    .mapNotNull { item.optString(it).takeIf { s -> s.isNotEmpty() } }
                    .firstOrNull() ?: ""
                val datePattern = Regex("\\d{8}")
                val perfDate = listOf("perfDate", "perfDay", "playDate", "perfDt")
                    .mapNotNull { item.optString(it).takeIf { s -> s.matches(datePattern) } }
                    .firstOrNull()
                withContext(Dispatchers.Main) {
                    if (seatGradeNo.isNotEmpty()) updateMelonSeatId(seatGradeNo)
                    if (gradeName.isNotEmpty()) updateMelonSeatGradeName(gradeName)
                    if (!perfDate.isNullOrEmpty()) updateMelonPlayDate(perfDate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchSeatGradeNo failed", e)
            }
        }
    }

    private fun updateMelonName(name: String) {
        val existing = storage.loadMelonTarget() ?: return
        if (existing.name == name) return
        storage.saveMelonTarget(existing.copy(name = name))
        Log.i(TAG, "Melon name updated: $name")
    }

    private fun updateMelonSeatId(seatGradeNo: String) {
        if (seatGradeNo.isBlank()) return
        val existing = storage.loadMelonTarget() ?: return
        if (existing.seatId == seatGradeNo) return
        storage.saveMelonTarget(existing.copy(seatId = seatGradeNo))
        Log.i(TAG, "Melon seatId updated: $seatGradeNo")
        Toast.makeText(requireContext(), "좌석 등급 감지됨: $seatGradeNo ✓", Toast.LENGTH_SHORT).show()
    }

    private fun updateMelonSeatGradeName(gradeName: String) {
        val existing = storage.loadMelonTarget() ?: return
        if (existing.seatGradeName == gradeName) return
        storage.saveMelonTarget(existing.copy(seatGradeName = gradeName))
        Log.i(TAG, "Melon seatGradeName updated: $gradeName")
    }

    private fun updateMelonPlayDate(date: String) {
        val existing = storage.loadMelonTarget() ?: return
        if (existing.playDate == date) return
        storage.saveMelonTarget(existing.copy(playDate = date))
        Log.i(TAG, "Melon playDate updated: $date")
    }

    /**
     * intent:// 스킴 처리.
     * 우선순위: 앱 설치됨 → startActivity / 앱 없음 → data http/https → browser_fallback_url
     */
    private fun handleIntentScheme(view: WebView, url: String) {
        try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            Log.d(TAG, "intent:// pkg=${intent.`package`} data=${intent.data}")
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
                return
            }
            val dataScheme = intent.data?.scheme
            if (dataScheme == "https" || dataScheme == "http") {
                view.loadUrl(intent.data.toString())
                return
            }
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null) {
                Log.d(TAG, "intent:// fallback: $fallback")
                view.loadUrl(fallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleIntentScheme failed: $url", e)
        }
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
