package pw.vasilevskiy.loeuc.shared.alerts.engine

/**
 * What the engine remembers about one condition between telemetry frames.
 *
 * @param isConditionMet whether the condition holds right now.
 * @param firstMetTimestampMs when it first became true, which is what minDurationMs measures
 *   against.
 * @param lastTriggeredTimestampMs when the alert last fired.
 * @param lastFiredStepIndex which step of
 *   [pw.vasilevskiy.loeuc.shared.alerts.model.ConditionTemplate.repeatEveryValue] the condition
 *   last fired on, or null when it has no step or has not fired since becoming true. Reset
 *   whenever the condition stops holding, so the ordinary hysteresis rearm and the step rearm
 *   cannot both claim the same firing.
 */
data class ConditionStateHistory(
    val isConditionMet: Boolean = false,
    val firstMetTimestampMs: Long = 0L,
    val lastTriggeredTimestampMs: Long = 0L,
    val lastFiredStepIndex: Int? = null
)
