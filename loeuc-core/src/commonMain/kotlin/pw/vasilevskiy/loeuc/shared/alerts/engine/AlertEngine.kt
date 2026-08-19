package pw.vasilevskiy.loeuc.shared.alerts.engine

import pw.vasilevskiy.loeuc.shared.alerts.model.Alert
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertCommand
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertCommandTrigger
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertConditionItem
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertTriggerEvent
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertVoice
import pw.vasilevskiy.loeuc.shared.alerts.model.ComparisonOperator
import pw.vasilevskiy.loeuc.shared.alerts.model.ConditionGroup
import pw.vasilevskiy.loeuc.shared.alerts.model.ConditionTemplate
import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.alerts.model.SingleCondition
import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot

import kotlin.jvm.JvmOverloads

/**
 * The rules engine: it takes a stream of [TelemetrySnapshot]s, decides which alerts hold, ranks
 * them, and publishes the one voice that should be sounding through [voicePublisher].
 *
 * It reads time from `snapshot.timestampMs` and never from a clock of its own, which is what
 * makes durations, cooldowns and ramps behave identically on a live ride and on a recording
 * replayed faster than real time.
 */
class AlertEngine @JvmOverloads constructor(
    /**
     * Called on every telemetry frame with the voice that should be sounding, or null when
     * nothing should. This is a level, not an event: a "play this pattern" event cannot
     * describe a continuous tone, because an event ends and a tone does not.
     */
    private val voicePublisher: (AlertVoice?) -> Unit = {},
    /**
     * Called for each of an alert's commands (see [Alert.commands]) when its condition becomes
     * true ([AlertCommandTrigger.Apply]) and again when it falls back through the hysteresis
     * band ([AlertCommandTrigger.Revert]).
     *
     * Unlike [voicePublisher] this does NOT go through [AlertVoiceArbiter]: a setting write
     * changes how the wheel behaves, not just what the rider hears, so it has to happen even
     * when a higher-priority alert is holding the voice. It is also independent of the alert's
     * [AlertType] - it follows the condition's edges, not the OneShot/Repeating/Accelerating
     * cadence.
     */
    private val commandExecutor: (Alert, AlertCommand, AlertCommandTrigger) -> Unit = { _, _, _ -> },
    private val evaluator: ConditionEvaluator = ConditionEvaluator(),
    private val arbiter: AlertVoiceArbiter = AlertVoiceArbiter(),
    /**
     * Where the alerts come from when [processTelemetry] is called without an explicit list.
     * Null means the engine evaluates whatever [setAlerts] last gave it, so a caller that
     * pushes its rules in has no reason to implement anything.
     */
    private val alertSource: AlertSource? = null,
) {

    private val alerts = mutableListOf<Alert>()

    // Condition tracking state, keyed "alertId:conditionId" -> ConditionStateHistory. The key
    // has to include the alert id (see ConditionEvaluator.stateKey): condition ids are unique
    // only within their own alert.
    private val conditionStateMap = mutableMapOf<String, ConditionStateHistory>()

    // When each alert last fired: alertId -> telemetry timestamp.
    private val alertLastTriggeredMap = mutableMapOf<String, Long>()

    // Whether a OneShot alert has already fired and is waiting to rearm.
    private val oneShotFiredMap = mutableMapOf<String, Boolean>()

    // Whether the condition held on the previous tick: alertId -> Boolean. Used only for the
    // edge detection that applies and reverts commands (Alert.commands), separately from
    // oneShotFiredMap/alertLastTriggeredMap, which drive sound.
    private val alertConditionActiveMap = mutableMapOf<String, Boolean>()

    fun setAlerts(newAlerts: List<Alert>) {
        alerts.clear()
        alerts.addAll(newAlerts)
    }

    fun addAlert(alert: Alert) {
        alerts.removeAll { it.id == alert.id }
        alerts.add(alert)
    }

    fun removeAlert(alertId: String) {
        alerts.removeAll { it.id == alertId }
        conditionStateMap.keys.removeAll { it.startsWith("$alertId:") }
        alertLastTriggeredMap.remove(alertId)
        oneShotFiredMap.remove(alertId)
        alertConditionActiveMap.remove(alertId)
    }

    fun clear() {
        alerts.clear()
        conditionStateMap.clear()
        alertLastTriggeredMap.clear()
        oneShotFiredMap.clear()
        alertConditionActiveMap.clear()
        arbiter.clear()
    }

    /**
     * Processes one telemetry frame.
     *
     * @param snapshot metric values and the timestamp they were observed at.
     * @param currentWheelId identifies the connected wheel, so alerts bound to another one are
     *   skipped.
     * @param activeAlerts the alerts to evaluate. Defaults to the [AlertSource] the engine was
     *   built with, or to the list [setAlerts] last received when there is none.
     * @return the [AlertTriggerEvent]s cleared to play on this frame.
     */
    @JvmOverloads
    fun processTelemetry(
        snapshot: TelemetrySnapshot,
        currentWheelId: String? = null,
        currentWheelAddress: String? = null,
        activeAlerts: List<Alert> = alertSource?.activeAlerts() ?: alerts.toList()
    ): List<AlertTriggerEvent> {
        setAlerts(activeAlerts)
        val candidates = mutableListOf<AlertTriggerEvent>()
        val voiceCandidates = mutableListOf<VoiceCandidate>()
        val nowMs = snapshot.timestampMs

        for (alert in alerts) {
            if (!alert.enabled) continue
            if (!matchesWheel(alert.wheelId, currentWheelId, currentWheelAddress)) continue

            // One broken alert takes down neither the others nor the application.
            //
            // On Kotlin/Native an uncaught exception is not an error message, it is the death
            // of the process. Telemetry frames arrive here dozens of times a second while
            // somebody is riding, so the cost of a slip in this arithmetic is not a silent
            // alarm - it is a screen going dark under a rider at speed. That has happened: an
            // inverted ramp interval crashed on the first frame after connecting (see
            // [AlertCadence.forProgress] and RampCrashProbeTest).
            //
            // The offending alert is skipped, the rest are still evaluated, and the reason is
            // printed - a device console shows it without a debugger attached, which for a
            // crash in a beta build is the only way to learn what failed.
            try {
                processAlert(alert, snapshot, nowMs, candidates, voiceCandidates)
            } catch (t: Throwable) {
                println("AlertEngine: skipped alert ${alert.id}: ${t.stackTraceToString()}")
            }
        }

        // The voice is a level: it is published on every frame, null included.
        val activeVoice = arbiter.select(voiceCandidates, nowMs)
        voicePublisher(activeVoice)

        if (activeVoice == null) return emptyList()

        // Only the alert that currently owns the voice gets an event, and only once its
        // cadence is due. Wheel commands are not tied to this: they follow the condition's
        // edges further up, whoever won the voice.
        val winner = candidates.firstOrNull { it.alert.id == activeVoice.alertId } ?: return emptyList()

        alertLastTriggeredMap[winner.alert.id] = nowMs
        oneShotFiredMap[winner.alert.id] = true
        return listOf(winner)
    }

    /**
     * One alert on one frame: conditions, command edges, cadence and voice.
     *
     * Split out of [processTelemetry] for exactly one reason - to put the failure boundary
     * around a single alert rather than around the whole frame.
     */
    private fun processAlert(
        alert: Alert,
        snapshot: TelemetrySnapshot,
        nowMs: Long,
        candidates: MutableList<AlertTriggerEvent>,
        voiceCandidates: MutableList<VoiceCandidate>,
    ) {
            val (isMet, updatedStates) = evaluator.evaluateAlertConditions(
                items = alert.conditionItems,
                snapshot = snapshot,
                stateMap = conditionStateMap,
                keyPrefix = alert.id
            )

            conditionStateMap.putAll(updatedStates)

            // Command edges: these fire on the condition's transitions alone, independent of
            // AlertType (the sound cadence) and of the arbiter (the contest for the single
            // voice). See AlertCommandTrigger.
            val wasActive = alertConditionActiveMap[alert.id] ?: false
            if (isMet && !wasActive) {
                dispatchCommands(alert, AlertCommandTrigger.Apply)
            } else if (!isMet && wasActive) {
                dispatchCommands(alert, AlertCommandTrigger.Revert)
            }
            alertConditionActiveMap[alert.id] = isMet

            if (!isMet) {
                // The condition no longer holds, so a OneShot alert may fire again.
                oneShotFiredMap[alert.id] = false
                return
            }

            val lastTriggered = alertLastTriggeredMap[alert.id] ?: 0L
            val timeSinceLast = nowMs - lastTriggered

            // The type is dispatched on exactly once, and progress is computed inside the
            // branch that has a use for it.
            //
            // It used to be dispatched twice: one `when` computed progress, this one computed
            // the cadence. On Kotlin/Native the second dispatch on the same value inside this
            // function stopped recognising `Accelerating` - the first answered correctly, the
            // next fell through to `else` - so a ramping alert beeped at somebody else's
            // cadence, once a second, with no rising pitch and no merge into a continuous tone
            // at the top of the curve. The JVM does not do this, so the JVM test suite was
            // green while the alarm on the phone was broken (RampFromStorageTest failed only
            // on the native target).
            //
            // Progress belongs here anyway: OneShot and Repeating have no notion of it, and a
            // separate pass over the type to compute it was redundant from the start.
            val (shouldTrigger, calculatedInterval, voice) = when (val type = alert.type) {
                is AlertType.OneShot -> {
                    val alreadyFired = oneShotFiredMap[alert.id] ?: false
                    val timeoutExpired = type.autoResetTimeoutMs > 0L && (timeSinceLast >= type.autoResetTimeoutMs)
                    Triple(
                        !alreadyFired || timeoutExpired,
                        0L,
                        AlertVoice(alert.id, alert.soundPattern, 0.0, 0L),
                    )
                }
                is AlertType.Repeating -> Triple(
                    lastTriggered == 0L || timeSinceLast >= type.intervalMs,
                    type.intervalMs,
                    AlertVoice(alert.id, alert.soundPattern, 0.0, type.intervalMs),
                )
                is AlertType.Accelerating -> {
                    val value = extractPrimaryMetricValue(alert, snapshot) ?: 0.0
                    val (minTarget, maxTarget) = extractThresholds(alert)
                    val progress = AccelerationCalculator.progressFor(value, minTarget, maxTarget)
                    val cadence = AlertCadence.forProgress(type, progress)
                    Triple(
                        lastTriggered == 0L || timeSinceLast >= cadence.intervalMs,
                        cadence.intervalMs,
                        AlertVoice(
                            alertId = alert.id,
                            pattern = alert.soundPattern,
                            progress = progress,
                            intervalMs = cadence.intervalMs,
                            pitchRatio = cadence.pitchRatio,
                            continuous = cadence.continuous,
                        ),
                    )
                }
                // A branch the types say cannot be reached: `AlertType` is sealed with three
                // subclasses and the compiler considers this `when` exhaustive.
                //
                // It stays as a failure boundary, not as a diagnosis. Without it the cost of a
                // slip is not a silent alarm but a `NoWhenBranchMatchedException`, which on
                // Kotlin/Native kills the process outright - a screen going dark under a rider
                // at speed. With it, the rule sounds once a second: not its own cadence, but
                // audible.
                //
                // The console line now means exactly one thing: a fourth `AlertType` subclass
                // exists and nobody handled it here.
                else -> {
                    println("AlertEngine: unhandled alert type ${alert.type} - add a branch in processAlert")
                    Triple(
                        lastTriggered == 0L || timeSinceLast >= 1_000L,
                        1_000L,
                        AlertVoice(alert.id, alert.soundPattern, 0.0, 1_000L),
                    )
                }
            }

            // Repeating and Accelerating claim the slot on every frame their condition holds,
            // because they are meant to sound for all of it. OneShot does not: it holds the
            // slot for exactly one pass of its pattern and lets go, or a single beep would
            // turn into an endless drone and mute every less critical neighbour forever. It
            // lets go by itself - the alert simply stops being a candidate and the arbiter
            // hands the slot to the next one.
            val ownsVoice = alert.type !is AlertType.OneShot ||
                shouldTrigger ||
                timeSinceLast < alert.soundPattern.totalDurationMs()
            if (ownsVoice) {
                voiceCandidates.add(VoiceCandidate(voice, alert.priority))
            }

            if (shouldTrigger) {
                candidates.add(
                    AlertTriggerEvent(
                        alert = alert,
                        timestampMs = nowMs,
                        currentIntervalMs = calculatedInterval,
                        triggeredMetrics = extractTriggeredMetrics(alert, snapshot)
                    )
                )
            }
    }

    private fun dispatchCommands(alert: Alert, trigger: AlertCommandTrigger) {
        for (command in alert.commands) {
            commandExecutor(alert, command, trigger)
        }
    }

    private fun extractPrimaryMetricValue(alert: Alert, snapshot: TelemetrySnapshot): Double? {
        val firstSingle = findFirstSingleCondition(alert.conditionItems)
        return firstSingle?.let { snapshot.getValue(it.metric) }
    }

    private fun findFirstSingleCondition(items: List<AlertConditionItem>): SingleCondition? {
        for (item in items) {
            when (item) {
                is SingleCondition -> return item
                is ConditionGroup -> {
                    val nested = findFirstSingleCondition(item.conditions)
                    if (nested != null) return nested
                }
            }
        }
        return null
    }

    private fun extractThresholds(alert: Alert): Pair<Double, Double> {
        val firstSingle = findFirstSingleCondition(alert.conditionItems)
        val template = firstSingle?.template
        val minTarget = template?.targetValue ?: 0.0
        val isDecreasing = template?.operator?.let { 
            it == ComparisonOperator.LESS_THAN || it == ComparisonOperator.LESS_OR_EQUAL 
        } ?: false

        val maxTarget = template?.accelMaxTargetValue ?: if (isDecreasing) {
            minTarget * 0.5
        } else {
            minTarget * 1.5
        }
        return Pair(minTarget, maxTarget)
    }

    private fun extractTriggeredMetrics(alert: Alert, snapshot: TelemetrySnapshot): Map<MetricId, Double> {
        val map = mutableMapOf<MetricId, Double>()
        for (item in alert.conditionItems) {
            if (item is SingleCondition) {
                snapshot.getValue(item.metric)?.let { map[item.metric] = it }
            }
        }
        return map
    }

    companion object {
        /**
         * Whether an alert applies to the connected wheel, matched by name or by address.
         */
        fun matchesWheel(
            alertWheelId: String?,
            currentWheelId: String?,
            currentWheelAddress: String? = null
        ): Boolean {
            val target = alertWheelId?.trim()
            if (target.isNullOrEmpty()) return true // a global alert applies to every wheel

            val id = currentWheelId?.trim()
            val addr = currentWheelAddress?.trim()

            // Nothing known about the connected wheel: do not filter the alert out.
            if (id.isNullOrEmpty() && addr.isNullOrEmpty()) return true

            if (!id.isNullOrEmpty()) {
                if (id.equals(target, ignoreCase = true) ||
                    id.contains(target, ignoreCase = true) ||
                    target.contains(id, ignoreCase = true)
                ) {
                    return true
                }
            }

            if (!addr.isNullOrEmpty()) {
                if (addr.equals(target, ignoreCase = true) ||
                    addr.contains(target, ignoreCase = true) ||
                    target.contains(addr, ignoreCase = true)
                ) {
                    return true
                }
            }

            return false
        }
    }
}
