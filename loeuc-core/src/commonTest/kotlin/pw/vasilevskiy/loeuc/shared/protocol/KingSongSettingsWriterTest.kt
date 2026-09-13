package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * KingSong settings frames. Every expected frame below is the one WheelLog and the official app
 * both build; the two places they differ are pinned to the side `KingSongSettingsWriter` follows.
 */
@OptIn(ExperimentalWriteApi::class)
class KingSongSettingsWriterTest {

    @Test
    fun speedAlarmsCarryAllFourSpeedsInOneFrame() {
        assertEquals(
            "AA 55 32 00 37 00 38 00 3C 00 00 00 00 00 00 00 85 14 5A 5A",
            KingSongSettingsWriter.speedAlarms(50, 55, 56, 60).hex(),
        )
    }

    /** Byte 17 is the official app's `0x14`, not WheelLog's `0x15`. */
    @Test
    fun pedalModeFollowsTheOfficialAppLengthByte() {
        assertEquals(
            "AA 55 00 E0 00 00 00 00 00 00 00 00 00 00 00 00 87 14 5A 5A",
            KingSongSettingsWriter.pedalMode(KingSongPedalMode.Hard).hex(),
        )
        assertEquals(0x02, KingSongSettingsWriter.pedalMode(KingSongPedalMode.Soft)[2].u8())
    }

    /**
     * Byte 3 is the wheel's voice-prompt switch. Sending WheelLog's constant 1 to a wheel with
     * prompts on would silence it every time the rider touched the light.
     */
    @Test
    fun lightModeCarriesTheVoiceSwitchBack() {
        assertEquals(
            "AA 55 12 01 00 00 00 00 00 00 00 00 00 00 00 00 73 14 5A 5A",
            KingSongSettingsWriter.lightMode(KingSongLightMode.On, voicePromptsOff = true).hex(),
        )
        assertEquals(
            "AA 55 14 00 00 00 00 00 00 00 00 00 00 00 00 00 73 14 5A 5A",
            KingSongSettingsWriter.lightMode(KingSongLightMode.Auto, voicePromptsOff = false).hex(),
        )
        // The existing quick-light builder is WheelLog's frame; the new one reproduces it.
        assertEquals(
            buildKingSongLightCommand(command = 0x13).hex(),
            KingSongSettingsWriter.lightMode(KingSongLightMode.Off, voicePromptsOff = true).hex(),
        )
    }

    @Test
    fun lightSwitchesAndActions() {
        assertEquals("AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 6C 14 5A 5A", KingSongSettingsWriter.ledStrip(true).hex())
        assertEquals("AA 55 01 00 00 00 00 00 00 00 00 00 00 00 00 00 6C 14 5A 5A", KingSongSettingsWriter.ledStrip(false).hex())
        assertEquals("AA 55 01 00 00 00 00 00 00 00 00 00 00 00 00 00 53 14 5A 5A", KingSongSettingsWriter.strobe(true).hex())
        assertEquals("AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 53 14 5A 5A", KingSongSettingsWriter.strobe(false).hex())
        assertEquals("AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 88 14 5A 5A", KingSongSettingsWriter.horn().hex())
        assertEquals("AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 14 5A 5A", KingSongSettingsWriter.powerOff().hex())
    }

    @Test
    fun standbyDelayAndChargeLimitAreLittleEndianWords() {
        assertEquals(
            "AA 55 01 00 10 0E 00 00 00 00 00 00 00 00 00 00 3F 14 5A 5A",
            KingSongSettingsWriter.standbyDelay(seconds = 3_600).hex(),
        )
        assertEquals(
            "AA 55 09 00 50 00 00 00 00 00 00 00 00 00 00 00 8A 14 5A 5A",
            KingSongSettingsWriter.chargeLimit(percent = 80).hex(),
        )
    }

    @Test
    fun readsAskWithoutChanging() {
        assertEquals("AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 98 14 5A 5A", KingSongSettingsReads.speedAlarms().hex())
        assertEquals("AA 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 3F 14 5A 5A", KingSongSettingsReads.standbyDelay().hex())
        assertEquals("AA 55 08 00 00 00 00 00 00 00 00 00 00 00 00 00 8A 14 5A 5A", KingSongSettingsReads.chargeLimit().hex())
    }

    /**
     * Calibration (`0x89`) and the gyroscope and balance angles of the `0x8A` family
     * (sub-commands 0..4) must never come out of this library, whatever a caller passes.
     */
    @Test
    fun noBuilderEverProducesCalibrationOrGyroBytes() {
        val everything = buildList {
            for (speed in listOf(-5, 0, 1, 30, 100, 255, 1_000)) {
                add(KingSongSettingsWriter.speedAlarms(speed, speed, speed, speed))
            }
            KingSongPedalMode.entries.forEach { add(KingSongSettingsWriter.pedalMode(it)) }
            KingSongLightMode.entries.forEach { mode ->
                add(KingSongSettingsWriter.lightMode(mode, voicePromptsOff = true))
                add(KingSongSettingsWriter.lightMode(mode, voicePromptsOff = false))
            }
            listOf(true, false).forEach {
                add(KingSongSettingsWriter.ledStrip(it))
                add(KingSongSettingsWriter.strobe(it))
            }
            for (seconds in listOf(-1, 0, 60, 14_400, 100_000)) add(KingSongSettingsWriter.standbyDelay(seconds))
            for (percent in listOf(-1, 0, 1, 3, 50, 80, 100, 1_000)) add(KingSongSettingsWriter.chargeLimit(percent))
            add(KingSongSettingsWriter.horn())
            add(KingSongSettingsWriter.powerOff())
            addAll(KingSongSettingsReads.all())
            val state = fullState()
            for (key in KingSongSettingsCatalog.keys) {
                for (value in -2..260 step 2) {
                    (KingSongSettingsCatalog.write(key, value, state) as? KingSongSettingWrite.Command)
                        ?.let { add(it.bytes) }
                }
            }
        }

        assertTrue(everything.size > 100, "the sweep has to actually cover the builders")
        everything.forEach { frame ->
            val command = frame[16].u8()
            assertTrue(command != 0x89, "calibration in ${frame.hex()}")
            if (command == 0x8A) {
                assertTrue(frame[2].u8() in setOf(8, 9), "0x8A sub-command ${frame[2].u8()} in ${frame.hex()}")
            }
        }
    }

    @Test
    fun oneAlarmChangeRestatesTheOtherThreeFromTheWheel() {
        val state = fullState()

        val write = KingSongSettingsCatalog.write(KingSongSettingsCatalog.Alarm2SpeedKey, 52, state)

        assertIs<KingSongSettingWrite.Command>(write)
        assertEquals(
            KingSongSettingsWriter.speedAlarms(50, 52, 56, 60).hex(),
            write.bytes.hex(),
        )
    }

    /** Without a `0xB5` answer the frame would restate zeroes: every alarm and tilt-back off. */
    @Test
    fun alarmsAreNotWrittenBeforeTheWheelReportedThem() {
        val write = KingSongSettingsCatalog.write(KingSongSettingsCatalog.MaxSpeedKey, 45, KingSongSettingsState())

        assertIs<KingSongSettingWrite.Rejected>(write)
    }

    @Test
    fun alarmOrderingFollowsTheOfficialApp() {
        val state = fullState()

        assertIs<KingSongSettingWrite.Rejected>(KingSongSettingsCatalog.write(KingSongSettingsCatalog.Alarm1SpeedKey, 56, state))
        assertIs<KingSongSettingWrite.Rejected>(KingSongSettingsCatalog.write(KingSongSettingsCatalog.MaxSpeedKey, 55, state))
        // Tilt-back off lifts the ceiling on the alarms.
        assertIs<KingSongSettingWrite.Command>(KingSongSettingsCatalog.write(KingSongSettingsCatalog.MaxSpeedKey, 0, state))
    }

    @Test
    fun catalogueWritesUseTheWheelsVoiceSwitchAndRiderUnits() {
        val promptsOn = fullState().copy(voicePromptsOff = false)

        val light = KingSongSettingsCatalog.write(KingSongSettingsCatalog.LightModeKey, KingSongLightMode.Off.ordinal, promptsOn)
        val standby = KingSongSettingsCatalog.write(KingSongSettingsCatalog.StandbyDelayKey, 60, promptsOn)

        assertIs<KingSongSettingWrite.Command>(light)
        assertEquals(0, light.bytes[3].u8())
        assertIs<KingSongSettingWrite.Command>(standby)
        assertEquals(KingSongSettingsWriter.standbyDelay(3_600).hex(), standby.bytes.hex())
        assertIs<KingSongSettingWrite.Rejected>(KingSongSettingsCatalog.write(KingSongSettingsCatalog.ChargeLimitKey, 40, promptsOn))
    }

    @Test
    fun capabilitiesShowOnlyWhatTheWheelReported() {
        val unknown = KingSongSettingsCatalog.capabilities(KingSongSettingsState())
        val reported = KingSongSettingsCatalog.capabilities(fullState())

        assertTrue(unknown.isNotEmpty())
        assertTrue(unknown.all { it.rawValue == null })
        // Charge limit is offered only by a wheel that answered its read.
        assertTrue(unknown.none { it.key == KingSongSettingsCatalog.ChargeLimitKey })
        val byKey = reported.associateBy { it.key }
        assertEquals(56, byKey.getValue(KingSongSettingsCatalog.Alarm3SpeedKey).rawValue)
        assertEquals(1, byKey.getValue(KingSongSettingsCatalog.LedStripKey).rawValue)
        assertEquals(60, byKey.getValue(KingSongSettingsCatalog.StandbyDelayKey).rawValue)
        assertEquals(80, byKey.getValue(KingSongSettingsCatalog.ChargeLimitKey).rawValue)
    }

    @Test
    fun readBackTargetsTheBlockThatChanged() {
        assertEquals(
            listOf(0x98),
            KingSongSettingsCatalog.readsFor(KingSongSettingsCatalog.Alarm1SpeedKey).map { it[16].u8() },
        )
        assertEquals(
            listOf(0x98, 0x6D, 0x54, 0x3F, 0x8A),
            KingSongSettingsCatalog.readsFor(null).map { it[16].u8() },
        )
        assertTrue(KingSongSettingsCatalog.readsFor(KingSongSettingsCatalog.PedalModeKey).isEmpty())
    }

    private fun fullState() = KingSongSettingsState(
        alarm1SpeedKmh = 50,
        alarm2SpeedKmh = 55,
        alarm3SpeedKmh = 56,
        maxSpeedKmh = 60,
        pedalMode = 1,
        lightMode = 0,
        voicePromptsOff = true,
        ledStripOn = true,
        strobeOn = false,
        standbyDelaySeconds = 3_600,
        chargeLimitPercent = 80,
    )
}

private fun ByteArray.hex(): String =
    joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }

private fun Byte.u8(): Int = toInt() and 0xFF
