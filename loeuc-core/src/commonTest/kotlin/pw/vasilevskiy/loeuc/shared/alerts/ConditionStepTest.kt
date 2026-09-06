package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.ConditionEvaluator
import pw.vasilevskiy.loeuc.shared.alerts.engine.ConditionStateHistory
import pw.vasilevskiy.loeuc.shared.alerts.model.ComparisonOperator
import pw.vasilevskiy.loeuc.shared.alerts.model.ConditionTemplate
import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.alerts.model.SingleCondition
import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "repeat every N units of the metric" step: what makes "trip >= 1 km" say something more
 * than once per ride.
 */
class ConditionStepTest {

    private fun rising(step: Double?) = ConditionTemplate(
        id = "t", name = "t",
        operator = ComparisonOperator.GREATER_OR_EQUAL,
        targetValue = 5.0,
        repeatEveryValue = step,
    )

    private fun falling(step: Double?) = ConditionTemplate(
        id = "t", name = "t",
        operator = ComparisonOperator.LESS_OR_EQUAL,
        targetValue = 50.0,
        repeatEveryValue = step,
    )

    @Test
    fun no_step_means_no_step() {
        assertNull(rising(null).advanceStep(100.0, null))
        assertNull(rising(0.0).advanceStep(100.0, null))
    }

    @Test
    fun the_first_satisfaction_is_not_a_rearm() {
        // The ordinary firing already happens here; the step only ever adds firings after it.
        val advance = rising(5.0).advanceStep(5.0, lastFired = null)!!
        assertEquals(0, advance.index)
        assertFalse(advance.rearmed)
    }

    @Test
    fun a_rising_metric_steps_up() {
        val t = rising(5.0)
        assertFalse(t.advanceStep(5.0, null)!!.rearmed)
        assertFalse(t.advanceStep(9.0, 0)!!.rearmed)
        assertTrue(t.advanceStep(10.0, 0)!!.rearmed)
        assertEquals(1, t.advanceStep(10.0, 0)!!.index)
        assertTrue(t.advanceStep(15.0, 1)!!.rearmed)
        assertEquals(2, t.advanceStep(15.0, 1)!!.index)
    }

    @Test
    fun a_falling_metric_steps_down() {
        // "Battery <= 50, every 10" has to walk 50, 40, 30 - not upwards.
        val t = falling(10.0)
        assertFalse(t.advanceStep(50.0, null)!!.rearmed)
        assertTrue(t.advanceStep(40.0, 0)!!.rearmed)
        assertEquals(1, t.advanceStep(40.0, 0)!!.index)
        assertTrue(t.advanceStep(30.0, 1)!!.rearmed)
        assertEquals(2, t.advanceStep(30.0, 1)!!.index)
    }

    @Test
    fun wobbling_across_a_boundary_does_not_speak_twice() {
        // Regeneration makes the charge hover. 41 and 39 sit either side of the 40 boundary,
        // and a rule that only compared step numbers would announce "battery 40" every time
        // the reading crossed it.
        val t = falling(10.0)
        assertTrue(t.advanceStep(39.0, 0)!!.rearmed)

        val backUp = t.advanceStep(41.0, 1)!!
        assertFalse(backUp.rearmed)
        assertEquals(1, backUp.index, "the index must not fall back one unit past a boundary")

        val downAgain = t.advanceStep(39.0, 1)!!
        assertFalse(downAgain.rearmed, "crossing the same boundary again is not a new step")
    }

    @Test
    fun retreating_a_full_step_arms_the_boundary_again() {
        // Charging back up to the threshold is a real return, not a wobble.
        val t = falling(10.0)
        assertTrue(t.advanceStep(40.0, 0)!!.rearmed)

        val recharged = t.advanceStep(50.0, 1)!!
        assertEquals(0, recharged.index)
        assertFalse(recharged.rearmed)

        assertTrue(t.advanceStep(40.0, 0)!!.rearmed)
    }

    @Test
    fun equals_has_no_direction_to_step_in() {
        val t = ConditionTemplate(
            id = "t", name = "t",
            operator = ComparisonOperator.EQUALS,
            targetValue = 42.0,
            repeatEveryValue = 5.0,
        )
        assertNull(t.advanceStep(42.0, null))
    }

    @Test
    fun a_value_landing_exactly_on_a_step_counts() {
        // 0.1 * 3 does not equal 0.3 in binary floating point, and without the epsilon the
        // rider would silently lose that step.
        val t = ConditionTemplate(
            id = "t", name = "t",
            operator = ComparisonOperator.GREATER_OR_EQUAL,
            targetValue = 0.0,
            repeatEveryValue = 0.1,
        )
        assertEquals(3, t.stepIndexFor(0.30000000000000004))
    }

    @Test
    fun the_evaluator_reports_the_step_as_an_edge() {
        val evaluator = ConditionEvaluator()
        val condition = SingleCondition(id = "c", metric = MetricId.TRIP_DISTANCE_KM, template = rising(5.0))

        var history = ConditionStateHistory()

        val first = evaluator.evaluateSingle(
            condition, TelemetrySnapshot.create(1000L, MetricId.TRIP_DISTANCE_KM to 5.0), history
        )
        assertTrue(first.isSatisfied)
        assertFalse(first.rearmed, "the first satisfaction is the ordinary firing")
        history = first.updatedHistory

        val between = evaluator.evaluateSingle(
            condition, TelemetrySnapshot.create(2000L, MetricId.TRIP_DISTANCE_KM to 7.0), history
        )
        assertFalse(between.rearmed)
        history = between.updatedHistory

        val crossed = evaluator.evaluateSingle(
            condition, TelemetrySnapshot.create(3000L, MetricId.TRIP_DISTANCE_KM to 10.0), history
        )
        assertTrue(crossed.rearmed, "crossing 10 with a step of 5 is a rearm")
        assertEquals(1, crossed.updatedHistory.lastFiredStepIndex)

        val stillThere = evaluator.evaluateSingle(
            condition, TelemetrySnapshot.create(4000L, MetricId.TRIP_DISTANCE_KM to 10.5), history.copy(lastFiredStepIndex = 1)
        )
        assertFalse(stillThere.rearmed, "the edge lasts exactly one frame")
    }

    @Test
    fun the_step_index_is_forgotten_when_the_condition_falls_away() {
        // Otherwise the ordinary hysteresis rearm and the step rearm would fight over the same
        // firing on the next ride through the threshold.
        val evaluator = ConditionEvaluator()
        val condition = SingleCondition(id = "c", metric = MetricId.SPEED_KMH, template = rising(5.0))

        val met = evaluator.evaluateSingle(
            condition, TelemetrySnapshot.create(1000L, MetricId.SPEED_KMH to 20.0), ConditionStateHistory()
        )
        assertEquals(3, met.updatedHistory.lastFiredStepIndex)

        val fell = evaluator.evaluateSingle(
            condition, TelemetrySnapshot.create(2000L, MetricId.SPEED_KMH to 0.0), met.updatedHistory
        )
        assertFalse(fell.isSatisfied)
        assertNull(fell.updatedHistory.lastFiredStepIndex)
    }

    @Test
    fun a_step_inside_an_and_group_waits_for_the_whole_group() {
        // "Trip every 5 km AND speed above 30" must not speak while the rider is stopped.
        val evaluator = ConditionEvaluator()
        val group = pw.vasilevskiy.loeuc.shared.alerts.model.ConditionGroup(
            id = "g",
            conditions = listOf(
                SingleCondition(id = "trip", metric = MetricId.TRIP_DISTANCE_KM, template = rising(5.0)),
                SingleCondition(
                    id = "speed",
                    metric = MetricId.SPEED_KMH,
                    template = ConditionTemplate(
                        id = "s", name = "s",
                        operator = ComparisonOperator.GREATER_THAN,
                        targetValue = 30.0,
                    )
                ),
            )
        )

        val moving = evaluator.evaluateGroup(
            group,
            TelemetrySnapshot.create(1000L, MetricId.TRIP_DISTANCE_KM to 5.0, MetricId.SPEED_KMH to 40.0),
            emptyMap(),
            "alert",
        )
        assertTrue(moving.isMet)

        val stopped = evaluator.evaluateGroup(
            group,
            TelemetrySnapshot.create(2000L, MetricId.TRIP_DISTANCE_KM to 10.0, MetricId.SPEED_KMH to 0.0),
            moving.states,
            "alert",
        )
        assertFalse(stopped.isMet)
        assertFalse(stopped.rearmed, "the step crossed, but the group does not hold")
    }
}
