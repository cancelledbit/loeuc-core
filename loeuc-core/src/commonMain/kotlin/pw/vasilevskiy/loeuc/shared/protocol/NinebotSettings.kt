package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/**
 * One decoded row of a Ninebot settings block.
 *
 * Mirrors [InmotionSettingCapability] so both brands map onto the same platform-side
 * `WheelSettingCapability` without a second conversion path.
 */
data class NinebotSettingCapability(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    /** In display units - km/h for speeds, 0-127 for volume - not in wheel units. */
    val rawValue: Int,
    val valueText: String,
    val sourceOffset: Int,
    /** The parameter id a write targets. For a packed bit this is the whole word's id. */
    val commandId: Int,
    val minValue: Int,
    val maxValue: Int,
    /** `"toggle"`, `"slider"`, or [KIND_READONLY]. */
    val kind: String,
)

/** How a value is laid out in the data field of a write. */
internal enum class NinebotWriteShape {
    /** Two bytes, little-endian. Every numeric parameter, and every rebuilt bit mask. */
    WordLe,

    /**
     * A single byte. Limited mode is the only parameter the wheel takes this way; sending it
     * as a word is untested and there is no reason to risk it.
     */
    SingleByte,

    /**
     * Four bytes, `F0 <value> 00 00`. The `0xF0` looks like a "this slot is set" marker: a
     * colour written as a plain word does nothing on a real wheel.
     */
    LedColor,
}

/**
 * One entry of the settings catalog.
 *
 * The whole Ninebot settings surface is this list. Because [NinebotParamBlock] addresses a value
 * by parameter id, a row needs no offsets: the id it writes to is the id it reads from.
 */
internal data class NinebotSettingRow(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    /** Base parameter id of the block this row lives in. */
    val blockBase: Int,
    val paramId: Int,
    val kind: String,
    val minValue: Int,
    val maxValue: Int,
    /** Bit position when the row is one flag of a packed word. */
    val bit: Int? = null,
    /** Wheel units per display unit: 100 for km/h, 8 for speaker volume, 1 otherwise. */
    val scale: Int = 1,
    val shape: NinebotWriteShape = NinebotWriteShape.WordLe,
    /** Reads the value out of the byte the matching write puts it in, rather than the slot. */
    val ledColorSlot: Boolean = false,
    val unit: String = "",
)

/**
 * Every setting a Ninebot Z exposes, and where it lives.
 *
 * Two of the three blocks pack several settings into one 16-bit word - `0x7C` carries the three
 * alarm enables, `0xD3` the five drive flags - so those rows share a [paramId] and differ only
 * by [NinebotSettingRow.bit]. A write to any one of them rebuilds the whole word from the last
 * block that was read, which is why [NinebotSettingsCatalog.buildCommand] needs the blocks and
 * not just the value.
 */
internal object NinebotSettingsCatalog {
    /** The blocks, in the order the connect sequence reads them. */
    val blockBaseIds: List<Int> = listOf(
        NinebotParam.LockMode,
        NinebotParam.LedMode,
        NinebotParam.SpeakerVolume,
    )

    /** How many bytes to ask for per block: enough to cover its last field. */
    fun blockLength(baseParamId: Int): Int = when (baseParamId) {
        NinebotParam.LockMode -> 0x20
        NinebotParam.LedMode -> 0x1C
        NinebotParam.SpeakerVolume -> 0x02
        else -> 0x02
    }

    val rows: List<NinebotSettingRow> = listOf(
        // --- block 0x70
        NinebotSettingRow(
            key = "ninebot_lock_mode",
            titleEn = "Lock mode",
            titleRu = "Режим блокировки",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.LockMode,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
        ),
        NinebotSettingRow(
            key = "ninebot_limited_mode",
            titleEn = "Speed limit mode",
            titleRu = "Режим ограничения скорости",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.LimitedMode,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            shape = NinebotWriteShape.SingleByte,
        ),
        NinebotSettingRow(
            key = "ninebot_limited_speed",
            titleEn = "Speed limit",
            titleRu = "Ограничение скорости",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.LimitedSpeed,
            kind = "slider",
            minValue = 0,
            maxValue = 65,
            scale = 100,
            unit = "km/h",
        ),
        NinebotSettingRow(
            key = "ninebot_limited_speed_first_km",
            titleEn = "Speed limit, first km",
            titleRu = "Ограничение на первом километре",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.LimitedSpeedFirstKm,
            // Present in the block and decodable, but no write has ever been demonstrated for
            // it - WheelLog reads it and marks it uncertain. Shown, never written.
            kind = KIND_READONLY,
            minValue = 0,
            maxValue = 65,
            scale = 100,
            unit = "km/h",
        ),
        NinebotSettingRow(
            key = "ninebot_alarm_1_enabled",
            titleEn = "Speed alarm 1",
            titleRu = "Сигнал скорости 1",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.Alarms,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            bit = 0,
        ),
        NinebotSettingRow(
            key = "ninebot_alarm_2_enabled",
            titleEn = "Speed alarm 2",
            titleRu = "Сигнал скорости 2",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.Alarms,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            bit = 1,
        ),
        NinebotSettingRow(
            key = "ninebot_alarm_3_enabled",
            titleEn = "Speed alarm 3",
            titleRu = "Сигнал скорости 3",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.Alarms,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            bit = 2,
        ),
        NinebotSettingRow(
            key = "ninebot_alarm_1_speed",
            titleEn = "Speed alarm 1 threshold",
            titleRu = "Порог сигнала 1",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.Alarm1Speed,
            kind = "slider",
            minValue = 0,
            maxValue = 65,
            scale = 100,
            unit = "km/h",
        ),
        NinebotSettingRow(
            key = "ninebot_alarm_2_speed",
            titleEn = "Speed alarm 2 threshold",
            titleRu = "Порог сигнала 2",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.Alarm2Speed,
            kind = "slider",
            minValue = 0,
            maxValue = 65,
            scale = 100,
            unit = "km/h",
        ),
        NinebotSettingRow(
            key = "ninebot_alarm_3_speed",
            titleEn = "Speed alarm 3 threshold",
            titleRu = "Порог сигнала 3",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.Alarm3Speed,
            kind = "slider",
            minValue = 0,
            maxValue = 65,
            scale = 100,
            unit = "km/h",
        ),

        // --- block 0xC6
        NinebotSettingRow(
            key = "ninebot_led_mode",
            titleEn = "LED mode",
            titleRu = "Режим подсветки",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.LedMode,
            kind = "slider",
            minValue = 0,
            maxValue = 7,
        ),
        NinebotSettingRow(
            key = "ninebot_led_color_1",
            titleEn = "LED colour 1",
            titleRu = "Цвет подсветки 1",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.LedColor1,
            kind = "slider",
            minValue = 0,
            maxValue = 255,
            shape = NinebotWriteShape.LedColor,
            ledColorSlot = true,
        ),
        NinebotSettingRow(
            key = "ninebot_led_color_2",
            titleEn = "LED colour 2",
            titleRu = "Цвет подсветки 2",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.LedColor2,
            kind = "slider",
            minValue = 0,
            maxValue = 255,
            shape = NinebotWriteShape.LedColor,
            ledColorSlot = true,
        ),
        NinebotSettingRow(
            key = "ninebot_led_color_3",
            titleEn = "LED colour 3",
            titleRu = "Цвет подсветки 3",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.LedColor3,
            kind = "slider",
            minValue = 0,
            maxValue = 255,
            shape = NinebotWriteShape.LedColor,
            ledColorSlot = true,
        ),
        NinebotSettingRow(
            key = "ninebot_led_color_4",
            titleEn = "LED colour 4",
            titleRu = "Цвет подсветки 4",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.LedColor4,
            kind = "slider",
            minValue = 0,
            maxValue = 255,
            shape = NinebotWriteShape.LedColor,
            ledColorSlot = true,
        ),
        NinebotSettingRow(
            key = "ninebot_pedal_sensitivity",
            titleEn = "Pedal sensitivity",
            titleRu = "Чувствительность педалей",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.PedalSensitivity,
            kind = "slider",
            minValue = 0,
            maxValue = 4,
        ),
        NinebotSettingRow(
            key = "ninebot_headlight",
            titleEn = "Headlight",
            titleRu = "Фара",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.DriveFlags,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            bit = NinebotDriveFlag.Headlight,
        ),
        NinebotSettingRow(
            key = "ninebot_drl",
            titleEn = "Daytime running light",
            titleRu = "Дневные ходовые огни",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.DriveFlags,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            bit = NinebotDriveFlag.DaytimeRunningLight,
        ),
        NinebotSettingRow(
            key = "ninebot_tail_light",
            titleEn = "Tail light",
            titleRu = "Габаритный огонь",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.DriveFlags,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            bit = NinebotDriveFlag.TailLight,
        ),
        NinebotSettingRow(
            key = "ninebot_handle_button",
            titleEn = "Handle button",
            titleRu = "Кнопка ручки",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.DriveFlags,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            bit = NinebotDriveFlag.HandleButton,
        ),
        NinebotSettingRow(
            key = "ninebot_brake_assist",
            titleEn = "Brake assist",
            titleRu = "Помощь при торможении",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.DriveFlags,
            kind = "toggle",
            minValue = 0,
            maxValue = 1,
            bit = NinebotDriveFlag.BrakeAssist,
        ),

        // --- the packed words themselves, shown so a write can rebuild them exactly
        //
        // A flag write has to send the whole 16-bit register. Rebuilding it from the five
        // decoded flags alone would zero every bit above them - bits nobody has identified yet,
        // which the wheel is presumably using for something. Carrying the raw word as its own
        // read-only row means the exact value the wheel reported goes back out, with only the
        // one bit changed.
        NinebotSettingRow(
            key = "ninebot_drive_flags_word",
            titleEn = "Drive flags (raw)",
            titleRu = "Флаги движения (сырое)",
            blockBase = NinebotParam.LedMode,
            paramId = NinebotParam.DriveFlags,
            kind = KIND_READONLY,
            minValue = 0,
            maxValue = 0xFFFF,
        ),
        NinebotSettingRow(
            key = "ninebot_alarm_mask_word",
            titleEn = "Alarm mask (raw)",
            titleRu = "Маска сигналов (сырое)",
            blockBase = NinebotParam.LockMode,
            paramId = NinebotParam.Alarms,
            kind = KIND_READONLY,
            minValue = 0,
            maxValue = 0xFFFF,
        ),

        // --- block 0xF5
        NinebotSettingRow(
            key = "ninebot_speaker_volume",
            titleEn = "Speaker volume",
            titleRu = "Громкость динамика",
            blockBase = NinebotParam.SpeakerVolume,
            paramId = NinebotParam.SpeakerVolume,
            kind = "slider",
            minValue = 0,
            maxValue = 127,
            scale = 8,
        ),
    )

    private val rowsByKey: Map<String, NinebotSettingRow> = rows.associateBy(NinebotSettingRow::key)

    fun capabilities(blocks: Map<Int, NinebotParamBlock>): List<NinebotSettingCapability> {
        return rows.mapNotNull { row ->
            val block = blocks[row.blockBase] ?: return@mapNotNull null
            val offset = block.offsetOf(row.paramId) ?: return@mapNotNull null
            val value = row.read(block) ?: return@mapNotNull null
            NinebotSettingCapability(
                key = row.key,
                titleEn = row.titleEn,
                titleRu = row.titleRu,
                rawValue = value,
                valueText = row.format(value),
                sourceOffset = offset,
                commandId = row.paramId,
                minValue = row.minValue,
                maxValue = row.maxValue,
                kind = row.kind,
            )
        }
    }

    fun buildCommand(
        key: String,
        value: Int,
        blocks: Map<Int, NinebotParamBlock>,
        keystream: NinebotKeystream = NinebotKeystream.Identity,
    ): ByteArray? = buildCommand(key, value, currentWord(key, blocks), keystream)

    /**
     * The bytes that set [key] to [value], or null when the key is unknown, read-only, out of
     * range, or - for a packed flag - when [currentWord] is missing.
     *
     * A packed flag cannot be written on its own: the wheel takes the whole 16-bit register, so
     * the value it currently holds has to come in. Writing without it would clear every other
     * flag sharing that register, including the ones nobody has identified yet.
     */
    fun buildCommand(
        key: String,
        value: Int,
        currentWord: Int?,
        keystream: NinebotKeystream = NinebotKeystream.Identity,
    ): ByteArray? {
        val row = rowsByKey[key] ?: return null
        if (row.kind == KIND_READONLY) return null
        if (value < row.minValue || value > row.maxValue) return null

        val data = when {
            row.bit != null -> {
                val current = currentWord ?: return null
                val updated = if (value != 0) {
                    current or (1 shl row.bit)
                } else {
                    current and (1 shl row.bit).inv()
                }
                wordLe(updated)
            }

            row.shape == NinebotWriteShape.SingleByte -> byteArrayOf(value.toByte())
            row.shape == NinebotWriteShape.LedColor ->
                byteArrayOf(0xF0.toByte(), value.toByte(), 0x00, 0x00)

            else -> wordLe(value * row.scale)
        }

        return NinebotFrameCodec.build(
            destination = NinebotAddress.Controller,
            command = NinebotCommand.Write,
            param = row.paramId,
            data = data,
            keystream = keystream,
        )
    }

    /** The register [key] is a flag of, as the wheel last reported it. */
    fun currentWord(key: String, blocks: Map<Int, NinebotParamBlock>): Int? {
        val row = rowsByKey[key] ?: return null
        if (row.bit == null) return null
        return blocks[row.blockBase]?.value(row.paramId)
    }

    /** Key of the read-only row carrying the register [key] is a flag of, if it is one. */
    fun packedWordKey(key: String): String? {
        val row = rowsByKey[key] ?: return null
        if (row.bit == null) return null
        return rows.firstOrNull { candidate ->
            candidate.kind == KIND_READONLY &&
                candidate.bit == null &&
                candidate.paramId == row.paramId &&
                candidate.blockBase == row.blockBase
        }?.key
    }

    /** The block read that refreshes [key], so a write can be confirmed against the wheel. */
    fun readBackBlockBase(key: String): Int? = rowsByKey[key]?.blockBase

    private fun wordLe(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun NinebotSettingRow.read(block: NinebotParamBlock): Int? {
        bit?.let { return block.bit(paramId, it) }
        if (ledColorSlot) {
            // The colour occupies four bytes and a write puts the value in the second of them,
            // behind an `F0` marker. Reading the same byte is what makes the screen agree with
            // what was just written; reading the 16-bit slot returns the marker instead.
            return block.byte(paramId, byteOffset = 1)
        }
        val value = block.value(paramId) ?: return null
        return if (scale == 1) value else value / scale
    }

    private fun NinebotSettingRow.format(value: Int): String = when {
        kind == "toggle" -> if (value != 0) "ON" else "OFF"
        unit.isNotEmpty() -> "$value $unit"
        else -> value.toString()
    }
}

/**
 * Builds a Ninebot settings write without a live engine.
 *
 * Android decodes from a window of stored frames rather than from a running engine, so its
 * decoder has no session state to consult - and giving the decoder `object` some would leak one
 * wheel's register values into the next connection. [currentWord] comes from the read-only row
 * [ninebotPackedWordKey] names, which travels alongside the flags in the same capability list.
 */
@ExperimentalWriteApi
fun buildNinebotSettingCommand(key: String, value: Int, currentWord: Int? = null): ByteArray? =
    NinebotSettingsCatalog.buildCommand(key, value, currentWord, NinebotKeystream.Identity)

/** The capability key holding the packed register [settingKey] is a flag of, or null. */
fun ninebotPackedWordKey(settingKey: String): String? =
    NinebotSettingsCatalog.packedWordKey(settingKey)
