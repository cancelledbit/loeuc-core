package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.SolowheelXtremeTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SolowheelXtremeTelemetryMappingTest {

    /**
     * The Solowheel Xtreme ASCII stream carries speed and pack voltage and nothing else. Every
     * other metric has to be absent so a caller can tell "this wheel cannot measure it" from
     * "it measured zero".
     */
    @Test
    fun onlySpeedAndVoltageAreReported() {
        val telemetry = SolowheelXtremeTelemetry(
            motionRaw = 1,
            speedKmh = 14.0,
            voltage = 63.2,
            stateRaw = 0,
        ).toDeviceTelemetry()

        assertEquals(14.0, telemetry[DeviceMetric.SpeedKmh])
        assertEquals(63.2, telemetry[DeviceMetric.PackVoltageV])
        assertEquals(
            setOf(DeviceMetric.SpeedKmh, DeviceMetric.PackVoltageV),
            telemetry.values.keys,
        )
        assertNull(telemetry[DeviceMetric.PwmPercent])
    }
}
