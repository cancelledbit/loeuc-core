package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaperKimProtocolEngineTest {
    @Test
    fun reassemblesNotificationsAndKeepsPageState() {
        val engine = LeaperKimProtocolEngine()
        val page2 = frame(page = 2).apply {
            putUInt16Be(4, 14_660)
            putInt16Be(6, -1_307)
            putInt16Be(16, -123)
            putInt16Be(18, 2_940)
            putUInt16Be(20, 57)
            putInt16Be(32, -4_200)
            putUInt16Be(34, 4_567)
            this[50] = 87
        }.withUpdatedCrc()

        assertNull(engine.consume(page2.copyOfRange(0, 20)))
        val telemetry = engine.consume(page2.copyOfRange(20, page2.size))

        assertNotNull(telemetry)
        assertEquals(130.7, telemetry.speedKmh, 0.01)
        assertEquals(146.6, telemetry.voltage, 0.01)
        assertEquals(12.3, telemetry.phaseCurrent, 0.01)
        assertEquals(45.67, telemetry.pwmPercent, 0.01)
        assertEquals(29.4, telemetry.temperature, 0.01)
        assertEquals(-4_200.0, telemetry.carPose, 0.01)
        assertEquals(87.0, telemetry.batteryPercent, 0.01)
        assertEquals(57.0, telemetry.shutdownTimeSeconds, 0.01)
    }

    @Test
    fun page8DiscoversCapabilitiesAndBuildsValidatedCommand() {
        val engine = LeaperKimProtocolEngine()
        val page8 = frame(page = 8).apply {
            (47 until size).forEach { index -> this[index] = 0x80.toByte() }
            this[50] = 72
            this[53] = 85.toByte()
        }.withUpdatedCrc()

        engine.consume(page8)

        // Two, not three: angle_trim used to make up the third because "offset 0" - the 0xDC
        // frame marker - read as 220 and passed the 0x80 sentinel check. It is derived from the
        // pedal trim now, and no frame here has supplied one.
        assertEquals(2, engine.settingsCapabilities().size)
        assertEquals(72, engine.settingsCapabilities().first().rawValue)
        assertNull(engine.buildSettingCommand("stop_speed", 80))
        assertEquals(
            "4C6441701201028080808080805ACC4D87F6",
            engine.buildSettingCommand("stop_power", 90)?.hex(),
        )
    }

    /**
     * angle_trim has no byte of its own on page 8: its "offset 0" is the 0xDC frame marker, which
     * reads as 220 and is not the 0x80 "not reported" sentinel, so it used to publish 22.0 degrees
     * of pedal trim on a wheel sitting level. It is a derived capability backed by the separate
     * page-0/4 pedal-trim field, so it stays unsupported until a frame supplies one.
     *
     * Fixtures are verbatim capture frames: the Lynx-S page 8 and page 0 from
     * loeuc_882583F62376_unknown_20260812_163536.jsonl (that wheel reports a pedal trim of exactly
     * 0.00 degrees throughout), and a Patton page 4 from
     * loeuc_882583F5CF2B_unknown_20260812_234505.jsonl, whose trim is -0.32 degrees.
     */
    @Test
    fun angleTrimIsUnsupportedUntilAPedalTrimArrives() {
        val engine = LeaperKimProtocolEngine()
        engine.consume(LYNX_S_PAGE_8.decodeHex())

        val beforeAngle = assertNotNull(
            engine.allSettingsCapabilities().firstOrNull { it.key == "angle_trim" },
        )
        assertFalse(beforeAngle.isSupported)
        assertNotEquals(220, beforeAngle.rawValue, "the 0xDC frame marker must never leak out")
        assertTrue(engine.settingsCapabilities().none { it.key == "angle_trim" })
        assertNull(engine.buildSettingCommand("angle_trim", -25))

        engine.consume(LYNX_S_PAGE_0.decodeHex())

        val afterAngle = assertNotNull(
            engine.allSettingsCapabilities().firstOrNull { it.key == "angle_trim" },
        )
        assertTrue(afterAngle.isSupported)
        assertEquals(0, afterAngle.rawValue)
        assertEquals(16, afterAngle.commandId)
    }

    @Test
    fun pageZeroPublishesRollAndKeepsPedalTrimAsASetting() {
        val engine = LeaperKimProtocolEngine()
        engine.consume(
            frame(page = 8).apply {
                (47 until size).forEach { this[it] = 0x80.toByte() }
            }.withUpdatedCrc(),
        )

        val telemetry = assertNotNull(
            engine.consume(
                frame(page = 0).apply {
                    putInt16Be(57, -2_372)
                    putInt16Be(67, -180)
                }.withUpdatedCrc(),
            ),
        )

        assertEquals(-23.72, telemetry.lateralAngle, 0.001)
        val trim = assertNotNull(
            engine.allSettingsCapabilities().singleOrNull { it.key == "angle_trim" },
        )
        assertTrue(trim.isSupported)
        assertEquals(-18, trim.rawValue)
    }

    @Test
    fun resetClearsBothRollAndPedalTrim() {
        val engine = LeaperKimProtocolEngine()
        val page8 = frame(page = 8).apply {
            (47 until size).forEach { this[it] = 0x80.toByte() }
        }.withUpdatedCrc()
        engine.consume(page8)
        engine.consume(
            frame(page = 0).apply {
                putInt16Be(57, 1_807)
                putInt16Be(67, -180)
            }.withUpdatedCrc(),
        )

        engine.reset()
        engine.consume(page8)
        val telemetry = assertNotNull(engine.consume(frame(page = 0, size = 57).withUpdatedCrc()))

        assertTrue(telemetry.lateralAngle.isNaN())
        assertFalse(engine.allSettingsCapabilities().single { it.key == "angle_trim" }.isSupported)
    }

    @Test
    fun angleTrimDerivesItsValueFromTheReportedPedalTrim() {
        val engine = LeaperKimProtocolEngine()
        engine.consume(LYNX_S_PAGE_8.decodeHex())
        val telemetry = assertNotNull(engine.consume(PATTON_PAGE_4_TILTED.decodeHex()))
        assertEquals(-7.35, telemetry.lateralAngle, 0.001)

        val angleTrim = assertNotNull(
            engine.allSettingsCapabilities().firstOrNull { it.key == "angle_trim" },
        )
        assertTrue(angleTrim.isSupported)
        assertEquals(-3, angleTrim.rawValue)

        // The write still builds - it sets a new trim, it does not depend on the reported one -
        // and still takes the paired LkAp + LdAp form the commandId == 16 branch produces.
        val command = assertNotNull(engine.buildSettingCommand("angle_trim", -25))
        assertEquals(32, command.size)
        assertEquals("4C6B417010018080808080E7", command.copyOfRange(0, 12).hex())
        assertEquals("4C64417010010080808080E7", command.copyOfRange(16, 28).hex())
    }

    @Test
    fun buildsValidatedFixedShutdownTimerCommand() {
        val command = LeaperKimProtocolEngine().buildFixedShutdownTimerCommand()

        assertEquals(
            "4C6441701680008080808080808080800180",
            command.dropLast(4).toByteArray().hex(),
        )
        assertEquals(22, command.size)
    }

    @Test
    fun buildsCombinedLightCommands() {
        val engine = LeaperKimProtocolEngine()

        val onCommand = engine.buildLightCommand(enabled = true)
        val offCommand = engine.buildLightCommand(enabled = false)

        assertEquals("4C6B41700D01808001", onCommand.take(9).toByteArray().hex())
        assertEquals("4C6441700D01008001", onCommand.drop(13).take(9).toByteArray().hex())
        assertEquals("4C6B41700D01808000", offCommand.take(9).toByteArray().hex())
        assertEquals("4C6441700D01008000", offCommand.drop(13).take(9).toByteArray().hex())
        assertEquals(26, onCommand.size)
        assertEquals(26, offCommand.size)
    }

    @Test
    fun accumulatesLeaperKimBmsCellsByBattery() {
        val engine = LeaperKimProtocolEngine()
        val page1 = frame(page = 1, size = 100).apply {
            this[28] = 35
            this[29] = 40
            this[30] = 0
            this[52] = 36
            repeat(15) { index ->
                putUInt16Be(53 + index * 2, 3_700 + index)
            }
        }.withUpdatedCrc()

        engine.consume(page1)

        val bms = assertNotNull(engine.bmsSnapshot())
        assertEquals("leaperkim_0090", bms.profileId)
        assertEquals("Lynx-S", bms.modelName)
        assertEquals(2, bms.batteries.size)
        with(bms.batteries.first()) {
            assertEquals(36, expectedCellCount)
            assertEquals(2, parallelStrings)
            assertEquals(15, cells.size)
            assertEquals(3.7, cells.first().voltage, 0.001)
            assertEquals(3.714, cells.last().voltage, 0.001)
        }
    }

    @Test
    fun rejectsFrameWhenCrcDoesNotMatchPayload() {
        val frame = frame(page = 0).withUpdatedCrc().apply {
            this[6] = 0x7F
        }

        assertNull(LeaperKimProtocolEngine().consume(frame))
    }

    /**
     * Exactly two temperatures come over the wire: control board and MOSFETs. Bytes `38..39`
     * were read as motor temperature, but the frame builder at `FUN_0801ABE8` fills them from
     * `0x2000058A` - the `AI` field of the diagnostic co-stream, outside the temperature block
     * at `0x20005F0C`.
     */
    @Test
    fun separatesControlBoardAndMosTemperaturesAndReportsNoMotorTemperature() {
        val engine = LeaperKimProtocolEngine()
        val page0 = frame(page = 0).apply {
            putInt16Be(18, 3_456)
            putInt16Be(38, 510)
            putInt16Be(59, 4_088)
        }.withUpdatedCrc()

        val telemetry = assertNotNull(engine.consume(page0))

        assertEquals(34.56, telemetry.temperature, 0.01)
        assertEquals(40.88, telemetry.mosTemperature, 0.01)
        assertTrue(telemetry.motorTemperature.isNaN())
    }

    @Test
    fun calculatesNosfetApexBatteryPercentFromVoltage() {
        val engine = LeaperKimProtocolEngine()
        // Page 1 for hardware code
        val page1 = frame(page = 1).apply {
            this[30] = 7 // versionNumber 501008
            this[28] = 165.toByte()
            this[29] = 16.toByte()
        }.withUpdatedCrc()
        engine.consume(page1)

        // Any page with voltage
        val page0 = frame(page = 0).apply {
            putUInt16Be(4, 13812) // ~138.12 V
        }.withUpdatedCrc()

        val telemetry = engine.consume(page0)
        assertNotNull(telemetry)
        // 138.12V -> 68.66%
        assertEquals(68.666, telemetry.batteryPercent, 0.01)
    }

    /**
     * Patton (hardware `0040`) predates the Smart BMS pages and emits much shorter frames than
     * Lynx-S/Oryx: page 2 is 54 bytes, so the payload ends at offset 49 and 50..53 are the CRC32
     * trailer. Offset 50 is the reported battery percent *on the long-frame models only*; here it
     * is the first CRC byte, which is uniformly random per frame. Accepting it whenever it happened
     * to land in 0..100 made the dashboard percentage jump between unrelated values several times a
     * second on a parked wheel.
     *
     * Fixture: five consecutive real page-2 frames from
     * `loeuc_882583F5CF2B_unknown_20260812_234505.jsonl` (device `LK17344`). The pack sits at a
     * steady 123.7 V throughout, so the percentage must not move.
     */
    @Test
    fun shortPattonFramesDoNotReadBatteryPercentFromCrcTrailer() {
        val engine = LeaperKimProtocolEngine()
        val page2Frames = listOf(
            // CRC[0] = 51 - the old decoder reported "51%"
            "DC5A5C3230540000EE48001BEE48001B00000DDC0D4D000007D003200FB1000219D00000006F0000808080808080022D010033AA33DD",
            // CRC[0] = 13
            "DC5A5C3230540000EE48001BEE48001B00000DDE0D4A000007D003200FB1000219CF0000006F0000808080808080022D01000DD0416A",
            // CRC[0] = 2
            "DC5A5C3230540000EE48001BEE48001B00000DDE0D45000007D003200FB1000219CC0000006F0000808080808080022D010002F0D6F1",
            // CRC[0] = 82
            "DC5A5C3230530000EE48001BEE48001B00000DDC0D43000007D003200FB1000219CC0000006F0000808080808080022D0100525E02AC",
            // CRC[0] = 232 - above 100, so the old decoder fell through to the voltage table here
            "DC5A5C3230540000EE48001BEE48001B00000DDB0D4E000007D003200FB1000219CE0000006F0000808080808080022D0100E860A36D",
        )

        val percents = page2Frames.map { hex ->
            val telemetry = assertNotNull(engine.consume(hex.decodeHex()))
            assertEquals(123.7, telemetry.voltage, 0.02)
            telemetry.batteryPercent
        }

        // The pack drifts by 0.01 V across these frames, so the percentage may wiggle by a hair -
        // but nothing like the 0..100 sweep the CRC trailer produced.
        val spread = percents.max() - percents.min()
        assertTrue(
            spread < 1.0,
            "battery percent must not track the CRC trailer, but swung $spread across $percents",
        )
    }

    /**
     * Same root cause on page 0: the left/right battery current offsets 69..72 are payload on the
     * 77-byte Lynx-S frame but the CRC32 trailer on Patton's 73-byte one. The guard tested total
     * frame size instead of payload size, so a parked Patton reported hundreds of amps.
     */
    @Test
    fun shortPattonFramesDoNotReadBatteryCurrentFromCrcTrailer() {
        val engine = LeaperKimProtocolEngine()
        val page0 =
            "DC5A5C4530540000EE48001BEE48001B00000DE00D50000007D003200FB1000219CB0000006F000080808080808000000336" +
                "FFFFFFFFFF32EEFD1F09AB12C803D00000FFE0D10DD7DD"

        val telemetry = assertNotNull(engine.consume(page0.decodeHex()))

        assertTrue(
            telemetry.leftBatteryCurrent.isNaN(),
            "left battery current must be unreported, was ${telemetry.leftBatteryCurrent}",
        )
        assertTrue(
            telemetry.rightBatteryCurrent.isNaN(),
            "right battery current must be unreported, was ${telemetry.rightBatteryCurrent}",
        )
        assertTrue(
            telemetry.batteryCurrent.isNaN(),
            "combined battery current must be unreported, was ${telemetry.batteryCurrent}",
        )
        // Offsets 57..60 and 67..68 are genuine payload even on the short frame.
        assertEquals(24.75, telemetry.mosTemperature, 0.01)
        assertEquals(-7.37, telemetry.lateralAngle, 0.01)
    }

    /**
     * And on page 1, where the reported cell count at offset 52 is CRC[1] on Patton's 55-byte frame.
     */
    @Test
    fun shortPattonFramesDoNotReadCellCountFromCrcTrailer() {
        val engine = LeaperKimProtocolEngine()
        // CRC[1] = 83, which the old decoder accepted as "83 cells".
        val page1 =
            "DC5A5C3330540000EE48001BEE48001B00000DDE0D50000007D003200FB1000219CC0000006F000080808080808001000000048B53C13A"

        engine.consume(page1.decodeHex())

        // Hardware 0040 has no BMS profile, so the count falls back to the generic voltage guess
        // rather than to whatever CRC[1] happened to be.
        assertEquals(83, page1.decodeHex()[52].toInt() and 0xFF, "fixture must still carry CRC[1]=83")
        val battery = engine.bmsSnapshot().batteries.first()
        assertEquals(30, battery.expectedCellCount)
        assertTrue(battery.cells.isEmpty(), "short frames carry no cell data, got ${battery.cells}")
    }

    /**
     * Patton (`0040`) is a 30S wheel, not one of the 24S Shermans it used to be grouped with. The
     * 24S table tops out at 100.80 V, so every reading from a real Patton - which idles well above
     * that - clamped to a flat 100%.
     */
    @Test
    fun pattonUses30sBatteryCurveRatherThanThe24sShermanTable() {
        val engine = LeaperKimProtocolEngine()
        val page2 =
            "DC5A5C3230540000EE48001BEE48001B00000DDC0D4D000007D003200FB1000219D00000006F0000808080808080022D010033AA33DD"

        val telemetry = assertNotNull(engine.consume(page2.decodeHex()))

        assertEquals(123.72, telemetry.voltage, 0.01)
        // 123.72 V / 30 cells = 4.124 V per cell - near the top of the curve, but not clamped.
        assertTrue(
            telemetry.batteryPercent < 100.0,
            "must not clamp to 100%, was ${telemetry.batteryPercent}",
        )
        assertEquals(98.88, telemetry.batteryPercent, 0.1)
    }

    /**
     * The legacy 36-byte Sherman Max frame has no byte 46, so `page` reads null on every frame
     * and the voltage-curve estimate is the only source of a battery percentage there. It used to
     * be computed from the first frame the engine saw and then frozen: the guard said "estimate
     * only once" when it meant "never overwrite a percentage the BMS reported itself".
     *
     * Fixtures: two verbatim frames from loeuc_882583F4626E_unknown_20260806_143311.jsonl
     * (device LK5690), the same capture as the other legacy fixtures here, at the highest and
     * lowest pack voltage it contains. Hardware code reads 0011, so this is the 24S Sherman
     * curve; 0.01 V is 0.0208% on it.
     */
    @Test
    fun legacyBatteryPercentTracksVoltageInsteadOfFreezingOnTheFirstFrame() {
        val engine = LeaperKimProtocolEngine()
        val higher = "DC5A5C20243000005D61000142AE008B00430EC30E1000000AF0024604530002005E0088"
        val lower = "DC5A5C20242C00005D61000142AE008B00080EB50E1000000AF0024604530002005D00E4"

        val first = assertNotNull(engine.consume(higher.decodeHex()))
        assertEquals(92.64, first.voltage, 0.01)
        assertEquals(35.75, first.batteryPercent, 0.001)

        val second = assertNotNull(engine.consume(lower.decodeHex()))
        assertEquals(92.60, second.voltage, 0.01)
        assertEquals(35.6667, second.batteryPercent, 0.001)
    }

    /**
     * The other half of the same guard: once page 2 has reported a real percentage at offset 50,
     * frames on any other page must not overwrite it with a voltage estimate, however far the
     * voltage has moved. Fixtures are real Lynx-S frames from
     * loeuc_882583F62376_unknown_20260812_163536.jsonl - page 5 at 141.11 V (whose estimate is
     * ~76.7%), then page 2 at 141.11 V reporting 78%, then page 0 at 141.05 V (~76.55%).
     */
    @Test
    fun reportedBatteryPercentSurvivesLaterFramesOnOtherPages() {
        val engine = LeaperKimProtocolEngine()

        val estimated = assertNotNull(engine.consume(LYNX_S_PAGE_5.decodeHex()))
        assertEquals(76.7, estimated.batteryPercent, 0.05)

        val reported = assertNotNull(engine.consume(LYNX_S_PAGE_2.decodeHex()))
        assertEquals(0x4E, LYNX_S_PAGE_2.decodeHex()[50].toInt() and 0xFF, "fixture must report 78%")
        assertEquals(78.0, reported.batteryPercent, 0.001)

        val afterOtherPage = assertNotNull(engine.consume(LYNX_S_PAGE_0.decodeHex()))
        assertEquals(141.05, afterOtherPage.voltage, 0.01)
        assertEquals(78.0, afterOtherPage.batteryPercent, 0.001)
    }

    /**
     * Battery temperature min/max must be the extremes of the *current* reading of each sensor,
     * not a running min/max over everything the engine has ever seen. A monotonic accumulator
     * latches a transient spike, and Android rebuilds the engine and replays a 1000-frame window
     * for every snapshot, so a latched spike would sit on DashboardMetric.BatteryTempMax until it
     * aged out of that window.
     *
     * Fixtures: two verbatim page-3 frames from
     * loeuc_882583F62376_unknown_20260812_163536.jsonl. No capture on hand contains an actual
     * sensor spike - the real ones drift by hundredths of a degree over a session - so the spike
     * is constructed by replaying two real frames recorded at different temperatures in
     * high-low-high order, rather than by hand-authoring a frame. Sensor 6 differs by 0.31 C
     * between them and sensor 4 by 0.02 C, which is enough for each direction to discriminate:
     *   LYNX_S_PAGE_3_WARM  sensors 22.65 22.60 22.68 22.32 22.45 23.60 -> min 22.32, max 23.60
     *   LYNX_S_PAGE_3_COOL  sensors 22.65 22.58 22.65 22.30 22.45 23.29 -> min 22.30, max 23.29
     */
    @Test
    fun batteryTemperatureExtremesFollowTheCurrentReadingInsteadOfLatching() {
        val engine = LeaperKimProtocolEngine()

        val warm = assertNotNull(engine.consume(LYNX_S_PAGE_3_WARM.decodeHex()))
        assertEquals(22.32, warm.batteryTemperatureMin, 0.001)
        assertEquals(23.60, warm.batteryTemperatureMax, 0.001)

        // Max must come back down with the sensor. A latching accumulator reports 23.60 here.
        val cool = assertNotNull(engine.consume(LYNX_S_PAGE_3_COOL.decodeHex()))
        assertEquals(22.30, cool.batteryTemperatureMin, 0.001)
        assertEquals(23.29, cool.batteryTemperatureMax, 0.001)

        // And min must come back up. A latching accumulator reports 22.30 here.
        val warmAgain = assertNotNull(engine.consume(LYNX_S_PAGE_3_WARM.decodeHex()))
        assertEquals(22.32, warmAgain.batteryTemperatureMin, 0.001)
        assertEquals(23.60, warmAgain.batteryTemperatureMax, 0.001)

        // bmsSnapshot reads the same per-sensor maps and must be unaffected by the change.
        val battery = assertNotNull(engine.bmsSnapshot()).batteries[0]
        assertEquals(6, battery.temperatures.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), battery.temperatures.map { it.index })
        assertEquals(22.65, battery.temperatures[0].celsius, 0.001)
        assertEquals(23.60, battery.temperatures[5].celsius, 0.001)
    }

    @Test
    fun recognizesNosfetApexBmsProfile() {
        val engine = LeaperKimProtocolEngine()
        val page1 = frame(page = 1).apply {
            this[30] = 7 // versionNumber 501008
            this[28] = 165.toByte()
            this[29] = 16.toByte()
        }.withUpdatedCrc()
        engine.consume(page1)

        val bms = assertNotNull(engine.bmsSnapshot())
        assertEquals("nosfet_5010", bms.profileId)
        assertEquals("Nosfet Apex", bms.modelName)
        assertEquals(2, bms.batteries.size)
        assertEquals(36, bms.batteries[0].expectedCellCount)
        assertEquals(2, bms.batteries[0].parallelStrings) // 4P total / 2 batteries
    }

    /**
     * Legacy Sherman Max frames are 36 bytes with no CRC32 trailer, declared by length byte 0x20.
     * The engine used to drop them at the CRC gate, so :shared decoded nothing for Sherman and
     * ShermanMax. Fixture: two consecutive frames from
     * loeuc_882583F4626E_unknown_20260806_143311.jsonl (device LK5690).
     */
    @Test
    fun decodesLegacyShermanMaxFramesWithoutCrc32() {
        val engine = LeaperKimProtocolEngine()
        val legacy = "DC5A5C20242F00005D60000142AD008B00320E940E1000000AF0024604530002005D00D1"

        val telemetry = assertNotNull(engine.consume(legacy.decodeHex()))

        assertEquals(92.63, telemetry.voltage, 0.01)
        assertEquals(0.0, telemetry.speedKmh, 0.01)
        assertEquals(5.0, telemetry.phaseCurrent, 0.01)
        assertEquals(37.32, telemetry.temperature, 0.01)
        // Raw counters, in metres - 89.4 km trip on a wheel with 9127 km total.
        assertEquals(89_440.0, telemetry.tripDistanceMeters, 0.5)
        assertEquals(9_126_573.0, telemetry.totalDistanceMeters, 0.5)
        // u16be @34 = 209, and the UI treats this field as /100 percent.
        assertEquals(2.09, telemetry.pwmPercent, 0.01)
        assertEquals(3_600.0, telemetry.shutdownTimeSeconds, 0.01)
        assertEquals(58.2, telemetry.stopSpeedKmh, 0.01)
        assertEquals(2.0, telemetry.rideMode, 0.01)
        // Offsets past the 36-byte frame must read as absent, never as adjacent memory.
        assertTrue(telemetry.motorTemperature.isNaN())
        assertTrue(telemetry.leftBatteryCurrent.isNaN())
        assertTrue(telemetry.batteryTemperatureMin.isNaN())
    }

    /** A legacy frame must not be mistaken for a CRC32 frame, and vice versa. */
    @Test
    fun legacyDetectionDoesNotSwallowShortCrc32Frames() {
        val engine = LeaperKimProtocolEngine()
        // Patton page 3 is the shortest CRC32-format frame observed: 51 bytes, above the 20..46 band.
        val pattonPage3 =
            "DC5A5C2F30540000EE48001BEE48001B00000DDF0D50000007D003200FB1000219CD0000006F000080808080808003" +
                "49AA02CC"

        val telemetry = assertNotNull(engine.consume(pattonPage3.decodeHex()))

        assertEquals(123.72, telemetry.voltage, 0.01)
    }

    /**
     * Fields Android's TelemetrySnapshot needs that the engine did not expose. Fixtures are real
     * Lynx-S frames from loeuc_882583F62376_unknown_20260812_163536.jsonl (page 5 for lock state,
     * page 8 for high-speed mode) and a legacy Sherman Max frame for the absent-field case.
     *
     * Expected values decoded by hand from the fixture bytes (not read back from the engine):
     *  - chargeMode: LYNX_S_PAGE_0 byte 23 = 0x00 -> 0.0
     *  - batteryTempMode: LYNX_S_PAGE_0 bytes 36..37 = 0x80 0xC8 -> uint16be 32968.0
     *  - lockState: LYNX_S_PAGE_5 byte 51 = 0x00 -> 0.0 (neighboring byte 52 is 0x24 = 36, the
     *    reported cell count, so a wrong-offset read would not silently read as the same value)
     *  - firmwareVersion: bytes 28/29/30 = 0x23 0x2C 0x00 across all three fixtures -> version
     *    int (30<<16)|(28<<8)|29 = (0<<16)|(35<<8)|44 = 9004.0
     *  - highSpeedMode: LYNX_S_PAGE_8 byte 61 = 0x01, not the 0x80 "unreported" sentinel -> true
     */
    @Test
    fun exposesChargeModeLockStateAndHighSpeedMode() {
        val engine = LeaperKimProtocolEngine()

        engine.consume(LYNX_S_PAGE_5.decodeHex())
        engine.consume(LYNX_S_PAGE_8.decodeHex())
        val telemetry = assertNotNull(engine.consume(LYNX_S_PAGE_0.decodeHex()))

        assertEquals("0090", telemetry.hardwareCode)
        assertEquals(0.0, telemetry.chargeMode, 0.01)
        assertEquals(32_968.0, telemetry.batteryTempMode, 0.01)
        assertEquals(0.0, telemetry.lockState, 0.01)
        assertEquals(9_004.0, telemetry.firmwareVersion, 0.01)
        assertTrue(telemetry.highSpeedMode)
    }

    /** A 36-byte legacy frame has no byte 36, so batteryTempMode must read as absent. */
    @Test
    fun legacyFramesReportAbsentBatteryTempMode() {
        val engine = LeaperKimProtocolEngine()
        val legacy = "DC5A5C20242F00005D60000142AD008B00320E940E1000000AF0024604530002005D00D1"

        val telemetry = assertNotNull(engine.consume(legacy.decodeHex()))

        assertEquals(0.0, telemetry.chargeMode, 0.01)
        assertTrue(telemetry.batteryTempMode.isNaN())
        assertTrue(telemetry.lockState.isNaN())
        assertEquals("0011", telemetry.hardwareCode)
    }

    /**
     * Same hardware code (0090), firmware version (9004), voltage and expected percentage as
     * the platform-side test this was ported from when the calibration table and the
     * calculation moved into the engine.
     */
    @Test
    fun fieldWeakeningUsesCalibrationForKnown0090Firmware() {
        val engine = LeaperKimProtocolEngine()
        val page8 = frame(page = 8).apply {
            this[61] = 0
        }.withUpdatedCrc()
        val main = frame(page = 0).apply {
            putUInt16Be(offset = 4, value = 14_660)
            this[30] = ((9_004 ushr 16) and 0xFF).toByte()
            this[28] = ((9_004 ushr 8) and 0xFF).toByte()
            this[29] = (9_004 and 0xFF).toByte()
        }.withUpdatedCrc()

        engine.consume(page8)
        val telemetry = assertNotNull(engine.consume(main))

        assertEquals(45.8095, telemetry.fieldWeakeningPercent, 0.0001)
    }

    /**
     * Ported from fieldWeakeningIsNotPublishedForUnknownFirmwareVersion in the same Android test
     * file. The original asserted a null TelemetrySnapshot.fieldWeakeningPercent; the shared
     * engine's LeaperKimTelemetry uses the Double.NaN "not reported" convention instead (see
     * hardwareCode/firmwareVersion in the same class), so "not published" is isNaN() here.
     */
    @Test
    fun fieldWeakeningIsNaNForUnknownFirmwareVersion() {
        val engine = LeaperKimProtocolEngine()
        val main = frame(page = 0).apply {
            putUInt16Be(offset = 4, value = 14_660)
            this[30] = ((9_005 ushr 16) and 0xFF).toByte()
            this[28] = ((9_005 ushr 8) and 0xFF).toByte()
            this[29] = (9_005 and 0xFF).toByte()
        }.withUpdatedCrc()

        val telemetry = assertNotNull(engine.consume(main))

        assertTrue(telemetry.fieldWeakeningPercent.isNaN())
    }

    /**
     * Ported verbatim from fieldWeakeningUsesVersionSpecificLegacyCalibrations in the same
     * Android test file - same hardware codes, firmware versions, and expected percentages.
     * calculateLeaperKimFieldWeakening now lives in this package, so no import is needed.
     */
    @Test
    fun fieldWeakeningUsesVersionSpecificLegacyCalibrations() {
        assertEquals(30.0, calculateLeaperKimFieldWeakening(130.0, false, "0060", 6008)!!, 0.0001)
        assertEquals(60.0, calculateLeaperKimFieldWeakening(130.0, true, "0060", 6008)!!, 0.0001)
        assertEquals(73.0952, calculateLeaperKimFieldWeakening(130.0, false, "0060", 6009)!!, 0.0001)
        assertEquals(80.0, calculateLeaperKimFieldWeakening(130.0, true, "0060", 6009)!!, 0.0001)
        assertEquals(26.0952, calculateLeaperKimFieldWeakening(130.0, false, "0060", 6010)!!, 0.0001)
        assertEquals(70.0, calculateLeaperKimFieldWeakening(130.0, true, "0060", 6010)!!, 0.0001)
        assertEquals(15.0, calculateLeaperKimFieldWeakening(130.0, false, "0070", 7003)!!, 0.0001)
        assertEquals(35.0, calculateLeaperKimFieldWeakening(130.0, true, "0070", 7003)!!, 0.0001)
    }
}

private fun frame(page: Int, size: Int = 84): ByteArray = ByteArray(size).apply {
    this[0] = 0xDC.toByte()
    this[1] = 0x5A
    this[2] = 0x5C
    this[3] = (size - 4).toByte()
    this[46] = page.toByte()
}

private fun ByteArray.putUInt16Be(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.putInt16Be(offset: Int, value: Int) = putUInt16Be(offset, value and 0xFFFF)

private fun ByteArray.withUpdatedCrc(): ByteArray {
    val payload = copyOfRange(0, size - 4)
    var crc = 0xFFFFFFFFL
    payload.forEach { byte ->
        crc = crc xor (byte.toLong() and 0xFF)
        repeat(8) {
            crc = if (crc and 1L != 0L) {
                (crc ushr 1) xor 0xEDB88320L
            } else {
                crc ushr 1
            }
        }
    }
    val value = crc xor 0xFFFFFFFFL
    this[size - 4] = (value ushr 24).toByte()
    this[size - 3] = (value ushr 16).toByte()
    this[size - 2] = (value ushr 8).toByte()
    this[size - 1] = value.toByte()
    return this
}

private fun ByteArray.hex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
}

// Real Lynx-S frames from loeuc_882583F62376_unknown_20260812_163536.jsonl.
private const val LYNX_S_PAGE_0 =
    "DC5A5C4937190000DF6C00007042000300070C8E0379000003C002EE232C00780006007E80C800008080808080800000000BFFFFFFFFFF3211FF430AEF0CDE022A003300000002000501A0CA44"

private const val LYNX_S_PAGE_3_WARM =
    "DC5A5C5F37250000DF6F00007045000300010D78037C000003C002EE232C00780002000E80C800008080808080800308D908D408DC08B808C509380F540F550F540F550F540F4F000000000000000000000000000000000000000000000000A879BCD7"

private const val LYNX_S_PAGE_3_COOL =
    "DC5A5C5F37190000DF6C00007042000300080C8F0378000003C002EE232C00780006007E80C800008080808080800308D908D208D908B608C509190F540F550F540F550F540F50000000000000000000000000000000000000000000000000B29AA3D2"

private const val LYNX_S_PAGE_2 =
    "DC5A5C53371F0000DF6C00007042000300060C8E0378000003C002EE232C00780006007780C80000808080808080022801004E80800F520F530F4B0F550F550F550F550F540F4F0F550F560F560F550F560F52FFCECC8B"

private const val LYNX_S_PAGE_5 =
    "DC5A5C53371F0000DF6C00007042000300070C930378000003C002EE232C00780006007F80C80000808080808080050000000000240F540F550F550F550F560F500F4E0F510F510F510F500F490F4E0F4E0F4FAFD855C7"

// A Patton (hardware 0040) page 4 from loeuc_882583F5CF2B_unknown_20260812_234505.jsonl.
// i16be @57 is roll (-7.35 degrees); i16be @67 is pedal trim (-0.32 degrees).
private const val PATTON_PAGE_4_TILTED =
    "DC5A5C4530540000EE48001BEE48001B00000DDC0D51000007D003200FB1000219CE0000006F00008080808080800" +
        "4000335FFFFFFFFFF32EEFD2109AB12C903D00000FFE0C78E511A"

private const val LYNX_S_PAGE_8 =
    "DC5A5C4737190000DF6C00007042000300080C8A0379000003C002EE232C00780006007D80C800008080808080800800008014004B56603C000000000001280432915A801E8080C9E81C4B"
