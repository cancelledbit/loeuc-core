package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.NinebotBmsPack
import pw.vasilevskiy.loeuc.shared.protocol.NinebotProtocolUpdate
import pw.vasilevskiy.loeuc.shared.protocol.NinebotTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals

class NinebotTelemetryMappingTest {

    @Test
    fun mapsTelemetryAndBmsCellStatistics() {
        val update = NinebotProtocolUpdate(
            param = 0xB0,
            source = 0,
            telemetry = NinebotTelemetry(
                speedKmh = 20.0,
                voltage = 84.0,
                current = 8.0,
                power = 672.0,
                pwmPercent = 25.0,
                temperature = 32.0,
                batteryPercent = 70.0,
                tripDistanceMeters = 1000.0,
                totalDistanceMeters = 20_000.0,
            ),
            bmsPacks = listOf(
                NinebotBmsPack(index = 1, cells = listOf(4.0, 4.1, 4.2)),
            ),
        )

        val telemetry = update.toDeviceTelemetry()

        assertEquals(20.0, telemetry[DeviceMetric.SpeedKmh])
        assertEquals(70.0, telemetry[DeviceMetric.BatteryPercent])
        assertEquals(4.1, telemetry[DeviceMetric.CellVoltageAvgV]!!, absoluteTolerance = 1e-9)
        assertEquals(0.2, telemetry[DeviceMetric.CellVoltageDeltaV]!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun doesNotInventValuesForAnEmptyUpdate() {
        val telemetry = NinebotProtocolUpdate(param = 0, source = 0).toDeviceTelemetry()

        assertEquals(null, telemetry[DeviceMetric.SpeedKmh])
        assertEquals(null, telemetry[DeviceMetric.CellVoltageAvgV])
    }
}
