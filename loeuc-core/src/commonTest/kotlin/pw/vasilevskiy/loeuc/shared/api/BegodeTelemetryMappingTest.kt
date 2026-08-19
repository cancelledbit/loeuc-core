package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.BegodeTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BegodeTelemetryMappingTest {

    /**
     * Begode reports the trip in metres and the odometer in kilometres - two neighbouring
     * fields of the same wheel in two different units. Both land on kilometre metrics.
     */
    @Test
    fun distancesArriveInKilometres() {
        val telemetry = BegodeTelemetry(
            tripDistanceMeters = 12_500.0,
            totalDistanceKm = 1_840.0,
        ).toDeviceTelemetry()

        assertEquals(12.5, telemetry[DeviceMetric.TripDistanceKm])
        assertEquals(1_840.0, telemetry[DeviceMetric.TotalDistanceKm])
    }

    /**
     * Begode has no MOSFET probe and no lean sensor. Those metrics have to stay absent rather
     * than arrive as zero, otherwise an alert set on them would look satisfied forever.
     */
    @Test
    fun sensorsBegodeDoesNotHaveStayAbsent() {
        val telemetry = BegodeTelemetry(speedKmh = 20.0).toDeviceTelemetry()

        assertEquals(20.0, telemetry[DeviceMetric.SpeedKmh])
        assertNull(telemetry[DeviceMetric.MosTemperatureC])
        assertNull(telemetry[DeviceMetric.RollDeg])
        assertNull(telemetry[DeviceMetric.PitchDeg])
    }

    @Test
    fun currentsKeepTheirSeparateMeanings() {
        val telemetry = BegodeTelemetry(
            current = 4.2,
            phaseCurrent = 31.0,
        ).toDeviceTelemetry()

        assertEquals(4.2, telemetry[DeviceMetric.BatteryCurrentA])
        assertEquals(31.0, telemetry[DeviceMetric.PhaseCurrentA])
        assertNull(telemetry[DeviceMetric.OutputCurrentA], "Begode reports no output current")
    }
}
