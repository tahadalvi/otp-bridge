package dev.taha.otpbridge.data

enum class Outcome { FORWARDED, IGNORED, NO_CODE, FAILED }

/**
 * Sender, time and outcome only. Never the code, never the message body —
 * this app is a courier and should retain nothing worth stealing.
 */
data class ActivityEntry(
    val sender: String,
    val atEpochMs: Long,
    val outcome: Outcome,
)

class ActivityLog(private val maxEntries: Int = 20) {
    private val entries = ArrayDeque<ActivityEntry>()

    @Synchronized
    fun record(entry: ActivityEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
    }

    @Synchronized
    fun entries(): List<ActivityEntry> = entries.toList().asReversed()
}
