package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * A wheel setting write bound to an alert.
 *
 * Sent once when the condition becomes true - edge-triggered, whatever the alert's
 * [AlertType] - and rolled back once, to the value captured immediately before the write,
 * when the condition falls back through its hysteresis band.
 *
 * @param id Unique identifier of the command within its alert.
 * @param settingKey Key of the wheel setting, meaningful only for the wheel the alert is
 *   bound to, see [Alert.wheelId].
 * @param targetValue Value to write when the alert fires.
 */
data class AlertCommand(
    val id: String,
    val settingKey: String,
    val targetValue: Int,
)
