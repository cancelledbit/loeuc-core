package pw.vasilevskiy.loeuc.shared.alerts.engine

import pw.vasilevskiy.loeuc.shared.alerts.model.AlertVoice
import pw.vasilevskiy.loeuc.shared.alerts.model.WaveShape

/**
 * What is sounding at one instant: whether the gate is open, at which frequency, in which
 * shape. Oscillator phase is deliberately absent - it runs continuously for the whole alarm
 * and belongs to the renderer.
 */
data class VoiceSample(
    val gateOpen: Boolean,
    val frequencyHz: Double,
    val volume: Float,
    val waveShape: WaveShape,
)

/**
 * Unrolls an [AlertVoice] over time.
 *
 * One cycle is the pattern's steps with their own gaps, followed by a tail gap of
 * [AlertVoice.intervalMs]. When [AlertVoice.continuous] is set there are no gaps at all and
 * the last step's tone is held: that is how beeping stops being beeping and becomes one
 * continuous tone, instead of speeding up without end.
 */
object VoiceTimeline {

    private const val MinFrequencyHz = 100.0
    private const val MaxFrequencyHz = 5000.0

    fun cycleDurationMs(voice: AlertVoice): Double {
        val pattern = voice.pattern.steps.sumOf { (it.durationMs + it.pauseAfterMs).toDouble() }
        return pattern + voice.intervalMs.toDouble()
    }

    fun sampleAt(voice: AlertVoice, tMs: Double): VoiceSample {
        val steps = voice.pattern.steps
        if (steps.isEmpty()) {
            return VoiceSample(false, MinFrequencyHz, 0f, WaveShape.SINE)
        }

        if (voice.continuous) {
            val last = steps.last()
            return VoiceSample(
                gateOpen = true,
                frequencyHz = clampFrequency(last.frequencyHz * voice.pitchRatio),
                volume = last.volume,
                waveShape = last.waveShape,
            )
        }

        val cycle = cycleDurationMs(voice)
        if (cycle <= 0.0) {
            return VoiceSample(false, MinFrequencyHz, 0f, steps.last().waveShape)
        }

        var offset = tMs.mod(cycle)
        for (step in steps) {
            if (offset < step.durationMs) {
                return VoiceSample(
                    gateOpen = true,
                    frequencyHz = clampFrequency(step.frequencyHz * voice.pitchRatio),
                    volume = step.volume,
                    waveShape = step.waveShape,
                )
            }
            offset -= step.durationMs
            if (offset < step.pauseAfterMs) {
                return VoiceSample(false, MinFrequencyHz, 0f, step.waveShape)
            }
            offset -= step.pauseAfterMs
        }

        // Whatever is left of the cycle is the tail gap of intervalMs.
        return VoiceSample(false, MinFrequencyHz, 0f, steps.last().waveShape)
    }

    private fun clampFrequency(hz: Double): Double = hz.coerceIn(MinFrequencyHz, MaxFrequencyHz)
}
