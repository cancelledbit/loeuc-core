package pw.vasilevskiy.loeuc.extension

/**
 * Codes of the metrics a [TelemetrySnapshot] carries.
 *
 * A code is assigned once and never reused or renumbered: the host and an extension are
 * updated separately, and a renumbered code would show phase current under the label of
 * power. A code an extension does not know comes from a newer host and is simply skipped.
 *
 * Values are in the unit named by the constant.
 */
object ExtensionMetric {
    const val SPEED_KMH = 1
    const val PWM_PERCENT = 2
    const val PACK_VOLTAGE_V = 3
    const val CELL_VOLTAGE_AVG_V = 4
    const val CELL_VOLTAGE_DELTA_V = 5
    const val BATTERY_CURRENT_A = 6
    const val OUTPUT_CURRENT_A = 7
    const val PHASE_CURRENT_A = 8
    const val POWER_W = 9
    const val MOTOR_POWER_W = 10
    const val APPARENT_POWER_VA = 11
    const val BATTERY_PERCENT = 12
    const val CONTROLLER_TEMPERATURE_C = 13
    const val MOS_TEMPERATURE_C = 14
    const val MOTOR_TEMPERATURE_C = 15
    const val BOARD_TEMPERATURE_C = 16
    const val BATTERY_TEMPERATURE_MIN_C = 17
    const val BATTERY_TEMPERATURE_MAX_C = 18
    const val PITCH_DEG = 19
    const val ROLL_DEG = 20
    const val FALL_PROTECTION_ANGLE_DEG = 21
    const val DANGER_SPEED_KMH = 22
    const val TIRE_PRESSURE_BAR = 23
    const val TRIP_DISTANCE_KM = 24
    const val TOTAL_DISTANCE_KM = 25
    const val CHARGE_VOLTAGE_V = 26
    const val CHARGE_CURRENT_A = 27
    const val CHARGE_POWER_W = 28
    const val CHARGER_TEMPERATURE_C = 29
    const val CHARGE_TARGET_VOLTAGE_V = 30
    const val CHARGED_AMP_HOURS = 31
    const val CHARGED_WATT_HOURS = 32

    /** 1 while charging, 0 otherwise. */
    const val CHARGING = 33

    /** Every code this version of the contract knows, by its constant name. */
    val NAMES: Map<Int, String> = mapOf(
        SPEED_KMH to "SPEED_KMH",
        PWM_PERCENT to "PWM_PERCENT",
        PACK_VOLTAGE_V to "PACK_VOLTAGE_V",
        CELL_VOLTAGE_AVG_V to "CELL_VOLTAGE_AVG_V",
        CELL_VOLTAGE_DELTA_V to "CELL_VOLTAGE_DELTA_V",
        BATTERY_CURRENT_A to "BATTERY_CURRENT_A",
        OUTPUT_CURRENT_A to "OUTPUT_CURRENT_A",
        PHASE_CURRENT_A to "PHASE_CURRENT_A",
        POWER_W to "POWER_W",
        MOTOR_POWER_W to "MOTOR_POWER_W",
        APPARENT_POWER_VA to "APPARENT_POWER_VA",
        BATTERY_PERCENT to "BATTERY_PERCENT",
        CONTROLLER_TEMPERATURE_C to "CONTROLLER_TEMPERATURE_C",
        MOS_TEMPERATURE_C to "MOS_TEMPERATURE_C",
        MOTOR_TEMPERATURE_C to "MOTOR_TEMPERATURE_C",
        BOARD_TEMPERATURE_C to "BOARD_TEMPERATURE_C",
        BATTERY_TEMPERATURE_MIN_C to "BATTERY_TEMPERATURE_MIN_C",
        BATTERY_TEMPERATURE_MAX_C to "BATTERY_TEMPERATURE_MAX_C",
        PITCH_DEG to "PITCH_DEG",
        ROLL_DEG to "ROLL_DEG",
        FALL_PROTECTION_ANGLE_DEG to "FALL_PROTECTION_ANGLE_DEG",
        DANGER_SPEED_KMH to "DANGER_SPEED_KMH",
        TIRE_PRESSURE_BAR to "TIRE_PRESSURE_BAR",
        TRIP_DISTANCE_KM to "TRIP_DISTANCE_KM",
        TOTAL_DISTANCE_KM to "TOTAL_DISTANCE_KM",
        CHARGE_VOLTAGE_V to "CHARGE_VOLTAGE_V",
        CHARGE_CURRENT_A to "CHARGE_CURRENT_A",
        CHARGE_POWER_W to "CHARGE_POWER_W",
        CHARGER_TEMPERATURE_C to "CHARGER_TEMPERATURE_C",
        CHARGE_TARGET_VOLTAGE_V to "CHARGE_TARGET_VOLTAGE_V",
        CHARGED_AMP_HOURS to "CHARGED_AMP_HOURS",
        CHARGED_WATT_HOURS to "CHARGED_WATT_HOURS",
        CHARGING to "CHARGING",
    )
}
