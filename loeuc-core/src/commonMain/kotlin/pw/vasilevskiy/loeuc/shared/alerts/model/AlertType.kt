package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * How an alert plays and repeats once its conditions hold.
 */
sealed interface AlertType {

    /**
     * Fires once when the conditions become true, and rearms only when they fall back through
     * the hysteresis band or [autoResetTimeoutMs] expires.
     */
    data class OneShot(
        val autoResetTimeoutMs: Long = 0L // 0 = rearm strictly on leaving the hysteresis band
    ) : AlertType

    /**
     * Repeats every [intervalMs] milliseconds for as long as the conditions hold.
     */
    data class Repeating(
        val intervalMs: Long = 3000L // repeat period
    ) : AlertType

    /**
     * Repeats faster as the metric climbs: the period shrinks from [maxIntervalMs] to
     * [minIntervalMs] across the condition band, and at the top of the curve the beeping
     * merges into one continuous tone.
     *
     * @param pitchRiseRatio Multiplier applied to every frequency in the pattern at progress
     *   1.0; 1.0 leaves the pitch alone. A multiplier rather than an absolute ceiling: a
     *   pattern is a sequence of steps at different frequencies, and a ceiling in hertz would
     *   crush them into unison at the top of the curve. The PWM alarm curve, 1200 -> 2800 Hz,
     *   is exactly 2.33.
     * @param continuousFromRatio Progress at which the gap drops to zero and the gate stays
     *   open. Not derived from [minIntervalMs]: the merge into a tone has to happen where it
     *   means something for the metric - for PWM that is 99%, one percent before the wheel
     *   runs out of headroom - and not wherever the interval happened to land.
     */
    data class Accelerating(
        val minIntervalMs: Long = 50L,   // period at the top of the band
        val maxIntervalMs: Long = 2000L, // period as the alert starts
        val curve: AccelerationCurve = AccelerationCurve.LINEAR,
        val pitchRiseRatio: Double = 1.0,
        val continuousFromRatio: Double = 0.9,
    ) : AlertType
}

enum class AccelerationCurve {
    LINEAR,
    EXPONENTIAL,
    LOGARITHMIC
}
