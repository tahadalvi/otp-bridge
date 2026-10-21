package dev.taha.otpbridge

import dev.taha.otpbridge.otp.OtpExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {

    @Test fun `extracts a leading six digit code`() {
        assertEquals("483920", OtpExtractor.extract("483920 is your OTP. Do not share."))
    }

    @Test fun `extracts a trailing code`() {
        assertEquals("112233", OtpExtractor.extract("Your verification code is 112233"))
    }

    @Test fun `extracts a four digit code`() {
        assertEquals("4821", OtpExtractor.extract("Use PIN 4821 to log in"))
    }

    @Test fun `extracts an eight digit code`() {
        assertEquals("12345678", OtpExtractor.extract("Your one-time password: 12345678"))
    }

    @Test fun `prefers the digits nearest a keyword over an unrelated number`() {
        val body = "Ref 9988776 - your OTP is 246810. Valid 10 min."
        assertEquals("246810", OtpExtractor.extract(body))
    }

    @Test fun `prefers a keyword-adjacent code even when another comes first`() {
        val body = "Order 4455 shipped. Verification code 778899 to confirm."
        assertEquals("778899", OtpExtractor.extract(body))
    }

    @Test fun `ignores digit runs longer than eight`() {
        assertEquals("4821", OtpExtractor.extract("Account 1234567890123 code 4821"))
    }

    @Test fun `ignores digits embedded in a word`() {
        assertNull(OtpExtractor.extract("Visit shop24seven for offers"))
    }

    @Test fun `ignores an amount with a decimal point`() {
        assertNull(OtpExtractor.extract("Debited Rs 5000.50 from your account"))
    }

    @Test fun `falls back to the first standalone run without a keyword`() {
        assertEquals("5566", OtpExtractor.extract("5566 unlocks the door"))
    }

    @Test fun `returns null when there are no digits`() {
        assertNull(OtpExtractor.extract("Your package has been delivered"))
    }

    @Test fun `returns null for an empty body`() {
        assertNull(OtpExtractor.extract(""))
    }

    @Test fun `handles a hyphenated code by taking the digit run`() {
        assertEquals("483920", OtpExtractor.extract("OTP: 483920-do not share"))
    }

    @Test fun `is case insensitive on keywords`() {
        assertEquals("246810", OtpExtractor.extract("Ref 9988776. YOUR otp IS 246810."))
    }
}
