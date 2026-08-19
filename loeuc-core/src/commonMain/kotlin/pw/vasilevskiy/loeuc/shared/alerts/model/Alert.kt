package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * One rider-defined alert on a wheel.
 *
 * @param id Unique identifier of the alert.
 * @param name Display name, for example "PWM above 80%".
 * @param enabled Whether the alert is active.
 * @param priority 0 is the most critical, 10 the most informational.
 * @param wheelId Identifier of one wheel, or null for an alert that applies to every wheel.
 * @param type How the alert fires: OneShot, Repeating or Accelerating.
 * @param conditionItems Condition items, combined with OR at the top level.
 * @param soundPattern The sound to play: a reusable template or an inline pattern.
 * @param commands Wheel setting writes bound to this alert, see [AlertCommand]. A non-empty
 *   list requires a non-null [wheelId]: which settings exist depends on the wheel that is
 *   connected, so an alert that writes settings cannot be a global one.
 */
data class Alert(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val priority: Int = 5,
    val wheelId: String? = null,
    val type: AlertType,
    val conditionItems: List<AlertConditionItem>,
    val soundPattern: SoundPattern,
    val commands: List<AlertCommand> = emptyList()
) {
    init {
        require(priority in 0..10) { "Priority must be between 0 (highest) and 10 (lowest). Received: $priority" }
        require(commands.isEmpty() || !wheelId.isNullOrBlank()) {
            "Alert with commands must be bound to a specific wheelId (cannot be global)."
        }
    }
}
