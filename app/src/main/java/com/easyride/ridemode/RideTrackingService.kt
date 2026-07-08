package com.easyride.ridemode

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RideTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var missionId: String = ""
    private var shiftId: String = ""
    private var csrfToken: String = ""
    private var baseUrl: String = ""
    private var consecutiveFailures = 0

    companion object {
        const val CHANNEL_ID = "ride_tracking_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.easyride.ridemode.action.STOP_TRACKING"
        const val EXTRA_MISSION_ID = "mission_id"
        const val EXTRA_SHIFT_ID = "shift_id"
        const val EXTRA_CSRF_TOKEN = "csrf_token"
        const val EXTRA_BASE_URL = "base_url"
        private const val PING_INTERVAL_MS = 10_000L
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        missionId = intent?.getStringExtra(EXTRA_MISSION_ID) ?: ""
        shiftId = intent?.getStringExtra(EXTRA_SHIFT_ID) ?: ""
        csrfToken = intent?.getStringExtra(EXTRA_CSRF_TOKEN) ?: ""
        baseUrl = intent?.getStringExtra(EXTRA_BASE_URL) ?: ""

        if (missionId.isEmpty() || shiftId.isEmpty() || csrfToken.isEmpty() || baseUrl.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, PING_INTERVAL_MS)
            .setMinUpdateIntervalMillis(PING_INTERVAL_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { sendPing(it) }
            }
        }
        locationCallback = callback

        fusedLocationClient.requestLocationUpdates(locationRequest, callback, mainLooper)
    }

    private fun sendPing(location: Location) {
        val cookie = CookieManager.getInstance().getCookie(baseUrl) ?: ""

        val formBody = FormBody.Builder()
            .add("csrf_token", csrfToken)
            .add("mission_id", missionId)
            .add("shift_id", shiftId)
            .add("lat", location.latitude.toString())
            .add("lng", location.longitude.toString())
            .add("accuracy", if (location.hasAccuracy()) location.accuracy.toString() else "")
            .add("speed", if (location.hasSpeed()) location.speed.toString() else "")
            .add("heading", if (location.hasBearing()) location.bearing.toString() else "")
            .add("battery_level", batteryLevelPercent()?.toString() ?: "")
            .add("status", "")
            .build()

        val request = Request.Builder()
            .url("$baseUrl/api-ride-ping.php")
            .header("Cookie", cookie)
            .post(formBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                registerFailure()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val ok = try {
                        JSONObject(it.body?.string() ?: "").optBoolean("ok", false)
                    } catch (e: Exception) {
                        false
                    }
                    if (ok) consecutiveFailures = 0 else registerFailure()
                }
            }
        })
    }

    private fun registerFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            stopTracking()
        }
    }

    private fun batteryLevelPercent(): Int? {
        val bm = getSystemService(BatteryManager::class.java) ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    private fun stopTracking() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RideTrackingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(0, getString(R.string.tracking_notification_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
