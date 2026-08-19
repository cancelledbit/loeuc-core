package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.protocol.KingSongTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KingSongTelemetryMappingTest {

    /**
     * KingSong reports no charge level at all. The metric has to stay absent rather than read
     * zero: a rider's "battery below 20 %" alert would otherwise fire the moment the wheel
     * connects and never stop.
     */
    @Test
    fun batteryPercentStaysAbsentBecauseKingSongNeverReportsIt() {
        val telemetry = KingSongTelemetry(speedKmh = 25.0).toDeviceTelemetry()

        assertEquals(25.0, telemetry[DeviceMetric.SpeedKmh])
        assertNull(telemetry[DeviceMetric.BatteryPercent])
        assertNull(telemetry.toSnapshot(0L).getValue(MetricId.BATTERY_PERCENT))
    }

    /**
     * `0xA9` and `0xB9` are two different probes arriving at the same rate. The engine keeps
     * them in separate fields on purpose - writing both into one made the reading flip several
     * times a second - so only the live one feeds the controller-temperature metric.
     */
    @Test
    fun onlyTheLiveTemperatureProbeFeedsTheMetric() {
        val telemetry = KingSongTelemetry(
            temperature = 42.0,
            secondaryTemperature = 38.0,
        ).toDeviceTelemetry()

        assertEquals(42.0, telemetry[DeviceMetric.ControllerTemperatureC])
    }

    @Test
    fun distancesArriveInKilometres() {
        val telemetry = KingSongTelemetry(
            tripDistanceMeters = 8_400.0,
            totalDistanceKm = 512.0,
        ).toDeviceTelemetry()

        assertEquals(8.4, telemetry[DeviceMetric.TripDistanceKm])
        assertEquals(512.0, telemetry[DeviceMetric.TotalDistanceKm])
    }
}
