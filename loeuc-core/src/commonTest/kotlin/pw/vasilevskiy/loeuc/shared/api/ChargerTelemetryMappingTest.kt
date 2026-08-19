package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.protocol.HwChargerTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.SkatChargerTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChargerTelemetryMappingTest {

    /**
     * `outputState` starts at -1, meaning "no frame has arrived yet". That has to map to an
     * absent metric rather than to 0: a confident "not charging" before the charger has said
     * anything is worse than no answer, because a rule can act on it.
     */
    @Test
    fun chargingIsUnknownUntilTheChargerHasSaidSomething() {
        val silent = HwChargerTelemetry().toDeviceTelemetry()
        val delivering = HwChargerTelemetry(outputState = 1).toDeviceTelemetry()
        val idle = HwChargerTelemetry(outputState = 0).toDeviceTelemetry()

        assertNull(silent[DeviceMetric.Charging], "-1 means unheard-of, not idle")
        assertEquals(1.0, delivering[DeviceMetric.Charging])
        assertEquals(0.0, idle[DeviceMetric.Charging])
    }

    /**
     * A charger measures nothing on the wheel side. Its readings have to land on the charge
     * metrics, and the wheel ones stay absent - otherwise a pack-voltage rule would start
     * watching the charger's output rail.
     */
    @Test
    fun hwChargerReadingsLandOnChargeMetricsOnly() {
        val telemetry = HwChargerTelemetry(
            outputVoltage = 100.8,
            outputCurrent = 5.0,
            temperature = 41.5,
            ah = 12.0,
            wh = 1_180.0,
        ).toDeviceTelemetry()

        assertEquals(100.8, telemetry[DeviceMetric.ChargeVoltageV])
        assertEquals(5.0, telemetry[DeviceMetric.ChargeCurrentA])
        assertEquals(504.0, telemetry[DeviceMetric.ChargePowerW], "power is voltage times current")
        assertEquals(41.5, telemetry[DeviceMetric.ChargerTemperatureC])
        assertEquals(12.0, telemetry[DeviceMetric.ChargedAmpHours])
        assertEquals(1_180.0, telemetry[DeviceMetric.ChargedWattHours])

        assertNull(telemetry[DeviceMetric.PackVoltageV])
        assertNull(telemetry[DeviceMetric.ControllerTemperatureC])
    }

    /**
     * Those same charge readings still have to reach the alert engine, which only knows about
     * the wheel-side ids - that is what the charger entries in [MetricIdSources] are for.
     */
    @Test
    fun chargerMetricsStillReachTheAlertEngine() {
        val snapshot = HwChargerTelemetry(
            outputVoltage = 100.8,
            outputCurrent = 5.0,
            temperature = 41.5,
        ).toDeviceTelemetry().toSnapshot(0L)

        assertEquals(100.8, snapshot.getValue(MetricId.VOLTAGE_V))
        assertEquals(5.0, snapshot.getValue(MetricId.CURRENT_A))
        assertEquals(41.5, snapshot.getValue(MetricId.TEMPERATURE_CONTROLLER_C))
    }

    /** The HW charger never reports a set point; only SKAT frames carry one. */
    @Test
    fun onlySkatReportsATargetVoltage() {
        assertNull(
            HwChargerTelemetry(outputVoltage = 100.8).toDeviceTelemetry()[DeviceMetric.ChargeTargetVoltageV],
        )
        assertEquals(
            100.8,
            SkatChargerTelemetry(targetVoltage = 100.8).toDeviceTelemetry()[DeviceMetric.ChargeTargetVoltageV],
        )
    }

    /**
     * SKAT has no output switch to read, so "charging" is inferred from current actually
     * flowing. Below the threshold it is idle; with no current reading at all it is unknown.
     */
    @Test
    fun skatInfersChargingFromCurrentFlow() {
        assertEquals(1.0, SkatChargerTelemetry(outputCurrent = 4.0).toDeviceTelemetry()[DeviceMetric.Charging])
        assertEquals(0.0, SkatChargerTelemetry(outputCurrent = 0.0).toDeviceTelemetry()[DeviceMetric.Charging])
        assertNull(SkatChargerTelemetry().toDeviceTelemetry()[DeviceMetric.Charging])
    }

    /** Only the FB frame carries temperature; a run of FA-only frames leaves it unknown. */
    @Test
    fun skatTemperatureStaysAbsentUntilAnFbFrameArrives() {
        val faOnly = SkatChargerTelemetry(outputVoltage = 84.0, outputCurrent = 3.0)

        assertNull(faOnly.toDeviceTelemetry()[DeviceMetric.ChargerTemperatureC])
    }
}
