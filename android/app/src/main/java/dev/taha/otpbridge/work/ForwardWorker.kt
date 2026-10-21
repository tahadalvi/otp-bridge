package dev.taha.otpbridge.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.taha.otpbridge.OtpBridgeApp
import dev.taha.otpbridge.data.ActivityEntry
import dev.taha.otpbridge.data.Outcome
import dev.taha.otpbridge.data.Settings
import dev.taha.otpbridge.net.OtpPayload
import dev.taha.otpbridge.net.RelayClient
import dev.taha.otpbridge.net.RelayException
import dev.taha.otpbridge.notify.FailureNotifier
import java.util.concurrent.TimeUnit

class ForwardWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val payload = OtpPayload(
            id = inputData.getString(KEY_ID) ?: return Result.failure(),
            code = inputData.getString(KEY_CODE) ?: return Result.failure(),
            sender = inputData.getString(KEY_SENDER) ?: return Result.failure(),
            body = inputData.getString(KEY_BODY).orEmpty(),
            receivedAt = inputData.getString(KEY_RECEIVED_AT) ?: return Result.failure(),
        )

        val settings = Settings(applicationContext)
        val outcome = RelayClient.post(settings.relayUrl(), settings.phoneToken(), payload)

        return outcome.fold(
            onSuccess = {
                record(payload.sender, Outcome.FORWARDED)
                Result.success()
            },
            onFailure = { err ->
                // 4xx other than 429 means the request itself is wrong. Retrying
                // an unauthorised or malformed POST forever just burns battery.
                val permanent = err is RelayException && err.status in 400..499 && err.status != 429
                if (permanent || runAttemptCount >= MAX_ATTEMPTS) {
                    record(payload.sender, Outcome.FAILED)
                    FailureNotifier.notifyFailure(
                        applicationContext,
                        payload.sender,
                        err.message ?: "unknown error",
                    )
                    Result.failure()
                } else {
                    Result.retry()
                }
            },
        )
    }

    private fun record(sender: String, outcome: Outcome) {
        OtpBridgeApp.activityLog.record(ActivityEntry(sender, System.currentTimeMillis(), outcome))
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_CODE = "code"
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_RECEIVED_AT = "receivedAt"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context, payload: OtpPayload) {
            val data = Data.Builder()
                .putString(KEY_ID, payload.id)
                .putString(KEY_CODE, payload.code)
                .putString(KEY_SENDER, payload.sender)
                .putString(KEY_BODY, payload.body)
                .putString(KEY_RECEIVED_AT, payload.receivedAt)
                .build()

            val request = OneTimeWorkRequestBuilder<ForwardWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            // Keyed by message id so a duplicate broadcast cannot double-send.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("otp-${payload.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
