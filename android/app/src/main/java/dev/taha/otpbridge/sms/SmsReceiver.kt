package dev.taha.otpbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dev.taha.otpbridge.OtpBridgeApp
import dev.taha.otpbridge.data.ActivityEntry
import dev.taha.otpbridge.data.Outcome
import dev.taha.otpbridge.data.Settings
import dev.taha.otpbridge.net.OtpPayload
import dev.taha.otpbridge.otp.OtpExtractor
import dev.taha.otpbridge.otp.SenderFilter
import dev.taha.otpbridge.work.ForwardWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Multipart messages arrive as several PDUs from the same sender; join them.
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val sender = messages[0].originatingAddress.orEmpty()
        val body = messages.joinToString("") { it.messageBody.orEmpty() }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context, sender, body)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, sender: String, body: String) {
        if (SenderFilter.isBlocked(sender, Settings(context).blockedSenders())) {
            // Muted sender: body discarded here, never stored or transmitted.
            record(sender, Outcome.IGNORED)
            return
        }

        // Content-based, so a service you have never used before still works.
        // Anything that does not read like a one-time code message stops here
        // and its body is discarded.
        val code = OtpExtractor.extractOtp(body)
        if (code == null) {
            record(sender, Outcome.NO_CODE)
            return
        }

        ForwardWorker.enqueue(
            context,
            OtpPayload(
                id = UUID.randomUUID().toString(),
                code = code,
                sender = sender,
                body = body,
                receivedAt = isoNow(),
            ),
        )
    }

    private fun record(sender: String, outcome: Outcome) {
        OtpBridgeApp.activityLog.record(ActivityEntry(sender, System.currentTimeMillis(), outcome))
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}
