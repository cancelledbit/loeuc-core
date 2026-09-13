package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Payload decoding for the legacy InMotion "ezcan" protocol. Layouts, scaling and the model
 * table follow WheelLog's InMotion adapter, the reference client for these wheels; where this
 * code departs from it the comment at that spot says why. Offsets are into the `extra` payload
 * of the extended reply.
 */

/**
 * Wheel models known to speak the legacy protocol, keyed by the model id slow info carries.
 *
 * @property modelId decimal model id as the wheel reports it: byte 107 (when non-zero) followed
 *   by byte 104 of slow info, so V8F is "86" and V10F is "141".
 * @property speedFactor raw speed counts per m/s.
 * @property maxSpeedKmh the top of the speed-limit range the reference client offers.
 */
enum class InmotionLegacyModel(
    val modelId: String,
    val displayName: String,
    val speedFactor: Double,
    val maxSpeedKmh: Int,
) {
    R1N("0", "R1N", 3812.0, 70),
    R1S("1", "R1S", 1000.0, 70),
    R1CF("2", "R1CF", 3812.0, 70),
    R1AP("3", "R1AP", 3812.0, 70),
    R1EX("4", "R1EX", 3812.0, 70),
    R1_SAMPLE("5", "R1 Sample", 1000.0, 70),
    R1T("6", "R1T", 3810.0, 70),
    R10("7", "R10", 3812.0, 70),
    V3("10", "V3", 3812.0, 70),
    V3C("11", "V3C", 3812.0, 70),
    V3_PRO("12", "V3 Pro", 3812.0, 70),
    V3S("13", "V3S", 3812.0, 70),
    R2("20", "R2", 3812.0, 70),
    R2N("21", "R2N", 3812.0, 70),
    R2S("22", "R2S", 3812.0, 70),
    R2_SAMPLE("23", "R2 Sample", 3812.0, 70),
    R2EX("24", "R2EX", 3812.0, 70),
    R0("30", "R0", 1000.0, 70),
    V5("50", "V5", 3812.0, 25),
    V5_PLUS("51", "V5+", 3812.0, 25),
    V5F("52", "V5F", 3812.0, 25),
    V5D("53", "V5D", 3812.0, 25),
    L6("60", "L6", 3812.0, 70),
    LIVELY("61", "Lively", 3812.0, 70),
    V8("80", "V8", 3812.0, 35),
    GLIDE_3("85", "Solowheel Glide 3", 3812.0, 35),
    V8F("86", "V8F", 3812.0, 45),
    V8S("87", "V8S", 3812.0, 45),
    V10S("100", "V10S", 3812.0, 45),
    V10SF("101", "V10SF", 3812.0, 45),
    V10("140", "V10", 3812.0, 45),
    V10F("141", "V10F", 3812.0, 45),
    V10T("142", "V10T", 3812.0, 45),
    V10FT("143", "V10FT", 3812.0, 45),
    ;

    /**
     * True for the models whose work-mode word is read by its high nibble, and whose roll field
     * the reference client ignores: V8F, V8S and the V10 family.
     */
    val isModernGeneration: Boolean
        get() = this == V8F || this == V8S || isV10Family

    internal val isV10Family: Boolean
        get() = this == V10 || this == V10F || this == V10S || this == V10SF || this == V10T || this == V10FT

    internal val isV5Family: Boolean
        get() = this == V5 || this == V5_PLUS || this == V5F || this == V5D

    internal val isV3Family: Boolean
        get() = this == V3 || this == V3C || this == V3_PRO || this == V3S

    companion object {
        /** The model decoding falls back to when the id is missing or unknown, as the reference does. */
        val FALLBACK: InmotionLegacyModel = V8

        fun fromModelId(modelId: String): InmotionLegacyModel? = entries.firstOrNull { it.modelId == modelId }
    }
}

/**
 * Identity from slow info.
 *
 * @property modelId the raw model id; empty when the payload is too short to carry one.
 * @property model the matched model, or null when [modelId] is not in the table.
 * @property decodingModel the model whose scaling telemetry uses: [model], or V8 as the
 *   reference client falls back to.
 */
data class InmotionLegacyIdentity(
    val serialNumber: String,
    val firmwareVersion: String,
    val modelId: String,
    val model: InmotionLegacyModel?,
    val decodingModel: InmotionLegacyModel,
    val displayName: String,
)

/**
 * Settings the wheel reports in slow info. Read-only. Each field is null when the payload is too
 * short to carry it: a V5F sends 124 bytes, which holds max speed, headlight and pedal tilt but
 * none of the others.
 *
 * @property pedalTiltDegrees pedal horizon, rounded to 0.1 degree.
 * @property pedalHardness 4..100 per the reference client's reading of byte 124 (128 = 100).
 * @property rideModeClassic true = classic, false = comfort.
 * @property handleButtonDisabled byte 129 != 1. The official app names the same byte "lift-up
 *   detection" with the opposite polarity.
 */
data class InmotionLegacySettings(
    val maxSpeedKmh: Int?,
    val headlightOn: Boolean?,
    val pedalTiltDegrees: Double?,
    val speakerVolume: Int?,
    val pedalHardness: Int?,
    val handleButtonDisabled: Boolean?,
    val decorativeLightOn: Boolean?,
    val rideModeClassic: Boolean?,
)

enum class InmotionLegacyWorkMode {
    IDLE,
    DRIVE,
    ZERO,
    LARGE_ANGLE,
    CHECK,
    LOCK,
    ERROR,
    CARRY,
    REMOTE_CONTROL,
    SHUTDOWN,
    POM_STOP,
    UNLOCK,
    CHARGING,
    UNKNOWN,
}

/**
 * Realtime telemetry from fast info.
 *
 * @property model the model whose scaling was applied.
 * @property speedKmh always non-negative: the reference formula takes the absolute value.
 * @property temperature board temperature, signed byte 32.
 * @property secondTemperature signed byte 34 ("IMU" in the reference client, "MOS" in the
 *   official app), or null when implausible - a V8S reports -110 there, which reads as a missing
 *   sensor rather than a temperature. [secondTemperatureRaw] keeps the byte.
 * @property rollDegrees null on V8F, V8S and the V10 family, whose field at offset 72 the reference
 *   client does not trust (it reports zero; a V8F capture carries 6.72 degrees there, which is not
 *   known to be a roll), and null when the payload is too short.
 * @property engineOff low nibble 1 on the modern generation; null on older models.
 * @property batteryPercent estimated from pack voltage; null where the reference has no curve.
 */
data class InmotionLegacyTelemetry(
    val model: InmotionLegacyModel,
    val speedKmh: Double,
    val voltage: Double,
    val current: Double,
    val power: Double,
    val temperature: Int,
    val secondTemperature: Int?,
    val secondTemperatureRaw: Int,
    val totalDistanceMeters: Double,
    val tripDistanceMeters: Double,
    val pitchDegrees: Double,
    val rollDegrees: Double?,
    val workModeRaw: Int?,
    val workMode: InmotionLegacyWorkMode?,
    val workModeText: String?,
    val engineOff: Boolean?,
    val batteryPercent: Int?,
)

/**
 * An alert the wheel pushed (id 0x0F780101). The id and both raw values come from the 8-byte
 * data field; the derived values are filled only for the ids that carry them, with the reference
 * client's scaling. Real captures also show ids 0x01 and 0x28, which have no known meaning.
 *
 * @property rawValue signed 16-bit LE at data[2..3].
 * @property rawValue2 signed 32-bit LE at data[4..7].
 */
data class InmotionLegacyAlert(
    val alertId: Int,
    val name: String,
    val rawValue: Int,
    val rawValue2: Int,
    val speedKmh: Double?,
    val tiltAngleDegrees: Double?,
    val tiltbackLimit: Double?,
    val voltage: Double?,
    val current: Double?,
    val cutoffValue: Double?,
)

object InmotionLegacyDecoder {
    const val ALERT_START_FROM_TILT: Int = 0x05
    const val ALERT_TILTBACK: Int = 0x06
    const val ALERT_FALL_DOWN: Int = 0x19
    const val ALERT_BAD_BATTERY_CELL: Int = 0x1D
    const val ALERT_LOW_BATTERY: Int = 0x20
    const val ALERT_SPEED_CUTOFF: Int = 0x21
    const val ALERT_HIGH_LOAD: Int = 0x26

    private const val SLOW_IDENTITY_MIN_SIZE = 28
    private const val SLOW_MODEL_ID_MIN_SIZE = 108
    private const val FAST_CORE_MIN_SIZE = 52
    private const val FAST_WORK_MODE_MIN_SIZE = 64
    private const val FAST_ROLL_MIN_SIZE = 76
    private const val PLAUSIBLE_TEMPERATURE_MIN = -40
    private const val PLAUSIBLE_TEMPERATURE_MAX = 125

    /** Divisor the reference client applies to the 64-bit total of R1/R2/R10/Lively wheels. */
    private const val LEGACY_R_DISTANCE_DIVISOR = 5.711016379455429E7

    /** Serial, firmware and model from a slow info reply; null for any other message. */
    fun decodeIdentity(message: InmotionLegacyMessage): InmotionLegacyIdentity? {
        if (message.id != InmotionLegacyIds.SLOW_INFO) return null
        val extra = message.extra ?: return null
        if (extra.size < SLOW_IDENTITY_MIN_SIZE) return null

        val serial = buildString {
            for (index in 7 downTo 0) append(extra.ezU8(index).toHex2())
        }
        val firmware = "${extra.ezU8(27)}.${extra.ezU8(26)}.${extra.ezU16Le(24)}"
        // Unsigned bytes throughout; the reference prints signed ones, which differs only for
        // ids above 127 that no known wheel uses.
        val modelId = if (extra.size >= SLOW_MODEL_ID_MIN_SIZE) {
            val high = extra.ezU8(107)
            (if (high > 0) high.toString() else "") + extra.ezU8(104).toString()
        } else {
            ""
        }
        val model = InmotionLegacyModel.fromModelId(modelId)
        return InmotionLegacyIdentity(
            serialNumber = serial,
            firmwareVersion = firmware,
            modelId = modelId,
            model = model,
            decodingModel = model ?: InmotionLegacyModel.FALLBACK,
            displayName = model?.displayName ?: if (modelId.isEmpty()) "Unknown model" else "Unknown model $modelId",
        )
    }

    /**
     * Settings from a slow info reply. Unlike the reference client, which fills a default for a
     * field the payload does not reach and so reports a V5F's volume as 0, a missing field is null.
     */
    fun decodeSettings(message: InmotionLegacyMessage): InmotionLegacySettings? {
        if (message.id != InmotionLegacyIds.SLOW_INFO) return null
        val extra = message.extra ?: return null
        fun has(lastIndex: Int) = extra.size > lastIndex
        return InmotionLegacySettings(
            maxSpeedKmh = if (has(61)) extra.ezU16Le(60) / 1000 else null,
            headlightOn = if (has(80)) extra.ezU8(80) == 1 else null,
            pedalTiltDegrees = if (has(59)) (extra.ezI32Le(56) / 6553.6).roundToInt() / 10.0 else null,
            speakerVolume = if (has(126)) extra.ezU16Le(125) / 100 else null,
            pedalHardness = if (has(124)) (extra.ezU8(124) - 28) and 0xFF else null,
            handleButtonDisabled = if (has(129)) extra.ezU8(129) != 1 else null,
            decorativeLightOn = if (has(130)) extra.ezU8(130) == 1 else null,
            rideModeClassic = if (has(132)) extra.ezU8(132) == 1 else null,
        )
    }

    /** Telemetry from a fast info reply, with the default battery curve. */
    fun decodeTelemetry(message: InmotionLegacyMessage, model: InmotionLegacyModel): InmotionLegacyTelemetry? =
        decodeTelemetry(message, model, false)

    /**
     * Telemetry from a fast info reply decoded with [model]'s scaling.
     *
     * @param betterBatteryPercents use the reference client's alternative battery curves.
     */
    fun decodeTelemetry(
        message: InmotionLegacyMessage,
        model: InmotionLegacyModel,
        betterBatteryPercents: Boolean,
    ): InmotionLegacyTelemetry? {
        if (message.id != InmotionLegacyIds.FAST_INFO) return null
        val extra = message.extra ?: return null
        if (extra.size < FAST_CORE_MIN_SIZE) return null

        val speedRaw = extra.ezI32Le(12).toDouble() + extra.ezI32Le(16).toDouble()
        val speedKmh = abs(speedRaw / (model.speedFactor * 2.0)) * 3.6
        val voltage = extra.ezI32Le(24) / 100.0
        val current = extra.ezI32Le(20) / 100.0
        val secondTemperatureRaw = extra[34].toInt()

        val workModeRaw = if (extra.size >= FAST_WORK_MODE_MIN_SIZE) extra.ezI32Le(60) else null
        val workMode = workModeRaw?.let { decodeWorkMode(it, model) }

        return InmotionLegacyTelemetry(
            model = model,
            speedKmh = speedKmh,
            voltage = voltage,
            current = current,
            power = voltage * current,
            temperature = extra[32].toInt(),
            secondTemperature = secondTemperatureRaw.takeIf {
                it in PLAUSIBLE_TEMPERATURE_MIN..PLAUSIBLE_TEMPERATURE_MAX
            },
            secondTemperatureRaw = secondTemperatureRaw,
            totalDistanceMeters = totalDistance(extra, model),
            tripDistanceMeters = extra.ezU32Le(48).toDouble(),
            pitchDegrees = extra.ezI32Le(0) / 65536.0,
            rollDegrees = when {
                extra.size < FAST_ROLL_MIN_SIZE -> null
                model.isModernGeneration -> null
                else -> extra.ezI32Le(72) / 90.0
            },
            workModeRaw = workModeRaw,
            workMode = workMode?.first,
            workModeText = workMode?.second,
            engineOff = if (workModeRaw != null && model.isModernGeneration) (workModeRaw and 0xF) == 1 else null,
            batteryPercent = batteryPercent(voltage, model, betterBatteryPercents),
        )
    }

    /** The alert in a pushed 0x0F780101 message; null for any other message. */
    fun decodeAlert(message: InmotionLegacyMessage): InmotionLegacyAlert? {
        if (message.id != InmotionLegacyIds.ALERT) return null
        val data = message.data
        val alertId = data.ezU8(0)
        val value = (data.ezU16Le(2)).toShort().toInt()
        val value2 = data.ezI32Le(4)
        // The reference converts the speed with the V-series factor for every model.
        val speed = abs(value2 / 3812.0 * 3.6)
        val name = when (alertId) {
            ALERT_START_FROM_TILT -> "Start from tilt angle"
            ALERT_TILTBACK -> "Tiltback"
            ALERT_FALL_DOWN -> "Fall down"
            ALERT_BAD_BATTERY_CELL -> "Bad battery cell"
            ALERT_LOW_BATTERY -> "Low battery"
            ALERT_SPEED_CUTOFF -> "Speed cut-off"
            ALERT_HIGH_LOAD -> "High load"
            else -> "Unknown alert"
        }
        return InmotionLegacyAlert(
            alertId = alertId,
            name = name,
            rawValue = value,
            rawValue2 = value2,
            speedKmh = if (alertId in SPEED_ALERTS) speed else null,
            tiltAngleDegrees = if (alertId == ALERT_START_FROM_TILT) value / 100.0 else null,
            tiltbackLimit = if (alertId == ALERT_TILTBACK) value / 1000.0 else null,
            voltage = if (alertId == ALERT_LOW_BATTERY || alertId == ALERT_BAD_BATTERY_CELL) value2 / 100.0 else null,
            current = if (alertId == ALERT_HIGH_LOAD) value / 1000.0 else null,
            cutoffValue = if (alertId == ALERT_SPEED_CUTOFF) value / 10.0 else null,
        )
    }

    /**
     * Battery estimate from pack voltage with the reference client's per-model curves, truncated
     * to a whole percent. Returns null for L6 and Lively, where the reference always shows 0 -
     * that is the absence of a curve, not an empty battery.
     */
    fun batteryPercent(voltage: Double, model: InmotionLegacyModel, betterPercents: Boolean): Int? {
        val fraction = when {
            model == InmotionLegacyModel.L6 || model == InmotionLegacyModel.LIVELY -> return null
            model.isV3Family || model == InmotionLegacyModel.R0 -> when {
                voltage >= 82.50 -> 1.0
                voltage > 68.0 -> (voltage - 68.0) / 14.50
                else -> 0.0
            }
            model.isV5Family || model == InmotionLegacyModel.V8 || model == InmotionLegacyModel.GLIDE_3 ||
                model == InmotionLegacyModel.V8F || model == InmotionLegacyModel.V8S -> when {
                betterPercents && voltage > 84.00 -> 1.0
                betterPercents && voltage > 68.5 -> (voltage - 68.5) / 15.5
                betterPercents -> 0.0
                else -> defaultLinearFraction(voltage)
            }
            model.isV10Family -> when {
                betterPercents && voltage > 83.50 -> 1.0
                betterPercents && voltage > 68.00 -> (voltage - 66.50) / 17.0
                betterPercents && voltage > 64.00 -> (voltage - 64.00) / 45.0
                betterPercents -> 0.0
                else -> defaultLinearFraction(voltage)
            }
            else -> when {
                voltage >= 82.00 -> 1.0
                voltage > 77.8 -> (voltage - 77.8) / 4.2 * 0.2 + 0.8
                voltage > 74.8 -> (voltage - 74.8) / 3.0 * 0.2 + 0.6
                voltage > 71.8 -> (voltage - 71.8) / 3.0 * 0.2 + 0.4
                voltage > 70.3 -> (voltage - 70.3) / 1.5 * 0.2 + 0.2
                voltage > 68.0 -> (voltage - 68.0) / 2.3 * 0.2
                else -> 0.0
            }
        }
        return (fraction * 100.0).toInt()
    }

    private fun defaultLinearFraction(voltage: Double): Double = when {
        voltage > 82.50 -> 1.0
        voltage > 68.0 -> (voltage - 68.0) / 14.5
        else -> 0.0
    }

    private fun totalDistance(extra: ByteArray, model: InmotionLegacyModel): Double = when {
        model.isV3Family || model.isV5Family || model == InmotionLegacyModel.V8 ||
            model == InmotionLegacyModel.GLIDE_3 || model.isV10Family ||
            model == InmotionLegacyModel.V8F || model == InmotionLegacyModel.V8S ->
            extra.ezU32Le(44).toDouble()
        // The R-series branches read eight bytes from offset 44, overlapping the trip field at
        // 48, exactly as the reference does. No R-series capture exists to check this against.
        model == InmotionLegacyModel.R0 -> extra.ezI64Le(44).toDouble()
        model == InmotionLegacyModel.L6 -> extra.ezI64Le(44).toDouble() * 100.0
        else -> (extra.ezI64Le(44) / LEGACY_R_DISTANCE_DIVISOR).roundToLong().toDouble()
    }

    /**
     * Work mode word at offset 60. Older models use the low nibble as a state; the modern
     * generation uses the value shifted right by four (1 shutdown, 2 drive, 3 charging) with
     * low nibble 1 meaning the motor is off. The official app reads the modern word differently
     * (low nibble plus lock and transport bits); captures in drive mode cannot tell them apart.
     */
    private fun decodeWorkMode(raw: Int, model: InmotionLegacyModel): Pair<InmotionLegacyWorkMode, String> {
        if (model.isModernGeneration) {
            val high = raw shr 4
            val mode = when (high) {
                1 -> InmotionLegacyWorkMode.SHUTDOWN
                2 -> InmotionLegacyWorkMode.DRIVE
                3 -> InmotionLegacyWorkMode.CHARGING
                else -> InmotionLegacyWorkMode.UNKNOWN
            }
            val base = when (mode) {
                InmotionLegacyWorkMode.SHUTDOWN -> "Shutdown"
                InmotionLegacyWorkMode.DRIVE -> "Drive"
                InmotionLegacyWorkMode.CHARGING -> "Charging"
                else -> "Unknown code $high"
            }
            return mode to if ((raw and 0xF) == 1) "$base - Engine off" else base
        }
        return when (raw and 0xF) {
            0 -> InmotionLegacyWorkMode.IDLE to "Idle"
            1 -> InmotionLegacyWorkMode.DRIVE to "Drive"
            2 -> InmotionLegacyWorkMode.ZERO to "Zero"
            3 -> InmotionLegacyWorkMode.LARGE_ANGLE to "Large angle"
            4 -> InmotionLegacyWorkMode.CHECK to "Check"
            5 -> InmotionLegacyWorkMode.LOCK to "Lock"
            6 -> InmotionLegacyWorkMode.ERROR to "Error"
            7 -> InmotionLegacyWorkMode.CARRY to "Carry"
            8 -> InmotionLegacyWorkMode.REMOTE_CONTROL to "Remote control"
            9 -> InmotionLegacyWorkMode.SHUTDOWN to "Shutdown"
            10 -> InmotionLegacyWorkMode.POM_STOP to "POM stop"
            12 -> InmotionLegacyWorkMode.UNLOCK to "Unlock"
            else -> InmotionLegacyWorkMode.UNKNOWN to "Unknown"
        }
    }

    private val SPEED_ALERTS = setOf(ALERT_START_FROM_TILT, ALERT_TILTBACK, ALERT_SPEED_CUTOFF, ALERT_HIGH_LOAD)
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun Int.toHex2(): String = "${HEX_DIGITS[(this shr 4) and 0xF]}${HEX_DIGITS[this and 0xF]}"

internal fun ByteArray.ezU16Le(index: Int): Int = ezU8(index) or (ezU8(index + 1) shl 8)

internal fun ByteArray.ezI32Le(index: Int): Int = ezU32Le(index).toInt()

internal fun ByteArray.ezI64Le(index: Int): Long = ezU32Le(index) or (ezU32Le(index + 4) shl 32)
