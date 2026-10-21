package dev.taha.otpbridge.ui

import android.app.Application
import android.net.Uri
import android.provider.Telephony
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.taha.otpbridge.BuildConfig
import dev.taha.otpbridge.OtpBridgeApp
import dev.taha.otpbridge.data.ActivityEntry
import dev.taha.otpbridge.data.Settings
import dev.taha.otpbridge.net.OtpPayload
import dev.taha.otpbridge.net.RelayClient
import dev.taha.otpbridge.update.UpdateInfo
import dev.taha.otpbridge.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    private val _relayUrl = MutableStateFlow(Settings.DEFAULT_RELAY_URL)
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _blocked = MutableStateFlow<Set<String>>(emptySet())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    private val _activity = MutableStateFlow<List<ActivityEntry>>(emptyList())
    val activity: StateFlow<List<ActivityEntry>> = _activity.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    val installedVersion: String =
        "${Updater.installedVersionName(app)} (build ${Updater.installedVersionCode(app)})"

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    private val _updateStatus = MutableStateFlow("")
    val updateStatus: StateFlow<String> = _updateStatus.asStateFlow()

    init {
        viewModelScope.launch {
            _relayUrl.value = settings.relayUrl()
            _token.value = settings.phoneToken()
            _blocked.value = settings.blockedSenders()
            refreshActivity()
            autoPairIfNeeded()
            checkForUpdate(announceWhenCurrent = false)
        }
    }

    fun checkForUpdate(announceWhenCurrent: Boolean = true) {
        viewModelScope.launch {
            if (announceWhenCurrent) _updateStatus.value = "Checking…"
            Updater.check(getApplication()).fold(
                onSuccess = { info ->
                    _update.value = info
                    _updateStatus.value = when {
                        info != null -> "Update available: ${info.versionName} (build ${info.versionCode})"
                        announceWhenCurrent -> "Up to date."
                        else -> ""
                    }
                },
                onFailure = { if (announceWhenCurrent) _updateStatus.value = "Check failed: ${it.message}" },
            )
        }
    }

    fun installUpdate() {
        val info = _update.value ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            if (!Updater.canInstall(ctx)) {
                _updateStatus.value = "Allow this app to install apps, then tap Install again."
                Updater.openInstallPermissionSettings(ctx)
                return@launch
            }
            _updateStatus.value = "Downloading ${info.versionName}…"
            Updater.download(ctx, info).fold(
                onSuccess = { file ->
                    _updateStatus.value = "Verified. Confirm the install prompt."
                    Updater.launchInstaller(ctx, file)
                },
                onFailure = { _updateStatus.value = "Update failed: ${it.message}" },
            )
        }
    }

    /**
     * First launch: swap the pairing token compiled into this build for the real
     * phone token. Runs once — after it succeeds there is a stored token, and
     * the relay will refuse the pairing token anyway once its uses are spent.
     */
    private suspend fun autoPairIfNeeded() {
        if (_token.value.isNotBlank()) return
        if (BuildConfig.PAIR_TOKEN.isBlank()) {
            _status.value = "No token yet — paste one in Settings, or rebuild with scripts/build-apk.ps1."
            return
        }

        _status.value = "Pairing with the relay…"
        RelayClient.claimPairing(_relayUrl.value, BuildConfig.PAIR_TOKEN).fold(
            onSuccess = { phoneToken ->
                settings.setPhoneToken(phoneToken)
                _token.value = phoneToken
                _status.value = "Paired. Add a sender and you are done."
            },
            onFailure = {
                // Most likely the pairing token is used up or expired. Manual
                // entry still works, so this is a message rather than a dead end.
                _status.value = "Automatic pairing failed: ${it.message}. " +
                    "Rebuild with scripts/build-apk.ps1 for a fresh token, or paste one below."
            },
        )
    }

    fun retryPairing() {
        viewModelScope.launch { autoPairIfNeeded() }
    }

    fun refreshActivity() {
        _activity.value = OtpBridgeApp.activityLog.entries()
    }

    fun setRelayUrl(v: String) {
        _relayUrl.value = v
        viewModelScope.launch { settings.setRelayUrl(v) }
    }

    fun setToken(v: String) {
        _token.value = v
        viewModelScope.launch { settings.setPhoneToken(v) }
    }

    fun blockSender(sender: String) {
        val s = sender.trim()
        if (s.isEmpty()) return
        val next = _blocked.value + s
        _blocked.value = next
        viewModelScope.launch { settings.setBlockedSenders(next) }
    }

    fun unblockSender(sender: String) {
        val next = _blocked.value - sender
        _blocked.value = next
        viewModelScope.launch { settings.setBlockedSenders(next) }
    }

    fun sendTest() {
        viewModelScope.launch {
            _status.value = "Sending test…"
            val payload = OtpPayload(
                id = UUID.randomUUID().toString(),
                code = "000000",
                sender = "TEST",
                body = "Test from OTP Bridge",
                receivedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date()),
            )
            _status.value = RelayClient.post(_relayUrl.value, _token.value, payload).fold(
                onSuccess = { "Test delivered — check latest.json on the PC" },
                onFailure = { "Test failed: ${it.message}" },
            )
            refreshActivity()
        }
    }
}
