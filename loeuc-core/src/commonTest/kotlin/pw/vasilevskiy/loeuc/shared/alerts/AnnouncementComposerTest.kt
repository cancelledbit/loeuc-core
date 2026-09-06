package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.model.AnnouncementComposer
import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.alerts.model.MetricSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnouncementComposerTest {

    private fun ru(template: String, metric: MetricId, value: Double) =
        AnnouncementComposer.compose(template, metric, value, isRu = true)

    @Test
    fun default_phrases_carry_no_unit() {
        // The whole units decision in one assertion: a rider who never edits the phrase hears
        // the number alone.
        val phrase = MetricSpeech.defaultPhrase(MetricId.SPEED_KMH, isRu = true)
        assertEquals("Скорость 60", ru(phrase, MetricId.SPEED_KMH, 60.0))
    }

    @Test
    fun speed_is_spoken_as_a_whole_number() {
        // 60.4 and 59.8 are the same instruction to a rider, and a spoken decimal is a gamble
        // on how the engine reads it.
        assertEquals("Скорость 60", ru("Скорость {значение}", MetricId.SPEED_KMH, 60.4))
        assertEquals("Скорость 60", ru("Скорость {значение}", MetricId.SPEED_KMH, 59.8))
    }

    @Test
    fun cell_voltage_keeps_its_decimals() {
        // The one place a tenth is the whole signal.
        assertEquals("Ячейка 3,95", ru("Ячейка {значение}", MetricId.CELL_VOLTAGE_AVG_V, 3.9542))
        assertEquals(
            "Перепад ячеек 0,07",
            ru("Перепад ячеек {значение}", MetricId.CELL_VOLTAGE_DELTA_V, 0.0651)
        )
    }

    @Test
    fun english_uses_a_dot_and_the_english_tokens() {
        assertEquals(
            "Cell 3.95",
            AnnouncementComposer.compose("Cell {value}", MetricId.CELL_VOLTAGE_AVG_V, 3.9542, isRu = false)
        )
    }

    @Test
    fun both_spellings_of_a_placeholder_are_accepted() {
        // The app language can change under an alert the rider already wrote.
        assertEquals("Скорость 60", ru("Скорость {value}", MetricId.SPEED_KMH, 60.0))
        assertEquals(
            "Speed 60",
            AnnouncementComposer.compose("Speed {значение}", MetricId.SPEED_KMH, 60.0, isRu = false)
        )
    }

    @Test
    fun russian_unit_agrees_with_the_number() {
        // Appending "километров" by hand would say "1 километров".
        val t = "Пробег {значение} {единицы}"
        assertEquals("Пробег 1 километр", ru(t, MetricId.TRIP_DISTANCE_KM, 1.0))
        assertEquals("Пробег 2 километра", ru(t, MetricId.TRIP_DISTANCE_KM, 2.0))
        assertEquals("Пробег 5 километров", ru(t, MetricId.TRIP_DISTANCE_KM, 5.0))
        assertEquals("Пробег 21 километр", ru(t, MetricId.TRIP_DISTANCE_KM, 21.0))
        assertEquals("Пробег 22 километра", ru(t, MetricId.TRIP_DISTANCE_KM, 22.0))
    }

    @Test
    fun russian_teens_take_the_many_form() {
        // 11..14 are the exception the last digit alone gets wrong: 11 is not "километр".
        val t = "Пробег {значение} {единицы}"
        assertEquals("Пробег 11 километров", ru(t, MetricId.TRIP_DISTANCE_KM, 11.0))
        assertEquals("Пробег 12 километров", ru(t, MetricId.TRIP_DISTANCE_KM, 12.0))
        assertEquals("Пробег 14 километров", ru(t, MetricId.TRIP_DISTANCE_KM, 14.0))
        assertEquals("Пробег 111 километров", ru(t, MetricId.TRIP_DISTANCE_KM, 111.0))
    }

    @Test
    fun percent_and_speed_units_decline_too() {
        assertEquals("Аккумулятор 1 процент", ru("Аккумулятор {значение} {единицы}", MetricId.BATTERY_PERCENT, 1.0))
        assertEquals("Аккумулятор 3 процента", ru("Аккумулятор {значение} {единицы}", MetricId.BATTERY_PERCENT, 3.0))
        assertEquals("Аккумулятор 10 процентов", ru("Аккумулятор {значение} {единицы}", MetricId.BATTERY_PERCENT, 10.0))
        assertEquals(
            "Скорость 1 километр в час",
            ru("Скорость {значение} {единицы}", MetricId.SPEED_KMH, 1.0)
        )
    }

    @Test
    fun a_fractional_value_takes_the_few_form() {
        // "3,95 вольта", not "3,95 вольт".
        assertEquals(
            "Ячейка 3,95 вольта",
            ru("Ячейка {значение} {единицы}", MetricId.CELL_VOLTAGE_AVG_V, 3.9542)
        )
    }

    @Test
    fun english_units_are_singular_only_at_one() {
        val t = "Trip {value} {unit}"
        assertEquals(
            "Trip 1 kilometer",
            AnnouncementComposer.compose(t, MetricId.TRIP_DISTANCE_KM, 1.0, isRu = false)
        )
        assertEquals(
            "Trip 5 kilometers",
            AnnouncementComposer.compose(t, MetricId.TRIP_DISTANCE_KM, 5.0, isRu = false)
        )
    }

    @Test
    fun negative_values_do_not_produce_minus_zero() {
        // Pitch hovers around zero, and "minus zero degrees" is not a thing anyone says.
        assertEquals("Тангаж 0", ru("Тангаж {значение}", MetricId.PITCH_DEG, -0.4))
        assertEquals("Тангаж -3", ru("Тангаж {значение}", MetricId.PITCH_DEG, -3.2))
    }

    @Test
    fun a_negative_fraction_keeps_its_sign() {
        assertEquals(
            "Перепад ячеек -0,07",
            ru("Перепад ячеек {значение}", MetricId.CELL_VOLTAGE_DELTA_V, -0.0651)
        )
    }

    @Test
    fun text_the_rider_typed_is_left_alone() {
        assertEquals(
            "Осталось 10 заряда, тормози",
            ru("Осталось {значение} заряда, тормози", MetricId.BATTERY_PERCENT, 10.0)
        )
    }

    @Test
    fun a_phrase_without_placeholders_still_speaks() {
        assertEquals("Пора домой", ru("Пора домой", MetricId.BATTERY_PERCENT, 10.0))
    }

    @Test
    fun collapsed_spacing_does_not_leave_a_pause() {
        // A synthesizer pauses on a double space, and the rider hears it as a stumble.
        assertEquals("Скорость 60", ru("Скорость   {значение}  ", MetricId.SPEED_KMH, 60.0))
    }

    @Test
    fun every_metric_has_a_phrase_and_a_unit_in_both_languages() {
        // A new MetricId with no speech entry would fail to compile in MetricSpeech's `when`,
        // but an empty string would compile: this catches that.
        for (metric in MetricId.entries) {
            for (isRu in listOf(true, false)) {
                val phrase = MetricSpeech.defaultPhrase(metric, isRu)
                assertTrue(phrase.isNotBlank(), "$metric has no default phrase (isRu=$isRu)")
                assertTrue(
                    MetricSpeech.valueTokens.any { phrase.contains(it) },
                    "$metric default phrase says no value (isRu=$isRu): $phrase"
                )
                assertTrue(
                    MetricSpeech.unitTokens.none { phrase.contains(it) },
                    "$metric default phrase speaks its unit (isRu=$isRu): $phrase"
                )
                assertTrue(
                    MetricSpeech.unitFor(metric, 2.0, isRu).isNotBlank(),
                    "$metric has no unit word (isRu=$isRu)"
                )
            }
        }
    }
}
