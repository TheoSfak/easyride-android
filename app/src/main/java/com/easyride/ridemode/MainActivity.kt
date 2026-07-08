package com.easyride.ridemode

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingSpinner: ProgressBar
    private var resolvedBaseUrl: String = BuildConfig.BOOTSTRAP_BASE_URL

    private var pendingMissionId: String? = null
    private var pendingShiftId: String? = null
    private var pendingCsrfToken: String? = null
    private var trackingFlowInProgress = false

    private val fineLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestBackgroundLocationIfNeeded() else showPermissionDeniedMessage()
    }

    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestNotificationPermissionIfNeeded() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { promptBatteryOptimizationExemption() }

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
                webView.addJavascriptInterface(RideBridge(this@MainActivity), "AndroidRideBridge")
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

    fun beginTrackingFlow(missionId: String, shiftId: String, csrfToken: String) {
        if (trackingFlowInProgress) return
        trackingFlowInProgress = true

        pendingMissionId = missionId
        pendingShiftId = shiftId
        pendingCsrfToken = csrfToken

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestBackgroundLocationIfNeeded()
        } else {
            fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requestNotificationPermissionIfNeeded()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionIfNeeded()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.background_location_dialog_title)
            .setMessage(R.string.background_location_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.background_location_dialog_positive) { _, _ ->
                backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .setNegativeButton(R.string.background_location_dialog_negative) { _, _ ->
                requestNotificationPermissionIfNeeded()
            }
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            promptBatteryOptimizationExemption()
        }
    }

    private fun promptBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            launchTrackingService()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_dialog_title)
            .setMessage(R.string.battery_dialog_message)
            .setCancelable(false)
            .setPositiveButton(R.string.battery_dialog_positive) { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                launchTrackingService()
            }
            .setNegativeButton(R.string.battery_dialog_negative) { _, _ -> launchTrackingService() }
            .show()
    }

    private fun launchTrackingService() {
        val intent = Intent(this, RideTrackingService::class.java).apply {
            putExtra(RideTrackingService.EXTRA_MISSION_ID, pendingMissionId)
            putExtra(RideTrackingService.EXTRA_SHIFT_ID, pendingShiftId)
            putExtra(RideTrackingService.EXTRA_CSRF_TOKEN, pendingCsrfToken)
            putExtra(RideTrackingService.EXTRA_BASE_URL, resolvedBaseUrl)
        }
        ContextCompat.startForegroundService(this, intent)
        trackingFlowInProgress = false
    }

    fun stopTrackingService() {
        val intent = Intent(this, RideTrackingService::class.java).apply {
            action = RideTrackingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun showPermissionDeniedMessage() {
        Toast.makeText(this, R.string.permission_denied_message, Toast.LENGTH_LONG).show()
        trackingFlowInProgress = false
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }
}
