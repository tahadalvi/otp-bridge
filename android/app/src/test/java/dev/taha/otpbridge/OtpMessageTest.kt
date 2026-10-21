package dev.taha.otpbridge

import dev.taha.otpbridge.otp.OtpExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * extractOtp is the gate the receiver actually uses. Every sender is allowed by
 * default, so this is the only thing between "a code arrives automatically" and
 * "your private texts get forwarded to a server" — and it also has to pick the
 * *right* number out of messages that are full of other numbers.
 */
class OtpMessageTest {

    // --- the message that broke the first version --------------------------
    // Real EPFO message, 2026-08-01. The old nearest-keyword rule returned
    // 7203 (a fragment of the masked UAN) because it sat closer to the word
    // "OTP" than the actual code did.

    @Test fun `epfo — masked UAN before the code, reference id after`() {
        val body = "Dear Member (UAN : XXXX XXXX 7203), OTP to login to Member " +
            "Interface is 102710 OTP-ID 8373. Do not share with anyone. -EPFO"
        assertEquals("102710", OtpExtractor.extractOtp(body))
    }

    // --- codes that FOLLOW the keyword -------------------------------------

    @Test fun `code after is`() {
        assertEquals("112233", OtpExtractor.extractOtp("Your verification code is 112233"))
    }

    @Test fun `code after a colon`() {
        assertEquals("445566", OtpExtractor.extractOtp("Login OTP: 445566"))
    }

    @Test fun `code after keyword with words in between`() {
        assertEquals("778899", OtpExtractor.extractOtp("OTP for your transaction is 778899"))
    }

    @Test fun `amount before the code does not win`() {
        val body = "OTP for txn of Rs 5000 at AMAZON is 123456. Valid 10 min."
        assertEquals("123456", OtpExtractor.extractOtp(body))
    }

    @Test fun `account ending digits do not win`() {
        val body = "OTP to authorise payment from A/c ending 4321 is 998877"
        assertEquals("998877", OtpExtractor.extractOtp(body))
    }

    @Test fun `reference number after the code does not win`() {
        assertEquals("246810", OtpExtractor.extractOtp("Your OTP is 246810. Ref No 55512345"))
    }

    // --- codes that PRECEDE the keyword ------------------------------------

    @Test fun `code before is-your-otp`() {
        assertEquals("483920", OtpExtractor.extractOtp("483920 is your OTP for HDFC Bank. Do not share."))
    }

    @Test fun `google prefixed code`() {
        assertEquals("772913", OtpExtractor.extractOtp("G-772913 is your Google verification code."))
    }

    @Test fun `code before keyword with an order number present`() {
        val body = "654321 is your OTP for order 78901234"
        assertEquals("654321", OtpExtractor.extractOtp(body))
    }

    // --- shapes and lengths ------------------------------------------------

    @Test fun `whatsapp split code`() {
        assertEquals("123456", OtpExtractor.extractOtp("Your WhatsApp code: 123-456. Don't share this code."))
    }

    @Test fun `four digit pin`() {
        assertEquals("4821", OtpExtractor.extractOtp("Use PIN 4821 to complete your login"))
    }

    @Test fun `eight digit one-time password`() {
        assertEquals("55123456", OtpExtractor.extractOtp("Your one-time password is 55123456"))
    }

    @Test fun `validity minutes are not mistaken for a code`() {
        assertEquals("135790", OtpExtractor.extractOtp("Your OTP is 135790, valid for 1500 seconds"))
    }

    @Test fun `lowercase otp`() {
        assertEquals("112233", OtpExtractor.extractOtp("112233 is your otp, valid for 10 minutes"))
    }

    @Test fun `passcode wording`() {
        assertEquals("909090", OtpExtractor.extractOtp("Your passcode is 909090"))
    }

    // --- ordinary messages that must NOT be forwarded ----------------------

    @Test fun `a personal text with a number is not an otp`() {
        assertNull(OtpExtractor.extractOtp("Call me back on 4567 when you land"))
    }

    @Test fun `a delivery notification is not an otp`() {
        assertNull(OtpExtractor.extractOtp("Your order 4455 has been shipped and arrives tomorrow"))
    }

    @Test fun `a bank balance alert is not an otp`() {
        assertNull(OtpExtractor.extractOtp("INR 5000 debited from account ending 1234 on 01-Aug"))
    }

    @Test fun `a plain message with no digits is not an otp`() {
        assertNull(OtpExtractor.extractOtp("Hey, are we still on for dinner?"))
    }

    @Test fun `a marketing message is not an otp`() {
        assertNull(OtpExtractor.extractOtp("FLAT 5000 OFF on orders above 9999. Shop now!"))
    }

    @Test fun `an otp keyword with no code is not forwarded`() {
        assertNull(OtpExtractor.extractOtp("Your OTP request could not be processed. Please try again."))
    }

    @Test fun `empty body is not an otp`() {
        assertNull(OtpExtractor.extractOtp(""))
    }

    @Test fun `a reset link is not mistaken for a code`() {
        assertNull(OtpExtractor.extractOtp("Reset your password at https://ex.co/r/abcdefgh"))
    }

    @Test fun `a message with only reference numbers yields nothing`() {
        assertNull(OtpExtractor.extractOtp("Your OTP request ref ID 8373 was logged"))
    }
}
