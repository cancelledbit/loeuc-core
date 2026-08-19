package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AccelerationCalculator
import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertCadence
import pw.vasilevskiy.loeuc.shared.alerts.engine.VoiceTimeline
import pw.vasilevskiy.loeuc.shared.alerts.model.AccelerationCurve
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertVoice
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundPattern
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundStep
import pw.vasilevskiy.loeuc.shared.alerts.model.WaveShape
import kotlin.test.Test
import kotlin.test.fail

/**
 * A sweep over ramp settings: every combination an editor is able to save has to pass through
 * the curve and the timeline without throwing.
 *
 * It exists because on Kotlin/Native an exception is not an error message but the death of the
 * application: an uncaught one kills the process, and the rider sees not "something went
 * wrong" but the app vanishing.
 */
class RampCrashProbeTest {

    private val tone = SoundPattern(
        id = "p",
        name = "p",
        steps = listOf(SoundStep(frequencyHz = 1200, durationMs = 120, pauseAfterMs = 50, waveShape = WaveShape.SINE))
    )

    @Test
    fun everyRampTheEditorCanSaveSurvivesTheCurve() {
        val intervals = listOf(0L, 1L, 50L, 800L, 1500L, 5000L)
        val ratios = listOf(0.0, 0.5, 0.9, 1.0)
        val pitches = listOf(1.0, 1.6, 3.0)

        for (min in intervals) {
            for (max in intervals) {
                for (curve in AccelerationCurve.entries) {
                    for (continuousFrom in ratios) {
                        for (pitch in pitches) {
                            val type = AlertType.Accelerating(
                                minIntervalMs = min,
                                maxIntervalMs = max,
                                curve = curve,
                                pitchRiseRatio = pitch,
                                continuousFromRatio = continuousFrom
                            )
                            for (progress in listOf(0.0, 0.25, 0.5, 0.89, 0.9, 1.0)) {
                                val where = "min=$min max=$max curve=$curve cont=$continuousFrom pitch=$pitch p=$progress"
                                val cadence = try {
                                    AlertCadence.forProgress(type, progress)
                                } catch (t: Throwable) {
                                    fail("AlertCadence.forProgress threw at $where: $t")
                                }
                                try {
                                    AccelerationCalculator.calculateIntervalMs(type, 85.0, 80.0, 95.0)
                                } catch (t: Throwable) {
                                    fail("calculateIntervalMs threw at $where: $t")
                                }
                                val voice = AlertVoice(
                                    alertId = "a",
                                    pattern = tone,
                                    progress = progress,
                                    intervalMs = cadence.intervalMs,
                                    pitchRatio = cadence.pitchRatio,
                                    continuous = cadence.continuous
                                )
                                try {
                                    VoiceTimeline.sampleAt(voice, 37.0)
                                } catch (t: Throwable) {
                                    fail("VoiceTimeline.sampleAt threw at $where: $t")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
