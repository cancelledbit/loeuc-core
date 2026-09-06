package pw.vasilevskiy.loeuc.shared.alerts

import pw.vasilevskiy.loeuc.shared.alerts.engine.AlertEngine
import pw.vasilevskiy.loeuc.shared.alerts.model.Alert
import pw.vasilevskiy.loeuc.shared.alerts.model.AlertType
import pw.vasilevskiy.loeuc.shared.alerts.model.ComparisonOperator
import pw.vasilevskiy.loeuc.shared.alerts.model.ConditionTemplate
import pw.vasilevskiy.loeuc.shared.alerts.model.MetricId
import pw.vasilevskiy.loeuc.shared.alerts.model.SingleCondition
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundPattern
import pw.vasilevskiy.loeuc.shared.alerts.model.SoundStep
import pw.vasilevskiy.loeuc.shared.api.DeviceMetric
import pw.vasilevskiy.loeuc.shared.api.toDeviceTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.BegodeTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.LeaperKimTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Battery temperature reaches the engine from the wheels that measure it, and stays away from
 * the ones that do not.
 *
 * The second half is the point. Not every wheel has a probe in the pack, and the failure mode
 * of getting that wrong is not a blank tile: a metric defaulted to zero reads as a working
 * sensor sitting at 0 C, so "battery over 45" would never fire on a wheel that is actually
 * cooking, and "battery below 5" would fire forever on one that has no sensor at all.
 */
class BatteryTemperatureAlertTest {

    /** LeaperKim reports one temperature per pack; the engine gets the hottest of them. */
    @Test
    fun leaperKimPackTemperaturesReachTheMetric() {
        val snapshot = leaperKim(batteryTemperatureMin = 31.0, batteryTemperatureMax = 47.5)
            .toDeviceTelemetry()
            .toSnapshot(timestampMs = 1_000L)

        assertEquals(47.5, snapshot.getValue(MetricId.TEMPERATURE_BATTERY_C))
    }

    @Test
    fun anOverheatingPackFiresTheAlert() {
        val engine = AlertEngine()
        engine.setAlerts(listOf(batteryOverheatAlert()))

        val events = engine.processTelemetry(
            leaperKim(batteryTemperatureMin = 31.0, batteryTemperatureMax = 47.5)
                .toDeviceTelemetry()
                .toSnapshot(timestampMs = 1_000L),
        )

        assertEquals("battery_overheat", events.firstOrNull()?.alert?.id)
    }

    /**
     * Begode carries no battery probe in any frame. The rule has to stay silent rather than
     * compare its threshold against a stand-in zero.
     */
    @Test
    fun aWheelWithoutTheSensorNeverFiresTheAlert() {
        val engine = AlertEngine()
        engine.setAlerts(listOf(batteryOverheatAlert()))

        val telemetry = BegodeTelemetry(
            speedKmh = 42.0,
            pwmPercent = 71.0,
            voltage = 96.4,
            current = 18.0,
            temperature = 44.0,
            motorTemperature = 63.0,
        ).toDeviceTelemetry()

        assertNull(
            telemetry[DeviceMetric.BatteryTemperatureMaxC],
            "Begode reports no battery temperature; a zero here would be an invented reading",
        )

        val fired = (1..20).flatMap { frame ->
            engine.processTelemetry(telemetry.toSnapshot(timestampMs = frame * 500L))
        }

        assertTrue(fired.isEmpty(), "a battery rule must not fire on a wheel without the probe")
    }

    /**
     * The mirror case, and the reason a missing metric may not be read as zero: a rule written
     * the other way round would otherwise be satisfied on every single frame.
     */
    @Test
    fun aBelowThresholdRuleAlsoStaysSilentWithoutTheSensor() {
        val engine = AlertEngine()
        engine.setAlerts(
            listOf(
                batteryOverheatAlert().copy(
                    id = "battery_too_cold",
                    conditionItems = listOf(
                        SingleCondition(
                            id = "cond_cold",
                            metric = MetricId.TEMPERATURE_BATTERY_C,
                            template = ConditionTemplate(
                                id = "tpl_cold",
                                name = "under 5",
                                operator = ComparisonOperator.LESS_THAN,
                                targetValue = 5.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val telemetry = BegodeTelemetry(speedKmh = 20.0, temperature = 30.0).toDeviceTelemetry()
        val fired = (1..20).flatMap { frame ->
            engine.processTelemetry(telemetry.toSnapshot(timestampMs = frame * 500L))
        }

        assertTrue(fired.isEmpty(), "an absent metric is not a cold pack")
    }
}

private fun batteryOverheatAlert() = Alert(
    id = "battery_overheat",
    name = "battery over 45",
    priority = 1,
    type = AlertType.Repeating(intervalMs = 1L),
    conditionItems = listOf(
        SingleCondition(
            id = "cond_battery",
            metric = MetricId.TEMPERATURE_BATTERY_C,
            template = ConditionTemplate(
                id = "tpl_battery",
                name = "over 45",
                operator = ComparisonOperator.GREATER_THAN,
                targetValue = 45.0,
                resetValue = 43.0,
            ),
        ),
    ),
    soundPattern = SoundPattern("pattern_battery", "beep", listOf(SoundStep())),
)

private fun leaperKim(
    batteryTemperatureMin: Double,
    batteryTemperatureMax: Double,
) = LeaperKimTelemetry(
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
    batteryTemperatureMin = batteryTemperatureMin,
    batteryTemperatureMax = batteryTemperatureMax,
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
)
