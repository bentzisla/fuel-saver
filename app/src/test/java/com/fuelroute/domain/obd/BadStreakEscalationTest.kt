package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mid-drive bad-reply escalation ladder ([ObdConnectionPolicy.nextBadStreakAction]).
 *
 * Before this ladder existed, 5 consecutive bad replies (~1.25 s at the 250 ms poll interval)
 * tore the RFCOMM socket down and reconnected — far too eager for a cheap single-channel clone
 * on a slow bus, where a short run of bad replies is often just a few genuinely slow PIDs. These
 * tests pin the three stages: wait, a one-shot non-destructive resync, and — only once the link
 * is truly gone, or a long stretch has produced no valid reply at all — a real reconnect.
 */
class BadStreakEscalationTest {

    @Test
    fun `a short streak with the link still open just waits`() {
        assertEquals(
            ObdConnectionPolicy.BadStreakAction.WAIT,
            ObdConnectionPolicy.nextBadStreakAction(
                badStreakMs = 1_000L,
                linkOpen = true,
                softResyncAttempted = false,
            ),
        )
        // Right at the old 5-failure/~1.25s trigger point: still just a WAIT under the new ladder.
        assertEquals(
            ObdConnectionPolicy.BadStreakAction.WAIT,
            ObdConnectionPolicy.nextBadStreakAction(
                badStreakMs = 1_250L,
                linkOpen = true,
                softResyncAttempted = false,
            ),
        )
    }

    @Test
    fun `crossing the soft-resync threshold triggers exactly one non-destructive resync`() {
        assertEquals(
            ObdConnectionPolicy.BadStreakAction.SOFT_RESYNC,
            ObdConnectionPolicy.nextBadStreakAction(
                badStreakMs = ObdConnectionPolicy.SOFT_RESYNC_AFTER_BAD_MS,
                linkOpen = true,
                softResyncAttempted = false,
            ),
        )
        // Already tried once for this streak: back to WAIT, not repeated every poll.
        assertEquals(
            ObdConnectionPolicy.BadStreakAction.WAIT,
            ObdConnectionPolicy.nextBadStreakAction(
                badStreakMs = ObdConnectionPolicy.SOFT_RESYNC_AFTER_BAD_MS + 500L,
                linkOpen = true,
                softResyncAttempted = true,
            ),
        )
    }

    @Test
    fun `only a long bad streak with the link still open escalates to a full reconnect`() {
        assertEquals(
            ObdConnectionPolicy.BadStreakAction.RECONNECT,
            ObdConnectionPolicy.nextBadStreakAction(
                badStreakMs = ObdConnectionPolicy.RECONNECT_AFTER_BAD_MS,
                linkOpen = true,
                softResyncAttempted = true,
            ),
        )
        // The requested 8-10s window: our threshold must land inside it.
        assertTrue(ObdConnectionPolicy.RECONNECT_AFTER_BAD_MS in 8_000L..10_000L)
    }

    @Test
    fun `a closed link reconnects immediately regardless of streak duration or resync state`() {
        // The per-command watchdog (ElmLink) already tore the socket down chasing a genuine
        // timeout: there is no cheaper option left, so this must not wait out the rest of the
        // window against a socket that no longer exists.
        assertEquals(
            ObdConnectionPolicy.BadStreakAction.RECONNECT,
            ObdConnectionPolicy.nextBadStreakAction(
                badStreakMs = 0L,
                linkOpen = false,
                softResyncAttempted = false,
            ),
        )
        assertEquals(
            ObdConnectionPolicy.BadStreakAction.RECONNECT,
            ObdConnectionPolicy.nextBadStreakAction(
                badStreakMs = 100L,
                linkOpen = false,
                softResyncAttempted = false,
            ),
        )
    }

    @Test
    fun `escalation stages are monotonic and ordered as time passes`() {
        var softResyncAttempted = false
        val stages = listOf(500L, 1_500L, 3_000L, 4_000L, 6_000L, 9_000L, 12_000L).map { ms ->
            val action = ObdConnectionPolicy.nextBadStreakAction(ms, linkOpen = true, softResyncAttempted)
            if (action == ObdConnectionPolicy.BadStreakAction.SOFT_RESYNC) softResyncAttempted = true
            action
        }
        assertEquals(
            listOf(
                ObdConnectionPolicy.BadStreakAction.WAIT, // 500ms
                ObdConnectionPolicy.BadStreakAction.WAIT, // 1500ms
                ObdConnectionPolicy.BadStreakAction.SOFT_RESYNC, // 3000ms: first crossing
                ObdConnectionPolicy.BadStreakAction.WAIT, // 4000ms: already resynced once
                ObdConnectionPolicy.BadStreakAction.WAIT, // 6000ms
                ObdConnectionPolicy.BadStreakAction.RECONNECT, // 9000ms: crosses the reconnect line
                ObdConnectionPolicy.BadStreakAction.RECONNECT, // 12000ms: still reconnect
            ),
            stages,
        )
    }
}
