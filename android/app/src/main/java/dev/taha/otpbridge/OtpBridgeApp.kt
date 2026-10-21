package dev.taha.otpbridge

import android.app.Application
import dev.taha.otpbridge.data.ActivityLog
import dev.taha.otpbridge.notify.FailureNotifier
import dev.taha.otpbridge.work.UpdateWorker

class OtpBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        FailureNotifier.ensureChannel(this)
        UpdateWorker.schedule(this)
    }

    companion object {
        /** In-memory only; cleared when the process dies. Holds no codes. */
        val activityLog = ActivityLog(maxEntries = 20)
    }
}
