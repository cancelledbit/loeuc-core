package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import pw.vasilevskiy.loeuc.shared.api.InmotionLegacySettingKeys
import kotlin.math.roundToInt

/**
 * One legacy InMotion setting as both platforms see it. Same shape as [KingSongSettingCapability],
 * plus the value as text in both languages, because a tilt in tenths of a degree or a ride mode
 * number is not what a rider should read.
 *
 * A row is writable when [commandId] is not null; its [kind] is then one of the kinds every
 * catalogue uses (`slider`, `toggle`, `choice`) and [minValue]..[maxValue] is the model's range.
 * A row that cannot be written has kind [KIND_READONLY] and a zero range.
 */
data class InmotionLegacySettingCapability(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    /** The wheel's value in the unit of [minValue]..[maxValue]: km/h, tenths of a degree, 1 for on. */
    val rawValue: Int,
    val valueTextEn: String,
    val valueTextRu: String,
    /** Byte offset of the value inside the slow info payload, for diagnostics. */
    val sourceOffset: Int,
    /** The message id the write goes out on, or null when this wheel cannot take the write. */
    val commandId: Int?,
    val minValue: Int,
    val maxValue: Int,
    val step: Int,
    val kind: String,
    val isSupported: Boolean = true,
    val options: List<InmotionLegacySettingOption> = emptyList(),
)

data class InmotionLegacySettingOption(
    val value: Int,
    val titleEn: String,
    val titleRu: String,
)

/** What a legacy settings write resolved to. */
sealed interface InmotionLegacySettingWrite {
    /** One complete frame, to be queued with [InmotionLegacyProtocolEngine.enqueue]. */
    class Command(val setting: InmotionLegacySetting, bytes: ByteArray) : InmotionLegacySettingWrite {
        private val wire: ByteArray = bytes.copyOf()
        val bytes: ByteArray get() = wire.copyOf()
    }

    data class Rejected(val reason: String) : InmotionLegacySettingWrite
}

/**
 * The settings a legacy InMotion's slow info reports, and the command each one writes.
 *
 * Everything that decides whether a row is writable - the model gates, the ranges, the rule that
 * the wheel must report the field back - is [InmotionLegacyCommands]'; this object only names the
 * rows and resolves a key to a builder. Every setting lives in slow info, so the read that
 * confirms any write is [InmotionLegacyRequests.slowInfo], which the session sends on its own
 * after each command.
 */
@OptIn(ExperimentalWriteApi::class)
object InmotionLegacySettingsCatalog {
    // The same kind strings every other brand's catalogue uses; the iOS screen switches on them.
    private const val Slider = "slider"
    private const val Toggle = "toggle"
    private const val Choice = "choice"

    /** Keys in the order [capabilities] lists them. */
    val keys: List<String> = InmotionLegacySettingKeys.all

    val rideModeOptions: List<InmotionLegacySettingOption> = listOf(
        InmotionLegacySettingOption(value = 0, titleEn = "Comfort", titleRu = "Комфорт"),
        InmotionLegacySettingOption(value = 1, titleEn = "Classic", titleRu = "Классика"),
    )

    /** The setting behind [key], or null for a key this family does not have. */
    fun settingFor(key: String): InmotionLegacySetting? = when (key) {
        InmotionLegacySettingKeys.Headlight -> InmotionLegacySetting.HEADLIGHT
        InmotionLegacySettingKeys.DecorativeLight -> InmotionLegacySetting.DECORATIVE_LIGHT
        InmotionLegacySettingKeys.SpeakerVolume -> InmotionLegacySetting.SPEAKER_VOLUME
        InmotionLegacySettingKeys.RideMode -> InmotionLegacySetting.RIDE_MODE
        InmotionLegacySettingKeys.MaxSpeed -> InmotionLegacySetting.MAX_SPEED
        InmotionLegacySettingKeys.PedalTilt -> InmotionLegacySetting.PEDAL_TILT
        InmotionLegacySettingKeys.HandleButton -> InmotionLegacySetting.HANDLE_BUTTON
        InmotionLegacySettingKeys.PedalHardness -> InmotionLegacySetting.PEDAL_SENSITIVITY
        else -> null
    }

    fun keyFor(setting: InmotionLegacySetting): String = when (setting) {
        InmotionLegacySetting.HEADLIGHT -> InmotionLegacySettingKeys.Headlight
        InmotionLegacySetting.DECORATIVE_LIGHT -> InmotionLegacySettingKeys.DecorativeLight
        InmotionLegacySetting.SPEAKER_VOLUME -> InmotionLegacySettingKeys.SpeakerVolume
        InmotionLegacySetting.RIDE_MODE -> InmotionLegacySettingKeys.RideMode
        InmotionLegacySetting.MAX_SPEED -> InmotionLegacySettingKeys.MaxSpeed
        InmotionLegacySetting.PEDAL_TILT -> InmotionLegacySettingKeys.PedalTilt
        InmotionLegacySetting.HANDLE_BUTTON -> InmotionLegacySettingKeys.HandleButton
        InmotionLegacySetting.PEDAL_SENSITIVITY -> InmotionLegacySettingKeys.PedalHardness
    }

    /** Every settings read: slow info carries all of them, whatever [key] is. */
    fun readsFor(key: String?): List<ByteArray> = listOf(InmotionLegacyRequests.slowInfo())

    /**
     * The rows [settings] carries, each writable when [InmotionLegacyCommands.writableSettings]
     * allows it for [identity]. A field the payload is too short to carry - most of them on a V5F -
     * is left out rather than shown as zero; an unknown model gets its rows and no writes.
     *
     * Raw values: pedal tilt in tenths of a degree; ride mode 1 for classic, 0 for comfort; the
     * handle button 1 when enabled.
     */
    fun capabilities(
        identity: InmotionLegacyIdentity?,
        settings: InmotionLegacySettings,
    ): List<InmotionLegacySettingCapability> {
        val model = identity?.model
        val writable = InmotionLegacyCommands.writableSettings(identity, settings)

        fun row(
            setting: InmotionLegacySetting,
            en: String,
            ru: String,
            raw: Int,
            textEn: String,
            textRu: String,
            offset: Int,
            options: List<InmotionLegacySettingOption> = emptyList(),
        ): InmotionLegacySettingCapability {
            val range = model?.takeIf { setting in writable }?.let { InmotionLegacyCommands.range(setting, it) }
            return InmotionLegacySettingCapability(
                key = keyFor(setting),
                titleEn = en,
                titleRu = ru,
                rawValue = raw,
                valueTextEn = textEn,
                valueTextRu = textRu,
                sourceOffset = offset,
                commandId = range?.let { commandIdFor(setting) },
                minValue = range?.first ?: 0,
                maxValue = range?.last ?: 0,
                step = 1,
                kind = when {
                    range == null -> KIND_READONLY
                    options.isNotEmpty() -> Choice
                    range == 0..1 -> Toggle
                    else -> Slider
                },
                options = options,
            )
        }

        fun onOffEn(value: Boolean) = if (value) "ON" else "OFF"
        fun onOffRu(value: Boolean) = if (value) "ВКЛ" else "ВЫКЛ"

        return buildList {
            settings.maxSpeedKmh?.let {
                add(row(InmotionLegacySetting.MAX_SPEED, "Max speed", "Максимальная скорость", it, "$it km/h", "$it км/ч", 60))
            }
            settings.pedalHardness?.let {
                add(row(InmotionLegacySetting.PEDAL_SENSITIVITY, "Pedal hardness", "Жёсткость педалей", it, "$it", "$it", 124))
            }
            settings.pedalTiltDegrees?.let {
                add(
                    row(
                        InmotionLegacySetting.PEDAL_TILT,
                        "Pedal horizon",
                        "Горизонт педалей",
                        (it * 10.0).roundToInt(),
                        "$it°",
                        "$it°",
                        56,
                    ),
                )
            }
            settings.rideModeClassic?.let {
                add(
                    row(
                        InmotionLegacySetting.RIDE_MODE,
                        "Ride mode",
                        "Режим езды",
                        if (it) 1 else 0,
                        if (it) "Classic" else "Comfort",
                        if (it) "Классика" else "Комфорт",
                        132,
                        options = rideModeOptions,
                    ),
                )
            }
            settings.headlightOn?.let {
                add(row(InmotionLegacySetting.HEADLIGHT, "Headlight", "Фара", if (it) 1 else 0, onOffEn(it), onOffRu(it), 80))
            }
            settings.decorativeLightOn?.let {
                add(
                    row(
                        InmotionLegacySetting.DECORATIVE_LIGHT,
                        "Decorative light",
                        "Декоративная подсветка",
                        if (it) 1 else 0,
                        onOffEn(it),
                        onOffRu(it),
                        130,
                    ),
                )
            }
            settings.speakerVolume?.let {
                add(row(InmotionLegacySetting.SPEAKER_VOLUME, "Speaker volume", "Громкость", it, "$it %", "$it %", 125))
            }
            settings.handleButtonDisabled?.let { disabled ->
                // Named after what the reference client calls byte 129; the official app shows the
                // same byte as "lift-up detection" with the opposite polarity.
                add(
                    row(
                        InmotionLegacySetting.HANDLE_BUTTON,
                        "Handle button",
                        "Кнопка на ручке",
                        if (disabled) 0 else 1,
                        onOffEn(!disabled),
                        onOffRu(!disabled),
                        129,
                    ),
                )
            }
        }
    }

    /**
     * The command that applies [value] to [key] on the wheel that reported [identity] and
     * [settings]. Rejected when the key is unknown, the model is unknown or lacks the setting, the
     * wheel does not report the field back, or the value is outside the model's range - the same
     * gates that decide whether [capabilities] marks the row writable.
     *
     * Values are in the units the rows advertise. The bytes go to
     * [InmotionLegacyProtocolEngine.enqueue], not straight to the characteristic: the session holds
     * them until the wheel is free and re-reads slow info afterwards.
     */
    @ExperimentalWriteApi
    fun write(
        key: String,
        value: Int,
        identity: InmotionLegacyIdentity?,
        settings: InmotionLegacySettings?,
    ): InmotionLegacySettingWrite {
        val setting = settingFor(key) ?: return InmotionLegacySettingWrite.Rejected("Unknown legacy InMotion setting $key.")
        val model = identity?.model ?: return InmotionLegacySettingWrite.Rejected("Wheel model is unknown.")
        if (InmotionLegacyCommands.range(setting, model) == null) {
            return InmotionLegacySettingWrite.Rejected("${model.displayName} has no such setting.")
        }
        if (setting !in InmotionLegacyCommands.writableSettings(identity, settings)) {
            return InmotionLegacySettingWrite.Rejected("The wheel does not report this setting back.")
        }
        return write(key, value, model)
    }

    /**
     * The command for [key] and [value] on [model], checked against the model's gate and range
     * only. For a caller that already built its rows with [capabilities] and kept the model, so the
     * readback check has been made; everyone else should use the overload that takes the wheel's
     * identity and settings.
     */
    @ExperimentalWriteApi
    fun write(key: String, value: Int, model: InmotionLegacyModel): InmotionLegacySettingWrite {
        val setting = settingFor(key) ?: return InmotionLegacySettingWrite.Rejected("Unknown legacy InMotion setting $key.")
        val range = InmotionLegacyCommands.range(setting, model)
            ?: return InmotionLegacySettingWrite.Rejected("${model.displayName} has no such setting.")
        if (value !in range) {
            return InmotionLegacySettingWrite.Rejected("Value must be ${range.first}..${range.last}.")
        }
        val bytes = InmotionLegacyCommands.build(setting, value, model)
            ?: return InmotionLegacySettingWrite.Rejected("Value is outside validated range.")
        return InmotionLegacySettingWrite.Command(setting, bytes)
    }

    private fun commandIdFor(setting: InmotionLegacySetting): Int = when (setting) {
        InmotionLegacySetting.HEADLIGHT -> InmotionLegacyCommands.HEADLIGHT_ID
        InmotionLegacySetting.DECORATIVE_LIGHT -> InmotionLegacyCommands.REMOTE_CONTROL_ID
        InmotionLegacySetting.SPEAKER_VOLUME -> InmotionLegacyCommands.SPEAKER_VOLUME_ID
        InmotionLegacySetting.HANDLE_BUTTON -> InmotionLegacyCommands.HANDLE_BUTTON_ID
        InmotionLegacySetting.RIDE_MODE,
        InmotionLegacySetting.MAX_SPEED,
        InmotionLegacySetting.PEDAL_TILT,
        InmotionLegacySetting.PEDAL_SENSITIVITY,
        -> InmotionLegacyCommands.RIDE_SETTINGS_ID
    }
}
