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
    /**
     * Hottest and coldest battery sensor the wheel reports, from the pack summary at `type=1`.
     *
     * `NaN` where the wheel does not measure it: one of the three captures that have this
     * frame answers a hard `0/0` on every one of them, and a battery claiming 0 C in a room
     * is the kind of reading an overheat alert is built to act on. The other two report
     * 22..24 C parked and 29..36 C after a ride, always at or below the controller sensor in
     * the same frame set, which is what the numbers should do.
     */
    val batteryTemperatureMax: Double = Double.NaN,
    val batteryTemperatureMin: Double = Double.NaN,
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
    /**
     * Side-fall cutoff level `0..2` from `type=4`, `(u16be @6 >> 7) & 7`, in the order of the
     * `>`, `=`, `<` commands. `null` until seen, or when the field holds anything else.
     *
     * This and the rest of the settings read-backs below come from one reading of WheelHUD's
     * native Begode parser, cross-checked by a second independent reading of the same binary
     * and against every Begode capture in the repository, where each field holds a constant
     * in-range value for the whole ride. No capture changes one of them mid-ride, so none is
     * proven by a change of value yet. See `docs/begode-protocol-notes.md`.
     */
    val sideTiltThreshold: Int? = null,
    /** LED strip mode `0..9` from `type=4 @13 & 0x0F`, the value `W M n` writes. */
    val ledMode: Int? = null,
    /**
     * Side-fall cutoff angle as the wire index `0..9` from the low nibble of `type=7 @5`;
     * the angle is [BegodeSettingsWriter.MinSideTiltDegrees] plus five degrees per step.
     */
    val sideTiltAngleIndex: Int? = null,
    /** Field weakening `0..9` from the high nibble of `type=7 @5`, the value `W C n` writes. */
    val fieldWeakening: Int? = null,
    /**
     * Pedal dip in turns `0..9`, `(u16be @14 >> 10) & 0x0F` of `type=0`.
     *
     * Read only on stock firmware once `type=7` has been seen, as WheelHUD gates it: on legacy
     * streams and on the custom firmwares `@14` is something else (PWM on `CF` and `BF`).
     */
    val pedalDip: Int? = null,
    /**
     * Pedal horizontal trim `-4..+5`, `((u16be @14 >> 5) & 0x1F) - 4` of `type=0` - the wire
     * value `W U n` writes, re-centred the same way [BegodeSettingsWriter.pedalTrim] does.
     * Same gating as [pedalDip].
     */
    val pedalTrim: Int? = null,
    /** Buzzer volume `1..9` from `type=0 @17 & 0x0F`. Same gating as [pedalDip]. */
    val buzzerVolume: Int? = null,
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
 *
 * ## Pacing: never while the wheel is silent
 *
 * A query costs the rider telemetry. `GotWay_012960` (stock `GW2026304`, captures
 * `20A71631EF9E/20260915_171748`, `_172124`, `_172235`) finishes the frame set it is in the middle
 * of and then sends nothing for as long as bytes keep arriving; about one second after the last
 * one it prints its version line and streams again. It never answered `N`. Asked every 100 ms,
 * twenty times each, it went silent for five seconds - longer than the phone's connection-lost
 * watchdog - so every connection ended in a reconnect that asked again.
 *
 * So a query goes out only while the stream is alive - a notify within [STREAM_ALIVE_MILLIS] -
 * and no sooner than [RETRY_INTERVAL_MILLIS] after the previous one, which is longer than that
 * wheel's pause. A wheel that stops to listen is therefore asked again only after it has
 * resumed, and each query costs at most one such pause. An answer seen before the session
 * started counts too ([seed]): that wheel's version line can arrive before the first query.
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
    private var lastNotifyAtMillis = 0L
    private var hasHeardWheel = false

    /** True once there is nothing more to ask: both answered, or both out of attempts. */
    val isFinished: Boolean
        get() = !nameDue && !versionDue

    private val nameDue get() = modelName == null && nameAttempts < MAX_ATTEMPTS
    private val versionDue get() = firmware == null && versionAttempts < MAX_ATTEMPTS

    /** One notification as it arrived, at [nowMillis] on the same clock as [nextRequest]. */
    fun onNotify(chunk: ByteArray, nowMillis: Long) {
        lastNotifyAtMillis = nowMillis
        hasHeardWheel = true
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

    /**
     * Answers that arrived on this connection before the session started - the engine has already
     * read them out of the stream. A known answer is not asked for again.
     */
    fun seed(modelName: String?, firmware: BegodeFirmware?) {
        if (this.modelName == null) this.modelName = modelName
        if (this.firmware == null) this.firmware = firmware
    }

    /** The query to write now, or `null` when nothing is due. Returns a fresh array. */
    fun nextRequest(nowMillis: Long): ByteArray? {
        if (isFinished) return null
        if (hasSentRequest && nowMillis - lastRequestAtMillis < RETRY_INTERVAL_MILLIS) return null
        // Only into a live stream: see the class comment for what a query into silence costs.
        if (!hasHeardWheel || nowMillis - lastNotifyAtMillis > STREAM_ALIVE_MILLIS) return null
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
        lastNotifyAtMillis = 0L
        hasHeardWheel = false
    }

    companion object {
        /** `N`, the model-name query. */
        const val NameQuery: Byte = 0x4E

        /** `V`, the firmware-version query. WheelLog and EUC World both send it at connect. */
        const val VersionQuery: Byte = 0x56

        /**
         * Least time between two queries. Longer than the pause `GotWay_012960` takes after a
         * query (its version line came 0.98 s after the last byte, frames 0.25 s after that).
         */
        const val RETRY_INTERVAL_MILLIS: Long = 1_500L

        /**
         * A stream counts as alive while its last notify is at most this old. Begode sends a frame
         * set at least every ~0.3 s; a wheel that has stopped to listen misses this at once.
         */
        const val STREAM_ALIVE_MILLIS: Long = 500L

        /** Per query. Each attempt can cost about a second of telemetry. */
        const val MAX_ATTEMPTS: Int = 2
    }
}

/**
 * Bits of the wheel's own alarm byte (`type=4,page=24 @14`), matching WheelLog's
 * `GotwayAdapter` decode. These are the alarms the wheel raises for itself, independent of
 * any threshold the app evaluates - [HighPower] in particular is the stock Begode alarm that
 * fires at [BegodeProtocolEngine.WHEEL_HIGH_POWER_ALARM_PWM_FRACTION] of motor PWM.
 */
data class BegodeBmsCell(
    val index: Int,
    val voltage: Double,
)

data class BegodeBmsBattery(
    val index: Int,
    /**
     * How many cells this pack has, taken from the highest page it has ever filled rather
     * than from a catalogue: the same frames serve a 32-cell wheel and a 50-cell one, and
     * the wheel is the only one that knows which it is.
     */
    val expectedCellCount: Int,
    val cells: List<BegodeBmsCell>,
)

data class BegodeBmsSnapshot(
    val profileId: String,
    val modelName: String,
    val batteries: List<BegodeBmsBattery>,
)

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

    /** And for the battery sensors, which a wheel without a smart BMS never fills. */
    private var modernBatteryTemperatureFieldAlive = false
    private var maxSeriesCellIndex = -1
    private var modernPackVoltageSeen = false

    /**
     * Has a `type=7` frame arrived. The settings word at `type=0 @14` and the volume at `@17`
     * are only read after one has: a wheel that sends `type=7` lays `type=0` out the modern
     * way, and one that does not puts other things there - an RS C30 without `type=7` reads
     * volume 12 and a pedal trim pinned to the bottom of the scale.
     */
    private var modernStatusFrameSeen = false
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

    // The controller's battery current, pulled onto what the smart BMS measures.
    private val batteryCurrentCalibration = BegodeBatteryCurrentCalibration()

    // Accumulated state to support multi-frame telemetry (Type/Page system)
    private var state = BegodeTelemetry()

    fun reset() {
        buffer = byteArrayOf()
        batteryCells.clear()
        modernProtocolSeen = false
        modernPwmFieldAlive = false
        modernMotorTemperatureFieldAlive = false
        modernBatteryTemperatureFieldAlive = false
        maxSeriesCellIndex = -1
        modernPackVoltageSeen = false
        modernStatusFrameSeen = false
        decodedModelName = null
        firmwareFamily = null
        catalogueSeriesCells = 0
        learnedNoLoadRatio = null
        noLoadEvidence.clear()
        highPowerAlarmActive = false
        batteryCurrentCalibration.reset()
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

    /**
     * Per-pack cell voltages, or null on a wheel whose BMS has said nothing.
     *
     * [BegodeTelemetry.cellVoltages] is the same numbers flattened for the battery-percent
     * curve; this keeps the pack each cell sits in, which is the only way a rider can tell
     * which half of the battery is the weak one.
     */
    fun bmsSnapshot(): BegodeBmsSnapshot? {
        val batteries = (0 until BMS_PACK_COUNT).mapNotNull { pack ->
            val base = pack * BMS_PACK_CELL_STRIDE
            val cells = batteryCells
                .filterKeys { key -> key in base until base + BMS_PACK_CELL_STRIDE }
                .map { (key, voltage) -> BegodeBmsCell(index = key - base + 1, voltage = voltage) }
                .sortedBy(BegodeBmsCell::index)
            cells.takeIf { it.isNotEmpty() }?.let {
                BegodeBmsBattery(
                    index = pack + 1,
                    expectedCellCount = it.last().index,
                    cells = it,
                )
            }
        }
        return batteries.takeIf { it.isNotEmpty() }?.let {
            BegodeBmsSnapshot(
                profileId = "begode_smart_bms",
                modelName = "Begode Smart BMS",
                batteries = it,
            )
        }
    }

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
            val packOffset = if (type == 3) BMS_PACK_CELL_STRIDE else 0
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
                next = next.withLiveFrameSettings(frame, volumeInFrame = true)
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
                next = next.withStatusFrameSettings(frame).copy(
                    totalDistanceKm = frame.totalDistanceKm(),
                    lightMode = frame.u8(15),
                    wheelAlertFlags = frame.u8(14),
                    isLegacy = false,
                )
            }
            1 -> {
                val voltage = frame.uint16Be(6) / 10.0
                modernPackVoltageSeen = true
                // The BMS's own pack current, tenths of an ampere, one page per half of a BMS.
                // Signed its own way per wheel; see [BegodeBatteryCurrentCalibration].
                batteryCurrentCalibration.onBmsCurrent(page, frame.int16Be(8) / 10.0)
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
                // Pack temperatures, whole degrees, as the official app reads them. The pair is
                // the battery's own extremes and not one pack's: every page of this frame
                // carries the same two numbers on both wheels that report them, so attributing
                // them to the page's pack would be inventing a link the wire does not make.
                val maxTemperature = frame.int16Be(10)
                val minTemperature = frame.int16Be(12)
                if (maxTemperature != 0 || minTemperature != 0) {
                    modernBatteryTemperatureFieldAlive = true
                }
                if (modernBatteryTemperatureFieldAlive && maxTemperature >= minTemperature) {
                    next = next.copy(
                        batteryTemperatureMax = maxTemperature.toDouble(),
                        batteryTemperatureMin = minTemperature.toDouble(),
                    )
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
                modernStatusFrameSeen = true
                val current = batteryCurrentCalibration.correct(frame.batteryCurrentAmps())
                val voltage = if (next.voltage.isNaN()) 100.0 else next.voltage
                // `@5` is two settings, one per nibble - the pair the notes long listed as
                // "unidentified": field weakening above, the side-fall angle index below.
                val angleAndWeakening = frame.u8(5)
                next = next.copy(
                    current = current,
                    power = voltage * current,
                    // Apparent power is a magnitude by definition, so it keeps no sign.
                    apparentPower = voltage * abs(current),
                    fieldWeakening = (angleAndWeakening ushr 4).takeIf { it <= 9 },
                    sideTiltAngleIndex = (angleAndWeakening and 0xF).takeIf { it <= 9 },
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
        val power = measuredCurrent?.let { voltage * it } ?: (flow * voltage * phaseCurrent * (pwm / 100.0))
        val temperature = if (smirnov) {
            frame.int16Be(12) / MPU6500_TEMPERATURE_SENSITIVITY + MPU6500_TEMPERATURE_OFFSET
        } else {
            frame.int16Be(12) / 340.0 + 36.53
        }
        // `@17` is the volume only while the pack voltage came from `@2`; otherwise it is the
        // low byte of the voltage this frame was read from.
        return state.withLiveFrameSettings(frame, volumeInFrame = baseVoltage in 45.0..70.0).copy(
            speedKmh = speedKmh,
            pwmPercent = pwm,
            voltage = voltage,
            current = measuredCurrent ?: (flow * phaseCurrent * (pwm / 100.0)), // Estimated battery current
            phaseCurrent = phaseCurrent,
            power = power,
            // Legacy wheels expose an estimate: keep its drive/regeneration direction in VA too.
            apparentPower = power,
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
        //
        // The settings in it are read here too, as on a modern stream. A wheel is "legacy" here
        // by the shape of its voltage field, not by its age: a RACE that sends `type=7` but no
        // `type=1` lands in this branch, and its settings screen stayed blank for it.
        val alertFlags = frame.u8(14)
        calibrateFromHighPowerAlarm(alertFlags and BegodeWheelAlert.HighPower != 0)
        return state.withStatusFrameSettings(frame).copy(
            totalDistanceKm = frame.totalDistanceKm(),
            wheelAlertFlags = alertFlags,
            lastType = frame.u8(18),
            lastPage = frame.u8(19),
            isLegacy = true,
        )
    }

    /**
     * The settings `type=4 page=24` carries, on modern and legacy streams alike.
     *
     * The wheel says "no tilt-back" by reporting a speed nobody rides at. That becomes a zero
     * rather than a NaN: NaN is reserved for "the wheel has not said yet", and collapsing the
     * two let the settings screen print "off" for a wheel that had said nothing - and let a
     * preset captured in that window write the off byte to a wheel whose tilt-back was armed.
     */
    private fun BegodeTelemetry.withStatusFrameSettings(frame: ByteArray): BegodeTelemetry {
        val tiltback = frame.uint16Be(10)
        val modeWord = frame.uint16Be(6)
        return copy(
            rideMode = ((modeWord ushr 13) and 0x7).toDouble(),
            tiltbackSpeedKmh = if (tiltback >= TILTBACK_UNSET_KMH) 0.0 else tiltback.toDouble(),
            autoPowerOffSeconds = frame.uint16Be(8).toDouble(),
            usesMiles = frame.u8(7) and 0x1 != 0,
            speedAlarmMode = frame.speedAlarmMode(),
            sideTiltThreshold = ((modeWord ushr 7) and 0x7).takeIf { it <= 2 },
            ledMode = (frame.u8(13) and 0xF).takeIf { it <= 9 },
        )
    }

    /**
     * The settings `type=0` carries: pedal dip and trim in the word at `@14`, the buzzer volume
     * in the low nibble of `@17`.
     *
     * Only on stock firmware once `type=7` has been seen - see [modernStatusFrameSeen]. Until
     * then the fields stay as they were, which on a fresh connection is unknown.
     */
    private fun BegodeTelemetry.withLiveFrameSettings(frame: ByteArray, volumeInFrame: Boolean): BegodeTelemetry {
        if (!modernStatusFrameSeen || firmwareFamily?.reportsHardwarePwm == true) return this
        val settingsWord = frame.uint16Be(14)
        return copy(
            pedalDip = ((settingsWord ushr 10) and 0xF).takeIf { it <= 9 },
            pedalTrim = ((settingsWord ushr 5) and 0x1F).takeIf { it <= 9 }?.minus(PEDAL_TRIM_WIRE_CENTRE),
            buzzerVolume = if (volumeInFrame) (frame.u8(17) and 0xF).takeIf { it in 1..9 } else buzzerVolume,
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
     * Battery current, amperes, from `type=7 @2`: positive while the pack drives the motor,
     * negative while braking returns energy to it.
     *
     * The wire counts the other way round - a draw is a negative word - so the sign is flipped
     * here, once, and every consumer downstream (battery power, the consumption integral, the
     * current alert) can read a positive number as "the pack is being spent".
     *
     * Unlike the phase current at `type=0 @10` this is a DC-bus quantity, and its sign does not
     * follow the direction of travel; it needs no [powerFlowSign] against the speed word. On
     * capture `385CFBC9BF07/20260727_084903` (Begode Master, stock firmware) every moving sample
     * reads a negative speed, so direction alone cannot tell the two readings apart there - but
     * acceleration can, binning the moving samples:
     *
     *     acceleration        mean @2     @2 negative
     *     over +3 km/h/s      -10.03 A    100 %
     *     +1..+3               -6.08 A     96 %
     *     cruise               -3.38 A     91 %
     *     -3..-1               -0.76 A     57 %
     *     under -3 km/h/s      +1.73 A     20 %
     *
     * Taken as read, that ride integrates to -116.6 Wh while the pack sags from 122.2 V to
     * 118.7 V under load. The phase current, whose convention was established independently
     * ([TorqueSignConvention.AgreementIsDriving]), says the same sample by sample: of 1,771
     * moving samples it calls driving above 10 A, all 1,771 read `@2` negative, and of 295 it
     * calls braking, 98 % read it positive. The strongest single draw, -22.83 A at 26 km/h, is
     * one of the first; the strongest return, +7.53 A at 30 km/h, one of the second. An Extreme Bull Commander GT Pro keeps `@2`
     * negative (-1.9..-4.8 A) in all four moving samples of its capture, creeping forwards and
     * backwards alike, which a direction-signed field would not do. WheelLog's `GotwayAdapter`
     * negates this field unconditionally, outside its per-wheel direction setting, and the
     * official app shows its absolute value.
     */
    private fun ByteArray.batteryCurrentAmps(): Double = -int16Be(2) / 100.0

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

        /**
         * The wire's pedal-trim zero, the same `4` [BegodeSettingsWriter.PedalTrimCentre] adds
         * on the way out. Repeated rather than referenced so reading a setting does not need
         * the write API.
         */
        private const val PEDAL_TRIM_WIRE_CENTRE = 4

        /** Two packs, and the cell keys of the second start one stride above the first. */
        private const val BMS_PACK_COUNT = 2
        private const val BMS_PACK_CELL_STRIDE = 64

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
