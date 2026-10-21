package dev.taha.otpbridge.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.taha.otpbridge.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "otp_bridge_settings")

class Settings(private val context: Context) {

    private object Keys {
        val RELAY_URL = stringPreferencesKey("relay_url")
        val PHONE_TOKEN = stringPreferencesKey("phone_token")
        // Was an allowlist. Now a mute list: empty means "forward everything
        // that looks like a one-time code", which is the default.
        val BLOCKED = stringSetPreferencesKey("blocked_senders")
    }

    val relayUrlFlow: Flow<String> =
        context.dataStore.data.map { it[Keys.RELAY_URL] ?: DEFAULT_RELAY_URL }

    val blockedSendersFlow: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.BLOCKED] ?: emptySet() }

    suspend fun relayUrl(): String = relayUrlFlow.first()
    suspend fun phoneToken(): String = context.dataStore.data.map { it[Keys.PHONE_TOKEN] ?: "" }.first()
    suspend fun blockedSenders(): Set<String> = blockedSendersFlow.first()

    suspend fun setRelayUrl(v: String) { context.dataStore.edit { it[Keys.RELAY_URL] = v } }
    suspend fun setPhoneToken(v: String) { context.dataStore.edit { it[Keys.PHONE_TOKEN] = v } }
    suspend fun setBlockedSenders(v: Set<String>) { context.dataStore.edit { it[Keys.BLOCKED] = v } }

    companion object {
        /** Baked in at build time by scripts/build-apk.ps1; falls back to the known host. */
        val DEFAULT_RELAY_URL: String =
            BuildConfig.RELAY_URL.ifBlank { "https://relay.example.com/otp-bridge" }
    }
}
