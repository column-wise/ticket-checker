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
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.ticketchecker.databinding.FragmentWebviewBinding
import com.ticketchecker.model.InterparkTarget
import com.ticketchecker.storage.TargetStorage

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
                // (window.close() 실패 후 21초 대기 스킵)
                if (url.contains("login-and-service-link")) {
                    val redirect = yanoljaRedirectUrl
                    if (redirect != null) {
                        Log.d(TAG, "login-and-service-link done, skipping wait → $redirect")
                        view.loadUrl(redirect)
                    }
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

            val extractedName = binding.webView.title
                ?.replace(Regex("\\s*[-–|]\\s*인터파크.*$"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val target = InterparkTarget(
                goodsCode = goodsCode,
                placeCode = placeCode,
                bizCode = bizCode,
                playSeq = playSeq,
                sessionId = sessionId,
                cookies = allCookies,
                goodsName = extractedName
                    ?: if (isSameConcert) existing?.goodsName ?: "" else "",
                playDate = if (isSameConcert) existing?.playDate ?: "" else "",
                watchGrades = if (isSameConcert) existing?.watchGrades ?: emptyList() else emptyList()
            )

            storage.saveInterparkTarget(target)
            Log.i(TAG, "Interpark target saved (new): goodsCode=$goodsCode, sessionId=${sessionId.take(10)}...")

            Toast.makeText(requireContext(), "인터파크 세션 감지됨 ✓", Toast.LENGTH_LONG).show()
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

            // 페이지 타이틀에서 공연명 추출 (예: "위켄드 콘서트 - 인터파크티켓" → "위켄드 콘서트")
            val extractedName = binding.webView.title
                ?.replace(Regex("\\s*[-–|]\\s*인터파크.*$"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val target = InterparkTarget(
                goodsCode = goodsCode,
                placeCode = placeCode,
                bizCode = bizCode,
                playSeq = playSeq,
                sessionId = sessionId,
                cookies = finalCookies,
                goodsName = extractedName
                    ?: if (isSameConcert) existing?.goodsName ?: "" else "",
                playDate = if (isSameConcert) existing?.playDate ?: "" else "",
                watchGrades = if (isSameConcert) existing?.watchGrades ?: emptyList() else emptyList()
            )

            storage.saveInterparkTarget(target)
            Log.i(TAG, "Interpark target saved: goodsCode=$goodsCode, sessionId=${sessionId.take(10)}...")

            Toast.makeText(requireContext(), "인터파크 세션 감지됨 ✓", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract interpark session", e)
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
