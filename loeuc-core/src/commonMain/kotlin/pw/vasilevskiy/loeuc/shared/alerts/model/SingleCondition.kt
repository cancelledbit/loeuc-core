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
        /**
         * The name an inline template gets when the caller does not supply one.
         *
         * The language is an explicit argument with no default. This string is written into the
         * rider's alert library and shown back to them, so a library-side default of one language
         * would quietly put that language into every consumer's data.
         */
        fun autoTemplateName(
            metric: MetricId,
            operator: ComparisonOperator,
            targetValue: Double,
            isRu: Boolean,
        ): String = "${metric.displayName(isRu)} ${operator.symbol} $targetValue"

        /**
         * Builds a condition around a freshly created inline template.
         *
         * [templateName] falls back to the English [autoTemplateName]: the library has no locale,
         * and a name in a language the consumer never asked for is worse than an English one it
         * can recognise and replace. A caller that knows the rider's language builds the name with
         * [autoTemplateName] and passes it. Kotlin defaults do not reach Swift, so on iOS the
         * argument is required anyway.
         */
        fun create(
            id: String,
            metric: MetricId,
            operator: ComparisonOperator,
            targetValue: Double,
            resetValue: Double? = null,
            minDurationMs: Long = 0L,
            accelMaxTargetValue: Double? = null,
            templateName: String = autoTemplateName(metric, operator, targetValue, isRu = false)
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
