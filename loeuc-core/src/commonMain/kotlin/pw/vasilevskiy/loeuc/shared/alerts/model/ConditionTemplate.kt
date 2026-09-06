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
    val accelMaxTargetValue: Double? = null,
    /**
     * Rearm the condition every this many units of its metric, without waiting for it to fall
     * back through the hysteresis band.
     *
     * Trip distance is why this exists: "trip >= 1 km" holds for the rest of the ride once it
     * is true, because distance does not go back down, so a one-shot alert on it fires exactly
     * once. With a step of 1 it fires at 1, 2, 3 and so on.
     *
     * Null leaves the condition as it was. Ignored for [ComparisonOperator.EQUALS], which has
     * no direction to step in.
     */
    val repeatEveryValue: Double? = null
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

    /**
     * Which step past the threshold [value] sits on, counting in the operator's own direction,
     * or null when this condition has no step.
     *
     * Step 0 is the threshold itself, so the first firing is the ordinary one and the step only
     * ever adds firings after it. Counting in the operator's direction is what makes
     * "battery <= 50, every 10" walk down through 50, 40, 30 rather than up.
     */
    fun stepIndexFor(value: Double): Int? {
        val step = repeatEveryValue ?: return null
        if (step <= 0.0) return null

        if (operator == ComparisonOperator.EQUALS) return null
        val distance = distanceFromTarget(value)
        if (distance < 0.0) return 0

        // A tiny epsilon so a value landing exactly on a step counts as having reached it:
        // floating point makes 3 * 0.1 land just under 0.3, and the rider would lose a step.
        return kotlin.math.floor(distance / step + 1e-9).toInt()
    }

    /**
     * What the step should do on this frame, or null when this condition has no step.
     *
     * @param lastFired the step index the condition last fired on, or null if it has not fired
     *   since the condition became true.
     * @return the index to remember, and whether this frame is a rearm.
     */
    fun advanceStep(value: Double, lastFired: Int?): StepAdvance? {
        val step = repeatEveryValue ?: return null
        if (step <= 0.0) return null
        val index = stepIndexFor(value) ?: return null

        if (lastFired == null) {
            // The condition has only just become true. This firing is the ordinary one; the
            // step adds firings after it, it does not add one here.
            return StepAdvance(index, rearmed = false)
        }
        if (index > lastFired) return StepAdvance(index, rearmed = true)

        // Falling back is measured in the metric, not in step numbers, and that distinction is
        // the whole hysteresis of the step. A step boundary is exactly where the value hovers:
        // for "battery <= 50, every 10" the readings 39 and 41 sit on either side of the
        // boundary at 40, so a two-unit wobble on regen would cross it again and again. The
        // index only goes back down once the value has retreated a full step past the boundary
        // it fired on - for that example, back to 50.
        val distance = distanceFromTarget(value)
        val retreatedTo = (lastFired - 1) * step
        if (distance <= retreatedTo) return StepAdvance(index, rearmed = false)

        return StepAdvance(lastFired, rearmed = false)
    }

    private fun distanceFromTarget(value: Double): Double = when (operator) {
        ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_OR_EQUAL ->
            value - targetValue
        ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL ->
            targetValue - value
        ComparisonOperator.EQUALS -> 0.0
    }

    /** One frame's worth of step bookkeeping: see [advanceStep]. */
    data class StepAdvance(val index: Int, val rearmed: Boolean)

    private fun computeDefaultResetValue(): Double {
        val delta = kotlin.math.max(kotlin.math.abs(targetValue * 0.02), 0.5)
        return when (operator) {
            ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_OR_EQUAL -> targetValue - delta
            ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_OR_EQUAL -> targetValue + delta
            ComparisonOperator.EQUALS -> targetValue
        }
    }
}
