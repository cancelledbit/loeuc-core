package pw.vasilevskiy.loeuc.shared.alerts.engine

/**
 * What the engine remembers about one condition between telemetry frames.
 *
 * @param isConditionMet whether the condition holds right now.
 * @param firstMetTimestampMs when it first became true, which is what minDurationMs measures
 *   against.
 * @param lastTriggeredTimestampMs when the alert last fired.
 */
data class ConditionStateHistory(
    val isConditionMet: Boolean = false,
    val firstMetTimestampMs: Long = 0L,
    val lastTriggeredTimestampMs: Long = 0L
)
