package dev.taha.otpbridge

import dev.taha.otpbridge.otp.SenderFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderFilterTest {

    @Test fun `forwards from any sender by default — no setup required`() {
        assertTrue(SenderFilter.isBlocked("VM-YOURBANK", emptySet()).not())
        assertTrue(SenderFilter.isBlocked("some-new-bank-we-never-saw", emptySet()).not())
    }

    @Test fun `blocks a muted sender`() {
        assertTrue(SenderFilter.isBlocked("VM-SPAMMR", setOf("VM-SPAMMR")))
    }

    @Test fun `blocking ignores case and whitespace`() {
        assertTrue(SenderFilter.isBlocked("  vm-spammr ", setOf("VM-SPAMMR")))
    }

    @Test fun `does not block a different sender`() {
        assertFalse(SenderFilter.isBlocked("VM-YOURBANK", setOf("VM-SPAMMR")))
    }

    @Test fun `does not block on a partial match`() {
        assertFalse(SenderFilter.isBlocked("VM-SPAM", setOf("VM-SPAMMR")))
        assertFalse(SenderFilter.isBlocked("XVM-SPAMMR", setOf("VM-SPAMMR")))
    }

    @Test fun `a blank sender is not blocked by an empty list`() {
        assertFalse(SenderFilter.isBlocked("", emptySet()))
    }
}
