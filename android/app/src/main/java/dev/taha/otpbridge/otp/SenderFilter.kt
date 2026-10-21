package dev.taha.otpbridge.otp

/**
 * Every sender is allowed by default — an OTP from a service you have never
 * used before still arrives, which is the whole point. The list is a *mute*
 * list for senders that turn out to be noisy.
 *
 * Detection is content-based; see [OtpExtractor.extractOtp].
 */
object SenderFilter {
    fun isBlocked(sender: String, blocked: Set<String>): Boolean {
        val s = sender.trim()
        if (s.isEmpty()) return false
        return blocked.any { it.trim().equals(s, ignoreCase = true) }
    }
}
