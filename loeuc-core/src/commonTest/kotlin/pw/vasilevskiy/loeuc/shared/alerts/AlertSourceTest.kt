package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertEngine
import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertSource
import pw.vasilevskiy.loeuc.shared.alerts.model.Alert
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType
import pw.vasilevskiy.loeuc.shared.alerts.model.ComparisonOperator
import pw.vasilevskiy.loeuc.shared.alerts.model.ConditionTemplate
import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.alerts.model.SingleCondition
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundPattern
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundStep
import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine owns no storage: it evaluates the alerts it is handed. There are three ways to
 * hand them over, and the point of these tests is that none of them surprises the caller.
 */
class AlertSourceTest {

    @Test
    fun anAlertSourceIsConsultedOnEveryFrame() {
        var reads = 0
        val engine = AlertEngine(alertSource = { reads++; listOf(speedAlert("from_source")) })

        val first = engine.processTelemetry(snapshot(1_000L, speed = 50.0))
        val second = engine.processTelemetry(snapshot(2_000L, speed = 50.0))

        assertEquals(2, reads, "the source must be read once per frame, not cached")
        assertTrue(first.isNotEmpty() || second.isNotEmpty())
        assertEquals("from_source", (first + second).first().alert.id)
    }

    /** A rider editing rules mid-ride is the normal case, not an edge case. */
    @Test
    fun alertsChangedBetweenFramesTakeEffectImmediately() {
        var alerts = listOf(speedAlert("first"))
        val engine = AlertEngine(alertSource = { alerts })

        val before = engine.processTelemetry(snapshot(1_000L, speed = 50.0))
        alerts = emptyList()
        val after = engine.processTelemetry(snapshot(2_000L, speed = 50.0))

        assertEquals("first", before.firstOrNull()?.alert.let { it?.id })
        assertTrue(after.isEmpty(), "an alert removed from the source must stop firing")
    }

    /** Without a source the engine keeps evaluating whatever setAlerts last gave it. */
    @Test
    fun withoutASourceTheEngineKeepsTheAlertsItWasGiven() {
        val engine = AlertEngine()
        engine.setAlerts(listOf(speedAlert("pushed")))

        val events = engine.processTelemetry(snapshot(1_000L, speed = 50.0))

        assertEquals("pushed", events.firstOrNull()?.alert?.id)
    }

    @Test
    fun anExplicitListOutranksTheSource() {
        val engine = AlertEngine(alertSource = { listOf(speedAlert("from_source")) })

        val events = engine.processTelemetry(
            snapshot(1_000L, speed = 50.0),
            activeAlerts = listOf(speedAlert("explicit")),
        )

        assertEquals("explicit", events.firstOrNull()?.alert?.id)
    }
}

private fun snapshot(timestampMs: Long, speed: Double) =
    TelemetrySnapshot(timestampMs, mapOf(MetricId.SPEED_KMH to speed))

private fun speedAlert(id: String) = Alert(
    id = id,
    name = id,
    priority = 1,
    type = AlertType.Repeating(intervalMs = 1L),
    conditionItems = listOf(
        SingleCondition(
            id = "cond_$id",
            metric = MetricId.SPEED_KMH,
            template = ConditionTemplate(
                id = "tpl_$id",
                name = "over 40",
                operator = ComparisonOperator.GREATER_THAN,
                targetValue = 40.0,
                resetValue = 38.0,
            ),
        ),
    ),
    soundPattern = SoundPattern("pattern_$id", "beep", listOf(SoundStep())),
)
