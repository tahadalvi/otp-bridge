package dev.taha.otpbridge.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.taha.otpbridge.data.Settings as AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val size: Long,
    val sha256: String,
)

/**
 * Pulls new builds from the relay so the app can update itself over the network.
 *
 * Safe because Android refuses an in-place update signed with a different key:
 * this channel can only ever deliver builds signed with the project keystore,
 * even if the relay were compromised. The downloaded file is additionally
 * checked against the SHA-256 the relay advertised before it is handed to the
 * package installer.
 */
object Updater {

    private const val APK_NAME = "update.apk"

    suspend fun check(context: Context): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        val settings = AppSettings(context)
        val token = settings.phoneToken()
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("not paired yet"))

        try {
            val conn = (URL(settings.relayUrl().trimEnd('/') + "/update/latest").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("authorization", "Bearer $token")
            }
            try {
                // 404 simply means nothing has been published — not an error.
                if (conn.responseCode == 404) return@withContext Result.success(null)
                if (conn.responseCode !in 200..299) {
                    return@withContext Result.failure(Exception("relay returned ${conn.responseCode}"))
                }
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val info = UpdateInfo(
                    versionCode = o.getLong("versionCode"),
                    versionName = o.optString("versionName"),
                    size = o.optLong("size"),
                    sha256 = o.optString("sha256"),
                )
                Result.success(if (info.versionCode > installedVersionCode(context)) info else null)
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun download(context: Context, expected: UpdateInfo): Result<File> = withContext(Dispatchers.IO) {
        val settings = AppSettings(context)
        val token = settings.phoneToken()
        val target = File(context.cacheDir, APK_NAME)

        try {
            val conn = (URL(settings.relayUrl().trimEnd('/') + "/update/apk").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("authorization", "Bearer $token")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    return@withContext Result.failure(Exception("download failed: ${conn.responseCode}"))
                }
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected.sha256, ignoreCase = true)) {
                    target.delete()
                    return@withContext Result.failure(
                        SecurityException("checksum mismatch — refusing to install"),
                    )
                }
                Result.success(target)
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            target.delete()
            Result.failure(e)
        }
    }

    /** True when the user has allowed this app to install packages. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun installedVersionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        0L
    }

    fun installedVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        "?"
    }
}
