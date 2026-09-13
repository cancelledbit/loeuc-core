package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/*
 * Setting writes and quick actions for legacy InMotion "ezcan" wheels (V5, V8, V10 families,
 * Solowheel Glide 3, older R-series).
 *
 * Every command is a short message on channel 5, format 0, type 0, len 8, framed and escaped
 * like any other request. Bytes, model gates and ranges follow WheelLog's InMotion adapter, the
 * reference client; each builder names what cross-checks it. None of these commands has been
 * sent to a wheel by this library's authors - there is no capture of any of them.
 *
 * Deliberately absent, whatever other clients do:
 * - calibration (id 0x0F550119), in any form: the official app's "calibration status" read sends
 *   the calibration trigger itself;
 * - lock / unlock, transport mode and berm angle: known from the official app only, and the first
 *   two share the remote-control sub-id 0xB2 with power-off, so one misread byte is a different
 *   command;
 * - PIN change (id 0x0F550306);
 * - power-off (remote control 0xB2 / 0x05): WheelLog only, the official app has no builder for it;
 * - WheelLog's beep (0xB2 / 0x11): the official app contradicts it and plays a sound instead.
 * [InmotionLegacyCommands.isForbidden] recognises all of these so a session refuses them even as
 * raw bytes.
 */

/** A setting the wheel reports in slow info and that can be written. */
enum class InmotionLegacySetting {
    HEADLIGHT,
    DECORATIVE_LIGHT,
    SPEAKER_VOLUME,
    RIDE_MODE,
    MAX_SPEED,
    PEDAL_TILT,
    HANDLE_BUTTON,
    PEDAL_SENSITIVITY,
}

object InmotionLegacyCommands {
    /** Headlight on/off. */
    const val HEADLIGHT_ID: Int = 0x0F55010D

    /** Remote control: data[0] = 0xB2, data[4] = sub-command. */
    const val REMOTE_CONTROL_ID: Int = 0x0F550116

    /** Ride settings: data[0] = field, data[4..7] = value. */
    const val RIDE_SETTINGS_ID: Int = 0x0F550115

    /** Handle button (the official app: lift-up detection). */
    const val HANDLE_BUTTON_ID: Int = 0x0F55012E

    const val SPEAKER_VOLUME_ID: Int = 0x0F55060A

    const val PLAY_SOUND_ID: Int = 0x0F550609

    /** Never built. See the file comment. */
    const val CALIBRATION_ID: Int = 0x0F550119

    /** Never built: changes the session PIN. */
    const val PIN_CHANGE_ID: Int = 0x0F550306

    /** Never built: berm angle mode. */
    const val BERM_ANGLE_ID: Int = 0x0F550132

    private const val REMOTE_CONTROL_MARKER = 0xB2
    private const val REMOTE_LED_ON = 0x0F
    private const val REMOTE_LED_OFF = 0x10

    private const val RIDE_FIELD_PEDAL_TILT = 0x00
    private const val RIDE_FIELD_MAX_SPEED = 0x01
    private const val RIDE_FIELD_PEDAL_SENSITIVITY = 0x06
    private const val RIDE_FIELD_RIDE_MODE = 0x0A

    /** The official app's horn: sound number 24 on the play-sound message. */
    const val HORN_SOUND: Int = 24

    private val LED_SUBCOMMANDS = setOf(REMOTE_LED_ON, REMOTE_LED_OFF)

    /** Remote-control sub-commands on 0xB2 that no builder here produces. */
    private val FORBIDDEN_REMOTE_SUBCOMMANDS = setOf(
        0x03, // lock (official app only)
        0x04, // unlock (official app only)
        0x05, // power off (WheelLog only)
        0x11, // beep (WheelLog only, contradicted by the official app)
        0x12, // transport mode on (official app only)
        0x13, // transport mode off (official app only)
    )

    /** Slowest top-speed limit offered, as in the reference client's slider. */
    const val MIN_MAX_SPEED_KMH: Int = 3

    /** Pedal tilt range in tenths of a degree, as in the reference client's slider. */
    val PEDAL_TILT_TENTHS_RANGE: IntRange = -80..80

    val SPEAKER_VOLUME_RANGE: IntRange = 0..100

    /** WheelLog's slider; see [pedalSensitivity] for why the scaling is WheelLog's alone. */
    val PEDAL_SENSITIVITY_RANGE: IntRange = 4..100

    /**
     * Models with the decorative LED switch, as WheelLog shows it: Glide 3, V8, V8F, V8S and the
     * V10 family.
     */
    fun hasDecorativeLight(model: InmotionLegacyModel): Boolean =
        model == InmotionLegacyModel.GLIDE_3 || model == InmotionLegacyModel.V8 ||
            model == InmotionLegacyModel.V8F || model == InmotionLegacyModel.V8S || model.isV10Family

    /**
     * Models with ride mode and pedal sensitivity, as WheelLog shows them: V8F, V8S and the V10
     * family. The official app reads both fields for V8 and V10 settings classes too, but V8 has
     * no capture, so WheelLog's narrower gate stands.
     */
    fun hasRideModes(model: InmotionLegacyModel): Boolean =
        model == InmotionLegacyModel.V8F || model == InmotionLegacyModel.V8S || model.isV10Family

    /**
     * The speed-limit range WheelLog's slider offers for [model]: 3 km/h to the model's top, capped
     * at 65 km/h because slow info reports the limit in 16 bits of metres per hour. WheelLog offers
     * 70 to models it has no top speed for, which its own readback could not show.
     */
    fun maxSpeedRange(model: InmotionLegacyModel): IntRange =
        MIN_MAX_SPEED_KMH..minOf(model.maxSpeedKmh, MAX_WIRE_SPEED_KMH)

    /**
     * Allowed values for [setting] on [model], or null when the model does not have it. Toggles
     * are 0..1: 1 = on, classic ride mode, handle button enabled.
     */
    fun range(setting: InmotionLegacySetting, model: InmotionLegacyModel): IntRange? = when (setting) {
        InmotionLegacySetting.HEADLIGHT, InmotionLegacySetting.HANDLE_BUTTON -> 0..1
        InmotionLegacySetting.DECORATIVE_LIGHT -> if (hasDecorativeLight(model)) 0..1 else null
        InmotionLegacySetting.RIDE_MODE -> if (hasRideModes(model)) 0..1 else null
        InmotionLegacySetting.PEDAL_SENSITIVITY -> if (hasRideModes(model)) PEDAL_SENSITIVITY_RANGE else null
        InmotionLegacySetting.SPEAKER_VOLUME -> SPEAKER_VOLUME_RANGE
        InmotionLegacySetting.MAX_SPEED -> maxSpeedRange(model)
        InmotionLegacySetting.PEDAL_TILT -> PEDAL_TILT_TENTHS_RANGE
    }

    /**
     * The value [settings] reports for [setting], in the unit its builder takes, or null when the
     * slow info payload did not carry it.
     */
    fun currentValue(setting: InmotionLegacySetting, settings: InmotionLegacySettings): Int? = when (setting) {
        InmotionLegacySetting.HEADLIGHT -> settings.headlightOn?.toFlag()
        InmotionLegacySetting.DECORATIVE_LIGHT -> settings.decorativeLightOn?.toFlag()
        InmotionLegacySetting.SPEAKER_VOLUME -> settings.speakerVolume
        InmotionLegacySetting.RIDE_MODE -> settings.rideModeClassic?.toFlag()
        InmotionLegacySetting.MAX_SPEED -> settings.maxSpeedKmh
        InmotionLegacySetting.PEDAL_TILT -> settings.pedalTiltDegrees?.let { degreesToTenths(it) }
        InmotionLegacySetting.HANDLE_BUTTON -> settings.handleButtonDisabled?.let { (!it).toFlag() }
        InmotionLegacySetting.PEDAL_SENSITIVITY -> settings.pedalHardness
    }

    /**
     * The settings that can be written to this wheel: the model is known, has the setting, and
     * its slow info carries the field so the wheel's own value can be read back after a write.
     * A V5F's 124-byte slow info, for example, leaves only headlight, max speed and pedal tilt.
     */
    fun writableSettings(identity: InmotionLegacyIdentity?, settings: InmotionLegacySettings?): List<InmotionLegacySetting> {
        val model = identity?.model ?: return emptyList()
        if (settings == null) return emptyList()
        return InmotionLegacySetting.entries.filter { setting ->
            range(setting, model) != null && currentValue(setting, settings) != null
        }
    }

    /**
     * The command that sets [setting] to [value] on [model], or null when the model does not
     * have the setting or [value] is outside [range]. Readback-agnostic: [writableSettings] is
     * what checks that the wheel reports the field.
     */
    @ExperimentalWriteApi
    fun build(setting: InmotionLegacySetting, value: Int, model: InmotionLegacyModel): ByteArray? {
        val allowed = range(setting, model) ?: return null
        if (value !in allowed) return null
        return when (setting) {
            InmotionLegacySetting.HEADLIGHT -> headlight(value == 1)
            InmotionLegacySetting.DECORATIVE_LIGHT -> decorativeLight(value == 1)
            InmotionLegacySetting.SPEAKER_VOLUME -> speakerVolume(value)
            InmotionLegacySetting.RIDE_MODE -> rideMode(classic = value == 1)
            InmotionLegacySetting.MAX_SPEED -> maxSpeed(value)
            InmotionLegacySetting.PEDAL_TILT -> pedalTilt(value)
            InmotionLegacySetting.HANDLE_BUTTON -> handleButton(enabled = value == 1)
            InmotionLegacySetting.PEDAL_SENSITIVITY -> pedalSensitivity(value)
        }
    }

    /**
     * Headlight: id 0x0F55010D, data[0] = 1 on / 0 off. Read back from slow info byte 80.
     * Validation: WheelLog + InMotion Pro decompile agree on bytes.
     * Wire (on): `AA AA 0D 01 A5 55 0F 01 00 00 00 00 00 00 00 08 05 00 00 80 55 55`.
     */
    @ExperimentalWriteApi
    fun headlight(on: Boolean): ByteArray = command(HEADLIGHT_ID, u32x2(on.toFlag(), 0))

    /**
     * Decorative LED: remote control 0xB2, sub-command 0x0F on / 0x10 off. Read back from slow
     * info byte 130. The official app names it "light effect mode".
     * Validation: WheelLog + InMotion Pro decompile agree on bytes.
     */
    @ExperimentalWriteApi
    fun decorativeLight(on: Boolean): ByteArray =
        command(REMOTE_CONTROL_ID, u32x2(REMOTE_CONTROL_MARKER, if (on) REMOTE_LED_ON else REMOTE_LED_OFF))

    /**
     * Speaker volume 0..100: id 0x0F55060A, data[0..3] = LE32(volume x 100). Read back from slow
     * info u16 at 125 / 100 - which a V5F payload does not reach (the official app reads the V5
     * volume with a separate request instead).
     * Validation: WheelLog + InMotion Pro decompile agree on bytes.
     */
    @ExperimentalWriteApi
    @Throws(IllegalArgumentException::class)
    fun speakerVolume(volume: Int): ByteArray {
        require(volume in SPEAKER_VOLUME_RANGE) { "volume must be 0..100" }
        return command(SPEAKER_VOLUME_ID, u32x2(volume * 100, 0))
    }

    /**
     * Ride mode: ride settings field 0x0A, value 1 classic / 0 comfort. Read back from slow info
     * byte 132.
     * Validation: WheelLog + InMotion Pro decompile agree on bytes.
     */
    @ExperimentalWriteApi
    fun rideMode(classic: Boolean): ByteArray = command(RIDE_SETTINGS_ID, u32x2(RIDE_FIELD_RIDE_MODE, classic.toFlag()))

    /**
     * Top-speed (tilt-back) limit in whole km/h: ride settings field 0x01, value LE32(km/h x 1000).
     * Read back from slow info u16 at 60 / 1000.
     *
     * WheelLog casts km/h x 1000 to a signed short, which wraps above 32 km/h; its own 35 km/h test
     * vector (`B8 88`) is only right as unsigned, and the official app writes the full 32-bit
     * field. The value is written unsigned here, which gives WheelLog's bytes for every speed and
     * never a negative limit.
     * Validation: WheelLog + InMotion Pro decompile agree on the field and its width; the
     * decompile's own argument scaling was not traced.
     */
    @ExperimentalWriteApi
    @Throws(IllegalArgumentException::class)
    fun maxSpeed(kmh: Int): ByteArray {
        require(kmh in MIN_MAX_SPEED_KMH..MAX_WIRE_SPEED_KMH) { "speed must be 3..65 km/h" }
        return command(RIDE_SETTINGS_ID, u32x2(RIDE_FIELD_MAX_SPEED, kmh * 1000))
    }

    /**
     * Pedal tilt (horizon) in tenths of a degree, -8.0..+8.0: ride settings field 0x00, value
     * signed LE32(tenths x 65536 / 10), truncated toward zero as WheelLog does. Read back from slow
     * info i32 at 56 / 6553.6, which rounds a truncated value back to the same tenth.
     * Validation: WheelLog + InMotion Pro decompile agree on the field (Q16.16 degrees).
     */
    @ExperimentalWriteApi
    @Throws(IllegalArgumentException::class)
    fun pedalTilt(tenths: Int): ByteArray {
        require(tenths in PEDAL_TILT_TENTHS_RANGE) { "tilt must be -80..80 tenths of a degree" }
        return command(RIDE_SETTINGS_ID, u32x2(RIDE_FIELD_PEDAL_TILT, tenths * 65536 / 10))
    }

    /**
     * Handle button: id 0x0F55012E, data[0] = 1 enabled / 0 disabled. Read back from slow info
     * byte 129 (1 = enabled).
     *
     * WheelLog's builder takes a flag its settings screen fills with "handle button disabled",
     * so its "on" argument writes 0; the byte itself means enabled at 1 in both its readback and
     * the official app, which calls the same byte "lift-up detection" / spin kill.
     * Validation: WheelLog + InMotion Pro decompile agree on bytes; whether "handle button" and
     * "lift-up detection" are the same physical feature is not established.
     */
    @ExperimentalWriteApi
    fun handleButton(enabled: Boolean): ByteArray = command(HANDLE_BUTTON_ID, u32x2(enabled.toFlag(), 0))

    /**
     * Pedal sensitivity 4..100: ride settings field 0x06, value LE16((sensitivity + 28) x 32). Read
     * back from slow info byte 124 - 28.
     *
     * WheelLog-only scaling. The official app writes the same field as 2048 + sensitivity x 20.48
     * and reads byte 124 as (byte x 32 - 2048) x 100 / 2048. The two agree only at 100 (0x1000,
     * byte 0x80), the only value any capture carries; WheelLog's pair is used because it round-trips
     * on that capture and over its whole range, but below 100 the wheel may understand a different
     * number than the one shown.
     * Validation: WheelLog (scaling); InMotion Pro decompile agrees on id and field only.
     */
    @ExperimentalWriteApi
    @Throws(IllegalArgumentException::class)
    fun pedalSensitivity(sensitivity: Int): ByteArray {
        require(sensitivity in PEDAL_SENSITIVITY_RANGE) { "sensitivity must be 4..100" }
        return command(RIDE_SETTINGS_ID, u32x2(RIDE_FIELD_PEDAL_SENSITIVITY, (sensitivity + 28) shl 5))
    }

    /**
     * Horn: play sound 24 (id 0x0F550609, data[0] = sound number), which is what the official app's
     * horn button sends on every model it supports - V5 family, V8, V8F, V8S, V10, V10F. Null for
     * other models.
     *
     * WheelLog's beep sends remote control 0xB2 / 0x11 on V8F and later and plays sound 4 on older
     * wheels. 0x11 appears nowhere in the official app and sits in the sub-id space of lock and
     * power-off, so it is not used.
     * Validation: WheelLog + InMotion Pro decompile agree on the play-sound message; the sound
     * number is the decompile's.
     */
    @ExperimentalWriteApi
    fun horn(model: InmotionLegacyModel): ByteArray? =
        if (hasOfficialHorn(model)) command(PLAY_SOUND_ID, u32x2(HORN_SOUND, 0)) else null

    /** The models the official app has a horn for; see [horn]. */
    fun hasOfficialHorn(model: InmotionLegacyModel): Boolean =
        model.isV5Family || model == InmotionLegacyModel.V8 || model == InmotionLegacyModel.V8F ||
            model == InmotionLegacyModel.V8S || model == InmotionLegacyModel.V10 || model == InmotionLegacyModel.V10F

    /**
     * True for a message this library never sends: calibration, PIN change, berm angle, or a
     * remote-control sub-command other than the decorative LED switch.
     */
    fun isForbidden(message: InmotionLegacyMessage): Boolean = when (message.id) {
        CALIBRATION_ID, PIN_CHANGE_ID, BERM_ANGLE_ID -> true
        // Only the LED switch is ever sent on the remote-control id; the listed sub-commands are
        // named so the reason is visible, and anything unlisted is refused as well.
        REMOTE_CONTROL_ID -> {
            val sub = message.data.ezU8(4)
            message.data.ezU8(0) != REMOTE_CONTROL_MARKER || sub in FORBIDDEN_REMOTE_SUBCOMMANDS ||
                sub !in LED_SUBCOMMANDS
        }
        else -> false
    }

    /** Ids of the write commands this file builds; the wheel answers each with the same id. */
    internal val WRITE_IDS: Set<Int> = setOf(
        HEADLIGHT_ID,
        REMOTE_CONTROL_ID,
        RIDE_SETTINGS_ID,
        HANDLE_BUTTON_ID,
        SPEAKER_VOLUME_ID,
    )

    /** The largest speed whose x 1000 still fits the 16-bit readback field. */
    private const val MAX_WIRE_SPEED_KMH = 65

    private fun command(id: Int, data: ByteArray): ByteArray = InmotionLegacyFraming.encode(
        InmotionLegacyMessage(id = id, data = data, length = 8, channel = 5, format = 0, type = 0, extra = null),
    )

    /** data = LE32([first]) followed by LE32([second]). */
    private fun u32x2(first: Int, second: Int): ByteArray {
        val data = ByteArray(8)
        for (index in 0 until 4) {
            data[index] = (first ushr (8 * index)).toByte()
            data[index + 4] = (second ushr (8 * index)).toByte()
        }
        return data
    }

    private fun degreesToTenths(degrees: Double): Int {
        val scaled = degrees * 10.0
        return if (scaled >= 0) (scaled + 0.5).toInt() else -((-scaled + 0.5).toInt())
    }

    private fun Boolean.toFlag(): Int = if (this) 1 else 0
}
