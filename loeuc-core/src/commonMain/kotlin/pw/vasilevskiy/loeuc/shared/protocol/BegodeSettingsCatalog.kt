package pw.vasilevskiy.loeuc.shared.protocol

/**
 * One Begode setting as both platforms see it.
 *
 * Same shape as [LeaperKimSettingCapability] on purpose: the phone screens are generic over
 * this, and a second shape would mean a second screen.
 *
 * [rawValue] is `null` where the wheel does not report the setting back. That is not a gap
 * to be filled with the last value written — Begode acknowledges nothing, and a screen that
 * shows what it hopes it set is worse than one that admits it cannot tell.
 */
data class BegodeSettingCapability(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    val rawValue: Int?,
    val sourceOffset: Int,
    val commandId: Int?,
    val minValue: Int,
    val maxValue: Int,
    val step: Int,
    val kind: String,
    val isSupported: Boolean = true,
    /**
     * The named values of a [BegodeSettingsCatalog.Choice] setting, in wire order. Empty for
     * every other kind.
     *
     * The labels live here rather than in either app for the same reason the titles do: two
     * copies drift, and a drifted label is a rider told the wheel is in a mode it is not in.
     */
    val options: List<BegodeSettingOption> = emptyList(),
    /**
     * The value as text, for a read-only row whose value is not a number - the firmware
     * version, a named mode. `null` everywhere else: numbers are formatted by the screens.
     */
    val valueTextEn: String? = null,
    val valueTextRu: String? = null,
)

/** One named value of a choice setting. */
data class BegodeSettingOption(
    val value: Int,
    val titleEn: String,
    val titleRu: String,
)

/**
 * The settings a Begode exposes, and which of them it reports back.
 *
 * ## Why this list is shorter than the ones in the vendor apps
 *
 * The commands were reconstructed by two independent readings of two different sources —
 * the official Begode app and the Begode branch of EUC World — by readers who could not see
 * each other's work. Only what both agree on is here. What one source saw alone, and what
 * the two disagreed about, is listed in `docs/begode-write-commands.md` and is deliberately
 * absent: a settings screen that sends a half-understood byte to a wheel somebody is
 * standing on is worse than one that admits it does not know.
 *
 * Gyroscope calibration is not here and will not be. Its bytes are documented so they can
 * be recognised, never sent.
 *
 * ## Why half of these have no current value
 *
 * The wheel confirms nothing it is told, and reports only five of its settings back: frame
 * `type=4 page=24` carries the tilt-back speed, the auto power-off timer, the unit flag and
 * the ride mode, and `type=1 @2` carries the power margin. Everything else is write-only
 * from the phone's side.
 */
@OptIn(pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi::class)
object BegodeSettingsCatalog {

    const val TiltbackSpeedKey = "begode_tiltback_speed"
    const val PowerMarginKey = "begode_power_margin"
    const val RideModeKey = "begode_ride_mode"
    const val UnitsKey = "begode_units"
    const val SideTiltThresholdKey = "begode_side_tilt_threshold"
    const val SideTiltAngleKey = "begode_side_tilt_angle"
    const val FieldWeakeningKey = "begode_field_weakening"
    const val PedalDipKey = "begode_pedal_dip"
    const val PedalTrimKey = "begode_pedal_trim"
    const val BuzzerVolumeKey = "begode_buzzer_volume"
    const val LedModeKey = "begode_led_mode"
    const val SpeedAlarmsKey = "begode_speed_alarms"
    const val PowerMarginTiltbackKey = "begode_power_margin_tiltback"
    const val AutoPowerOffKey = "begode_auto_power_off"

    /** Read-only: the firmware that answered `V`. Present once it has. */
    const val FirmwareKey = "begode_firmware"

    /** Freestyl3r (`CF`) firmware only. */
    const val CfPwmTiltbackKey = "begode_cf_pwm_tiltback"

    // SmirnoV (`BF`) firmware only, all read-only. See [BegodeSmirnovSettings].
    const val BfPedalModeKey = "begode_bf_pedal_mode"
    const val BfTrickKey = "begode_bf_trick"
    const val BfExtremeModeKey = "begode_bf_extreme_mode"
    const val BfBrakingCurrentKey = "begode_bf_braking_current"
    const val BfRotationControlKey = "begode_bf_rotation_control"
    const val BfRotationAngleKey = "begode_bf_rotation_angle"
    const val BfAdvancedSettingsKey = "begode_bf_advanced_settings"
    const val BfProportionalFactorKey = "begode_bf_pid_p"
    const val BfIntegralFactorKey = "begode_bf_pid_i"
    const val BfDifferentialFactorKey = "begode_bf_pid_d"
    const val BfDynamicCompensationKey = "begode_bf_dynamic_compensation"
    const val BfDynamicCompensationFilterKey = "begode_bf_dynamic_compensation_filter"
    const val BfAccelerationCompensationKey = "begode_bf_acceleration_compensation"
    const val BfCurrentQProportionalKey = "begode_bf_current_q_p"
    const val BfCurrentQIntegralKey = "begode_bf_current_q_i"
    const val BfCurrentDProportionalKey = "begode_bf_current_d_p"
    const val BfCurrentDIntegralKey = "begode_bf_current_d_i"

    /**
     * Tilt-back off, as the slider's far end.
     *
     * The wheel says "off" by reporting a speed nobody rides at, and the slider needs the
     * same extreme position — otherwise switching it off hides behind a separate button.
     */
    const val TiltbackOffValue = 0

    // The same four strings every other brand's catalogue uses. They are not decoration: the
    // iOS screen switches on them literally, so a capitalised "Toggle" here draws a slider
    // there and nobody notices until a rider drags a two-position switch.
    private const val Slider = "slider"
    private const val Toggle = "toggle"
    private const val Unit = "unit"

    /**
     * A choice between named values.
     *
     * Added because "unit" was standing in for it, and "unit" means kilometres or miles
     * literally: both screens draw two buttons, **km** and **mi**. Ride mode and the side
     * tilt threshold are choices of three, so a rider saw km/mi on them — and, worse, could
     * never reach the third value, because two buttons write 0 and 1 and nothing else.
     */
    private const val Choice = "choice"

    /**
     * The byte sequence that applies [value] to the setting [key], or `null` if the key is
     * not ours.
     *
     * Lives next to the catalogue rather than in either app: the mapping from a settings row
     * to the bytes it sends is protocol knowledge, and two copies of it would drift.
     */
    fun writeSteps(key: String, value: Int): List<BegodeWriteStep>? =
        stepsFor(key, value)?.let(BegodeSettingsWriter::withBeep)

    /**
     * The bytes of the setting itself, without the beep that follows it.
     *
     * Split out so the receipt is appended in exactly one place: a sequence that forgets it
     * leaves the rider with a slider that moved and a wheel that said nothing, which on a
     * brand that acknowledges no write at all is indistinguishable from a failure.
     */
    private fun stepsFor(key: String, value: Int): List<BegodeWriteStep>? = when (key) {
        TiltbackSpeedKey -> BegodeSettingsWriter.tiltbackSpeed(
            speedKmh = value.takeIf { it != TiltbackOffValue },
        )
        PowerMarginKey -> BegodeSettingsWriter.powerMarginPercent(value)
        RideModeKey -> BegodeSettingsWriter.rideMode(
            when (value) {
                0 -> BegodeRideMode.Soft
                1 -> BegodeRideMode.Medium
                else -> BegodeRideMode.Hard
            },
        )
        UnitsKey -> BegodeSettingsWriter.useMiles(value != 0)
        SideTiltThresholdKey -> BegodeSettingsWriter.sideTiltThreshold(
            when (value) {
                0 -> BegodeTiltThreshold.Low
                1 -> BegodeTiltThreshold.Medium
                else -> BegodeTiltThreshold.High
            },
        )
        SideTiltAngleKey -> BegodeSettingsWriter.sideTiltAngleDegrees(value)
        FieldWeakeningKey -> BegodeSettingsWriter.fieldWeakening(value)
        PedalDipKey -> BegodeSettingsWriter.pedalDip(value)
        PedalTrimKey -> BegodeSettingsWriter.pedalTrim(value)
        BuzzerVolumeKey -> BegodeSettingsWriter.buzzerVolume(value)
        LedModeKey -> BegodeSettingsWriter.ledMode(value)
        SpeedAlarmsKey -> BegodeSettingsWriter.speedAlarms(
            if (value == 0) BegodeSpeedAlarms.SecondOnly else BegodeSpeedAlarms.Both,
        )
        PowerMarginTiltbackKey -> BegodeSettingsWriter.powerMarginTiltback(value != 0)
        CfPwmTiltbackKey -> BegodeSettingsWriter.pwmTiltback(value != 0)
        else -> null
    }

    /**
     * The stock rows, then the rows a custom firmware adds once `V` has named it.
     *
     * Stock firmware - and a wheel that has not answered `V` - gets exactly the stock list.
     * The custom rows are appended rather than woven in, so nothing about a stock wheel's
     * screen depends on this.
     */
    fun capabilities(telemetry: BegodeTelemetry): List<BegodeSettingCapability> =
        stockCapabilities(telemetry) + firmwareCapabilities(telemetry)

    private fun firmwareCapabilities(telemetry: BegodeTelemetry): List<BegodeSettingCapability> {
        val firmware = telemetry.firmware ?: return emptyList()
        return buildList {
            add(
                readOnly(
                    key = FirmwareKey,
                    titleEn = "Firmware",
                    titleRu = "Прошивка",
                    rawValue = null,
                    sourceOffset = -1,
                    valueTextEn = "${firmware.family.displayName} ${firmware.label}",
                    valueTextRu = "${firmware.family.displayName} ${firmware.label}",
                ),
            )
            when (firmware.family) {
                BegodeFirmwareFamily.Freestyl3r -> add(cfPwmTiltback(telemetry))
                BegodeFirmwareFamily.SmirnoV -> addAll(smirnovCapabilities(telemetry))
                else -> {}
            }
        }
    }

    /**
     * Freestyl3r's PWM tilt-back: the fourth speed-alarm mode, which only this firmware has.
     * Bytes and their validation are on [BegodeSettingsWriter.pwmTiltback]; the value reads
     * back from the alarm-mode bits of `type=4 @6`.
     */
    private fun cfPwmTiltback(telemetry: BegodeTelemetry) = BegodeSettingCapability(
        key = CfPwmTiltbackKey,
        titleEn = "PWM tilt-back (Freestyl3r)",
        titleRu = "Тилтбэк по PWM (Freestyl3r)",
        rawValue = telemetry.speedAlarmMode?.let { if (it == CfPwmTiltbackAlarmMode) 1 else 0 },
        sourceOffset = 6,
        commandId = 'I'.code,
        minValue = 0,
        maxValue = 1,
        step = 1,
        kind = Toggle,
    )

    /**
     * SmirnoV's rows, all read-only. They tune the balance and current controllers, and the
     * firmware's write commands for them have one source - WheelLog - and no second reading, so
     * none of them is built. What the wheel reports is still worth seeing.
     */
    private fun smirnovCapabilities(telemetry: BegodeTelemetry): List<BegodeSettingCapability> = buildList {
        telemetry.smirnovPedalMode?.let { mode ->
            // WheelLog's labels for the wire values: 2, 1, 0, 3 are modes 1, 2, 3, 4.
            val number = when (mode) {
                2 -> 1
                1 -> 2
                0 -> 3
                else -> 4
            }
            add(
                readOnly(
                    key = BfPedalModeKey,
                    titleEn = "Pedal mode",
                    titleRu = "Режим педалей",
                    rawValue = mode,
                    sourceOffset = 6,
                    valueTextEn = "Mode $number",
                    valueTextRu = "Режим $number",
                ),
            )
        }
        telemetry.smirnovTrick?.let { trick ->
            add(
                readOnly(
                    key = BfTrickKey,
                    titleEn = "Trick",
                    titleRu = "Трюк",
                    rawValue = trick,
                    sourceOffset = 16,
                    valueTextEn = when (trick) {
                        0 -> "None"
                        1 -> "Front flip"
                        2 -> "Back flip"
                        else -> null
                    },
                    valueTextRu = when (trick) {
                        0 -> "Нет"
                        1 -> "Сальто вперёд"
                        2 -> "Сальто назад"
                        else -> null
                    },
                ),
            )
        }
        val settings = telemetry.smirnovSettings ?: return@buildList
        fun flag(key: String, en: String, ru: String, value: Boolean, offset: Int) = readOnly(
            key = key,
            titleEn = en,
            titleRu = ru,
            rawValue = if (value) 1 else 0,
            sourceOffset = offset,
            valueTextEn = if (value) "On" else "Off",
            valueTextRu = if (value) "Вкл" else "Выкл",
        )
        fun number(key: String, en: String, ru: String, value: Int, offset: Int) = readOnly(
            key = key,
            titleEn = en,
            titleRu = ru,
            rawValue = value,
            sourceOffset = offset,
        )
        add(flag(BfExtremeModeKey, "Extreme mode", "Экстремальный режим", settings.extremeMode, 2))
        add(number(BfBrakingCurrentKey, "Braking current", "Ток торможения", settings.brakingCurrent, 3))
        add(flag(BfRotationControlKey, "Rotation control", "Контроль вращения", settings.rotationControl, 4))
        add(
            readOnly(
                key = BfRotationAngleKey,
                titleEn = "Rotation angle",
                titleRu = "Угол вращения",
                rawValue = settings.rotationAngleDegrees,
                sourceOffset = 5,
                valueTextEn = "${settings.rotationAngleDegrees}°",
                valueTextRu = "${settings.rotationAngleDegrees}°",
            ),
        )
        add(flag(BfAdvancedSettingsKey, "Advanced settings", "Расширенные настройки", settings.advancedSettings, 6))
        add(number(BfProportionalFactorKey, "Balance PID: P", "ПИД баланса: P", settings.proportionalFactor, 7))
        add(number(BfIntegralFactorKey, "Balance PID: I", "ПИД баланса: I", settings.integralFactor, 8))
        add(number(BfDifferentialFactorKey, "Balance PID: D", "ПИД баланса: D", settings.differentialFactor, 9))
        add(
            number(
                BfDynamicCompensationKey,
                "Dynamic compensation",
                "Динамическая компенсация",
                settings.dynamicCompensation,
                10,
            ),
        )
        add(
            number(
                BfDynamicCompensationFilterKey,
                "Dynamic compensation filter",
                "Фильтр динамической компенсации",
                settings.dynamicCompensationFilter,
                11,
            ),
        )
        add(
            number(
                BfAccelerationCompensationKey,
                "Acceleration compensation",
                "Компенсация ускорения",
                settings.accelerationCompensation,
                12,
            ),
        )
        add(number(BfCurrentQProportionalKey, "Current loop Q: P", "Контур тока Q: P", settings.currentQProportional, 14))
        add(number(BfCurrentQIntegralKey, "Current loop Q: I", "Контур тока Q: I", settings.currentQIntegral, 15))
        add(number(BfCurrentDProportionalKey, "Current loop D: P", "Контур тока D: P", settings.currentDProportional, 16))
        add(number(BfCurrentDIntegralKey, "Current loop D: I", "Контур тока D: I", settings.currentDIntegral, 17))
    }

    private fun readOnly(
        key: String,
        titleEn: String,
        titleRu: String,
        rawValue: Int?,
        sourceOffset: Int,
        valueTextEn: String? = null,
        valueTextRu: String? = null,
    ) = BegodeSettingCapability(
        key = key,
        titleEn = titleEn,
        titleRu = titleRu,
        rawValue = rawValue,
        sourceOffset = sourceOffset,
        commandId = null,
        minValue = 0,
        maxValue = rawValue ?: 0,
        step = 1,
        kind = KIND_READONLY,
        valueTextEn = valueTextEn,
        valueTextRu = valueTextRu,
    )

    /** `type=4 @6` alarm-mode value that means Freestyl3r's PWM tilt-back is on. */
    private const val CfPwmTiltbackAlarmMode = 3

    private fun stockCapabilities(telemetry: BegodeTelemetry): List<BegodeSettingCapability> = listOf(
        BegodeSettingCapability(
            key = TiltbackSpeedKey,
            titleEn = "Speed tilt-back",
            titleRu = "Тилтбэк по скорости",
            // No `?: TiltbackOffValue` here: a wheel that has said nothing is `null` like
            // every other unreported setting. Substituting the off value made the card print
            // "off" before the first `type=4 page=24` frame, and `capturePresetSettings` took
            // that for a reading - so a profile captured in that window switched a rider's
            // tilt-back off when it was applied.
            rawValue = telemetry.tiltbackSpeedKmh.takeIf { it.isFinite() }?.toInt(),
            sourceOffset = 10,
            commandId = 'Y'.code,
            minValue = TiltbackOffValue,
            maxValue = 99,
            step = 1,
            kind = Slider,
        ),
        // Shown as the reserve left, not the load level. The wire carries the level, and the
        // two source apps show the two ends of the same number — which is what made this look
        // like a disagreement between them. "Warn me with 30 % left" needs no explaining;
        // "load 70" does.
        BegodeSettingCapability(
            key = PowerMarginKey,
            titleEn = "Warn when reserve drops to",
            titleRu = "Предупреждать при запасе",
            rawValue = telemetry.powerMarginPercent.takeIf { it.isFinite() }?.toInt(),
            sourceOffset = 2,
            commandId = 'P'.code,
            minValue = 10,
            maxValue = 50,
            step = 5,
            kind = Slider,
        ),
        BegodeSettingCapability(
            key = RideModeKey,
            titleEn = "Ride mode",
            titleRu = "Режим езды",
            rawValue = telemetry.rideMode.takeIf { it.isFinite() }?.toInt(),
            sourceOffset = 6,
            commandId = 's'.code,
            minValue = 0,
            maxValue = 2,
            step = 1,
            kind = Choice,
            options = listOf(
                BegodeSettingOption(0, titleEn = "Soft", titleRu = "Мягкий"),
                BegodeSettingOption(1, titleEn = "Medium", titleRu = "Средний"),
                BegodeSettingOption(2, titleEn = "Hard", titleRu = "Жёсткий"),
            ),
        ),
        BegodeSettingCapability(
            key = UnitsKey,
            titleEn = "Units on the wheel's display",
            titleRu = "Единицы на дисплее колеса",
            rawValue = telemetry.usesMiles?.let { if (it) 1 else 0 },
            sourceOffset = 7,
            commandId = 'g'.code,
            minValue = 0,
            maxValue = 1,
            step = 1,
            // The one setting [Unit] was invented for. It was declared a toggle, so the
            // screens drew ON / OFF on it — and "units: ON" says nothing at all.
            kind = Unit,
        ),
        BegodeSettingCapability(
            key = SideTiltThresholdKey,
            titleEn = "Side tilt threshold",
            titleRu = "Порог бокового наклона",
            rawValue = null,
            sourceOffset = -1,
            commandId = '>'.code,
            minValue = 0,
            maxValue = 2,
            step = 1,
            kind = Choice,
            options = listOf(
                BegodeSettingOption(0, titleEn = "Low", titleRu = "Низкий"),
                BegodeSettingOption(1, titleEn = "Medium", titleRu = "Средний"),
                BegodeSettingOption(2, titleEn = "High", titleRu = "Высокий"),
            ),
        ),
        BegodeSettingCapability(
            key = SideTiltAngleKey,
            titleEn = "Maximum side tilt angle",
            titleRu = "Предельный угол бокового наклона",
            rawValue = null,
            sourceOffset = -1,
            commandId = 'X'.code,
            minValue = BegodeSettingsWriter.MinSideTiltDegrees,
            maxValue = BegodeSettingsWriter.MinSideTiltDegrees +
                9 * BegodeSettingsWriter.SideTiltStepDegrees,
            step = BegodeSettingsWriter.SideTiltStepDegrees,
            kind = Slider,
        ),
        // The floor is one, not zero: this is exactly where the two readings disagreed, one
        // allowing zero and the other refusing it with an explicit check. Until a live wheel
        // settles it, take the intersection. Losing one step is safer than sending a byte
        // that one of the two apps deliberately refuses to send.
        BegodeSettingCapability(
            key = FieldWeakeningKey,
            titleEn = "Field weakening",
            titleRu = "Ослабление поля",
            rawValue = null,
            sourceOffset = -1,
            commandId = 'C'.code,
            minValue = 1,
            maxValue = 9,
            step = 1,
            kind = Slider,
        ),
        BegodeSettingCapability(
            key = PedalDipKey,
            titleEn = "Pedal dip in turns",
            titleRu = "Подгиб педалей в поворотах",
            rawValue = null,
            sourceOffset = -1,
            commandId = 'R'.code,
            minValue = 0,
            maxValue = 9,
            step = 1,
            kind = Slider,
        ),
        // Centred on zero: "-4..+4" reads as a trim, "0..8" reads as nothing in particular.
        BegodeSettingCapability(
            key = PedalTrimKey,
            titleEn = "Pedal horizontal trim",
            titleRu = "Горизонтальная подстройка педалей",
            rawValue = null,
            sourceOffset = -1,
            commandId = 'U'.code,
            minValue = -BegodeSettingsWriter.PedalTrimCentre,
            maxValue = 4,
            step = 1,
            kind = Slider,
        ),
        BegodeSettingCapability(
            key = BuzzerVolumeKey,
            titleEn = "Buzzer volume",
            titleRu = "Громкость пищалки",
            rawValue = null,
            sourceOffset = -1,
            commandId = 'B'.code,
            minValue = 1,
            maxValue = 9,
            step = 1,
            kind = Slider,
        ),
        BegodeSettingCapability(
            key = LedModeKey,
            titleEn = "LED strip mode",
            titleRu = "Режим ленты подсветки",
            rawValue = null,
            sourceOffset = -1,
            commandId = 'M'.code,
            minValue = 0,
            maxValue = 9,
            step = 1,
            kind = Slider,
        ),
        // Named after what the two letters actually do, not after the vendor apps' three-way
        // list. As "Speed alarms" it read backwards on a two-position switch: turning the row
        // *on* would have silenced the first alarm. As "First speed alarm" the switch means
        // what it says, and the second alarm stays armed either way.
        //
        // The third letter the vendor apps have is left out: one reading has it silencing the
        // second alarm, the other all of them. A rider who believes the second alarm is still
        // armed and is wrong gets no warning at all.
        BegodeSettingCapability(
            key = SpeedAlarmsKey,
            titleEn = "First speed alarm",
            titleRu = "Первый скоростной аларм",
            rawValue = null,
            sourceOffset = -1,
            commandId = 'o'.code,
            minValue = 0,
            maxValue = 1,
            step = 1,
            kind = Toggle,
        ),
        BegodeSettingCapability(
            key = PowerMarginTiltbackKey,
            titleEn = "Tilt-back on low power reserve",
            titleRu = "Тилтбэк по запасу мощности",
            rawValue = null,
            sourceOffset = -1,
            commandId = 'e'.code,
            minValue = 0,
            maxValue = 1,
            step = 1,
            kind = Toggle,
        ),
        // Read-only, and honestly so: the wheel reports the timer, and neither reading found
        // a command that changes it. A row without a command is "I know but cannot change
        // it", not a stub waiting to be filled.
        BegodeSettingCapability(
            key = AutoPowerOffKey,
            titleEn = "Auto power-off timer",
            titleRu = "Таймер автоотключения",
            rawValue = telemetry.autoPowerOffSeconds.takeIf { it.isFinite() }?.toInt(),
            sourceOffset = 8,
            commandId = null,
            minValue = 0,
            maxValue = 7_200,
            step = 60,
            kind = KIND_READONLY,
            isSupported = telemetry.autoPowerOffSeconds.isFinite(),
        ),
    )
}
