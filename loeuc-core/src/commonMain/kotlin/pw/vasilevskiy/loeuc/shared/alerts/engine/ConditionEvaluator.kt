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
     */
    data class EvaluationResult(
        val isSatisfied: Boolean,
        val updatedHistory: ConditionStateHistory,
        val metricValue: Double?
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

        val updatedHistory = history.copy(
            isConditionMet = currentlyRawMet,
            firstMetTimestampMs = updatedFirstMet
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
            metricValue = value
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
    ): Pair<Boolean, Map<String, ConditionStateHistory>> {
        val updatedMap = stateMap.toMutableMap()
        var allMet = true

        for (condition in group.conditions) {
            val key = stateKey(keyPrefix, condition.id)
            val hist = updatedMap[key] ?: ConditionStateHistory()
            val res = evaluateSingle(condition, snapshot, hist)
            updatedMap[key] = res.updatedHistory
            if (!res.isSatisfied) {
                allMet = false
            }
        }

        return Pair(allMet, updatedMap)
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
    ): Pair<Boolean, Map<String, ConditionStateHistory>> {
        val updatedMap = stateMap.toMutableMap()
        var anyMet = false

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
                }
                is ConditionGroup -> {
                    val (groupMet, groupState) = evaluateGroup(item, snapshot, updatedMap, keyPrefix)
                    updatedMap.putAll(groupState)
                    if (groupMet) {
                        anyMet = true
                    }
                }
            }
        }

        return Pair(anyMet, updatedMap)
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
