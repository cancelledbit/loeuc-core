package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.protocol.InmotionTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals

class InmotionTelemetryMappingTest {

    private fun telemetry(
        speedKmh: Double = Double.NaN,
        pwmPercent: Double = Double.NaN,
        voltage: Double = Double.NaN,
        phaseCurrent: Double = Double.NaN,
        pitchAngle: Double = Double.NaN,
        lateralAngle: Double = Double.NaN,
    ) = InmotionTelemetry(
        profileId = "v11",
        speedKmh = speedKmh,
        pwmPercent = pwmPercent,
        voltage = voltage,
        phaseCurrent = phaseCurrent,
        batteryCurrent = Double.NaN,
        power = Double.NaN,
        motorPower = Double.NaN,
        batteryPercent = Double.NaN,
        rideBatteryPercent = Double.NaN,
        tripDistanceMeters = Double.NaN,
        pitchAngle = pitchAngle,
        lateralAngle = lateralAngle,
        tirePressureBar = Double.NaN,
        mosTemperature = Double.NaN,
        motorTemperature = Double.NaN,
        boardTemperature = Double.NaN,
    )

    /**
     * InMotion reports speed with a sign: rolling backwards makes it negative. A speed alert
     * asks "faster than X", so a wheel rolling backwards at 30 km/h has to read 30, not -30.
     */
    @Test
    fun speedIsReportedWithoutItsSign() {
        val rollingBackwards = telemetry(speedKmh = -30.0).toDeviceTelemetry()

        assertEquals(30.0, rollingBackwards[DeviceMetric.SpeedKmh])
        assertEquals(30.0, rollingBackwards.toSnapshot(0L).getValue(MetricId.SPEED_KMH))
    }

    /**
     * Pitch is the live lean along the wheel, roll the lean across it. They are separate
     * sensors and must not land on one metric; iOS currently carries InMotion's pitch in a
     * field named after the fall-protection setting, which this mapping does not repeat.
     */
    @Test
    fun pitchAndRollLandOnTheirOwnMetrics() {
        val leaning = telemetry(pitchAngle = 3.4, lateralAngle = -1.2).toDeviceTelemetry()

        assertEquals(3.4, leaning[DeviceMetric.PitchDeg])
        assertEquals(-1.2, leaning[DeviceMetric.RollDeg])

        val snapshot = leaning.toSnapshot(0L)
        assertEquals(3.4, snapshot.getValue(MetricId.PITCH_DEG))
        assertEquals(-1.2, snapshot.getValue(MetricId.ROLL_DEG))
    }

    /**
     * Apparent power is not on the wire; it is voltage times phase current scaled by duty.
     * Both platforms compute it the same way, so the library owns the formula now.
     */
    @Test
    fun apparentPowerIsComputedFromVoltagePhaseCurrentAndDuty() {
        val loaded = telemetry(voltage = 84.0, phaseCurrent = 20.0, pwmPercent = 50.0)
            .toDeviceTelemetry()

        assertEquals(840.0, loaded[DeviceMetric.ApparentPowerVa])
    }

    /** With no duty reading there is nothing to scale by, so the product stays unknown. */
    @Test
    fun apparentPowerStaysAbsentWithoutADutyReading() {
        val noDuty = telemetry(voltage = 84.0, phaseCurrent = 20.0).toDeviceTelemetry()

        assertEquals(null, noDuty[DeviceMetric.ApparentPowerVa])
    }
}
