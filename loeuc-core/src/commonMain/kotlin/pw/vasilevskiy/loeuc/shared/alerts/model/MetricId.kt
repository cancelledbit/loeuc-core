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
    PHONE_GPS_SPEED_KMH("Скорость GPS телефона", "Phone GPS speed", "км/ч"),
    DRAGY_SPEED_KMH("Dragy: скорость", "Dragy: speed", "км/ч"),
    DRAGY_SATELLITES("Dragy: спутники", "Dragy: satellites", ""),
    DRAGY_HACC_M("Dragy: точность", "Dragy: accuracy", "м"),
    DRAGY_HEADING_DEG("Dragy: курс", "Dragy: heading", "°"),
    DRAGY_ALT_M("Dragy: высота", "Dragy: altitude", "м"),
    DRAGY_FIX_TYPE("Dragy: тип фикса", "Dragy: fix type", ""),
    DRAGY_BATTERY_PERCENT("Dragy: заряд", "Dragy: battery", "%"),
    PWM_PERCENT("ШИМ (PWM)", "PWM", "%"),
    VOLTAGE_V("Напряжение АКБ", "Battery Voltage", "В"),
    CELL_VOLTAGE_AVG_V("Среднее напр. ячейки", "Avg Cell Voltage", "В"),
    CELL_VOLTAGE_DELTA_V("Разброс ячеек", "Cell Voltage Delta", "В"),
    CURRENT_A("Ток АКБ", "Battery Current", "А"),
    BUS_CURRENT_A("Ток шины", "Bus Current", "А"),
    PHASE_CURRENT_A("Фазный ток", "Phase Current", "А"),
    POWER_W("Мощность", "Power", "Вт"),
    BATTERY_PERCENT("Заряд АКБ", "Battery Level", "%"),
    TEMPERATURE_CONTROLLER_C("Температура контроллера", "Controller Temp", "°C"),
    TEMPERATURE_MOS_C("Температура мосфетов", "MOSFET Temp", "°C"),
    TEMPERATURE_MOTOR_C("Температура мотора", "Motor Temp", "°C"),
    TEMPERATURE_BATTERY_C("Температура АКБ", "Battery Temp", "°C"),
    PITCH_DEG("Наклон вперёд-назад", "Pitch Angle", "°"),
    ROLL_DEG("Крен", "Roll Angle", "°"),
    TRIP_DISTANCE_KM("Дистанция поездки", "Trip Distance", "км"),
    TOTAL_DISTANCE_KM("Общий пробег", "Total Odometer", "км"),

    /**
     * Tyre pressure, from the rider's own TPMS sensor rather than from the wheel.
     *
     * Like [GPS_SPEED_KMH] this is not the wheel's metric: no brand puts tyre pressure on the
     * wire, and the sensor is a separate device the rider binds themselves. It belongs here all
     * the same, because a slow puncture is the one failure a rider cannot feel until the wheel
     * is already handling differently, and watching the number by eye is exactly what an alert
     * is for. A consumer feeding only wheel telemetry into the library simply never fills it,
     * and a condition on it then stays silent.
     */
    TIRE_PRESSURE_BAR("Давление в шине", "Tire Pressure", "бар");

    /**
     * The label in the requested language.
     *
     * No default: a default here would be one language's, and this library is not written in one
     * language. Kotlin defaults do not survive Swift interop either, so every iOS call site has to
     * name the language whatever the Kotlin signature says - dropping the default only makes the
     * Kotlin side as honest as the Swift side already has to be.
     */
    fun displayName(isRu: Boolean): String = if (isRu) displayNameRu else displayNameEn

    /** The unit in the requested language. No default, for the same reason as [displayName]. */
    fun unit(isRu: Boolean): String = when (this) {
        SPEED_KMH, GPS_SPEED_KMH, PHONE_GPS_SPEED_KMH, DRAGY_SPEED_KMH -> if (isRu) "км/ч" else "km/h"
        DRAGY_HACC_M, DRAGY_ALT_M -> if (isRu) "м" else "m"
        TRIP_DISTANCE_KM, TOTAL_DISTANCE_KM -> if (isRu) "км" else "km"
        VOLTAGE_V, CELL_VOLTAGE_AVG_V, CELL_VOLTAGE_DELTA_V -> if (isRu) "В" else "V"
        TIRE_PRESSURE_BAR -> if (isRu) "бар" else "bar"
        CURRENT_A, BUS_CURRENT_A, PHASE_CURRENT_A -> if (isRu) "А" else "A"
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
