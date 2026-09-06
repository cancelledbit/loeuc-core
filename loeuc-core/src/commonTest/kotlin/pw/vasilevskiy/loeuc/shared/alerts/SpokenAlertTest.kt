package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertEngine
import pw.vasilevskiy.loeuc.shared.alerts.model.Alert
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertVoice
import pw.vasilevskiy.loeuc.shared.alerts.model.ComparisonOperator
import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.alerts.model.SingleCondition
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundPattern
import pw.vasilevskiy.loeuc.shared.alerts.model.SpokenAnnouncement
import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpokenAlertTest {

    private fun spoken(
        id: String,
        metric: MetricId,
        target: Double,
        phrase: String,
        priority: Int = 5,
        operator: ComparisonOperator = ComparisonOperator.GREATER_OR_EQUAL,
        step: Double? = null,
    ): Alert {
        val condition = SingleCondition.create(
            id = "c_$id", metric = metric, operator = operator, targetValue = target
        )
        return Alert(
            id = id,
            name = id,
            priority = priority,
            type = AlertType.Spoken(phrase),
            conditionItems = listOf(
                condition.copy(template = condition.template.copy(repeatEveryValue = step))
            ),
            soundPattern = SoundPattern.SINGLE_SHORT_BEEP,
        )
    }

    private fun beeping(id: String, metric: MetricId, target: Double, priority: Int = 0): Alert = Alert(
        id = id,
        name = id,
        priority = priority,
        type = AlertType.Repeating(intervalMs = 500L),
        conditionItems = listOf(
            SingleCondition.create(
                id = "c_$id", metric = metric,
                operator = ComparisonOperator.GREATER_OR_EQUAL, targetValue = target
            )
        ),
        soundPattern = SoundPattern.SINGLE_SHORT_BEEP,
    )

    /**
     * An engine plus the two things it publishes, so a test can read what a ride would have
     * heard. The alert list is kept because [AlertEngine.processTelemetry] resets the engine's
     * list from its argument on every call.
     */
    private class Harness(val alertsHolder: List<Alert>) {
        val voices = mutableListOf<AlertVoice?>()
        val said = mutableListOf<SpokenAnnouncement>()
        val engine = AlertEngine(voicePublisher = { voices.add(it) })

        init {
            engine.announcementPublisher = { said.add(it) }
        }

        fun frame(timeMs: Long, vararg metrics: Pair<MetricId, Double>) {
            engine.processTelemetry(
                snapshot = TelemetrySnapshot.create(timeMs, *metrics),
                activeAlerts = alertsHolder,
            )
        }
    }

    private fun run(alerts: List<Alert>): Harness = Harness(alerts)

    @Test
    fun a_spoken_alert_never_claims_the_tone_slot() {
        // The regression this test exists for: the rule that decided slot ownership was
        // written as "everything except OneShot", which is true of a spoken alert, so it
        // would have taken the slot and beeped its unused pattern over everything else.
        val h = run(listOf(spoken("say", MetricId.SPEED_KMH, 60.0, "Скорость {значение}")))
        h.frame(1000L, MetricId.SPEED_KMH to 65.0)

        assertEquals(listOf<AlertVoice?>(null), h.voices, "a spoken alert must publish no tone")
        assertEquals(1, h.said.size)
        assertEquals("Скорость 65", h.said.first().text(isRu = true))
    }

    @Test
    fun a_spoken_alert_speaks_once_and_rearms_only_when_the_condition_falls() {
        val h = run(listOf(spoken("say", MetricId.SPEED_KMH, 60.0, "Скорость {значение}")))
        h.frame(1000L, MetricId.SPEED_KMH to 65.0)
        h.frame(2000L, MetricId.SPEED_KMH to 66.0)
        h.frame(3000L, MetricId.SPEED_KMH to 67.0)
        assertEquals(1, h.said.size, "one crossing, one phrase")

        h.frame(4000L, MetricId.SPEED_KMH to 10.0)
        h.frame(5000L, MetricId.SPEED_KMH to 65.0)
        assertEquals(2, h.said.size, "the condition fell and was crossed again")
    }

    @Test
    fun a_sounding_tone_holds_the_phrase_back() {
        val h = run(
            listOf(
                spoken("say", MetricId.TRIP_DISTANCE_KM, 5.0, "Пробег {значение}"),
                beeping("alarm", MetricId.PWM_PERCENT, 80.0),
            )
        )

        h.frame(1000L, MetricId.TRIP_DISTANCE_KM to 5.0, MetricId.PWM_PERCENT to 90.0)
        assertTrue(h.said.isEmpty(), "nothing is said while the alarm sounds")
        assertNotNull(h.voices.last(), "the alarm holds the tone slot")

        h.frame(2000L, MetricId.TRIP_DISTANCE_KM to 5.2, MetricId.PWM_PERCENT to 10.0)
        assertEquals(1, h.said.size, "the alarm stopped, so the phrase is released")
    }

    @Test
    fun a_held_phrase_is_re_valued_before_it_speaks() {
        // The rule that replaced a staleness timeout: what waits is the announcement, and what
        // it says is read at the moment it is finally spoken.
        val h = run(
            listOf(
                spoken("bat", MetricId.BATTERY_PERCENT, 10.0, "Аккумулятор {значение}",
                    operator = ComparisonOperator.LESS_OR_EQUAL),
                beeping("alarm", MetricId.PWM_PERCENT, 80.0),
            )
        )

        h.frame(1000L, MetricId.BATTERY_PERCENT to 10.0, MetricId.PWM_PERCENT to 95.0)
        h.frame(2000L, MetricId.BATTERY_PERCENT to 9.0, MetricId.PWM_PERCENT to 95.0)
        h.frame(3000L, MetricId.BATTERY_PERCENT to 8.0, MetricId.PWM_PERCENT to 95.0)
        assertTrue(h.said.isEmpty())

        h.frame(4000L, MetricId.BATTERY_PERCENT to 8.0, MetricId.PWM_PERCENT to 0.0)
        assertEquals("Аккумулятор 8", h.said.single().text(isRu = true))
    }

    @Test
    fun a_held_phrase_whose_condition_falls_away_is_dropped() {
        // "Speed eighty" does not survive the rider braking to forty.
        val h = run(
            listOf(
                spoken("say", MetricId.SPEED_KMH, 80.0, "Скорость {значение}"),
                beeping("alarm", MetricId.PWM_PERCENT, 80.0),
            )
        )

        h.frame(1000L, MetricId.SPEED_KMH to 80.0, MetricId.PWM_PERCENT to 95.0)
        assertTrue(h.said.isEmpty())

        h.frame(2000L, MetricId.SPEED_KMH to 40.0, MetricId.PWM_PERCENT to 95.0)
        h.frame(3000L, MetricId.SPEED_KMH to 40.0, MetricId.PWM_PERCENT to 0.0)
        assertTrue(h.said.isEmpty(), "the rider is no longer doing eighty, so nothing is said")
    }

    @Test
    fun a_stepping_alert_waiting_through_an_alarm_says_one_thing_not_four() {
        // Four kilometres pass during a long alarm. Queuing per firing would say "trip four"
        // four times over, because every queued copy re-values to the same number.
        val h = run(
            listOf(
                spoken("trip", MetricId.TRIP_DISTANCE_KM, 1.0, "Пробег {значение}", step = 1.0),
                beeping("alarm", MetricId.PWM_PERCENT, 80.0),
            )
        )

        h.frame(1000L, MetricId.TRIP_DISTANCE_KM to 1.0, MetricId.PWM_PERCENT to 95.0)
        h.frame(2000L, MetricId.TRIP_DISTANCE_KM to 2.0, MetricId.PWM_PERCENT to 95.0)
        h.frame(3000L, MetricId.TRIP_DISTANCE_KM to 3.0, MetricId.PWM_PERCENT to 95.0)
        h.frame(4000L, MetricId.TRIP_DISTANCE_KM to 4.0, MetricId.PWM_PERCENT to 95.0)
        assertTrue(h.said.isEmpty())

        h.frame(5000L, MetricId.TRIP_DISTANCE_KM to 4.0, MetricId.PWM_PERCENT to 0.0)
        assertEquals(1, h.said.size)
        assertEquals("Пробег 4", h.said.single().text(isRu = true))
    }

    @Test
    fun a_step_makes_a_never_falling_metric_speak_again() {
        val h = run(listOf(spoken("trip", MetricId.TRIP_DISTANCE_KM, 5.0, "Пробег {значение}", step = 5.0)))

        h.frame(1000L, MetricId.TRIP_DISTANCE_KM to 5.0)
        h.frame(2000L, MetricId.TRIP_DISTANCE_KM to 7.0)
        h.frame(3000L, MetricId.TRIP_DISTANCE_KM to 10.0)
        h.frame(4000L, MetricId.TRIP_DISTANCE_KM to 12.0)
        h.frame(5000L, MetricId.TRIP_DISTANCE_KM to 15.0)

        assertEquals(
            listOf("Пробег 5", "Пробег 10", "Пробег 15"),
            h.said.map { it.text(isRu = true) },
        )
    }

    @Test
    fun only_one_phrase_is_released_per_frame() {
        val h = run(
            listOf(
                spoken("a", MetricId.SPEED_KMH, 10.0, "Скорость {значение}", priority = 7),
                spoken("b", MetricId.BATTERY_PERCENT, 50.0, "Аккумулятор {значение}", priority = 2),
            )
        )

        h.frame(1000L, MetricId.SPEED_KMH to 20.0, MetricId.BATTERY_PERCENT to 60.0)
        assertEquals(1, h.said.size)
        assertEquals("Аккумулятор 60", h.said.single().text(isRu = true), "priority 2 outranks 7")

        h.frame(2000L, MetricId.SPEED_KMH to 20.0, MetricId.BATTERY_PERCENT to 60.0)
        assertEquals(2, h.said.size)
        assertEquals("Скорость 20", h.said.last().text(isRu = true))
    }

    @Test
    fun nothing_is_released_while_the_platform_is_still_speaking() {
        val h = run(
            listOf(
                spoken("a", MetricId.SPEED_KMH, 10.0, "Скорость {значение}"),
                spoken("b", MetricId.BATTERY_PERCENT, 50.0, "Аккумулятор {значение}"),
            )
        )

        h.frame(1000L, MetricId.SPEED_KMH to 20.0, MetricId.BATTERY_PERCENT to 60.0)
        assertEquals(1, h.said.size)

        h.engine.setSpeaking(true)
        h.frame(2000L, MetricId.SPEED_KMH to 20.0, MetricId.BATTERY_PERCENT to 60.0)
        h.frame(3000L, MetricId.SPEED_KMH to 20.0, MetricId.BATTERY_PERCENT to 60.0)
        assertEquals(1, h.said.size, "the synthesizer has not finished the first phrase")

        h.engine.setSpeaking(false)
        h.frame(4000L, MetricId.SPEED_KMH to 20.0, MetricId.BATTERY_PERCENT to 60.0)
        assertEquals(2, h.said.size)
    }

    @Test
    fun a_spoken_alert_with_two_or_conditions_stays_silent() {
        // It could not say which of the two fired, and a phrase naming the wrong metric is
        // worse than no phrase.
        val ambiguous = Alert(
            id = "amb",
            name = "amb",
            type = AlertType.Spoken("Скорость {значение}"),
            conditionItems = listOf(
                SingleCondition.create(
                    id = "c1", metric = MetricId.SPEED_KMH,
                    operator = ComparisonOperator.GREATER_OR_EQUAL, targetValue = 60.0
                ),
                SingleCondition.create(
                    id = "c2", metric = MetricId.PWM_PERCENT,
                    operator = ComparisonOperator.GREATER_OR_EQUAL, targetValue = 80.0
                ),
            ),
            soundPattern = SoundPattern.SINGLE_SHORT_BEEP,
        )

        val h = run(listOf(ambiguous))
        h.frame(1000L, MetricId.SPEED_KMH to 70.0, MetricId.PWM_PERCENT to 90.0)

        assertTrue(h.said.isEmpty())
        assertEquals(listOf<AlertVoice?>(null), h.voices, "and it must not beep instead")
    }

    @Test
    fun a_spoken_alert_still_produces_an_event() {
        // Alert history and the ride summary should know a rule fired, whatever it sounded
        // like.
        val h = run(listOf(spoken("say", MetricId.SPEED_KMH, 60.0, "Скорость {значение}")))
        val events = h.engine.processTelemetry(
            TelemetrySnapshot.create(1000L, MetricId.SPEED_KMH to 65.0),
            activeAlerts = h.alertsHolder,
        )
        assertEquals("say", events.single().alert.id)
    }

    @Test
    fun clearing_the_engine_forgets_a_waiting_phrase() {
        val h = run(
            listOf(
                spoken("say", MetricId.SPEED_KMH, 60.0, "Скорость {значение}"),
                beeping("alarm", MetricId.PWM_PERCENT, 80.0),
            )
        )
        h.frame(1000L, MetricId.SPEED_KMH to 65.0, MetricId.PWM_PERCENT to 95.0)
        assertTrue(h.said.isEmpty())

        h.engine.clear()
        h.engine.processTelemetry(
            TelemetrySnapshot.create(2000L, MetricId.SPEED_KMH to 10.0, MetricId.PWM_PERCENT to 0.0),
            activeAlerts = emptyList(),
        )
        assertTrue(h.said.isEmpty())
        assertNull(h.voices.last())
    }
}
