package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.LeaperKimTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals

class LeaperKimTelemetryMappingTest {

    @Test
    fun mapsLiveAndConfiguredAnglesSeparately() {
        val telemetry = LeaperKimTelemetry(
            speedKmh = 28.0,
            pwmPercent = 12.0,
            voltage = 134.0,
            phaseCurrent = 40.0,
            outputCurrent = 15.0,
            power = 2010.0,
            temperature = 31.0,
            mosTemperature = 38.0,
            motorTemperature = Double.NaN,
            batteryPercent = 64.0,
            tripDistanceMeters = 1200.0,
            totalDistanceMeters = 42_000.0,
            batteryCurrent = 10.0,
            leftBatteryCurrent = 4.0,
            rightBatteryCurrent = 6.0,
            lateralAngle = -2.0,
            carPose = 3.5,
            batteryTemperatureMin = 25.0,
            batteryTemperatureMax = 29.0,
            dangerSpeedKmh = 45.0,
            stopSpeedKmh = 3.0,
            fallProtectionAngle = 8.0,
            rideMode = 1.0,
            shutdownTimeSeconds = 30.0,
            chargeMode = 0.0,
            batteryTempMode = 0.0,
            lockState = 0.0,
            hardwareCode = null,
            firmwareVersion = 9.4,
            highSpeedMode = false,
            fieldWeakeningPercent = 0.0,
        ).toDeviceTelemetry()

        assertEquals(28.0, telemetry[DeviceMetric.SpeedKmh])
        assertEquals(-2.0, telemetry[DeviceMetric.RollDeg])
        assertEquals(3.5, telemetry[DeviceMetric.PitchDeg])
        assertEquals(8.0, telemetry[DeviceMetric.FallProtectionAngleDeg])
        assertEquals(10.0, telemetry[DeviceMetric.BatteryCurrentA])
        assertEquals(1.2, telemetry[DeviceMetric.TripDistanceKm])
        assertEquals(42.0, telemetry[DeviceMetric.TotalDistanceKm])
    }
}
