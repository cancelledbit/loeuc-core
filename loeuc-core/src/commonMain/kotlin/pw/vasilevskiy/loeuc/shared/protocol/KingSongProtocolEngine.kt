package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

data class KingSongTelemetry(
    val speedKmh: Double = Double.NaN,
    val voltage: Double = Double.NaN,
    val current: Double = Double.NaN,
    val phaseCurrent: Double = Double.NaN,
    val power: Double = Double.NaN,
    val apparentPower: Double = Double.NaN,
    /**
     * Live temperature, taken from the `0xB9` sensor because that is the one that actually
     * tracks the ride. See [secondaryTemperature] for why they are kept apart.
     */
    val temperature: Double = Double.NaN,
    /**
     * The `0xA9` temperature sensor. `0xA9` and `0xB9` are two *different* sensors and both
     * arrive at ~2 Hz, so writing them into a single field made the reading flip between them
     * several times a second — on an S22 capture the value alternated on 290 of 517 updates
     * with a 4 C swing. They now live in separate fields.
     */
    val secondaryTemperature: Double = Double.NaN,
    /** Output/duty from `0xF5`, percent. */
    val pwmPercent: Double = Double.NaN,
    /** Speed ceiling the wheel is currently enforcing, from `0xF6`, km/h. */
    val speedLimitKmh: Double = Double.NaN,
    /** Set while the wheel is holding that ceiling below its nominal value. */
    val speedLimitReduced: Boolean = false,
    /** Trip energy counter from `0xF6`, Wh. */
    val tripWattHours: Double = Double.NaN,
    /** Since-power-on energy counter from `0xF6`, Wh. */
    val sessionWattHours: Double = Double.NaN,
    val tripDistanceMeters: Double = Double.NaN,
    val totalDistanceKm: Double = Double.NaN,
    /**
     * How long this ride has been going, seconds.
     *
     * Seconds, not minutes, and the old name said otherwise: a KSN-S18Pro+ capture has the
     * `0xB9` counter stepping 582, 583, 584, 585 against four seconds of wall clock. The
     * number used to be published as [pw.vasilevskiy.loeuc.protocol.TelemetrySnapshot.shutdownTime],
     * so a King Song showed its ride time under the label "shutdown timer"; it is neither a
     * timer nor a countdown, and the app has its own ride clock for the rider.
     */
    val rideTimeSeconds: Double = Double.NaN,
    val rideMode: Double = Double.NaN,
    /**
     * Charge computed from the pack voltage, percent, or `NaN` while the pack shape is unknown.
     *
     * King Song sends no percent of its own anywhere in the protocol: on a wheel with a smart
     * BMS the number is rebuilt from the `0xF1`/`0xF2` pages, and a wheel without one - an S18
     * and its relatives - used to leave the charge blank for the whole ride. This is the answer
     * for that wheel, and it exists only once [KingSongProtocolEngine.setPackSeriesCells] has
     * been told how many cells the pack has. Measured cells always win over it.
     */
    val batteryPercent: Double = Double.NaN,
    val lastCommand: Int = -1,
    val isLegacy: Boolean = false,
    /** Cooling fan running, from `0xB9` byte 12. `null` until the wheel reports it. */
    val fanRunning: Boolean? = null,
    /** Charger connected and charging, from `0xB9` byte 13. `null` until the wheel reports it. */
    val charging: Boolean? = null,
)

/**
 * Who the wheel says it is.
 *
 * [name] is the whole `0xBB` answer, e.g. `KS-S22-0237`: the model with the firmware build
 * appended. [model] drops that last part and [firmwareVersion] reads it as hundredths, so
 * `0237` becomes `2.37`, which is how both WheelLog and the official app show it.
 */
data class KingSongIdentity(
    val name: String? = null,
    val model: String? = null,
    val firmwareVersion: String? = null,
    /** The 17-character serial from `0xB3`. */
    val serialNumber: String? = null,
)

class KingSongProtocolEngine {
    private var buffer = byteArrayOf()
    private var state = KingSongTelemetry()

    /**
     * Wheels that never send `0xB9` would otherwise report no temperature at all, so the
     * `0xA9` sensor stands in until the first `0xB9` frame arrives and then steps aside.
     */
    private var sawRideStatsTemperature = false
    private var identity = KingSongIdentity()

    private val bmsPages = KingSongBmsPages()
    private val settingsTracker = KingSongSettingsTracker()

    /**
     * Series-cell count of the pack, from the catalogue entry for the model or the rider's own
     * pick. `0` means nobody has said yet, and then the charge stays unknown rather than guessed
     * - see [CellChargeCurve.percentFromPackVoltage].
     */
    private var catalogueSeriesCells = 0

    /**
     * Tell the engine how many cells the pack has in series.
     *
     * The engine cannot work this out for itself: King Song states neither the count nor the
     * charge, and a wheel without a smart BMS reports no cells either. Zero or a nonsensical
     * count clears it back to "unknown".
     */
    fun setPackSeriesCells(seriesCells: Int) {
        catalogueSeriesCells = CellChargeCurve.normalizeSeriesCells(seriesCells)
    }

    /** Series-cell count the voltage-derived charge currently runs on, `0` while unknown. */
    fun packSeriesCells(): Int = catalogueSeriesCells

    fun reset() {
        buffer = byteArrayOf()
        state = KingSongTelemetry()
        sawRideStatsTemperature = false
        identity = KingSongIdentity()
        bmsPages.clear()
        settingsTracker.clear()
        // A new session is a new wheel: last pack's cell count says nothing about this one.
        catalogueSeriesCells = 0
    }

    /**
     * The packs rebuilt from every BMS page seen so far. Empty until the wheel has answered at
     * least one page, and only complete after a full sweep.
     */
    fun bmsPacks(): List<KingSongBmsPack> {
        return bmsPages.packs()
    }

    /** Name/type response (`0xBB`) used by platform battery-catalogue lookup. */
    fun modelName(): String? = identity.name

    /** Name, firmware version and serial number, as far as the wheel has answered them. */
    fun identity(): KingSongIdentity = identity

    /** The settings the wheel has reported so far. */
    fun settings(): KingSongSettingsState = settingsTracker.state

    fun initialReadCommand(): ByteArray {
        return simpleCommand(command = KINGSONG_COMMAND_ALARMS_READ)
    }

    /**
     * The connect burst: speed alarms (`0x98`), serial (`0x63`) and name (`0x9B`) - the three
     * reads WheelLog sends - then the official app's `0x5E` and `0x4B`, and last the reads that
     * report the remaining settings (see [KingSongSettingsReads]).
     */
    fun initialReadCommands(): List<ByteArray> {
        return listOf(
            simpleCommand(command = KINGSONG_COMMAND_ALARMS_READ),
            simpleCommand(command = KINGSONG_COMMAND_SERIAL_READ),
            simpleCommand(command = KINGSONG_COMMAND_NAME_READ),
            simpleCommand(command = KINGSONG_COMMAND_INITIAL_READ),
            simpleCommand(command = KINGSONG_COMMAND_TRIP_READ),
            KingSongSettingsReads.ledStrip(),
            KingSongSettingsReads.strobe(),
            KingSongSettingsReads.standbyDelay(),
            KingSongSettingsReads.chargeLimit(),
        )
    }

    /** The reads that bring a setting back after it was written; `null` asks for all of them. */
    fun settingsReadCommands(key: String? = null): List<ByteArray> = KingSongSettingsCatalog.readsFor(key)

    fun telemetryPollingCommand(): ByteArray {
        return simpleCommand(command = KINGSONG_COMMAND_HEARTBEAT)
    }

    fun consume(chunk: ByteArray): KingSongTelemetry? {
        var updated = false
        buffer = scanKingSongFrames(buffer + chunk) { frame ->
            processFrame(frame)
            updated = true
        }
        return if (updated) state else null
    }

    fun consumeFrame(frame: ByteArray): KingSongTelemetry? {
        if (!frame.hasKingSongFrameShape()) {
            return null
        }
        processFrame(frame)
        return state
    }

    private fun processFrame(frame: ByteArray) {
        bmsPages.accept(frame)
        settingsTracker.accept(frame)
        if (frame.isStandardKingSongFrame() && frame.command() == KINGSONG_COMMAND_NAME_RESPONSE) {
            frame.kingSongNameText()?.let { name ->
                identity = identity.copy(
                    name = name,
                    model = kingSongModelOf(name),
                    firmwareVersion = kingSongFirmwareVersionOf(name),
                )
            }
        }
        if (frame.isStandardKingSongFrame() && frame.command() == KINGSONG_COMMAND_SERIAL_RESPONSE) {
            frame.kingSongSerialText()?.let { serial -> identity = identity.copy(serialNumber = serial) }
        }
        state = when {
            frame.isStandardKingSongFrame() && frame.command() == KINGSONG_COMMAND_LIVE_TELEMETRY -> {
                val voltage = frame.int16Le(offset = 2) / 100.0
                val speed = (frame.int16Le(offset = 4) / 100.0).coerceIn(0.0, 200.0)
                val totalDistance = frame.kingSongDistance(offset = 6) / 1_000.0
                val current = (frame.int16Le(offset = 10) / 100.0)
                    .let { if (it > -0.12 && it < 0.05) 0.0 else it }
                val temperature = frame.uint16Le(offset = 12) / 100.0
                val rideMode = if (frame.u8(15) == 0xE0) frame.u8(14).toDouble() else state.rideMode

                state.copy(
                    speedKmh = speed,
                    voltage = voltage,
                    batteryPercent = CellChargeCurve.percentFromPackVoltage(
                        packVoltage = voltage,
                        seriesCells = catalogueSeriesCells,
                    ),
                    current = current,
                    phaseCurrent = current, // KingSong reports phase current
                    power = voltage * current,
                    apparentPower = voltage * current,
                    secondaryTemperature = temperature,
                    temperature = if (sawRideStatsTemperature) state.temperature else temperature,
                    totalDistanceKm = totalDistance,
                    rideMode = rideMode,
                    lastCommand = frame.command(),
                    isLegacy = false,
                )
            }

            frame.isLegacyKingSongFrame() && frame.command() == KINGSONG_COMMAND_LEGACY_MAIN -> {
                state.copy(
                    speedKmh = (frame.int16Le(offset = 4) / 100.0).coerceIn(0.0, 200.0),
                    voltage = frame.int16Le(offset = 2) / 100.0,
                    rideTimeSeconds = frame.uint16Le(offset = 10).toDouble(),
                    lastCommand = frame.command(),
                    isLegacy = true,
                )
            }

            frame.isLegacyKingSongFrame() && frame.command() == KINGSONG_COMMAND_LEGACY_TRIP -> {
                state.copy(
                    tripDistanceMeters = frame.kingSongDistance(offset = 2).toDouble(),
                    lastCommand = frame.command(),
                    isLegacy = true,
                )
            }

            frame.isStandardKingSongFrame() && frame.command() == KINGSONG_COMMAND_RIDE_STATS -> {
                sawRideStatsTemperature = true
                state.copy(
                    tripDistanceMeters = frame.kingSongDistance(offset = 2).toDouble(),
                    rideTimeSeconds = frame.int16Le(offset = 6).toDouble(),
                    temperature = frame.int16Le(offset = 14) / 100.0,
                    // WheelLog and the official app agree on both bytes; the app shows any
                    // non-zero fan byte as "cooling" and treats a charging byte of 1 as charging.
                    fanRunning = frame.u8(12) != 0,
                    charging = frame.u8(13) != 0,
                    lastCommand = frame.command(),
                    isLegacy = false,
                )
            }

            frame.isStandardKingSongFrame() && frame.command() == KINGSONG_COMMAND_OUTPUT -> {
                state.copy(
                    pwmPercent = frame.u8(15).toDouble(),
                    lastCommand = frame.command(),
                    isLegacy = false,
                )
            }

            frame.isStandardKingSongFrame() && frame.command() == KINGSONG_COMMAND_LIMITS -> {
                state.copy(
                    speedLimitKmh = frame.uint16Le(offset = 2) / 100.0,
                    speedLimitReduced = frame.u8(4) != 0,
                    sessionWattHours = frame.uint16Le(offset = 8).toDouble(),
                    tripWattHours = frame.uint16Le(offset = 10).toDouble(),
                    lastCommand = frame.command(),
                    isLegacy = false,
                )
            }

            else -> state.copy(
                lastCommand = frame.command(),
                isLegacy = frame.isLegacyKingSongFrame(),
            )
        }
    }

    private fun simpleCommand(command: Int): ByteArray {
        return ByteArray(KINGSONG_FRAME_SIZE).apply {
            this[0] = 0xAA.toByte()
            this[1] = 0x55
            this[16] = command.toByte()
            this[17] = KINGSONG_SIMPLE_FRAME_LENGTH.toByte()
            this[18] = 0x5A
            this[19] = 0x5A
        }
    }
}

@ExperimentalWriteApi
fun buildKingSongLightCommand(command: Int): ByteArray {
    return ByteArray(20).apply {
        this[0] = 0xAA.toByte()
        this[1] = 0x55
        this[2] = command.toByte()
        this[3] = 0x01
        this[16] = 0x73
        this[17] = 0x14
        this[18] = 0x5A
        this[19] = 0x5A
    }
}

fun reassembleKingSongFrames(chunk: ByteArray): KingSongReassemblyResult {
    val frames = mutableListOf<KingSongFrame>()
    val remaining = scanKingSongFrames(chunk) { frame ->
        frames += KingSongFrame(
            command = frame.command(),
            isLegacy = frame.isLegacyKingSongFrame(),
            bytes = frame,
        )
    }
    return KingSongReassemblyResult(frames = frames, remainingBuffer = remaining)
}

/**
 * Cuts every complete frame off the front of [input], hands each to [onFrame], and returns the
 * bytes still waiting for the rest of a frame.
 *
 * Almost every frame is 20 bytes. The one exception is the F-series extended BMS frame (see
 * [kingSongFrameLength]), which is a 20-byte header followed by its payload.
 */
private inline fun scanKingSongFrames(input: ByteArray, onFrame: (ByteArray) -> Unit): ByteArray {
    var buffer = input
    while (buffer.size >= KINGSONG_FRAME_SIZE) {
        val headerIndex = buffer.indexOfKingSongHeader()
        if (headerIndex < 0) {
            return buffer.takeLast(1).toByteArray()
        }
        if (headerIndex > 0) {
            buffer = buffer.copyOfRange(headerIndex, buffer.size)
        }
        if (buffer.size < KINGSONG_FRAME_SIZE) break

        if (!buffer.hasKingSongFrameShape()) {
            buffer = buffer.copyOfRange(1, buffer.size)
            continue
        }
        val length = buffer.kingSongFrameLength() ?: break
        onFrame(buffer.copyOfRange(0, length))
        buffer = buffer.copyOfRange(length, buffer.size)
    }
    return buffer
}

/**
 * The length of the frame at the start of this buffer, or `null` while more bytes are needed.
 *
 * The F-series extended BMS frame (`0xF1..0xF4`, page `0xD0..0xDF`) carries its payload length in
 * header byte 15 and needs a connection MTU large enough to arrive in one notification. When the
 * link truncates it to the 20-byte header, the next notification starts a fresh `AA 55` frame
 * right where the payload should have been; that header is then taken alone instead of swallowing
 * the live frames behind it.
 */
private fun ByteArray.kingSongFrameLength(): Int? {
    if (!isKingSongExtendedBmsHeader()) return KINGSONG_FRAME_SIZE
    if (size < KINGSONG_FRAME_SIZE + 2) return null
    if (this[KINGSONG_FRAME_SIZE] == 0xAA.toByte() && this[KINGSONG_FRAME_SIZE + 1] == 0x55.toByte()) {
        return KINGSONG_FRAME_SIZE
    }
    val total = KINGSONG_FRAME_SIZE + u8(15)
    return if (size >= total) total else null
}

private fun ByteArray.isKingSongExtendedBmsHeader(): Boolean =
    isStandardKingSongFrame() &&
        command() in KINGSONG_BMS_COMMANDS &&
        u8(17) in KINGSONG_BMS_PAGE_EXTENDED_FIRST..KINGSONG_BMS_PAGE_EXTENDED_LAST &&
        this[18] == 0x5A.toByte() && this[19] == 0x5A.toByte()

data class KingSongFrame(
    val command: Int,
    val isLegacy: Boolean,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        return other is KingSongFrame &&
            command == other.command &&
            isLegacy == other.isLegacy &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + isLegacy.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * One King Song battery pack, rebuilt from the paged `0xF1`/`0xF2` BMS frames.
 *
 * Validated against an S22 capture: the 30 decoded cells sum to 108.05 V against the 108.28 V
 * the same pack reports in its summary page, and the capacity pair yields the same charge level
 * the cell voltages imply.
 */
data class KingSongBmsPack(
    /** 1-based, matching the `0xF1`/`0xF2` frame the pack came from. */
    val index: Int,
    val voltage: Double? = null,
    val currentAmps: Double? = null,
    val remainingAh: Double? = null,
    val fullAh: Double? = null,
    val factoryAh: Double? = null,
    val chargeCycles: Int? = null,
    val maxCellVoltage: Double? = null,
    val cells: List<Double> = emptyList(),
    val temperatures: List<Double> = emptyList(),
    /**
     * State of charge as the BMS states it, percent. Only the F-series extended frame carries it;
     * the paged S-series frames leave it to [remainingAh] over [fullAh].
     */
    val reportedChargePercent: Double? = null,
    /**
     * Temperatures of the pack's environment sensors, C, from the F-series extended frame. The
     * name is WheelLog's; nothing beyond a plausible range confirms which part they measure.
     */
    val environmentTemperatures: List<Double> = emptyList(),
    /**
     * Relative humidity next to those sensors, percent, same frame and the same caveat: WheelLog
     * labels the field and itself doubts the label.
     */
    val environmentHumidity: List<Double> = emptyList(),
) {
    val chargePercent: Double?
        get() {
            reportedChargePercent?.let { return it.coerceIn(0.0, 100.0) }
            val full = fullAh?.takeIf { it > 0.0 } ?: return null
            val remaining = remainingAh ?: return null
            return (remaining / full * 100.0).coerceIn(0.0, 100.0)
        }

    val hasData: Boolean
        get() = voltage != null || cells.isNotEmpty()
}

/**
 * Rebuilds the battery packs from a chronological run of frames, newest page winning.
 *
 * The BMS answers one page per frame, keyed by byte 17, so a pack is only complete once a full
 * sweep of pages has arrived. Packs that never reported anything are dropped, which is how a
 * 2-pack wheel ends up with two entries even though it also answers `0xF3`/`0xF4` with zeroes.
 */
fun decodeKingSongBmsPacks(frames: List<ByteArray>): List<KingSongBmsPack> {
    val pages = KingSongBmsPages()
    frames.forEach(pages::accept)
    return pages.packs()
}

/**
 * Accumulates BMS pages as they stream past. A pack only reads correctly once a full sweep has
 * arrived, so the pages have to outlive the frame that carried them.
 */
internal class KingSongBmsPages {
    private val pagesByCommand = mutableMapOf<Int, MutableMap<Int, ByteArray>>()

    /** The newest F-series extended frame per pack, which replaces the whole page sweep. */
    private val extendedByCommand = mutableMapOf<Int, ByteArray>()

    fun clear() {
        pagesByCommand.clear()
        extendedByCommand.clear()
    }

    /** Ignores anything that is not a BMS frame, so it can be fed the whole stream. */
    fun accept(frame: ByteArray) {
        if (frame.size < KINGSONG_FRAME_SIZE || !frame.hasKingSongFrameShape()) return
        val command = frame.command()
        if (command !in KINGSONG_BMS_COMMANDS) return
        val page = frame.u8(17)
        if (page == KINGSONG_BMS_PAGE_EXTENDED_FIRST) {
            // A header whose payload never arrived carries nothing to keep.
            if (frame.size > KINGSONG_FRAME_SIZE) extendedByCommand[command] = frame
            return
        }
        if (page > KINGSONG_BMS_PAGE_LAST) return
        pagesByCommand.getOrPut(command) { mutableMapOf() }[page] = frame
    }

    fun packs(): List<KingSongBmsPack> {
        return KINGSONG_BMS_COMMANDS.mapIndexedNotNull { index, command ->
            val extended = extendedByCommand[command]?.let { readKingSongExtendedBmsPack(index + 1, it) }
            if (extended != null) return@mapIndexedNotNull extended.takeIf { it.hasData }
            val pages = pagesByCommand[command] ?: return@mapIndexedNotNull null
            readKingSongBmsPack(index = index + 1, pages = pages).takeIf { it.hasData }
        }
    }
}

/**
 * The F-series (F18P, F22P) extended BMS frame, page `0xD0`: one frame instead of a page sweep.
 *
 * Layout after the 20-byte header, as WheelLog reads it; the offsets below are absolute:
 *
 * - `21`: cell count N, then N cells as `u16le` mV from byte 22.
 * - one byte with the temperature count T, then T temperatures, `u16le` deci-Kelvin.
 * - then, from the byte after the temperatures: current `i16le / 100` A, voltage `u16le / 100`
 *   V, state of charge `u16le / 10` %, charge cycles `u16le` at +9, factory capacity `u16le /
 *   100` Ah at +11, two environment temperatures `i16le / 10` C at +14 and +16, and two
 *   humidity readings `u16le / 10` % at +18 and +20.
 *
 * Validated only by closure on WheelLog's F22P and F18P test captures: the cells sum to within
 * 0.05 V of the voltage the same frame reports. No capture of our own exists.
 */
private fun readKingSongExtendedBmsPack(index: Int, frame: ByteArray): KingSongBmsPack? {
    val cellCountOffset = KINGSONG_FRAME_SIZE + 1
    if (frame.size <= cellCountOffset) return null
    val cellCount = frame.u8(cellCountOffset)
    val temperatureCountOffset = cellCountOffset + 1 + cellCount * 2
    if (cellCount == 0 || frame.size <= temperatureCountOffset) return null
    val temperatureCount = frame.u8(temperatureCountOffset)
    val tail = temperatureCountOffset + 1 + temperatureCount * 2
    if (frame.size < tail + KINGSONG_BMS_EXTENDED_TAIL_SIZE) return null

    val cells = mutableListOf<Double>()
    for (cell in 0 until cellCount) {
        val millivolts = frame.uint16Le(cellCountOffset + 1 + cell * 2)
        if (millivolts !in KINGSONG_BMS_CELL_MIN_MILLIVOLTS..KINGSONG_BMS_CELL_MAX_MILLIVOLTS) break
        cells += millivolts / 1_000.0
    }
    val temperatures = mutableListOf<Double>()
    for (sensor in 0 until temperatureCount) {
        temperatures.addKingSongBmsTemperature(frame.uint16Le(temperatureCountOffset + 1 + sensor * 2))
    }
    val chargePercent = frame.uint16Le(tail + 4) / 10.0
    val factoryAh = frame.uint16Le(tail + 11) / 100.0
    val environmentTemperatures = mutableListOf<Double>()
    val environmentHumidity = mutableListOf<Double>()
    for (sensor in 0..1) {
        val rawTemperature = frame.int16Le(tail + 14 + sensor * 2)
        val rawHumidity = frame.uint16Le(tail + 18 + sensor * 2)
        // An absent sensor reads zero on both, which a real one never does.
        if (rawTemperature == 0 && rawHumidity == 0) continue
        environmentTemperatures += rawTemperature / 10.0
        environmentHumidity += rawHumidity / 10.0
    }

    return KingSongBmsPack(
        index = index,
        voltage = frame.uint16Le(tail + 2) / 100.0,
        currentAmps = frame.int16Le(tail) / 100.0,
        remainingAh = factoryAh * chargePercent / 100.0,
        factoryAh = factoryAh,
        chargeCycles = frame.uint16Le(tail + 9),
        cells = cells,
        temperatures = temperatures,
        reportedChargePercent = chargePercent,
        environmentTemperatures = environmentTemperatures,
        environmentHumidity = environmentHumidity,
    )
}

private fun readKingSongBmsPack(index: Int, pages: Map<Int, ByteArray>): KingSongBmsPack {
    val summary = pages[KINGSONG_BMS_PAGE_SUMMARY]
    val last = pages[KINGSONG_BMS_PAGE_LAST]

    val cells = mutableListOf<Double>()
    // Pages 2..5 hold seven cells each, page 6 opens with the last two.
    val cellSlots = (2..5).flatMap { page -> (2..14 step 2).map { page to it } } +
        listOf(KINGSONG_BMS_PAGE_LAST to 2, KINGSONG_BMS_PAGE_LAST to 4)
    for ((page, offset) in cellSlots) {
        val millivolts = pages[page]?.uint16Le(offset) ?: break
        if (millivolts !in KINGSONG_BMS_CELL_MIN_MILLIVOLTS..KINGSONG_BMS_CELL_MAX_MILLIVOLTS) break
        cells += millivolts / 1_000.0
    }

    val temperatures = mutableListOf<Double>()
    pages[KINGSONG_BMS_PAGE_TEMPERATURES]?.let { page ->
        (2..14 step 2).forEach { offset -> temperatures.addKingSongBmsTemperature(page.uint16Le(offset)) }
    }
    last?.let { temperatures.addKingSongBmsTemperature(it.uint16Le(offset = 10)) }

    return KingSongBmsPack(
        index = index,
        voltage = summary?.uint16Le(offset = 2)?.div(100.0),
        currentAmps = summary?.int16Le(offset = 4)?.div(100.0),
        remainingAh = summary?.uint16Le(offset = 6)?.div(100.0),
        fullAh = summary?.uint16Le(offset = 8)?.div(100.0),
        chargeCycles = summary?.uint16Le(offset = 10),
        factoryAh = summary?.uint16Le(offset = 12)?.div(100.0),
        maxCellVoltage = summary?.uint16Le(offset = 14)
            ?.takeIf { it in KINGSONG_BMS_CELL_MIN_MILLIVOLTS..KINGSONG_BMS_CELL_MAX_MILLIVOLTS }
            ?.div(1_000.0),
        cells = cells,
        temperatures = temperatures,
    )
}

private fun MutableList<Double>.addKingSongBmsTemperature(raw: Int) {
    if (raw in KINGSONG_BMS_TEMPERATURE_MIN_RAW..KINGSONG_BMS_TEMPERATURE_MAX_RAW) {
        this += (raw - KINGSONG_BMS_KELVIN_OFFSET) / 10.0
    }
}

data class KingSongReassemblyResult(
    val frames: List<KingSongFrame>,
    val remainingBuffer: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        return other is KingSongReassemblyResult &&
            frames == other.frames &&
            remainingBuffer.contentEquals(other.remainingBuffer)
    }

    override fun hashCode(): Int {
        var result = frames.hashCode()
        result = 31 * result + remainingBuffer.contentHashCode()
        return result
    }
}

private fun ByteArray.hasKingSongFrameShape(): Boolean {
    return when {
        // Byte 17 is the frame length on live frames but the page index on BMS frames
        // (`0xF1..0xF4` carry `0x00..0x06`), so only the `AA 55 .. 5A 5A` envelope can be
        // required here. Demanding 0x14 dropped every BMS frame before it reached a decoder.
        isStandardKingSongFrame() ->
            (this[18] == 0x5A.toByte() && this[19] == 0x5A.toByte()) ||
                isKingSongNameFrame() || isKingSongSerialFrame()
        isLegacyKingSongFrame() -> u8(17) == KINGSONG_SIMPLE_FRAME_LENGTH
        else -> false
    }
}

/**
 * The `0xBB` name answer ends in a checksum instead of `5A 5A`: `0xFFFF` minus the sum of the
 * name bytes, `u16le` in bytes 18..19. The S22 capture has `KS-S22-0237` closing on `84 FD`.
 */
private fun ByteArray.isKingSongNameFrame(): Boolean {
    if (command() != KINGSONG_COMMAND_NAME_RESPONSE) return false
    var sum = 0
    for (offset in 2 until 16) {
        val byte = u8(offset)
        if (byte == 0) break
        sum += byte
    }
    return ((0xFFFF - sum) and 0xFFFF) == uint16Le(offset = 18)
}

/**
 * The `0xB3` serial answer spills its last three characters into bytes 17..19, where every other
 * frame has its length and trailer. Only printable ASCII is accepted, so a stray `AA 55` inside
 * binary data does not pass for one.
 */
private fun ByteArray.isKingSongSerialFrame(): Boolean {
    if (command() != KINGSONG_COMMAND_SERIAL_RESPONSE) return false
    return KINGSONG_SERIAL_OFFSETS.all { offset -> u8(offset) in 0x20..0x7E }
}

private fun ByteArray.kingSongNameText(): String? {
    return copyOfRange(2, 16)
        .takeWhile { it != 0.toByte() }
        .toByteArray()
        .decodeToString()
        .trim()
        .takeIf { it.isNotEmpty() }
}

private fun ByteArray.kingSongSerialText(): String? {
    if (!isKingSongSerialFrame()) return null
    return KINGSONG_SERIAL_OFFSETS.map { offset -> this[offset] }
        .toByteArray()
        .decodeToString()
        .trim()
        .takeIf { it.isNotEmpty() }
}

/** `KS-S22-0237` -> `KS-S22`. A name without a dash is a model with no firmware part. */
internal fun kingSongModelOf(name: String): String {
    val parts = name.split('-')
    return if (parts.size < 2) name else parts.dropLast(1).joinToString("-")
}

/** `KS-S22-0237` -> `2.37`, or `null` when the last part is not a number. */
internal fun kingSongFirmwareVersionOf(name: String): String? {
    val parts = name.split('-')
    if (parts.size < 2) return null
    val build = parts.last().toIntOrNull() ?: return null
    return "${build / 100}.${(build % 100).toString().padStart(2, '0')}"
}

private fun ByteArray.isStandardKingSongFrame(): Boolean {
    return size >= KINGSONG_FRAME_SIZE && this[0] == 0xAA.toByte() && this[1] == 0x55.toByte()
}

private fun ByteArray.isLegacyKingSongFrame(): Boolean {
    return size >= KINGSONG_FRAME_SIZE && this[0] == 0xF1.toByte() && this[1] == 0xEF.toByte()
}

private fun ByteArray.indexOfKingSongHeader(): Int {
    for (index in 0 until size - 1) {
        val first = this[index]
        val second = this[index + 1]
        if ((first == 0xAA.toByte() && second == 0x55.toByte()) ||
            (first == 0xF1.toByte() && second == 0xEF.toByte())
        ) {
            return index
        }
    }
    return -1
}

private fun ByteArray.command(): Int {
    return u8(16)
}

private fun ByteArray.kingSongDistance(offset: Int): Long {
    return ((this[offset].toLong() and 0xFFL) shl 16) or
        ((this[offset + 1].toLong() and 0xFFL) shl 24) or
        (this[offset + 2].toLong() and 0xFFL) or
        ((this[offset + 3].toLong() and 0xFFL) shl 8)
}

private fun ByteArray.int16Le(offset: Int): Int {
    return ((this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() shl 8)).toShort().toInt()
}

private fun ByteArray.uint16Le(offset: Int): Int {
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.u8(offset: Int): Int {
    return this[offset].toInt() and 0xFF
}

private const val KINGSONG_FRAME_SIZE = 20
private const val KINGSONG_SIMPLE_FRAME_LENGTH = 0x14
private const val KINGSONG_COMMAND_HEARTBEAT = 0x00
private const val KINGSONG_COMMAND_ALARMS_READ = 0x98
private const val KINGSONG_COMMAND_SERIAL_READ = 0x63
private const val KINGSONG_COMMAND_SERIAL_RESPONSE = 0xB3
private const val KINGSONG_COMMAND_NAME_READ = 0x9B
private const val KINGSONG_COMMAND_NAME_RESPONSE = 0xBB

/** Where the 17 serial characters sit in a `0xB3` frame. */
private val KINGSONG_SERIAL_OFFSETS = (2..15) + (17..19)
private const val KINGSONG_COMMAND_INITIAL_READ = 0x5E
private const val KINGSONG_COMMAND_TRIP_READ = 0x4B
private const val KINGSONG_COMMAND_LIVE_TELEMETRY = 0xA9
private const val KINGSONG_COMMAND_RIDE_STATS = 0xB9
private const val KINGSONG_COMMAND_OUTPUT = 0xF5
private const val KINGSONG_COMMAND_LIMITS = 0xF6
private const val KINGSONG_COMMAND_LEGACY_MAIN = 0xC2
private const val KINGSONG_COMMAND_LEGACY_TRIP = 0xC5

/** `0xF1`/`0xF2` are the two battery packs; `0xF3`/`0xF4` exist but stay empty on a 2-pack wheel. */
private val KINGSONG_BMS_COMMANDS = listOf(0xF1, 0xF2, 0xF3, 0xF4)
private const val KINGSONG_BMS_PAGE_SUMMARY = 0x00
private const val KINGSONG_BMS_PAGE_TEMPERATURES = 0x01
private const val KINGSONG_BMS_PAGE_LAST = 0x06

/** Page range of the F-series extended frames; `0xD0` carries the pack, `0xD1` is not decoded. */
private const val KINGSONG_BMS_PAGE_EXTENDED_FIRST = 0xD0
private const val KINGSONG_BMS_PAGE_EXTENDED_LAST = 0xDF

/** Bytes the extended frame needs after its temperatures: up to the second humidity reading. */
private const val KINGSONG_BMS_EXTENDED_TAIL_SIZE = 22

/** King Song BMS temperatures are deci-Kelvin: 2987 -> 25.7 C. */
private const val KINGSONG_BMS_KELVIN_OFFSET = 2730
private const val KINGSONG_BMS_TEMPERATURE_MIN_RAW = 2530 // -20 C
private const val KINGSONG_BMS_TEMPERATURE_MAX_RAW = 3930 // 120 C
private const val KINGSONG_BMS_CELL_MIN_MILLIVOLTS = 1_000
private const val KINGSONG_BMS_CELL_MAX_MILLIVOLTS = 5_000
