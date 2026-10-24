package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Display-only rows read straight off an InMotion V2 settings block, next to the named fields
 * [InmotionProtocolEngine.settingsCapabilities] decodes.
 *
 * Three kinds, all [KIND_READONLY] - none of them has a write command:
 * - the two V12 HS/HT/Pro fields that have no known write path: the turn-signal bit of the
 *   status byte and the low/high beam switch speed, whose scale is unconfirmed;
 * - the length of the block, which is how a block of an unexpected size is noticed;
 * - one row per byte, only while the model's block has no named fields at all. Where the block
 *   does parse the byte dump is fifty rows of noise under the named ones, so it is left out.
 *
 * Unlike the named capabilities, [InmotionSettingCapability.sourceOffset] of these rows is an
 * index into the whole frame, not into the payload: `payloadOffset` is where the payload
 * starts in the frame, so the offset points at the byte a capture shows at that index. The
 * length row points at the frame's own length byte.
 *
 * A pure function of its arguments so a platform that replays a frame history (Android) and
 * one that keeps a live engine (iOS, through [InmotionProtocolEngine.settingsBlockRows]) build
 * exactly the same rows.
 */
object InmotionSettingsBlockRows {
    const val KEY_TURN_LIGHT_STATE = "inmotion_turn_light_state"
    const val KEY_BEAM_SWITCH_SPEED_THRESHOLD = "inmotion_auto_low_high_beam_switch_speed_thr"
    const val KEY_PAYLOAD_LENGTH = "inmotion_settings_payload_length"
    const val KEY_RAW_BYTE_PREFIX = "inmotion_setting_byte_"

    /** Index of the length byte in a V2 frame. */
    private const val FRAME_LENGTH_OFFSET = 3
    private const val V12_STATUS_BYTE = 32
    private const val V12_BEAM_SWITCH_SPEED = 28
    private val V12_MODELS = setOf(71, 72, 73)

    /**
     * @param modelId the id from the main-info answer (`series * 10 + type`), null or an
     *   unlisted number when the model is not known
     * @param payload the settings block, without the frame header and the command field
     * @param payloadOffset index of `payload[0]` in the frame
     * @param hasNamedFields whether the engine decoded any named field from this block
     */
    fun rows(
        modelId: Int?,
        payload: ByteArray,
        payloadOffset: Int,
        hasNamedFields: Boolean,
    ): List<InmotionSettingCapability> = buildList {
        if (modelId in V12_MODELS) {
            if (payload.size > V12_STATUS_BYTE) {
                val on = (payload.u8(V12_STATUS_BYTE) shr 1) and 1
                add(
                    readOnly(
                        key = KEY_TURN_LIGHT_STATE,
                        titleEn = "Turn signal active",
                        titleRu = "Активен поворотник",
                        rawValue = on,
                        sourceOffset = payloadOffset + V12_STATUS_BYTE,
                        valueTextEn = if (on == 1) "ON" else "OFF",
                        valueTextRu = null,
                    ),
                )
            }
            if (payload.size >= V12_BEAM_SWITCH_SPEED + 2) {
                val raw = payload.u8(V12_BEAM_SWITCH_SPEED) or (payload.u8(V12_BEAM_SWITCH_SPEED + 1) shl 8)
                add(
                    readOnly(
                        key = KEY_BEAM_SWITCH_SPEED_THRESHOLD,
                        titleEn = "Auto low/high beam switch speed (raw, scale unconfirmed)",
                        titleRu = "Скорость переключения ближний/дальний",
                        rawValue = raw,
                        sourceOffset = payloadOffset + V12_BEAM_SWITCH_SPEED,
                        valueTextEn = "raw $raw",
                        valueTextRu = "$raw (ед. колеса)",
                    ),
                )
            }
        }
        add(
            readOnly(
                key = KEY_PAYLOAD_LENGTH,
                titleEn = "Settings payload length",
                titleRu = "Размер блока настроек",
                rawValue = payload.size,
                sourceOffset = FRAME_LENGTH_OFFSET,
                valueTextEn = "${payload.size} bytes",
                valueTextRu = null,
            ),
        )
        if (!hasNamedFields) {
            val model = modelDisplayName(modelId)
            payload.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xFF
                add(
                    readOnly(
                        key = "$KEY_RAW_BYTE_PREFIX$index",
                        titleEn = "$model: raw setting byte $index",
                        titleRu = "$model: байт настройки $index",
                        rawValue = value,
                        sourceOffset = payloadOffset + index,
                        valueTextEn = "0x${value.toString(16).uppercase().padStart(2, '0')}",
                        valueTextRu = null,
                    ),
                )
            }
        }
    }

    /** The name a raw byte row is titled with; the bare brand for a model nobody has listed. */
    fun modelDisplayName(modelId: Int?): String = when (modelId) {
        61 -> "Inmotion V11"
        62 -> "Inmotion V11y"
        71 -> "Inmotion V12 HS"
        72 -> "Inmotion V12 HT"
        73 -> "Inmotion V12 PRO"
        81 -> "Inmotion V13"
        82 -> "Inmotion V13 PRO"
        91 -> "Inmotion V14 50GB"
        92 -> "Inmotion V14 50S"
        111 -> "Inmotion V12S"
        121 -> "Inmotion V9"
        131 -> "Inmotion P6"
        else -> "Inmotion"
    }

    private fun readOnly(
        key: String,
        titleEn: String,
        titleRu: String,
        rawValue: Int,
        sourceOffset: Int,
        valueTextEn: String?,
        valueTextRu: String?,
    ) = InmotionSettingCapability(
        key = key,
        titleEn = titleEn,
        titleRu = titleRu,
        rawValue = rawValue,
        sourceOffset = sourceOffset,
        commandId = 0,
        minValue = 0,
        maxValue = 0,
        kind = KIND_READONLY,
        valueTextEn = valueTextEn,
        valueTextRu = valueTextRu,
    )

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
}
