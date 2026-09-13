package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V12S, V11Y, V9 and V13 against real frames. Every hex frame below was captured from a wheel and
 * published with WheelLog's `InmotionAdapterV2Test` (`decode with v12s full data 1`, `v11y full
 * data 1`, `v9 full data 1`, `v13 settings data 1`, `v11y settings data 1`); the expected values
 * are what the InMotion Pro parsers for those models read out of the same bytes.
 */
@OptIn(ExperimentalWriteApi::class)
class InmotionV2ModelGapsTest {
    /**
     * Through the V12 layout this frame read speed from `charge_voltage` @4 and battery from the
     * aim pitch angle @24. The V14 layout gives the 83.73 V, 100 % and 70 km/h tilt-back a
     * freshly charged V12S reports.
     */
    @Test
    fun v12sRealtimeUsesTheV14Layout() {
        val engine = InmotionProtocolEngine()
        engine.consume(V12S_MAIN_INFO.bytes())

        val telemetry = engine.consume(V12S_REALTIME.bytes())?.telemetry

        assertNotNull(telemetry)
        assertEquals("inmotion_v14", telemetry.profileId)
        assertEquals(83.73, telemetry.voltage, 0.001)
        assertEquals(0.01, telemetry.batteryCurrent, 0.001)
        assertEquals(100.0, telemetry.batteryPercent, 0.001)
        assertEquals(99.68, telemetry.rideBatteryPercent, 0.001)
        assertEquals(-69.53, telemetry.pitchAngle, 0.001)
        assertEquals(70.0, telemetry.speedTiltBackKmh, 0.001)
        assertEquals(70.0, telemetry.busCurrentWarningAmps, 0.001)
        assertEquals(270.0, telemetry.phaseCurrentLimitAmps, 0.001)
        assertEquals(29.0, telemetry.mosTemperature, 0.001)
        assertEquals(30.0, telemetry.motorTemperature, 0.001)
        assertEquals(27.0, telemetry.imuTemperature, 0.001)
    }

    /**
     * Battery is `battery_percentage` @34, not WheelLog's average of @34 and @36: the Pro app
     * names @36 `battery_percentage_for_ride`, a second view of the same pack rather than a
     * second pack.
     */
    @Test
    fun v11yRealtimeUsesTheV14Layout() {
        val engine = InmotionProtocolEngine()
        engine.consume(V11Y_MAIN_INFO.bytes())

        val telemetry = engine.consume(V11Y_REALTIME.bytes())?.telemetry

        assertNotNull(telemetry)
        assertEquals("inmotion_v14", telemetry.profileId)
        assertEquals(78.28, telemetry.voltage, 0.001)
        assertEquals(1.35, telemetry.speedKmh, 0.001)
        assertEquals(2.65, telemetry.torque, 0.001)
        assertEquals(6.13, telemetry.lateralAngle, 0.001)
        assertEquals(30.0, telemetry.tripDistanceMeters, 0.001)
        assertEquals(82.67, telemetry.batteryPercent, 0.001)
        assertEquals(80.05, telemetry.rideBatteryPercent, 0.001)
        assertEquals(41.0, telemetry.speedTiltBackKmh, 0.001)
        assertEquals(58.0, telemetry.busCurrentWarningAmps, 0.001)
        assertEquals(28.0, telemetry.mosTemperature, 0.001)
        assertEquals(21.0, telemetry.motorTemperature, 0.001)
        assertEquals(29.0, telemetry.imuTemperature, 0.001)
    }

    @Test
    fun v9RealtimeUsesTheV14Layout() {
        val engine = InmotionProtocolEngine()
        engine.consume(V9_MAIN_INFO.bytes())

        val telemetry = engine.consume(V9_REALTIME.bytes())?.telemetry

        assertNotNull(telemetry)
        assertEquals("inmotion_v14", telemetry.profileId)
        assertEquals(77.42, telemetry.voltage, 0.001)
        assertEquals(-0.81, telemetry.torque, 0.001)
        assertEquals(-0.01, telemetry.pitchAngle, 0.001)
        assertEquals(-2.97, telemetry.lateralAngle, 0.001)
        assertEquals(60.0, telemetry.tripDistanceMeters, 0.001)
        assertEquals(60.42, telemetry.batteryPercent, 0.001)
        assertEquals(42.29, telemetry.speedTiltBackKmh, 0.001)
        assertEquals(40.0, telemetry.busCurrentWarningAmps, 0.001)
    }

    @Test
    fun v12sSettingsBlockDecodesWithTheP6ShapedLayout() {
        val engine = InmotionProtocolEngine()
        engine.consume(V12S_MAIN_INFO.bytes())
        engine.consume(V12S_SETTINGS.bytes())

        val settings = engine.settingsCapabilities().associateBy { it.key }

        // 70 km/h is the tilt-back speed this wheel's realtime frame reports too.
        assertEquals(70, settings.getValue("inmotion_speed_limit").rawValue)
        assertEquals(70, settings.getValue("inmotion_speed_warning_level_1").rawValue)
        assertEquals(30, settings.getValue("inmotion_speed_warning_level_2").rawValue)
        assertEquals(10, settings.getValue("inmotion_standby_time").rawValue)
        assertEquals(1, settings.getValue("inmotion_ride_mode").rawValue)
        assertEquals(100, settings.getValue("inmotion_pedal_sensitivity_1").rawValue)
        assertEquals(21, settings.getValue("inmotion_voice_volume").rawValue)
        assertEquals(10, settings.getValue("inmotion_auto_light_low_thr").rawValue)
        assertEquals(40, settings.getValue("inmotion_auto_light_high_thr").rawValue)
        assertEquals(100, settings.getValue("inmotion_high_beam_brightness").rawValue)
        assertEquals(20, settings.getValue("inmotion_high_beam_auto_switch_speed").rawValue)
        assertEquals(50, settings.getValue("inmotion_braking_feedback").rawValue)
        // Flag word 0x00143005: bits 0, 2, 12, 13 and 20.
        assertEquals(1, settings.getValue("inmotion_audio_switch").rawValue)
        assertEquals(1, settings.getValue("inmotion_lift_up_detection").rawValue)
        assertEquals(0, settings.getValue("inmotion_auto_light_state").rawValue)
        assertEquals(1, settings.getValue("inmotion_usb_power_switch").rawValue)
        assertEquals(1, settings.getValue("inmotion_auto_close_screen").rawValue)
        assertEquals(0, settings.getValue("inmotion_berm_angle_mode").rawValue)
        assertEquals(1, settings.getValue("inmotion_show_tbox_info").rawValue)
        assertNull(settings["inmotion_metric_unit"])
    }

    @Test
    fun v9SettingsBlockNamesBits17And18Differently() {
        val engine = InmotionProtocolEngine()
        engine.consume(V9_MAIN_INFO.bytes())
        engine.consume(V9_SETTINGS.bytes())

        val settings = engine.settingsCapabilities().associateBy { it.key }

        assertEquals(45, settings.getValue("inmotion_speed_limit").rawValue)
        assertEquals(40, settings.getValue("inmotion_speed_warning_level_1").rawValue)
        assertEquals(26, settings.getValue("inmotion_voice_volume").rawValue)
        assertEquals(30, settings.getValue("inmotion_speeding_feedback").rawValue)
        // Flag word 0x00153005: bit 16 acceleration feedback, bit 17 clear, bit 18 DRL.
        assertEquals(1, settings.getValue("inmotion_acce_feedback").rawValue)
        assertEquals(0, settings.getValue("inmotion_metric_unit").rawValue)
        assertEquals(1, settings.getValue("inmotion_drl_state").rawValue)
        assertNull(settings["inmotion_berm_angle_mode"])
        assertEquals(KIND_READONLY, settings.getValue("inmotion_drl_state").kind)
    }

    @Test
    fun v13SettingsBlockDecodesWithThePackedFlagBytes() {
        val engine = InmotionProtocolEngine()
        engine.consume(V13_MAIN_INFO.bytes())
        engine.consume(V13_SETTINGS.bytes())

        val settings = engine.settingsCapabilities().associateBy { it.key }

        assertEquals(90, settings.getValue("inmotion_speed_limit").rawValue)
        assertEquals(85, settings.getValue("inmotion_speed_warning_level_1").rawValue)
        assertEquals(9, settings.getValue("inmotion_pitch_zero").rawValue)
        assertEquals(48, settings.getValue("inmotion_speeding_feedback").rawValue)
        assertEquals(44, settings.getValue("inmotion_braking_feedback").rawValue)
        assertEquals(33, settings.getValue("inmotion_voice_volume").rawValue)
        assertEquals(10, settings.getValue("inmotion_standby_time").rawValue)
        assertEquals(100, settings.getValue("inmotion_light_brightness").rawValue)
        // Byte 30 = 0x55: sound, DRL, lift-up and auto headlight each read 1.
        assertEquals(1, settings.getValue("inmotion_audio_switch").rawValue)
        assertEquals(1, settings.getValue("inmotion_drl_state").rawValue)
        assertEquals(1, settings.getValue("inmotion_lift_up_detection").rawValue)
        assertEquals(1, settings.getValue("inmotion_auto_light_state").rawValue)
        assertEquals(1, settings.getValue("inmotion_no_load_detect").rawValue)
        assertEquals(1, settings.getValue("inmotion_usb_power_switch").rawValue)
        // Byte 34 = 0x10: bit 4 speed-limit unlock, bit 5 berm-angle mode clear.
        assertEquals(1, settings.getValue("inmotion_speed_limit_unlock").rawValue)
        assertEquals(0, settings.getValue("inmotion_berm_angle_mode").rawValue)
        assertNull(settings["inmotion_charge_cut_off_percent"])
    }

    @Test
    fun v11ySettingsBlockAddsTheChargeCutOff() {
        val engine = InmotionProtocolEngine()
        engine.consume(V11Y_MAIN_INFO.bytes())
        engine.consume(V11Y_SETTINGS.bytes())

        val settings = engine.settingsCapabilities().associateBy { it.key }

        // 41 km/h is the tilt-back speed in the same session's realtime frame.
        assertEquals(41, settings.getValue("inmotion_speed_limit").rawValue)
        assertEquals(36, settings.getValue("inmotion_speed_warning_level_1").rawValue)
        assertEquals(50, settings.getValue("inmotion_voice_volume").rawValue)
        assertEquals(100, settings.getValue("inmotion_low_beam_brightness").rawValue)
        assertEquals(90, settings.getValue("inmotion_charge_cut_off_percent").rawValue)
        // Byte 30 = 0x14: DRL and lift-up on, sound and auto headlight off.
        assertEquals(0, settings.getValue("inmotion_audio_switch").rawValue)
        assertEquals(1, settings.getValue("inmotion_drl_state").rawValue)
        assertEquals(1, settings.getValue("inmotion_lift_up_detection").rawValue)
        assertEquals(0, settings.getValue("inmotion_auto_light_state").rawValue)
        assertEquals(1, settings.getValue("inmotion_load_detect").rawValue)
        assertEquals(1, settings.getValue("inmotion_assist_balance").rawValue)
        assertNull(settings["inmotion_light_brightness"])
    }

    /** Only volume, sound and auto headlight have bytes both sources agree on for these models. */
    @Test
    fun newModelSettingsWriteOnlyTheThreeAgreedFields() {
        val blocks = listOf(
            V13_MAIN_INFO to V13_SETTINGS,
            V11Y_MAIN_INFO to V11Y_SETTINGS,
            V12S_MAIN_INFO to V12S_SETTINGS,
            V9_MAIN_INFO to V9_SETTINGS,
        )
        for ((mainInfo, settingsFrame) in blocks) {
            val engine = InmotionProtocolEngine()
            engine.consume(mainInfo.bytes())
            engine.consume(settingsFrame.bytes())
            val writable = engine.settingsCapabilities().filter { it.kind != KIND_READONLY }.map { it.key }.toSet()

            assertEquals(
                setOf("inmotion_voice_volume", "inmotion_audio_switch", "inmotion_auto_light_state"),
                writable,
            )
            assertEquals("AAAA140360261E4F", engine.buildSettingCommand("inmotion_voice_volume", 30)?.hex())
            assertEquals("AAAA1403602C005B", engine.buildSettingCommand("inmotion_audio_switch", 0)?.hex())
            assertEquals("AAAA1403602F0058", engine.buildSettingCommand("inmotion_auto_light_state", 0)?.hex())
            assertNull(engine.buildSettingCommand("inmotion_speed_limit", 40))
            assertNull(engine.buildSettingCommand("inmotion_drl_state", 1))
            assertNull(engine.buildSettingCommand("inmotion_lock_mode", 1))
        }
    }

    /**
     * No capture has a V12S answering `0x08`, and its block is not the V12 one - byte 32, the
     * V12 status byte, is a reserved word there. Parsed with V12 offsets it used to hand out a
     * writable auto-headlight toggle bound to that word.
     */
    @Test
    fun v12sSettingsReadIsNotParsedWithTheV12Layout() {
        val engine = InmotionProtocolEngine()
        engine.consume(V12S_MAIN_INFO.bytes())
        engine.consume(buildFrame(0x14, 0x08, ByteArray(33) { 0x0A }))

        assertEquals(emptyList(), engine.settingsCapabilities())
    }

    @Test
    fun hornBytesFollowTheModelWhereBothSourcesAgree() {
        fun horn(vararg frames: String): String? {
            val engine = InmotionProtocolEngine()
            frames.forEach { engine.consume(it.bytes()) }
            return engine.hornCommand()?.hex()
        }

        assertEquals("AAAA14046051180138", horn(V12S_MAIN_INFO))
        assertEquals("AAAA14046051180138", horn(V9_MAIN_INFO))
        assertEquals("AAAA14046051180138", horn(buildFrame(0x11, 0x02, byteArrayOf(1, 2, 7, 3)).hex()))
        assertEquals("AAAA14046051026447", horn(V11Y_MAIN_INFO))
        // V13 and V14 are where WheelLog and the Pro app send different bytes; P6 has no WheelLog.
        assertNull(horn(V13_MAIN_INFO))
        assertNull(horn(buildFrame(0x11, 0x02, byteArrayOf(1, 2, 9, 2)).hex()))
        assertNull(horn(buildFrame(0x11, 0x02, byteArrayOf(1, 2, 13, 1)).hex()))
        assertNull(horn())
    }

    @Test
    fun v11HornWaitsForFirmwareOneFourOrLater() {
        val v11MainInfo = "AAAA110882010206010201009C"
        fun versionBlock(major: Int, minor: Int) = buildFrame(
            0x11,
            0x02,
            ByteArray(24).apply {
                this[0] = 0x06
                this[13] = minor.toByte()
                this[14] = major.toByte()
            },
        ).hex()
        fun horn(vararg frames: String): String? {
            val engine = InmotionProtocolEngine()
            frames.forEach { engine.consume(it.bytes()) }
            return engine.hornCommand()?.hex()
        }

        assertNull(horn(v11MainInfo))
        assertNull(horn(v11MainInfo, versionBlock(major = 1, minor = 3)))
        assertEquals("AAAA14046051180138", horn(v11MainInfo, versionBlock(major = 1, minor = 4)))
    }

    @Test
    fun shortV14LayoutFrameIsNotReadWithAnotherModelsOffsets() {
        val engine = InmotionProtocolEngine()
        engine.consume(V12S_MAIN_INFO.bytes())

        val update = engine.consume(buildFrame(0x14, 0x04, ByteArray(50)))

        assertTrue(update != null && update.telemetry == null)
    }
}

private const val V12S_MAIN_INFO = "aaaa11088201020b0101010092"
private const val V12S_SETTINGS =
    "aaaa142ca0200000000006030008581b581bb80b0000580210646415020a28646428d0073232040000002508053014008e"
private const val V12S_REALTIME =
    "aaaa145784b520010000000000000000000000000000000000d7e40000d7e400000000000000002427f026e02e581b581b" +
        "401f401f581b581b786900000000cdce00ceb0cbccceb0216403000000000000000000000000000000001b"
private const val V11Y_MAIN_INFO = "aaaa110882010206020101009c"
private const val V11Y_SETTINGS =
    "aaaa1428a0200410100e401f401f0000006464323232000000005802000a28645a280000144001040100250d92"
private const val V11Y_REALTIME =
    "aaaa145984941e11000000000087000000090104020000000000006502000000000300000000004b20451fe02e0410100e" +
        "401f401fa816a816c05d00000000ccc5cecdb0cd0000b0c36400000000004900000000000000000000000000fe"
private const val V9_MAIN_INFO = "aaaa11088201020c0101010095"
private const val V9_SETTINGS =
    "aaaa142ca0202a000000071900089411a00f9511000058020064641a020a28646428d0071e32010001012501053015009c"
private const val V9_REALTIME =
    "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f" +
        "401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f"
private const val V13_MAIN_INFO = "AAAA1108820102080101010091"
private const val V13_SETTINGS =
    "AAAA1428A02028233421401F401F5A00006464302C21000000005802000A28645A2800005500010410000000EB"

private fun String.bytes(): ByteArray =
    filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
}

/** A response frame, command field included: ids from `0x20` up take two bytes. */
private fun buildFrame(flag: Int, command: Int, data: ByteArray): ByteArray {
    val commandBytes = if (command <= 0x1F) {
        byteArrayOf((command or 0x80).toByte())
    } else {
        byteArrayOf((0xA0 or ((command shr 8) and 0x1F)).toByte(), (command and 0xFF).toByte())
    }
    val body = commandBytes + data
    val payload = byteArrayOf(flag.toByte(), body.size.toByte()) + body
    val checksum = payload.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) }.toByte()
    return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + payload + byteArrayOf(checksum)
}
