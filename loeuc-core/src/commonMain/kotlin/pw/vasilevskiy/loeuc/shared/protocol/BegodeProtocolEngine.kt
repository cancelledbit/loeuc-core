package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.math.abs
import kotlin.math.roundToInt

data class BegodeTelemetry(
    val speedKmh: Double = Double.NaN,
    val pwmPercent: Double = Double.NaN,
    val voltage: Double = Double.NaN,
    val current: Double = Double.NaN,
    val phaseCurrent: Double = Double.NaN,
    val power: Double = Double.NaN,
    val apparentPower: Double = Double.NaN,
    val temperature: Double = Double.NaN,
    val motorTemperature: Double = Double.NaN,
    val batteryPercent: Double = Double.NaN,
    val tripDistanceMeters: Double = Double.NaN,
    val totalDistanceKm: Double = Double.NaN,
    val rideMode: Double = Double.NaN,
    /**
     * Tilt-back speed the wheel is set to, km/h. `NaN` when the wheel reports it unset.
     *
     * The frame carries it at `@10` of `type=4`, and the field has been sitting there
     * unread: the engine kept only the odometer and the alarm flags from that frame. A
     * settings screen that cannot read a setting back is a remote control, not settings —
     * the rider has no way to tell what the wheel is actually set to.
     *
     * A hundred and above means "no tilt-back": that is how the wheel says off, and it is
     * the same reading WheelLog's `GotwayAdapter` takes.
     */
    val tiltbackSpeedKmh: Double = Double.NaN,
    /**
     * Auto power-off timeout, seconds. Idles at 7200 and counts down while the wheel
     * stands still, which is what once made it look like a slowly drifting odometer.
     */
    val autoPowerOffSeconds: Double = Double.NaN,
    /** The wheel's own unit setting: `true` when it is set to miles. */
    val usesMiles: Boolean? = null,
    /** Light mode as the wheel reports it at `@15` of `type=4`. */
    val lightMode: Int? = null,

    /**
     * Power reserve the wheel starts warning at, in percent, as the wheel reports it.
     *
     * The wire carries the load level; this is its complement, the same way round the setting
     * is written and shown. `NaN` until `type=1` has been seen.
     */
    val powerMarginPercent: Double = Double.NaN,
    val cellVoltages: List<Double> = emptyList(),
    /** Raw alarm bitmask reported by the wheel itself, see [BegodeWheelAlert]. */
    val wheelAlertFlags: Int = 0,
    val lastType: Int = -1,
    val lastPage: Int = -1,
    val isLegacy: Boolean = false,
    /** The firmware's answer to `V`, or `null` until the wheel has given one. */
    val firmware: BegodeFirmware? = null,
    /**
     * Speed-alarm mode as `type=4 @6` reports it, `(x >> 10) & 3`: `0..2` are the stock alarm
     * choices, and `3` is the PWM tilt-back mode of Freestyl3r firmware. `null` until seen.
     *
     * WheelLog (`alarmMode`) and EUC World (`speedAlerts >= 3` switches its CF "dynamic
     * tiltback" on) read the same two bits the same way.
     */
    val speedAlarmMode: Int? = null,
    /** SmirnoV firmware only: pedal mode `0..3` from `type=1 @6 & 3`. See [BegodeSmirnovSettings]. */
    val smirnovPedalMode: Int? = null,
    /** SmirnoV firmware only: the trick byte at `type=0 @16`. */
    val smirnovTrick: Int? = null,
    /** SmirnoV firmware only: the settings frame `type=0xFF`, or `null` until one arrives. */
    val smirnovSettings: BegodeSmirnovSettings? = null,
)

/**
 * Which firmware answered the version query `V`.
 *
 * The reply is the prefix and a version number, `GW2002001` on a stock wheel. The prefix is
 * what matters: two custom firmwares reuse fields of the stock frames for other things, and a
 * decoder that does not know which one it is talking to reads those fields wrong. Prefixes as
 * WheelLog's `GotwayAdapter` knows them; EUC World's handshake (`^(GW|JL|JN|CF)\d{7}`) agrees on
 * `GW`, `JN` and `CF` and adds `JL`, which it treats as stock.
 */
enum class BegodeFirmwareFamily(val prefixes: List<String>, val displayName: String) {
    /** Stock Begode firmware. */
    Begode(listOf("GW", "JL"), "Begode"),

    /** ExtremeBull's firmware. Decodes exactly as stock. */
    ExtremeBull(listOf("JN"), "ExtremeBull"),

    /**
     * Freestyl3r custom firmware. Stock layout, except that `type=0 @14` carries the motor PWM
     * in tenths of a percent - WheelLog reads it as hardware PWM, EUC World as the power margin
     * `100 - |@14| / 10`, which is the same number seen from the other end.
     */
    Freestyl3r(listOf("CF"), "Freestyl3r"),

    /**
     * SmirnoV (Alexovik) custom firmware. Reuses several stock fields; see
     * [BegodeProtocolEngine] for which, and [BegodeSmirnovSettings] for its settings frame.
     */
    SmirnoV(listOf("BF"), "SmirnoV"),
    ;

    /** Both custom firmwares report the motor PWM at `type=0 @14`. */
    val reportsHardwarePwm: Boolean
        get() = this == Freestyl3r || this == SmirnoV
}

/** A decoded answer to `V`: the family, the prefix exactly as sent, and the version after it. */
data class BegodeFirmware(
    val family: BegodeFirmwareFamily,
    val prefix: String,
    val version: String,
) {
    /** What the wheel sent, e.g. `GW2002001`. */
    val label: String get() = prefix + version
}

/**
 * The SmirnoV firmware's settings frame, `type=0xFF`, byte for byte as WheelLog reads it.
 *
 * Read-only in LoEUC. Every field is a byte on the wire; the values are the firmware's own
 * units, which WheelLog's screen presents as 0..100 sliders without saying more. These tune the
 * balance and current controllers, and nothing here writes them back.
 */
data class BegodeSmirnovSettings(
    /** `@2 & 1`. */
    val extremeMode: Boolean,
    /** `@3`. */
    val brakingCurrent: Int,
    /** `@4 & 1`. */
    val rotationControl: Boolean,
    /** `@5 + 260`, degrees. */
    val rotationAngleDegrees: Int,
    /** `@6 & 1`. */
    val advancedSettings: Boolean,
    /** `@7`, `@8`, `@9`: the balance PID. */
    val proportionalFactor: Int,
    val integralFactor: Int,
    val differentialFactor: Int,
    /** `@10`, `@11`. */
    val dynamicCompensation: Int,
    val dynamicCompensationFilter: Int,
    /** `@12`. */
    val accelerationCompensation: Int,
    /** `@14`, `@15`: Q-axis current loop P and I. */
    val currentQProportional: Int,
    val currentQIntegral: Int,
    /** `@16`, `@17`: D-axis current loop P and I. */
    val currentDProportional: Int,
    val currentDIntegral: Int,
)

/**
 * The two text replies a Begode gives at connect, outside the binary frame stream: `NAME:` for
 * `N`, and the firmware prefix and version for `V`.
 */
object BegodeIdentityReplies {

    /** The model name if [chunk] starts with a `NAME:` reply. */
    fun modelName(chunk: ByteArray): String? {
        val prefix = NamePrefix
        if (chunk.size < prefix.size || !prefix.indices.all { chunk[it] == prefix[it] }) return null
        return chunk.copyOfRange(prefix.size, chunk.size)
            .decodeToString()
            .trim { it == '\u0000' || it == '\r' || it == '\n' || it == ' ' }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * The firmware if [chunk] starts with a reply to `V`, leading whitespace allowed - the way
     * WheelLog takes it, from the start of one notification.
     */
    fun firmware(chunk: ByteArray): BegodeFirmware? {
        var start = 0
        while (start < chunk.size && (chunk[start].toInt() and 0xFF) <= ' '.code) start++
        val match = matchAt(chunk, start) ?: return null
        return decode(chunk, start, match)
    }

    /**
     * Where a reply to `V` sits inside [buffer], for a reassembler that sees notifications glued
     * together. Binary frames can in principle hold the same bytes; the caller is expected to
     * rule out matches inside a complete frame.
     */
    fun locateFirmware(buffer: ByteArray, fromIndex: Int = 0): IntRange? {
        for (start in fromIndex.coerceAtLeast(0) until buffer.size - 1) {
            val end = matchAt(buffer, start) ?: continue
            return start until end
        }
        return null
    }

    /** Decodes a range [locateFirmware] returned. */
    fun firmware(buffer: ByteArray, range: IntRange): BegodeFirmware? {
        val end = matchAt(buffer, range.first) ?: return null
        return decode(buffer, range.first, end)
    }

    /** Exclusive end of a firmware reply starting at [start], or `null` when there is none. */
    private fun matchAt(bytes: ByteArray, start: Int): Int? {
        if (start + PrefixLength + MinVersionLength > bytes.size) return null
        val prefix = bytes.copyOfRange(start, start + PrefixLength).decodeToString()
        if (familyOf(prefix) == null) return null
        // The version is digits: `GW2002001`, and EUC World's pattern wants seven of them.
        // Letters cannot be allowed to extend it - the frame bytes that follow a reply on the
        // wire are `55 AA` and `5A`, which are `U` and `Z`.
        var end = start + PrefixLength
        while (end < bytes.size && end - start - PrefixLength < MaxVersionLength && bytes[end].isVersionChar()) end++
        if (end < bytes.size && bytes[end].isVersionChar()) return null
        if ((bytes[start + PrefixLength].toInt() and 0xFF) !in '0'.code..'9'.code) return null
        return if (end - start - PrefixLength >= MinVersionLength) end else null
    }

    private fun decode(bytes: ByteArray, start: Int, end: Int): BegodeFirmware? {
        val prefix = bytes.copyOfRange(start, start + PrefixLength).decodeToString()
        val family = familyOf(prefix) ?: return null
        return BegodeFirmware(
            family = family,
            prefix = prefix,
            version = bytes.copyOfRange(start + PrefixLength, end).decodeToString(),
        )
    }

    private fun familyOf(prefix: String): BegodeFirmwareFamily? =
        BegodeFirmwareFamily.entries.firstOrNull { prefix in it.prefixes }

    private fun Byte.isVersionChar(): Boolean {
        val c = toInt() and 0xFF
        return c in '0'.code..'9'.code || c == '.'.code
    }

    private val NamePrefix = "NAME:".encodeToByteArray()
    private const val PrefixLength = 2
    private const val MinVersionLength = 3
    private const val MaxVersionLength = 16
}

/**
 * The connect-time identity queries, `N` (model name) and `V` (firmware version), asked until
 * answered, a bounded number of times each. Clock passed in, so both phones and a test drive the
 * same logic.
 *
 * Both are read-only one-byte queries: WheelLog asks `V` then `N`, EUC World asks `N` then `V`
 * and refuses to write settings before both have answered, and the official Begode app asks `N`.
 * WheelLog retries every 40 ms for about fifty frames, because a wheel that is busy streaming can
 * miss a single byte. Here the two alternate every [RETRY_INTERVAL_MILLIS], which asks each one
 * every 200 ms, up to [MAX_ATTEMPTS] times; a stock wheel that never answers `V` costs twenty
 * ignored bytes.
 *
 * Nothing but `N` and `V` ever comes out of this. Settings writes do not go through it.
 */
class BegodeIdentityRequests {
    var modelName: String? = null
        private set
    var firmware: BegodeFirmware? = null
        private set
    var nameAttempts: Int = 0
        private set
    var versionAttempts: Int = 0
        private set

    private var lastRequestAtMillis = 0L
    private var hasSentRequest = false
    private var lastSent: Byte? = null

    /** True once there is nothing more to ask: both answered, or both out of attempts. */
    val isFinished: Boolean
        get() = !nameDue && !versionDue

    private val nameDue get() = modelName == null && nameAttempts < MAX_ATTEMPTS
    private val versionDue get() = firmware == null && versionAttempts < MAX_ATTEMPTS

    /** One notification as it arrived. */
    fun onNotify(chunk: ByteArray) {
        // Same rule as [BegodeProtocolEngine.consume]: a `NAME:` reply counts only at the start of a
        // notify. Accepting it mid-notify here stopped the queries while the engine, which reads it
        // only at the start, never recorded the name - the model hint stayed missing all session.
        if (modelName == null) {
            BegodeIdentityReplies.modelName(chunk)?.let { modelName = it }
        }
        if (firmware == null) {
            BegodeIdentityReplies.firmware(chunk)?.let { firmware = it }
        }
    }

    /** The query to write now, or `null` when nothing is due. Returns a fresh array. */
    fun nextRequest(nowMillis: Long): ByteArray? {
        if (isFinished) return null
        if (hasSentRequest && nowMillis - lastRequestAtMillis < RETRY_INTERVAL_MILLIS) return null
        // Alternate, so a wheel that never answers one query does not hold up the other.
        val askVersion = versionDue && (!nameDue || lastSent == NameQuery)
        val query = if (askVersion) VersionQuery else NameQuery
        if (askVersion) versionAttempts++ else nameAttempts++
        lastSent = query
        hasSentRequest = true
        lastRequestAtMillis = nowMillis
        return byteArrayOf(query)
    }

    /** The last query could not be written: it does not count, and is retried after the interval. */
    fun onWriteFailed(nowMillis: Long) {
        when (lastSent) {
            NameQuery -> if (nameAttempts > 0) nameAttempts--
            VersionQuery -> if (versionAttempts > 0) versionAttempts--
        }
        lastSent = if (lastSent == NameQuery) VersionQuery else NameQuery
        lastRequestAtMillis = nowMillis
    }

    fun reset() {
        modelName = null
        firmware = null
        nameAttempts = 0
        versionAttempts = 0
        lastRequestAtMillis = 0L
        hasSentRequest = false
        lastSent = null
    }

    companion object {
        /** `N`, the model-name query. */
        const val NameQuery: Byte = 0x4E

        /** `V`, the firmware-version query. WheelLog and EUC World both send it at connect. */
        const val VersionQuery: Byte = 0x56
        const val RETRY_INTERVAL_MILLIS: Long = 100L
        const val MAX_ATTEMPTS: Int = 20
    }
}

/**
 * Bits of the wheel's own alarm byte (`type=4,page=24 @14`), matching WheelLog's
 * `GotwayAdapter` decode. These are the alarms the wheel raises for itself, independent of
 * any threshold the app evaluates - [HighPower] in particular is the stock Begode alarm that
 * fires at [BegodeProtocolEngine.WHEEL_HIGH_POWER_ALARM_PWM_FRACTION] of motor PWM.
 */
object BegodeWheelAlert {
    const val HighPower: Int = 1 shl 0
    const val Speed2: Int = 1 shl 1
    const val Speed1: Int = 1 shl 2
    const val LowVoltage: Int = 1 shl 3
    const val OverVoltage: Int = 1 shl 4
    const val OverTemperature: Int = 1 shl 5
    const val HallSensorError: Int = 1 shl 6
    const val TransportMode: Int = 1 shl 7
}

class BegodeProtocolEngine {
    private var buffer = byteArrayOf()
    private val batteryCells = mutableMapOf<Int, Double>()
    private var legacyMaxVoltage: Double = 100.8
    private var legacyVoltageScale: Double = legacyMaxVoltage / LEGACY_BASE_MAX_VOLTAGE
    private var legacyPwmFreeSpinSpeedKmh: Double = legacyDefaultFreeSpinSpeedKmh(legacyMaxVoltage)
    private var legacyPwmFreeSpinVoltage: Double = legacyMaxVoltage
    private var legacyPwmPowerFactor: Double = LEGACY_DEFAULT_POWER_FACTOR
    private var modernProtocolSeen = false

    /**
     * Has the wheel's own PWM field ever carried anything.
     *
     * Not every modern Begode fills `type=7`. An EX30 sends the frame 1,666 times in a
     * capture and leaves everything past the battery current at zero - no PWM, no motor
     * temperature - while a Commander reports 33..47 C and a moving PWM in the same fields.
     * Published as-is, that zero is worse than nothing: PWM is the overload warning, and a
     * gauge pinned to 0 % reads as "no load" right up to the cutout.
     *
     * The rule is "a zero from a field that has never reported is not a reading, and a zero
     * from one that has is": a parked wheel legitimately reports 0 % PWM, so the first
     * non-zero is what proves the field exists, and after that its zeroes are real. EUC World
     * guards the same two fields the same way - `if (stored != null || value != 0)` in the
     * `type=7` branch of its Begode decoder - which is a second reading of the protocol
     * arriving at this independently.
     */
    private var modernPwmFieldAlive = false

    /** The same rule for the motor temperature, which shares the frame and the firmware gap. */
    private var modernMotorTemperatureFieldAlive = false
    private var maxSeriesCellIndex = -1
    private var modernPackVoltageSeen = false
    private var decodedModelName: String? = null

    /**
     * The firmware family from the answer to `V`, or `null` while there has been none - and
     * until then every frame decodes exactly as stock. Custom firmwares only change how a
     * field is read once the wheel has said which firmware it runs.
     */
    private var firmwareFamily: BegodeFirmwareFamily? = null

    // Series-cell count of the pack: the model's published pack string, handed down by the
    // caller. There is no fallback guess - see calculateBatteryPercent().
    private var catalogueSeriesCells = 0

    // Per-device no-load ratio (km/h per volt) learned from the live stream, overriding the
    // voltage-class default when the wheel itself proves it wrong. See observeNoLoadEvidence()
    // and calibrateFromHighPowerAlarm().
    private var learnedNoLoadRatio: Double? = null
    private val noLoadEvidence = ArrayDeque<Double>()
    private var highPowerAlarmActive = false

    // Accumulated state to support multi-frame telemetry (Type/Page system)
    private var state = BegodeTelemetry()

    fun reset() {
        buffer = byteArrayOf()
        batteryCells.clear()
        modernProtocolSeen = false
        modernPwmFieldAlive = false
        modernMotorTemperatureFieldAlive = false
        maxSeriesCellIndex = -1
        modernPackVoltageSeen = false
        decodedModelName = null
        firmwareFamily = null
        catalogueSeriesCells = 0
        learnedNoLoadRatio = null
        noLoadEvidence.clear()
        highPowerAlarmActive = false
        state = BegodeTelemetry()
    }

    fun setLegacyVoltageClassMaxVoltage(maxVoltage: Double) {
        legacyMaxVoltage = maxVoltage
        legacyVoltageScale = maxVoltage / LEGACY_BASE_MAX_VOLTAGE
        legacyPwmFreeSpinSpeedKmh = legacyDefaultFreeSpinSpeedKmh(maxVoltage)
        legacyPwmFreeSpinVoltage = maxVoltage
        learnedNoLoadRatio = null
        noLoadEvidence.clear()
    }

    /**
     * How many cells the pack has in series, from the retail catalogue entry for this model.
     *
     * Wheels without a smart BMS never report their cells, and the charge scale is the pack
     * voltage divided by this number - so getting it wrong moves the whole scale. It cannot be
     * derived from a single voltage reading: a full 36S reads 151.2 V and so does a 40S at
     * 3.78 V per cell. The caller resolves the model against the catalogue - or takes the rider's
     * own pick - and pushes the answer down here. Without it the charge stays unknown rather than
     * being guessed; see [calculateBatteryPercent].
     *
     * Ignored for the legacy voltage-class path, where the rider's own choice of pack class is
     * the better answer. Zero or a nonsensical count clears it.
     */
    fun setPackSeriesCells(seriesCells: Int) {
        catalogueSeriesCells = CellChargeCurve.normalizeSeriesCells(seriesCells)
        // The catalogue also settles the PWM estimate's baseline, and it is the better source:
        // the manual voltage class exists for wheels whose model is unknown, and a modern wheel
        // with a known model has no reason to run the estimate off the 100.8 V default. The
        // no-load speed is not proportional to the class - 102.7 km/h at 100.8 V against
        // 113.0 at 134.4 - so an EX30 on the default baseline would read about a fifth low,
        // which is the dangerous direction for an overload warning.
        if (catalogueSeriesCells > 0) {
            val catalogueMaxVoltage = catalogueSeriesCells * FULL_CELL_VOLTAGE
            legacyPwmFreeSpinVoltage = catalogueMaxVoltage
            legacyPwmFreeSpinSpeedKmh = legacyDefaultFreeSpinSpeedKmh(catalogueMaxVoltage)
        }
    }

    /** Series-cell count the charge scale currently runs on, or `0` while nothing is known. */
    fun seriesCellCount(): Int = catalogueSeriesCells

    fun modelName(): String? = decodedModelName

    /** The firmware that answered `V`, or `null` until one has. */
    fun firmware(): BegodeFirmware? = state.firmware

    /**
     * Settings this wheel exposes, with the values it actually reports back.
     *
     * Same entry point as the other brands have, so both phones can build their settings
     * screen from the engine rather than each writing its own list. What is in the list and
     * why it is shorter than the vendor apps' — see [BegodeSettingsCatalog].
     */
    fun settingsCapabilities(): List<BegodeSettingCapability> =
        BegodeSettingsCatalog.capabilities(state)

    /**
     * No-load ratio the PWM estimate currently runs on, in km/h per volt: either the voltage
     * class default, the value handed to [setLegacyPwmCalibration], or whatever the live stream
     * has since proven (see [observeNoLoadEvidence], [calibrateFromHighPowerAlarm]).
     */
    fun legacyPwmNoLoadRatio(): Double = learnedNoLoadRatio ?: configuredNoLoadRatio()

    fun setLegacyPwmCalibration(
        freeSpinSpeedKmh: Double,
        freeSpinVoltage: Double = legacyMaxVoltage,
        powerFactor: Double = 1.0,
    ) {
        if (freeSpinSpeedKmh.isFinite() && freeSpinSpeedKmh > 0.0) {
            legacyPwmFreeSpinSpeedKmh = freeSpinSpeedKmh
        }
        if (freeSpinVoltage.isFinite() && freeSpinVoltage > 0.0) {
            legacyPwmFreeSpinVoltage = freeSpinVoltage
        }
        if (powerFactor.isFinite() && powerFactor > 0.0) {
            legacyPwmPowerFactor = powerFactor
        }
        // An explicit calibration supersedes anything learned from the previous baseline.
        learnedNoLoadRatio = null
        noLoadEvidence.clear()
    }

    fun consume(chunk: ByteArray): BegodeTelemetry? {
        BegodeIdentityReplies.modelName(chunk)?.let { decodedModelName = it }
        var updated = false
        // The first answer sticks, as in WheelLog: a firmware does not change mid-session, and a
        // later chunk that merely looks like a reply must not flip how frames are read.
        if (firmwareFamily == null) {
            BegodeIdentityReplies.firmware(chunk)?.let { firmware ->
                firmwareFamily = firmware.family
                state = state.copy(firmware = firmware)
                updated = true
            }
        }
        buffer += chunk

        while (buffer.size >= BEGODE_FRAME_SIZE) {
            val headerIndex = buffer.indexOfHeader()
            if (headerIndex < 0) {
                buffer = buffer.takeLast(1).toByteArray()
                break
            }
            if (headerIndex > 0) {
                buffer = buffer.copyOfRange(headerIndex, buffer.size)
            }
            if (buffer.size < BEGODE_FRAME_SIZE) break

            // Check if we have a valid 24-byte frame
            if (buffer.hasFooter()) {
                val frame = buffer.copyOfRange(0, BEGODE_FRAME_SIZE)
                processFrame(frame)
                updated = true
                buffer = buffer.copyOfRange(BEGODE_FRAME_SIZE, buffer.size)
            } else {
                buffer = buffer.copyOfRange(1, buffer.size)
            }
        }

        return if (updated) state else null
    }

    private fun processFrame(frame: ByteArray) {
        val type = frame.u8(18)
        val page = frame.u8(19)

        if (firmwareFamily == BegodeFirmwareFamily.SmirnoV && processSmirnovFrame(frame, type, page)) {
            return
        }

        // Update BMS cells if present (common for modern Begode with Smart BMS)
        if (type in 2..3 && page in 0..7) {
            val packOffset = if (type == 3) 64 else 0
            val pageOffset = page * 8
            repeat(8) { index ->
                val voltage = frame.uint16Be(2 + index * 2)
                if (voltage in 2500..4500) {
                    batteryCells[packOffset + pageOffset + index] = voltage / 1000.0
                    maxSeriesCellIndex = maxOf(maxSeriesCellIndex, pageOffset + index)
                }
            }
        }

        if (isModernIdentityFrame(frame, type, page)) {
            modernProtocolSeen = true
        }

        if (isModernFrame(frame, type, page)) {
            state = parseModern(frame, type, page)
            return
        }

        if (isLegacyStatusFrame(frame)) {
            state = parseLegacyStatus(frame)
            return
        }

        if (isLegacyLiveTelemetryFrame(frame)) {
            state = parseLegacy(frame)
        }
    }

    /**
     * The frames SmirnoV firmware lays out differently, per WheelLog's `GotwayAdapter`, which is
     * the only reading of this firmware there is. Returns `false` for a frame that decodes as
     * stock.
     *
     * - `type=1` carries no pack voltage and no BMS: `@6 & 3` is the pedal mode. Without this
     *   branch a pedal byte of 1..3 with a large `@7` reads as a 70..102 V pack and flips the
     *   wheel to the modern decode.
     * - `type=4` carries the odometer and nothing else WheelLog reads - no alarm flags, no
     *   settings word.
     * - `type=7` is not read at all.
     * - `type=0xFF` is the firmware's settings frame, [BegodeSmirnovSettings].
     *
     * `type=0` goes through [parseLegacy], which knows the SmirnoV field changes.
     */
    private fun processSmirnovFrame(frame: ByteArray, type: Int, page: Int): Boolean {
        when (type) {
            1 -> state = state.copy(
                smirnovPedalMode = frame.u8(6) and 0x3,
                lastType = type,
                lastPage = page,
            )
            4 -> if (page == 24) {
                state = state.copy(
                    totalDistanceKm = frame.totalDistanceKm(),
                    lastType = type,
                    lastPage = page,
                )
            }
            7 -> Unit
            0xFF -> state = state.copy(
                smirnovSettings = BegodeSmirnovSettings(
                    extremeMode = frame.u8(2) and 0x1 != 0,
                    brakingCurrent = frame.u8(3),
                    rotationControl = frame.u8(4) and 0x1 != 0,
                    rotationAngleDegrees = frame.u8(5) + SMIRNOV_ROTATION_ANGLE_BASE,
                    advancedSettings = frame.u8(6) and 0x1 != 0,
                    proportionalFactor = frame.u8(7),
                    integralFactor = frame.u8(8),
                    differentialFactor = frame.u8(9),
                    dynamicCompensation = frame.u8(10),
                    dynamicCompensationFilter = frame.u8(11),
                    accelerationCompensation = frame.u8(12),
                    currentQProportional = frame.u8(14),
                    currentQIntegral = frame.u8(15),
                    currentDProportional = frame.u8(16),
                    currentDIntegral = frame.u8(17),
                ),
                lastType = type,
                lastPage = page,
            )
            0 -> if (page == 24 && abs(frame.int16Be(4)) <= MAX_SPEED_RAW) {
                state = parseLegacy(frame)
            }
            else -> return false
        }
        return true
    }

    private fun parseModern(frame: ByteArray, type: Int, page: Int): BegodeTelemetry {
        var next = state.copy(lastType = type, lastPage = page)

        when (type) {
            0 -> if (page == 24) {
                val speedKmh = (abs(frame.int16Be(4)).toDouble() * 3.6) / 100.0
                val phaseCurrent = abs(frame.int16Be(10)) / 100.0
                next = next.copy(
                    speedKmh = speedKmh,
                    // Motor/phase current from the main live frame. A moving Commander GT Pro
                    // dump reaches -100.4..138.9 A here while the battery current in type 7 only
                    // reaches -4.78 A. Deriving phase current by dividing battery current by PWM
                    // was especially wrong at 0% PWM, where idle noise became a bogus 10+ A.
                    phaseCurrent = phaseCurrent,
                    // The traditional Begode thermistor conversion. On Commander GT Pro this
                    // channel sits around 33 C and moves under controller load, so expose it as
                    // the primary/controller temperature.
                    temperature = frame.int16Be(12) / 340.0 + 36.53,
                    tripDistanceMeters = frame.uint16Be(8).toDouble(),
                    isLegacy = false,
                )
                // A modern wheel whose own PWM channel never says anything still gets the
                // estimate - the same one every Gotway has always run on, and the same one
                // this engine uses for legacy wheels. Being recognised as modern must not
                // cost a rider the overload warning; a wheel that does report PWM has already
                // set [modernPwmFieldAlive] and keeps its own number.
                val voltage = next.voltage
                val hardwarePwm = frame.hardwarePwmPercent()
                // Nothing is published before the pack voltage is known: the estimate divides
                // by it, and a zero shown at connect is the same lie this whole branch exists
                // to stop telling.
                if (!modernPwmFieldAlive && hardwarePwm != null) {
                    // Custom firmware measures what the estimate guesses. `type=7` still wins
                    // once it has proven itself, as in WheelLog, where `truePWM` outranks it.
                    next = next.copy(pwmPercent = hardwarePwm)
                } else if (!modernPwmFieldAlive && voltage.isFinite()) {
                    observeNoLoadEvidence(speedKmh, voltage, phaseCurrent)
                    next = next.copy(
                        pwmPercent = calculateLegacyPwmPercent(speedKmh, voltage, phaseCurrent),
                    )
                }
            }
            4 -> if (page == 24) {
                val tiltback = frame.uint16Be(10)
                next = next.copy(
                    totalDistanceKm = frame.totalDistanceKm(),
                    rideMode = ((frame.uint16Be(6) ushr 13) and 0x7).toDouble(),
                    // The wheel says "no tilt-back" by reporting a speed nobody rides at. That
                    // becomes a zero rather than a NaN: NaN is reserved for "the wheel has not
                    // said yet", and collapsing the two let the settings screen print "off" for
                    // a wheel that had said nothing - and let a preset captured in that window
                    // write the off byte to a wheel whose tilt-back was armed.
                    tiltbackSpeedKmh = if (tiltback >= TILTBACK_UNSET_KMH) {
                        0.0
                    } else {
                        tiltback.toDouble()
                    },
                    autoPowerOffSeconds = frame.uint16Be(8).toDouble(),
                    usesMiles = frame.u8(7) and 0x1 != 0,
                    lightMode = frame.u8(15),
                    wheelAlertFlags = frame.u8(14),
                    speedAlarmMode = frame.speedAlarmMode(),
                    isLegacy = false,
                )
            }
            1 -> {
                val voltage = frame.uint16Be(6) / 10.0
                modernPackVoltageSeen = true
                next = next.copy(
                    voltage = voltage,
                    batteryPercent = calculateBatteryPercent(voltage, batteryCells.values.toList(), isLegacy = false),
                    isLegacy = false,
                )
                // The power-margin setting, read back. The wire carries the load level and the
                // rider is shown its complement, exactly as the `W P` command writes it.
                //
                // This was the field the notes could not decide about - "likely SOC" in one
                // place, "power alarm threshold" in another. It is not the charge: the EX30
                // capture reports 85 while sitting at 133.5 V of a 134.4 V pack, and a riding
                // Commander holds 90 unchanged from 0 to 36 km/h. A number that does not move
                // while the wheel does is a setting.
                val level = frame.uint16Be(2)
                if (level in MIN_POWER_LEVEL..MAX_POWER_LEVEL) {
                    next = next.copy(powerMarginPercent = (100 - level).toDouble())
                }
            }
            2, 3 -> {
                val cells = batteryCells.values.sorted()
                val voltage = if (modernPackVoltageSeen && next.voltage.isFinite()) {
                    next.voltage
                } else {
                    cells.takeIf { it.isNotEmpty() }?.average()?.times(inferredSeriesCellCount()) ?: Double.NaN
                }
                next = next.copy(
                    voltage = voltage,
                    batteryPercent = calculateBatteryPercent(voltage, cells, isLegacy = false),
                    cellVoltages = cells,
                    isLegacy = false,
                )
            }
            7 -> if (page == 24) {
                val current = frame.int16Be(2) / 100.0
                val voltage = if (next.voltage.isNaN()) 100.0 else next.voltage
                next = next.copy(
                    current = current,
                    power = voltage * current,
                    apparentPower = voltage * current,
                    isLegacy = false,
                )
                // Battery current sits at @2 on every wheel seen so far; everything past it is
                // optional, and each field proves itself separately - see [modernPwmFieldAlive].
                val motorTemperature = frame.int16Be(6)
                if (motorTemperature != 0) modernMotorTemperatureFieldAlive = true
                if (modernMotorTemperatureFieldAlive) {
                    // Commander GT Pro reports a separate, cooler 20 C channel here. This is the
                    // motor temperature; keeping it separate makes both temperatures visible.
                    next = next.copy(motorTemperature = motorTemperature.toDouble())
                }
                val pwm = frame.int16Be(8)
                if (pwm != 0) modernPwmFieldAlive = true
                if (modernPwmFieldAlive) {
                    next = next.copy(pwmPercent = abs(pwm).toDouble())
                }
            }
        }
        return next
    }

    private fun parseLegacy(frame: ByteArray): BegodeTelemetry {
        val speedRaw = frame.int16Be(4)
        val baseVoltage = frame.uint16Be(2) / 100.0
        val voltage = if (baseVoltage in 45.0..70.0) baseVoltage * legacyVoltageScale else frame.uint16Be(16).toDouble()
        val phaseCurrentRaw = frame.int16Be(10)
        // SmirnoV firmware sends the same fields in its own units, per WheelLog: phase current
        // in tenths of an ampere rather than hundredths, the IMU temperature of an MPU6500
        // rather than an MPU6050, and `@8` - the trip counter on stock - is the measured battery
        // current in hundredths of an ampere whenever bit 0 of `@7` says so.
        val smirnov = firmwareFamily == BegodeFirmwareFamily.SmirnoV
        val phaseCurrent = abs(phaseCurrentRaw) / if (smirnov) 10.0 else 100.0
        val speedKmh = if (abs(speedRaw) <= LEGACY_STATIONARY_SPEED_DEADBAND_RAW) {
            0.0
        } else {
            (abs(speedRaw) * 3.6) / 100.0
        }

        val hardwarePwm = frame.hardwarePwmPercent()
        // Deliberately the magnitude: the PWM model adds a resistive-drop term and the no-load
        // detector compares against a ceiling, and both would read a braking sample as a lightly
        // loaded one. Reading PWM high under braking is the safe direction to be wrong in.
        // A wheel that measures its own PWM needs none of it, and teaches the estimate nothing.
        if (hardwarePwm == null) observeNoLoadEvidence(speedKmh, voltage, phaseCurrent)
        val pwm = hardwarePwm ?: calculateLegacyPwmPercent(speedKmh, voltage, phaseCurrent)
        val flow = powerFlowSign(phaseCurrentRaw, speedRaw, TorqueSignConvention.AgreementIsDriving)
        val measuredCurrent = if (smirnov && frame.u8(7) and 0x1 != 0) frame.int16Be(8) / 100.0 else null
        val temperature = if (smirnov) {
            frame.int16Be(12) / MPU6500_TEMPERATURE_SENSITIVITY + MPU6500_TEMPERATURE_OFFSET
        } else {
            frame.int16Be(12) / 340.0 + 36.53
        }
        return state.copy(
            speedKmh = speedKmh,
            pwmPercent = pwm,
            voltage = voltage,
            current = measuredCurrent ?: (flow * phaseCurrent * (pwm / 100.0)), // Estimated battery current
            phaseCurrent = phaseCurrent,
            power = measuredCurrent?.let { voltage * it } ?: (flow * voltage * phaseCurrent * (pwm / 100.0)),
            // Apparent power is a magnitude by definition, so it keeps no sign.
            apparentPower = measuredCurrent?.let { voltage * abs(it) } ?: (voltage * phaseCurrent * (pwm / 100.0)),
            temperature = temperature,
            smirnovTrick = if (smirnov) frame.u8(16) else state.smirnovTrick,
            batteryPercent = calculateBatteryPercent(voltage, emptyList(), isLegacy = true),
            // Raw meters, exactly as WheelLog's GotwayAdapter reads it (`shortFromBytesBE(buff, 8)`
            // straight into setWheelDistance). The old `/ 3.6` here was inferred from a ride that
            // displayed "45 km for a 12.5 km trip", but that number came from the ride summary
            // differencing @8 of frame B - the power-off timer, not an odometer - so the 3.6 was a
            // coincidence. See docs/begode-protocol-notes.md. SmirnoV firmware has no trip counter
            // there: the field is the battery current.
            tripDistanceMeters = if (smirnov) state.tripDistanceMeters else frame.uint16Be(8).toDouble(),
            lastType = frame.u8(18),
            lastPage = frame.u8(19),
            isLegacy = true,
        )
    }

    private fun parseLegacyStatus(frame: ByteArray): BegodeTelemetry {
        // Frame B carries the odometer as a 32-bit metre count at @2..@5 on legacy streams just
        // like it does on modern ones. @8 is the auto-power-off countdown in seconds (it idles at
        // 7200 = two hours and ticks down while the wheel stands still) and @10 is the tiltback
        // speed - neither is distance or charge, though both used to be read as such here.
        val alertFlags = frame.u8(14)
        calibrateFromHighPowerAlarm(alertFlags and BegodeWheelAlert.HighPower != 0)
        return state.copy(
            totalDistanceKm = frame.totalDistanceKm(),
            wheelAlertFlags = alertFlags,
            speedAlarmMode = frame.speedAlarmMode(),
            lastType = frame.u8(18),
            lastPage = frame.u8(19),
            isLegacy = true,
        )
    }

    private fun configuredNoLoadRatio(): Double = legacyPwmFreeSpinSpeedKmh / legacyPwmFreeSpinVoltage

    private fun calculateLegacyPwmPercent(speedKmh: Double, voltage: Double, phaseCurrent: Double): Double {
        if (!speedKmh.isFinite() || !voltage.isFinite() || voltage <= 0.0) {
            return 0.0
        }
        val fraction = legacyPwmFraction(speedKmh, voltage, phaseCurrent) ?: return Double.NaN
        // Deliberately reported a touch high: the estimate has no direct measurement behind it,
        // and reading low means the rider is warned late.
        return (fraction * LEGACY_PWM_SAFETY_MARGIN * 100.0).coerceIn(0.0, 100.0)
    }

    /**
     * WheelLog's model (`WheelData.calculatePwm`): speed over the no-load speed the pack can spin
     * to at this voltage, plus a load term for the resistive drop the free-spin ratio can't see.
     * Returns the raw fraction with no safety margin, so the calibration paths can invert it.
     */
    private fun legacyPwmFraction(speedKmh: Double, voltage: Double, phaseCurrent: Double): Double? {
        val noLoadAtVoltage = legacyPwmNoLoadRatio() * voltage * legacyPwmPowerFactor
        if (!noLoadAtVoltage.isFinite() || noLoadAtVoltage <= 0.0) {
            return null
        }
        val speedRatio = speedKmh.coerceAtLeast(0.0) / noLoadAtVoltage
        return speedRatio + legacyLoadCompensation(voltage, phaseCurrent)
    }

    /** Iphase * R / Vbat, with R the estimated phase resistance. */
    private fun legacyLoadCompensation(voltage: Double, phaseCurrent: Double): Double {
        if (!phaseCurrent.isFinite() || voltage <= 0.0) {
            return 0.0
        }
        return (phaseCurrent * LEGACY_ESTIMATED_PHASE_RESISTANCE) / voltage
    }

    /**
     * Fallback 2: the wheel's own high-power alarm fires at a known PWM, so the moment it turns on
     * is a direct reading of the model's only unknown. Solves the model for the no-load ratio and
     * takes the answer only if it lands in a sane band around the voltage-class default - a
     * recalibration that disagrees wildly is garbage (a stale speed sample, an alarm raised for
     * something other than PWM saturation) and gets dropped rather than trusted.
     */
    private fun calibrateFromHighPowerAlarm(active: Boolean) {
        val rising = active && !highPowerAlarmActive
        highPowerAlarmActive = active
        if (!rising || modernProtocolSeen) {
            return
        }
        val speedKmh = state.speedKmh
        val voltage = state.voltage
        if (!speedKmh.isFinite() || speedKmh < MIN_CALIBRATION_SPEED_KMH) {
            return
        }
        if (!voltage.isFinite() || voltage <= 0.0 || legacyPwmPowerFactor <= 0.0) {
            return
        }
        val speedShare = WHEEL_HIGH_POWER_ALARM_PWM_FRACTION - legacyLoadCompensation(voltage, state.phaseCurrent)
        if (speedShare < MIN_ALARM_SPEED_SHARE) {
            return
        }
        acceptNoLoadRatio(speedKmh / (speedShare * voltage * legacyPwmPowerFactor))
    }

    /**
     * PWM cannot exceed 100%, so a wheel seen holding a given speed at a given voltage puts a hard
     * floor under its no-load ratio. Raises the calibration to that floor once the stream sustains
     * it, which keeps one corrupt speed sample from silently muting the PWM estimate for the rest
     * of the ride. Only ever raises: lowering it needs the wheel's own alarm as evidence.
     */
    private fun observeNoLoadEvidence(speedKmh: Double, voltage: Double, phaseCurrent: Double) {
        if (speedKmh < MIN_CALIBRATION_SPEED_KMH || !voltage.isFinite() || voltage <= 0.0 ||
            phaseCurrent > MAX_NO_LOAD_EVIDENCE_PHASE_CURRENT || legacyPwmPowerFactor <= 0.0
        ) {
            noLoadEvidence.clear()
            return
        }
        val floor = speedKmh / (voltage * legacyPwmPowerFactor)
        if (floor <= legacyPwmNoLoadRatio()) {
            noLoadEvidence.clear()
            return
        }
        noLoadEvidence.addLast(floor)
        if (noLoadEvidence.size < NO_LOAD_EVIDENCE_SAMPLES) {
            return
        }
        // The weakest sample of the run, so the ratio moves no further than every sample supports.
        val sustained = noLoadEvidence.minOrNull()
        noLoadEvidence.clear()
        if (sustained != null) {
            acceptNoLoadRatio(sustained)
        }
    }

    private fun acceptNoLoadRatio(candidate: Double) {
        if (!candidate.isFinite() || candidate <= 0.0) {
            return
        }
        val default = configuredNoLoadRatio()
        if (!default.isFinite() || default <= 0.0) {
            return
        }
        if (candidate < default * MIN_CALIBRATION_FACTOR || candidate > default * MAX_CALIBRATION_FACTOR) {
            return
        }
        learnedNoLoadRatio = candidate
    }

    /**
     * Charge for one cell, read off the curve every brand shares; see [CellChargeCurve].
     */
    internal fun batteryPercentFromCellVoltage(cellVoltage: Double): Double =
        CellChargeCurve.percentFromCellVoltage(cellVoltage)

    /**
     * Charge from the pack voltage, which is only meaningful once the series-cell count is known.
     *
     * There is no guess here on purpose. The count used to be inferred from the live pack voltage
     * by buckets, and that is a trap rather than an approximation: the buckets stepped at 90 V,
     * which sits in the middle of a 24S pack's working range, so every sag under that line moved
     * the divider from 24 to 20 and the reported charge jumped from about 53 % to a clipped 100 %.
     * A rider who connected below the line - a T4 at half charge - saw a full battery for the
     * whole session. Two layers of latching were built on top to hold the guess steady, and
     * neither survived a reconnect.
     *
     * The count cannot be derived from one voltage reading even in principle: a full 36S reads
     * 151.2 V and so does a 40S at 3.78 V per cell. It comes from the pack itself (a smart BMS
     * reporting cells), or from the catalogue entry for the model, and when neither is available
     * the honest answer is that the charge is unknown.
     */
    private fun calculateBatteryPercent(voltage: Double, cells: List<Double>, isLegacy: Boolean): Double {
        val cellVoltage = if (cells.isNotEmpty()) cells.average() else {
            // Legacy wheels have a fixed, rider-selected voltage class (legacyMaxVoltage), so the
            // series-cell count stays pinned to that class rather than being re-derived from the
            // live pack voltage.
            val series = if (isLegacy && legacyMaxVoltage > 0.0) {
                (legacyMaxVoltage / 4.2).roundToInt()
            } else {
                catalogueSeriesCells
            }
            if (series <= 0) return Double.NaN
            voltage / series
        }

        return batteryPercentFromCellVoltage(cellVoltage)
    }

    /**
     * Series-cell count used to rebuild the pack voltage from cell readings. Measured cells win
     * over the catalogue - they are the pack itself rather than its data sheet - and the
     * catalogue wins over the blanket default, which is a 32S guess that suits no 24S wheel.
     */
    private fun inferredSeriesCellCount(): Int {
        val nominal = if (catalogueSeriesCells > 0) catalogueSeriesCells else DEFAULT_SERIES_CELL_COUNT
        return maxOf(nominal, maxSeriesCellIndex + 1)
    }

    private fun ByteArray.indexOfHeader(): Int {
        for (i in 0 until size - 1) {
            if (this[i] == 0x55.toByte() && this[i + 1] == 0xAA.toByte()) return i
        }
        return -1
    }

    private fun ByteArray.hasFooter(): Boolean {
        if (size < BEGODE_FRAME_SIZE) return false
        return this[20] == 0x5A.toByte() && this[21] == 0x5A.toByte() &&
               this[22] == 0x5A.toByte() && this[23] == 0x5A.toByte()
    }

    private fun isModernFrame(frame: ByteArray, type: Int, page: Int): Boolean {
        return when (type) {
            0 -> modernProtocolSeen && page == 24 && isModernSpeedFrame(frame)
                || page == 24 && isModernSpeedFrame(frame) && !isLegacyLiveTelemetryFrame(frame)
            1 -> isModernIdentityFrame(frame, type, page)
            2, 3 -> isModernIdentityFrame(frame, type, page)
            4 -> modernProtocolSeen && page == 24
            7 -> page == 24 && (modernProtocolSeen || !isLegacyLiveTelemetryFrame(frame))
            else -> false
        }
    }

    private fun isModernIdentityFrame(frame: ByteArray, type: Int, page: Int): Boolean {
        return when (type) {
            1 -> page in 0..3 && modernPackVoltage(frame).isFinite()
            2, 3 -> page in 0..7 && hasCellVoltages(frame)
            else -> false
        }
    }

    private fun isModernSpeedFrame(frame: ByteArray): Boolean {
        return abs(frame.int16Be(4)) <= MAX_SPEED_RAW
    }

    private fun isLegacyLiveTelemetryFrame(frame: ByteArray): Boolean {
        // Frame kind comes from the type/page bytes, never from the payload looking plausible -
        // every other frame carries a distance or cell voltage at @4 that reads as a fine speed.
        if (frame.u8(18) != 0 || frame.u8(19) != 24) {
            return false
        }
        val speedRaw = abs(frame.int16Be(4))
        if (speedRaw > MAX_SPEED_RAW) {
            return false
        }
        val baseVoltage = frame.uint16Be(2) / 100.0
        val fallbackVoltage = frame.uint16Be(16).toDouble()
        return baseVoltage in 45.0..70.0 || fallbackVoltage in 45.0..120.0
    }

    private fun isLegacyStatusFrame(frame: ByteArray): Boolean {
        return frame.u8(18) == 4 && frame.u8(19) == 24 && !modernProtocolSeen
    }

    private fun modernPackVoltage(frame: ByteArray): Double {
        return (frame.uint16Be(6) / 10.0).takeIf { it in 70.0..250.0 } ?: Double.NaN
    }

    private fun hasCellVoltages(frame: ByteArray): Boolean {
        return (0 until 8).any { index ->
            frame.uint16Be(2 + index * 2) in 2500..4500
        }
    }

    /**
     * Motor PWM, percent, from `type=0 @14` - only on firmware that puts it there. Tenths of a
     * percent on the wire: WheelLog scales it `* 10` into its hundredths-of-a-percent output,
     * and EUC World's CF branch reads the same field as `100 - |@14| * 0.1`. On stock firmware
     * `@14` is a word of settings bits, so this is `null` until `V` has named a custom firmware.
     */
    private fun ByteArray.hardwarePwmPercent(): Double? {
        if (firmwareFamily?.reportsHardwarePwm != true) return null
        return (abs(int16Be(14)) / 10.0).coerceAtMost(100.0)
    }

    /** `type=4 @6`, bits 10..11. */
    private fun ByteArray.speedAlarmMode(): Int = (uint16Be(6) ushr 10) and 0x3

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
    private fun ByteArray.uint16Be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)
    private fun ByteArray.int16Be(offset: Int): Int {
        val v = uint16Be(offset)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    private fun ByteArray.totalDistanceKm(): Double {
        val high = uint16Be(2).toLong()
        val low = uint16Be(4).toLong()
        return ((high shl 16) + low) / 1000.0
    }

    companion object {
        private const val BEGODE_FRAME_SIZE = 24

        // SmirnoV firmware's IMU temperature, `raw / 333.87 + 21.00` (the MPU6500 datasheet
        // conversion), as WheelLog reads it; stock is the MPU6050's `raw / 340 + 36.53`.
        private const val MPU6500_TEMPERATURE_SENSITIVITY = 333.87
        private const val MPU6500_TEMPERATURE_OFFSET = 21.0

        /** SmirnoV's rotation angle byte counts from 260 degrees. */
        private const val SMIRNOV_ROTATION_ANGLE_BASE = 260
        private const val MAX_SPEED_RAW = 4_500

        /**
         * At and above this the wheel means "tilt-back off" rather than a speed.
         *
         * Nobody rides a Begode at a hundred kilometres an hour with tilt-back armed, and
         * this is the same reading WheelLog's `GotwayAdapter` takes of the field.
         */
        private const val TILTBACK_UNSET_KMH = 100

        // The window the vendor apps accept for the power level, and the window the write
        // command can produce. Outside it the field is something else, or nothing.
        private const val MIN_POWER_LEVEL = 50
        private const val MAX_POWER_LEVEL = 100
        private const val LEGACY_BASE_MAX_VOLTAGE = 67.2

        /** A full li-ion cell, the number the pack's class is built out of. */
        private const val FULL_CELL_VOLTAGE = 4.2
        private const val LEGACY_STATIONARY_SPEED_DEADBAND_RAW = 100
        private const val DEFAULT_SERIES_CELL_COUNT = 32

        // Sanity bounds on a pushed-down series-cell count: below is no unicycle pack, above is
        // not one either, and either way it is a caller's mistake rather than a pack.
        private const val LEGACY_ESTIMATED_PHASE_RESISTANCE = 0.05

        /** WheelLog's default for the same model; the free-spin ratio alone reads optimistically. */
        private const val LEGACY_DEFAULT_POWER_FACTOR = 0.9

        /** Report the estimate 1% high rather than risk warning the rider late. */
        private const val LEGACY_PWM_SAFETY_MARGIN = 1.01

        /** Motor PWM at which stock Begode firmware raises [BegodeWheelAlert.HighPower]. */
        const val WHEEL_HIGH_POWER_ALARM_PWM_FRACTION = 0.8

        /**
         * Below this the wheel is manoeuvring rather than saturating the motor, and the speed
         * sample is too small for the model inversion to say anything useful.
         */
        private const val MIN_CALIBRATION_SPEED_KMH = 25.0

        /** Leaves the alarm inversion room to be about speed rather than about the load term. */
        private const val MIN_ALARM_SPEED_SHARE = 0.3

        /** Above this the load term dominates and the 100%-PWM ceiling stops being a clean floor. */
        private const val MAX_NO_LOAD_EVIDENCE_PHASE_CURRENT = 25.0

        /** Consecutive samples a higher no-load floor must survive before it is believed. */
        private const val NO_LOAD_EVIDENCE_SAMPLES = 3

        // Band a recalibration has to land in to be taken at all. Wide enough for the spread
        // between motor variants inside one voltage class, narrow enough that a bogus reading
        // cannot walk the PWM estimate somewhere absurd.
        private const val MIN_CALIBRATION_FACTOR = 0.7
        private const val MAX_CALIBRATION_FACTOR = 1.5

        /**
         * Lift-up (free-spin) speed at the class's own maximum voltage, WheelLog's "rotation
         * speed" setting. The 100.8 V entry is measured: recorded rides from a GotWay_50763
         * (Begode RS) have the wheel spinning at 100.8 km/h on 99.0 V drawing 8..15 A, and its
         * speed tracks GPS within 1.3% across 843 matched points, so 100.8/99.0 * 100.8 is a lower
         * bound on free spin. That is one RS, though - slower motors on the same voltage class
         * (MSX, Nikola) will read low here, as will every unmeasured class, which is what the
         * per-device calibration above is for.
         */
        private fun legacyDefaultFreeSpinSpeedKmh(maxVoltage: Double): Double {
            return when {
                maxVoltage <= 67.2 -> 44.0
                maxVoltage <= 84.0 -> 79.0
                maxVoltage <= 100.8 -> 102.7
                maxVoltage <= 116.8 -> 95.0
                maxVoltage <= 134.4 -> 113.0
                else -> 140.0
            }
        }
    }
}
