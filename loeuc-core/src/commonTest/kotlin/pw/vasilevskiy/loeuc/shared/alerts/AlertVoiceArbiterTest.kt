package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertVoiceArbiter
import pw.vasilevskiy.loeuc.shared.alerts.engine.VoiceCandidate
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertVoice
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundPattern
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlertVoiceArbiterTest {

    // A 100 ms pattern, so the hold is decided by minHoldMs rather than by the pattern.
    private val shortPattern = SoundPattern(
        id = "p",
        name = "p",
        steps = listOf(SoundStep(frequencyHz = 1000, durationMs = 60, pauseAfterMs = 40)),
    )

    // A 500 ms pattern - longer than any minHoldMs used below - to exercise the
    // maxOf(minHoldMs, pattern.totalDurationMs()) branch on its own.
    private val longPattern = SoundPattern(
        id = "long",
        name = "long",
        steps = listOf(SoundStep(frequencyHz = 1000, durationMs = 300, pauseAfterMs = 200)),
    )

    private fun candidate(id: String, priority: Int, progress: Double = 0.0) = VoiceCandidate(
        voice = AlertVoice(
            alertId = id,
            pattern = shortPattern,
            progress = progress,
            intervalMs = 500L,
        ),
        priority = priority,
    )

    private fun longCandidate(id: String, priority: Int, progress: Double = 0.0) = VoiceCandidate(
        voice = AlertVoice(
            alertId = id,
            pattern = longPattern,
            progress = progress,
            intervalMs = 500L,
        ),
        priority = priority,
    )

    @Test
    fun testOnlyTheMostCriticalOfThreeIsHeard() {
        val arbiter = AlertVoiceArbiter()

        val voice = arbiter.select(
            listOf(candidate("pwm", 0), candidate("temp", 2), candidate("battery", 1)),
            nowMs = 0L,
        )

        assertEquals("pwm", voice?.alertId)
    }

    @Test
    fun testHigherPriorityPreemptsImmediatelyInsideHoldWindow() {
        val arbiter = AlertVoiceArbiter(minHoldMs = 1000L)
        arbiter.select(listOf(candidate("temp", 2)), nowMs = 0L)

        val voice = arbiter.select(listOf(candidate("temp", 2), candidate("pwm", 0)), nowMs = 10L)

        assertEquals("pwm", voice?.alertId)
    }

    @Test
    fun testEqualPriorityDoesNotAlternateBetweenFrames() {
        val arbiter = AlertVoiceArbiter(minHoldMs = 300L)
        val pair = listOf(candidate("a", 2, progress = 0.4), candidate("b", 2, progress = 0.1))

        val heard = (0..9).map { frame -> arbiter.select(pair, nowMs = frame * 20L)?.alertId }

        assertEquals(List(10) { "a" }, heard)
    }

    @Test
    fun testEqualPriorityChallengerCannotStealInsideHoldWindow() {
        val arbiter = AlertVoiceArbiter(minHoldMs = 1000L)
        arbiter.select(listOf(candidate("a", 2, progress = 0.1)), nowMs = 0L)

        // "b" is further along its curve, but priority is equal: the slot does not move.
        val voice = arbiter.select(
            listOf(candidate("a", 2, progress = 0.1), candidate("b", 2, progress = 0.9)),
            nowMs = 100L,
        )

        assertEquals("a", voice?.alertId)
    }

    @Test
    fun testHeldVoiceKeepsAcceleratingWhileItHoldsTheSlot() {
        val arbiter = AlertVoiceArbiter(minHoldMs = 1000L)
        arbiter.select(listOf(candidate("pwm", 0, progress = 0.2)), nowMs = 0L)

        val voice = arbiter.select(listOf(candidate("pwm", 0, progress = 0.8)), nowMs = 100L)

        assertEquals(0.8, voice?.progress)
    }

    @Test
    fun testSlotIsReleasedWhenTheHeldAlertStopsMatching() {
        val arbiter = AlertVoiceArbiter(minHoldMs = 1000L)
        arbiter.select(listOf(candidate("pwm", 0)), nowMs = 0L)

        // The PWM condition dropped inside the hold window: go silent, do not play it out.
        assertNull(arbiter.select(emptyList(), nowMs = 100L))
    }

    @Test
    fun testSlotIsHandedOverWhenHeldAlertStopsMatchingButOthersRemain() {
        val arbiter = AlertVoiceArbiter(minHoldMs = 1000L)
        arbiter.select(listOf(candidate("pwm", 0)), nowMs = 0L)

        // PWM dropped while the overheat alert still holds. The slot has to move across at
        // once, not finish the PWM hold window first.
        val voice = arbiter.select(listOf(candidate("temp", 1)), nowMs = 10L)

        assertEquals("temp", voice?.alertId)
    }

    @Test
    fun testEqualPriorityHandsOverOnceHoldExpires() {
        val arbiter = AlertVoiceArbiter(minHoldMs = 300L)
        arbiter.select(listOf(candidate("a", 2, progress = 0.1)), nowMs = 0L)

        val voice = arbiter.select(
            listOf(candidate("a", 2, progress = 0.1), candidate("b", 2, progress = 0.9)),
            nowMs = 400L,
        )

        assertEquals("b", voice?.alertId)
    }

    @Test
    fun testHoldWindowExtendsToPatternDurationWhenLongerThanMinHold() {
        val arbiter = AlertVoiceArbiter(minHoldMs = 100L)
        arbiter.select(listOf(longCandidate("a", 2, progress = 0.1)), nowMs = 0L)

        // longPattern runs 500 ms, longer than minHoldMs (100 ms). At 300 ms the hold window
        // is still open, so an equally critical "b" cannot take the slot.
        val voice = arbiter.select(
            listOf(longCandidate("a", 2, progress = 0.1), longCandidate("b", 2, progress = 0.9)),
            nowMs = 300L,
        )

        assertEquals("a", voice?.alertId)
    }

    @Test
    fun testThreeWayTieBreaksByLexicographicallySmallestId() {
        val arbiter = AlertVoiceArbiter()

        val voice = arbiter.select(
            listOf(
                candidate("charlie", 2, progress = 0.5),
                candidate("alpha", 2, progress = 0.5),
                candidate("bravo", 2, progress = 0.5),
            ),
            nowMs = 0L,
        )

        assertEquals("alpha", voice?.alertId)
    }
}
