package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertEngine
import pw.vasilevskiy.loeuc.shared.alerts.engine.VoiceTimeline
import pw.vasilevskiy.loeuc.shared.alerts.model.*
import kotlin.test.Test
import kotlin.test.fail
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import pw.vasilevskiy.loeuc.shared.alerts.engine.ConditionEvaluator
import pw.vasilevskiy.loeuc.shared.alerts.engine.ConditionStateHistory

/**
 * An enabled alert against live telemetry - what happens the moment a wheel connects.
 *
 * What is asserted is not the arithmetic but that the path runs at all: on Kotlin/Native an
 * exception kills the process, and "it crashes when I connect" looks exactly like this.
 */
class EnabledAlertOnConnectTest {

    private fun ramp(min: Long, max: Long) = AlertType.Accelerating(
        minIntervalMs = min,
        maxIntervalMs = max,
        curve = AccelerationCurve.LINEAR,
        pitchRiseRatio = 1.6,
        continuousFromRatio = 0.9
    )

    private fun alertWith(type: AlertType, ceiling: Double?, minDurationMs: Long = 0L) = Alert(
        id = "a",
        name = "a",
        enabled = true,
        priority = 0,
        type = type,
        conditionItems = listOf(
            SingleCondition(
                "c",
                MetricId.PWM_PERCENT,
                ConditionTemplate(
                    id = "t",
                    name = "t",
                    operator = ComparisonOperator.GREATER_OR_EQUAL,
                    targetValue = 80.0,
                    resetValue = 74.0,
                    minDurationMs = minDurationMs,
                    accelMaxTargetValue = ceiling
                )
            )
        ),
        soundPattern = SoundPattern.tripleWarningAlarm(true)
    )

    @Test
    fun liveTelemetryDoesNotThrowForAnyEnabledAlert() {
        val types = listOf(
            AlertType.OneShot(0L),
            AlertType.OneShot(60_000L),
            AlertType.Repeating(2_000L),
            ramp(50L, 800L),
            ramp(800L, 50L),
            ramp(0L, 0L),
        )
        for (type in types) {
            for (ceiling in listOf(null, 95.0, 80.0, 40.0)) {
                for (hold in listOf(0L, 300L)) {
                    var lastVoice: AlertVoice? = null
                    val engine = AlertEngine(voicePublisher = { lastVoice = it })
                    val alert = alertWith(type, ceiling, hold)
                    val where = "type=$type ceiling=$ceiling hold=$hold"
                    // The full arc: below the threshold, across it, ramping to the top and beyond.
                    var t = 1_000L
                    for (pwm in listOf(0.0, 50.0, 79.0, 80.0, 85.0, 90.0, 95.0, 99.0, 120.0, 60.0)) {
                        repeat(5) {
                            t += 100L
                            try {
                                engine.processTelemetry(
                                    snapshot = TelemetrySnapshot.create(t, MetricId.PWM_PERCENT to pwm),
                                    activeAlerts = listOf(alert)
                                )
                            } catch (e: Throwable) {
                                fail("processTelemetry threw at pwm=$pwm, $where: $e")
                            }
                            val voice = lastVoice ?: return@repeat
                            try {
                                VoiceTimeline.sampleAt(voice, (t % 997).toDouble())
                            } catch (e: Throwable) {
                                fail("VoiceTimeline threw at pwm=$pwm, $where: $e")
                            }
                        }
                    }
                }
            }
        }
    }

    /// A voice has to appear once the condition holds: a silent alarm is worse than none.
    @Test
    fun crossingTheThresholdActuallyPublishesAVoice() {
        for (type in listOf(
            AlertType.OneShot(0L),
            AlertType.Repeating(2_000L),
            ramp(50L, 800L),
        )) {
            var heard = false
            val engine = AlertEngine(voicePublisher = { if (it != null) heard = true })
            val alert = alertWith(type, ceiling = 95.0)
            var t = 1_000L
            for (pwm in listOf(50.0, 85.0, 90.0, 95.0)) {
                repeat(5) {
                    t += 100L
                    engine.processTelemetry(
                        snapshot = TelemetrySnapshot.create(t, MetricId.PWM_PERCENT to pwm),
                        activeAlerts = listOf(alert)
                    )
                }
            }
            assertTrue(heard, "the engine published no voice at all for $type")
        }
    }
}

/**
 * The failure boundary around a single alert.
 *
 * It asserts the one thing the boundary exists for: a broken alert takes down neither its
 * neighbour nor the whole telemetry frame. On iOS "takes down" means the app disappears.
 */
class AlertFailureBoundaryTest {

    private class ThrowingEvaluator(private val badAlertId: String) : ConditionEvaluator() {
        override fun evaluateAlertConditions(
            items: List<AlertConditionItem>,
            snapshot: TelemetrySnapshot,
            stateMap: Map<String, ConditionStateHistory>,
            keyPrefix: String,
        ): Pair<Boolean, Map<String, ConditionStateHistory>> {
            if (keyPrefix == badAlertId) throw IllegalStateException("broken alert")
            return super.evaluateAlertConditions(items, snapshot, stateMap, keyPrefix)
        }
    }

    private fun alert(id: String, metric: MetricId) = Alert(
        id = id,
        name = id,
        enabled = true,
        priority = 0,
        type = AlertType.Repeating(1_000L),
        conditionItems = listOf(
            SingleCondition(
                "c_$id",
                metric,
                ConditionTemplate(
                    id = "t",
                    name = "t",
                    operator = ComparisonOperator.GREATER_OR_EQUAL,
                    targetValue = 10.0
                )
            )
        ),
        soundPattern = SoundPattern.singleBeep(true)
    )

    @Test
    fun aBrokenAlertNeitherCrashesTheFrameNorSilencesItsNeighbour() {
        var heard: AlertVoice? = null
        val engine = AlertEngine(
            voicePublisher = { heard = it },
            evaluator = ThrowingEvaluator(badAlertId = "bad")
        )
        engine.processTelemetry(
            snapshot = TelemetrySnapshot.create(
                1_000L,
                MetricId.PWM_PERCENT to 90.0,
                MetricId.SPEED_KMH to 50.0
            ),
            activeAlerts = listOf(alert("bad", MetricId.PWM_PERCENT), alert("good", MetricId.SPEED_KMH))
        )
        assertEquals("good", heard?.alertId, "a healthy alert has to sound despite a broken neighbour")
    }
}
