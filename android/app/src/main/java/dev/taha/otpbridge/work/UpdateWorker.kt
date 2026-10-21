package dev.taha.otpbridge.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.taha.otpbridge.update.Updater
import java.util.concurrent.TimeUnit

/**
 * Notices new builds in the background so the app is current without anyone
 * opening it. It only *downloads and verifies*; installing still needs a tap,
 * because Android requires user confirmation for a sideloaded install.
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val info = Updater.check(applicationContext).getOrNull() ?: return Result.success()
        // Pre-fetch so that tapping Install in the UI is instant, and so a bad
        // download is caught here rather than in front of the user.
        Updater.download(applicationContext, info)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "otp-update-check",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
