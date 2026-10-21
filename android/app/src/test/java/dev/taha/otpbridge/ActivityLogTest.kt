package dev.taha.otpbridge

import dev.taha.otpbridge.data.ActivityEntry
import dev.taha.otpbridge.data.ActivityLog
import dev.taha.otpbridge.data.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLogTest {

    @Test fun `returns entries newest first`() {
        val log = ActivityLog()
        log.record(ActivityEntry("A", 1, Outcome.FORWARDED))
        log.record(ActivityEntry("B", 2, Outcome.IGNORED))
        assertEquals(listOf("B", "A"), log.entries().map { it.sender })
    }

    @Test fun `drops the oldest entry past the cap`() {
        val log = ActivityLog(maxEntries = 2)
        log.record(ActivityEntry("A", 1, Outcome.FORWARDED))
        log.record(ActivityEntry("B", 2, Outcome.FORWARDED))
        log.record(ActivityEntry("C", 3, Outcome.FORWARDED))
        assertEquals(listOf("C", "B"), log.entries().map { it.sender })
    }

    @Test fun `starts empty`() {
        assertEquals(emptyList<ActivityEntry>(), ActivityLog().entries())
    }

    @Test fun `records the outcome`() {
        val log = ActivityLog()
        log.record(ActivityEntry("A", 1, Outcome.IGNORED))
        assertEquals(Outcome.IGNORED, log.entries()[0].outcome)
    }
}
