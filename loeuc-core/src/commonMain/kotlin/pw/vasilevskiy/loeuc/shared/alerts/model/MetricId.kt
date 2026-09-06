package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * Telemetry metrics an alert can be built on.
 */
enum class MetricId(
    val displayNameRu: String,
    val displayNameEn: String,
    val unit: String
) {
    SPEED_KMH("Скорость", "Speed", "км/ч"),

    /**
     * Speed as the phone's GNSS receiver reports it, not as the wheel reports it.
     *
     * Only present while GPS recording is on, and absent whenever the last fix is stale, so
     * an alert built on it stops evaluating rather than reading a frozen number.
     *
     * Deliberately absent from `MetricIdSources`: this is the phone's metric, not the wheel's,
     * and a consumer feeding wheel telemetry into the library has no fix to offer.
     */
    GPS_SPEED_KMH("Скорость по GPS", "GPS Speed", "км/ч"),
    PWM_PERCENT("ШИМ (PWM)", "PWM", "%"),
    VOLTAGE_V("Напряжение АКБ", "Battery Voltage", "В"),
    CELL_VOLTAGE_AVG_V("Среднее напр. ячейки", "Avg Cell Voltage", "В"),
    CELL_VOLTAGE_DELTA_V("Перепад ячеек (Delta)", "Cell Voltage Delta", "В"),
    CURRENT_A("Ток АКБ", "Battery Current", "А"),
    PHASE_CURRENT_A("Фазный ток", "Phase Current", "А"),
    POWER_W("Мощность", "Power", "Вт"),
    BATTERY_PERCENT("Заряд АКБ", "Battery Level", "%"),
    TEMPERATURE_CONTROLLER_C("Температура контроллера", "Controller Temp", "°C"),
    TEMPERATURE_MOS_C("Температура мосфетов", "MOSFET Temp", "°C"),
    TEMPERATURE_MOTOR_C("Температура мотора", "Motor Temp", "°C"),
    TEMPERATURE_BATTERY_C("Температура АКБ", "Battery Temp", "°C"),
    PITCH_DEG("Тангаж (Pitch)", "Pitch Angle", "°"),
    ROLL_DEG("Крен (Roll)", "Roll Angle", "°"),
    TRIP_DISTANCE_KM("Дистанция поездки", "Trip Distance", "км"),
    TOTAL_DISTANCE_KM("Общий пробег", "Total Odometer", "км");

    fun displayName(isRu: Boolean = true): String = if (isRu) displayNameRu else displayNameEn

    fun unit(isRu: Boolean = true): String = when (this) {
        SPEED_KMH, GPS_SPEED_KMH -> if (isRu) "км/ч" else "km/h"
        TRIP_DISTANCE_KM, TOTAL_DISTANCE_KM -> if (isRu) "км" else "km"
        VOLTAGE_V, CELL_VOLTAGE_AVG_V, CELL_VOLTAGE_DELTA_V -> if (isRu) "В" else "V"
        CURRENT_A, PHASE_CURRENT_A -> if (isRu) "А" else "A"
        POWER_W -> if (isRu) "Вт" else "W"
        else -> unit
    }

    companion object {
        /**
         * Names under which metrics were stored in alerts riders have already saved.
         *
         * Before the split there was one `TEMPERATURE_MOSFET_C`. It was named after the
         * MOSFETs but read the wheel's general temperature wherever no separate MOSFET
         * sensor exists, which is the case on Begode and KingSong. So the old name maps to
         * [TEMPERATURE_CONTROLLER_C] rather than to the new [TEMPERATURE_MOS_C]: a saved
         * alert has to stay on the sensor it was configured against.
         */
        private val legacyNames: Map<String, MetricId> = mapOf(
            "TEMPERATURE_MOSFET_C" to TEMPERATURE_CONTROLLER_C,
        )

        fun fromKey(key: String): MetricId? =
            entries.find { it.name.equals(key, ignoreCase = true) }
                ?: legacyNames.entries
                    .firstOrNull { (legacy, _) -> legacy.equals(key, ignoreCase = true) }
                    ?.value
    }
}
