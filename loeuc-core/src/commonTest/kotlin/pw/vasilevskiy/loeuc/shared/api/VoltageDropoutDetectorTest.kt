package pw.vasilevskiy.loeuc.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoltageDropoutDetectorTest {

    private fun warmedUp(voltage: Double = 150.0, frames: Int = 20): VoltageDropoutDetector {
        val detector = VoltageDropoutDetector()
        repeat(frames) { index ->
            assertFalse(detector.isDropout(timestampMs = index * 500L, voltage = voltage, currentA = 3.0))
        }
        return detector
    }

    @Test
    fun first_frames_are_never_a_dropout() {
        val detector = VoltageDropoutDetector()
        assertFalse(detector.isDropout(timestampMs = 0L, voltage = 40.0, currentA = 0.0))
    }

    @Test
    fun power_off_frame_without_load_is_a_dropout() {
        val detector = warmedUp()
        // The wheel is going down: a third of the pack voltage with no current to explain it.
        assertTrue(detector.isDropout(timestampMs = 10_000L, voltage = 50.0, currentA = 0.2))
    }

    @Test
    fun missing_current_cannot_explain_a_drop() {
        val detector = warmedUp()
        assertTrue(detector.isDropout(timestampMs = 10_000L, voltage = 50.0, currentA = null))
    }

    @Test
    fun the_same_drop_under_load_is_a_real_sag() {
        val detector = warmedUp()
        assertFalse(detector.isDropout(timestampMs = 10_000L, voltage = 50.0, currentA = 120.0))
        assertFalse(detector.isDropout(timestampMs = 10_100L, voltage = 50.0, currentA = -120.0))
    }

    @Test
    fun ordinary_sag_stays_a_reading() {
        val detector = warmedUp()
        // A 15 % sag is what a hard launch does to a pack; it passes with or without current.
        assertFalse(detector.isDropout(timestampMs = 10_000L, voltage = 128.0, currentA = 0.0))
    }

    @Test
    fun a_dropout_does_not_move_the_reference() {
        val detector = warmedUp()
        assertTrue(detector.isDropout(timestampMs = 10_000L, voltage = 50.0, currentA = 0.0))
        // Still judged against 150 V, not against the bogus 50 V.
        assertTrue(detector.isDropout(timestampMs = 10_100L, voltage = 52.0, currentA = 0.0))
        assertFalse(detector.isDropout(timestampMs = 10_200L, voltage = 149.0, currentA = 0.0))
    }

    @Test
    fun a_level_that_persists_past_the_window_becomes_the_reading() {
        val detector = warmedUp()
        assertTrue(detector.isDropout(timestampMs = 10_000L, voltage = 50.0, currentA = 0.0))
        // Once every accepted reading has aged out of the window there is nothing to judge
        // against, so a genuinely low pack is not silenced forever.
        assertFalse(detector.isDropout(timestampMs = 10_000L + VoltageDropoutDetector.WindowMs + 1, voltage = 50.0, currentA = 0.0))
    }

    @Test
    fun reference_follows_a_slowly_draining_pack() {
        val detector = VoltageDropoutDetector()
        var voltage = 150.0
        var time = 0L
        // Full to empty over an hour: never a dropout.
        while (voltage > 100.0) {
            assertFalse(detector.isDropout(timestampMs = time, voltage = voltage, currentA = 5.0))
            voltage -= 0.05
            time += 3_600L
        }
    }

    @Test
    fun non_finite_voltage_is_not_a_reading_and_not_a_dropout() {
        val detector = warmedUp()
        assertFalse(detector.isDropout(timestampMs = 10_000L, voltage = Double.NaN, currentA = 0.0))
        assertFalse(detector.isDropout(timestampMs = 10_000L, voltage = null, currentA = 0.0))
    }

    @Test
    fun time_going_backwards_forgets_the_reference() {
        val detector = warmedUp()
        // A dump seek: the reading before the seek is no reference for the one after it.
        assertFalse(detector.isDropout(timestampMs = 1_000L, voltage = 50.0, currentA = 0.0))
    }

    @Test
    fun load_threshold_is_inclusive() {
        assertTrue(warmedUp().isDropout(timestampMs = 10_000L, voltage = 50.0, currentA = VoltageDropoutDetector.LoadCurrentA - 0.01))
        assertFalse(warmedUp().isDropout(timestampMs = 10_000L, voltage = 50.0, currentA = VoltageDropoutDetector.LoadCurrentA))
    }

    @Test
    fun only_readings_inside_the_window_form_the_reference() {
        val detector = VoltageDropoutDetector()
        repeat(20) { assertFalse(detector.isDropout(timestampMs = it * 500L, voltage = 150.0, currentA = 0.0)) }
        // Twelve seconds of a genuinely lower level: the first reading is against 150 V and is
        // a dropout, but by the end of the window the 150 V readings have aged out and the
        // new level is the reference.
        var rejected = 0
        for (index in 0 until 24) {
            if (detector.isDropout(timestampMs = 10_000L + index * 500L, voltage = 100.0, currentA = 0.0)) rejected++
        }
        assertTrue(rejected in 1..21, "rejected=$rejected")
        assertFalse(detector.isDropout(timestampMs = 22_000L, voltage = 100.0, currentA = 0.0))
        // ...and 150 V is not a dropout either: only drops are.
        assertFalse(detector.isDropout(timestampMs = 22_500L, voltage = 150.0, currentA = 0.0))
    }

    @Test
    fun series_helper_blanks_the_interpolated_shoulders_too() {
        // Rows a second apart, resampled at 100 ms: the resampler draws 139 -> 50 as a ramp.
        val voltage = List(20) { 139.0 } + List(9) { index -> 139.0 - (index + 1) * 8.9 } + listOf(50.0) + List(20) { 139.0 }
        val cleaned = VoltageDropoutDetector.withoutDropouts(voltage, currentA = null, stepMillis = 100L, shoulderMillis = 1_000L)
        // The ramp crosses the threshold at index 24 (94.5 V); a second either side of every
        // dropout point is gone with it, so 14..39 are blank and the rest survives.
        assertEquals(List<Double?>(26) { null }, cleaned.subList(14, 40))
        assertEquals(139.0, cleaned[13])
        assertEquals(139.0, cleaned[40])
    }

    @Test
    fun series_helper_blanks_only_the_dropouts() {
        val voltage = listOf(150.0, 150.0, 150.0, 50.0, 150.0, 40.0, 150.0)
        val current = listOf(2.0, 2.0, 2.0, 0.0, 2.0, 130.0, 2.0)
        val cleaned = VoltageDropoutDetector.withoutDropouts(voltage, current, stepMillis = 500L)
        assertEquals(listOf(150.0, 150.0, 150.0, null, 150.0, 40.0, 150.0), cleaned)
    }

    @Test
    fun series_helper_without_current_treats_every_drop_as_unexplained() {
        val voltage = listOf<Double?>(150.0, 150.0, null, 50.0, 150.0)
        val cleaned = VoltageDropoutDetector.withoutDropouts(voltage, currentA = null, stepMillis = 500L)
        assertEquals(listOf(150.0, 150.0, null, null, 150.0), cleaned)
    }
}
