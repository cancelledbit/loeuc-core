package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

data class NinebotTelemetry(
    val speedKmh: Double = Double.NaN,
    val averageSpeedKmh: Double = Double.NaN,
    val voltage: Double = Double.NaN,
    val current: Double = Double.NaN,
    val power: Double = Double.NaN,
    /** Derived, not reported. See [ninebotPwmPercent]. */
    val pwmPercent: Double = Double.NaN,
    val temperature: Double = Double.NaN,
    val batteryPercent: Double = Double.NaN,
    val tripDistanceMeters: Double = Double.NaN,
    val totalDistanceMeters: Double = Double.NaN,
    val operatingTimeSeconds: Double = Double.NaN,
    val errorCode: Int = 0,
    val alarmCode: Int = 0,
    val escStatus: Int = 0,
)

/**
 * One Ninebot battery. The wheel answers three separate reads per pack, so a pack fills in over
 * three round trips and is incomplete until all three have arrived.
 */
data class NinebotBmsPack(
    val index: Int,
    val serialNumber: String? = null,
    val firmwareVersion: String? = null,
    val factoryCapacityAh: Double? = null,
    val actualCapacityAh: Double? = null,
    val fullCycles: Int? = null,
    val chargeCount: Int? = null,
    val manufactureDate: String? = null,
    val status: Int? = null,
    val remainingCapacityAh: Double? = null,
    val remainingPercent: Int? = null,
    val currentAmps: Double? = null,
    val voltage: Double? = null,
    val temperature1: Double? = null,
    val temperature2: Double? = null,
    val balanceMap: Int? = null,
    val healthPercent: Int? = null,
    val cells: List<Double> = emptyList(),
) {
    val hasData: Boolean get() = voltage != null || cells.isNotEmpty() || serialNumber != null

    val cellCount: Int get() = cells.size
}

data class NinebotDiagnostics(
    val errorCode1: Int = 0,
    val errorCode2: Int = 0,
    val warningCode1: Int = 0,
    val warningCode2: Int = 0,
    /** From the live-data frame, which reports faults faster than the firmware read. */
    val liveErrorCode: Int = 0,
    val liveAlarmCode: Int = 0,
) {
    val hasFault: Boolean
        get() = errorCode1 != 0 || errorCode2 != 0 || liveErrorCode != 0

    val messagesEn: List<String>
        get() = activeCodes.map { code -> "Err $code: ${ninebotErrorTextEn(code)}" }

    val messagesRu: List<String>
        get() = activeCodes.map { code -> "Ошибка $code: ${ninebotErrorTextRu(code)}" }

    private val activeCodes: List<Int>
        get() = listOf(errorCode1, errorCode2, liveErrorCode).filter { it != 0 }.distinct()
}

/** What one decoded frame changed. Null members mean "this frame said nothing about it". */
data class NinebotProtocolUpdate(
    val param: Int,
    val source: Int,
    val telemetry: NinebotTelemetry? = null,
    val bmsPacks: List<NinebotBmsPack>? = null,
    val diagnostics: NinebotDiagnostics? = null,
    val settingsChanged: Boolean = false,
    val serialNumber: String? = null,
    val firmwareVersion: String? = null,
)

/**
 * Ninebot Z (Z6/Z8/Z10) protocol.
 *
 * Framing lives in [NinebotFrameCodec], the settings surface in [NinebotSettingsCatalog]; this
 * holds the session — the rolling receive buffer, the latest telemetry, the two battery packs,
 * and the three settings blocks the wheel has answered so far.
 *
 * Derived from the byte layout described in `docs/ninebot-protocol-notes.md`. Until a real Z10
 * capture lands, every field is a hypothesis.
 */
class NinebotProtocolEngine {
    /**
     * Fixed at [NinebotKeystream.Identity] rather than injected: no Z-series wheel negotiates a
     * key, and a constructor default would not survive the trip to Swift anyway. The framing in
     * [NinebotFrameCodec] takes a keystream, which is where a future encrypted model plugs in.
     */
    private val keystream: NinebotKeystream = NinebotKeystream.Identity

    private var buffer = ByteArray(0)
    private var telemetry = NinebotTelemetry()
    private var diagnostics = NinebotDiagnostics()
    private val packs = mutableMapOf<Int, NinebotBmsPack>()
    private val blocks = mutableMapOf<Int, NinebotParamBlock>()

    var serialNumber: String? = null
        private set
    var firmwareVersion: String? = null
        private set

    fun reset() {
        buffer = ByteArray(0)
        telemetry = NinebotTelemetry()
        diagnostics = NinebotDiagnostics()
        packs.clear()
        blocks.clear()
        serialNumber = null
        firmwareVersion = null
    }

    // --- commands ------------------------------------------------------------------------

    /**
     * The connect burst. WheelLog walks these through a step counter that waits for each answer;
     * LoEUC's service sends them as a batch and lets unanswered reads simply go unanswered, the
     * same way the InMotion path does.
     */
    fun initialReadCommands(): List<ByteArray> = buildList {
        add(read(NinebotAddress.Controller, NinebotParam.BleVersion, 0x02))
        add(read(NinebotAddress.Controller, NinebotParam.SerialNumber, 0x0E))
        add(read(NinebotAddress.Controller, NinebotParam.Firmware, 0x06))
        addAll(settingsReadCommands())
    }

    fun telemetryPollingCommand(): ByteArray =
        read(NinebotAddress.Controller, NinebotParam.LiveData, 0x20)

    fun bmsPollingCommands(): List<ByteArray> = buildList {
        listOf(NinebotAddress.Bms1, NinebotAddress.Bms2).forEach { address ->
            add(read(address, NinebotParam.BmsSerial, 0x22))
            add(read(address, NinebotParam.BmsLife, 0x18))
            add(read(address, NinebotParam.BmsCells, 0x20))
        }
    }

    /**
     * Re-reads the settings blocks. With no argument, all three; with a key, only the block that
     * key lives in — which is what a write needs so the screen can show the wheel's answer
     * instead of the value that was requested.
     */
    fun settingsReadCommands(key: String? = null): List<ByteArray> {
        val bases = if (key == null) {
            NinebotSettingsCatalog.blockBaseIds
        } else {
            listOfNotNull(NinebotSettingsCatalog.readBackBlockBase(key))
        }
        return bases.map { base ->
            read(NinebotAddress.Controller, base, NinebotSettingsCatalog.blockLength(base))
        }
    }

    fun settingsCapabilities(): List<NinebotSettingCapability> =
        NinebotSettingsCatalog.capabilities(blocks)

    @ExperimentalWriteApi
    fun buildSettingCommand(key: String, value: Int): ByteArray? =
        NinebotSettingsCatalog.buildCommand(key, value, blocks, keystream)

    private fun read(destination: Int, param: Int, length: Int): ByteArray =
        NinebotFrameCodec.build(
            destination = destination,
            command = NinebotCommand.Read,
            param = param,
            data = byteArrayOf(length.toByte()),
            keystream = keystream,
        )

    // --- decoding ------------------------------------------------------------------------

    fun latestTelemetry(): NinebotTelemetry = telemetry

    fun latestDiagnostics(): NinebotDiagnostics = diagnostics

    fun bmsPacks(): List<NinebotBmsPack> =
        packs.values.filter(NinebotBmsPack::hasData).sortedBy(NinebotBmsPack::index)

    /** Feeds a BLE notification and returns whatever it resolved. */
    fun consume(chunk: ByteArray): List<NinebotProtocolUpdate> {
        val result = NinebotFrameCodec.extract(buffer + chunk, keystream)
        buffer = result.remainingBuffer
        return result.frames.mapNotNull(::apply)
    }

    /** Feeds one already-reassembled frame, markers included. */
    fun consumeFrame(frame: ByteArray): NinebotProtocolUpdate? =
        NinebotFrameCodec.parse(frame, keystream)?.let(::apply)

    /**
     * Names the settings block [frame] carries, or null if it carries none. One name per block,
     * so a capture window that pins them keeps all three rather than letting the last one
     * answered stand in for the other two. The block ids live in the (internal) settings catalog,
     * which is why this is asked of the engine rather than re-derived by each platform.
     */
    fun settingsFrameSlot(frame: ByteArray): String? {
        val parsed = NinebotFrameCodec.parse(frame, keystream) ?: return null
        if (!parsed.isFromController || parsed.param !in NinebotSettingsCatalog.blockBaseIds) {
            return null
        }
        return "ninebot_settings_${parsed.param}"
    }

    private fun apply(frame: NinebotFrame): NinebotProtocolUpdate? {
        frame.bmsIndex?.let { index -> return applyBms(frame, index) }
        if (!frame.isFromController) return null

        return when (frame.param) {
            NinebotParam.LiveData -> applyLiveData(frame)
            NinebotParam.SerialNumber -> applySerialNumber(frame)
            NinebotParam.Firmware -> applyFirmware(frame)
            in NinebotSettingsCatalog.blockBaseIds -> applySettingsBlock(frame)
            else -> null
        }
    }

    private fun applyLiveData(frame: NinebotFrame): NinebotProtocolUpdate? {
        val data = frame.payload
        if (data.size < 28) return null

        val voltage = data.u16Le(24) / 100.0
        val speed = data.u16Le(10) / 100.0
        val current = data.i16Le(26) / 100.0
        telemetry = NinebotTelemetry(
            speedKmh = speed,
            averageSpeedKmh = data.u16Le(12) / 100.0,
            voltage = voltage,
            current = current,
            power = voltage * current,
            pwmPercent = ninebotPwmPercent(speedKmh = speed, voltage = voltage),
            temperature = data.i16Le(22) / 10.0,
            batteryPercent = data.u16Le(8).toDouble(),
            tripDistanceMeters = data.u16Le(18) * 10.0,
            totalDistanceMeters = data.u32Le(14).toDouble(),
            operatingTimeSeconds = data.u16Le(20).toDouble(),
            errorCode = data.u16Le(0),
            alarmCode = data.u16Le(2),
            escStatus = data.u16Le(4),
        )
        diagnostics = diagnostics.copy(
            liveErrorCode = telemetry.errorCode,
            liveAlarmCode = telemetry.alarmCode,
        )
        return NinebotProtocolUpdate(
            param = frame.param,
            source = frame.source,
            telemetry = telemetry,
            diagnostics = diagnostics,
        )
    }

    private fun applySerialNumber(frame: NinebotFrame): NinebotProtocolUpdate? {
        val serial = frame.payload.asAscii().ifBlank { return null }
        serialNumber = serial
        return NinebotProtocolUpdate(
            param = frame.param,
            source = frame.source,
            serialNumber = serial,
        )
    }

    private fun applyFirmware(frame: NinebotFrame): NinebotProtocolUpdate? {
        val data = frame.payload
        if (data.size < 6) return null
        // Three nibbles, high to low: major from byte 1, then both nibbles of byte 0.
        val version = buildString {
            append((data.u8(1) and 0x0F).toString(16).uppercase())
            append('.')
            append(((data.u8(0) shr 4) and 0x0F).toString(16).uppercase())
            append('.')
            append((data.u8(0) and 0x0F).toString(16).uppercase())
        }
        firmwareVersion = version
        diagnostics = diagnostics.copy(
            errorCode1 = data.u8(2),
            errorCode2 = data.u8(3),
            warningCode1 = data.u8(4),
            warningCode2 = data.u8(5),
        )
        return NinebotProtocolUpdate(
            param = frame.param,
            source = frame.source,
            firmwareVersion = version,
            diagnostics = diagnostics,
        )
    }

    private fun applySettingsBlock(frame: NinebotFrame): NinebotProtocolUpdate {
        blocks[frame.param] = NinebotParamBlock(
            baseParamId = frame.param,
            payload = frame.payload,
        )
        return NinebotProtocolUpdate(
            param = frame.param,
            source = frame.source,
            settingsChanged = true,
        )
    }

    private fun applyBms(frame: NinebotFrame, index: Int): NinebotProtocolUpdate? {
        val data = frame.payload
        val current = packs[index] ?: NinebotBmsPack(index = index)
        val updated = when (frame.param) {
            NinebotParam.BmsSerial -> {
                if (data.size < 34) return null
                current.copy(
                    serialNumber = data.copyOfRange(0, 14).asAscii(),
                    firmwareVersion = buildString {
                        append(data.u8(15).toString(16).uppercase())
                        append('.')
                        append(((data.u8(14) shr 4) and 0x0F).toString(16).uppercase())
                        append('.')
                        append((data.u8(14) and 0x0F).toString(16).uppercase())
                    },
                    factoryCapacityAh = data.u16Le(16) / 1_000.0,
                    actualCapacityAh = data.u16Le(18) / 1_000.0,
                    fullCycles = data.u16Le(22),
                    chargeCount = data.u16Le(24),
                    manufactureDate = ninebotPackedDate(data.u16Le(32)),
                )
            }

            NinebotParam.BmsLife -> {
                if (data.size < 24) return null
                current.copy(
                    status = data.u16Le(0),
                    remainingCapacityAh = data.u16Le(2) / 1_000.0,
                    remainingPercent = data.u16Le(4),
                    currentAmps = data.i16Le(6) / 100.0,
                    voltage = data.u16Le(8) / 100.0,
                    temperature1 = (data.u8(10) - NinebotBmsTemperatureOffset).toDouble(),
                    temperature2 = (data.u8(11) - NinebotBmsTemperatureOffset).toDouble(),
                    balanceMap = data.u16Le(12),
                    healthPercent = data.u16Le(22),
                )
            }

            NinebotParam.BmsCells -> {
                if (data.size < 32) return null
                val millivolts = (0 until 16).map { cell -> data.u16Le(cell * 2) }
                // The pack does not report how many cells it has. Fourteen is the Z-series
                // norm; the last two slots read zero unless the pack actually has them.
                val count = when {
                    millivolts[15] > 0 -> 16
                    millivolts[14] > 0 -> 15
                    else -> 14
                }
                current.copy(cells = millivolts.take(count).map { it / 1_000.0 })
            }

            else -> return null
        }

        packs[index] = updated
        return NinebotProtocolUpdate(
            param = frame.param,
            source = frame.source,
            bmsPacks = bmsPacks(),
        )
    }
}

/**
 * Duty estimate, in percent.
 *
 * A Z10 reports no duty cycle, so it has to be derived. At full duty a motor's speed is
 * proportional to bus voltage, which makes duty the ratio of the speed being ridden to the speed
 * the wheel could reach at the voltage it has right now.
 *
 * The anchors are measured, not generic: 14 series cells give [NinebotZ10FullChargeVoltage] at
 * full charge, and a Z10 with its firmware limits removed sustains
 * [NinebotZ10FullDutySpeedKmh] under load at that voltage. A stock wheel stops at 45 km/h and
 * lifts the pedals, but that is a speed limit rather than the wheel running out of power, so it
 * is the wrong anchor for full duty — riding that limit at full charge reads 82 % here.
 *
 * This tracks load only through voltage sag: a sagging bus shrinks the denominator and pushes
 * the result up. A heavily loaded wheel at low speed therefore still reads below its true duty,
 * which is why the value must be presented as an estimate and not as a reported duty cycle.
 */
fun ninebotPwmPercent(speedKmh: Double, voltage: Double): Double {
    if (speedKmh.isNaN() || voltage.isNaN() || voltage <= 0.0) return Double.NaN
    val fullDutySpeed = NinebotZ10FullDutySpeedKmh * voltage / NinebotZ10FullChargeVoltage
    if (fullDutySpeed <= 0.0) return Double.NaN
    return speedKmh / fullDutySpeed * 100.0
}

/** 14 series cells at 4.2 V. WheelLog reports the same 14 cells for this wheel. */
const val NinebotZ10FullChargeVoltage: Double = 58.8

/** Top speed under load of a Z10 whose limits have been patched out — the electrical ceiling. */
const val NinebotZ10FullDutySpeedKmh: Double = 55.0

/** BMS temperatures are single bytes with a fixed offset: 45 reads as 25 C. */
private const val NinebotBmsTemperatureOffset: Int = 20

/** `yyyyyyy mmmm ddddd` packed into 16 bits, year counted from 2000. */
internal fun ninebotPackedDate(value: Int): String? {
    if (value == 0) return null
    val year = (value shr 9) and 0x7F
    val month = (value shr 5) and 0x0F
    val day = value and 0x1F
    if (month !in 1..12 || day !in 1..31) return null
    return "${day.pad2()}.${month.pad2()}.${2000 + year}"
}

private fun Int.pad2(): String = if (this < 10) "0$this" else toString()

private fun ByteArray.asAscii(): String =
    takeWhile { byte -> byte != 0.toByte() }
        .map { byte -> (byte.toInt() and 0xFF).toChar() }
        .joinToString("")
        .trim()

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun ByteArray.u16Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.i16Le(offset: Int): Int = u16Le(offset).toShort().toInt()

private fun ByteArray.u32Le(offset: Int): Long =
    (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)

private fun ninebotErrorTextEn(code: Int): String = when (code) {
    1 -> "Motor hall sensor error"
    6 -> "Initial S/N"
    8 -> "Battery 1 input error"
    9 -> "Battery 2 input error"
    10 -> "Battery 1 communication fault"
    11 -> "Battery 2 communication fault"
    12 -> "Gyroscope initialisation failed"
    24 -> "Pack voltage above 65 V or below 40 V"
    25 -> "VGM voltage below 10 V"
    28 -> "Battery 1 power supply fault"
    29 -> "Battery 2 power supply fault"
    34 -> "Battery 1 cell voltage spread too wide"
    35 -> "Battery 2 cell voltage spread too wide"
    36 -> "Battery 1 input error 0x800"
    37 -> "Battery 2 input error 0x800"
    38 -> "Memory check failed"
    46 -> "Unknown error"
    else -> "Error"
}

private fun ninebotErrorTextRu(code: Int): String = when (code) {
    1 -> "Ошибка датчика Холла двигателя"
    6 -> "Не задан серийный номер"
    8 -> "Ошибка входа батареи 1"
    9 -> "Ошибка входа батареи 2"
    10 -> "Нет связи с батареей 1"
    11 -> "Нет связи с батареей 2"
    12 -> "Не удалась инициализация гироскопа"
    24 -> "Напряжение пакета выше 65 В или ниже 40 В"
    25 -> "Напряжение VGM ниже 10 В"
    28 -> "Ошибка питания батареи 1"
    29 -> "Ошибка питания батареи 2"
    34 -> "Слишком большой разброс ячеек батареи 1"
    35 -> "Слишком большой разброс ячеек батареи 2"
    36 -> "Ошибка входа батареи 1 0x800"
    37 -> "Ошибка входа батареи 2 0x800"
    38 -> "Не прошла проверка памяти"
    46 -> "Неизвестная ошибка"
    else -> "Ошибка"
}
