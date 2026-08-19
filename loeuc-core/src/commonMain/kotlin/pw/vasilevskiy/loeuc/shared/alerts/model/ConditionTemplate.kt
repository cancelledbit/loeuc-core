package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * A reusable condition template: the operator, thresholds, hysteresis and hold time, with no
 * metric attached. One template can drive any number of metrics across any number of alerts.
 */
data class ConditionTemplate(
    val id: String,
    val name: String,
    val operator: ComparisonOperator,
    val targetValue: Double,
    val resetValue: Double? = null,
    val minDurationMs: Long = 0L,
    val accelMaxTargetValue: Double? = null
) {
    /**
     * The plain comparison, with no hysteresis or hold time applied.
     */
    fun evaluateRaw(value: Double): Boolean {
        return operator.compare(value, targetValue)
    }

    /**
     * Whether the condition has fallen back through its hysteresis band. When resetValue is
     * not set explicitly the default band is about 2% of targetValue.
     */
    fun evaluateReset(value: Double): Boolean {
        val effectiveReset = resetValue ?: computeDefaultResetValue()
        return when (operator) {
            ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_OR_EQUAL -> value < effectiveReset
            ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL -> value > effectiveReset
            ComparisonOperator.EQUALS -> kotlin.math.abs(value - targetValue) >= 1e-6
        }
    }

    private fun computeDefaultResetValue(): Double {
        val delta = kotlin.math.max(kotlin.math.abs(targetValue * 0.02), 0.5)
        return when (operator) {
            ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_OR_EQUAL -> targetValue - delta
            ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL -> targetValue + delta
            ComparisonOperator.EQUALS -> targetValue
        }
    }
}
