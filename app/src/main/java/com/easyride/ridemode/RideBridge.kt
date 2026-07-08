package com.easyride.ridemode

import android.webkit.JavascriptInterface

class RideBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun startTracking(missionId: String, shiftId: String, csrfToken: String) {
        activity.runOnUiThread {
            activity.beginTrackingFlow(missionId, shiftId, csrfToken)
        }
    }

    @JavascriptInterface
    fun stopTracking() {
        activity.runOnUiThread {
            activity.stopTrackingService()
        }
    }
}
