package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.math.abs
import kotlin.math.roundToInt

data class LeaperKimTelemetry(
    val speedKmh: Double,
    val pwmPercent: Double,
    val voltage: Double,
    val phaseCurrent: Double,
    val outputCurrent: Double,
    val power: Double,
    val temperature: Double,
    val mosTemperature: Double,
    val motorTemperature: Double,
    val coilTemperature: Double = Double.NaN,
    val batteryPercent: Double,
    val tripDistanceMeters: Double,
    val totalDistanceMeters: Double,
    val batteryCurrent: Double,
    val leftBatteryCurrent: Double,
    val rightBatteryCurrent: Double,
    val lateralAngle: Double,
    val carPose: Double,
    val batteryTemperatureMin: Double,
    val batteryTemperatureMax: Double,
    val dangerSpeedKmh: Double,
    val stopSpeedKmh: Double,
    val fallProtectionAngle: Double,
    val rideMode: Double,
    val shutdownTimeSeconds: Double,
    val chargeMode: Double,
    val batteryTempMode: Double,
    val lockState: Double,
    val hardwareCode: String?,
    val firmwareVersion: Double,
    val highSpeedMode: Boolean,
    val fieldWeakeningPercent: Double,
    /**
     * The speed word `@6` with the sign the wheel sends. [speedKmh] is its magnitude, which is
     * what a dashboard wants; the sign is what a crash reconstruction wants.
     */
    val signedSpeedKmh: Double = Double.NaN,
    /**
     * The q-axis current `@16` with its sign. [phaseCurrent] is its magnitude; read against
     * [signedSpeedKmh], opposite signs are drive and equal signs are braking on this firmware.
     */
    val signedPhaseCurrent: Double = Double.NaN,
    /**
     * The main-frame halfword `@38..39`, signed and unscaled. Reverse engineering reads it as the
     * input (battery) current in tenths of an amp - zero on a parked wheel, tracking the phase
     * current with the opposite sign while riding - but that reading is not confirmed, so the
     * value is published raw. Absent on legacy 36-byte frames.
     */
    val inputCurrentRaw: Double = Double.NaN,
)

data class LeaperKimBmsCell(
    val index: Int,
    val voltage: Double,
)

data class LeaperKimBmsTemperature(
    val index: Int,
    val celsius: Double,
)

data class LeaperKimBmsBattery(
    val index: Int,
    val expectedCellCount: Int,
    val parallelStrings: Int,
    val cells: List<LeaperKimBmsCell>,
    val currentAmps: Double,
    val temperatures: List<LeaperKimBmsTemperature>,
    val maxChargeVoltage: Double,
)

data class LeaperKimBmsSnapshot(
    val profileId: String,
    val modelName: String,
    val batteries: List<LeaperKimBmsBattery>,
)

data class LeaperKimSettingCapability(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    val rawValue: Int,
    val sourceOffset: Int,
    val commandId: Int?,
    val minValue: Int,
    val maxValue: Int,
    val kind: String,
    /**
     * Base voltage, in whole volts, that `max_charge_voltage`'s raw adjustment is added to:
     * `base + rawValue / 10` V. The wheel's own page 8 byte 65 when it reports one, otherwise
     * the base known for this firmware build ([LeaperKimMaxChargeVoltage.baseVolts]). Null when
     * neither knows it: a consumer then shows the adjustment alone, never a guessed voltage, and
     * the telemetry max-charge voltage is NaN for the same frame.
     */
    val baseValue: Int? = null,
    /**
     * False when the current wheel firmware doesn't report this field this cycle (raw byte
     * reads the 0x80 "not reported" sentinel). [rawValue] is meaningless when false - mirrors
     * Android's WheelSettingCapability.isSupported semantics so both platforms expose the same
     * 16-entry capability set, just with different fields flagged unsupported per wheel.
     */
    val isSupported: Boolean = true,
)

class LeaperKimProtocolEngine {
    private val reassembler = LeaperKimFrameReassembler()

    /**
     * The reassembled frames this link has seen, newest first.
     *
     * Kept because a frame carries more than telemetry: the tail the patched firmware hands to
     * plugins is raw bytes no decoded value contains, and the only proof that a plugin is alive
     * in the wheel's flash is that those bytes stopped being the sentinel. Android reads the same
     * window out of its own capture store; this is where the other shell gets it, so both read
     * one reassembly rather than two.
     *
     * Frames are kept whole, CRC32 trailer included, exactly as they arrived - offsets counted
     * against a trimmed frame would be a second, quieter frame format.
     */
    private val recentFrames = ArrayDeque<ByteArray>()
    private var batteryPercent = Double.NaN

    /**
     * True once page 2 has reported a real percentage at offset 50. Only then may the
     * voltage-curve estimate be suppressed - see the battery block in [toTelemetry].
     */
    private var hasReportedBatteryPercent = false
    private var lastVoltage = Double.NaN
    private var leftBatteryCurrent = Double.NaN
    private var rightBatteryCurrent = Double.NaN
    private var lateralAngle = Double.NaN
    private var pedalAngleTrim = Double.NaN
    private var mosTemperature = Double.NaN
    private var fallProtectionAngle = Double.NaN
    private var settings = emptyList<LeaperKimSettingCapability>()
    private var hardwareCode: String? = null
    private val batteryCells = List(2) { mutableMapOf<Int, Double>() }
    private val batteryTemperatures = List(2) { mutableMapOf<Int, Double>() }
    private val reportedCellCounts = arrayOfNulls<Int>(2)
    private val signedBatteryCurrents = DoubleArray(2) { Double.NaN }
    private var maxChargeVoltage = Double.NaN
    private var lockState = Double.NaN
    private var highSpeedMode = false
    private var firmwareVersion = Double.NaN

    /** The frame window, newest first. Empty until the first frame passes its CRC32. */
    fun recentFrames(): List<ByteArray> = recentFrames.toList()

    fun reset() {
        reassembler.reset()
        recentFrames.clear()
        batteryPercent = Double.NaN
        hasReportedBatteryPercent = false
        lastVoltage = Double.NaN
        leftBatteryCurrent = Double.NaN
        rightBatteryCurrent = Double.NaN
        lateralAngle = Double.NaN
        pedalAngleTrim = Double.NaN
        mosTemperature = Double.NaN
        fallProtectionAngle = Double.NaN
        settings = emptyList()
        hardwareCode = null
        batteryCells.forEach { it.clear() }
        batteryTemperatures.forEach { it.clear() }
        reportedCellCounts.fill(null)
        signedBatteryCurrents.fill(Double.NaN)
        maxChargeVoltage = Double.NaN
        lockState = Double.NaN
        highSpeedMode = false
        firmwareVersion = Double.NaN
    }

    /**
     * Existing UI-facing capability list - unsupported fields (isSupported=false) are
     * dropped, preserving the behavior every current caller (iOS settings screen) already
     * relies on.
     */
    fun settingsCapabilities(): List<LeaperKimSettingCapability> {
        return allSettingsCapabilities().filter { it.isSupported }
    }

    /** Model identity carried by the firmware version field, independent of BMS page support. */
    fun modelName(): String? = when (hardwareCode) {
        "0010" -> "Sherman"
        "0011" -> "Sherman Max"
        "0020" -> "Abrams"
        // 0030 is Sherman-S, which has no unambiguous catalogue entry yet.
        "0040" -> "Patton"
        "0050" -> "Lynx"
        "0060" -> "Sherman-L"
        "0070" -> "Patton-S"
        "0080" -> "Oryx"
        "0090" -> "Lynx-S"
        "0100" -> "Sherman-P"
        "5010" -> "Apex"
        "5020" -> "Aero"
        "5030" -> "Aeon"
        "5040" -> "Xeno"
        else -> null
    }

    /**
     * Full capability list including fields the current wheel doesn't report this cycle
     * (isSupported=false, rawValue meaningless) - matches Android's WheelSettingCapability
     * list size/shape. Intended for callers that need to know the complete set of settings
     * a wheel model *could* expose (e.g. the AlertCommand action picker), as opposed to
     * [settingsCapabilities] which is for display of currently-known values only.
     */
    fun allSettingsCapabilities(): List<LeaperKimSettingCapability> {
        return settings.map { capability ->
            // angle_trim is a setting mirrored in the page-0/4 extension, not live roll. It is
            // supported only once a frame has actually supplied that separate trim value.
            // Until then it stays
            // isSupported = false, which is how every platform already renders an absent field.
            if (capability.key == "angle_trim" && pedalAngleTrim.isFinite()) {
                // Rounded, not truncated. The wheel reports hundredths of a degree and the
                // capability carries tenths, so half of every possible reading is not a whole
                // tenth - a factory trim of -7.99 deg is a real value, not a rounding artefact.
                // `toInt()` truncates toward zero, so 800 of the 1601 readings displayed a
                // tenth closer to level than the wheel actually stood, and always in the same
                // direction. Values this app wrote itself are whole tenths and survived either
                // way, which is why it went unnoticed.
                val raw = (pedalAngleTrim * 10.0).roundToInt().coerceIn(-80, 80)
                capability.copy(rawValue = raw, isSupported = true)
            } else {
                capability
            }
        }
    }

    fun bmsSnapshot(): LeaperKimBmsSnapshot {
        val profile = hardwareCode?.let(LeaperKimBmsProfiles::get) ?: LeaperKimBmsProfile(
            profileId = "leaperkim_generic",
            modelName = "Generic LeaperKim",
            batteryCount = 2,
            cellCount = reportedCellCounts.filterNotNull().maxOrNull() ?: guessSCountFromVoltage(lastVoltage),
            totalParallelStrings = 2,
        )
        return LeaperKimBmsSnapshot(
            profileId = profile.profileId,
            modelName = profile.modelName,
            batteries = List(profile.batteryCount) { batteryIndex ->
                LeaperKimBmsBattery(
                    index = batteryIndex + 1,
                    expectedCellCount = reportedCellCounts[batteryIndex] ?: profile.cellCount,
                    parallelStrings = profile.parallelStringsPerBattery,
                    cells = batteryCells[batteryIndex]
                        .map { (index, voltage) -> LeaperKimBmsCell(index, voltage) }
                        .sortedBy(LeaperKimBmsCell::index),
                    currentAmps = signedBatteryCurrents[batteryIndex],
                    temperatures = batteryTemperatures[batteryIndex]
                        .map { (index, celsius) -> LeaperKimBmsTemperature(index, celsius) }
                        .sortedBy(LeaperKimBmsTemperature::index),
                    maxChargeVoltage = maxChargeVoltage,
                )
            },
        )
    }

    @ExperimentalWriteApi
    fun buildSettingCommand(key: String, value: Int): ByteArray? {
        // The derived list, not the raw parse: a derived capability such as angle_trim is only
        // marked supported there, and refusing to write it would be exactly backwards - the write
        // sets a new trim, it does not depend on the byte the wheel reports.
        val capability = allSettingsCapabilities().firstOrNull { it.key == key } ?: return null
        if (!capability.isSupported) {
            return null
        }
        val commandId = capability.commandId ?: return null
        if (value !in capability.minValue..capability.maxValue) {
            return null
        }
        if (commandId == 16) {
            val legacy = byteArrayOf(
                'L'.code.toByte(), 'k'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
                0x10, 0x01, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x80.toByte(), value.toByte(),
            ).withCrc32()
            val new = byteArrayOf(
                'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
                0x10, 0x01, 0x00, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
                0x80.toByte(), value.toByte(),
            ).withCrc32()
            return legacy + new
        }
        val valueIndex = commandId - 5
        if (valueIndex < 7) {
            return null
        }
        val payload = ByteArray(valueIndex + 1) { 0x80.toByte() }
        payload[0] = 'L'.code.toByte()
        payload[1] = 'd'.code.toByte()
        payload[2] = 'A'.code.toByte()
        payload[3] = 'p'.code.toByte()
        payload[4] = commandId.toByte()
        payload[5] = 0x01
        payload[6] = 0x02
        payload[valueIndex] = value.toByte()
        return payload.withCrc32()
    }

    /**
     * The frames that put a plugin into the wheel's second flash bank.
     *
     * A patched wheel carries a receiver that knows three things — unlock the flash, erase a page,
     * write one halfword — and nothing else: no cursor, no state, no length field. The address
     * rides in every frame, which is what keeps the receiver small enough to fit the ninety-six
     * bytes the stock image has to spare. Ordering, erasing before writing and checking the result
     * are therefore this side's job.
     *
     * The offset is in halfwords from the start of the bank, sixteen bits of it, which caps the
     * whole plugin area at the 128 KB the budget allows and means no frame can reach the wheel's
     * own filesystem at the far end of the flash. That area is sixty-four slots of one flash page,
     * and the wheel calls every slot that carries the magic — so a plugin is installed *alongside*
     * the others, not instead of them, and [slot] is which one this payload goes into. A plugin
     * longer than a page simply covers the slots that follow.
     *
     * A stock wheel ignores every one of these frames: family `0x61` falls through its dispatch
     * chain to the branch that does nothing.
     */
    @ExperimentalWriteApi
    fun buildPluginUploadFrames(
        payload: ByteArray,
        slot: Int = 0,
        pageSize: Int = PluginSlotBytes,
    ): ByteArray {
        require(payload.isNotEmpty()) { "empty plugin" }
        require(pageSize > 0 && pageSize % 2 == 0) { "page size must be even" }
        require(slot in 0 until PluginSlots) { "slot $slot is outside the $PluginSlots the wheel calls" }
        val even = if (payload.size % 2 == 0) payload else payload + byteArrayOf(0xFF.toByte())
        require(even.size <= PluginSlots * PluginSlotBytes - slot * PluginSlotBytes) {
            "plugin does not fit the slots left from $slot"
        }
        val base = slot * PluginSlotBytes / 2

        val stream = ArrayList<Byte>(even.size * 8)
        fun frame(offsetHalfwords: Int, sub: Int, value: Int) {
            stream += byteArrayOf(
                'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
                0x10, 0x01, PluginLoaderFamily.toByte(),
                (offsetHalfwords ushr 8).toByte(), offsetHalfwords.toByte(),
                sub.toByte(), value.toByte(), (value ushr 8).toByte(),
            ).withCrc32().toList()
        }

        fun halfword(index: Int): Int {
            val at = index * 2
            return (even[at].toInt() and 0xFF) or ((even[at + 1].toInt() and 0xFF) shl 8)
        }

        val halfwords = even.size / 2
        frame(base, PluginLoaderUnlock, 0)

        // Every page first, then every write. Erasing between writes would take the magic back
        // out along with the page it sits in.
        for (index in 0 until halfwords) {
            if (index * 2 % pageSize == 0) {
                frame(base + index, PluginLoaderErase, 0)
            }
        }

        // The body, and only then the two halfwords the firmware recognises a plugin by. Until
        // the magic lands there is no plugin in the bank: the hook finds erased flash, falls back
        // to stock behaviour, and a link that drops mid-upload leaves a wheel that behaves exactly
        // as it did before. Writing the magic first would arm the hook over an image that is
        // mostly still erased flash, and it would call into it twice a second, and a power cycle
        // would not clear it.
        for (index in MagicHalfwords until halfwords) {
            frame(base + index, PluginLoaderWrite, halfword(index))
        }
        for (index in 0 until minOf(MagicHalfwords, halfwords)) {
            frame(base + index, PluginLoaderWrite, halfword(index))
        }
        return stream.toByteArray()
    }

    /**
     * A command for one of the plugins the wheel is carrying.
     *
     * The receiver in the firmware recognises this subcommand and does exactly one thing with it:
     * hand the frame to every installed plugin and stop looking. So the frame has to name its
     * addressee itself — otherwise a command meant for one plugin is answered by all of them.
     * [pluginId] is that name, `0` reaches everyone, and what [opcode] and [value] mean is between
     * this side and that plugin. A wheel with no plugin installed drops the frame.
     *
     * The two bytes that carry a flash address for an upload are free here, which is where the id
     * and the opcode ride; the value gets the two the payload always had.
     */
    @ExperimentalWriteApi
    fun buildPluginCommandFrame(pluginId: Int, opcode: Int, value: Int): ByteArray {
        require(pluginId in 0..0xFF) { "plugin id is one byte" }
        require(opcode in 0..0xFF) { "opcode is one byte" }
        require(value in 0..0xFFFF) { "value is one halfword" }
        return byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x10, 0x01, PluginLoaderFamily.toByte(),
            pluginId.toByte(), opcode.toByte(), PluginLoaderCall.toByte(),
            value.toByte(), (value ushr 8).toByte(),
        ).withCrc32()
    }

    /**
     * ABI 6 built-in module call. Version is checked before dispatch by the firmware.
     * Module ids run 1..13; 13 is configurable field weakening (Lynx-S b78).
     */
    @ExperimentalWriteApi
    fun buildModuleCommandFrame(pluginId: Int, opcode: Int, value: Int, protocolVersion: Int): ByteArray {
        require(pluginId in 1..13 && opcode in 0..255 && value in 0..65535)
        require(protocolVersion in 1..255)
        return byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x11, 0x01, PluginLoaderFamily.toByte(),
            (0x40 + pluginId).toByte(), opcode.toByte(), 3,
            value.toByte(), (value ushr 8).toByte(), protocolVersion.toByte(),
        ).withCrc32()
    }

    /** Bounded bank erase/check, or the separately keyed settings reset on ABI 6. */
    @ExperimentalWriteApi
    fun buildModuleMaintenanceFrame(subcommand: Int, page: Int, portion: Int): ByteArray {
        require(subcommand in listOf(1, 4, 5))
        require(page in 0..31 && portion in 0..15)
        val offset = if (subcommand == 1) page * 2048 else page
        val value = if (subcommand == 5) 0x5E7A else if (subcommand == 4) portion else 0
        return byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x10, 0x01, PluginLoaderFamily.toByte(),
            (offset ushr 8).toByte(), offset.toByte(), subcommand.toByte(),
            value.toByte(), (value ushr 8).toByte(),
        ).withCrc32()
    }

    @ExperimentalWriteApi
    fun buildFixedShutdownTimerCommand(): ByteArray {
        return byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x16, 0x80.toByte(), 0x00,
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x01, 0x80.toByte(),
        ).withCrc32()
    }

    /**
     * The horn: an `0x16` action frame whose only effect is the tone the wheel sounds on receipt.
     *
     * Both spellings of the app's own `0x16` action packet, with every action slot left at the
     * `0x80` placeholder. The documented shapes are, pre-CRC:
     *
     * ```text
     * fall protection angle  4C 64 41 70 16 01 00 80 80 80 80 80 80 80 80 80 80 <angle>
     * shutdown in 10 seconds 4C 64 41 70 16 01 00 80 80 80 80 80 80 80 80 80 01 80
     * this frame             4C 64 41 70 16 63 00 80 80 80 80 80 80 80 80 80 80 80
     * ```
     *
     * ## What makes the sound
     *
     * `packet[5]` is a beep request, and the dispatcher hands it to a decoder **before** it
     * switches on `packet[6]` - so every `LdAp` frame sounds whatever that byte asks for, whatever
     * command it turns out to carry. Traced in two images: `0x08011DF4` -> `0x0800DE94` on a NOSFET
     * Aero `502.0.06`, `0x0801263C` -> `0x0800E064` on a LeaperKim Lynx-S `009.0.04`. The decoders
     * are byte-identical and both tail-call the beeper.
     *
     * The byte is a two-decimal-digit spec, rejected outside `1..120`:
     *
     * ```text
     * duration = (value - value % 10) * 5     raised to 25 when that lands below 50
     * loudness = (value % 10) * 100           beeper clamps at 1023; the speed alarm uses 999
     * ```
     *
     * This frame asks for [HornBeepCode] = 99: **450 ms at loudness 900**. Every other command in
     * this engine sends [AckBeepCode] = 1 there, which is 50 ms at loudness 100 - the quietest tone
     * the firmware can emit, and why a command acknowledgement is barely audible. Leave it that
     * way: on the light and settings frames the chirp is an acknowledgement, and only here is it
     * the point. Raising it elsewhere would make every setting write shout.
     *
     * ## Why not the vendor's own horn command
     *
     * The NOSFET app's horn button sends `BtManager.CMD_OLDCMDB`, `4C 6B 41 70 0E 00 80 80 80 01`
     * plus CRC32, and that cannot be reused on a modern wheel. On an Aero no `LkAp` frame reaches
     * the command dispatcher at all: `0x0801AD04` tests the marker and only the `LdAp` arm at
     * `0x0801AD3A` continues to `0x0801AD4A b.w 0x08011DF4`. The Lynx-S image does not contain the
     * string `LkAp` anywhere. The constant's own name says what it is - `OLDCMDb`, the horn for the
     * wheels that predate this family.
     *
     * ## What the rest of the frame does
     *
     * Nothing to any wheel setting. The legacy short layout (`packet[6] == 0x00`, handler
     * `0x08011E48`) feeds nine setters from the remaining slots and every one range-rejects `0x80`,
     * `CFG+0x35` - the byte the speed alarm's threshold is derived from - included.
     *
     * It is **not** a no-op, and an earlier version of this comment saying so was wrong: two stores
     * run ahead of any range check, `0x200002FC = 8` in the headlight setter and `0x200002FE = 3`
     * in the beep decoder. Both are display/state bytes.
     *
     * ## The `LkAp` half, and a risk that was weighed and accepted
     *
     * The old spelling still goes out because on the wheels that predate `LdAp` it is the only
     * thing that can arrive - and it keeps [AckBeepCode], because there `packet[5]` is part of the
     * command's identity rather than a beep request (the vendor pairs it with the frame length to
     * tell its commands apart), so those wheels get a silent horn.
     *
     * That frame is **one byte away from the vendor's "shut down in ten seconds" packet**:
     * `CMD_SET_CLOSE_IN_10` is `LkAp 16 01` + ten `0x80` + `01 80`, and this is the same length and
     * the same selector with `0x80` in that slot instead. The owner weighed that against dropping
     * the old spelling and kept it (2026-09-21). If you edit this builder, slot 16 of the `LkAp`
     * frame stays `0x80`.
     */
    @ExperimentalWriteApi
    fun buildHornCommand(): ByteArray {
        val placeholders = ByteArray(11) { 0x80.toByte() }
        val oldCommand = byteArrayOf(
            'L'.code.toByte(), 'k'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x16, AckBeepCode,
        ) + placeholders + byteArrayOf(0x80.toByte())
        val newCommand = byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x16, HornBeepCode, 0x00,
        ) + placeholders
        return oldCommand.withCrc32() + newCommand.withCrc32()
    }

    /**
     * The horn for the wheel this engine has seen: [buildHornCommand], or null where it cannot be
     * dispatched.
     *
     * The four oldest hardware codes (see [usesTextLightCommand]) carry neither the `LkAp` nor
     * the `LdAp` handler, so the frame would be silence to them and they get no horn at all. An
     * unidentified wheel is treated like the newer ones, as it is for the light.
     */
    @ExperimentalWriteApi
    fun hornCommand(): ByteArray? =
        if (usesTextLightCommand()) null else buildHornCommand()

    /**
     * Whether the headlight can only be switched by the text command, because this wheel's
     * firmware has no binary `0x0D` handler at all.
     *
     * Read out of the firmware images rather than guessed. Every LeaperKim build understands the
     * text form; the binary family is the newer addition, and four hardware codes predate it:
     *
     * - `0010` Sherman, `0011` Sherman Max, `0020` Abrams, `0030` Sherman S: text only.
     * - `0040` Patton: text plus the old `LkAp` frame.
     * - `0050` Lynx: text plus both frames.
     * - `0060` Sherman-L, `0070` Patton-S, `0090` Lynx-S: text plus the new `LdAp` frame.
     *
     * The marker check is the whole proof for the four: `sherman_max_1.1.7.bin` contains neither
     * `LkAp` nor `LdAp` anywhere, so the frame this engine builds cannot be dispatched by
     * anything, and the light button was silent. Its command dispatcher at `0x080041A0` compares
     * the received text against a literal table and, on `SetLightON`, stores `1` into the light
     * mode byte at `0x200002C1` (`SetLightOFF` stores `0`).
     *
     * Unknown hardware codes answer `false`: the binary frame is what every caller sent before,
     * and a wheel this engine cannot identify is not the place to change that.
     */
    fun usesTextLightCommand(): Boolean = when (hardwareCode) {
        "0010", "0011", "0020", "0030" -> true
        else -> false
    }

    /**
     * Clear the wheel's trip meter - the distance the wheel itself counts since the last reset,
     * not the app's own ride record.
     *
     * Both spellings, exactly as the vendor app sends them, pre-CRC:
     *
     * ```text
     * LkAp FB 00 01
     * LdAp 0D 00 02 80 01
     * ```
     *
     * Two independent reads agree on these bytes:
     *
     * - The official app's own constants, `BtManager.CMD_CLEAR_METER` /
     *   `CMD_CLEAR_METER_NEW`, sent together through `sendBytesDataCombine`, each CRC32-wrapped -
     *   the same shape [buildLightCommand] already ships. Its only caller is the home screen's
     *   "reset meter" button, behind a confirmation dialog and behind **no** speed or charging
     *   gate (unlike the shutdown button right next to it, which the app blocks while moving).
     * - The firmware itself. In the page-8 settings grammar decoded in
     *   `docs/leaperkim-firmware-re-notes.md`, the frame's length byte is the setting id and a
     *   value for id `N` lands at `body[N - 10]`. The new packet's `0x0D` is id 13, its
     *   `body[1] == 0x02` selects that page-8 branch, and `applyPage8SettingsPacket` calls
     *   `triggerClearTripOrCallback(body[3])` - which acts only on the value `1`, the byte this
     *   packet puts there. Every other slot is the `0x80` placeholder its setter range-rejects.
     *
     * Nothing here is written to the persistent settings block: the handler at `0x08003BD0`
     * calls one function pointer and stores nothing.
     */
    @ExperimentalWriteApi
    fun buildClearTripCommand(): ByteArray {
        val oldCommand = byteArrayOf(
            'L'.code.toByte(), 'k'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0xFB.toByte(), 0x00, 0x01,
        ).withCrc32()
        val newCommand = byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x0D, 0x00, 0x02, 0x80.toByte(), 0x01,
        ).withCrc32()
        return oldCommand + newCommand
    }

    /**
     * The trip reset for the wheel this engine has seen: [buildClearTripCommand], or null where
     * it cannot be dispatched.
     *
     * Same gate as the horn and the light: the four oldest hardware codes (see
     * [usesTextLightCommand]) carry neither the `LkAp` nor the `LdAp` handler, so the frame would
     * be silence to them and they get no button at all. An unidentified wheel is treated like the
     * newer ones.
     */
    @ExperimentalWriteApi
    fun clearTripCommand(): ByteArray? =
        if (usesTextLightCommand()) null else buildClearTripCommand()

    /**
     * The headlight for the wheel this engine has seen, in the direction asked for.
     *
     * **Off goes out twice**, as the frame and as the text. Both spellings reach the same
     * light-mode byte on every image that has been read, and both write a plain zero there, so
     * there is nothing for them to fight over and whichever one this wheel dispatches puts the
     * light out. **On goes out as the frame alone**, because there the two are not always the
     * same request - see the two images below.
     *
     * The reason for the pair is a rider on an old Lynx (`0050`) on 2026-09-29: with the frame
     * alone going out, turning the headlight on worked and **nothing in the app would turn it
     * off** - not the off button, not the software auto-light, which sends this same command with
     * no state of its own in the way.
     *
     * Be clear about what this does and does not explain. The `005.0.13` image was disassembled
     * afterwards and it says the frame's zero **must** put that light out: the frame is valid by
     * length and CRC, its body[3] is the light slot, the range check there passes exactly 0 and 1,
     * the byte is written as it arrives, and the periodic lamp driver clears the pin for any value
     * but 1. So this pair is not a diagnosis. It is one extra write that removes the guess about
     * which spelling lands, on a wheel where the rider could not put the light out at all.
     *
     * The two images differ, and only one of them has the dim/bright story:
     *
     * - Lynx-S `009.0.0x`: the frame's setter stores `v * 2`, so the frame means mode 2 and the
     *   text means mode 1, and the light driver turns those into levels 190 and 60 - the bright
     *   and the dim beam. Sending both spellings for **on** would be asking for two lights.
     * - Old Lynx `005.0.05`..`005.0.13`: the frame's setter stores `v` as it arrives, rejects 2
     *   and above, and the lamp is a plain on/off pin with no levels at all. Here the frame and
     *   the text are the same request in both directions.
     *
     * The four oldest hardware codes ([usesTextLightCommand]) dispatch no frame at all and get
     * the text in both directions, as they did before. On `005.0.04` it is the other way round -
     * no frame path reaches the light byte there either - but that code is `0050` like the newer
     * Lynx images, and this engine cannot tell the two apart from telemetry.
     *
     * Dual-beam wheels never come here for their low beam: on a Lynx-S or an Oryx the text *is*
     * the low beam, and the platform shells route that button to [buildLeaperKimLowLightCommand]
     * on its own.
     */
    @ExperimentalWriteApi
    fun lightCommand(enabled: Boolean): ByteArray = when {
        usesTextLightCommand() -> buildLeaperKimLowLightCommand(enabled = enabled)
        enabled -> buildLightCommand(enabled = true)
        else -> buildLightCommand(enabled = false) + buildLeaperKimLowLightCommand(enabled = false)
    }

    @ExperimentalWriteApi
    fun buildLightCommand(enabled: Boolean): ByteArray {
        val value = if (enabled) 0x01 else 0x00
        val oldCommand = byteArrayOf(
            'L'.code.toByte(), 'k'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x0D, 0x01, 0x80.toByte(), 0x80.toByte(), value.toByte(),
        ).withCrc32()
        val newCommand = byteArrayOf(
            'L'.code.toByte(), 'd'.code.toByte(), 'A'.code.toByte(), 'p'.code.toByte(),
            0x0D, 0x01, 0x00, 0x80.toByte(), value.toByte(),
        ).withCrc32()
        return oldCommand + newCommand
    }

    fun consume(chunk: ByteArray): LeaperKimTelemetry? {
        var latest: LeaperKimTelemetry? = null

        for (frame in reassembler.consume(chunk)) {
            val frameLength = frame.size

            // Legacy Sherman/ShermanMax frames are 36 bytes with no CRC32 trailer. Nothing in the
            // newer format is anywhere near that short - Patton's page 3, the shortest CRC32 frame
            // observed, is 51 bytes - so the declared length alone separates the two formats.
            if (frameLength in LegacyFrameLengthRange) {
                // No checksum on the 36-byte stream. Reject impossible duty/pose words before
                // they can update BMS state, energy or ride maxima. LK5690's 2026-10-24 ride
                // contains duty 19464 and pose 23584 (0x5C20, the tail of a new frame header).
                if (frameLength != LegacyFrameSize || frame.uint16Be(34) > 10_000 ||
                    abs(frame.int16Be(32)) > 18_000
                ) continue
                remember(frame)
                latest = frame.toTelemetry(isLegacy = true)
                continue
            }
            if (!frame.hasValidCrc32()) {
                continue
            }
            remember(frame)
            // Decode the payload only. Frame length is per-page and per-model: Lynx-S/Oryx send a
            // 87-byte page 2, Patton (hardware 0040, no Smart BMS) only 54, so offsets that are
            // payload on one model land in the CRC32 trailer on another. Every `size` guard and
            // `*OrNull` accessor below bounds against the array it is given, so handing them the
            // trailer-free payload is what makes "field absent on this model" read as absent
            // instead of as four bytes of checksum noise.
            latest = frame.copyOfRange(0, frameLength - CRC32_SIZE).toTelemetry(isLegacy = false)
        }
        return latest
    }

    /** Newest first, and only as deep as the page cycle: nine frames plus a little slack. */
    private fun remember(frame: ByteArray) {
        recentFrames.addFirst(frame)
        while (recentFrames.size > RECENT_FRAME_WINDOW) recentFrames.removeLast()
    }

    private fun ByteArray.toTelemetry(isLegacy: Boolean): LeaperKimTelemetry? {
        // Legacy frames are exactly 36 bytes; CRC32 payloads start at 38.
        val minimumSize = if (isLegacy) LegacyFrameSize else 38
        if (size < minimumSize) {
            return null
        }
        val page = getOrNull(46)?.toInt()?.and(0xFF)
        readHardwareCode()?.let { hardwareCode = it }
        updateBmsState(page)

        val voltageRaw = uint16Be(4)
        val voltage = voltageRaw / 100.0
        lastVoltage = voltage

        // Two separate ideas, and they used to be conflated into one `batteryPercent.isNaN()`
        // guard: "a voltage estimate must never overwrite a percentage the BMS reported itself"
        // (still true, now expressed by [hasReportedBatteryPercent]) and "estimate only once"
        // (never intended). The legacy 36-byte Sherman Max frame has no byte 46, so `page` reads
        // null and every frame took the else branch - the estimate was computed from the first
        // frame the engine ever saw and then frozen for the life of the engine.
        if (page == 2) {
            val reportedPercent = u8OrNull(50)
            if (reportedPercent != null && reportedPercent != 0x80 && reportedPercent <= 100) {
                batteryPercent = reportedPercent.toDouble()
                hasReportedBatteryPercent = true
            } else if (voltageRaw > 0) {
                calculateLeaperKimBatteryPercent(voltageRaw, hardwareCode)?.let { batteryPercent = it }
            }
        } else if (!hasReportedBatteryPercent && voltageRaw > 0) {
            calculateLeaperKimBatteryPercent(voltageRaw, hardwareCode)?.let { batteryPercent = it }
        }

        if (page == 0 || page == 4) {
            // Bounds are per field, not one guard for the block: Patton's 69-byte page-0 payload
            // carries the MOS temperature and lateral angle but stops before the battery currents,
            // so a single `size` check would either drop all four or read the currents past the end.
            int16BeOrNull(59)
                ?.div(100.0)
                ?.takeIf { it in -40.0..200.0 }
                ?.let { mosTemperature = it }
            int16BeOrNull(57)?.let { lateralAngle = it / 100.0 }
            int16BeOrNull(67)?.let { pedalAngleTrim = it / 100.0 }
            val left = int16BeOrNull(69)
            val right = int16BeOrNull(71)
            if (left != null && right != null) {
                leftBatteryCurrent = abs(left) / 100.0
                rightBatteryCurrent = abs(right) / 100.0
                signedBatteryCurrents[0] = left / 100.0
                signedBatteryCurrents[1] = right / 100.0
            }
        }
        if (page == 2 && size > 47) {
            fallProtectionAngle = signedByte(47).toDouble()
        }
        if (page == 8) {
            settings = parseSettings()
            val adjustment = controlByte(64)
            if (adjustment != null) {
                maxChargeVoltage = LeaperKimMaxChargeVoltage.volts(maxChargeBase(), adjustment) ?: Double.NaN
            }
        }
        if (page == 5) {
            u8OrNull(51)?.let { lockState = it.toDouble() }
        }
        if (page == 8) {
            highSpeedMode = (controlByte(61) ?: 0) > 0
        }
        readFirmwareVersion()?.let { firmwareVersion = it.toDouble() }

        // `@16` is the q-axis (torque) current: `docs/leaperkim-lynxs-protocol.md` matched it
        // against the diagnostic `AQ`, and decoding the co-stream out of the `882583F62376`
        // captures shows `AQ` running -25.6..+49.2 A. So the word is signed, and the sign is the
        // torque direction - see [powerFlowSign] for why it is read against the speed word rather
        // than on its own.
        val phaseCurrentRaw = int16Be(16)
        val speedRaw = int16Be(6)
        val phaseCurrent = abs(phaseCurrentRaw) / 10.0
        val outputRaw = uint16Be(34)
        val outputCurrent = phaseCurrent * outputRaw / 10_000.0
        // Legacy Sherman Max and newer LeaperKim firmware use opposite torque axes.
        // LK5690, 2026-10-24: positive speed/current during acceleration was incorrectly
        // recorded as regeneration (1768.25 Wh over 47.6 km).
        val convention = if (isLegacy) TorqueSignConvention.AgreementIsDriving
            else TorqueSignConvention.AgreementIsBraking
        val flow = powerFlowSign(phaseCurrentRaw, speedRaw, convention)
        return LeaperKimTelemetry(
            speedKmh = abs(speedRaw) / 10.0,
            pwmPercent = outputRaw / 100.0,
            voltage = voltage,
            phaseCurrent = phaseCurrent,
            outputCurrent = flow * outputCurrent,
            power = flow * outputCurrent * voltage,
            temperature = int16Be(18) / 100.0,
            mosTemperature = mosTemperature,
            // Stock firmware writes six `0x80` sentinel bytes into main-frame `@40..45` and puts
            // no motor temperature on the wire. A patched build (see the "motor temperature onto
            // the wire" note in the RE docs) replaces the first four of those with the measured
            // motor probe (`0x20005F18`) at `@40..41` and the modelled coil (`0x20005F1C`) at
            // `@42..43`, big-endian centidegrees.
            //
            // The guard is the wheel's own measuring range, not a round number. The firmware
            // converts the probe through a 27-entry lookup at `0x080156F4` whose result is
            // `23000 - 1000 * index` in centidegrees plus interpolation — so it reads from about
            // -40 C at the cold end to about 220 C at the hot one, and cannot produce more.
            // Anything outside that is the stock sentinel (`0x8080` -> -326.40 C) or noise.
            //
            // An earlier version cut this off at 200 C, and a patched wheel that got hotter than
            // that simply stopped reporting: the reading was thrown away here rather than missing
            // on the wire. A coil above 200 C is exactly when somebody wants to see the number.
            motorTemperature = int16BeOrNull(40)
                ?.div(100.0)
                ?.takeIf { it in -40.0..MotorProbeCeilingCelsius }
                ?: Double.NaN,
            coilTemperature = int16BeOrNull(42)
                ?.div(100.0)
                ?.takeIf { it in -40.0..MotorProbeCeilingCelsius }
                ?: Double.NaN,
            batteryPercent = batteryPercent,
            tripDistanceMeters = distance(8),
            totalDistanceMeters = distance(12),
            batteryCurrent = if (leftBatteryCurrent.isFinite() && rightBatteryCurrent.isFinite()) {
                leftBatteryCurrent + rightBatteryCurrent
            } else {
                Double.NaN
            },
            leftBatteryCurrent = leftBatteryCurrent,
            rightBatteryCurrent = rightBatteryCurrent,
            lateralAngle = lateralAngle,
            carPose = int16Be(32).toDouble(),
            batteryTemperatureMin = currentBatteryTemperatures.minOrNull() ?: Double.NaN,
            batteryTemperatureMax = currentBatteryTemperatures.maxOrNull() ?: Double.NaN,
            dangerSpeedKmh = uint16Be(24) / 10.0,
            stopSpeedKmh = uint16Be(26) / 10.0,
            fallProtectionAngle = fallProtectionAngle,
            rideMode = u8OrNull(31)?.toDouble() ?: Double.NaN,
            shutdownTimeSeconds = uint16Be(20).toDouble(),
            chargeMode = u8OrNull(23)?.toDouble() ?: Double.NaN,
            batteryTempMode = uint16BeOrNull(36)?.toDouble() ?: Double.NaN,
            lockState = lockState,
            hardwareCode = hardwareCode,
            firmwareVersion = firmwareVersion,
            highSpeedMode = highSpeedMode,
            fieldWeakeningPercent = calculateLeaperKimFieldWeakening(
                packVoltage = voltage,
                highSpeedMode = highSpeedMode,
                hardwareCode = hardwareCode,
                firmwareVersion = firmwareVersion.takeIf { it.isFinite() }?.toInt(),
            ) ?: Double.NaN,
            signedSpeedKmh = speedRaw / 10.0,
            signedPhaseCurrent = phaseCurrentRaw / 10.0,
            inputCurrentRaw = if (isLegacy) Double.NaN else int16BeOrNull(38)?.toDouble() ?: Double.NaN,
        )
    }

    private fun ByteArray.parseSettings(): List<LeaperKimSettingCapability> {
        // The same sentinel rule as every other page-8 byte. Reading 0x80 as a base of 128 V put
        // a NOSFET Apex's stock 151.2 V (raw 62) on screen as 134.2 V. The telemetry max-charge
        // voltage resolves the base through the same call, so the two always agree.
        val baseVol = maxChargeBase()
        return SettingDefinitions.map { definition ->
            // 0x80 is the wire "field not reported this cycle" sentinel. Every defined field
            // is always exposed (matching Android's WheelSettingCapability list) - a missing
            // reading is surfaced as isSupported=false rather than dropping the entry, so the
            // UI/AlertCommand picker can show a consistent settings list per wheel model.
            // A derived setting has no byte to read here at all; [allSettingsCapabilities] fills
            // it in from live telemetry, and it stays unsupported until that telemetry arrives.
            val raw = if (definition.isDerived) {
                null
            } else {
                u8OrNull(definition.offset)?.takeUnless { it == 0x80 }
            }
            LeaperKimSettingCapability(
                key = definition.key,
                titleEn = definition.titleEn,
                titleRu = definition.titleRu,
                rawValue = when {
                    raw == null -> 0
                    definition.key == "voltage_correction" && raw >= 0x80 -> raw - 0x100
                    else -> raw
                },
                sourceOffset = definition.offset,
                commandId = definition.commandId,
                minValue = definition.minValue,
                maxValue = definition.maxValue,
                kind = definition.kind,
                baseValue = if (definition.key == "max_charge_voltage") baseVol else null,
                isSupported = raw != null && !definition.isHidden,
            )
        }
    }

    /**
     * The newest reading of every battery temperature sensor the wheel has reported so far, from
     * the same per-sensor maps [bmsSnapshot] publishes. Deliberately not a running min/max: a
     * monotonic accumulator latches a transient sensor spike, and Android rebuilds the engine and
     * replays a window of frames for every snapshot, so a latched spike would sit on the
     * dashboard until it aged out of that window. [updateBmsState] already applies the
     * -40..120 C plausibility filter when it writes these maps, so a garbage reading can never
     * become the min or the max here.
     */
    private val currentBatteryTemperatures: List<Double>
        get() = batteryTemperatures.flatMap { sensors -> sensors.values }

    private fun ByteArray.updateBmsState(page: Int?) {
        val batteryIndex = when (page) {
            in 1..3 -> 0
            in 5..7 -> 1
            else -> return
        }
        val localPage = page!! - batteryIndex * 4
        val profile = hardwareCode?.let(LeaperKimBmsProfiles::get)
        if (localPage == 1) {
            reportedCellCounts[batteryIndex] = u8OrNull(52)
                ?.takeIf { it in 1..120 }
                ?: reportedCellCounts[batteryIndex]
        }
        val expectedCount = reportedCellCounts[batteryIndex]
            ?: profile?.cellCount
            ?: 36

        fun readCells(startIndex: Int, sourceOffset: Int, maxCount: Int) {
            // `size` is already the CRC-stripped payload length, so no trailer allowance here.
            val availableCount = ((size - sourceOffset) / 2).coerceAtLeast(0)
            val count = minOf(maxCount, availableCount, (expectedCount - startIndex).coerceAtLeast(0))
            repeat(count) { offset ->
                uint16BeOrNull(sourceOffset + offset * 2)
                    ?.takeIf { it in 2_500..4_300 }
                    ?.div(1_000.0)
                    ?.let { voltage ->
                        batteryCells[batteryIndex][startIndex + offset + 1] = voltage
                    }
            }
        }

        when (localPage) {
            1 -> readCells(startIndex = 0, sourceOffset = 53, maxCount = 15)
            2 -> readCells(startIndex = 15, sourceOffset = 53, maxCount = 15)
            3 -> {
                repeat(6) { index ->
                    val temperature = int16BeOrNull(47 + index * 2)
                        ?.div(100.0)
                        ?.takeIf { it in -40.0..120.0 }
                        ?: return@repeat
                    val previous = batteryTemperatures[batteryIndex][index + 1]
                    // A sensor cannot instantly fall from a warm pack to exactly zero.
                    // Keep real freezing temperatures, including a gradual crossing of 0 C.
                    if (temperature == 0.0 && previous != null && abs(previous) > 5.0) return@repeat
                    batteryTemperatures[batteryIndex][index + 1] = temperature
                }
                readCells(startIndex = 30, sourceOffset = 59, maxCount = 6)
                if (expectedCount > 36) {
                    readCells(
                        startIndex = 36,
                        sourceOffset = 71,
                        maxCount = expectedCount - 36,
                    )
                }
            }
        }
    }

    private fun ByteArray.readHardwareCode(): String? {
        val first = u8OrNull(30) ?: return null
        val second = u8OrNull(28) ?: return null
        val third = u8OrNull(29) ?: return null
        val version = (first shl 16) or (second shl 8) or third
        return version
            .takeIf { it > 0 }
            ?.toString()
            ?.padStart(6, '0')
            ?.take(4)
    }

    /**
     * Mirrors [readHardwareCode] but returns the full integer rather than the first four
     * digits. Same reading as `leaperKimFirmwareVersion()` in [LeaperKimFieldWeakening].
     */
    private fun ByteArray.readFirmwareVersion(): Int? {
        if (size <= 30) return null
        val first = u8(30)
        val second = u8(28)
        val third = u8(29)
        return (first shl 16) or (second shl 8) or third
    }

    /** Page 8's max-charge base: reported, or known for this frame's firmware, or null. */
    private fun ByteArray.maxChargeBase(): Int? =
        LeaperKimMaxChargeVoltage.baseVolts(controlByte(65), readFirmwareVersion())

    private fun ByteArray.controlByte(offset: Int): Int? =
        u8OrNull(offset)?.takeUnless { it == 0x80 }

    private fun calculateLeaperKimBatteryPercent(voltageRaw: Int, hardwareCode: String?): Double? {
        val sCount = when (hardwareCode) {
            "0050", "0060", "0090", "5010", "5030" -> 36
            // Patton (0040) is 30S with a 126.0 V full charge. It was grouped with the 24S
            // Shermans, which capped the table at 100.80 V while the real wheel idles at 123.72 V
            // (dump loeuc_882583F5CF2B, device LK17344) - every reading above 100.80 V clamped to
            // a flat 100%.
            //
            // Known defect, tracked separately: NosfetAeroTable tops out at 123.75 V (4.125 V per
            // cell), below Patton's real 126.0 V full charge, so the top ~9% of the pack reads as
            // a flat 100%. See docs/leaperkim-lynxs-protocol.md.
            "0070", "5020", "5040", "0040" -> 30
            "0080", "0100" -> 42
            // Sherman-s (0030) joins the 24S Shermans on the evidence of dump
            // loeuc_882583F51F81_unknown_20260819_220233.jsonl (device LK8435, firmware 3015),
            // which idles at 100.86 V on a pack its rider reports as fully charged: 4.2025 V per
            // cell across 24, against an impossible-for-a-full-pack 3.362 V across 30. Without
            // the entry the code fell through to guessSCountFromVoltage, whose "above 100.0 V
            // means 30S" branch reads a *full* 24S pack as a nearly empty 30S one - the wheel
            // showed 14.3% at 100.86 V. Abrams (0020) is the same generation and is suspected of
            // the same fault, but no capture of one exists here, so it stays with the guess.
            "0010", "0011", "0030" -> 24
            else -> {
                reportedCellCounts.filterNotNull().maxOrNull() ?: guessSCountFromVoltage(voltageRaw / 100.0)
            }
        }

        val table = when (sCount) {
            36 -> NosfetApexTable
            30 -> NosfetAeroTable
            42 -> NosfetOryxTable
            24 -> NosfetShermanTable
            else -> return null
        }
        if (voltageRaw >= table.last()) return 100.0
        if (voltageRaw <= table.first()) return 0.0

        val nextIndex = table.indexOfFirst { it > voltageRaw }
        if (nextIndex <= 0) return 0.0

        val prevIndex = nextIndex - 1
        val v0 = table[prevIndex].toDouble()
        val v1 = table[nextIndex].toDouble()

        val fraction = (voltageRaw - v0) / (v1 - v0)
        return prevIndex + fraction
    }

    private fun guessSCountFromVoltage(voltage: Double): Int {
        return if (voltage.isNaN() || voltage <= 0.0) 36
        else if (voltage > 155.0) 42
        else if (voltage > 130.0) 36
        else if (voltage > 100.0) 30
        else 24
    }
}

private data class LeaperKimBmsProfile(
    val profileId: String,
    val modelName: String,
    val batteryCount: Int,
    val cellCount: Int,
    val totalParallelStrings: Int,
) {
    val parallelStringsPerBattery: Int
        get() = totalParallelStrings / batteryCount
}

/**
 * Parallel counts come from a retail catalogue that publishes the pack string per model; see
 * `WheelPackCatalogue`. Before it, they were derived from published pack energy on the assumption
 * of Samsung 21700 cells at 5.0 Ah nominal - `P = Wh / (cells * 3.6 V * 5.0 Ah)` - and the
 * catalogue confirmed every one of those:
 *
 *     model       Wh    cells   nominal Ah   P
 *     Lynx        2700     36        20.8     4.17 -> 4
 *     Lynx-S      2700     36        20.8     4.17 -> 4
 *     Sherman-L   4000     36        30.9     6.17 -> 6
 *     Patton-S    2220     30        20.6     4.11 -> 4
 *     Oryx        4700     42        31.1     6.22 -> 6
 *
 * All five land within 4 % of a whole number and they all overshoot by the same amount, which is
 * the rating being a shade optimistic against 5.0 Ah - so the formula reproduces rather than
 * coincides. Sherman-L's 6P is stated outright by the vendor, which is the one independent check
 * available, and it matches.
 *
 * Four of these were wrong before: Lynx, Sherman-L and Patton-S all carried 2, and Oryx 4. The
 * value is a **pack total**; [LeaperKimBmsProfile.parallelStringsPerBattery] divides it by the
 * battery count, so a Lynx is two batteries of 36S2P making 36S4P.
 *
 * Nosfet packs are left as they were: no published pack energy has been found for them, and the
 * formula needs one. They are marked here so the gap is visible rather than assumed correct.
 */
private val LeaperKimBmsProfiles = mapOf(
    "0050" to LeaperKimBmsProfile("leaperkim_0050", "Lynx", 2, 36, 4),
    "0060" to LeaperKimBmsProfile("leaperkim_0060", "Sherman-L", 2, 36, 6),
    "0070" to LeaperKimBmsProfile("leaperkim_0070", "Patton-S", 2, 30, 4),
    "0080" to LeaperKimBmsProfile("leaperkim_0080", "Oryx", 2, 42, 6),
    "0090" to LeaperKimBmsProfile("leaperkim_0090", "Lynx-S", 2, 36, 4),
    // Sherman-P 010.0.02: 42S6P / 4700 Wh, same modern LeaperKim page layout.
    "0100" to LeaperKimBmsProfile("leaperkim_0100", "Sherman-P", 2, 42, 6),
    // Nosfet, from the same catalogue: Apex 36s4p / 2700 Wh, Aero 30s2p / 1110 Wh,
    // Aeon 36s2p / 1350 Wh. Apex and Aero were already right; Aeon carried 4.
    // Xeno 30s4p / 2200 Wh at 126 V. 5040 is the Xeno because its firmware clamps the charge
    // voltage to a 30s pack and the only other 30s NOSFET, the Aero, is 5020.
    "5010" to LeaperKimBmsProfile("nosfet_5010", "Nosfet Apex", 2, 36, 4),
    "5020" to LeaperKimBmsProfile("nosfet_5020", "Nosfet Aero", 2, 30, 2),
    "5030" to LeaperKimBmsProfile("nosfet_5030", "Nosfet Aeon", 2, 36, 2),
    "5040" to LeaperKimBmsProfile("nosfet_5040", "Nosfet Xeno", 2, 30, 4),
)

private val NosfetApexTable = intArrayOf(
    11340, 11394, 11452, 11509, 11567, 11624, 11682, 11736, 11794, 11851,
    11909, 11966, 12024, 12056, 12092, 12128, 12164, 12200, 12236, 12272,
    12308, 12344, 12380, 12416, 12452, 12481, 12514, 12546, 12578, 12611,
    12643, 12676, 12708, 12740, 12773, 12805, 12838, 12870, 12899, 12928,
    12960, 12989, 13018, 13050, 13079, 13108, 13140, 13169, 13198, 13230,
    13255, 13280, 13306, 13334, 13360, 13385, 13414, 13439, 13464, 13493,
    13518, 13543, 13572, 13608, 13644, 13680, 13716, 13752, 13788, 13824,
    13860, 13896, 13932, 13968, 14004, 14044, 14083, 14123, 14166, 14206,
    14245, 14285, 14328, 14368, 14407, 14447, 14490, 14515, 14544, 14573,
    14598, 14627, 14656, 14681, 14710, 14738, 14764, 14792, 14821, 14850
)

private val NosfetAeroTable = intArrayOf(
    9450, 9495, 9543, 9591, 9639, 9687, 9735, 9780, 9828, 9876, 9924, 9972, 10020, 10047, 10077, 10107, 10137, 10167, 10197, 10227,
    10257, 10287, 10317, 10347, 10377, 10401, 10428, 10455, 10482, 10509, 10536, 10563, 10590, 10617, 10644, 10671, 10698, 10725, 10749, 10773,
    10800, 10824, 10848, 10875, 10899, 10923, 10950, 10974, 10998, 11025, 11046, 11067, 11088, 11112, 11133, 11154, 11178, 11199, 11220, 11244,
    11265, 11286, 11310, 11340, 11370, 11400, 11430, 11460, 11490, 11520, 11550, 11580, 11610, 11640, 11670, 11703, 11736, 11769, 11805, 11838,
    11871, 11904, 11940, 11973, 12006, 12039, 12075, 12096, 12120, 12144, 12165, 12189, 12213, 12234, 12258, 12282, 12303, 12327, 12351, 12375
)

private val NosfetOryxTable = NosfetApexTable.map { (it * 42 / 36) }.toIntArray()

/**
 * 24S: the same per-cell curve as [NosfetApexTable], scaled the way [NosfetOryxTable] is.
 *
 * The three long tables agree per cell to within 0.00011 V, so the curve is one shape and the
 * cell count is the only thing that differs - deriving is reading the same evidence, not
 * inventing a new table.
 *
 * What stood here before was 54 hand-written entries from 75.60 V to 100.80 V, spaced a flat
 * 0.02 V per cell. Percentage is the table *index*, so 54 entries could only ever express
 * 0..53%: a Sherman at 100.0 V - a cell above 4.16 V, all but full - reported 51%, and the
 * whole lower half of the pack read at roughly half its true charge. The ramp was linear
 * besides, which no lithium pack is.
 *
 * The cost of the swap is the ceiling documented for the other three tables: this curve tops
 * out at 4.125 V per cell, 99.00 V here, so the last ~7% of a 24S pack reads a flat 100%.
 * Fixing that needs a stock-charge-limit discharge capture for all four tables at once, not a
 * rescale of this one (docs/leaperkim-lynxs-protocol.md).
 */
private val NosfetShermanTable = NosfetApexTable.map { (it * 24 / 36) }.toIntArray()

private data class SettingDefinition(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    val offset: Int,
    val commandId: Int?,
    val minValue: Int,
    val maxValue: Int,
    val kind: String = "slider",
    /**
     * True when the value has no byte of its own on page 8 and is derived from live telemetry
     * instead. [offset] is then only a display label, never something [parseSettings] may read:
     * `angle_trim`'s "offset 0" is the 0xDC frame marker, which reads as 220 and is not the 0x80
     * "not reported" sentinel, so reading it published 22.0 degrees of pedal trim on a level wheel.
     */
    val isDerived: Boolean = false,
    /**
     * True for a setting that is kept out of every settings list on purpose. It stays in the
     * catalogue, so the full capability list keeps its shape and the byte is still decoded, but
     * it is always reported as `isSupported = false` - the flag every consumer already filters
     * on - and therefore is never displayed, offered to an alert command, or written.
     */
    val isHidden: Boolean = false,
)

private val SettingDefinitions = listOf(
    SettingDefinition("pedal_hardness", "Pedal hardness / ride mode", "Жёсткость педалей", 50, 15, 0, 100),
    SettingDefinition("stop_speed", "Tiltback speed", "Тилтбэк по скорости", 52, 17, 10, 120),
    SettingDefinition("stop_power", "PWM threshold", "Тилтбэк по ШИМ", 53, 18, 30, 100),
    SettingDefinition("speed_alarm", "Alarm speed", "Скоростной аларм", 54, 19, 10, 120),
    SettingDefinition("screen_backlight", "Screen backlight", "Подсветка экрана", 55, 20, 0, 100),
    // commandId is deliberately null (not "we haven't found the write command yet"):
    // LoEUC never exposes gyroscope calibration as something an app control (settings
    // slider, AlertCommand, ...) can trigger remotely, on any wheel - the real procedure
    // requires the wheel to be physically leveled (e.g. hung level) first, which the app
    // has no way to verify. buildSettingCommand() returns null for it unconditionally via
    // the `commandId ?: return null` check below. Do not "fix" this by wiring up a real
    // commandId even if one is reverse-engineered later - see matching comments on
    // SettingControl in iOS WheelDeviceSettingsView.swift and AlertEditorView.swift's
    // writableCommandCapabilities, and the writeSpec = null LeaperKimAnalysis.kt entry
    // on the Android side.
    SettingDefinition("gyro_calibration", "Gyro calibration", "Калибровка гироскопа", 56, null, 0, 255),
    SettingDefinition("transport_mode", "Transport mode", "Транспортный режим", 57, 22, 0, 1, "toggle"),
    SettingDefinition("unit", "Units", "Единицы", 58, 23, 0, 1, "unit"),
    SettingDefinition("voltage_correction", "Voltage correction", "Поправка измерения напряжения", 59, 24, -15, 15),
    SettingDefinition("low_voltage_mode", "Low battery mode", "Старт на низкой батарее", 60, 25, 0, 1, "toggle"),
    SettingDefinition("high_speed_mode", "High speed mode", "Высокоскоростной режим", 61, 26, 0, 1, "toggle"),
    SettingDefinition("angle_trim", "Pedal angle trim", "Угол педалей", 0, 16, -80, 80, isDerived = true),
    SettingDefinition("key_tone", "Button tone volume", "Громкость кнопок", 63, 28, 0, 100),
    SettingDefinition("max_charge_voltage", "Max charge voltage", "Макс. напряжение зарядки", 64, 29, 0, 120),
    SettingDefinition("acc_dec_helper", "Acceleration/deceleration assist", "Помощь при разгоне и торможении", 66, 31, 0, 100),
    SettingDefinition("acc_reduction", "Accelerometer reduction", "CrazyLevel (ослабление акселерометра)", 68, 33, 0, 100),
    // Hidden (product decision, 2026-09-21): the name is a literal rendering of the vendor app's
    // label, no handler for id 34 was found in the firmware, and nobody can say what the value
    // does. A writable row with unknown meaning is worse than no row.
    SettingDefinition("brake_overpressure_alarm", "Brake overpressure alarm", "Тревога давления торможения", 69, 34, 80, 125, isHidden = true),
)

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun ByteArray.u8OrNull(offset: Int): Int? =
    if (offset in indices) u8(offset) else null

private fun ByteArray.uint16Be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

private fun ByteArray.uint16BeOrNull(offset: Int): Int? =
    if (offset >= 0 && offset + 1 < size) uint16Be(offset) else null

private fun ByteArray.int16BeOrNull(offset: Int): Int? {
    val value = uint16BeOrNull(offset) ?: return null
    return if (value and 0x8000 != 0) value - 0x10000 else value
}

private fun ByteArray.int16Be(offset: Int): Int {
    val value = uint16Be(offset)
    return if (value and 0x8000 != 0) value - 0x10000 else value
}

private fun ByteArray.signedByte(offset: Int): Int {
    val value = u8(offset)
    return if (value >= 0x80) value - 0x100 else value
}

private fun ByteArray.distance(offset: Int): Double =
    uint16Be(offset + 2) * 65_536.0 + uint16Be(offset)

private fun ByteArray.withCrc32(): ByteArray {
    val value = crc32()
    return this + byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

/**
 * The hot end of the motor probe's own measuring range, in degrees. The firmware's lookup at
 * `0x080156F4` walks 27 entries and returns `23000 - 1000 * index` centidegrees, so 220 C is the
 * most it can report; a larger number on the wire is not a hotter motor, it is a broken frame.
 */
private const val MotorProbeCeilingCelsius = 220.0

/**
 * The beep every command but the horn asks for: 50 ms at loudness 100 of 1023.
 *
 * `packet[5]` of an `LdAp` frame is a two-decimal-digit beep request - tens pick the duration,
 * units pick the loudness in hundreds - and the wheel sounds it before it even looks at which
 * command the frame carries. See [LeaperKimProtocolEngine.buildHornCommand] for the decode. The
 * vendor app puts `1` here on every command it sends, so a setting write or a light toggle is
 * acknowledged with the quietest tone the firmware has. That is the right level for an
 * acknowledgement; do not raise it.
 */
private const val AckBeepCode: Byte = 0x01

/**
 * The horn's beep: 450 ms at loudness 900 of 1023, the longest and loudest the two-digit spec can
 * ask for (`99` -> duration `90 * 5`, loudness `9 * 100`).
 *
 * Chosen 2026-09-21 because the horn exists to be heard by a pedestrian, and the
 * acknowledgement-level [AckBeepCode] the button shipped with was 100 of 1023 - audible to the
 * rider at a standstill and to nobody else.
 */
private const val HornBeepCode: Byte = 0x63

/** The command family the plugin receiver answers to; stock firmware ignores it. */
private const val PluginLoaderFamily = 0x61
private const val PluginLoaderUnlock = 0
private const val PluginLoaderErase = 1
private const val PluginLoaderWrite = 2

/** Hand the frame to every plugin. The receiver does not look at what is in it. */
private const val PluginLoaderCall = 3

/**
 * The plugin budget cut into flash pages, every one of which the wheel calls.
 *
 * A slot is a page because a page is the erase unit: removing a plugin is then exactly one erase
 * and cannot touch its neighbour. A plugin longer than a page takes the following slots too, and
 * the wheel skips what it has already accounted for by reading the length out of the header.
 *
 * The page above 512 KB on this part is **four** kilobytes, not the two that bank 0 uses. Sizing a
 * slot at 2 KB cost a wheel its plugins on 2026-08-28: installing the second one erased the page
 * it shared with the first, and both went away at once. The firmware says 4 KB itself — its own
 * littlefs volume on internal flash is sixteen blocks of four kilobytes.
 */
private const val PluginSlots = 32
private const val PluginSlotBytes = 4096

/** What the second bank may hold: 128 KB, and 320 KB clear of the wheel's own filesystem. */

/** The `LPLG` word: four bytes, and the last thing an upload writes. */
private const val MagicHalfwords = 2

private const val CRC32_SIZE = 4

/**
 * How deep the kept frame window is. The stock page cycle is eight pages plus the plugin page,
 * so anything shorter than nine could miss the page a plugin writes into. A host that sends a
 * second plugin page now and then sends it in the first page's turn, which puts two of those
 * turns, eighteen frames, between two first pages; the window covers that.
 */
private const val RECENT_FRAME_WINDOW = 24
private const val LegacyFrameSize = 36
private val LegacyFrameLengthRange = 20..46

private fun ByteArray.hasValidCrc32(): Boolean {
    if (size < 8) {
        return false
    }
    val expected = (u8(size - 4).toLong() shl 24) or
        (u8(size - 3).toLong() shl 16) or
        (u8(size - 2).toLong() shl 8) or
        u8(size - 1).toLong()
    return copyOfRange(0, size - 4).crc32() == expected
}

private fun ByteArray.crc32(): Long {
    var crc = 0xFFFFFFFFL
    for (byte in this) {
        crc = crc xor (byte.toLong() and 0xFF)
        repeat(8) {
            crc = if (crc and 1L != 0L) {
                (crc ushr 1) xor 0xEDB88320L
            } else {
                crc ushr 1
            }
        }
    }
    return crc xor 0xFFFFFFFFL
}

@ExperimentalWriteApi
fun buildLeaperKimLowLightCommand(enabled: Boolean): ByteArray {
    val text = if (enabled) "SetLightON" else "SetLightOFF"
    return text.encodeToByteArray()
}
