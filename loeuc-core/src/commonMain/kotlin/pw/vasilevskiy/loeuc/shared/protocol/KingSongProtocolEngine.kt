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
    val rideTimeMinutes: Double = Double.NaN,
    val rideMode: Double = Double.NaN,
    val lastCommand: Int = -1,
    val isLegacy: Boolean = false,
)

class KingSongProtocolEngine {
    private var buffer = byteArrayOf()
    private var state = KingSongTelemetry()

    /**
     * Wheels that never send `0xB9` would otherwise report no temperature at all, so the
     * `0xA9` sensor stands in until the first `0xB9` frame arrives and then steps aside.
     */
    private var sawRideStatsTemperature = false
    private var decodedModelName: String? = null

    private val bmsPages = KingSongBmsPages()

    fun reset() {
        buffer = byteArrayOf()
        state = KingSongTelemetry()
        sawRideStatsTemperature = false
        decodedModelName = null
        bmsPages.clear()
    }

    /**
     * The packs rebuilt from every BMS page seen so far. Empty until the wheel has answered at
     * least one page, and only complete after a full sweep.
     */
    fun bmsPacks(): List<KingSongBmsPack> {
        return bmsPages.packs()
    }

    /** Name/type response (`0xBB`) used by platform battery-catalogue lookup. */
    fun modelName(): String? = decodedModelName

    fun initialReadCommand(): ByteArray {
        return simpleCommand(command = KINGSONG_COMMAND_SERIAL_READ)
    }

    fun initialReadCommands(): List<ByteArray> {
        return listOf(
            simpleCommand(command = KINGSONG_COMMAND_SERIAL_READ),
            simpleCommand(command = KINGSONG_COMMAND_NAME_READ),
            simpleCommand(command = KINGSONG_COMMAND_VERSION_READ),
            simpleCommand(command = KINGSONG_COMMAND_INITIAL_READ),
            simpleCommand(command = KINGSONG_COMMAND_TRIP_READ),
        )
    }

    fun telemetryPollingCommand(): ByteArray {
        return simpleCommand(command = KINGSONG_COMMAND_HEARTBEAT)
    }

    fun consume(chunk: ByteArray): KingSongTelemetry? {
        buffer += chunk
        var updated = false

        while (buffer.size >= KINGSONG_FRAME_SIZE) {
            val headerIndex = buffer.indexOfKingSongHeader()
            if (headerIndex < 0) {
                buffer = buffer.takeLast(1).toByteArray()
                break
            }
            if (headerIndex > 0) {
                buffer = buffer.copyOfRange(headerIndex, buffer.size)
            }
            if (buffer.size < KINGSONG_FRAME_SIZE) break

            val frame = buffer.copyOfRange(0, KINGSONG_FRAME_SIZE)
            if (frame.hasKingSongFrameShape()) {
                processFrame(frame)
                updated = true
                buffer = buffer.copyOfRange(KINGSONG_FRAME_SIZE, buffer.size)
            } else {
                buffer = buffer.copyOfRange(1, buffer.size)
            }
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
        if (frame.isStandardKingSongFrame() && frame.command() == KINGSONG_COMMAND_NAME_RESPONSE) {
            decodedModelName = frame.copyOfRange(2, 16)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .decodeToString()
                .trim()
                .takeIf { it.isNotEmpty() }
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
                    rideTimeMinutes = frame.uint16Le(offset = 10) / 60.0,
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
                    rideTimeMinutes = frame.int16Le(offset = 6).toDouble(),
                    temperature = frame.int16Le(offset = 14) / 100.0,
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
    var buffer = chunk

    while (buffer.size >= KINGSONG_FRAME_SIZE) {
        val headerIndex = buffer.indexOfKingSongHeader()
        if (headerIndex < 0) {
            return KingSongReassemblyResult(frames = frames, remainingBuffer = buffer.takeLast(1).toByteArray())
        }
        if (headerIndex > 0) {
            buffer = buffer.copyOfRange(headerIndex, buffer.size)
        }
        if (buffer.size < KINGSONG_FRAME_SIZE) {
            break
        }

        val frame = buffer.copyOfRange(0, KINGSONG_FRAME_SIZE)
        if (frame.hasKingSongFrameShape()) {
            frames += KingSongFrame(
                command = frame.command(),
                isLegacy = frame.isLegacyKingSongFrame(),
                bytes = frame,
            )
            buffer = buffer.copyOfRange(KINGSONG_FRAME_SIZE, buffer.size)
        } else {
            buffer = buffer.copyOfRange(1, buffer.size)
        }
    }

    return KingSongReassemblyResult(frames = frames, remainingBuffer = buffer)
}

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
) {
    val chargePercent: Double?
        get() {
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

    fun clear() {
        pagesByCommand.clear()
    }

    /** Ignores anything that is not a BMS frame, so it can be fed the whole stream. */
    fun accept(frame: ByteArray) {
        if (frame.size < KINGSONG_FRAME_SIZE || !frame.hasKingSongFrameShape()) return
        val command = frame.command()
        if (command !in KINGSONG_BMS_COMMANDS) return
        val page = frame.u8(17)
        if (page > KINGSONG_BMS_PAGE_LAST) return
        pagesByCommand.getOrPut(command) { mutableMapOf() }[page] = frame
    }

    fun packs(): List<KingSongBmsPack> {
        return KINGSONG_BMS_COMMANDS.mapIndexedNotNull { index, command ->
            val pages = pagesByCommand[command] ?: return@mapIndexedNotNull null
            readKingSongBmsPack(index = index + 1, pages = pages).takeIf { it.hasData }
        }
    }
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
        isStandardKingSongFrame() -> this[18] == 0x5A.toByte() && this[19] == 0x5A.toByte()
        isLegacyKingSongFrame() -> u8(17) == KINGSONG_SIMPLE_FRAME_LENGTH
        else -> false
    }
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
private const val KINGSONG_COMMAND_SERIAL_READ = 0x98
private const val KINGSONG_COMMAND_NAME_READ = 0x63
private const val KINGSONG_COMMAND_NAME_RESPONSE = 0xBB
private const val KINGSONG_COMMAND_VERSION_READ = 0x9B
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

/** King Song BMS temperatures are deci-Kelvin: 2987 -> 25.7 C. */
private const val KINGSONG_BMS_KELVIN_OFFSET = 2730
private const val KINGSONG_BMS_TEMPERATURE_MIN_RAW = 2530 // -20 C
private const val KINGSONG_BMS_TEMPERATURE_MAX_RAW = 3930 // 120 C
private const val KINGSONG_BMS_CELL_MIN_MILLIVOLTS = 1_000
private const val KINGSONG_BMS_CELL_MAX_MILLIVOLTS = 5_000
