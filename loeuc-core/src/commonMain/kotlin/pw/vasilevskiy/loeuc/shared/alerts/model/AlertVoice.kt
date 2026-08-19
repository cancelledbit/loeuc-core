package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * The current state of the one voice that is sounding.
 *
 * This is a level, not an event: it is recomputed on every telemetry frame and published as a
 * current value, or null when nothing is sounding. A "play this pattern" event cannot describe
 * a continuous tone at all - an event ends, a tone does not.
 *
 * @param progress Position between the condition thresholds, 0..1. Always 0.0 for OneShot and
 *   Repeating.
 * @param intervalMs Gap between repetitions of the pattern. 0 when [continuous].
 * @param pitchRatio Multiplier applied to every frequency in the pattern.
 * @param continuous Hold the gate open, with no gap at all.
 */
data class AlertVoice(
    val alertId: String,
    val pattern: SoundPattern,
    val progress: Double,
    val intervalMs: Long,
    val pitchRatio: Double = 1.0,
    val continuous: Boolean = false,
)
