package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetricIdProjectionTest {

    /**
     * The source list per `MetricId` is a preference order, not a set of synonyms: `CURRENT_A`
     * means battery current, and only falls back to output current on wheels that report no
     * battery current at all.
     */
    @Test
    fun batteryCurrentWinsOverOutputCurrent() {
        val telemetry = DeviceTelemetry.of(
            DeviceMetric.BatteryCurrentA to 4.0,
            DeviceMetric.OutputCurrentA to 9.0,
        )

        assertEquals(4.0, telemetry.toSnapshot(timestampMs = 0L).getValue(MetricId.CURRENT_A))
    }

    @Test
    fun outputCurrentFillsInWhenBatteryCurrentIsAbsent() {
        val telemetry = DeviceTelemetry.of(DeviceMetric.OutputCurrentA to 9.0)

        assertEquals(9.0, telemetry.toSnapshot(timestampMs = 0L).getValue(MetricId.CURRENT_A))
    }

    /**
     * A charger reports no pack voltage of its own, so charge voltage stands in - otherwise
     * every alert configured for charging would otherwise lose its source metric.
     */
    @Test
    fun chargeMetricsStandInForTheWheelOnes() {
        val telemetry = DeviceTelemetry.of(
            DeviceMetric.ChargeVoltageV to 100.4,
            DeviceMetric.ChargerTemperatureC to 41.0,
        )

        val snapshot = telemetry.toSnapshot(timestampMs = 0L)

        assertEquals(100.4, snapshot.getValue(MetricId.VOLTAGE_V))
        assertEquals(41.0, snapshot.getValue(MetricId.TEMPERATURE_CONTROLLER_C))
    }

    /**
     * Two different sensors, not synonyms. LeaperKim and InMotion have a dedicated MOSFET
     * probe; Begode and KingSong have none and report only a controller temperature. Letting
     * `TEMPERATURE_MOS_C` fall back would point a "MOSFET" alert at another sensor on half the
     * wheels without saying so.
     */
    @Test
    fun mosfetTemperatureDoesNotFallBackToTheControllerProbe() {
        val telemetry = DeviceTelemetry.of(DeviceMetric.ControllerTemperatureC to 38.0)

        assertNull(telemetry.toSnapshot(timestampMs = 0L).getValue(MetricId.TEMPERATURE_MOS_C))
    }

    /**
     * Battery temperature means the hottest battery reading the device reports: the alert is an
     * overheat warning, and the coldest pack cannot raise one.
     */
    @Test
    fun batteryTemperatureIsTheHottestPack() {
        val telemetry = DeviceTelemetry.of(
            DeviceMetric.BatteryTemperatureMinC to 24.0,
            DeviceMetric.BatteryTemperatureMaxC to 41.5,
        )

        assertEquals(41.5, telemetry.toSnapshot(timestampMs = 0L).getValue(MetricId.TEMPERATURE_BATTERY_C))
    }

    /**
     * A wheel with a single battery probe fills only the min slot - InMotion V11/V12 map their
     * one `batteryTemperature` there. That reading is the whole truth the wheel has, so it has
     * to reach the alert rather than be dropped for not being called a maximum.
     */
    @Test
    fun aSingleBatteryProbeStillReachesTheAlert() {
        val telemetry = DeviceTelemetry.of(DeviceMetric.BatteryTemperatureMinC to 33.0)

        assertEquals(33.0, telemetry.toSnapshot(timestampMs = 0L).getValue(MetricId.TEMPERATURE_BATTERY_C))
    }

    /**
     * A wheel with no battery probe at all - Begode reports none - must leave the metric absent
     * rather than zero. Zero is a reading, and a rule written as "below 5 C" would fire on it
     * forever.
     */
    @Test
    fun aWheelWithoutABatteryProbeReportsNoBatteryTemperature() {
        val telemetry = DeviceTelemetry.of(
            DeviceMetric.ControllerTemperatureC to 38.0,
            DeviceMetric.MotorTemperatureC to 51.0,
        )

        assertNull(telemetry.toSnapshot(timestampMs = 0L).getValue(MetricId.TEMPERATURE_BATTERY_C))
    }

    @Test
    fun snapshotCarriesTheTimestampItWasGiven() {
        val telemetry = DeviceTelemetry.of(DeviceMetric.SpeedKmh to 1.0)

        assertEquals(1_700_000_000_000L, telemetry.toSnapshot(1_700_000_000_000L).timestampMs)
    }
}
