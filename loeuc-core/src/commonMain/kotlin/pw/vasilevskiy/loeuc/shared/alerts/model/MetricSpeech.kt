package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * How a metric is said out loud: the phrase a new spoken alert starts from, how many decimals
 * its number keeps, and the unit word in the three Russian plural forms.
 *
 * Two rules shape every entry here, and both come from the alert being heard in a helmet at
 * speed rather than read on a screen.
 *
 * The default phrases carry **no unit**. The rider wrote the rule and knows what their speed
 * is measured in, so the unit adds no knowledge - it adds the time during which the situation
 * changes. "Speed sixty kilometers per hour" takes about twice as long to say as "speed sixty",
 * and at 60 km/h that extra second is fifteen metres of road.
 *
 * The numbers are integers wherever the metric can bear it. Engines read decimals
 * unpredictably, and "eighty four point two" is worse in a helmet than "eighty four". Only the
 * per-cell voltages keep decimals, because there a tenth is the whole signal.
 */
object MetricSpeech {

    /** Placeholder for the metric's current value. Both spellings are accepted. */
    val valueTokens = listOf("{значение}", "{value}")

    /** Placeholder for the unit word, declined to match the number. */
    val unitTokens = listOf("{единицы}", "{unit}")

    /**
     * The unit word in the forms Russian needs to agree with a number: one, two, five.
     *
     * English gets by with the first two - [one] is the singular and [few] doubles as the
     * plural - so an entry looks the same on both sides, and only the Russian branch of
     * [unitFor] reads all three.
     */
    data class UnitWord(val one: String, val few: String, val many: String)

    /**
     * How many decimals the spoken number keeps. Zero everywhere it can be, because a spoken
     * decimal is a gamble on the engine's reading of it.
     */
    fun decimals(metric: MetricId): Int = when (metric) {
        MetricId.CELL_VOLTAGE_AVG_V, MetricId.CELL_VOLTAGE_DELTA_V -> 2
        else -> 0
    }

    /**
     * The phrase a spoken alert on this metric starts from. The rider can rewrite it; this is
     * only where they start.
     */
    fun defaultPhrase(metric: MetricId, isRu: Boolean): String {
        val name = if (isRu) defaultNameRu(metric) else defaultNameEn(metric)
        // Token taken from the list rather than spelled out again: two spellings of the same
        // placeholder drifting apart would leave a phrase that substitutes nothing.
        val token = if (isRu) valueTokens[0] else valueTokens[1]
        return "$name $token"
    }

    /**
     * The metric's name as it is spoken, which is shorter than the name on the dashboard:
     * "Motor 85" rather than "Motor temperature 85". The screen has room for the long form and
     * the rider has time to read it; a phrase said at speed has neither.
     */
    private fun defaultNameRu(metric: MetricId): String = when (metric) {
        MetricId.SPEED_KMH -> "Скорость"
        MetricId.GPS_SPEED_KMH -> "Скорость по GPS"
        MetricId.PWM_PERCENT -> "ШИМ"
        MetricId.VOLTAGE_V -> "Напряжение"
        MetricId.CELL_VOLTAGE_AVG_V -> "Ячейка"
        MetricId.CELL_VOLTAGE_DELTA_V -> "Перепад ячеек"
        MetricId.CURRENT_A -> "Ток"
        MetricId.PHASE_CURRENT_A -> "Фазный ток"
        MetricId.POWER_W -> "Мощность"
        MetricId.BATTERY_PERCENT -> "Аккумулятор"
        MetricId.TEMPERATURE_CONTROLLER_C -> "Контроллер"
        MetricId.TEMPERATURE_MOS_C -> "Мосфеты"
        MetricId.TEMPERATURE_MOTOR_C -> "Мотор"
        MetricId.TEMPERATURE_BATTERY_C -> "Батарея"
        MetricId.PITCH_DEG -> "Тангаж"
        MetricId.ROLL_DEG -> "Крен"
        MetricId.TRIP_DISTANCE_KM -> "Пробег"
        MetricId.TOTAL_DISTANCE_KM -> "Общий пробег"
    }

    private fun defaultNameEn(metric: MetricId): String = when (metric) {
        MetricId.SPEED_KMH -> "Speed"
        MetricId.GPS_SPEED_KMH -> "GPS speed"
        MetricId.PWM_PERCENT -> "PWM"
        MetricId.VOLTAGE_V -> "Voltage"
        MetricId.CELL_VOLTAGE_AVG_V -> "Cell"
        MetricId.CELL_VOLTAGE_DELTA_V -> "Cell spread"
        MetricId.CURRENT_A -> "Current"
        MetricId.PHASE_CURRENT_A -> "Phase current"
        MetricId.POWER_W -> "Power"
        MetricId.BATTERY_PERCENT -> "Battery"
        MetricId.TEMPERATURE_CONTROLLER_C -> "Controller"
        MetricId.TEMPERATURE_MOS_C -> "MOSFETs"
        MetricId.TEMPERATURE_MOTOR_C -> "Motor"
        MetricId.TEMPERATURE_BATTERY_C -> "Battery temperature"
        MetricId.PITCH_DEG -> "Pitch"
        MetricId.ROLL_DEG -> "Roll"
        MetricId.TRIP_DISTANCE_KM -> "Trip"
        MetricId.TOTAL_DISTANCE_KM -> "Odometer"
    }

    private fun unitWordRu(metric: MetricId): UnitWord = when (metric) {
        MetricId.SPEED_KMH, MetricId.GPS_SPEED_KMH ->
            UnitWord("километр в час", "километра в час", "километров в час")
        MetricId.TRIP_DISTANCE_KM, MetricId.TOTAL_DISTANCE_KM ->
            UnitWord("километр", "километра", "километров")
        MetricId.PWM_PERCENT, MetricId.BATTERY_PERCENT ->
            UnitWord("процент", "процента", "процентов")
        MetricId.VOLTAGE_V, MetricId.CELL_VOLTAGE_AVG_V, MetricId.CELL_VOLTAGE_DELTA_V ->
            UnitWord("вольт", "вольта", "вольт")
        MetricId.CURRENT_A, MetricId.PHASE_CURRENT_A ->
            UnitWord("ампер", "ампера", "ампер")
        MetricId.POWER_W ->
            UnitWord("ватт", "ватта", "ватт")
        MetricId.TEMPERATURE_CONTROLLER_C, MetricId.TEMPERATURE_MOS_C,
        MetricId.TEMPERATURE_MOTOR_C, MetricId.TEMPERATURE_BATTERY_C,
        MetricId.PITCH_DEG, MetricId.ROLL_DEG ->
            UnitWord("градус", "градуса", "градусов")
    }

    private fun unitWordEn(metric: MetricId): UnitWord = when (metric) {
        MetricId.SPEED_KMH, MetricId.GPS_SPEED_KMH ->
            UnitWord("kilometer per hour", "kilometers per hour", "kilometers per hour")
        MetricId.TRIP_DISTANCE_KM, MetricId.TOTAL_DISTANCE_KM ->
            UnitWord("kilometer", "kilometers", "kilometers")
        MetricId.PWM_PERCENT, MetricId.BATTERY_PERCENT ->
            UnitWord("percent", "percent", "percent")
        MetricId.VOLTAGE_V, MetricId.CELL_VOLTAGE_AVG_V, MetricId.CELL_VOLTAGE_DELTA_V ->
            UnitWord("volt", "volts", "volts")
        MetricId.CURRENT_A, MetricId.PHASE_CURRENT_A ->
            UnitWord("amp", "amps", "amps")
        MetricId.POWER_W ->
            UnitWord("watt", "watts", "watts")
        MetricId.TEMPERATURE_CONTROLLER_C, MetricId.TEMPERATURE_MOS_C,
        MetricId.TEMPERATURE_MOTOR_C, MetricId.TEMPERATURE_BATTERY_C,
        MetricId.PITCH_DEG, MetricId.ROLL_DEG ->
            UnitWord("degree", "degrees", "degrees")
    }

    /**
     * The unit word agreeing with [value].
     *
     * Russian picks between three forms by the last two digits, and getting it wrong is
     * audible: a template that simply appended "километров" would say "one kilometers". A
     * number with decimals always takes the [UnitWord.few] form - "three point five
     * kilometers of the metre" is how Russian counts fractions.
     */
    fun unitFor(metric: MetricId, value: Double, isRu: Boolean): String {
        val word = if (isRu) unitWordRu(metric) else unitWordEn(metric)
        if (!isRu) return if (value == 1.0) word.one else word.few
        if (value != value.toLong().toDouble()) return word.few

        val n = kotlin.math.abs(value.toLong())
        val lastTwo = n % 100
        if (lastTwo in 11..14) return word.many
        return when (n % 10) {
            1L -> word.one
            2L, 3L, 4L -> word.few
            else -> word.many
        }
    }
}
