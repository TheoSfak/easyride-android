package com.easyride.ridemode

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingSpinner: ProgressBar
    private var resolvedBaseUrl: String = BuildConfig.BOOTSTRAP_BASE_URL

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingSpinner = findViewById(R.id.loadingSpinner)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        resolveBaseUrlAndLoad()
    }

    private fun resolveBaseUrlAndLoad() {
        val fetcher = HttpConfigFetcher(BuildConfig.SERVER_CONFIG_URL)
        val cache = PrefsConfigCache(applicationContext)

        Executors.newSingleThreadExecutor().execute {
            val url = ServerConfig.resolveBaseUrl(fetcher, cache, BuildConfig.BOOTSTRAP_BASE_URL)
            runOnUiThread {
                resolvedBaseUrl = url
                setUpWebViewClient(url)
                webView.loadUrl("$url/login.php")
                loadingSpinner.visibility = View.GONE
            }
        }
    }

    private fun setUpWebViewClient(baseUrl: String) {
        val baseHost = Uri.parse(baseUrl).host
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return if (url.host != null && url.host != baseHost) {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }
}
