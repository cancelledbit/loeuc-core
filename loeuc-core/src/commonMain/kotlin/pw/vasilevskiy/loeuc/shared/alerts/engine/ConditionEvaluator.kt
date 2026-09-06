package pw.vasilevskiy.loeuc.shared.alerts.engine

import pw.vasilevskiy.loeuc.shared.alerts.model.AlertConditionItem
import pw.vasilevskiy.loeuc.shared.alerts.model.ConditionGroup
import pw.vasilevskiy.loeuc.shared.alerts.model.SingleCondition
import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot

/**
 * Evaluates alert conditions, with hysteresis, a minimum hold time (minDurationMs) and
 * OR/AND composition.
 */
open class ConditionEvaluator {

    /**
     * The outcome of evaluating one condition.
     *
     * @param rearmed the condition crossed another
     *   [pw.vasilevskiy.loeuc.shared.alerts.model.ConditionTemplate.repeatEveryValue] step on
     *   this frame. This is an edge, not a level: it is true for exactly the one frame that
     *   crosses, which is what lets an alert fire again without ever leaving its hysteresis
     *   band.
     */
    data class EvaluationResult(
        val isSatisfied: Boolean,
        val updatedHistory: ConditionStateHistory,
        val metricValue: Double?,
        val rearmed: Boolean = false
    )

    /**
     * The outcome of evaluating a group or a whole alert's conditions.
     *
     * A plain `Pair` carried this before the step existed. It is a named type now because the
     * third value is easy to drop silently at a call site, and dropping it means an alert that
     * quietly never repeats.
     */
    data class ConditionOutcome(
        val isMet: Boolean,
        val rearmed: Boolean,
        val states: Map<String, ConditionStateHistory>
    )

    /**
     * Evaluates one [SingleCondition] against the frame.
     */
    fun evaluateSingle(
        condition: SingleCondition,
        snapshot: TelemetrySnapshot,
        history: ConditionStateHistory
    ): EvaluationResult {
        val value = snapshot.getValue(condition.metric)
            ?: return EvaluationResult(false, history, null)

        val template = condition.template

        val currentlyRawMet = if (history.isConditionMet) {
            // It held before, so ask whether it has fallen back through the reset threshold.
            !template.evaluateReset(value)
        } else {
            // It did not hold, so ask whether it has crossed the trigger threshold.
            template.evaluateRaw(value)
        }

        val updatedFirstMet = if (currentlyRawMet) {
            if (!history.isConditionMet || history.firstMetTimestampMs == 0L) {
                snapshot.timestampMs
            } else {
                history.firstMetTimestampMs
            }
        } else {
            0L
        }

        // The step walks only while the condition holds. Clearing the index as soon as it
        // stops holding keeps the two rearm paths apart: leaving the hysteresis band is the
        // ordinary rearm, crossing a step is the other, and neither should be able to claim a
        // firing that belongs to the other.
        val advance = if (currentlyRawMet) {
            template.advanceStep(value, history.lastFiredStepIndex)
        } else {
            null
        }

        val updatedHistory = history.copy(
            isConditionMet = currentlyRawMet,
            firstMetTimestampMs = updatedFirstMet,
            lastFiredStepIndex = if (currentlyRawMet) advance?.index else null
        )

        val timeInCondition = if (currentlyRawMet && updatedFirstMet > 0L) {
            snapshot.timestampMs - updatedFirstMet
        } else {
            0L
        }

        val isDurationSatisfied = currentlyRawMet && (timeInCondition >= template.minDurationMs)

        return EvaluationResult(
            isSatisfied = isDurationSatisfied,
            updatedHistory = updatedHistory,
            metricValue = value,
            // A step crossed while the hold time has not elapsed is not a firing: minDurationMs
            // gates every way an alert can sound.
            rearmed = isDurationSatisfied && advance?.rearmed == true
        )
    }

    /**
     * Evaluates a [ConditionGroup], whose conditions are combined with AND.
     *
     * @param keyPrefix namespace for the keys in [stateMap], see [stateKey].
     */
    fun evaluateGroup(
        group: ConditionGroup,
        snapshot: TelemetrySnapshot,
        stateMap: Map<String, ConditionStateHistory>,
        keyPrefix: String = ""
    ): ConditionOutcome {
        val updatedMap = stateMap.toMutableMap()
        var allMet = true
        var rearmed = false

        for (condition in group.conditions) {
            val key = stateKey(keyPrefix, condition.id)
            val hist = updatedMap[key] ?: ConditionStateHistory()
            val res = evaluateSingle(condition, snapshot, hist)
            updatedMap[key] = res.updatedHistory
            if (!res.isSatisfied) {
                allMet = false
            }
            if (res.rearmed) {
                rearmed = true
            }
        }

        // A group is AND, so a step crossed inside it only counts while the whole group holds:
        // "trip every 5 km AND speed above 30" must not speak while the rider is stopped.
        return ConditionOutcome(allMet, allMet && rearmed, updatedMap)
    }

    /**
     * Evaluates an alert's top-level [AlertConditionItem]s, which are combined with OR.
     *
     * @param keyPrefix namespace for the keys in [stateMap], see [stateKey].
     */
    open fun evaluateAlertConditions(
        items: List<AlertConditionItem>,
        snapshot: TelemetrySnapshot,
        stateMap: Map<String, ConditionStateHistory>,
        keyPrefix: String = ""
    ): ConditionOutcome {
        val updatedMap = stateMap.toMutableMap()
        var anyMet = false
        var rearmed = false

        for (item in items) {
            when (item) {
                is SingleCondition -> {
                    val key = stateKey(keyPrefix, item.id)
                    val hist = updatedMap[key] ?: ConditionStateHistory()
                    val res = evaluateSingle(item, snapshot, hist)
                    updatedMap[key] = res.updatedHistory
                    if (res.isSatisfied) {
                        anyMet = true
                    }
                    if (res.rearmed) {
                        rearmed = true
                    }
                }
                is ConditionGroup -> {
                    val group = evaluateGroup(item, snapshot, updatedMap, keyPrefix)
                    updatedMap.putAll(group.states)
                    if (group.isMet) {
                        anyMet = true
                    }
                    if (group.rearmed) {
                        rearmed = true
                    }
                }
            }
        }

        return ConditionOutcome(anyMet, rearmed, updatedMap)
    }

    companion object {
        /**
         * Key under which one condition's state lives in the engine's shared map.
         *
         * Condition ids are unique only within their own alert - an editor may well name the
         * first condition of every new alert identically - so the state has to be namespaced
         * by the alert. Without that, two alerts would overwrite each other's hysteresis and
         * `firstMetTimestampMs` on every telemetry frame, and only one of them would really
         * work.
         */
        fun stateKey(keyPrefix: String, conditionId: String): String =
            if (keyPrefix.isEmpty()) conditionId else "$keyPrefix:$conditionId"
    }
}
