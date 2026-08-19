package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * One step of a sound pattern: a tone, then a gap.
 */
data class SoundStep(
    val frequencyHz: Int = 1000,
    val durationMs: Int = 100,
    val pauseAfterMs: Int = 50,
    val volume: Float = 1.0f,
    val waveShape: WaveShape = WaveShape.SINE
)
