package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * Comparison operators available to alert conditions.
 */
enum class ComparisonOperator(val symbol: String, val displayNameRu: String, val displayNameEn: String) {
    GREATER_THAN(">", "Больше", "Greater than"),
    GREATER_OR_EQUAL(">=", "Больше или равно", "Greater than or equal"),
    LESS_THAN("<", "Меньше", "Less than"),
    LESS_OR_EQUAL("<=", "Меньше или равно", "Less than or equal"),
    EQUALS("==", "Равно", "Equals");

    fun displayName(isRu: Boolean = true): String = if (isRu) displayNameRu else displayNameEn

    fun compare(value: Double, threshold: Double): Boolean = when (this) {
        GREATER_THAN -> value > threshold
        GREATER_OR_EQUAL -> value >= threshold
        LESS_THAN -> value < threshold
        LESS_OR_EQUAL -> value <= threshold
        EQUALS -> kotlin.math.abs(value - threshold) < 1e-6
    }
}
