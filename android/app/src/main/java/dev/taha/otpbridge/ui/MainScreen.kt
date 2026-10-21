package dev.taha.otpbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.taha.otpbridge.data.ActivityEntry
import dev.taha.otpbridge.data.Outcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(vm: MainViewModel, onOpenBatterySettings: () -> Unit) {
    val relayUrl by vm.relayUrl.collectAsState()
    val token by vm.token.collectAsState()
    val blocked by vm.blocked.collectAsState()
    val activity by vm.activity.collectAsState()
    val status by vm.status.collectAsState()
    val update by vm.update.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("OTP Bridge", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Forwarding any message that looks like a one-time code. " +
                    "Nothing to set up.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        item { HorizontalDivider() }

        item {
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sender and outcome only — no codes or message text are kept on the phone. " +
                    "Mute anything noisy.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (activity.isEmpty()) {
            item { Text("Nothing yet.") }
        }

        items(activity) { entry ->
            ActivityRow(
                entry = entry,
                isBlocked = blocked.any { it.equals(entry.sender.trim(), ignoreCase = true) },
                onMute = { vm.blockSender(entry.sender) },
                onUnmute = { vm.unblockSender(entry.sender) },
            )
        }

        if (blocked.isNotEmpty()) {
            item { HorizontalDivider() }
            item {
                Text("Muted senders", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Messages from these are discarded on the phone and never sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(blocked.toList().sorted()) { sender ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sender)
                    IconButton(onClick = { vm.unblockSender(sender) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Unmute $sender")
                    }
                }
            }
        }

        item { HorizontalDivider() }

        item {
            Text("App version", style = MaterialTheme.typography.titleMedium)
            Text(vm.installedVersion, style = MaterialTheme.typography.bodyMedium)
            if (updateStatus.isNotEmpty()) {
                Text(updateStatus, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.checkForUpdate() }) { Text("Check for updates") }
                if (update != null) {
                    Button(onClick = { vm.installUpdate() }) { Text("Install") }
                }
            }
            Text(
                "Updates come from your own relay and must be signed with the same key, " +
                    "so nothing else can install itself here.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item { HorizontalDivider() }

        item {
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = relayUrl,
                onValueChange = vm::setRelayUrl,
                label = { Text("Relay URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = vm::setToken,
                label = { Text("Phone token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.sendTest() }) { Text("Send test") }
                Button(onClick = { vm.refreshActivity() }) { Text("Refresh") }
            }
            if (token.isBlank()) {
                OutlinedButton(onClick = { vm.retryPairing() }) { Text("Retry pairing") }
            }
        }

        item {
            OutlinedButton(onClick = onOpenBatterySettings, modifier = Modifier.fillMaxWidth()) {
                Text("Exempt from battery optimisation")
            }
            Text(
                "Xiaomi, Samsung and OnePlus kill background receivers aggressively. " +
                    "Without this the app can silently stop forwarding.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ActivityRow(
    entry: ActivityEntry,
    isBlocked: Boolean,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
) {
    val time = remember(entry.atEpochMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.atEpochMs))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(entry.sender, style = MaterialTheme.typography.bodyLarge)
                Text("$time · ${entry.outcome.label()}", style = MaterialTheme.typography.bodySmall)
            }
            if (isBlocked) {
                TextButton(onClick = onUnmute) { Text("Unmute") }
            } else {
                TextButton(onClick = onMute) { Text("Mute") }
            }
        }
    }
}

private fun Outcome.label(): String = when (this) {
    Outcome.FORWARDED -> "forwarded"
    Outcome.IGNORED -> "muted — discarded on the phone"
    Outcome.NO_CODE -> "not a code message — ignored"
    Outcome.FAILED -> "failed to send"
}
