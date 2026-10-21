package dev.taha.otpbridge.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OtpPayload(
    val id: String,
    val code: String,
    val sender: String,
    val body: String,
    val receivedAt: String,
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("code", code)
        .put("sender", sender)
        .put("body", body)
        .put("receivedAt", receivedAt)
        .toString()
}

object RelayClient {

    /**
     * Trade the build-time pairing token for the real phone token.
     *
     * Unauthenticated by design — the pairing token is itself the credential.
     * The relay decrements its remaining uses, so this works for as many phones
     * as the token was minted for and then stops working entirely.
     */
    suspend fun claimPairing(relayUrl: String, pairToken: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (pairToken.isBlank()) return@withContext Result.failure(IllegalStateException("no pairing token in this build"))

            val url = URL(relayUrl.trimEnd('/') + "/pair/claim")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                }
                val body = JSONObject().put("pairToken", pairToken).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status !in 200..299) {
                    val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    return@withContext Result.failure(RelayException(status, "pairing failed: $status $detail".trim()))
                }
                val phoneToken = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    .optString("phoneToken")
                if (phoneToken.isNullOrBlank()) {
                    Result.failure(IllegalStateException("relay returned no phone token"))
                } else {
                    Result.success(phoneToken)
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }

    suspend fun post(relayUrl: String, token: String, payload: OtpPayload): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext Result.failure(IllegalStateException("no token configured"))

            val url = URL(relayUrl.trimEnd('/') + "/otp")
            var conn: HttpURLConnection? = null
            try {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(payload.toJson().toByteArray(Charsets.UTF_8)) }

                val status = conn.responseCode
                if (status in 200..299) {
                    Result.success(Unit)
                } else {
                    val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Result.failure(RelayException(status, "relay returned $status $detail".trim()))
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }
}

/** [status] lets the worker distinguish "retry later" from "will never succeed". */
class RelayException(val status: Int, message: String) : Exception(message)
