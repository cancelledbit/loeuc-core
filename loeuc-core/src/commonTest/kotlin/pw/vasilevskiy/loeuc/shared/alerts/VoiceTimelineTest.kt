package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.VoiceTimeline
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertVoice
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundPattern
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundStep
import pw.vasilevskiy.loeuc.shared.alerts.model.WaveShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceTimelineTest {

    private val twoStep = SoundPattern(
        id = "p",
        name = "p",
        steps = listOf(
            SoundStep(frequencyHz = 1000, durationMs = 100, pauseAfterMs = 50, waveShape = WaveShape.SINE),
            SoundStep(frequencyHz = 2000, durationMs = 100, pauseAfterMs = 0, waveShape = WaveShape.SQUARE),
        ),
    )

    private fun voice(intervalMs: Long, pitchRatio: Double = 1.0, continuous: Boolean = false) = AlertVoice(
        alertId = "a",
        pattern = twoStep,
        progress = 0.5,
        intervalMs = intervalMs,
        pitchRatio = pitchRatio,
        continuous = continuous,
    )

    @Test
    fun testCycleIsPatternPlusTailInterval() {
        // Pattern: 100 + 50 + 100 + 0 = 250 ms, plus a 400 ms tail gap.
        assertEquals(650.0, VoiceTimeline.cycleDurationMs(voice(intervalMs = 400L)))
    }

    @Test
    fun testGateFollowsStepsAndPauses() {
        val v = voice(intervalMs = 400L)

        assertTrue(VoiceTimeline.sampleAt(v, 10.0).gateOpen)     // inside the first step
        assertFalse(VoiceTimeline.sampleAt(v, 120.0).gateOpen)   // in the gap after it
        assertTrue(VoiceTimeline.sampleAt(v, 200.0).gateOpen)    // inside the second step
        assertFalse(VoiceTimeline.sampleAt(v, 400.0).gateOpen)   // in the tail gap
    }

    @Test
    fun testStepFrequenciesAndShapesAreReported() {
        val v = voice(intervalMs = 400L)

        val first = VoiceTimeline.sampleAt(v, 10.0)
        assertEquals(1000.0, first.frequencyHz)
        assertEquals(WaveShape.SINE, first.waveShape)

        val second = VoiceTimeline.sampleAt(v, 200.0)
        assertEquals(2000.0, second.frequencyHz)
        assertEquals(WaveShape.SQUARE, second.waveShape)
    }

    @Test
    fun testPitchRatioMultipliesEveryStepAndKeepsTheirInterval() {
        val v = voice(intervalMs = 400L, pitchRatio = 2.0)

        assertEquals(2000.0, VoiceTimeline.sampleAt(v, 10.0).frequencyHz)
        assertEquals(4000.0, VoiceTimeline.sampleAt(v, 200.0).frequencyHz)
    }

    @Test
    fun testFrequencyIsClampedToSynthRange() {
        val v = voice(intervalMs = 400L, pitchRatio = 100.0)

        assertEquals(5000.0, VoiceTimeline.sampleAt(v, 10.0).frequencyHz)
    }

    @Test
    fun testContinuousHoldsLastStepToneForever() {
        val v = voice(intervalMs = 0L, pitchRatio = 1.0, continuous = true)

        for (t in listOf(0.0, 137.0, 5_000.0, 120_000.0)) {
            val sample = VoiceTimeline.sampleAt(v, t)
            assertTrue(sample.gateOpen, "the gate closed at t=$t")
            assertEquals(2000.0, sample.frequencyHz, "the tone drifted at t=$t")
            assertEquals(WaveShape.SQUARE, sample.waveShape)
        }
    }

    @Test
    fun testCycleRepeats() {
        val v = voice(intervalMs = 400L)

        assertEquals(
            VoiceTimeline.sampleAt(v, 10.0),
            VoiceTimeline.sampleAt(v, 10.0 + 650.0),
        )
    }
}
