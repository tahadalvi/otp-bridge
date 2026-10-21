package dev.taha.otpbridge.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * A silently dropped OTP is worse than a visibly failed one — you would sit
 * waiting for a code that is never coming. This is the only thing the app
 * ever notifies about.
 */
object FailureNotifier {

    private const val CHANNEL_ID = "otp_failures"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Forwarding failures",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "Shown when an OTP could not be delivered to the PC" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyFailure(context: Context, sender: String, reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("OTP from $sender not delivered")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
