package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.math.abs

data class InmotionTelemetry(
    val profileId: String,
    val speedKmh: Double,
    val pwmPercent: Double,
    val voltage: Double,
    val phaseCurrent: Double = Double.NaN,
    val batteryCurrent: Double,
    val power: Double,
    val motorPower: Double,
    val batteryPercent: Double,
    val rideBatteryPercent: Double,
    val tripDistanceMeters: Double,
    val totalDistanceMeters: Double = Double.NaN,
    val temperature: Double = Double.NaN,
    val torque: Double = Double.NaN,
    val pitchAngle: Double = Double.NaN,
    val lateralAngle: Double = Double.NaN,
    val targetAngle: Double = Double.NaN,
    val chargeVoltage: Double = Double.NaN,
    val chargeCurrent: Double = Double.NaN,
    val acceleration: Double = Double.NaN,
    val speedingBrakingAngle: Double = Double.NaN,
    val tirePressureBar: Double,
    val estimatedRangeKm: Double = Double.NaN,
    val remainingRangeKm: Double = Double.NaN,
    val speedTiltBackKmh: Double = Double.NaN,
    val speedWarningKmh: Double = Double.NaN,
    val outputTiltBackPercent: Double = Double.NaN,
    val outputWarningPercent: Double = Double.NaN,
    val busCurrentTiltBackAmps: Double = Double.NaN,
    val busCurrentWarningAmps: Double = Double.NaN,
    val phaseCurrentLimitAmps: Double = Double.NaN,
    val currentLimit: Double = Double.NaN,
    val mosTemperature: Double,
    val motorTemperature: Double,
    val boardTemperature: Double,
    val cpuTemperature: Double = Double.NaN,
    val imuTemperature: Double = Double.NaN,
    val lampTemperature: Double = Double.NaN,
    val batteryTemperature: Double = Double.NaN,
    val batteryMaxCellTemperature: Double = Double.NaN,
    val batteryMaxBmsTemperature: Double = Double.NaN,
    val motorEnabled: Double = Double.NaN,
    val charging: Double = Double.NaN,
)

data class InmotionProtocolUpdate(
    /** The last command answered in the chunk. Kept so the realtime ping-pong reads one value. */
    val responseCommand: Int,
    val telemetry: InmotionTelemetry?,
    val bmsSnapshot: InmotionBmsSnapshot?,
    val diagnosticsSnapshot: InmotionDiagnosticsSnapshot? = null,
    /**
     * Every command answered in this chunk, in arrival order. One notification can carry more
     * than one complete frame - a V11 sends its settings block and a realtime frame in the same
     * 109 bytes - and a caller that reacts to a once-per-session answer has to see it even when
     * a later frame shares the notification.
     */
    val responseCommands: List<Int> = listOf(responseCommand),
)

data class InmotionBmsBattery(
    val index: Int,
    val detected: Boolean,
    val enabled: Boolean,
    val charging: Boolean,
    val voltage: Double,
    val chargeCurrent: Double,
    val dischargeCurrent: Double,
    val hasFault: Boolean,
    val rawData: ByteArray = byteArrayOf(),
)

data class InmotionBmsSnapshot(
    val profileId: String,
    val modelName: String,
    val batteries: List<InmotionBmsBattery>,
)

data class InmotionDiagnosticsSnapshot(
    val command: Int,
    val errorCode: Long,
    val payload: ByteArray,
    val decodedItems: List<WheelDiagnosticItem>,
) {
    val activeFlagCount: Int
        get() = decodedItems.count { it.isActive }
}

/**
 * EUC settings (command 0x08) writable fields. Offsets and command ids are
 * confirmed against the decompiled official app; see docs/inmotion-protocol-notes.md.
 * V12-family (HS/HT/PRO/S) and V14-family (50GB/50S, plus P6 by inference) use
 * different byte layouts internally — see [InmotionProtocolEngine.settingsCommand]'s
 * private parsers — but expose overlapping keys where the underlying setting is
 * the same logical field (e.g. auto-light thresholds).
 */
data class InmotionSettingCapability(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    val rawValue: Int,
    val sourceOffset: Int,
    val commandId: Int,
    val minValue: Int,
    val maxValue: Int,
    /** `"toggle"`, `"slider"` or [KIND_READONLY] - fields we can decode but must not write. */
    val kind: String,
)

/**
 * Marks a capability that is decoded for display only - used where a model's write command
 * id or payload shape is not resolved, or a model does not answer the read this capability
 * would need to seed a paired write.
 *
 * This is not a safety gate. LoEUC's only standing never-write policy is gyro/IMU
 * calibration, which has no capability entry at all because it is an action, not a value
 * (see AGENTS.md). Everything else marked [KIND_READONLY] is display-only purely because its
 * command id or bounds are not established for that model yet - check each model's
 * `to*SettingCapabilities()` for what is and is not resolved there.
 */
const val KIND_READONLY = "readonly"

/**
 * Stateful Inmotion V2 stream decoder used by both platform BLE transports.
 *
 * Values unavailable for a model are represented as NaN so Kotlin/Native exports
 * scalar Doubles to Swift without nullable-number wrappers.
 */
class InmotionProtocolEngine {
    private var buffer = byteArrayOf()
    private var modelId: Int? = null
    private var settings = emptyList<InmotionSettingCapability>()
    private var mainBoardVersion: InmotionMainBoardVersion? = null

    fun reset() {
        buffer = byteArrayOf()
        modelId = null
        settings = emptyList()
        mainBoardVersion = null
    }

    fun mainInfoCommand(): ByteArray =
        buildMessage(flag = FLAG_INITIAL, command = COMMAND_MAIN_INFO, data = byteArrayOf(0x01))

    /**
     * Same `0x02` main-info command as [mainInfoCommand], different selector: `data = {0x06}`
     * instead of `{0x01}` asks for the version block (main-board, driver-board and BLE
     * firmware) rather than the model block. `payload[0] == 0x06` is what tells [consume]
     * which of the two came back - both answer on the same command byte, so nothing else
     * distinguishes them on the wire.
     */
    fun versionInfoCommand(): ByteArray =
        buildMessage(flag = FLAG_INITIAL, command = COMMAND_MAIN_INFO, data = byteArrayOf(0x06))

    fun realtimeCommand(): ByteArray =
        buildMessage(flag = FLAG_DEFAULT, command = COMMAND_REALTIME, data = byteArrayOf())

    fun settingsCommand(): ByteArray =
        buildMessage(flag = FLAG_DEFAULT, command = COMMAND_SETTINGS, data = byteArrayOf())

    /**
     * P6 does not answer the `0x08` settings read at all - no `0x88` response appears in
     * any captured P6 session. The current official app reads P6 settings through
     * `getSettingsAll` (`settingsAllCmd = 0x20`, a literal in `LorinCmd`) and parses the
     * reply with `P6Settings.fromBytes`, whose layout carries six extra leading bytes
     * relative to the V13/V14 `0x08` block. See docs/inmotion-settings-commands.md.
     *
     * Sent unconditionally next to [settingsCommand] because the model id is not known
     * until main info comes back; a wheel that does not implement the read just does not
     * answer it, and only P6 and V11 responses are parsed (see [consume]).
     *
     * Goes out as `AA AA 14 02 20 20`, two command bytes - not the one-byte `AA AA 14 01
     * 20 35` LoEUC sent until now, which five real captures (two P6, a V14S, a V11)
     * answered exactly never while every command below `0x20` from the same burst was
     * answered. See [encodeLorinCommand] for where the second byte comes from.
     */
    fun settingsAllCommand(): ByteArray =
        buildMessage(flag = FLAG_DEFAULT, command = COMMAND_SETTINGS_ALL, data = byteArrayOf())

    /**
     * The reads to send after a write so the screen shows what the wheel applied rather than
     * what was asked for.
     *
     * A wheel volunteers its settings block exactly once, in answer to the connect burst: the
     * V11 capture `loeuc_dump_1786731914.jsonl` has a single `0x20` answer at t+32 ms and none
     * over the next 92 seconds, across four Control(`0x60`) writes. Without this the whole
     * screen keeps its pre-write values until the next connect, and the Write button - which
     * enables on "selected differs from current" - goes dead for exactly the value the wheel
     * has just moved away from.
     *
     * Both reads go out for the reason [settingsAllCommand] already gives: which one a model
     * answers is model business (V11 and P6 answer only `0x20`, V12/V13/V14 only `0x08`), and
     * a wheel that does not implement one simply does not answer it. Same pair as the connect
     * burst, so nothing new reaches the wire here.
     */
    fun settingsReadCommands(): List<ByteArray> = listOf(settingsCommand(), settingsAllCommand())

    fun settingsCapabilities(): List<InmotionSettingCapability> = settings

    /**
     * Builds a write command for a V12 setting exposed by [settingsCapabilities].
     * The auto-light-threshold and beam-brightness fields are packed low/high pairs
     * on the wire, so writing one field re-sends the other field's last known value
     * to avoid clobbering it.
     */
    @ExperimentalWriteApi
    fun buildSettingCommand(key: String, value: Int): ByteArray? {
        val capability = settings.firstOrNull { it.key == key } ?: return null
        if (capability.kind == KIND_READONLY) {
            return null
        }
        if (value !in capability.minValue..capability.maxValue) {
            return null
        }
        fun otherValue(otherKey: String) = settings.firstOrNull { it.key == otherKey }?.rawValue ?: 0
        return when (key) {
            KEY_AUTO_LIGHT_STATE ->
                buildControlCommand(0x2F, byteArrayOf(value.toByte()))
            KEY_RIDE_MODE ->
                buildControlCommand(SUB_RIDE_MODE, byteArrayOf(value.toByte()))
            KEY_PEDAL_SENSITIVITY_1 ->
                buildControlCommand(
                    SUB_PEDAL_SENSITIVITY,
                    byteArrayOf(value.toByte(), otherValue(KEY_PEDAL_SENSITIVITY_2).toByte()),
                )
            KEY_PEDAL_SENSITIVITY_2 ->
                buildControlCommand(
                    SUB_PEDAL_SENSITIVITY,
                    byteArrayOf(otherValue(KEY_PEDAL_SENSITIVITY_1).toByte(), value.toByte()),
                )
            KEY_VOICE_VOLUME ->
                buildControlCommand(SUB_VOLUME, byteArrayOf(value.toByte()))
            KEY_LIGHT_EFFECT_MODE ->
                buildControlCommand(SUB_LIGHT_EFFECT_MODE, byteArrayOf(value.toByte()))
            KEY_DRL_STATE ->
                // V11 and P6 disagree on the DRL subcommand id - see SUB_DRL_V11's doc.
                buildControlCommand(
                    if (modelId == MODEL_V11) SUB_DRL_V11 else SUB_DRL_P6,
                    byteArrayOf(value.toByte()),
                )
            KEY_BERM_ANGLE_MODE ->
                buildControlCommand(SUB_BERM_ANGLE_MODE_P6, byteArrayOf(value.toByte()))
            KEY_SPEED_LIMIT ->
                buildControlCommand(SUB_SPEED_LIMIT, int16LeBytes(value * SPEED_SCALE))
            KEY_SPEED_WARNING_1 ->
                buildControlCommand(
                    SUB_WARNING_SPEED,
                    int16LeBytes(value * SPEED_SCALE) + int16LeBytes(otherValue(KEY_SPEED_WARNING_2) * SPEED_SCALE),
                )
            KEY_SPEED_WARNING_2 ->
                buildControlCommand(
                    SUB_WARNING_SPEED,
                    int16LeBytes(otherValue(KEY_SPEED_WARNING_1) * SPEED_SCALE) + int16LeBytes(value * SPEED_SCALE),
                )
            KEY_PITCH_ZERO ->
                buildControlCommand(SUB_BALANCE_ANGLE, int16LeBytes(value * PITCH_SCALE))
            KEY_AUTO_LIGHT_LOW_THR ->
                buildControlCommand(0x2A, byteArrayOf(value.toByte(), otherValue(KEY_AUTO_LIGHT_HIGH_THR).toByte()))
            KEY_AUTO_LIGHT_HIGH_THR ->
                buildControlCommand(0x2A, byteArrayOf(otherValue(KEY_AUTO_LIGHT_LOW_THR).toByte(), value.toByte()))
            KEY_LOW_BEAM_BRIGHTNESS ->
                buildControlCommand(0x2B, byteArrayOf(value.toByte(), otherValue(KEY_HIGH_BEAM_BRIGHTNESS).toByte()))
            KEY_HIGH_BEAM_BRIGHTNESS ->
                buildControlCommand(0x2B, byteArrayOf(otherValue(KEY_LOW_BEAM_BRIGHTNESS).toByte(), value.toByte()))
            KEY_LIGHT_BRIGHTNESS ->
                buildControlCommand(0x2B, byteArrayOf(value.toByte()))
            KEY_AUDIO_SWITCH ->
                buildControlCommand(SUB_VOICE_SWITCH, byteArrayOf(value.toByte()))
            KEY_AUTO_CLOSE_SCREEN ->
                buildControlCommand(SUB_DISPLAY_AUTO_OFF, byteArrayOf(value.toByte()))
            KEY_LOGO_LIGHT_BRIGHTNESS ->
                buildControlCommand(SUB_LOGO_LIGHT, byteArrayOf(value.toByte()))
            KEY_BERM_ANGLE ->
                buildControlCommand(SUB_BERM_ANGLE, byteArrayOf(value.toByte()))
            KEY_TAIL_LIGHT_MODE ->
                buildControlCommand(SUB_TAIL_LIGHT_MODE, byteArrayOf(value.toByte()))
            KEY_TURN_LIGHT_MODE ->
                buildControlCommand(SUB_TURN_LIGHT_MODE, byteArrayOf(value.toByte()))
            KEY_TPMS_LOW_ALARM ->
                buildControlCommand(SUB_TPMS_LOW_ALARM, int16LeBytes(value * TPMS_SCALE))
            KEY_NO_LOAD_DETECT ->
                buildControlCommand(SUB_NO_LOAD_DETECT, byteArrayOf(value.toByte()))
            KEY_ACCEL_ASSIST ->
                buildControlCommand(
                    SUB_ACCEL_BRAKE_ASSIST,
                    byteArrayOf(value.toByte(), otherValue(KEY_BRAKE_ASSIST).toByte()),
                )
            KEY_BRAKE_ASSIST ->
                buildControlCommand(
                    SUB_ACCEL_BRAKE_ASSIST,
                    byteArrayOf(otherValue(KEY_ACCEL_ASSIST).toByte(), value.toByte()),
                )
            KEY_LOCK_MODE ->
                buildControlCommand(SUB_LOCK_MODE, byteArrayOf(value.toByte()))
            KEY_TRANSPORT_MODE ->
                buildControlCommand(SUB_TRANSPORT_MODE, byteArrayOf(value.toByte()))
            KEY_LIFT_UP_DETECTION ->
                buildControlCommand(SUB_LIFT_UP_DETECTION, byteArrayOf(value.toByte()))
            KEY_LOW_BATTERY_RIDE ->
                buildControlCommand(SUB_LOW_BATTERY_RIDE, byteArrayOf(value.toByte()))
            KEY_STANDBY_TIME ->
                buildControlCommand(SUB_STANDBY_TIME, int16LeBytes(value * STANDBY_SCALE))
            else -> null
        }
    }

    @ExperimentalWriteApi
    fun v12LowLightCommand(enabled: Boolean): ByteArray =
        buildControlCommand(0x50, byteArrayOf(if (enabled) 1 else 0, 0))

    @ExperimentalWriteApi
    fun v12HighLightCommand(enabled: Boolean): ByteArray =
        buildControlCommand(0x50, byteArrayOf(0, if (enabled) 1 else 0))

    @ExperimentalWriteApi
    fun v12AutoLightCommand(enabled: Boolean): ByteArray =
        buildControlCommand(0x2F, byteArrayOf(if (enabled) 1 else 0))

    @ExperimentalWriteApi
    fun v12PowerOffInitialCommand(): ByteArray = buildControlCommand(0x81, byteArrayOf(0))

    @ExperimentalWriteApi
    fun v12PowerOffFinalCommand(): ByteArray = buildControlCommand(0x82, byteArrayOf())

    /**
     * V11's headlight on/off quick action. The Control(0x60) subcommand forks on the wheel's
     * own main-board firmware: `0x40` below version 1.4, `0x50` at 1.4 and up - cross-checked
     * against WheelLog, whose equivalent test is `major < 2 && minor < 4`. Returns null rather
     * than guessing when [versionInfoCommand] has not been answered yet; a caller must not
     * send either byte on a hunch.
     */
    fun v11HeadlightCommand(enabled: Boolean): ByteArray? {
        val version = mainBoardVersion ?: return null
        val subcommand = if (version.major < 2 && version.minor < 4) {
            HEADLIGHT_SUB_PRE_1_4
        } else {
            HEADLIGHT_SUB_1_4_PLUS
        }
        return buildControlCommand(subcommand, byteArrayOf(if (enabled) 1 else 0))
    }

    /** Whether [v11HeadlightCommand] has what it needs, so a caller can gate UI on it. */
    fun mainBoardVersionKnown(): Boolean = mainBoardVersion != null

    fun bmsRealtimeCommand(): ByteArray? =
        if (modelId == MODEL_P6) {
            buildMessage(flag = FLAG_DEFAULT, command = COMMAND_BATTERY_REALTIME, data = byteArrayOf())
        } else {
            null
        }

    fun bmsReadCommands(): List<ByteArray> {
        if (modelId != MODEL_P6) {
            return emptyList()
        }
        return buildList {
            add(bmsRealtimeCommand()!!)
            DIRECT_BMS_ADDRESSES.forEach { address ->
                DIRECT_BMS_SELECTORS.forEach { selector ->
                    // cmd byte carries no 0x80: that bit marks a write. The target
                    // goes on the wire unshifted - TargetDeviceType stores 0x24..0x27
                    // directly. See buildInmotionDirectReadMessage for the derivation.
                    add(
                        buildMessage(
                            flag = FLAG_MANY_TO_MANY,
                            command = 0x02,
                            data = byteArrayOf(address.toByte(), selector.toByte()),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Every complete frame in [chunk] is decoded, and what they produced is merged into one
     * update. Merging, rather than keeping the last frame's result, is what a shared notification
     * requires: a V11 answers its settings read and a realtime read in the same 109 bytes, and
     * the settings block is read once per session - reported as a realtime answer, it is gone.
     */
    fun consume(chunk: ByteArray): InmotionProtocolUpdate? {
        buffer += chunk
        val accumulator = FrameAccumulator()

        while (true) {
            val start = buffer.indexOfHeader()
            if (start < 0) {
                buffer = buffer.takeLast(1).toByteArray()
                return accumulator.merged()
            }
            if (start > 0) {
                buffer = buffer.copyOfRange(start, buffer.size)
            }

            val wireEnd = buffer.v2WireFrameEnd() ?: return accumulator.merged()
            val frame = buffer.copyOfRange(0, wireEnd).unescaped()
            buffer = buffer.copyOfRange(wireEnd, buffer.size)
            decodeFrame(frame, accumulator)
        }
    }

    /**
     * Decodes one complete frame that has already been framed and unescaped.
     *
     * [consume] is for the wire: it buffers, finds frame boundaries and strips the `0xA5`
     * escape. A caller that replays frames a capture store already holds has had all three done
     * for it, and putting those bytes back through [consume] unescapes them a second time - a
     * payload byte that happens to equal `0xA5` then eats the byte behind it, the frame comes up
     * one short of its own length field, and it is never completed. That is not a rare corner: a
     * P6 whose ride-battery reading sat at 93.81 % (`0x24A5`) put that byte in every realtime
     * frame of a 70-second capture, and the whole dashboard stopped updating.
     *
     * The frame is still validated here - header, declared length and checksum - so a caller
     * cannot smuggle in something the wire path would have rejected.
     */
    fun consumeFrame(frame: ByteArray): InmotionProtocolUpdate? {
        val accumulator = FrameAccumulator()
        decodeFrame(frame, accumulator)
        return accumulator.merged()
    }

    /** What one [consume] call collected across the frames it completed. */
    private class FrameAccumulator {
        val commands = mutableListOf<Int>()
        var telemetry: InmotionTelemetry? = null
        var bms: InmotionBmsSnapshot? = null
        var diagnostics: InmotionDiagnosticsSnapshot? = null

        fun merged(): InmotionProtocolUpdate? = commands.lastOrNull()?.let { last ->
            InmotionProtocolUpdate(
                responseCommand = last,
                telemetry = telemetry,
                bmsSnapshot = bms,
                diagnosticsSnapshot = diagnostics,
                responseCommands = commands.toList(),
            )
        }
    }

    private fun decodeFrame(frame: ByteArray, accumulator: FrameAccumulator) {
        if (!frame.isValidV2Frame()) {
            return
        }

        val command = frame.u8(4) and 0x7F
        val data = frame.v2Data()
        if (command == COMMAND_MAIN_INFO) {
            // The version block answers on this same command byte (see
            // [versionInfoCommand]) and does not start with the model block's `0x01`
            // marker, so `data.modelId()` correctly returns null for it - `?.let` is what
            // keeps that null from clobbering an already-known modelId back to unknown.
            data.modelId()?.let { modelId = it }
            data.toMainBoardVersion()?.let { mainBoardVersion = it }
        }
        if (command == COMMAND_SETTINGS) {
            settings = when {
                modelId in V12_MODELS -> data.toV12SettingCapabilities()
                modelId in V14_MODELS || modelId == MODEL_P6 -> data.toV14SettingCapabilities()
                modelId == MODEL_V11 -> data.toV11SettingCapabilities()
                else -> emptyList()
            }
        }
        // Which of the two reads a wheel answers is model business: P6 answers only
        // `0x20`, and V11 is not known to answer either yet, so its block is parsed
        // from whichever one comes back.
        if (command == COMMAND_SETTINGS_ALL) {
            val parsed = when {
                modelId == MODEL_P6 -> data.toP6SettingCapabilities()
                modelId == MODEL_V11 -> data.toV11SettingCapabilities()
                else -> emptyList()
            }
            parsed.takeIf { it.isNotEmpty() }?.let { settings = it }
        }
        accumulator.commands += command
        if (command == COMMAND_REALTIME) {
            data.toTelemetry(modelId)?.let { accumulator.telemetry = it }
        }
        if (command == COMMAND_BATTERY_REALTIME && modelId == MODEL_P6) {
            data.toP6BmsSnapshot()?.let { accumulator.bms = it }
        }
        if (command == COMMAND_REALTIME_ERROR) {
            data.toDiagnosticsSnapshot()?.let { accumulator.diagnostics = it }
        }
    }

    fun decodeBatteryDiagnostics(payload: ByteArray): List<InmotionBmsBattery> {
        return payload.toBmsBatteries(filterEmpty = false)
    }

    private fun ByteArray.toP6BmsSnapshot(): InmotionBmsSnapshot? {
        val batteries = toBmsBatteries(filterEmpty = true)
        return batteries.takeIf { it.isNotEmpty() }?.let {
            InmotionBmsSnapshot(
                profileId = "inmotion_p6",
                modelName = "Inmotion P6",
                batteries = it,
            )
        }
    }

    /**
     * Command `0x05` battery packs, from the current InMotion Pro app
     * (`lorin/realtime/battery/c10_realtime_battery_info.dart`,
     * `C10BatteryPackInfo.fromBytes`, which every lorin model reuses):
     * `voltage u16le @0`, `chargeCurrent i16le @2`, `dischargeCurrent i16le @4`
     * and a `u16le` flag word `@6` - **eight bytes per pack, exactly two
     * packs**, with the payload rejected below sixteen bytes.
     *
     * The previous sixteen-byte-by-four layout came from the legacy SCV app's
     * V14 reverse. Every model parser in the current app - v11, v12, v12s, v13,
     * v14, s1 and p6 alike - emits the same `cmp #0x10` / `cmp #2` / `lsl #3`
     * sequence, so pack 2 was being read from offset 16 (past the payload)
     * while its real bytes at offset 8 were never looked at.
     */
    private fun ByteArray.toBmsBatteries(filterEmpty: Boolean): List<InmotionBmsBattery> {
        if (size < BmsPackSize * BmsPackCount) {
            return emptyList()
        }
        return (0 until BmsPackCount).mapNotNull { packIndex ->
            val offset = packIndex * BmsPackSize
            val flags = uint16Le(offset + 6)
            val voltageRaw = uint16Le(offset)
            val fault = (BmsFlagErrorLogic..BmsFlagBatteryError).any { flags.bit(it) }
            val detected = flags.bit(BmsFlagStateDetected)
            // An absent pack still occupies its eight bytes and they are not
            // cleared - a real single-battery P6 reports `1E 02 00 …` in slot 2 -
            // so presence is decided by the flag word, not by the numbers.
            if (filterEmpty && !detected && !fault) {
                return@mapNotNull null
            }
            InmotionBmsBattery(
                index = packIndex + 1,
                detected = detected,
                enabled = flags.bit(BmsFlagStateEnable),
                charging = flags.bit(BmsFlagStateCharge),
                voltage = voltageRaw / 100.0,
                chargeCurrent = int16Le(offset + 2) / 100.0,
                dischargeCurrent = int16Le(offset + 4) / 100.0,
                hasFault = fault,
                rawData = copyOfRange(offset, offset + BmsPackSize),
            )
        }
    }

    private fun ByteArray.toDiagnosticsSnapshot(): InmotionDiagnosticsSnapshot {
        val errorCode = if (size >= 4) {
            u8(0).toLong() or
                (u8(1).toLong() shl 8) or
                (u8(2).toLong() shl 16) or
                (u8(3).toLong() shl 24)
        } else {
            0L
        }
        return InmotionDiagnosticsSnapshot(
            command = COMMAND_REALTIME_ERROR,
            errorCode = errorCode,
            payload = copyOf(),
            decodedItems = decodeDiagnostics(),
        )
    }

    private fun ByteArray.toTelemetry(modelId: Int?): InmotionTelemetry? {
        if (size < 38) {
            return null
        }
        return when {
            modelId == MODEL_P6 && size >= 54 -> toV14FamilyTelemetry("inmotion_p6", hasTirePressure = true)
            modelId in V14_MODELS && size >= 55 -> toV14FamilyTelemetry("inmotion_v14")
            modelId in V13_MODELS && size >= 55 -> toV14FamilyTelemetry("inmotion_v13")
            modelId in V12_MODELS && size >= 47 -> toV12Telemetry()
            modelId == MODEL_V11 && size < 51 -> toV11LegacyTelemetry()
            modelId == MODEL_V11 && size >= 58 -> toV11NewTelemetry()
            modelId == null && size < 51 -> toV11LegacyTelemetry()
            size >= 65 -> toV14FamilyTelemetry("inmotion_v14")
            size >= 47 -> toV12Telemetry()
            else -> null
        }
    }

    private fun ByteArray.toV14FamilyTelemetry(
        profileId: String,
        hasTirePressure: Boolean = false,
    ): InmotionTelemetry {
        val thresholdOffset = 40
        val temperatureOffset = 58
        val rideBatteryPercent = uint16Le(36) / 100.0
        val estimatedRangeKm = uint16Le(38) / 100.0
        val state = if (size > 74) u8(74) else -1
        return InmotionTelemetry(
            profileId = profileId,
            speedKmh = int16Le(8) / 100.0,
            pwmPercent = pwmPercent(14),
            voltage = uint16Le(0) / 100.0,
            batteryCurrent = int16Le(2) / 100.0,
            phaseCurrent = int16Le(2) / 100.0,
            power = int16Le(16).toDouble(),
            motorPower = int16Le(18).toDouble(),
            batteryPercent = uint16Le(34).div(100.0).coerceIn(0.0, 100.0),
            rideBatteryPercent = rideBatteryPercent,
            tripDistanceMeters = uint16Le(28) * 10.0,
            torque = int16Le(12) / 100.0,
            pitchAngle = int16Le(20) / 100.0,
            lateralAngle = int16Le(22) / 100.0,
            targetAngle = int16Le(24) / 100.0,
            // `charge_voltage` is tenths of a volt, not hundredths like `bus_voltage` two
            // fields earlier. A P6 on the charger reported 232.4 V while this read out as
            // 23.24 - and 23 V is not a voltage a 232 V pack can charge at, so the scale is
            // the only thing that can be wrong. `charge_current` right after it is left at
            // hundredths: no capture has ever caught either field non-zero (they are a hard
            // zero in all 90 realtime frames of the 2026-08-13 P6 dump), so there is nothing
            // to check the second one against yet.
            chargeVoltage = uint16Le(4) / 10.0,
            chargeCurrent = int16Le(6) / 100.0,
            acceleration = int16Le(10) / 100.0,
            speedingBrakingAngle = int16Le(26) / 100.0,
            tirePressureBar = if (hasTirePressure) uint16Le(30) / 100.0 else Double.NaN,
            estimatedRangeKm = estimatedRangeKm,
            remainingRangeKm = rideBatteryPercent * estimatedRangeKm / 100.0,
            speedTiltBackKmh = uint16Le(thresholdOffset) / 100.0,
            speedWarningKmh = uint16Le(thresholdOffset + 2) / 100.0,
            outputTiltBackPercent = uint16Le(thresholdOffset + 4) / 100.0,
            outputWarningPercent = uint16Le(thresholdOffset + 6) / 100.0,
            busCurrentTiltBackAmps = uint16Le(thresholdOffset + 8) / 100.0,
            busCurrentWarningAmps = uint16Le(thresholdOffset + 10) / 100.0,
            phaseCurrentLimitAmps = uint16Le(thresholdOffset + 12) / 100.0,
            mosTemperature = presentTemperature(temperatureOffset),
            motorTemperature = presentTemperature(temperatureOffset + 1),
            boardTemperature = presentTemperature(temperatureOffset + 3),
            cpuTemperature = presentTemperature(temperatureOffset + 4),
            imuTemperature = presentTemperature(temperatureOffset + 5),
            lampTemperature = presentTemperature(temperatureOffset + 6),
            batteryTemperature = presentTemperature(temperatureOffset + 2),
            batteryMaxCellTemperature = presentTemperature(temperatureOffset + 7),
            batteryMaxBmsTemperature = presentTemperature(temperatureOffset + 8),
            motorEnabled = if (state >= 0) if (state and 0x40 != 0) 1.0 else 0.0 else Double.NaN,
            charging = if (state >= 0) if (state and 0x80 != 0) 1.0 else 0.0 else Double.NaN,
        )
    }

    private fun ByteArray.toV12Telemetry(): InmotionTelemetry {
        return InmotionTelemetry(
            profileId = "inmotion_v12",
            speedKmh = int16Le(4) / 100.0,
            pwmPercent = pwmPercent(8),
            voltage = uint16Le(0) / 100.0,
            phaseCurrent = int16Le(2) / 100.0,
            batteryCurrent = int16Le(2) / 100.0,
            power = int16Le(10).toDouble(),
            motorPower = Double.NaN,
            batteryPercent = uint16Le(24) / 100.0,
            rideBatteryPercent = Double.NaN,
            tripDistanceMeters = uint16Le(22) * 10.0,
            temperature = temperature(40),
            tirePressureBar = Double.NaN,
            mosTemperature = Double.NaN,
            motorTemperature = Double.NaN,
            boardTemperature = Double.NaN,
            lateralAngle = int16Le(20) / 100.0,
            currentLimit = uint16Le(32) / 100.0,
            speedTiltBackKmh = uint16Le(30) / 100.0,
        )
    }

    private fun ByteArray.toV11LegacyTelemetry(): InmotionTelemetry {
        val voltage = uint16Le(0) / 100.0
        val current = int16Le(2) / 100.0
        return InmotionTelemetry(
            profileId = "inmotion_v11",
            speedKmh = int16Le(4) / 100.0,
            pwmPercent = pwmPercent(36),
            voltage = voltage,
            phaseCurrent = current,
            batteryCurrent = current,
            power = int16Le(8).toDouble(),
            motorPower = Double.NaN,
            batteryPercent = (u8(16) and 0x7F).toDouble(),
            rideBatteryPercent = Double.NaN,
            tripDistanceMeters = uint16Le(12) * 10.0,
            temperature = temperature(17),
            tirePressureBar = Double.NaN,
            mosTemperature = Double.NaN,
            motorTemperature = Double.NaN,
            boardTemperature = Double.NaN,
            pitchAngle = int16Le(22) / 100.0,
            lateralAngle = int16Le(26) / 100.0,
            speedTiltBackKmh = uint16Le(28) / 100.0,
            currentLimit = uint16Le(30) / 100.0,
        )
    }

    /**
     * V11 on main-board firmware 1.4 and up, which is the only V11 realtime layout the
     * current app carries: `V11RealTimeInfo.fromBytes`
     * (`lorin/realtime/info/v11_realtime_info.dart`, 0x8ab6a0) rejects payloads below 56
     * bytes and reads `bus_voltage`, `bus_current`, `speed`, `torque`, `output_rate`,
     * `battery_output_power` and `motor_output_power` as seven signed 16-bit values from
     * offset 0, then the seven temperature bytes from 42 - `mos`, `motor`, `battery`,
     * `board`, `cpu`, `imu`, `lamp` in that order, each `+ 80 - 256`.
     *
     * Torque, motor power and the temperatures are what this adds over the fields LoEUC
     * already read. The block between the angles and the temperatures holds three more
     * slots than `toMap` has names for, so the offsets already in use there are left as
     * they are rather than re-guessed.
     */
    private fun ByteArray.toV11NewTelemetry(): InmotionTelemetry {
        val current = int16Le(2) / 100.0
        return InmotionTelemetry(
            profileId = "inmotion_v11",
            speedKmh = int16Le(4) / 100.0,
            pwmPercent = pwmPercent(8),
            voltage = uint16Le(0) / 100.0,
            phaseCurrent = current,
            batteryCurrent = current,
            power = int16Le(10).toDouble(),
            motorPower = int16Le(12).toDouble(),
            batteryPercent = uint16Le(28) / 100.0,
            rideBatteryPercent = Double.NaN,
            tripDistanceMeters = uint16Le(26) * 10.0,
            // Torque in N*m, confirmed against hardware: over the 53 loaded frames of the
            // spin-up capture (loeuc_dump_1786714254.jsonl) this tracks motorPower/speed at
            // r=1.000, and the fitted slope is 0.249 m - the rolling radius of the V11's 18"
            // wheel. That is the tau = P/omega identity, which nothing but torque satisfies.
            //
            // A first reading of the same capture took it for acceleration: it correlates with
            // dv/dt too. That is a confound of spinning the wheel in the air, where tau = J*alpha
            // makes the two proportional. The standstill wobble is not counter-evidence either -
            // the balance loop keeps applying torque, and offset 2 is averaged battery current,
            // not phase current.
            torque = int16Le(6) / 100.0,
            temperature = temperature(42),
            tirePressureBar = Double.NaN,
            mosTemperature = presentTemperature(42),
            // `0xB0` means "no sensor" here, not 0 C. It is the only value byte 43 has ever
            // carried on a V11 - constant across both captures, through a spin-up to 78 km/h
            // and the braking after it, while the four live sensors move - and the official
            // Inmotion app shows no motor temperature for this wheel at all. Through the shared
            // offset it would read as exactly 0 C next to a 22 C MOSFET, because
            // `presentTemperature` only discards a raw zero.
            //
            // Scoped to that one value rather than blanked outright: a V11 that does carry the
            // sensor still reports it, and 0 C is a real reading for a wheel left out in winter,
            // so the sentinel must not be filtered for every model.
            motorTemperature = if (u8(43) == 0xB0) Double.NaN else presentTemperature(43),
            batteryTemperature = presentTemperature(44),
            boardTemperature = presentTemperature(45),
            cpuTemperature = presentTemperature(46),
            imuTemperature = presentTemperature(47),
            lampTemperature = presentTemperature(48),
            pitchAngle = int16Le(16) / 100.0,
            lateralAngle = int16Le(20) / 100.0,
            speedTiltBackKmh = uint16Le(34) / 100.0,
            currentLimit = uint16Le(36) / 100.0,
        )
    }

    fun v2Data(frame: ByteArray): ByteArray = frame.v2Data()
}

private const val FLAG_INITIAL = 0x11
private const val FLAG_DEFAULT = 0x14
private const val FLAG_MANY_TO_MANY = 0x16
private const val COMMAND_MAIN_INFO = 0x02
private const val COMMAND_REALTIME_ERROR = 0x03
private const val COMMAND_REALTIME = 0x04
private const val COMMAND_BATTERY_REALTIME = 0x05
private const val COMMAND_SETTINGS = 0x08
private const val COMMAND_SETTINGS_ALL = 0x20
private const val COMMAND_CONTROL = 0x60
internal const val KEY_AUTO_LIGHT_STATE = "inmotion_auto_light_state"
internal const val KEY_AUTO_LIGHT_LOW_THR = "inmotion_auto_light_low_thr"
internal const val KEY_AUTO_LIGHT_HIGH_THR = "inmotion_auto_light_high_thr"
internal const val KEY_LOW_BEAM_BRIGHTNESS = "inmotion_low_beam_brightness"
internal const val KEY_HIGH_BEAM_BRIGHTNESS = "inmotion_high_beam_brightness"
internal const val KEY_LIGHT_BRIGHTNESS = "inmotion_light_brightness"
internal const val KEY_RIDE_MODE = "inmotion_ride_mode"
internal const val KEY_PEDAL_SENSITIVITY_1 = "inmotion_pedal_sensitivity_1"
internal const val KEY_PEDAL_SENSITIVITY_2 = "inmotion_pedal_sensitivity_2"
internal const val KEY_VOICE_VOLUME = "inmotion_voice_volume"
internal const val KEY_LIGHT_EFFECT_MODE = "inmotion_light_effect_mode"
internal const val KEY_DRL_STATE = "inmotion_drl_state"
internal const val KEY_BERM_ANGLE_MODE = "inmotion_berm_angle_mode"
internal const val KEY_SPEED_LIMIT = "inmotion_speed_limit"
internal const val KEY_SPEED_WARNING_1 = "inmotion_speed_warning_level_1"
internal const val KEY_SPEED_WARNING_2 = "inmotion_speed_warning_level_2"
internal const val KEY_PITCH_ZERO = "inmotion_pitch_zero"
internal const val KEY_AUDIO_SWITCH = "inmotion_audio_switch"
internal const val KEY_AUTO_CLOSE_SCREEN = "inmotion_auto_close_screen"
internal const val KEY_LOW_BATTERY_RIDE = "inmotion_low_battery_ride"
internal const val KEY_LOGO_LIGHT_BRIGHTNESS = "inmotion_logo_light_brightness"
internal const val KEY_BERM_ANGLE = "inmotion_berm_angle"
internal const val KEY_TAIL_LIGHT_MODE = "inmotion_tail_light_mode"
internal const val KEY_TURN_LIGHT_MODE = "inmotion_turn_light_mode"
internal const val KEY_TPMS_LOW_ALARM = "inmotion_tpms_low_alarm_threshold"
internal const val KEY_NO_LOAD_DETECT = "inmotion_no_load_detect"
internal const val KEY_QUIET_FAN = "inmotion_fan_mute_mode"
internal const val KEY_FAN = "inmotion_fan_status"
internal const val KEY_ACCEL_ASSIST = "inmotion_speeding_feedback"
internal const val KEY_BRAKE_ASSIST = "inmotion_braking_feedback"
internal const val KEY_LOCK_MODE = "inmotion_lock_mode"
internal const val KEY_TRANSPORT_MODE = "inmotion_transport_mode"
internal const val KEY_LIFT_UP_DETECTION = "inmotion_lift_up_detection"
internal const val KEY_STANDBY_TIME = "inmotion_standby_time"

/**
 * The settings block stores speeds in hundredths of km/h and the pedal-pitch trim in
 * hundredths of a degree, matching how every angle and speed is scaled in the realtime
 * frame. Both are exposed in coarser units so the value is a usable slider and reads the
 * way the official app shows it: speeds in whole km/h, pitch trim in tenths of a degree
 * (the same unit and +-8.0 range LeaperKim's `angle_trim` uses). Writes multiply back,
 * so a wheel reporting 45.5 km/h round-trips as 45.
 */
private const val SPEED_SCALE = 100
private const val PITCH_SCALE = 10

/**
 * Upper bound for the speed limit and both warning thresholds. A P6 with the limit off
 * reports 150.00 km/h in `speedTiltBackKmh`/`speedWarningKmh` (raw 15000), which is the
 * factory "unrestricted" value, so the slider has to reach it - a lower ceiling would put
 * a stock wheel's own current value out of range and refuse to write it back.
 */
private const val SPEED_MAX_KMH = 150

/**
 * `setHeadlightBrightness` clamps to 0..100 in the official app - the same clamp `setVolume`,
 * `setPedalSensitivity` and `setAccAndBrakeAssistPercentage` carry, read out of the setter
 * bodies. Only the V11 row uses this; the legacy V12/V14 rows still advertise 0..255 because
 * that (independently corroborated) path says nothing either way.
 */
private const val LIGHT_BRIGHTNESS_MAX = 100

// Subcommand ids for the Control(0x60) family. The numbers are the official app's own
// command ids, read out of `LorinPrivateRequestBody`'s AOT code (the literal loaded into
// the `cmd:` named argument, Smi-tagged there, so the real id is half the immediate) and
// out of the `LorinCmd`/`V14Cmd` getters for the model-specific ones. The Control(0x60)
// *framing* is LoEUC's own choice, kept for consistency with the already-shipped
// 0x2A/0x2B/0x2F/0x50 writes - see docs/inmotion-protocol-notes.md.
private const val SUB_RIDE_MODE = 0x24
private const val SUB_PEDAL_SENSITIVITY = 0x25
private const val SUB_VOLUME = 0x26
private const val SUB_LIGHT_EFFECT_MODE = 0x33
private const val SUB_DRL_P6 = 0x4E
private const val SUB_BERM_ANGLE_MODE_P6 = 0x43
private const val SUB_SPEED_LIMIT = 0x21
private const val SUB_BALANCE_ANGLE = 0x22
private const val SUB_WARNING_SPEED = 0x3E

/**
 * Second batch of ids, read the same way and each one **unique** in the whole
 * `LorinPrivateRequestBody` setter table - which matters, because that table is shared
 * with scooters and several ids are reused there (`setSpeedLimit` and `setScreenBrightness`
 * both read `0x21`, `setMainSoundSwitch` and `setMaxChargeCurrent` both `0x39`). Anything
 * ambiguous is left read-only rather than guessed; that is why the charge limit, the
 * charge currents and the standby timer are still display-only.
 *
 * Payload shapes come from the same disassembly: `setVoiceSwitch`,
 * `setDisplayAutoOffSwitch`, `setLowBatteryRideSwitch`, `setLogoLightSwitch`,
 * `setBermAngle`, `setBrakeTailLightMode` and `setTurnSignalLightMode` all build a
 * one-element parameter list, the same shape as the already-shipped `0x2F` toggle;
 * `setLogoLightBrightness` clamps its value to 0..100 like `setVolume` does.
 */
private const val SUB_VOICE_SWITCH = 0x2C
private const val SUB_TURN_LIGHT_MODE = 0x30
private const val SUB_LOW_BATTERY_RIDE = 0x37
private const val SUB_BERM_ANGLE = 0x3A
private const val SUB_TAIL_LIGHT_MODE = 0x3B
private const val SUB_DISPLAY_AUTO_OFF = 0x3D
private const val SUB_LOGO_LIGHT = 0x44
private const val SUB_TPMS_LOW_ALARM = 0x4D

/**
 * V11-only ids from the same protocol facts table as the rest of this file's Control(0x60)
 * constants. `SUB_DRL_V11` deliberately does not reuse `SUB_DRL_P6` (0x4E) - the two models
 * disagree on the DRL subcommand, so [InmotionProtocolEngine.buildSettingCommand] picks
 * between them by `modelId`.
 *
 * The two fan ids that used to live here are gone. `0x43` was read as "fan" on the claim that
 * its collision with `bermAngleModeSwitchCmd` was a coincidence of two models' command tables;
 * that was a guess, `0x38` was in no table at all, and the row it wrote (`fan_status`) is a
 * status field rather than a setting. Both rows are display-only now - see
 * [toV11SettingCapabilities].
 */
private const val SUB_DRL_V11 = 0x2D
private const val SUB_NO_LOAD_DETECT = 0x36
private const val SUB_ACCEL_BRAKE_ASSIST = 0x3F
private const val SUB_LOCK_MODE = 0x31
private const val SUB_TRANSPORT_MODE = 0x32
private const val SUB_LIFT_UP_DETECTION = 0x2E
private const val SUB_STANDBY_TIME = 0x28

/** Minutes on the slider, seconds on the wire - the unit a real P6 confirmed for this field. */
private const val STANDBY_SCALE = 60
private const val STANDBY_MIN_MINUTES = 1
private const val STANDBY_MAX_MINUTES = 60

/**
 * V11's headlight quick action forks on main-board firmware. See
 * [InmotionProtocolEngine.v11HeadlightCommand].
 */
private const val HEADLIGHT_SUB_PRE_1_4 = 0x40
private const val HEADLIGHT_SUB_1_4_PLUS = 0x50

/** Tyre-pressure alarm is exposed in millibar; the block stores hundred-thousandths of a bar. */
private const val TPMS_SCALE = 10
private const val TPMS_MAX_MBAR = 5_000
private const val BERM_ANGLE_MAX_DEGREES = 90
private const val TURN_LIGHT_MODE_MAX = 4

/**
 * `Utils.intTo2Bytes`: clamp to signed 16-bit, then store little-endian. Used by
 * `setSpeedLimit`, `setWarningSpeed` and `setBalanceAngle`, matching the little-endian
 * reads of the same fields in the settings block.
 */
private fun int16LeBytes(value: Int): ByteArray {
    val clamped = value.coerceIn(-32768, 32767)
    return byteArrayOf((clamped and 0xFF).toByte(), ((clamped shr 8) and 0xFF).toByte())
}
private const val MODEL_V11 = 61
private const val MODEL_P6 = 131
private val V14_MODELS = setOf(91, 92)
private val V13_MODELS = setOf(81, 82)
private val V12_MODELS = setOf(71, 72, 73, 111)
private val DIRECT_BMS_ADDRESSES = listOf(0x24, 0x25, 0x26, 0x27, 0x32, 0x34)
private val DIRECT_BMS_SELECTORS = listOf(0x01, 0x02, 0x04)

// Command 0x05 battery pack layout, see toBmsBatteries.
private const val BmsPackSize = 8
private const val BmsPackCount = 2

// Bit positions in the pack's u16le flag word at offset 6, named after the
// official parser's toMap keys. Bits 3..5 are unused there.
private const val BmsFlagStateDetected = 0
private const val BmsFlagStateEnable = 1
private const val BmsFlagStateCharge = 2
private const val BmsFlagErrorLogic = 6

@Suppress("unused")
private const val BmsFlagErrorSignal = 7

@Suppress("unused")
private const val BmsFlagErrorVoltageSensor = 8

@Suppress("unused")
private const val BmsFlagErrorCurrentSensor = 9

@Suppress("unused")
private const val BmsFlagErrorCannotCharge = 10

@Suppress("unused")
private const val BmsFlagBatteryWarning = 11

@Suppress("unused")
private const val BmsFlagBatteryProtect = 12
private const val BmsFlagBatteryError = 13

/**
 * The command field of a Lorin request body, from `Utils.encodeCmd` (0x7a6ae4 in the
 * InMotion Pro AOT dump), reached from `LorinRequestBody.cmdList`.
 *
 * The id is written big-endian into as many bytes as it needs - one below `0x20`, two
 * below `0x1000` - and then bit `0x20` is set on the first byte whenever more than one
 * byte was used (bit `0x40` marks `needTab`, which none of these reads set, and `0x80`
 * marks a continuation byte from the third onwards). Every command LoEUC sends is below
 * `0x20` except `getSettingsAll`, so this changes exactly one request on the wire:
 * `0x20` becomes `20 20` instead of a bare `20`.
 *
 * A response repeats the same field with the read bit `0x80` set on its first byte, which
 * is why `0xA0 20` still resolves to `0x20` under the existing `and 0x7F` mask and why
 * [ByteArray.v2CommandSize] has to skip both bytes before the payload starts.
 */
private fun encodeLorinCommand(command: Int): ByteArray =
    if (command <= 0x1F) {
        byteArrayOf(command.toByte())
    } else {
        byteArrayOf((0x20 or ((command shr 8) and 0x1F)).toByte(), (command and 0xFF).toByte())
    }

private fun buildMessage(flag: Int, command: Int, data: ByteArray): ByteArray =
    buildFramedMessage(flag, encodeLorinCommand(command), data)

private fun buildFramedMessage(flag: Int, commandBytes: ByteArray, data: ByteArray): ByteArray {
    val body = commandBytes + data
    val payload = byteArrayOf(flag.toByte(), body.size.toByte()) + body
    val checksum = payload.fold(0) { value, byte -> value xor (byte.toInt() and 0xFF) }.toByte()
    val escaped = payload.flatMap { byte ->
        if (byte == 0xAA.toByte() || byte == 0xA5.toByte()) {
            listOf(0xA5.toByte(), byte)
        } else {
            listOf(byte)
        }
    }.toByteArray()
    return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + escaped + checksum
}

/**
 * Control frames keep their single `0x60` command byte on purpose. They are private-body
 * writes, which do not go through `LorinRequestBody.cmdList` at all, and this framing is
 * what LoEUC already ships and what the light and power-off writes were checked against -
 * re-encoding it is a change no capture backs. See docs/inmotion-protocol-notes.md.
 */
private fun buildControlCommand(subcommand: Int, data: ByteArray): ByteArray {
    return buildFramedMessage(
        flag = FLAG_DEFAULT,
        commandBytes = byteArrayOf(COMMAND_CONTROL.toByte()),
        data = byteArrayOf(subcommand.toByte()) + data,
    )
}

/**
 * `V11Settings.fromBytes` (`lorin/settings/v11_settings.dart`, 0x9e41a4), joined to its
 * `toMap` keys through the object field offsets the two functions share - the same method
 * the P6 layout below was read with.
 *
 * The three packed bytes at 20..22 hold four two-bit fields each, at bit positions
 * 0/2/4/6, the `ByteUtil.bitValue(value, position, width)` shape already documented for
 * V14. Bytes 23 and 24 carry four more such fields plus `version`, but which name lands
 * on which slot is not resolved, so they are left out rather than guessed - the Android
 * settings screen shows every payload byte raw anyway.
 *
 * Writable fields below are keyed off the same Control(0x60) subcommand table as the rest of
 * this file (P6's `setting()` calls, `docs/inmotion-protocol-notes.md`), cross-checked against
 * a real V11's WheelLog wire trace: speed limit, pitch trim, pedal sensitivity (Comfort),
 * voice volume, standby timeout (`0x28`, 1..60 minutes - the range the official app offers,
 * seconds on the wire), headlight brightness, sound, DRL, auto headlight, no-load detection,
 * wheel lock, transport mode, lift-up detection, low battery ride mode, and the
 * acceleration/brake assist pair. Everything else stays [KIND_READONLY] because its command id,
 * payload shape or bounds are not resolved for V11 - `driver_mode`, `ride_mode`, LED effect
 * mode, the auto-light sensor thresholds, auto brightness, rider detection and both fan rows
 * among them.
 *
 * `lamp_brightness` is 0..100, not 0..255: `setHeadlightBrightness` clamps there in the official
 * app, alongside `setVolume`, `setPedalSensitivity` and `setAccAndBrakeAssistPercentage`, and a
 * stock V11 reports exactly 100. (The legacy V12/V14 rows keep 0..255 - that path is
 * independently corroborated and nothing in it says otherwise.)
 *
 * "Headlight on/off" (`0x50`, or `0x40` on main-board firmware below 1.4) is not a
 * settings-block field at all - it never appears in this payload - so it is not represented
 * here; see [InmotionProtocolEngine.v11HeadlightCommand] instead.
 *
 * Pedal sensitivity is **per ride mode**, not a doubled value: WheelLog's Inmotion V2 adapter
 * reads `mComfSens = data[5]` (Comfort) and `mClassSens = data[6]` (Classic), and a real wheel
 * reports two different numbers there (74/80) - impossible if one write set both. The active
 * slot is chosen by ride mode, the high nibble of byte 4 ([KEY_RIDE_MODE] above). Comfort is
 * writable; Classic stays read-only for now (a project choice, not a protocol gap) but its raw
 * byte is still preserved correctly when Comfort is written, through the same `otherValue`
 * pair-preservation pattern already used for the packed pairs below it.
 */
private fun ByteArray.toV11SettingCapabilities(): List<InmotionSettingCapability> {
    if (size < 23) {
        return emptyList()
    }
    fun packed(offset: Int, position: Int) = (u8(offset) shr position) and 0x3
    return listOf(
        setting(KEY_SPEED_LIMIT, "Speed limit, km/h", "Ограничение скорости, км/ч", uint16Le(0) / SPEED_SCALE, 0, SUB_SPEED_LIMIT, 0, SPEED_MAX_KMH, "slider"),
        setting(KEY_PITCH_ZERO, "Pedal angle trim, 0.1°", "Угол педалей, 0.1°", int16Le(2) / PITCH_SCALE, 2, SUB_BALANCE_ANGLE, -80, 80, "slider"),
        setting("inmotion_driver_mode", "Controller mode", "Режим контроллера", u8(4) and 0xF, 4),
        setting(KEY_RIDE_MODE, "Pedal mode", "Режим педалей", (u8(4) shr 4) and 0xF, 4),
        setting(KEY_PEDAL_SENSITIVITY_1, "Pedal sensitivity (Comfort)", "Чувствительность педалей (Комфорт)", u8(5), 5, SUB_PEDAL_SENSITIVITY, 0, 100, "slider"),
        setting(KEY_PEDAL_SENSITIVITY_2, "Pedal sensitivity (Classic)", "Чувствительность педалей (Классика)", u8(6), 6),
        setting(KEY_VOICE_VOLUME, "Voice volume", "Громкость озвучки", u8(7), 7, SUB_VOLUME, 0, 100, "slider"),
        setting(
            KEY_STANDBY_TIME,
            "Standby timeout, min",
            "Режим ожидания, мин",
            uint16Le(12) / STANDBY_SCALE,
            12,
            SUB_STANDBY_TIME,
            STANDBY_MIN_MINUTES,
            STANDBY_MAX_MINUTES,
            "slider",
        ),
        setting(KEY_LIGHT_EFFECT_MODE, "LED effect mode", "Режим светодиодных эффектов", u8(14), 14),
        setting(KEY_AUTO_LIGHT_LOW_THR, "Auto headlight sensor threshold (low)", "Порог датчика автосвета (низкий)", u8(15), 15),
        setting(KEY_AUTO_LIGHT_HIGH_THR, "Auto headlight sensor threshold (high)", "Порог датчика автосвета (высокий)", u8(16), 16),
        setting(KEY_LIGHT_BRIGHTNESS, "Headlight brightness", "Яркость света", u8(17), 17, 0x2B, 0, LIGHT_BRIGHTNESS_MAX, "slider"),
        setting(KEY_ACCEL_ASSIST, "Acceleration assist, %", "Ассист разгона, %", u8(18), 18, SUB_ACCEL_BRAKE_ASSIST, 0, 100, "slider"),
        setting(KEY_BRAKE_ASSIST, "Brake assist, %", "Ассист торможения, %", u8(19), 19, SUB_ACCEL_BRAKE_ASSIST, 0, 100, "slider"),
        setting(KEY_AUDIO_SWITCH, "Sound", "Звук", packed(20, 0), 20, SUB_VOICE_SWITCH, 0, 1, "toggle"),
        setting(KEY_DRL_STATE, "Daytime running lights", "Дневные ходовые огни", packed(20, 2), 20, SUB_DRL_V11, 0, 1, "toggle"),
        setting(KEY_LIFT_UP_DETECTION, "Lift-up detection", "Датчик поднятия", packed(20, 4), 20, SUB_LIFT_UP_DETECTION, 0, 1, "toggle"),
        setting(KEY_AUTO_LIGHT_STATE, "Auto headlight", "Автосвет", packed(20, 6), 20, 0x2F, 0, 1, "toggle"),
        setting("inmotion_auto_brightness", "Auto brightness", "Автояркость", packed(21, 0), 21),
        setting(KEY_LOCK_MODE, "Wheel lock", "Замок колеса", packed(21, 2), 21, SUB_LOCK_MODE, 0, 1, "toggle"),
        setting(KEY_TRANSPORT_MODE, "Transport mode", "Транспортировочный режим", packed(21, 4), 21, SUB_TRANSPORT_MODE, 0, 1, "toggle"),
        setting("inmotion_load_detect", "Rider detection", "Датчик присутствия райдера", packed(21, 6), 21),
        setting(KEY_NO_LOAD_DETECT, "No-load detection", "Датчик отсутствия нагрузки", packed(22, 0), 22, SUB_NO_LOAD_DETECT, 0, 1, "toggle"),
        setting(KEY_LOW_BATTERY_RIDE, "Low battery ride mode", "Режим езды при низком заряде", packed(22, 2), 22, SUB_LOW_BATTERY_RIDE, 0, 1, "toggle"),
        // Both fan rows are display-only, and not by policy: `fan_status` is a *status* (the same
        // status/mode split `drl_light_status` has - the official `toMap` exposes only
        // `fan_mute_mode` as a fan setting), and neither has a usable write id. `0x43` is
        // `bermAngleModeSwitchCmd` in the very table the rest of these ids come from, and `0x38`
        // is in no table at all. Field-reported on a real V11 (2026-08-14): the row neither stuck
        // nor produced the confirmation beep every other write on that wheel produced.
        setting(KEY_QUIET_FAN, "Quiet fan mode", "Тихий режим вентилятора", packed(22, 4), 22),
        setting(KEY_FAN, "Fan", "Вентилятор", packed(22, 6), 22),
    )
}

private fun ByteArray.toV12SettingCapabilities(): List<InmotionSettingCapability> {
    if (size < 33) {
        return emptyList()
    }
    val statusByte = u8(32)
    return listOf(
        InmotionSettingCapability(
            key = KEY_AUTO_LIGHT_STATE,
            titleEn = "Auto headlight",
            titleRu = "Автосвет",
            rawValue = if (statusByte.bit(3)) 1 else 0,
            sourceOffset = 32,
            commandId = 0x2F,
            minValue = 0,
            maxValue = 1,
            kind = "toggle",
        ),
        InmotionSettingCapability(
            key = KEY_AUTO_LIGHT_LOW_THR,
            titleEn = "Auto headlight sensor threshold (low)",
            titleRu = "Порог датчика автосвета (низкий)",
            rawValue = u8(23),
            sourceOffset = 23,
            commandId = 0x2A,
            minValue = 0,
            maxValue = 255,
            kind = "slider",
        ),
        InmotionSettingCapability(
            key = KEY_AUTO_LIGHT_HIGH_THR,
            titleEn = "Auto headlight sensor threshold (high)",
            titleRu = "Порог датчика автосвета (высокий)",
            rawValue = u8(24),
            sourceOffset = 24,
            commandId = 0x2A,
            minValue = 0,
            maxValue = 255,
            kind = "slider",
        ),
        InmotionSettingCapability(
            key = KEY_LOW_BEAM_BRIGHTNESS,
            titleEn = "Low beam brightness",
            titleRu = "Яркость ближнего света",
            rawValue = u8(25),
            sourceOffset = 25,
            commandId = 0x2B,
            minValue = 0,
            maxValue = 255,
            kind = "slider",
        ),
        InmotionSettingCapability(
            key = KEY_HIGH_BEAM_BRIGHTNESS,
            titleEn = "High beam brightness",
            titleRu = "Яркость дальнего света",
            rawValue = u8(26),
            sourceOffset = 26,
            commandId = 0x2B,
            minValue = 0,
            maxValue = 255,
            kind = "slider",
        ),
    )
}

/**
 * V13/V14/P6 settings layout is structurally different from V12: a single
 * `lightBrightness` byte instead of separate low/high beam values, and no
 * beam-switch-speed-threshold field at all. P6 has no dedicated settings class
 * in the decompile and is inferred (not directly observed) to route through
 * the same V14 code path.
 *
 * `autoLightState` at byte 28 is a packed-bit field read via the official
 * app's `ByteUtil.bitValue(value, position, width)` helper, not a plain
 * shift+mask visible at the call site. Traced directly from that helper's own
 * disassembly (not just its callers): it unboxes a tagged-Smi `position` and
 * computes `(value >> position) & ((1 << width) - 1)`. The four packed fields
 * in byte 28 use `position=0/2/4/6`, `width=2` each: `soundState`(0),
 * `drlState`(2), `liftedState`(4), `autoLightState`(6) — i.e.
 * `(byte28 >> 6) and 0x3`. This resolves an earlier disagreement with
 * WheelLog's independent trace (which put `drlState` a full byte over, at
 * byte 29 bit 2) in favor of the official app's own bytecode.
 */
private fun ByteArray.toV14SettingCapabilities(): List<InmotionSettingCapability> {
    if (size < 29) {
        return emptyList()
    }
    return listOf(
        InmotionSettingCapability(
            key = KEY_AUTO_LIGHT_STATE,
            titleEn = "Auto headlight",
            titleRu = "Автосвет",
            rawValue = (u8(28) shr 6) and 0x3,
            sourceOffset = 28,
            commandId = 0x2F,
            minValue = 0,
            maxValue = 1,
            kind = "toggle",
        ),
        InmotionSettingCapability(
            key = KEY_AUTO_LIGHT_LOW_THR,
            titleEn = "Auto headlight sensor threshold (low)",
            titleRu = "Порог датчика автосвета (низкий)",
            rawValue = u8(23),
            sourceOffset = 23,
            commandId = 0x2A,
            minValue = 0,
            maxValue = 255,
            kind = "slider",
        ),
        InmotionSettingCapability(
            key = KEY_AUTO_LIGHT_HIGH_THR,
            titleEn = "Auto headlight sensor threshold (high)",
            titleRu = "Порог датчика автосвета (высокий)",
            rawValue = u8(24),
            sourceOffset = 24,
            commandId = 0x2A,
            minValue = 0,
            maxValue = 255,
            kind = "slider",
        ),
        InmotionSettingCapability(
            key = KEY_LIGHT_BRIGHTNESS,
            titleEn = "Headlight brightness",
            titleRu = "Яркость света",
            rawValue = u8(25),
            sourceOffset = 25,
            commandId = 0x2B,
            minValue = 0,
            maxValue = 255,
            kind = "slider",
        ),
    )
}

/**
 * `P6Settings.fromBytes` layout (`lorin/settings/p6_settings.dart` in the current
 * InMotion Pro AOT dump), relative to the `getSettingsAll` (`0x20`) response payload.
 * Field names are the class's own `toMap()` keys, joined to the parse offsets through
 * the object field offsets both functions share.
 *
 * This is *not* the V13/V14 `0x08` layout: P6 prepends `voice_packet_index` and
 * `rgb_packet_index` (u32 each) where V14 has two bytes, so every later field sits six
 * bytes further along - `low_threshold` @29 here vs @23 there. Applying the V14 offsets
 * to a P6 settings block silently reads the wrong bytes; that mismatch is why both
 * layouts are kept separate rather than merged.
 *
 * The parser rejects payloads shorter than 47 bytes (`ArgumentError` in the original),
 * and every field read past that point is individually bounds-checked, so short frames
 * yield the fields that fit.
 */
private fun ByteArray.toP6SettingCapabilities(): List<InmotionSettingCapability> {
    if (size < 49) {
        return emptyList()
    }
    val flags = uint32Le(45)
    fun flag(bit: Int) = if (flags.bit(bit)) 1 else 0
    return listOf(
        setting(KEY_RIDE_MODE, "Pedal mode", "Режим педалей", (u8(24) shr 4) and 0xF, 24, SUB_RIDE_MODE, 0, 2, "slider"),
        setting("inmotion_driver_mode", "Controller mode", "Режим контроллера", u8(24) and 0xF, 24),
        setting(KEY_PEDAL_SENSITIVITY_1, "Pedal sensitivity 1", "Чувствительность педалей 1", u8(25), 25, SUB_PEDAL_SENSITIVITY, 0, 100, "slider"),
        setting(KEY_PEDAL_SENSITIVITY_2, "Pedal sensitivity 2", "Чувствительность педалей 2", u8(26), 26, SUB_PEDAL_SENSITIVITY, 0, 100, "slider"),
        setting(KEY_VOICE_VOLUME, "Voice volume", "Громкость озвучки", u8(27), 27, SUB_VOLUME, 0, 100, "slider"),
        setting(KEY_LIGHT_EFFECT_MODE, "LED effect mode", "Режим светодиодных эффектов", u8(28), 28, SUB_LIGHT_EFFECT_MODE, 0, 255, "slider"),
        setting(KEY_AUTO_LIGHT_LOW_THR, "Auto headlight sensor threshold (low)", "Порог датчика автосвета (низкий)", u8(29), 29, 0x2A, 0, 255, "slider"),
        setting(KEY_AUTO_LIGHT_HIGH_THR, "Auto headlight sensor threshold (high)", "Порог датчика автосвета (высокий)", u8(30), 30, 0x2A, 0, 255, "slider"),
        setting(KEY_LOW_BEAM_BRIGHTNESS, "Low beam brightness", "Яркость ближнего света", u8(31), 31, 0x2B, 0, 100, "slider"),
        setting(KEY_HIGH_BEAM_BRIGHTNESS, "High beam brightness", "Яркость дальнего света", u8(32), 32, 0x2B, 0, 100, "slider"),
        setting(KEY_AUTO_LIGHT_STATE, "Auto headlight", "Автосвет", flag(3), 45, 0x2F, 0, 1, "toggle"),
        setting(KEY_DRL_STATE, "Daytime running lights", "Дневные ходовые огни", flag(21), 45, SUB_DRL_P6, 0, 1, "toggle"),
        setting(KEY_BERM_ANGLE_MODE, "Berm angle mode", "Компенсация уклона в вираже", flag(17), 45, SUB_BERM_ANGLE_MODE_P6, 0, 1, "toggle"),
        setting(KEY_SPEED_LIMIT, "Speed limit, km/h", "Ограничение скорости, км/ч", uint16Le(8) / SPEED_SCALE, 8, SUB_SPEED_LIMIT, 0, SPEED_MAX_KMH, "slider"),
        setting(KEY_SPEED_WARNING_1, "Speed warning 1, km/h", "Порог предупреждения 1, км/ч", uint16Le(10) / SPEED_SCALE, 10, SUB_WARNING_SPEED, 0, SPEED_MAX_KMH, "slider"),
        setting(KEY_SPEED_WARNING_2, "Speed warning 2, km/h", "Порог предупреждения 2, км/ч", uint16Le(12) / SPEED_SCALE, 12, SUB_WARNING_SPEED, 0, SPEED_MAX_KMH, "slider"),
        setting(KEY_PITCH_ZERO, "Pedal angle trim, 0.1°", "Угол педалей, 0.1°", int16Le(20) / PITCH_SCALE, 20, SUB_BALANCE_ANGLE, -80, 80, "slider"),
        // Decoded but never written: no resolved command id, or writing it can cut motor
        // power under the rider. See KIND_READONLY.
        // Units below are the ones a real P6 confirmed against the official app's own
        // screens on 2026-08-13: output thresholds in hundredths of a percent (80.00%),
        // standby in seconds (14400 = 240 min), tyre alarm in hundred-thousandths of a bar
        // (19000 = 1.90 bar, exposed as millibar so the integer stays exact), charge
        // currents in tenths of an amp (30 = 3.0 A). See docs/inmotion-protocol-notes.md.
        setting("inmotion_output_tiltback_threshold", "Output tiltback threshold, %", "Порог откидывания по выходу, %", int16Le(14) / 100, 14),
        setting("inmotion_output_warning_threshold_1", "Output warning 1, %", "Порог предупреждения по выходу 1, %", int16Le(16) / 100, 16),
        setting("inmotion_output_warning_threshold_2", "Output warning 2, %", "Порог предупреждения по выходу 2, %", int16Le(18) / 100, 18),
        setting("inmotion_standby_time", "Standby timeout, min", "Режим ожидания, мин", uint16Le(22) / 60, 22),
        setting("inmotion_active_sound_sensitivity", "Active sound sensitivity", "Чувствительность активного звука", u8(33), 33),
        setting("inmotion_high_beam_auto_switch_speed", "High beam auto switch speed, km/h", "Скорость автопереключения дальнего, км/ч", uint16Le(34) / SPEED_SCALE, 34),
        // The pair `setAccAndBrakeAssistPercentage` writes: the setter clamps both to
        // 0..100, and a real P6 reads 50/50 here. This is the whole of "pedal adjustment"
        // the protocol carries - the four angle/rate rows the official app shows on that
        // screen are in no settings block and have no setter anywhere in the private-body
        // surface, so the app derives them. Read-only here: the write command id exists
        // only on V14 (`V14Cmd.accAndBrakeAssistPercentageCmd` = 0x3F) and P6 inherits no
        // equivalent.
        setting("inmotion_speeding_feedback", "Acceleration assist, %", "Ассист разгона, %", int8(36), 36),
        setting("inmotion_braking_feedback", "Brake assist, %", "Ассист торможения, %", int8(37), 37),
        setting(KEY_TPMS_LOW_ALARM, "Tyre low pressure alarm, mbar", "Порог низкого давления шины, мбар", uint16Le(38) / TPMS_SCALE, 38, SUB_TPMS_LOW_ALARM, 0, TPMS_MAX_MBAR, "slider"),
        setting(KEY_LOGO_LIGHT_BRIGHTNESS, "Logo light brightness, %", "Яркость подсветки логотипа, %", u8(40), 40, SUB_LOGO_LIGHT, 0, 100, "slider"),
        setting(KEY_BERM_ANGLE, "Berm angle, °", "Угол наклона бермы, °", u8(41), 41, SUB_BERM_ANGLE, 0, BERM_ANGLE_MAX_DEGREES, "slider"),
        setting("inmotion_charge_cut_off_percent", "Charge cut-off, %", "Ограничение заряда, %", u8(42), 42),
        setting("inmotion_max_charge_current_ac220", "Max charge current 220V, 0.1 A", "Макс. ток заряда 220 В, 0.1 А", u8(43), 43),
        setting("inmotion_max_charge_current_ac110", "Max charge current 110V, 0.1 A", "Макс. ток заряда 110 В, 0.1 А", u8(44), 44),
        setting(KEY_AUDIO_SWITCH, "Sound", "Звук", flag(0), 45, SUB_VOICE_SWITCH, 0, 1, "toggle"),
        setting("inmotion_turn_signal_light", "Turn signal active", "Активен поворотник", flag(1), 45),
        setting("inmotion_lift_up_detection", "Lift-up detection", "Датчик поднятия", flag(2), 45),
        setting("inmotion_auto_brightness", "Auto brightness", "Автояркость", flag(4), 45),
        setting("inmotion_lock_mode", "Wheel lock", "Замок колеса", flag(5), 45),
        setting("inmotion_transport_mode", "Transport mode", "Транспортировочный режим", flag(6), 45),
        setting("inmotion_load_detect", "Rider detection", "Датчик присутствия райдера", flag(7), 45),
        setting("inmotion_no_load_detect", "No-load detection", "Датчик отсутствия нагрузки", flag(8), 45),
        // Read-only on purpose, though its id (0x37) is as solid as the others: switching
        // it off is what stops a wheel from riding on a low pack, and an alert rule could
        // do that under a rider at 10 %. Same reasoning as lock/transport/lift-up.
        setting(KEY_LOW_BATTERY_RIDE, "Low battery ride mode", "Режим езды при низком заряде", flag(9), 45),
        setting("inmotion_active_sound", "Active sound", "Активный звук", flag(10), 45),
        setting("inmotion_touch_key", "Touch key", "Сенсорная кнопка", flag(11), 45),
        setting("inmotion_usb_power_switch", "USB power", "Питание USB", flag(12), 45),
        setting(KEY_AUTO_CLOSE_SCREEN, "Auto screen off", "Автовыключение экрана", flag(13), 45, SUB_DISPLAY_AUTO_OFF, 0, 1, "toggle"),
        setting("inmotion_range_estimate", "Range estimate", "Расчёт запаса хода", flag(14), 45),
        setting("inmotion_assist_balance", "Assist balance", "Балансировка ассистента", flag(15), 45),
        setting("inmotion_acce_feedback", "Acceleration feedback switch", "Переключатель отклика разгона", flag(16), 45),
        setting("inmotion_logo_light_status", "Logo light", "Подсветка логотипа", flag(18), 45),
        setting("inmotion_tbox_low_battery_wakeup", "T-Box low battery wake-up", "Пробуждение T-Box при низком заряде", flag(19), 45),
        setting("inmotion_show_tbox_info", "Show T-Box info", "Показывать данные T-Box", flag(20), 45),
        setting("inmotion_shield_tps_error", "Mute TPMS fault", "Скрыть ошибку TPMS", flag(22), 45),
        setting(KEY_TURN_LIGHT_MODE, "Turn signal mode", "Режим поворотников", (flags shr 23) and 0x7, 45, SUB_TURN_LIGHT_MODE, 0, TURN_LIGHT_MODE_MAX, "slider"),
        setting(KEY_TAIL_LIGHT_MODE, "Tail light mode", "Режим заднего фонаря", flag(26), 45, SUB_TAIL_LIGHT_MODE, 0, 1, "toggle"),
        setting("inmotion_auto_lock", "Auto lock on shutdown", "Автозамок при выключении", flag(27), 45),
    )
}

private fun setting(
    key: String,
    titleEn: String,
    titleRu: String,
    rawValue: Int,
    sourceOffset: Int,
    commandId: Int = 0,
    minValue: Int = 0,
    maxValue: Int = 0,
    kind: String = KIND_READONLY,
) = InmotionSettingCapability(
    key = key,
    titleEn = titleEn,
    titleRu = titleRu,
    rawValue = rawValue,
    sourceOffset = sourceOffset,
    commandId = commandId,
    minValue = minValue,
    maxValue = maxValue,
    kind = kind,
)

private fun ByteArray.modelId(): Int? {
    if (size < 4 || this[0] != 0x01.toByte()) {
        return null
    }
    return u8(2) * 10 + u8(3)
}

/** Main-board / driver-board / BLE firmware versions, from the `0x02` main-info response's
 * `{0x06}`-selector answer. Only the main-board pair is resolved - see [versionInfoCommand].
 */
private data class InmotionMainBoardVersion(val major: Int, val minor: Int)

private fun ByteArray.toMainBoardVersion(): InmotionMainBoardVersion? {
    if (size < 24 || u8(0) != 0x06) {
        return null
    }
    return InmotionMainBoardVersion(major = u8(14), minor = u8(13))
}

private fun ByteArray.indexOfHeader(): Int {
    for (index in 0 until lastIndex) {
        if (this[index] == 0xAA.toByte() && this[index + 1] == 0xAA.toByte()) {
            return index
        }
    }
    return -1
}

private fun ByteArray.v2WireFrameEnd(): Int? {
    var unescapedCount = 0
    var expectedSize: Int? = null
    var escaped = false
    forEachIndexed { index, byte ->
        if (!escaped && byte == 0xA5.toByte()) {
            escaped = true
            return@forEachIndexed
        }
        unescapedCount += 1
        if (unescapedCount == 4) {
            expectedSize = u8(index) + 5
        }
        if (expectedSize == unescapedCount) {
            return index + 1
        }
        escaped = false
    }
    return null
}

private fun ByteArray.unescaped(): ByteArray {
    val result = ArrayList<Byte>(size)
    var escaped = false
    for (byte in this) {
        if (!escaped && byte == 0xA5.toByte()) {
            escaped = true
        } else {
            result += byte
            escaped = false
        }
    }
    return result.toByteArray()
}

private fun ByteArray.isValidV2Frame(): Boolean {
    if (size < 6 || this[0] != 0xAA.toByte() || this[1] != 0xAA.toByte()) {
        return false
    }
    if (size != u8(3) + 5) {
        return false
    }
    val checksum = (2 until lastIndex).fold(0) { value, index -> value xor u8(index) }
    return checksum == u8(lastIndex) && u8(2) in setOf(0x11, 0x14, 0x16)
}

/**
 * How many bytes the frame's command field takes, so the payload behind it can be found.
 * See [encodeLorinCommand]: bit `0x20` on the first byte means a second one follows.
 *
 * Many-to-many frames are excluded - their body is a source/target/command triple rather
 * than an encoded command field, and their source address (`0x24`, `0x25`, `0x32`, …)
 * would trip the same bit.
 */
private fun ByteArray.v2CommandSize(): Int {
    if (u8(2) == FLAG_MANY_TO_MANY) {
        return 1
    }
    return if (u8(4) and 0x20 != 0) 2 else 1
}

private fun ByteArray.v2Data(): ByteArray {
    if (size < 6) {
        return byteArrayOf()
    }
    val commandSize = v2CommandSize()
    val dataSize = minOf(u8(3) - commandSize, size - 1 - (4 + commandSize))
    return if (dataSize <= 0) byteArrayOf() else copyOfRange(4 + commandSize, 4 + commandSize + dataSize)
}

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun Int.bit(index: Int): Boolean = ((this ushr index) and 1) != 0

private fun ByteArray.uint16Le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

private fun ByteArray.uint32Le(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl 8) or (u8(offset + 2) shl 16) or (u8(offset + 3) shl 24)

private fun ByteArray.int8(offset: Int): Int {
    val value = u8(offset)
    return if (value and 0x80 != 0) value - 0x100 else value
}

private fun ByteArray.int16Le(offset: Int): Int {
    val value = uint16Le(offset)
    return if (value and 0x8000 != 0) value - 0x10000 else value
}

private fun ByteArray.pwmPercent(offset: Int): Double {
    val value = abs(int16Le(offset)) / 100.0
    return value.takeIf { it <= 200.0 } ?: Double.NaN
}

private fun ByteArray.temperature(offset: Int): Double {
    if (offset !in indices) {
        return Double.NaN
    }
    return (u8(offset) + 80 - 256).toDouble()
}

private fun ByteArray.presentTemperature(offset: Int): Double {
    return if (offset !in indices || u8(offset) == 0) Double.NaN else temperature(offset)
}

private data class DiagnosticDefinition(
    val categoryEn: String,
    val categoryRu: String,
    val titleEn: String,
    val titleRu: String,
    val severity: WheelDiagnosticSeverity,
)

private fun error(categoryEn: String, categoryRu: String, titleEn: String, titleRu: String) =
    DiagnosticDefinition(categoryEn, categoryRu, titleEn, titleRu, WheelDiagnosticSeverity.Error)

private fun warning(categoryEn: String, categoryRu: String, titleEn: String, titleRu: String) =
    DiagnosticDefinition(categoryEn, categoryRu, titleEn, titleRu, WheelDiagnosticSeverity.Warning)

private val DIAGNOSTIC_DEFINITIONS = listOf(
    error("Driver board", "Силовая плата", "Phase current sensor fault", "Ошибка датчика фазного тока"),
    error("Driver board", "Силовая плата", "Bus current sensor fault", "Ошибка датчика тока шины"),
    error("Motor", "Мотор", "Left Hall sensor fault", "Ошибка левого датчика Холла"),
    error("Motor", "Мотор", "Right Hall sensor fault", "Ошибка правого датчика Холла"),
    error("Battery", "Батарея", "Battery fault", "Ошибка батареи"),
    error("Driver board", "Силовая плата", "IMU sensor fault", "Ошибка датчика IMU"),
    error("Communication", "Связь", "Driver board communication fault 1", "Ошибка связи с силовой платой 1"),
    error("Communication", "Связь", "Driver board communication fault 2", "Ошибка связи с силовой платой 2"),
    error("Communication", "Связь", "HMIC communication fault 1", "Ошибка связи с HMIC 1"),
    error("Communication", "Связь", "HMIC communication fault 2", "Ошибка связи с HMIC 2"),
    error("Driver board", "Силовая плата", "MOS temperature sensor fault", "Ошибка датчика температуры MOS"),
    error("Motor", "Мотор", "Motor temperature sensor fault", "Ошибка датчика температуры мотора"),
    error("Driver board", "Силовая плата", "Board hot-area sensor fault", "Ошибка датчика горячей зоны платы"),
    error("Cooling", "Охлаждение", "Fan fault", "Ошибка вентилятора"),
    error("HMIC", "HMIC", "HMIC RTC fault", "Ошибка часов RTC HMIC"),
    error("HMIC", "HMIC", "HMIC flash fault", "Ошибка flash-памяти HMIC"),
    error("Driver board", "Силовая плата", "Bus voltage sensor fault", "Ошибка датчика напряжения шины"),
    error("Battery", "Батарея", "Battery voltage sensor fault", "Ошибка датчика напряжения батареи"),
    error("Battery", "Батарея", "Battery cannot power off", "Батарея не может отключиться"),
    error("Battery", "Батарея", "Battery cannot charge", "Батарея не может заряжаться"),
    warning("Battery", "Батарея", "Critically low battery", "Критически низкий заряд"),
    warning("Battery", "Батарея", "Battery overvoltage", "Перенапряжение батареи"),
    warning("Driver board", "Силовая плата", "Overcurrent", "Превышение тока"),
    warning("Battery", "Батарея", "Low battery", "Низкий заряд батареи"),
    error("Battery", "Батарея", "Additional battery fault", "Дополнительная ошибка батареи"),
    warning("Motor", "Мотор", "Motor overtemperature", "Перегрев мотора"),
    warning("Temperature", "Температура", "Vehicle overtemperature", "Общий перегрев"),
    warning("Driver board", "Силовая плата", "CPU overtemperature", "Перегрев CPU"),
    warning("Driver board", "Силовая плата", "IMU overtemperature", "Перегрев IMU"),
    warning("Safety", "Безопасность", "Locked because of a safety issue", "Блокировка из-за проблемы безопасности"),
    warning("Safety", "Безопасность", "Overspeed", "Превышение скорости"),
    warning("Motor", "Мотор", "Unexpected motor spin", "Непредусмотренное вращение мотора"),
    warning("Motor", "Мотор", "Motor blocked", "Мотор заблокирован"),
    warning("Safety", "Безопасность", "Fall detected", "Обнаружено падение"),
    warning("Safety", "Безопасность", "Risky riding behavior", "Опасное поведение при езде"),
    warning("Motor", "Мотор", "Motor no-load protection", "Защита вращения мотора без нагрузки"),
    warning("Safety", "Безопасность", "Required self-check not passed", "Не пройдена обязательная самопроверка"),
    warning("Controls", "Управление", "Power key held too long", "Слишком долгое нажатие кнопки питания"),
    warning("Battery", "Батарея", "Some batteries are not enabled", "Часть батарей не включена"),
    warning("Battery", "Батарея", "Battery calibration required", "Требуется калибровка батареи"),
    warning("Compatibility", "Совместимость", "Software incompatible", "Несовместимое программное обеспечение"),
    warning("Firmware", "Прошивка", "Functions limited by incomplete firmware update", "Функции ограничены из-за незавершенного обновления"),
    warning("Safety", "Безопасность", "Remote lock active", "Активна удаленная блокировка"),
    warning("Compatibility", "Совместимость", "Hardware incompatible", "Несовместимое оборудование"),
    warning("Cooling", "Охлаждение", "Fan speed too low", "Слишком низкая скорость вентилятора"),
)

private fun ByteArray.decodeDiagnostics(): List<WheelDiagnosticItem> {
    return DIAGNOSTIC_DEFINITIONS.mapIndexedNotNull { index, definition ->
        val payloadOffset = index / 8
        val bitOffset = index % 8
        val byte = getOrNull(payloadOffset)?.toInt()?.and(0xFF) ?: return@mapIndexedNotNull null
        WheelDiagnosticItem(
            index = index,
            payloadOffset = payloadOffset,
            bitOffset = bitOffset,
            rawValue = (byte ushr bitOffset) and 0x01,
            categoryEn = definition.categoryEn,
            categoryRu = definition.categoryRu,
            titleEn = definition.titleEn,
            titleRu = definition.titleRu,
            severity = definition.severity,
        )
    }
}
