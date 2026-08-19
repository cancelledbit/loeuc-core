package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * Marker interface for the items in an alert's condition list. Top-level items are combined
 * with OR.
 */
sealed interface AlertConditionItem {
    val id: String
}
