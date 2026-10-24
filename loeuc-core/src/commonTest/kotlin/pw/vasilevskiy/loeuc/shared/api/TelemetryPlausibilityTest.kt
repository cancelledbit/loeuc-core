package pw.vasilevskiy.loeuc.shared.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryPlausibilityTest {

    @Test
    fun ordinary_ride_frame_passes() {
        assertTrue(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 42.0,
                pwmPercent = 37.5,
                packVoltage = 100.4,
                batteryPercent = 78.0,
                tripDistanceMeters = 12_300.0,
                totalDistanceMeters = 4_120_000.0,
            ),
        )
    }

    @Test
    fun fast_downhill_still_passes() {
        // The limits catch a decode that came apart, not a surprising reading: 130 km/h at
        // 160 V on a big wheel is a ride, not garbage.
        assertTrue(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 130.0,
                pwmPercent = 99.0,
                packVoltage = 160.0,
            ),
        )
    }

    @Test
    fun impossible_speed_is_rejected() {
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 4_210.0,
                pwmPercent = 37.5,
                packVoltage = 100.4,
            ),
        )
    }

    @Test
    fun rolling_backwards_is_plausible() {
        // Engines keep the direction of travel, so a wheel rolled back reports a negative speed.
        assertTrue(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = -3.0,
                pwmPercent = 10.0,
                packVoltage = 100.0,
            ),
        )
    }

    @Test
    fun impossible_backwards_speed_is_rejected() {
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = -4_210.0,
                pwmPercent = 10.0,
                packVoltage = 100.0,
            ),
        )
    }

    @Test
    fun impossible_pwm_is_rejected() {
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 20.0,
                pwmPercent = 5_000.0,
                packVoltage = 100.0,
            ),
        )
    }

    @Test
    fun impossible_voltage_is_rejected() {
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 20.0,
                pwmPercent = 30.0,
                packVoltage = 3.5,
            ),
        )
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 20.0,
                pwmPercent = 30.0,
                packVoltage = 1_000.0,
            ),
        )
    }

    @Test
    fun impossible_battery_percent_is_rejected() {
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 20.0,
                pwmPercent = 30.0,
                packVoltage = 100.0,
                batteryPercent = 240.0,
            ),
        )
    }

    @Test
    fun missing_speed_pwm_or_voltage_is_rejected() {
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = null,
                pwmPercent = 30.0,
                packVoltage = 100.0,
            ),
        )
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 20.0,
                pwmPercent = null,
                packVoltage = 100.0,
            ),
        )
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 20.0,
                pwmPercent = 30.0,
                packVoltage = null,
            ),
        )
    }

    @Test
    fun nan_counts_as_absent() {
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = Double.NaN,
                pwmPercent = 30.0,
                packVoltage = 100.0,
            ),
        )
        assertTrue(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 20.0,
                pwmPercent = 30.0,
                packVoltage = 100.0,
                batteryPercent = Double.NaN,
            ),
        )
    }

    @Test
    fun pwmless_wires_pass_without_a_duty_cycle() {
        // The InMotion legacy fast-info frame and the Solowheel Xtreme record carry no duty
        // cycle at all; demanding one would let none of their frames through.
        assertFalse(TelemetryPlausibility.carriesPwm(WheelProtocol.InmotionLegacy))
        assertFalse(TelemetryPlausibility.carriesPwm(WheelProtocol.SolowheelXtreme))
        assertTrue(TelemetryPlausibility.carriesPwm(WheelProtocol.Begode))
        assertTrue(TelemetryPlausibility.carriesPwm(null))
        assertTrue(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 18.0,
                pwmPercent = null,
                packVoltage = 67.0,
                requiresPwm = false,
            ),
        )
        // A PWM that did arrive is still range-checked.
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = 18.0,
                pwmPercent = 900.0,
                packVoltage = 67.0,
                requiresPwm = false,
            ),
        )
    }

    @Test
    fun charger_frame_with_one_reading_passes() {
        assertTrue(
            TelemetryPlausibility.isPlausibleChargerFrame(chargeVoltage = 84.2),
        )
    }

    @Test
    fun charger_frame_of_nothing_is_rejected() {
        assertFalse(TelemetryPlausibility.isPlausibleChargerFrame())
    }

    @Test
    fun charger_frame_out_of_range_is_rejected() {
        assertFalse(
            TelemetryPlausibility.isPlausibleChargerFrame(
                chargeVoltage = 84.2,
                chargeCurrent = 4_000.0,
            ),
        )
    }

    @Test
    fun charger_frame_never_passes_the_wheel_rule() {
        // Exactly what tripped iOS up: under the wheel rule a healthy charger frame was dropped
        // every time, because it has no speed, no PWM and no pack voltage.
        assertFalse(
            TelemetryPlausibility.isPlausibleWheelFrame(
                speedKmh = null,
                pwmPercent = null,
                packVoltage = null,
            ),
        )
    }
}
