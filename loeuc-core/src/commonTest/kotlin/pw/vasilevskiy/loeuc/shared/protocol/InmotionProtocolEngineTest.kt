package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InmotionProtocolEngineTest {
    @Test
    fun buildsReadOnlyProtocolCommands() {
        val engine = InmotionProtocolEngine()

        assertEquals("AAAA1102020110", engine.mainInfoCommand().hex())
        assertEquals("AAAA14010411", engine.realtimeCommand().hex())
        assertEquals("AAAA1401081D", engine.settingsCommand().hex())
        // The 0x20 read carries the request parameter byte; the empty-payload form
        // (AAAA14012035) was never answered by any captured wheel.
        assertEquals("AAAA1402202016", engine.settingsAllCommand().hex())
    }

    /**
     * Real main-info frame from `loeuc_EB36B63A1665_unknown_20260813_004213.jsonl`
     * (`V11-C7800041`): model bytes `06 01` resolve to id 61, which is what routes the
     * realtime frame into the V11 layout instead of the size-based V12/V14 fallback.
     */
    @Test
    fun parsesRealV11MainInfoAndDecodesFirmware14Telemetry() {
        val engine = InmotionProtocolEngine()
        assertEquals(2, engine.consume("AAAA110882010206010201009C".bytes())?.responseCommand)

        val data = ByteArray(62).apply {
            putInt16Le(0, 7_363)
            putInt16Le(2, -150)
            putInt16Le(4, 1_234)
            putInt16Le(6, 250)
            putInt16Le(8, 4_200)
            putInt16Le(10, 300)
            putInt16Le(12, 280)
            putInt16Le(16, 150)
            putInt16Le(18, 100)
            putInt16Le(20, -50)
            putInt16Le(26, 123)
            putInt16Le(28, 6_550)
            putInt16Le(30, 2_500)
            putInt16Le(34, 5_000)
            putInt16Le(36, 3_000)
            this[42] = celsius(35)
            this[43] = celsius(40)
            this[44] = celsius(25)
            this[45] = celsius(30)
            this[46] = celsius(45)
            this[47] = celsius(33)
        }

        val telemetry = assertNotNull(engine.consume(buildFrame(0x14, 0x04, data))?.telemetry)
        assertEquals("inmotion_v11", telemetry.profileId)
        assertEquals(73.63, telemetry.voltage, 0.01)
        assertEquals(-1.5, telemetry.batteryCurrent, 0.01)
        assertEquals(12.34, telemetry.speedKmh, 0.01)
        assertEquals(2.5, telemetry.torque, 0.01)
        assertEquals(42.0, telemetry.pwmPercent, 0.01)
        assertEquals(300.0, telemetry.power, 0.01)
        assertEquals(280.0, telemetry.motorPower, 0.01)
        assertEquals(1.5, telemetry.pitchAngle, 0.01)
        assertEquals(-0.5, telemetry.lateralAngle, 0.01)
        assertEquals(1_230.0, telemetry.tripDistanceMeters, 0.01)
        assertEquals(65.5, telemetry.batteryPercent, 0.01)
        assertEquals(50.0, telemetry.speedTiltBackKmh, 0.01)
        assertEquals(30.0, telemetry.currentLimit, 0.01)
        assertEquals(35.0, telemetry.mosTemperature, 0.01)
        assertEquals(40.0, telemetry.motorTemperature, 0.01)
        assertEquals(25.0, telemetry.batteryTemperature, 0.01)
        assertEquals(30.0, telemetry.boardTemperature, 0.01)
        assertEquals(45.0, telemetry.cpuTemperature, 0.01)
        assertEquals(33.0, telemetry.imuTemperature, 0.01)
        // The lamp sensor byte is a raw zero here, which means "no sensor", not -176 °C.
        assertTrue(telemetry.lampTemperature.isNaN())
    }

    /**
     * One notification, two complete frames - the real 109-byte chunk that carried the V11's
     * settings block and a realtime frame together (`loeuc_dump_1786713663.jsonl`, chunk 2).
     *
     * `consume` used to keep only the last frame's update, so the settings answer was reported
     * as a realtime one. The caller refreshes the settings screen when it sees `0x08`/`0x20`,
     * that never arrived, and the block is a one-shot read - so the wheel's settings screen
     * stayed empty for the whole session while the engine held all 26 values.
     */
    @Test
    fun reportsEveryFrameWhenOneNotificationCarriesTwo() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes())

        val update = assertNotNull(
            engine.consume(
                (
                    "AAAA141EA0207C156400104A50322A0000005802000000642E3D1500041001000000B8" +
                        "AAAA144584FB1EF5FF00000DFB160100000000000050005AFF6400000000000000EE1E" +
                        "FA1C65437C15641900000000C6B000C7CBCC0000280000000000491400000000000000" +
                        "000000AD"
                    ).bytes(),
            ),
        )

        assertEquals(listOf(0x20, 0x04), update.responseCommands)
        // Still the last frame, so the realtime ping-pong keeps working off this field.
        assertEquals(0x04, update.responseCommand)
        assertEquals(79.31, assertNotNull(update.telemetry).voltage, 0.01)
        assertEquals(26, engine.settingsCapabilities().size)
        assertEquals(55, engine.settingsCapabilities().first { it.key == KEY_SPEED_LIMIT }.rawValue)
    }

    /**
     * The earlier frame's payload must survive too, not just its command number: a P6 answers
     * its battery read and a realtime read close enough together to share a notification.
     */
    @Test
    fun keepsAnEarlierFramesSnapshotWhenALaterFrameFollowsInTheSameChunk() {
        val engine = InmotionProtocolEngine()
        engine.consume(buildFrame(0x11, 0x02, byteArrayOf(0x01, 0x02, 0x0D, 0x01, 0x02, 0x01, 0x00)))

        val diagnostics = buildFrame(0x14, 0x03, ByteArray(8))
        val realtime = buildFrame(0x14, 0x04, ByteArray(62))
        val update = assertNotNull(engine.consume(diagnostics + realtime))

        assertEquals(listOf(0x03, 0x04), update.responseCommands)
        assertNotNull(update.diagnosticsSnapshot)
        assertNotNull(update.telemetry)
    }

    /**
     * The first realtime frame a V11 has ever answered, from `loeuc_dump_1786713663.jsonl`
     * (14.08, iOS build 38, wheel standing still, 683 such frames in the session). Until this
     * capture the 1.4+ layout came only from `V11RealTimeInfo.fromBytes` in the decompiled app
     * and had never met hardware.
     *
     * What the frame confirms: 68-byte payload routes to the 1.4+ layout, and voltage, battery
     * percent, angles, tiltback speed, current limit and four of the seven temperatures land on
     * values a standing 20S wheel can actually have - 79.31 V at 79.18 % is 80 % of a 60..84 V
     * pack, to within a rounding step.
     *
     * What it does not confirm: speed, power and trip are all zero on a wheel that never moved.
     * And two fields read wrong, so they are pinned here as observed rather than as correct -
     * see the open questions in docs/inmotion-v11-roadmap.md:
     *  - `@6` decodes to -12.67 N*m while battery current is -0.11 A and both power fields are
     *    zero. A wheel holding balance does apply torque, but not that much at that current.
     *  - `@43` carries `0xB0`, which the shared offset turns into exactly 0 C for the motor next
     *    to a 22 C MOSFET. `presentTemperature` only discards a raw zero, so this reaches the
     *    dashboard as a real reading.
     */
    @Test
    fun decodesTheFirstRealV11RealtimeFrame() {
        val engine = InmotionProtocolEngine()
        assertEquals(2, engine.consume("AAAA110882010206010201009C".bytes())?.responseCommand)

        val frame = (
            "AAAA144584FB1EF5FF00000DFB160100000000000050005AFF6400000000000000EE1EFA1C" +
                "65437C15641900000000C6B000C7CBCC0000280000000000491400000000000000000000AD"
            ).bytes()
        val telemetry = assertNotNull(engine.consume(frame)?.telemetry)

        assertEquals("inmotion_v11", telemetry.profileId)
        assertEquals(79.31, telemetry.voltage, 0.01)
        assertEquals(-0.11, telemetry.batteryCurrent, 0.01)
        assertEquals(79.18, telemetry.batteryPercent, 0.01)
        assertEquals(0.0, telemetry.speedKmh, 0.01)
        assertEquals(0.0, telemetry.tripDistanceMeters, 0.01)
        assertEquals(0.8, telemetry.pitchAngle, 0.01)
        assertEquals(1.0, telemetry.lateralAngle, 0.01)
        assertEquals(55.0, telemetry.speedTiltBackKmh, 0.01)
        assertEquals(65.0, telemetry.currentLimit, 0.01)
        assertEquals(22.0, telemetry.mosTemperature, 0.01)
        assertEquals(23.0, telemetry.boardTemperature, 0.01)
        assertEquals(27.0, telemetry.cpuTemperature, 0.01)
        assertEquals(28.0, telemetry.imuTemperature, 0.01)
        // Raw zero means "no sensor" for both of these, and that is how they come off the wheel.
        assertTrue(telemetry.batteryTemperature.isNaN())
        assertTrue(telemetry.lampTemperature.isNaN())
        // Byte 43 is a constant `0xB0` on this wheel, not a motor reading. See the spin-up test.
        assertTrue(telemetry.motorTemperature.isNaN())
        assertEquals(-12.67, telemetry.torque, 0.01)
    }

    /**
     * Same wheel, spun up in the air to 78 km/h (`loeuc_dump_1786714254.jsonl`, 552 realtime
     * frames), at the frame of peak speed. The standing capture left speed, PWM, power and
     * torque unexercised; here they all move together the way a free-spinning motor makes them.
     *
     * This frame is also the torque proof: 789 W at 21.79 m/s is 9.0 N*m at a 0.25 m radius, and
     * over the capture's loaded frames the slot tracks motorPower/speed at r=1.000 with a fitted
     * radius of 0.249 m - the V11's 18" wheel.
     */
    @Test
    fun decodesAV11AtFullSpeedFromTheSpinUpCapture() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes())

        val frame = (
            "AAAA144584B21EF7015AE14CFD0F27B10315030000E5FA0B0805047CFE02000000F11E" +
                "FD1C65437C15641900000000C7B000C7CDCD00002800000000004914000000000000" +
                "000C000061"
            ).bytes()
        val telemetry = assertNotNull(engine.consume(frame)?.telemetry)

        assertEquals(78.58, telemetry.voltage, 0.01)
        assertEquals(5.03, telemetry.batteryCurrent, 0.01)
        assertEquals(-78.46, telemetry.speedKmh, 0.01)
        assertEquals(99.99, telemetry.pwmPercent, 0.01)
        assertEquals(945.0, telemetry.power, 0.01)
        assertEquals(789.0, telemetry.motorPower, 0.01)
        assertEquals(-6.92, telemetry.torque, 0.01)
        assertTrue(telemetry.motorTemperature.isNaN())
    }

    /**
     * The pre-1.4 V11 frame is shorter and has its own field order. The current app does
     * not carry a parser for it, so this only pins the routing and the fields LoEUC
     * already decoded - a short frame from a V11 must not fall through to the V12 layout.
     */
    @Test
    fun routesPreFirmware14V11FrameToTheLegacyLayout() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes())

        val data = ByteArray(44).apply {
            putInt16Le(0, 7_363)
            putInt16Le(2, -150)
            putInt16Le(4, 1_234)
            putInt16Le(8, 300)
            putInt16Le(12, 123)
            this[16] = (0x80 or 66).toByte()
            this[17] = celsius(35)
            putInt16Le(22, 150)
            putInt16Le(26, -50)
            putInt16Le(28, 5_000)
            putInt16Le(30, 3_000)
            putInt16Le(36, 4_200)
        }

        val telemetry = assertNotNull(engine.consume(buildFrame(0x14, 0x04, data))?.telemetry)
        assertEquals("inmotion_v11", telemetry.profileId)
        assertEquals(73.63, telemetry.voltage, 0.01)
        assertEquals(12.34, telemetry.speedKmh, 0.01)
        assertEquals(42.0, telemetry.pwmPercent, 0.01)
        assertEquals(66.0, telemetry.batteryPercent, 0.01)
        assertEquals(1_230.0, telemetry.tripDistanceMeters, 0.01)
        assertEquals(300.0, telemetry.power, 0.01)
        assertEquals(35.0, telemetry.temperature, 0.01)
        assertEquals(1.5, telemetry.pitchAngle, 0.01)
        assertEquals(-0.5, telemetry.lateralAngle, 0.01)
        assertEquals(50.0, telemetry.speedTiltBackKmh, 0.01)
        assertEquals(30.0, telemetry.currentLimit, 0.01)
    }

    @Test
    fun parsesV11SettingsBlockFromEitherSettingsRead() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes())

        val data = ByteArray(28).apply {
            putInt16Le(0, 4_500)
            putInt16Le(2, -25)
            this[4] = 0x21 // controller mode 1, pedal mode 2
            this[5] = 60
            this[6] = 80
            this[7] = 45
            putInt16Le(12, 600)
            this[15] = 30
            this[16] = 90
            this[17] = 70
            this[20] = 0x41 // sound on, auto headlight on
            this[21] = 0x04 // wheel lock on
            this[22] = 0x40 // fan on
        }

        engine.consume(buildFrame(0x14, 0x20, data))
        val settings = engine.settingsCapabilities().associateBy { it.key }

        assertEquals(45, settings.getValue("inmotion_speed_limit").rawValue)
        assertEquals(-2, settings.getValue("inmotion_pitch_zero").rawValue)
        assertEquals(1, settings.getValue("inmotion_driver_mode").rawValue)
        assertEquals(2, settings.getValue("inmotion_ride_mode").rawValue)
        assertEquals(60, settings.getValue("inmotion_pedal_sensitivity_1").rawValue)
        assertEquals(80, settings.getValue("inmotion_pedal_sensitivity_2").rawValue)
        assertEquals(45, settings.getValue("inmotion_voice_volume").rawValue)
        // Raw 600 seconds, exposed in minutes.
        assertEquals(10, settings.getValue("inmotion_standby_time").rawValue)
        assertEquals(30, settings.getValue("inmotion_auto_light_low_thr").rawValue)
        assertEquals(90, settings.getValue("inmotion_auto_light_high_thr").rawValue)
        assertEquals(70, settings.getValue("inmotion_light_brightness").rawValue)
        assertEquals(1, settings.getValue("inmotion_audio_switch").rawValue)
        assertEquals(1, settings.getValue("inmotion_auto_light_state").rawValue)
        assertEquals(1, settings.getValue("inmotion_lock_mode").rawValue)
        assertEquals(1, settings.getValue("inmotion_fan_status").rawValue)

        // Fields whose command id, payload shape or bounds are not resolved for V11 stay
        // display-only regardless of the value offered - see toV11SettingCapabilities's
        // class doc for the full writable/read-only split.
        listOf(
            "inmotion_pedal_sensitivity_2",
            "inmotion_driver_mode",
            "inmotion_ride_mode",
            "inmotion_load_detect",
            "inmotion_auto_brightness",
            "inmotion_light_effect_mode",
        ).forEach { key ->
            assertNull(engine.buildSettingCommand(key, 1))
        }

        // The same block is accepted from the legacy 0x08 read.
        val other = InmotionProtocolEngine()
        other.consume("AAAA110882010206010201009C".bytes())
        other.consume(buildFrame(0x14, 0x08, data))
        assertEquals(45, other.settingsCapabilities().single { it.key == "inmotion_speed_limit" }.rawValue)
    }

    /**
     * Every V11 write this change adds, checked against exact frame bytes. Sub-command ids
     * and payload shapes are the established protocol facts table cross-checked against a
     * real V11's WheelLog wire trace; the checksum arithmetic itself is exercised the same
     * way the V12/P6 write tests above check it.
     */
    @Test
    fun buildsV11SettingWrites() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes())

        val data = ByteArray(23).apply {
            putInt16Le(0, 3_600) // speed limit raw -> 36 km/h
            putInt16Le(2, -150) // pitch raw -> -15 (tenths of a degree)
            this[4] = 0x10 // driver mode 0, ride mode (Classic) 1
            this[5] = 74 // pedal sensitivity, Comfort
            this[6] = 80 // pedal sensitivity, Classic
            this[7] = 50 // voice volume
            putInt16Le(12, 900) // standby raw -> 15 min, still read-only
            this[14] = 2 // LED effect mode, read-only
            this[15] = 20 // auto light low thr, read-only
            this[16] = 200.toByte() // auto light high thr, read-only
            this[17] = 120 // headlight brightness
            this[18] = 40 // acceleration assist
            this[19] = 55 // brake assist
            this[20] = 0x45 // sound on, drl on, lift-up off, auto headlight on
            this[21] = 0x00 // auto brightness/lock/transport/load-detect all off
            this[22] = 0x51 // no-load on, low-battery-ride off, quiet fan on, fan on
        }
        engine.consume(buildFrame(0x14, 0x08, data))
        val settings = engine.settingsCapabilities().associateBy { it.key }
        assertEquals(74, settings.getValue("inmotion_pedal_sensitivity_1").rawValue)
        assertEquals(80, settings.getValue("inmotion_pedal_sensitivity_2").rawValue)
        assertEquals("Pedal sensitivity (Comfort)", settings.getValue("inmotion_pedal_sensitivity_1").titleEn)
        assertEquals("Pedal sensitivity (Classic)", settings.getValue("inmotion_pedal_sensitivity_2").titleEn)

        assertEquals("AAAA140460218813CA", engine.buildSettingCommand("inmotion_speed_limit", 50)?.hex())
        assertNull(engine.buildSettingCommand("inmotion_speed_limit", 151))

        assertEquals("AAAA140460229600C4", engine.buildSettingCommand("inmotion_pitch_zero", 15)?.hex())
        assertNull(engine.buildSettingCommand("inmotion_pitch_zero", -81))

        // Comfort's write preserves Classic's current raw byte (80 = 0x50) rather than
        // clobbering it - the correction over the original doubled-write hypothesis.
        assertEquals(
            "AAAA140460255A505F",
            engine.buildSettingCommand("inmotion_pedal_sensitivity_1", 90)?.hex(),
        )
        // Classic itself is still never written, whatever value is offered.
        assertNull(engine.buildSettingCommand("inmotion_pedal_sensitivity_2", 80))

        assertEquals("AAAA140360263C6D", engine.buildSettingCommand("inmotion_voice_volume", 60)?.hex())
        assertEquals("AAAA1403602B6438", engine.buildSettingCommand("inmotion_light_brightness", 100)?.hex())
        assertEquals("AAAA1403602C005B", engine.buildSettingCommand("inmotion_audio_switch", 0)?.hex())
        // V11's DRL id (0x2D) is not P6's (0x4E) - see SUB_DRL_V11's doc on the engine.
        assertEquals("AAAA1403602D015B", engine.buildSettingCommand("inmotion_drl_state", 1)?.hex())
        assertEquals("AAAA1403602F0159", engine.buildSettingCommand("inmotion_auto_light_state", 1)?.hex())
        assertEquals("AAAA140360360140", engine.buildSettingCommand("inmotion_no_load_detect", 1)?.hex())

        // The 0x3F pair: writing one assist re-sends the other's last known raw byte.
        assertEquals(
            "AAAA1404603F46373E",
            engine.buildSettingCommand("inmotion_speeding_feedback", 70)?.hex(),
        )
        assertEquals(
            "AAAA1404603F284126",
            engine.buildSettingCommand("inmotion_braking_feedback", 65)?.hex(),
        )
        assertNull(engine.buildSettingCommand("inmotion_speeding_feedback", 101))

        // Wheel lock, transport mode, lift-up detection and low battery ride mode: plain
        // toggles the official app exposes, all raw 0 (off) in this fixture.
        assertEquals(0, settings.getValue("inmotion_lock_mode").rawValue)
        assertEquals(0, settings.getValue("inmotion_transport_mode").rawValue)
        assertEquals(0, settings.getValue("inmotion_lift_up_detection").rawValue)
        assertEquals(0, settings.getValue("inmotion_low_battery_ride").rawValue)
        assertEquals("AAAA140360310147", engine.buildSettingCommand("inmotion_lock_mode", 1)?.hex())
        assertEquals("AAAA140360320144", engine.buildSettingCommand("inmotion_transport_mode", 1)?.hex())
        assertEquals("AAAA1403602E0158", engine.buildSettingCommand("inmotion_lift_up_detection", 1)?.hex())
        assertEquals("AAAA140360370141", engine.buildSettingCommand("inmotion_low_battery_ride", 1)?.hex())
        assertNull(engine.buildSettingCommand("inmotion_lock_mode", 2))

        // Standby timeout: minutes on the slider (raw 900 s -> 15 min here), 1..60 minutes,
        // seconds on the wire.
        assertEquals(15, settings.getValue("inmotion_standby_time").rawValue)
        assertEquals("AAAA14046028B004EC", engine.buildSettingCommand("inmotion_standby_time", 20)?.hex())
        assertNull(engine.buildSettingCommand("inmotion_standby_time", 0))
        assertNull(engine.buildSettingCommand("inmotion_standby_time", 61))
    }

    /**
     * `setHeadlightBrightness` clamps to 0..100 in the official app (see the "Two clamps worth
     * noting" note in docs/inmotion-protocol-notes.md), and a real V11 reports exactly 100 for
     * `lamp_brightness` at stock. The V11 row shipped a 0..255 slider, so the top three quarters
     * of it asked the wheel for values it does not accept.
     */
    @Test
    fun v11HeadlightBrightnessIsBoundedToTheClampTheWheelActuallyUses() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes())
        engine.consume(buildFrame(0x14, 0x20, v11SettingsBlock()))

        val brightness = engine.settingsCapabilities().single { it.key == "inmotion_light_brightness" }
        assertEquals(0, brightness.minValue)
        assertEquals(100, brightness.maxValue)
        assertNull(engine.buildSettingCommand("inmotion_light_brightness", 101))
    }

    /**
     * `fan_status` is a status field, not a setting - the same status/mode split `drl_light_status`
     * has, and the official `toMap` exposes only `fan_mute_mode` as a fan *setting*. Neither had a
     * usable write id either: `0x43` is `bermAngleModeSwitchCmd` in the very command table the rest
     * of these ids come from, and `0x38` appears in no table at all. Both rows are display-only
     * until an id survives one of AGENTS.md's three validation routes.
     */
    @Test
    fun v11FanRowsAreDisplayOnly() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes())
        engine.consume(buildFrame(0x14, 0x20, v11SettingsBlock()))

        val settings = engine.settingsCapabilities().associateBy { it.key }
        assertEquals(KIND_READONLY, settings.getValue("inmotion_fan_status").kind)
        assertEquals(KIND_READONLY, settings.getValue("inmotion_fan_mute_mode").kind)
        assertNull(engine.buildSettingCommand("inmotion_fan_status", 1))
        assertNull(engine.buildSettingCommand("inmotion_fan_mute_mode", 1))
    }

    /**
     * A wheel volunteers its settings block exactly once, in answer to the connect burst - the
     * real V11 capture `loeuc_dump_1786731914.jsonl` has one `0x20` answer at t+32 ms and none
     * over the next 92 seconds, across four Control(0x60) writes. Without a read-back the screen
     * keeps the pre-write `rawValue` for the rest of the session, which also freezes the Write
     * button: it enables on `selected != current`, so re-selecting the value the wheel already
     * moved away from looks like a no-op edit.
     */
    @Test
    fun settingsReadCommandsReAskForBothBlocks() {
        val engine = InmotionProtocolEngine()
        val commands = engine.settingsReadCommands().map { it.hex() }

        assertEquals(listOf("AAAA1401081D", "AAAA1402202016"), commands)
    }

    /** Stock-ish V11 block: byte 17 at the wheel's own reported 100, both fan bits set. */
    private fun v11SettingsBlock(): ByteArray = ByteArray(28).apply {
        putInt16Le(0, 5_500)
        this[5] = 74
        this[6] = 80
        this[7] = 10
        putInt16Le(12, 600)
        this[17] = 100
        this[20] = 0x05
        this[22] = 0x50
    }

    @Test
    fun buildsVersionInfoCommand() {
        val engine = InmotionProtocolEngine()
        // Same 0x02 main-info command as mainInfoCommand(), selector 0x06 instead of 0x01.
        assertEquals("AAAA1102020617", engine.versionInfoCommand().hex())
    }

    /**
     * `payload[0] == 0x06` identifies the version block; `major = payload[14]`,
     * `minor = payload[13]`. Cross-checked against WheelLog's own field offsets.
     */
    @Test
    fun parsesMainBoardVersionFromTheVersionBlockAndPreservesModelId() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes()) // real V11 model id 61

        // No headlight command until the version has actually answered - not a guess.
        assertNull(engine.v11HeadlightCommand(true))
        assertEquals(false, engine.mainBoardVersionKnown())

        val versionBlock = ByteArray(24).apply {
            this[0] = 0x06
            this[13] = 4 // minor
            this[14] = 1 // major -> 1.4
        }
        engine.consume(buildFrame(0x11, 0x02, versionBlock))
        assertEquals(true, engine.mainBoardVersionKnown())
        assertNotNull(engine.v11HeadlightCommand(true))

        // The version block answers on the same 0x02 command byte as the model block - this
        // is the regression the ?.let fix in consume() guards against: modelId must survive
        // it rather than being clobbered back to null, or settings would stop routing to the
        // V11 layout after the version read.
        engine.consume(buildFrame(0x14, 0x08, ByteArray(23)))
        assertTrue(engine.settingsCapabilities().isNotEmpty())
    }

    /**
     * The headlight quick action on both sides of the 1.4 boundary, and the exact frame
     * bytes each subcommand produces. WheelLog's own test is `major < 2 && minor < 4`.
     */
    @Test
    fun buildsV11HeadlightCommandOnBothSidesOfTheFirmwareFork() {
        fun versionBlock(major: Int, minor: Int) = ByteArray(24).apply {
            this[0] = 0x06
            this[13] = minor.toByte()
            this[14] = major.toByte()
        }

        val preFirmware = InmotionProtocolEngine()
        preFirmware.consume(buildFrame(0x11, 0x02, versionBlock(major = 1, minor = 3)))
        assertEquals("AAAA140360400136", preFirmware.v11HeadlightCommand(true)?.hex())
        assertEquals("AAAA140360400037", preFirmware.v11HeadlightCommand(false)?.hex())

        val postFirmware = InmotionProtocolEngine()
        postFirmware.consume(buildFrame(0x11, 0x02, versionBlock(major = 1, minor = 4)))
        assertEquals("AAAA140360500126", postFirmware.v11HeadlightCommand(true)?.hex())
        assertEquals("AAAA140360500027", postFirmware.v11HeadlightCommand(false)?.hex())

        // major >= 2 is unambiguously "1.4 and up" regardless of minor.
        val newMajor = InmotionProtocolEngine()
        newMajor.consume(buildFrame(0x11, 0x02, versionBlock(major = 2, minor = 0)))
        assertEquals("AAAA140360500126", newMajor.v11HeadlightCommand(true)?.hex())
    }

    @Test
    fun v11HeadlightCommandIsNullWithoutAnAnsweredVersion() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA110882010206010201009C".bytes()) // model known, version not
        assertNull(engine.v11HeadlightCommand(true))
        assertNull(engine.v11HeadlightCommand(false))
    }

    @Test
    fun reassemblesFragmentsAndParsesP6Telemetry() {
        val engine = InmotionProtocolEngine()
        val mainInfo = "AAAA11088201020D0101010094".bytes()
        val realtime = """
            AAAA1457841B58AA0200000000301500001708490C0206E404C90017FE640050004800E500
            E706F023D723983A983A983A401F401FE02EE02E50C300000000D0D400D7B0D4CCCFB0
            FA0004000000004900000000000000000000004A
        """.bytes()

        val mainUpdate = engine.consume(mainInfo)
        assertEquals(2, mainUpdate?.responseCommand)
        assertNull(mainUpdate?.telemetry)
        assertEquals(19, engine.bmsReadCommands().size)

        assertNull(engine.consume(realtime.copyOfRange(0, 17)))
        val telemetry = engine.consume(realtime.copyOfRange(17, realtime.size))?.telemetry

        assertNotNull(telemetry)
        assertEquals("inmotion_p6", telemetry.profileId)
        assertEquals(225.55, telemetry.voltage, 0.01)
        assertEquals(54.24, telemetry.speedKmh, 0.01)
        assertEquals(31.45, telemetry.pwmPercent, 0.01)
        assertEquals(6.82, telemetry.batteryCurrent, 0.01)
        assertEquals(1538.0, telemetry.power, 0.01)
        assertEquals(1252.0, telemetry.motorPower, 0.01)
        assertEquals(20.71, telemetry.torque, 0.01)
        assertEquals(2.01, telemetry.pitchAngle, 0.01)
        assertEquals(-4.89, telemetry.lateralAngle, 0.01)
        assertEquals(1.0, telemetry.targetAngle, 0.01)
        assertEquals(92.0, telemetry.batteryPercent, 0.01)
        assertEquals(91.75, telemetry.rideBatteryPercent, 0.01)
        assertEquals(2.29, telemetry.tirePressureBar, 0.01)
        assertEquals(150.0, telemetry.speedTiltBackKmh, 0.01)
        assertEquals(150.0, telemetry.speedWarningKmh, 0.01)
        assertEquals(80.0, telemetry.outputTiltBackPercent, 0.01)
        assertEquals(80.0, telemetry.outputWarningPercent, 0.01)
        assertEquals(120.0, telemetry.busCurrentTiltBackAmps, 0.01)
        assertEquals(120.0, telemetry.busCurrentWarningAmps, 0.01)
        assertEquals(500.0, telemetry.phaseCurrentLimitAmps, 0.01)
        // Charge voltage is tenths of a volt: raw 0 here, and the field is checked for
        // scale in chargeVoltageIsTenthsOfAVolt below.
        assertEquals(0.0, telemetry.chargeVoltage, 0.01)
        assertEquals(32.0, telemetry.mosTemperature, 0.01)
        assertEquals(36.0, telemetry.motorTemperature, 0.01)
        assertEquals(39.0, telemetry.boardTemperature, 0.01)
        assertEquals(28.0, telemetry.lampTemperature, 0.01)
        assertEquals(31.0, telemetry.batteryMaxCellTemperature, 0.01)
    }

    /**
     * A P6 charging at 232.4 V read out as 23.24 V while `bus_voltage` two fields earlier
     * was right, so `charge_voltage` is tenths of a volt. No capture holds a non-zero
     * value for it yet - the scale rests on that reading plus the fact that a 232 V pack
     * cannot charge at 23 V.
     */
    @Test
    fun chargeVoltageIsTenthsOfAVolt() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA11088201020D0101010094".bytes())
        val data = ByteArray(86).apply {
            putInt16Le(0, 23_240)
            putInt16Le(4, 2_324)
        }

        val telemetry = assertNotNull(engine.consume(buildFrame(0x14, 0x04, data))?.telemetry)
        assertEquals("inmotion_p6", telemetry.profileId)
        assertEquals(232.4, telemetry.voltage, 0.01)
        assertEquals(232.4, telemetry.chargeVoltage, 0.01)
    }

    @Test
    fun parsesConfirmedP6BatteryRealtimeResponse() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA11088201020D0101010094".bytes())

        val update = engine.consume(
            "AAAA141185CE580000000001001B020000000000000E".bytes(),
        )

        val bms = assertNotNull(update?.bmsSnapshot)
        assertEquals("inmotion_p6", bms.profileId)
        assertEquals(1, bms.batteries.size)
        with(bms.batteries.single()) {
            assertEquals(227.34, voltage, 0.001)
            assertEquals(0.0, chargeCurrent, 0.001)
            assertEquals(0.0, dischargeCurrent, 0.001)
            assertEquals(true, detected)
            assertEquals(false, enabled)
            assertEquals(false, hasFault)
        }
    }

    @Test
    fun parsesRealP6DiagnosticsReportWithMissingSecondBattery() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA11088201020D0101010094".bytes())

        val diagnostics = engine.consume("AAAA14098300040000000000009A".bytes())?.diagnosticsSnapshot
        val bms = engine.consume("AAAA141185CE580000000001001E020000000000000B".bytes())?.bmsSnapshot

        assertNotNull(diagnostics)
        assertEquals(0x00000400, diagnostics.errorCode)
        assertEquals(45, diagnostics.decodedItems.size)
        assertEquals(1, diagnostics.activeFlagCount)
        val active = diagnostics.decodedItems.single { it.isActive }
        assertEquals(10, active.index)
        assertEquals("MOS temperature sensor fault", active.titleEn)
        assertEquals("Ошибка датчика температуры MOS", active.titleRu)

        assertNotNull(bms)
        assertEquals("inmotion_p6", bms.profileId)
        assertEquals(1, bms.batteries.size)
        with(bms.batteries.single()) {
            assertEquals(1, index)
            assertEquals(true, detected)
            assertEquals(false, enabled)
            assertEquals(false, charging)
            assertEquals(227.34, voltage, 0.001)
            assertEquals(false, hasFault)
            // rawData is this pack's eight bytes; slot 2 (`1E02…`) is a separate,
            // undetected pack and is filtered out of the snapshot.
            assertEquals("CE58000000000100", rawData.hex())
        }
    }

    @Test
    fun parsesV13SafetyEnvelopeAndBatteryTemperatures() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA1108820102080101010091".bytes())
        val data = ByteArray(76).apply {
            putInt16Le(0, 12_600)
            putInt16Le(2, 450)
            putInt16Le(8, 4_200)
            putInt16Le(12, 1_250)
            putInt16Le(14, 3_500)
            putInt16Le(16, 567)
            putInt16Le(18, 500)
            putInt16Le(34, 8_000)
            putInt16Le(36, 7_800)
            putInt16Le(38, 9_000)
            putInt16Le(40, 9_000)
            putInt16Le(42, 8_500)
            putInt16Le(44, 8_000)
            putInt16Le(46, 7_500)
            putInt16Le(48, 10_000)
            putInt16Le(50, 9_000)
            putInt16Le(52, 30_000)
            this[58] = 210.toByte()
            this[60] = 211.toByte()
            this[65] = 212.toByte()
            this[66] = 213.toByte()
            this[74] = 0x40
        }

        val telemetry = engine.consume(buildFrame(flag = 0x14, command = 0x04, data = data))?.telemetry

        assertNotNull(telemetry)
        assertEquals("inmotion_v13", telemetry.profileId)
        assertEquals(80.0, telemetry.batteryPercent, 0.01)
        assertEquals(78.0, telemetry.rideBatteryPercent, 0.01)
        assertEquals(70.2, telemetry.remainingRangeKm, 0.01)
        assertEquals(90.0, telemetry.speedTiltBackKmh, 0.01)
        assertEquals(85.0, telemetry.speedWarningKmh, 0.01)
        assertEquals(80.0, telemetry.outputTiltBackPercent, 0.01)
        assertEquals(90.0, telemetry.busCurrentWarningAmps, 0.01)
        assertEquals(300.0, telemetry.phaseCurrentLimitAmps, 0.01)
        assertEquals(35.0, telemetry.batteryTemperature, 0.01)
        assertEquals(36.0, telemetry.batteryMaxCellTemperature, 0.01)
        assertEquals(37.0, telemetry.batteryMaxBmsTemperature, 0.01)
        assertEquals(1.0, telemetry.motorEnabled, 0.01)
    }

    @Test
    fun parsesV12SettingsAndBuildsLightWriteCommands() {
        val engine = InmotionProtocolEngine()
        val mainInfo = buildFrame(0x11, 0x02, byteArrayOf(1, 2, 7, 2)) // V12HT model id 72

        val payload = ByteArray(33)
        payload[23] = 100 // auto light low thr
        payload[24] = 200.toByte() // auto light high thr
        payload[25] = 50 // low beam brightness
        payload[26] = 80 // high beam brightness
        payload[32] = 0x0A // bit1 turn light, bit3 auto light state (both on)
        val settingsFrame = buildFrame(0x14, 0x08, payload)

        engine.consume(mainInfo)
        engine.consume(settingsFrame)

        val capabilities = engine.settingsCapabilities().associateBy { it.key }
        assertEquals(1, capabilities.getValue("inmotion_auto_light_state").rawValue)
        assertEquals(100, capabilities.getValue("inmotion_auto_light_low_thr").rawValue)
        assertEquals(200, capabilities.getValue("inmotion_auto_light_high_thr").rawValue)
        assertEquals(50, capabilities.getValue("inmotion_low_beam_brightness").rawValue)
        assertEquals(80, capabilities.getValue("inmotion_high_beam_brightness").rawValue)

        assertEquals(
            "AAAA1404602A1EC88C",
            engine.buildSettingCommand("inmotion_auto_light_low_thr", 30)?.hex(),
        )
        assertEquals(
            "AAAA1404602A64D2EC",
            engine.buildSettingCommand("inmotion_auto_light_high_thr", 210)?.hex(),
        )
        assertEquals(
            "AAAA1404602B5A5051",
            engine.buildSettingCommand("inmotion_low_beam_brightness", 90)?.hex(),
        )
        assertEquals(
            "AAAA1403602F0058",
            engine.buildSettingCommand("inmotion_auto_light_state", 0)?.hex(),
        )
        assertNull(engine.buildSettingCommand("inmotion_low_beam_brightness", 999))
        assertNull(engine.buildSettingCommand("unknown_key", 1))
    }

    @Test
    fun parsesV14AndP6SettingsAndBuildsLightWriteCommands() {
        val payload = ByteArray(29)
        payload[23] = 30 // auto light low thr
        payload[24] = 180.toByte() // auto light high thr
        payload[25] = 60 // light brightness
        payload[28] = 0x40 // packed byte: soundState=0, drlState=0, liftedState=0, autoLightState(bits6-7)=1

        val v14Engine = InmotionProtocolEngine()
        v14Engine.consume(buildFrame(0x11, 0x02, byteArrayOf(1, 2, 9, 2))) // V14S model id 92
        v14Engine.consume(buildFrame(0x14, 0x08, payload))

        val p6Engine = InmotionProtocolEngine()
        p6Engine.consume(buildFrame(0x11, 0x02, byteArrayOf(1, 2, 13, 1))) // P6 model id 131
        p6Engine.consume(buildFrame(0x14, 0x08, payload))

        for (engine in listOf(v14Engine, p6Engine)) {
            val capabilities = engine.settingsCapabilities().associateBy { it.key }
            assertEquals(30, capabilities.getValue("inmotion_auto_light_low_thr").rawValue)
            assertEquals(180, capabilities.getValue("inmotion_auto_light_high_thr").rawValue)
            assertEquals(60, capabilities.getValue("inmotion_light_brightness").rawValue)
            assertEquals(1, capabilities.getValue("inmotion_auto_light_state").rawValue)
            // V14/P6 has no separate low/high beam pair, unlike V12.
            assertNull(capabilities["inmotion_low_beam_brightness"])
            assertNull(capabilities["inmotion_high_beam_brightness"])

            assertEquals(
                "AAAA1403602F0058",
                engine.buildSettingCommand("inmotion_auto_light_state", 0)?.hex(),
            )

            assertEquals(
                "AAAA1404602A3CB4D2",
                engine.buildSettingCommand("inmotion_auto_light_low_thr", 60)?.hex(),
            )
            assertEquals(
                "AAAA1403602B96CA",
                engine.buildSettingCommand("inmotion_light_brightness", 150)?.hex(),
            )
        }
    }

    @Test
    fun parsesP6SettingsAllBlockAndBuildsWritesOnlyForWritableFields() {
        val payload = ByteArray(49)
        payload.putInt16Le(8, 4500) // limit_speed
        payload[24] = 0x21 // ride_mode = high nibble = 2, driver_mode = 1
        payload[25] = 40 // sensation_1
        payload[26] = 55 // sensation_2
        payload[27] = 70 // voice_volume
        payload[28] = 3 // rgb_light_mode
        payload[29] = 30 // low_threshold
        payload[30] = 180.toByte() // high_threshold
        payload[31] = 60 // low_lamp_brightness
        payload[32] = 90 // high_lamp_brightness
        // flag word @45: lamp_auto_switch (bit3), berm_angle_mode (bit17), drl (bit21)
        val flags = (1 shl 3) or (1 shl 17) or (1 shl 21)
        payload[45] = (flags and 0xFF).toByte()
        payload[46] = ((flags ushr 8) and 0xFF).toByte()
        payload[47] = ((flags ushr 16) and 0xFF).toByte()
        payload[48] = ((flags ushr 24) and 0xFF).toByte()

        val engine = InmotionProtocolEngine()
        engine.consume(buildFrame(0x11, 0x02, byteArrayOf(1, 2, 13, 1))) // P6 model id 131
        engine.consume(buildFrame(0x14, 0x20, payload))

        val capabilities = engine.settingsCapabilities().associateBy { it.key }
        assertEquals(2, capabilities.getValue("inmotion_ride_mode").rawValue)
        assertEquals(1, capabilities.getValue("inmotion_driver_mode").rawValue)
        assertEquals(40, capabilities.getValue("inmotion_pedal_sensitivity_1").rawValue)
        assertEquals(70, capabilities.getValue("inmotion_voice_volume").rawValue)
        assertEquals(30, capabilities.getValue("inmotion_auto_light_low_thr").rawValue)
        assertEquals(180, capabilities.getValue("inmotion_auto_light_high_thr").rawValue)
        assertEquals(60, capabilities.getValue("inmotion_low_beam_brightness").rawValue)
        assertEquals(90, capabilities.getValue("inmotion_high_beam_brightness").rawValue)
        assertEquals(45, capabilities.getValue("inmotion_speed_limit").rawValue)
        assertEquals(1, capabilities.getValue("inmotion_auto_light_state").rawValue)
        assertEquals(1, capabilities.getValue("inmotion_drl_state").rawValue)
        assertEquals(1, capabilities.getValue("inmotion_berm_angle_mode").rawValue)
        assertEquals(0, capabilities.getValue("inmotion_lock_mode").rawValue)

        // P6 carries a low/high lamp pair like V12, so 0x2B keeps the paired payload.
        assertEquals(
            "AAAA1404602B325A33",
            engine.buildSettingCommand("inmotion_low_beam_brightness", 50)?.hex(),
        )
        assertEquals(
            "AAAA140360240251",
            engine.buildSettingCommand("inmotion_ride_mode", 2)?.hex(),
        )
        assertEquals(
            "AAAA140360263263",
            engine.buildSettingCommand("inmotion_voice_volume", 50)?.hex(),
        )
        assertEquals(
            "AAAA1403604E0138",
            engine.buildSettingCommand("inmotion_drl_state", 1)?.hex(),
        )

        // Speeds and the pedal trim are exposed in km/h and tenths of a degree, and go on
        // the wire as little-endian hundredths (`Utils.intTo2Bytes`). Warning levels are a
        // pair in one command, like the auto-light thresholds.
        assertEquals(
            "AAAA140460219411D4",
            engine.buildSettingCommand("inmotion_speed_limit", 45)?.hex(),
        )
        assertEquals(
            "AAAA1406603ED80E00009A",
            engine.buildSettingCommand("inmotion_speed_warning_level_1", 38)?.hex(),
        )
        assertEquals(
            "AAAA1404602206FFAB",
            engine.buildSettingCommand("inmotion_pitch_zero", -25)?.hex(),
        )
        assertNull(engine.buildSettingCommand("inmotion_pitch_zero", -81))
        // A stock P6 reports the limit off as 150.00 km/h, so the slider must reach it.
        assertEquals(
            "AAAA14046021983AF3",
            engine.buildSettingCommand("inmotion_speed_limit", 150)?.hex(),
        )
        assertNull(engine.buildSettingCommand("inmotion_speed_limit", 151))

        // Read-only fields never produce a write, even for an in-range value.
        assertNull(engine.buildSettingCommand("inmotion_low_battery_ride", 1))
        assertNull(engine.buildSettingCommand("inmotion_lock_mode", 1))
        assertNull(engine.buildSettingCommand("inmotion_transport_mode", 1))
        assertNull(engine.buildSettingCommand("inmotion_lift_up_detection", 1))
        // Ride mode is a three-way field; out-of-range values are still rejected.
        assertNull(engine.buildSettingCommand("inmotion_ride_mode", 3))
    }

    /**
     * The first settings block any wheel has ever answered:
     * `loeuc_F78526C39A8E_unknown_20260813_124803`'s successor at 16:14 on the same P6,
     * the first session where the read went out as `AA AA 14 02 20 20 16`.
     *
     * The response repeats both command bytes (`A0 20`), so the block starts at frame
     * index 6 - the one thing that could not be checked before a real answer existed. The
     * values below match what the wheel's own screens show for this unit.
     */
    @Test
    fun parsesRealP6SettingsBlockFromCapture() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA11088201020D0101010094".bytes())
        engine.consume(
            """
            AAAA1434A0200000000001000000983A983AB80B401F401F34216400403800282814030A28
            646428D0073232384A01325F1E1E06313C0E2EE4
            """.bytes(),
        )

        val settings = engine.settingsCapabilities().associateBy { it.key }
        assertEquals(150, settings.getValue("inmotion_speed_limit").rawValue)
        // Raw 100 hundredths of a degree, exposed in tenths like LeaperKim's angle trim.
        assertEquals(10, settings.getValue("inmotion_pitch_zero").rawValue)
        assertEquals(0, settings.getValue("inmotion_driver_mode").rawValue)
        assertEquals(0, settings.getValue("inmotion_ride_mode").rawValue)
        assertEquals(40, settings.getValue("inmotion_pedal_sensitivity_1").rawValue)
        assertEquals(40, settings.getValue("inmotion_pedal_sensitivity_2").rawValue)
        assertEquals(20, settings.getValue("inmotion_voice_volume").rawValue)
        assertEquals(3, settings.getValue("inmotion_light_effect_mode").rawValue)
        assertEquals(10, settings.getValue("inmotion_auto_light_low_thr").rawValue)
        assertEquals(40, settings.getValue("inmotion_auto_light_high_thr").rawValue)
        assertEquals(100, settings.getValue("inmotion_low_beam_brightness").rawValue)
        assertEquals(100, settings.getValue("inmotion_high_beam_brightness").rawValue)
        assertEquals(50, settings.getValue("inmotion_berm_angle").rawValue)
        assertEquals(95, settings.getValue("inmotion_charge_cut_off_percent").rawValue)
        // Units cross-checked field by field against the official app's own screens for
        // this wheel: 240 min standby, 80/80/85 % output thresholds, 1.90 bar tyre alarm,
        // 3.0 A charge current, 1 % logo brightness, 20 km/h high-beam switch speed.
        assertEquals(240, settings.getValue("inmotion_standby_time").rawValue)
        assertEquals(80, settings.getValue("inmotion_output_tiltback_threshold").rawValue)
        assertEquals(80, settings.getValue("inmotion_output_warning_threshold_1").rawValue)
        assertEquals(85, settings.getValue("inmotion_output_warning_threshold_2").rawValue)
        assertEquals(1_900, settings.getValue("inmotion_tpms_low_alarm_threshold").rawValue)
        assertEquals(30, settings.getValue("inmotion_max_charge_current_ac220").rawValue)
        assertEquals(30, settings.getValue("inmotion_max_charge_current_ac110").rawValue)
        assertEquals(1, settings.getValue("inmotion_logo_light_brightness").rawValue)
        assertEquals(20, settings.getValue("inmotion_high_beam_auto_switch_speed").rawValue)
        // Flag bits, each matched to a toggle the app or the wheel's own screen shows.
        assertEquals(0, settings.getValue("inmotion_audio_switch").rawValue) // Beep off
        assertEquals(0, settings.getValue("inmotion_transport_mode").rawValue)
        assertEquals(1, settings.getValue("inmotion_auto_close_screen").rawValue)
        assertEquals(0, settings.getValue("inmotion_shield_tps_error").rawValue)
        assertEquals(1, settings.getValue("inmotion_auto_lock").rawValue)
        assertEquals(1, settings.getValue("inmotion_tail_light_mode").rawValue) // Highlight
        assertEquals(4, settings.getValue("inmotion_turn_light_mode").rawValue) // Sync with tail
        // Flag word @45: auto headlight off, daytime running lights and lift-up on.
        assertEquals(0, settings.getValue("inmotion_auto_light_state").rawValue)
        assertEquals(1, settings.getValue("inmotion_drl_state").rawValue)
        assertEquals(1, settings.getValue("inmotion_lift_up_detection").rawValue)
        assertEquals(0, settings.getValue("inmotion_lock_mode").rawValue)
    }

    /**
     * The second batch of P6 writes, on top of the real block. Ids are the unique literals
     * in the private-body setter table and the payloads are the one-byte shape that table
     * shows - see the `SUB_*` block in the engine for what was deliberately left read-only.
     */
    @Test
    fun buildsSecondBatchOfP6SettingWrites() {
        val engine = InmotionProtocolEngine()
        engine.consume("AAAA11088201020D0101010094".bytes())
        engine.consume(
            """
            AAAA1434A0200000000001000000983A983AB80B401F401F34216400403800282814030A28
            646428D0073232384A01325F1E1E06313C0E2EE4
            """.bytes(),
        )

        assertEquals("AAAA1403602C015A", engine.buildSettingCommand("inmotion_audio_switch", 1)?.hex())
        assertEquals("AAAA1403603D004A", engine.buildSettingCommand("inmotion_auto_close_screen", 0)?.hex())
        assertEquals("AAAA140360443C0F", engine.buildSettingCommand("inmotion_logo_light_brightness", 60)?.hex())
        assertEquals("AAAA1403603A2D60", engine.buildSettingCommand("inmotion_berm_angle", 45)?.hex())
        assertEquals("AAAA1403603B004C", engine.buildSettingCommand("inmotion_tail_light_mode", 0)?.hex())
        assertEquals("AAAA140360300245", engine.buildSettingCommand("inmotion_turn_light_mode", 2)?.hex())
        // Millibar on the slider, hundred-thousandths of a bar on the wire.
        assertEquals("AAAA1404604D384A4F", engine.buildSettingCommand("inmotion_tpms_low_alarm_threshold", 1_900)?.hex())

        // Out of range stays unsent.
        assertNull(engine.buildSettingCommand("inmotion_turn_light_mode", 5))
        assertNull(engine.buildSettingCommand("inmotion_berm_angle", 91))
        // Fields whose command id is ambiguous in the setter table, and the ones that can
        // cut motor power, are still display-only.
        assertNull(engine.buildSettingCommand("inmotion_charge_cut_off_percent", 90))
        assertNull(engine.buildSettingCommand("inmotion_standby_time", 30))
        assertNull(engine.buildSettingCommand("inmotion_max_charge_current_ac220", 20))
        assertNull(engine.buildSettingCommand("inmotion_low_battery_ride", 1))
        assertNull(engine.buildSettingCommand("inmotion_lock_mode", 1))
        assertNull(engine.buildSettingCommand("inmotion_transport_mode", 1))
        assertNull(engine.buildSettingCommand("inmotion_lift_up_detection", 1))
        assertNull(engine.buildSettingCommand("inmotion_speeding_feedback", 60))
    }

    @Test
    fun settingsAllResponseIsIgnoredForNonP6Models() {
        val engine = InmotionProtocolEngine()
        engine.consume(buildFrame(0x11, 0x02, byteArrayOf(1, 2, 9, 2))) // V14S model id 92
        engine.consume(buildFrame(0x14, 0x20, ByteArray(49)))

        assertEquals(emptyList(), engine.settingsCapabilities())
    }

    /**
     * A stored frame is already unescaped, so it must not be unescaped a second time.
     *
     * Both real P6 frames below are from `loeuc_F78526C39A8E_unknown_20260815_051914.jsonl`.
     * The wheel's ride-battery reading sat at 93.81 % for the whole capture, and 9381 is
     * `0x24A5` - so the low byte was the escape marker `0xA5` in every one of the 144 realtime
     * frames. Sent through [InmotionProtocolEngine.consume], which unescapes, that byte ate the
     * one after it: the frame came out 91 bytes against the 92 its length field declares, never
     * completed, and its bytes ran into the next frame. Speed, PWM and the rest of the dashboard
     * went dead - not for a protocol reason, but because a slow-moving field happened to park on
     * one value.
     */
    @Test
    fun consumeFrameDecodesAStoredFrameWhosePayloadCarriesTheEscapeByte() {
        val engine = InmotionProtocolEngine()
        engine.consume("AA AA 11 08 82 01 02 0D 01 01 01 00 94".bytes()) // P6, model id 131
        val stored = (
            "AA AA 14 57 84 77 59 EC FF 00 00 00 00 00 00 00 00 60 01 B4 01 00 00 00 00 67 00 " +
                "B3 FF 64 00 00 00 00 00 D5 00 2D 01 B8 24 A5 24 98 3A 98 3A 98 3A 40 1F 40 1F " +
                "E0 2E E0 2E 50 C3 00 00 00 00 CD CE 00 CF B0 CC CD D0 B0 52 00 04 00 00 00 00 " +
                "49 00 00 00 00 00 00 00 00 00 00 00 B6"
            ).bytes()

        val telemetry = engine.consumeFrame(stored)?.telemetry

        assertNotNull(telemetry)
        assertEquals("inmotion_p6", telemetry.profileId)
        assertEquals(0.0, telemetry.speedKmh, 0.0001)
        assertEquals(4.36, telemetry.pwmPercent, 0.0001)
        assertEquals(229.03, telemetry.voltage, 0.0001)
        // The field that carried the escape byte, and the two that follow it.
        assertEquals(93.81, telemetry.rideBatteryPercent, 0.0001)
        assertEquals(150.0, telemetry.speedTiltBackKmh, 0.0001)
        assertEquals(150.0, telemetry.speedWarningKmh, 0.0001)
        assertEquals(2.13, telemetry.tirePressureBar, 0.0001)
    }

    /** The same frame as it arrives on the wire still decodes to the same values. */
    @Test
    fun consumeDecodesTheWireFormOfTheSameEscapedFrame() {
        val engine = InmotionProtocolEngine()
        engine.consume("AA AA 11 08 82 01 02 0D 01 01 01 00 94".bytes())
        val wire = (
            "AA AA 14 57 84 77 59 EC FF 00 00 00 00 00 00 00 00 60 01 B4 01 00 00 00 00 67 00 " +
                "B3 FF 64 00 00 00 00 00 D5 00 2D 01 B8 24 A5 A5 24 98 3A 98 3A 98 3A 40 1F 40 " +
                "1F E0 2E E0 2E 50 C3 00 00 00 00 CD CE 00 CF B0 CC CD D0 B0 52 00 04 00 00 00 " +
                "00 49 00 00 00 00 00 00 00 00 00 00 00 B6"
            ).bytes()

        val telemetry = engine.consume(wire)?.telemetry

        assertNotNull(telemetry)
        assertEquals(4.36, telemetry.pwmPercent, 0.0001)
        assertEquals(93.81, telemetry.rideBatteryPercent, 0.0001)
        assertEquals(2.13, telemetry.tirePressureBar, 0.0001)
    }

    /**
     * The invariant behind the two tests above: a frame off the wire and the same frame handed
     * back by a capture store are the same frame, so [consume] and [consumeFrame] must agree on
     * it - for every payload byte, not just the ones a fixture happens to contain.
     *
     * Swept rather than sampled on purpose. The escape marker is written into each payload
     * offset in turn, for both bytes a wheel escapes, because that is the shape of this defect:
     * `unescaped()` is the identity function on any frame without one of those two bytes, so an
     * extra unescape pass is invisible until a slow-moving field parks on one. All 116 Inmotion
     * V2 fixtures in this suite missed it that way, including a real P6 frame replayed through
     * the whole capture-store path.
     *
     * Both bytes are real: across the eleven Inmotion captures in the working tree every `0xA5`
     * and `0xAA` inside a frame is escaped - 190 and 44 of them - and neither ever appears raw.
     */
    @Test
    fun theWireFormAndTheStoredFormOfAFrameAlwaysDecodeIdentically() {
        val modelFrame = "AA AA 11 08 82 01 02 0D 01 01 01 00 94".bytes() // P6, model id 131
        val base = (
            "AA AA 14 57 84 77 59 EC FF 00 00 00 00 00 00 00 00 60 01 B4 01 00 00 00 00 67 00 " +
                "B3 FF 64 00 00 00 00 00 D5 00 2D 01 B8 24 A5 24 98 3A 98 3A 98 3A 40 1F 40 1F " +
                "E0 2E E0 2E 50 C3 00 00 00 00 CD CE 00 CF B0 CC CD D0 B0 52 00 04 00 00 00 00 " +
                "49 00 00 00 00 00 00 00 00 00 00 00 B6"
            ).bytes()
        val dataRange = 5 until base.lastIndex
        var checked = 0

        for (offset in dataRange) {
            for (marker in listOf(0xA5, 0xAA)) {
                val stored = base.copyOf()
                stored[offset] = marker.toByte()
                stored[stored.lastIndex] = stored.v2Checksum()
                if (stored[stored.lastIndex] == 0xA5.toByte()) {
                    // A checksum that lands on the escape marker is ambiguous on the wire, and
                    // which way a wheel resolves it is not established. Not this test's subject.
                    continue
                }

                val fromWire = InmotionProtocolEngine()
                    .also { it.consume(modelFrame) }
                    .consume(stored.v2Escaped())
                val fromStore = InmotionProtocolEngine()
                    .also { it.consume(modelFrame) }
                    .consumeFrame(stored)

                assertNotNull(fromWire, "wire form dropped the frame at offset $offset")
                assertNotNull(fromStore, "stored form dropped the frame at offset $offset")
                assertEquals(fromWire.responseCommands, fromStore.responseCommands)
                assertEquals(
                    fromWire.telemetry,
                    fromStore.telemetry,
                    "wire and stored forms disagree with $marker at offset $offset",
                )
                assertNotNull(fromWire.telemetry, "no telemetry at offset $offset")
                checked += 1
            }
        }

        // Guards the sweep itself: a base frame that stopped decoding would otherwise let this
        // test pass having compared nothing.
        assertTrue(checked > 160, "expected the sweep to cover the payload, covered $checked")
    }
}

/** XOR over everything between the header and the checksum, the way a wheel computes it. */
private fun ByteArray.v2Checksum(): Byte =
    (2 until lastIndex).fold(0) { value, index -> value xor (this[index].toInt() and 0xFF) }.toByte()

/**
 * Puts a frame back on the wire: `AA AA` stays as it is, and every `0xA5` or `0xAA` behind it
 * gets the escape marker in front. The inverse of the engine's own `unescaped()`.
 */
private fun ByteArray.v2Escaped(): ByteArray {
    val escaped = drop(2).flatMap { byte ->
        if (byte == 0xAA.toByte() || byte == 0xA5.toByte()) {
            listOf(0xA5.toByte(), byte)
        } else {
            listOf(byte)
        }
    }
    return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + escaped.toByteArray()
}

private fun String.bytes(): ByteArray =
    filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun ByteArray.hex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
}

/** Inverse of the engine's `u8 + 80 - 256` temperature scale. */
private fun celsius(value: Int): Byte = (value - 80 + 256).toByte()

private fun ByteArray.putInt16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

/**
 * Builds a response the way a wheel does, command field included: ids from `0x20` up take
 * two bytes, with the read bit on the first one.
 */
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
