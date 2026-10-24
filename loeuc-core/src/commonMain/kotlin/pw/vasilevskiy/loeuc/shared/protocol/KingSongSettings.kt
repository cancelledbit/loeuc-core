package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import pw.vasilevskiy.loeuc.shared.api.SettingWriteRefusal

/**
 * The KingSong settings the wheel has reported back, each `null` until it has.
 *
 * Every field is what the wheel said, never what was last sent to it. A setting written a moment
 * ago stays at its old value here until the wheel answers the read that follows the write.
 */
data class KingSongSettingsState(
    /** First speed alarm, km/h, from `0xB5` byte 4. Zero means the alarm is off. */
    val alarm1SpeedKmh: Int? = null,
    /** Second speed alarm, km/h, from `0xB5` byte 6. */
    val alarm2SpeedKmh: Int? = null,
    /** Third speed alarm, km/h, from `0xB5` byte 8. */
    val alarm3SpeedKmh: Int? = null,
    /** Speed tilt-back, km/h, from `0xB5` byte 10. Zero means tilt-back is off. */
    val maxSpeedKmh: Int? = null,
    /** Pedal mode from `0xA9` byte 14, reported only while byte 15 is `0xE0`. See [KingSongPedalMode]. */
    val pedalMode: Int? = null,
    /** Headlight mode from `0xB9` byte 10. See [KingSongLightMode]. */
    val lightMode: Int? = null,
    /**
     * Voice prompts switched off, from `0xB9` byte 11. The light-mode command carries this flag in
     * its byte 3, so it has to be sent back unchanged or a light switch silences the wheel.
     */
    val voicePromptsOff: Boolean? = null,
    /** Decorative LED strip on, from the `0x6E` answer. The wire says 0 for on. */
    val ledStripOn: Boolean? = null,
    /** Headlight strobe on, from the `0x55` answer. */
    val strobeOn: Boolean? = null,
    /** Auto power-off delay while standing, seconds, from the `0x3F` answer. Zero means never. */
    val standbyDelaySeconds: Int? = null,
    /** Charge limit, percent, from the `0x8A` sub-command 8 answer. */
    val chargeLimitPercent: Int? = null,
)

/**
 * Pedal mode as the wire numbers it. The official app calls the three "Expert", "Intermediate"
 * and "Beginner"; WheelLog calls them hard, medium and soft. Both agree on the numbering.
 */
enum class KingSongPedalMode(val wire: Int) {
    Hard(0),
    Medium(1),
    Soft(2),
}

/** Headlight mode. The wire carries `0x12 + ordinal`, and `0xB9` reports it back the same way. */
enum class KingSongLightMode(val wire: Int) {
    On(0x12),
    Off(0x13),
    Auto(0x14),
}

/**
 * Builds the frames that change a KingSong's settings or act on it.
 *
 * ## Where this comes from
 *
 * Every frame here was read twice, from two sources that do not share code: WheelLog's KingSong
 * adapter and the official KingSong app (4.9.82). A frame is built only where both agree on the
 * command byte, the value offsets and the scaling. Each builder records the one place they did
 * not agree byte for byte, and which side this follows.
 *
 * None of it has been checked against a live capture of the official app writing to a wheel.
 *
 * ## What is deliberately absent
 *
 * Calibration (`0x89`) and every gyroscope or balance angle in the `0x8A` family (sub-commands 0
 * to 4) are not here and will not be. The only `0x8A` frame built is the charge limit, sub-command
 * 9. `KingSongSettingsWriterTest` asserts that nothing here ever emits the others.
 */
@ExperimentalWriteApi
object KingSongSettingsWriter {

    /**
     * All three speed alarms and the tilt-back speed, in one frame: the wheel has no way to change
     * one of them alone, so the caller has to supply the current value of the other three.
     *
     * Validation: WheelLog + official app agree on bytes (2, 4, 6, 8; command `0x85`). The
     * official app also puts a six-character speed-limit password into bytes 10..15 when the
     * rider's account has one; WheelLog never does, and neither does this.
     */
    fun speedAlarms(alarm1Kmh: Int, alarm2Kmh: Int, alarm3Kmh: Int, maxSpeedKmh: Int): ByteArray =
        kingSongSettingsFrame(COMMAND_SPEED_ALARMS) {
            this[2] = alarm1Kmh.coerceIn(0, MAX_SPEED_CEILING_KMH).toByte()
            this[4] = alarm2Kmh.coerceIn(0, MAX_SPEED_CEILING_KMH).toByte()
            this[6] = alarm3Kmh.coerceIn(0, MAX_SPEED_CEILING_KMH).toByte()
            this[8] = maxSpeedKmh.coerceIn(0, MAX_SPEED_CEILING_KMH).toByte()
        }

    /**
     * Pedal mode.
     *
     * Validation: WheelLog + official app agree on bytes 2, 3 and 16 (`mode`, `0xE0`, `0x87`).
     * They differ on byte 17: the official app sends the usual `0x14`, WheelLog `0x15`. This
     * follows the official app.
     */
    fun pedalMode(mode: KingSongPedalMode): ByteArray = kingSongSettingsFrame(COMMAND_PEDAL_MODE) {
        this[2] = mode.wire.toByte()
        this[3] = 0xE0.toByte()
    }

    /**
     * Headlight mode.
     *
     * Validation: WheelLog + official app agree on byte 2 (`0x12` on, `0x13` off, `0x14` auto).
     * They differ on byte 3: WheelLog always sends 1, the official app sends the wheel's current
     * voice-prompt switch (1 = prompts off), which it reads from `0xB9` byte 11. This follows the
     * official app, so a caller that does not know the voice switch should pass `true`, which
     * reproduces WheelLog's frame exactly.
     */
    fun lightMode(mode: KingSongLightMode, voicePromptsOff: Boolean): ByteArray = kingSongSettingsFrame(COMMAND_LIGHT_MODE) {
        this[2] = mode.wire.toByte()
        this[3] = if (voicePromptsOff) 1 else 0
    }

    /** Decorative LED strip. The wire says 0 for on. Validation: WheelLog + official app agree on bytes. */
    fun ledStrip(on: Boolean): ByteArray = kingSongSettingsFrame(COMMAND_LED_STRIP) {
        this[2] = if (on) 0 else 1
    }

    /** Headlight strobe, 1 for on. Validation: WheelLog + official app agree on bytes. */
    fun strobe(on: Boolean): ByteArray = kingSongSettingsFrame(COMMAND_STROBE) {
        this[2] = if (on) 1 else 0
    }

    /**
     * Auto power-off delay while standing, in seconds; zero switches it off. The official app
     * offers whole minutes up to four hours.
     *
     * Validation: WheelLog + official app agree on bytes (sub-command 1 in byte 2, `u16le`
     * seconds in bytes 4..5, command `0x3F`).
     */
    fun standbyDelay(seconds: Int): ByteArray = kingSongSettingsFrame(COMMAND_STANDBY) {
        val clamped = seconds.coerceIn(0, MAX_STANDBY_SECONDS)
        this[2] = 1
        this[4] = (clamped and 0xFF).toByte()
        this[5] = ((clamped shr 8) and 0xFF).toByte()
    }

    /**
     * Stop charging at this percentage.
     *
     * Validation: WheelLog + official app agree on bytes (sub-command 9 in byte 2, the percentage
     * in byte 4, command `0x8A`). The official app writes it as `u16le` over bytes 4..5, which is
     * the same frame for every value it allows; its slider runs from 50 to 100.
     */
    fun chargeLimit(percent: Int): ByteArray = kingSongSettingsFrame(COMMAND_SUB_SETTINGS) {
        val clamped = percent.coerceIn(MIN_CHARGE_LIMIT_PERCENT, 100)
        this[2] = SUB_CHARGE_LIMIT_WRITE.toByte()
        this[4] = (clamped and 0xFF).toByte()
        this[5] = ((clamped shr 8) and 0xFF).toByte()
    }

    /** Horn. Validation: WheelLog + official app agree on bytes (command `0x88`, empty payload). */
    fun horn(): ByteArray = kingSongSettingsFrame(COMMAND_HORN) {}

    /**
     * Power off.
     *
     * Validation: WheelLog + official app agree on bytes (command `0x40`, empty payload). The
     * official app refuses to send it above 0.5 km/h; the caller has to hold the same line.
     */
    fun powerOff(): ByteArray = kingSongSettingsFrame(COMMAND_POWER_OFF) {}

    const val MAX_SPEED_CEILING_KMH = 100
    const val MAX_STANDBY_SECONDS = 240 * 60
    const val MIN_CHARGE_LIMIT_PERCENT = 50
}

/**
 * Frames that only ask a KingSong to report a setting. Nothing here changes the wheel.
 *
 * The alarm read comes from both WheelLog and the official app. The other four come from the
 * official app alone, which sends them when its own settings screens open; WheelLog never reads
 * those settings back.
 */
object KingSongSettingsReads {
    /** Answered by `0xB5` with the three alarms and the tilt-back speed. */
    fun speedAlarms(): ByteArray = kingSongSettingsFrame(COMMAND_SPEED_ALARMS_READ) {}

    /** Answered by `0x6E`. */
    fun ledStrip(): ByteArray = kingSongSettingsFrame(COMMAND_LED_STRIP_READ) {}

    /** Answered by `0x55`. */
    fun strobe(): ByteArray = kingSongSettingsFrame(COMMAND_STROBE_READ) {}

    /** Answered by `0x3F` with sub-command 0 and the delay in bytes 4..5. */
    fun standbyDelay(): ByteArray = kingSongSettingsFrame(COMMAND_STANDBY) {}

    /** Answered by `0x8A` with sub-command 8 and the percentage in bytes 4..5. */
    fun chargeLimit(): ByteArray = kingSongSettingsFrame(COMMAND_SUB_SETTINGS) {
        this[2] = SUB_CHARGE_LIMIT_READ.toByte()
    }

    /** Every settings read, in the order a connection sends them. */
    fun all(): List<ByteArray> = listOf(speedAlarms(), ledStrip(), strobe(), standbyDelay(), chargeLimit())
}

/**
 * Tracks the settings a KingSong reports as its frames stream past.
 *
 * Fed every frame; anything that is not a settings answer is ignored.
 */
internal class KingSongSettingsTracker {
    var state = KingSongSettingsState()
        private set

    fun clear() {
        state = KingSongSettingsState()
    }

    fun accept(frame: ByteArray) {
        if (frame.size < 20 || frame[0] != 0xAA.toByte() || frame[1] != 0x55.toByte()) return
        val u8 = { offset: Int -> frame[offset].toInt() and 0xFF }
        val u16 = { offset: Int -> u8(offset) or (u8(offset + 1) shl 8) }
        state = when (u8(16)) {
            COMMAND_SPEED_ALARMS_ANSWER -> state.copy(
                alarm1SpeedKmh = u8(4),
                alarm2SpeedKmh = u8(6),
                alarm3SpeedKmh = u8(8),
                maxSpeedKmh = u8(10),
            )
            COMMAND_LIVE -> if (u8(15) == 0xE0) state.copy(pedalMode = u8(14)) else state
            COMMAND_RIDE_STATS -> state.copy(
                lightMode = KingSongLightMode.entries.firstOrNull { it.wire == u8(10) }?.ordinal
                    ?: state.lightMode,
                voicePromptsOff = u8(11) != 0,
            )
            COMMAND_LED_STRIP_ANSWER -> state.copy(ledStripOn = u8(2) == 0)
            COMMAND_STROBE_ANSWER -> state.copy(strobeOn = u8(2) != 0)
            COMMAND_STANDBY -> if (u8(2) == 0) state.copy(standbyDelaySeconds = u16(4)) else state
            COMMAND_SUB_SETTINGS ->
                if (u8(2) == SUB_CHARGE_LIMIT_READ) state.copy(chargeLimitPercent = u16(4)) else state
            else -> state
        }
    }
}

/** One KingSong setting as both platforms see it. Same shape as [BegodeSettingCapability]. */
data class KingSongSettingCapability(
    val key: String,
    val titleEn: String,
    val titleRu: String,
    /** The wheel's value in the unit of [minValue]..[maxValue], or `null` until it reports one. */
    val rawValue: Int?,
    /** Byte offset of the value inside its answer frame, for diagnostics. */
    val sourceOffset: Int,
    val commandId: Int?,
    val minValue: Int,
    val maxValue: Int,
    val step: Int,
    val kind: String,
    val isSupported: Boolean = true,
    val options: List<KingSongSettingOption> = emptyList(),
)

data class KingSongSettingOption(
    val value: Int,
    val titleEn: String,
    val titleRu: String,
)

/** What a settings write resolved to. */
sealed interface KingSongSettingWrite {
    class Command(val bytes: ByteArray) : KingSongSettingWrite
    /**
     * Nothing was built. [refusal] carries the wording in both languages; [reason] is the
     * English one, for a log line or a test.
     */
    data class Rejected(val refusal: SettingWriteRefusal) : KingSongSettingWrite {
        val reason: String get() = refusal.english
    }
}

/**
 * The settings a KingSong exposes, the frame each one writes and the read that confirms it.
 *
 * Every row carries the wheel's own value or none. A row whose value is unknown is still
 * writable, except the speed alarms, whose single frame would overwrite the three values the
 * wheel has not reported yet.
 */
@OptIn(ExperimentalWriteApi::class)
object KingSongSettingsCatalog {
    const val Alarm1SpeedKey = "kingsong_alarm_1_speed"
    const val Alarm2SpeedKey = "kingsong_alarm_2_speed"
    const val Alarm3SpeedKey = "kingsong_alarm_3_speed"
    const val MaxSpeedKey = "kingsong_max_speed"
    const val PedalModeKey = "kingsong_pedal_mode"
    const val LightModeKey = "kingsong_light_mode"
    const val LedStripKey = "kingsong_led_strip"
    const val StrobeKey = "kingsong_strobe"
    const val StandbyDelayKey = "kingsong_standby_delay"
    const val ChargeLimitKey = "kingsong_charge_limit"

    /**
     * Read-only: the wheel's voice-prompt switch, 1 while prompts are on. No command for it was
     * cross-checked, but the light-mode write has to send it back, so it is shown and carried.
     */
    const val VoicePromptsKey = "kingsong_voice_prompts"

    val keys: List<String> = listOf(
        Alarm1SpeedKey, Alarm2SpeedKey, Alarm3SpeedKey, MaxSpeedKey, PedalModeKey, LightModeKey,
        LedStripKey, StrobeKey, StandbyDelayKey, ChargeLimitKey, VoicePromptsKey,
    )

    // The same kind strings every other brand's catalogue uses; the iOS screen switches on them.
    private const val Slider = "slider"
    private const val Toggle = "toggle"
    private const val Choice = "choice"

    /** The reads that bring [key] back from the wheel after a write, or every read for `null`. */
    fun readsFor(key: String?): List<ByteArray> = when (key) {
        null -> KingSongSettingsReads.all()
        Alarm1SpeedKey, Alarm2SpeedKey, Alarm3SpeedKey, MaxSpeedKey -> listOf(KingSongSettingsReads.speedAlarms())
        LedStripKey -> listOf(KingSongSettingsReads.ledStrip())
        StrobeKey -> listOf(KingSongSettingsReads.strobe())
        StandbyDelayKey -> listOf(KingSongSettingsReads.standbyDelay())
        ChargeLimitKey -> listOf(KingSongSettingsReads.chargeLimit())
        // Pedal and light mode ride along in the live frames and need no read.
        else -> emptyList()
    }

    /**
     * The frame that applies [value] to [key] on a wheel currently reporting [state].
     *
     * Values arrive in the units the capabilities advertise: km/h, the pedal and light mode
     * numbers, 1 for on, minutes for the standby delay and percent for the charge limit.
     */
    @ExperimentalWriteApi
    fun write(key: String, value: Int, state: KingSongSettingsState): KingSongSettingWrite = when (key) {
        Alarm1SpeedKey, Alarm2SpeedKey, Alarm3SpeedKey, MaxSpeedKey -> speedAlarmsWrite(key, value, state)
        PedalModeKey -> KingSongPedalMode.entries.firstOrNull { it.wire == value }
            ?.let { KingSongSettingWrite.Command(KingSongSettingsWriter.pedalMode(it)) }
            ?: KingSongSettingWrite.Rejected(
                SettingWriteRefusal(
                    english = "Unknown pedal mode $value.",
                    russian = "Неизвестный режим педалей $value.",
                ),
            )
        LightModeKey -> KingSongLightMode.entries.getOrNull(value)
            ?.let {
                // Unknown voice switch: send what WheelLog sends, which is also what the wheels
                // seen so far report.
                KingSongSettingWrite.Command(
                    KingSongSettingsWriter.lightMode(it, voicePromptsOff = state.voicePromptsOff ?: true),
                )
            }
            ?: KingSongSettingWrite.Rejected(
                SettingWriteRefusal(
                    english = "Unknown light mode $value.",
                    russian = "Неизвестный режим света $value.",
                ),
            )
        LedStripKey -> KingSongSettingWrite.Command(KingSongSettingsWriter.ledStrip(on = value != 0))
        StrobeKey -> KingSongSettingWrite.Command(KingSongSettingsWriter.strobe(on = value != 0))
        StandbyDelayKey ->
            if (value in 0..MAX_STANDBY_MINUTES) {
                KingSongSettingWrite.Command(KingSongSettingsWriter.standbyDelay(seconds = value * 60))
            } else {
                KingSongSettingWrite.Rejected(
                    SettingWriteRefusal(
                        english = "Standby delay must be 0..$MAX_STANDBY_MINUTES minutes.",
                        russian = "Автоотключение — от 0 до $MAX_STANDBY_MINUTES минут.",
                    ),
                )
            }
        ChargeLimitKey ->
            if (value in KingSongSettingsWriter.MIN_CHARGE_LIMIT_PERCENT..100) {
                KingSongSettingWrite.Command(KingSongSettingsWriter.chargeLimit(value))
            } else {
                KingSongSettingWrite.Rejected(
                    SettingWriteRefusal(
                        english = "Charge limit must be 50..100 %.",
                        russian = "Предел зарядки — от 50 до 100 %.",
                    ),
                )
            }
        else -> KingSongSettingWrite.Rejected(SettingWriteRefusal.unknownSetting(key))
    }

    /**
     * The alarms and the tilt-back speed share one frame, so changing one means restating the
     * other three. They are taken from the wheel's last `0xB5` answer; with no answer yet there is
     * nothing safe to restate, and zeroes would switch every alarm and tilt-back off.
     *
     * The ordering check is the official app's: the alarms may not decrease from first to third,
     * and none may exceed an active tilt-back speed.
     */
    private fun speedAlarmsWrite(key: String, value: Int, state: KingSongSettingsState): KingSongSettingWrite {
        val alarm1 = state.alarm1SpeedKmh
        val alarm2 = state.alarm2SpeedKmh
        val alarm3 = state.alarm3SpeedKmh
        val max = state.maxSpeedKmh
        if (alarm1 == null || alarm2 == null || alarm3 == null || max == null) {
            return KingSongSettingWrite.Rejected(
                SettingWriteRefusal(
                    english = "The wheel has not reported its current speed alarms yet.",
                    russian = "Колесо ещё не прислало текущие скоростные алармы. Подождите пару секунд и повторите.",
                ),
            )
        }
        if (value !in 0..KingSongSettingsWriter.MAX_SPEED_CEILING_KMH) {
            return KingSongSettingWrite.Rejected(
                SettingWriteRefusal(
                    english = "Speed must be 0..${KingSongSettingsWriter.MAX_SPEED_CEILING_KMH} km/h.",
                    russian = "Скорость: от 0 до ${KingSongSettingsWriter.MAX_SPEED_CEILING_KMH} км/ч.",
                ),
            )
        }
        val a1 = if (key == Alarm1SpeedKey) value else alarm1
        val a2 = if (key == Alarm2SpeedKey) value else alarm2
        val a3 = if (key == Alarm3SpeedKey) value else alarm3
        val m = if (key == MaxSpeedKey) value else max
        if (a1 > a2 || a2 > a3) {
            return KingSongSettingWrite.Rejected(
                SettingWriteRefusal(
                    english = "Speed alarms must not decrease from the first to the third.",
                    russian = "Алармы должны идти по возрастанию: первый ≤ второй ≤ третий.",
                ),
            )
        }
        if (m != 0 && a3 > m) {
            return KingSongSettingWrite.Rejected(
                SettingWriteRefusal(
                    english = "Speed alarms must not exceed the tilt-back speed.",
                    russian = "Алармы не могут быть выше скорости тилтбэка.",
                ),
            )
        }
        return KingSongSettingWrite.Command(KingSongSettingsWriter.speedAlarms(a1, a2, a3, m))
    }

    fun capabilities(state: KingSongSettingsState): List<KingSongSettingCapability> = buildList {
        add(speed(Alarm1SpeedKey, "First speed alarm", "Первый скоростной аларм", state.alarm1SpeedKmh, 4))
        add(speed(Alarm2SpeedKey, "Second speed alarm", "Второй скоростной аларм", state.alarm2SpeedKmh, 6))
        add(speed(Alarm3SpeedKey, "Third speed alarm", "Третий скоростной аларм", state.alarm3SpeedKmh, 8))
        add(speed(MaxSpeedKey, "Speed tilt-back", "Тилтбэк по скорости", state.maxSpeedKmh, 10))
        add(
            KingSongSettingCapability(
                key = PedalModeKey,
                titleEn = "Pedal mode",
                titleRu = "Режим педалей",
                rawValue = state.pedalMode,
                sourceOffset = 14,
                commandId = COMMAND_PEDAL_MODE,
                minValue = 0,
                maxValue = 2,
                step = 1,
                kind = Choice,
                options = listOf(
                    KingSongSettingOption(KingSongPedalMode.Hard.wire, "Hard", "Жёсткий"),
                    KingSongSettingOption(KingSongPedalMode.Medium.wire, "Medium", "Средний"),
                    KingSongSettingOption(KingSongPedalMode.Soft.wire, "Soft", "Мягкий"),
                ),
            ),
        )
        add(
            KingSongSettingCapability(
                key = LightModeKey,
                titleEn = "Headlight",
                titleRu = "Фара",
                rawValue = state.lightMode,
                sourceOffset = 10,
                commandId = COMMAND_LIGHT_MODE,
                minValue = 0,
                maxValue = 2,
                step = 1,
                kind = Choice,
                options = listOf(
                    KingSongSettingOption(KingSongLightMode.On.ordinal, "On", "Включена"),
                    KingSongSettingOption(KingSongLightMode.Off.ordinal, "Off", "Выключена"),
                    KingSongSettingOption(KingSongLightMode.Auto.ordinal, "Auto", "Авто"),
                ),
            ),
        )
        add(
            KingSongSettingCapability(
                key = VoicePromptsKey,
                titleEn = "Voice prompts",
                titleRu = "Голосовые подсказки",
                rawValue = state.voicePromptsOff?.let { off -> if (off) 0 else 1 },
                sourceOffset = 11,
                commandId = null,
                minValue = 0,
                maxValue = 1,
                step = 1,
                kind = KIND_READONLY,
                isSupported = state.voicePromptsOff != null,
            ),
        )
        add(toggle(LedStripKey, "LED strip", "Светодиодная лента", state.ledStripOn, COMMAND_LED_STRIP))
        add(toggle(StrobeKey, "Headlight strobe", "Стробоскоп фары", state.strobeOn, COMMAND_STROBE))
        add(
            KingSongSettingCapability(
                key = StandbyDelayKey,
                titleEn = "Auto power-off, minutes",
                titleRu = "Автоотключение, минут",
                rawValue = state.standbyDelaySeconds?.let { it / 60 },
                sourceOffset = 4,
                commandId = COMMAND_STANDBY,
                minValue = 0,
                maxValue = MAX_STANDBY_MINUTES,
                step = 1,
                kind = Slider,
            ),
        )
        // Offered only once the wheel has answered the read: the official app shows it on the
        // battery screen of the models that have it, and a wheel that does not answer is not
        // one to send a charging command to.
        state.chargeLimitPercent?.let { percent ->
            add(
                KingSongSettingCapability(
                    key = ChargeLimitKey,
                    titleEn = "Charge limit",
                    titleRu = "Предел зарядки",
                    rawValue = percent,
                    sourceOffset = 4,
                    commandId = COMMAND_SUB_SETTINGS,
                    minValue = KingSongSettingsWriter.MIN_CHARGE_LIMIT_PERCENT,
                    maxValue = 100,
                    step = 1,
                    kind = Slider,
                ),
            )
        }
    }

    private fun speed(key: String, titleEn: String, titleRu: String, value: Int?, offset: Int) =
        KingSongSettingCapability(
            key = key,
            titleEn = titleEn,
            titleRu = titleRu,
            rawValue = value,
            sourceOffset = offset,
            commandId = COMMAND_SPEED_ALARMS,
            minValue = 0,
            maxValue = KingSongSettingsWriter.MAX_SPEED_CEILING_KMH,
            step = 1,
            kind = Slider,
        )

    private fun toggle(key: String, titleEn: String, titleRu: String, on: Boolean?, command: Int) =
        KingSongSettingCapability(
            key = key,
            titleEn = titleEn,
            titleRu = titleRu,
            rawValue = on?.let { if (it) 1 else 0 },
            sourceOffset = 2,
            commandId = command,
            minValue = 0,
            maxValue = 1,
            step = 1,
            kind = Toggle,
        )

    const val MAX_STANDBY_MINUTES = KingSongSettingsWriter.MAX_STANDBY_SECONDS / 60
}

internal inline fun kingSongSettingsFrame(command: Int, fill: ByteArray.() -> Unit): ByteArray =
    ByteArray(20).apply {
        this[0] = 0xAA.toByte()
        this[1] = 0x55
        fill()
        this[16] = command.toByte()
        this[17] = 0x14
        this[18] = 0x5A
        this[19] = 0x5A
    }

private const val COMMAND_SPEED_ALARMS = 0x85
private const val COMMAND_SPEED_ALARMS_READ = 0x98
private const val COMMAND_SPEED_ALARMS_ANSWER = 0xB5
private const val COMMAND_PEDAL_MODE = 0x87
private const val COMMAND_LIGHT_MODE = 0x73
private const val COMMAND_LED_STRIP = 0x6C
private const val COMMAND_LED_STRIP_READ = 0x6D
private const val COMMAND_LED_STRIP_ANSWER = 0x6E
private const val COMMAND_STROBE = 0x53
private const val COMMAND_STROBE_READ = 0x54
private const val COMMAND_STROBE_ANSWER = 0x55
private const val COMMAND_STANDBY = 0x3F
private const val COMMAND_SUB_SETTINGS = 0x8A
private const val SUB_CHARGE_LIMIT_READ = 8
private const val SUB_CHARGE_LIMIT_WRITE = 9
private const val COMMAND_HORN = 0x88
private const val COMMAND_POWER_OFF = 0x40
private const val COMMAND_LIVE = 0xA9
private const val COMMAND_RIDE_STATS = 0xB9
