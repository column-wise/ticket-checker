package com.ticketchecker.ui.interpark

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
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
        private const val SESSION_TRIGGER = "BookInfoXml.asp?Flag=OrderSeatGrade"
        private const val PREFS_NAME = "interpark_webview"
        private const val KEY_LAST_URL = "last_url"
    }

    private var _binding: FragmentWebviewBinding? = null
    private val binding get() = _binding!!
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var storage: TargetStorage
    private lateinit var backCallback: OnBackPressedCallback

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
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()

                if (url.contains(SESSION_TRIGGER)) {
                    Log.d(TAG, "Session trigger detected: $url")
                    mainHandler.post {
                        handleInterparkSession(url, view)
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                backCallback.isEnabled = view.canGoBack()
                // 마지막 URL 저장 (복원용) — 세션 예매 페이지(poticket)는 제외
                val currentUrl = view.url
                if (!currentUrl.isNullOrEmpty() &&
                    currentUrl != "about:blank" &&
                    !currentUrl.contains("poticket.interpark.com")) {
                    requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_LAST_URL, currentUrl).apply()
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }
        }

        // 마지막으로 보던 페이지 복원, 없으면 메인으로
        val lastUrl = requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_URL, INTERPARK_URL) ?: INTERPARK_URL
        webView.loadUrl(lastUrl)
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
        binding.webView.destroy()
        _binding = null
        super.onDestroyView()
    }
}
