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
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

    private val geolocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingGeolocationOrigin?.let { origin -> pendingGeolocationCallback?.invoke(origin, granted, false) }
        pendingGeolocationCallback = null
        pendingGeolocationOrigin = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        applySystemBarInsets()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        webView.settings.setGeolocationEnabled(true)

        // WebView has no built-in download handling — an <a download> link
        // (e.g. the site's own APK download link) silently does nothing
        // unless a DownloadListener is set. Hand it off to the system
        // browser, which already knows how to download + offer to install
        // an .apk.
        webView.setDownloadListener { url, _, _, _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

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

    private fun applySystemBarInsets() {
        val rootContainer = findViewById<View>(R.id.rootContainer)
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun resolveBaseUrlAndLoad() {
        val fetcher = HttpConfigFetcher(BuildConfig.SERVER_CONFIG_URL)
        val cache = PrefsConfigCache(applicationContext)

        Executors.newSingleThreadExecutor().execute {
            val url = ServerConfig.resolveBaseUrl(fetcher, cache, BuildConfig.BOOTSTRAP_BASE_URL)
            runOnUiThread {
                resolvedBaseUrl = url
                setUpWebViewClient(url)
                setUpWebChromeClient()
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

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // login.php always redirects to dashboard.php (no redirect-target
                // param exists server-side) — this app is scoped to Ride Mode only,
                // so bounce straight past the dashboard to the rides list instead.
                if (url != null && Uri.parse(url).path?.endsWith("/dashboard.php") == true) {
                    view.loadUrl("$baseUrl/my-participations.php")
                }
            }
        }
    }

    private fun setUpWebChromeClient() {
        // WebView denies every page's navigator.geolocation call by default —
        // silently and instantly, with no prompt — unless a WebChromeClient
        // explicitly grants it here. This governs ANY page's GPS use inside
        // the WebView (ride-mode.php's own watchPosition() self-marker call,
        // my-participations.php's standalone "Αποστολή Θέσης" button, etc.),
        // separately from the native ACCESS_FINE_LOCATION permission Ride
        // Mode's Start flow requests. If the native permission isn't held
        // yet — e.g. the rider taps a GPS feature before ever using Ride
        // Mode's Start button — request it here on demand instead of just
        // denying, exactly like a normal browser would prompt in the moment.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val hasFineLocation = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (hasFineLocation) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeolocationCallback = callback
                    pendingGeolocationOrigin = origin
                    geolocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
