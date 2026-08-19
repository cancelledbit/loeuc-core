package pw.vasilevskiy.loeuc.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceTelemetryTest {

    /**
     * Engines use `Double.NaN` for "the wheel did not report this field in that frame". The
     * public container turns that convention into a structural one: a metric the wheel never
     * reported is simply absent, so a caller cannot mistake a missing sensor for a reading.
     */
    @Test
    fun nonFiniteValuesAreNotStored() {
        val telemetry = DeviceTelemetry.of(
            DeviceMetric.SpeedKmh to 12.5,
            DeviceMetric.PwmPercent to Double.NaN,
            DeviceMetric.PackVoltageV to Double.POSITIVE_INFINITY,
        )

        assertEquals(12.5, telemetry[DeviceMetric.SpeedKmh])
        assertNull(telemetry[DeviceMetric.PwmPercent])
        assertNull(telemetry[DeviceMetric.PackVoltageV])
    }

    /**
     * Wheels spread their fields over several frame types, so a frame that carries voltage but
     * not speed must not blank the speed the previous frame reported. This is the accumulation
     * the engines already do internally with `state.copy(...)`.
     */
    @Test
    fun mergingKeepsMetricsTheNewerUpdateDoesNotCarry() {
        val earlier = DeviceTelemetry.of(
            DeviceMetric.SpeedKmh to 12.5,
            DeviceMetric.PackVoltageV to 84.0,
        )
        val later = DeviceTelemetry.of(DeviceMetric.PackVoltageV to 83.2)

        val merged = earlier.mergedWith(later)

        assertEquals(12.5, merged[DeviceMetric.SpeedKmh], "speed was dropped by the merge")
        assertEquals(83.2, merged[DeviceMetric.PackVoltageV], "newer voltage should win")
    }
}
