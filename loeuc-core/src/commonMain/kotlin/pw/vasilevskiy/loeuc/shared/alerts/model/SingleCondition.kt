package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * A [ConditionTemplate], reusable or inline, bound to one [MetricId].
 */
data class SingleCondition(
    override val id: String,
    val metric: MetricId,
    val template: ConditionTemplate
) : AlertConditionItem {

    companion object {
        fun create(
            id: String,
            metric: MetricId,
            operator: ComparisonOperator,
            targetValue: Double,
            resetValue: Double? = null,
            minDurationMs: Long = 0L,
            accelMaxTargetValue: Double? = null,
            templateName: String = "${metric.displayNameRu} ${operator.symbol} $targetValue"
        ): SingleCondition {
            val template = ConditionTemplate(
                id = "tpl_$id",
                name = templateName,
                operator = operator,
                targetValue = targetValue,
                resetValue = resetValue,
                minDurationMs = minDurationMs,
                accelMaxTargetValue = accelMaxTargetValue
            )
            return SingleCondition(id, metric, template)
        }
    }
}
