package com.ticketchecker.ui.melon

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.ticketchecker.databinding.FragmentWebviewBinding
import com.ticketchecker.model.MelonTarget
import com.ticketchecker.storage.TargetStorage
import org.json.JSONObject

class MelonFragment : Fragment() {

    companion object {
        private const val TAG = "MelonFragment"
        private const val MELON_URL = "https://ticket.melon.com/main/index.htm"
        private const val JS_INTERFACE_NAME = "MelonAndroid"
    }

    private var _binding: FragmentWebviewBinding? = null
    private val binding get() = _binding!!
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var storage: TargetStorage
    private lateinit var backCallback: OnBackPressedCallback

    inner class MelonJsInterface {

        @JavascriptInterface
        fun onSeatStateRequest(dataJson: String) {
            Log.d(TAG, "seatStateInfo request intercepted: $dataJson")
            mainHandler.post {
                handleMelonSession(dataJson)
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
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(MelonJsInterface(), JS_INTERFACE_NAME)

        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                backCallback.isEnabled = view.canGoBack()
                injectXhrInterceptor(view)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
            }
        }

        webView.loadUrl(MELON_URL)
    }

    private fun injectXhrInterceptor(webView: WebView) {
        val js = """
            (function() {
                if (window.__melonXhrInjected) return;
                window.__melonXhrInjected = true;

                var OrigXHR = window.XMLHttpRequest;

                function InterceptedXHR() {
                    var xhr = new OrigXHR();
                    var capturedUrl = '';
                    var capturedMethod = '';

                    this.open = function(method, url) {
                        capturedMethod = method;
                        capturedUrl = url;
                        return xhr.open.apply(xhr, arguments);
                    };

                    this.send = function(body) {
                        if (capturedUrl && capturedUrl.indexOf('seatStateInfo.json') !== -1 && capturedMethod === 'POST') {
                            try {
                                var params = {};
                                if (body) {
                                    body.split('&').forEach(function(pair) {
                                        var parts = pair.split('=');
                                        if (parts.length === 2) {
                                            params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1]);
                                        }
                                    });
                                }
                                window.MelonAndroid.onSeatStateRequest(JSON.stringify(params));
                            } catch(e) {
                                console.error('Melon interceptor error:', e);
                            }
                        }
                        return xhr.send.apply(xhr, arguments);
                    };

                    var self = this;
                    ['abort','setRequestHeader','getResponseHeader','getAllResponseHeaders','addEventListener',
                     'removeEventListener','dispatchEvent','overrideMimeType'].forEach(function(m) {
                        self[m] = function() { return xhr[m].apply(xhr, arguments); };
                    });

                    ['onreadystatechange','onload','onerror','onabort','onprogress','ontimeout',
                     'readyState','response','responseText','responseType','responseURL',
                     'responseXML','status','statusText','timeout','upload','withCredentials'].forEach(function(prop) {
                        Object.defineProperty(self, prop, {
                            get: function() { return xhr[prop]; },
                            set: function(v) { xhr[prop] = v; }
                        });
                    });
                }

                window.XMLHttpRequest = InterceptedXHR;
                console.log('[MelonChecker] XHR interceptor injected');
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
            val volume = data.optString("volume").ifEmpty { "1" }
            val selectedGradeVolume = data.optString("selectedGradeVolume").ifEmpty { "1" }

            // Extract cookies
            val cookieString = CookieManager.getInstance()
                .getCookie("https://ticket.melon.com/") ?: ""
            val allCookies = parseCookies(cookieString)

            val interestCookieKeys = listOf("PCID", "JSESSIONID", "keyCookie", "TKT_POC_ID", "NetFunnel_ID")
            val filteredCookies = allCookies.filter { it.key in interestCookieKeys }.toMutableMap()
            val finalCookies = if (filteredCookies.isNotEmpty()) filteredCookies else allCookies

            val target = MelonTarget(
                prodId = prodId,
                scheduleNo = scheduleNo,
                seatId = seatId,
                volume = volume,
                selectedGradeVolume = selectedGradeVolume,
                cookies = finalCookies,
                name = ""
            )

            storage.saveMelonTarget(target)
            Log.i(TAG, "Melon target saved: prodId=$prodId, scheduleNo=$scheduleNo")

            Toast.makeText(requireContext(), "멜론티켓 세션 감지됨 ✓", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract melon session", e)
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
