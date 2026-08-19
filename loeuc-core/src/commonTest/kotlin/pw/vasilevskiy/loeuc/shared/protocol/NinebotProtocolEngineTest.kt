package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NinebotProtocolEngineTest {
    // --- framing --------------------------------------------------------------------------

    @Test
    fun readCommandMatchesHandComputedFrame() {
        // 5A A5 | len 01 | src 3E | dst 14 | cmd 01 | param 68 | data 02 | crc 41 FF
        // sum = 01+3E+14+01+68+02 = BE, crc = BE xor FFFF = FF41, little-endian on the wire.
        val command = NinebotFrameCodec.build(
            destination = NinebotAddress.Controller,
            command = NinebotCommand.Read,
            param = NinebotParam.BleVersion,
            data = byteArrayOf(0x02),
        )

        assertEquals("5aa5013e1401680241ff", command.toHex())
    }

    @Test
    fun frameLengthIsDataPlusOverhead() {
        val command = NinebotFrameCodec.build(
            destination = NinebotAddress.Controller,
            command = NinebotCommand.Write,
            param = NinebotParam.PedalSensitivity,
            data = byteArrayOf(0x02, 0x00),
        )

        assertEquals(2 + NinebotFrameCodec.FrameOverhead, command.size)
    }

    @Test
    fun corruptChecksumYieldsNoFrame() {
        val frame = response(NinebotParam.LiveData, ByteArray(32)).copyOf()
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()

        assertNull(NinebotFrameCodec.parse(frame))
    }

    @Test
    fun frameSplitAcrossNotificationsIsReassembledOnce() {
        val engine = NinebotProtocolEngine()
        val frame = response(NinebotParam.LiveData, liveDataPayload())

        val firstHalf = engine.consume(frame.copyOfRange(0, 7))
        val secondHalf = engine.consume(frame.copyOfRange(7, frame.size))

        assertTrue(firstHalf.isEmpty(), "an incomplete frame must not decode")
        assertEquals(1, secondHalf.size)
        assertEquals(NinebotParam.LiveData, secondHalf.single().param)
    }

    @Test
    fun garbageBetweenFramesDoesNotSwallowTheNextOne() {
        val engine = NinebotProtocolEngine()
        val noise = "00ff5a00a5".decodeHex()
        val frame = response(NinebotParam.LiveData, liveDataPayload())

        val updates = engine.consume(noise + frame)

        assertEquals(1, updates.size)
    }

    @Test
    fun identityKeystreamIsANoOpAndRealOneRoundTrips() {
        val body = "01 3e 14 01 68 02 41 ff".filterNot { it == ' ' }.decodeHex()
        val gamma = ByteArray(NinebotKeystream.KeyLength) { index -> (index * 7 + 3).toByte() }
        val keystream = NinebotKeystream(gamma)

        assertTrue(NinebotKeystream.Identity.isIdentity)
        assertEquals(body.toHex(), NinebotKeystream.Identity.apply(body).toHex())
        assertEquals(body.toHex(), keystream.apply(keystream.apply(body)).toHex())
        // The length byte travels in the clear so a reader can find frame boundaries.
        assertEquals(body[0], keystream.apply(body)[0])
    }

    @Test
    fun encryptedFrameDecodesUnderTheSameKeystream() {
        val gamma = ByteArray(NinebotKeystream.KeyLength) { index -> (index + 1).toByte() }
        val keystream = NinebotKeystream(gamma)
        val frame = NinebotFrameCodec.build(
            destination = NinebotAddress.Controller,
            command = NinebotCommand.Read,
            param = NinebotParam.BleVersion,
            data = byteArrayOf(0x02),
            keystream = keystream,
        )

        assertNull(NinebotFrameCodec.parse(frame), "must not decode without the keystream")
        val parsed = assertNotNull(NinebotFrameCodec.parse(frame, keystream))
        assertEquals(NinebotParam.BleVersion, parsed.param)
    }

    // --- parameter block ------------------------------------------------------------------

    @Test
    fun paramBlockAddressesEverySettingByItsOwnId() {
        val block = NinebotParamBlock(baseParamId = NinebotParam.LedMode, payload = ledBlock())

        // offset = (paramId - baseParamId) * 2, checked against the whole block.
        assertEquals(0, block.offsetOf(NinebotParam.LedMode))
        assertEquals(4, block.offsetOf(NinebotParam.LedColor1))
        assertEquals(8, block.offsetOf(NinebotParam.LedColor2))
        assertEquals(12, block.offsetOf(NinebotParam.LedColor3))
        assertEquals(16, block.offsetOf(NinebotParam.LedColor4))
        assertEquals(24, block.offsetOf(NinebotParam.PedalSensitivity))
        assertEquals(26, block.offsetOf(NinebotParam.DriveFlags))
    }

    @Test
    fun paramBlockRefusesIdsPastItsEnd() {
        val block = NinebotParamBlock(baseParamId = NinebotParam.SpeakerVolume, payload = ByteArray(2))

        assertEquals(0, block.offsetOf(NinebotParam.SpeakerVolume))
        assertNull(block.offsetOf(NinebotParam.SpeakerVolume + 1))
        assertNull(block.value(NinebotParam.SpeakerVolume - 1))
    }

    // --- telemetry ------------------------------------------------------------------------

    @Test
    fun telemetryDecodesEveryDocumentedOffset() {
        val engine = NinebotProtocolEngine()

        engine.consume(response(NinebotParam.LiveData, liveDataPayload()))
        val telemetry = engine.latestTelemetry()

        assertClose(45.0, telemetry.speedKmh)
        assertClose(20.0, telemetry.averageSpeedKmh)
        assertClose(58.8, telemetry.voltage)
        assertClose(-3.5, telemetry.current)
        assertClose(58.8 * -3.5, telemetry.power)
        assertClose(38.5, telemetry.temperature)
        assertClose(87.0, telemetry.batteryPercent)
        assertClose(2500.0, telemetry.tripDistanceMeters)
        assertClose(123_456.0, telemetry.totalDistanceMeters)
        assertClose(3600.0, telemetry.operatingTimeSeconds)
        assertEquals(1, telemetry.escStatus)
    }

    @Test
    fun pwmEstimateIsAnchoredOnMeasuredZ10Behaviour() {
        // Full duty: the patched-out top speed at full charge.
        assertClose(100.0, ninebotPwmPercent(speedKmh = 55.0, voltage = 58.8))
        // A stock wheel riding its 45 km/h limit at full charge.
        assertClose(81.8, ninebotPwmPercent(speedKmh = 45.0, voltage = 58.8), tolerance = 0.1)
        // Load shows up as voltage sag, which raises the estimate at the same speed.
        assertTrue(
            ninebotPwmPercent(speedKmh = 45.0, voltage = 52.0) >
                ninebotPwmPercent(speedKmh = 45.0, voltage = 58.8),
        )
        // Half the voltage halves the speed the wheel can reach, so duty doubles.
        assertClose(
            2 * ninebotPwmPercent(speedKmh = 20.0, voltage = 58.8),
            ninebotPwmPercent(speedKmh = 20.0, voltage = 29.4),
        )
    }

    @Test
    fun pwmEstimateIsAbsentRatherThanInfiniteWithoutVoltage() {
        assertTrue(ninebotPwmPercent(speedKmh = 30.0, voltage = 0.0).isNaN())
        assertTrue(ninebotPwmPercent(speedKmh = 30.0, voltage = Double.NaN).isNaN())
    }

    @Test
    fun firmwareAnswerYieldsVersionAndErrorCodes() {
        val engine = NinebotProtocolEngine()

        engine.consume(
            response(NinebotParam.Firmware, byteArrayOf(0x25, 0x01, 0x0C, 0x00, 0x00, 0x00)),
        )

        assertEquals("1.2.5", engine.firmwareVersion)
        val diagnostics = engine.latestDiagnostics()
        assertEquals(12, diagnostics.errorCode1)
        assertTrue(diagnostics.hasFault)
        assertTrue(diagnostics.messagesEn.single().contains("Gyroscope"))
        assertTrue(diagnostics.messagesRu.single().contains("гироскопа"))
    }

    @Test
    fun serialNumberAnswerIsTakenFromTheControllerOnly() {
        val engine = NinebotProtocolEngine()
        val serial = "N2GZ1234567890".encodeToByteArray()

        engine.consume(response(NinebotParam.SerialNumber, serial))

        assertEquals("N2GZ1234567890", engine.serialNumber)
    }

    // --- BMS ------------------------------------------------------------------------------

    @Test
    fun bmsLifeAndSerialFillTheSamePack() {
        val engine = NinebotProtocolEngine()

        engine.consume(response(NinebotParam.BmsSerial, bmsSerialPayload(), NinebotAddress.Bms1))
        engine.consume(response(NinebotParam.BmsLife, bmsLifePayload(), NinebotAddress.Bms1))

        val pack = engine.bmsPacks().single()
        assertEquals(1, pack.index)
        assertEquals("BMS0123456789", pack.serialNumber)
        assertClose(8.5, pack.factoryCapacityAh!!)
        assertEquals(37, pack.fullCycles)
        assertClose(58.4, pack.voltage!!)
        assertClose(-1.25, pack.currentAmps!!)
        assertClose(25.0, pack.temperature1!!)
        assertClose(27.0, pack.temperature2!!)
        assertEquals(98, pack.healthPercent)
    }

    @Test
    fun bmsCellCountFallsBackToFourteenAndGrowsWhenSlotsAreUsed() {
        assertEquals(14, cellCountFor(usedCells = 14))
        assertEquals(15, cellCountFor(usedCells = 15))
        assertEquals(16, cellCountFor(usedCells = 16))
    }

    @Test
    fun twoPacksAreKeptApart() {
        val engine = NinebotProtocolEngine()

        engine.consume(response(NinebotParam.BmsLife, bmsLifePayload(), NinebotAddress.Bms1))
        engine.consume(response(NinebotParam.BmsLife, bmsLifePayload(), NinebotAddress.Bms2))

        assertEquals(listOf(1, 2), engine.bmsPacks().map(NinebotBmsPack::index))
    }

    // --- settings -------------------------------------------------------------------------

    @Test
    fun settingsBlocksDecodeIntoCapabilities() {
        val engine = engineWithSettings()

        val byKey = engine.settingsCapabilities().associateBy(NinebotSettingCapability::key)

        assertEquals(1, byKey.getValue("ninebot_lock_mode").rawValue)
        assertEquals(1, byKey.getValue("ninebot_limited_mode").rawValue)
        assertEquals(25, byKey.getValue("ninebot_limited_speed").rawValue)
        assertEquals("25 km/h", byKey.getValue("ninebot_limited_speed").valueText)
        assertEquals(15, byKey.getValue("ninebot_limited_speed_first_km").rawValue)
        assertEquals(30, byKey.getValue("ninebot_alarm_1_speed").rawValue)
        assertEquals(35, byKey.getValue("ninebot_alarm_2_speed").rawValue)
        assertEquals(40, byKey.getValue("ninebot_alarm_3_speed").rawValue)
        // alarm mask 0b101
        assertEquals(1, byKey.getValue("ninebot_alarm_1_enabled").rawValue)
        assertEquals(0, byKey.getValue("ninebot_alarm_2_enabled").rawValue)
        assertEquals(1, byKey.getValue("ninebot_alarm_3_enabled").rawValue)
        assertEquals(3, byKey.getValue("ninebot_led_mode").rawValue)
        assertEquals(2, byKey.getValue("ninebot_pedal_sensitivity").rawValue)
        // drive flags 0b10101
        assertEquals(1, byKey.getValue("ninebot_drl").rawValue)
        assertEquals(0, byKey.getValue("ninebot_tail_light").rawValue)
        assertEquals(1, byKey.getValue("ninebot_headlight").rawValue)
        assertEquals(0, byKey.getValue("ninebot_handle_button").rawValue)
        assertEquals(1, byKey.getValue("ninebot_brake_assist").rawValue)
        assertEquals(100, byKey.getValue("ninebot_speaker_volume").rawValue)
    }

    @Test
    fun ledColourIsReadFromTheByteItsWriteTargets() {
        val engine = engineWithSettings()

        val colours = engine.settingsCapabilities()
            .filter { it.key.startsWith("ninebot_led_color_") }
            .map(NinebotSettingCapability::rawValue)

        assertEquals(listOf(200, 100, 50, 25), colours)
    }

    @Test
    fun capabilitiesAreAbsentUntilTheirBlockIsAnswered() {
        val engine = NinebotProtocolEngine()

        assertTrue(engine.settingsCapabilities().isEmpty())

        engine.consume(response(NinebotParam.SpeakerVolume, speakerBlock()))

        assertEquals(
            listOf("ninebot_speaker_volume"),
            engine.settingsCapabilities().map(NinebotSettingCapability::key),
        )
    }

    @Test
    fun speedWritesScaleToHundredthsOfKmh() {
        val engine = engineWithSettings()

        val write = assertNotNull(engine.buildSettingCommand("ninebot_alarm_2_speed", 42))
        val frame = assertNotNull(NinebotFrameCodec.parse(write))

        assertEquals(NinebotCommand.Write, frame.command)
        assertEquals(NinebotParam.Alarm2Speed, frame.param)
        assertEquals(NinebotAddress.Controller, frame.destination)
        assertEquals("6810", frame.payload.toHex()) // 4200 = 0x1068, little-endian
    }

    @Test
    fun speakerVolumeWriteShiftsBackIntoWheelUnits() {
        val engine = engineWithSettings()

        val write = assertNotNull(engine.buildSettingCommand("ninebot_speaker_volume", 64))
        val frame = assertNotNull(NinebotFrameCodec.parse(write))

        assertEquals("0002", frame.payload.toHex()) // 64 * 8 = 512
    }

    @Test
    fun limitedModeIsWrittenAsASingleByte() {
        val engine = engineWithSettings()

        val write = assertNotNull(engine.buildSettingCommand("ninebot_limited_mode", 0))
        val frame = assertNotNull(NinebotFrameCodec.parse(write))

        assertEquals("00", frame.payload.toHex())
    }

    @Test
    fun ledColourWriteCarriesTheMarkerByte() {
        val engine = engineWithSettings()

        val write = assertNotNull(engine.buildSettingCommand("ninebot_led_color_3", 0x2A))
        val frame = assertNotNull(NinebotFrameCodec.parse(write))

        assertEquals(NinebotParam.LedColor3, frame.param)
        assertEquals("f02a0000", frame.payload.toHex())
    }

    @Test
    fun packedBitWritePreservesEveryOtherFlag() {
        val engine = engineWithSettings()

        // Drive flags start at 0b10101. Turning the tail light on must not disturb the rest.
        val write = assertNotNull(engine.buildSettingCommand("ninebot_tail_light", 1))
        val frame = assertNotNull(NinebotFrameCodec.parse(write))

        assertEquals(NinebotParam.DriveFlags, frame.param)
        assertEquals("1700", frame.payload.toHex()) // 0b10111
    }

    @Test
    fun packedBitWriteClearsOnlyItsOwnBit() {
        val engine = engineWithSettings()

        val write = assertNotNull(engine.buildSettingCommand("ninebot_brake_assist", 0))
        val frame = assertNotNull(NinebotFrameCodec.parse(write))

        assertEquals("0500", frame.payload.toHex()) // 0b10101 without bit 4
    }

    @Test
    fun alarmEnableRebuildsTheAlarmMask() {
        val engine = engineWithSettings()

        val write = assertNotNull(engine.buildSettingCommand("ninebot_alarm_2_enabled", 1))
        val frame = assertNotNull(NinebotFrameCodec.parse(write))

        assertEquals(NinebotParam.Alarms, frame.param)
        assertEquals("0700", frame.payload.toHex()) // 0b101 -> 0b111
    }

    @Test
    fun packedRegistersAreExposedSoAWriteCanRebuildThemExactly() {
        val engine = engineWithSettings()
        val byKey = engine.settingsCapabilities().associateBy(NinebotSettingCapability::key)

        assertEquals(0b10101, byKey.getValue("ninebot_drive_flags_word").rawValue)
        assertEquals(0b101, byKey.getValue("ninebot_alarm_mask_word").rawValue)
        assertEquals(KIND_READONLY, byKey.getValue("ninebot_drive_flags_word").kind)

        assertEquals("ninebot_drive_flags_word", ninebotPackedWordKey("ninebot_headlight"))
        assertEquals("ninebot_alarm_mask_word", ninebotPackedWordKey("ninebot_alarm_3_enabled"))
        assertNull(ninebotPackedWordKey("ninebot_pedal_sensitivity"))
        assertNull(ninebotPackedWordKey("ninebot_nonexistent"))
    }

    @Test
    fun flagWritePreservesBitsNobodyHasIdentified() {
        // Bit 11 is not a setting LoEUC knows. It must survive a headlight write untouched.
        val currentWord = (1 shl 11) or 0b10101

        val write = assertNotNull(
            buildNinebotSettingCommand("ninebot_tail_light", 1, currentWord),
        )
        val frame = assertNotNull(NinebotFrameCodec.parse(write))

        assertEquals("1708", frame.payload.toHex()) // bit 11 still set, bit 1 now set
    }

    @Test
    fun statelessBuilderAgreesWithTheEngine() {
        val engine = engineWithSettings()
        val fromEngine = assertNotNull(engine.buildSettingCommand("ninebot_headlight", 0))
        val fromBuilder = assertNotNull(
            buildNinebotSettingCommand("ninebot_headlight", 0, currentWord = 0b10101),
        )

        assertEquals(fromEngine.toHex(), fromBuilder.toHex())
    }

    @Test
    fun readOnlyOutOfRangeAndUnknownWritesAreRefused() {
        val engine = engineWithSettings()

        assertNull(engine.buildSettingCommand("ninebot_limited_speed_first_km", 20))
        assertNull(engine.buildSettingCommand("ninebot_drive_flags_word", 3))
        assertNull(engine.buildSettingCommand("ninebot_pedal_sensitivity", 9))
        assertNull(engine.buildSettingCommand("ninebot_pedal_sensitivity", -1))
        assertNull(engine.buildSettingCommand("ninebot_nonexistent", 1))
    }

    @Test
    fun packedBitWriteIsRefusedBeforeTheBlockIsKnown() {
        val engine = NinebotProtocolEngine()

        // Without the current word there is no way to preserve the other flags, so refusing
        // beats writing a mask that silently clears them.
        assertNull(engine.buildSettingCommand("ninebot_headlight", 1))
    }

    @Test
    fun readBackTargetsOnlyTheBlockTheSettingLivesIn() {
        val engine = NinebotProtocolEngine()

        val params = engine.settingsReadCommands("ninebot_pedal_sensitivity")
            .map { assertNotNull(NinebotFrameCodec.parse(it)).param }

        assertEquals(listOf(NinebotParam.LedMode), params)
        assertEquals(3, engine.settingsReadCommands().size)
    }

    @Test
    fun connectBurstAsksForEveryBlockAndIdentity() {
        val engine = NinebotProtocolEngine()

        val params = engine.initialReadCommands()
            .map { assertNotNull(NinebotFrameCodec.parse(it)).param }

        assertEquals(
            listOf(
                NinebotParam.BleVersion,
                NinebotParam.SerialNumber,
                NinebotParam.Firmware,
                NinebotParam.LockMode,
                NinebotParam.LedMode,
                NinebotParam.SpeakerVolume,
            ),
            params,
        )
    }

    @Test
    fun bmsPollSweepsBothPacks() {
        val engine = NinebotProtocolEngine()

        val addressed = engine.bmsPollingCommands().map {
            val frame = assertNotNull(NinebotFrameCodec.parse(it))
            frame.destination to frame.param
        }

        assertEquals(
            listOf(
                NinebotAddress.Bms1 to NinebotParam.BmsSerial,
                NinebotAddress.Bms1 to NinebotParam.BmsLife,
                NinebotAddress.Bms1 to NinebotParam.BmsCells,
                NinebotAddress.Bms2 to NinebotParam.BmsSerial,
                NinebotAddress.Bms2 to NinebotParam.BmsLife,
                NinebotAddress.Bms2 to NinebotParam.BmsCells,
            ),
            addressed,
        )
    }

    @Test
    fun resetClearsEverySessionFact() {
        val engine = engineWithSettings()
        engine.consume(response(NinebotParam.LiveData, liveDataPayload()))
        engine.consume(response(NinebotParam.BmsLife, bmsLifePayload(), NinebotAddress.Bms1))

        engine.reset()

        assertTrue(engine.settingsCapabilities().isEmpty())
        assertTrue(engine.bmsPacks().isEmpty())
        assertNull(engine.serialNumber)
        assertTrue(engine.latestTelemetry().speedKmh.isNaN())
    }

    @Test
    fun packedDateDecodesAndRejectsImpossibleValues() {
        // 2021-03-14 -> year 21, month 3, day 14
        assertEquals("14.03.2021", ninebotPackedDate((21 shl 9) or (3 shl 5) or 14))
        assertNull(ninebotPackedDate(0))
        assertNull(ninebotPackedDate((21 shl 9) or (13 shl 5) or 14))
    }

    // --- fixtures -------------------------------------------------------------------------

    private fun engineWithSettings(): NinebotProtocolEngine {
        val engine = NinebotProtocolEngine()
        engine.consume(response(NinebotParam.LockMode, lockBlock()))
        engine.consume(response(NinebotParam.LedMode, ledBlock()))
        engine.consume(response(NinebotParam.SpeakerVolume, speakerBlock()))
        return engine
    }

    private fun cellCountFor(usedCells: Int): Int {
        val engine = NinebotProtocolEngine()
        val payload = ByteArray(32)
        repeat(usedCells) { cell -> payload.putU16Le(cell * 2, 3_800) }

        engine.consume(response(NinebotParam.BmsCells, payload, NinebotAddress.Bms1))

        return engine.bmsPacks().single().cellCount
    }

    /** An answer from the wheel: same framing, source and destination swapped. */
    private fun response(
        param: Int,
        payload: ByteArray,
        source: Int = NinebotAddress.Controller,
    ): ByteArray = NinebotFrameCodec.build(
        destination = NinebotAddress.App,
        command = NinebotCommand.Read,
        param = param,
        data = payload,
        source = source,
    )

    private fun liveDataPayload(): ByteArray = ByteArray(32).apply {
        putU16Le(0, 0) // error code
        putU16Le(2, 0) // alarm code
        putU16Le(4, 1) // esc status
        putU16Le(8, 87) // battery percent
        putU16Le(10, 4_500) // 45.00 km/h
        putU16Le(12, 2_000) // 20.00 km/h average
        putU32Le(14, 123_456) // metres
        putU16Le(18, 250) // trip, tens of metres
        putU16Le(20, 3_600) // seconds
        putU16Le(22, 385) // 38.5 C
        putU16Le(24, 5_880) // 58.80 V
        putU16Le(26, 0x10000 - 350) // -3.50 A
        putU16Le(28, 4_500)
        putU16Le(30, 2_000)
    }

    private fun lockBlock(): ByteArray = ByteArray(32).apply {
        putU16Le(0, 1) // 0x70 lock mode
        putU16Le(4, 1) // 0x72 limited mode
        putU16Le(6, 1_500) // 0x73 limited speed, first km
        putU16Le(8, 2_500) // 0x74 limited speed
        putU16Le(24, 0b101) // 0x7C alarm mask
        putU16Le(26, 3_000) // 0x7D
        putU16Le(28, 3_500) // 0x7E
        putU16Le(30, 4_000) // 0x7F
    }

    private fun ledBlock(): ByteArray = ByteArray(28).apply {
        putU16Le(0, 3) // 0xC6 led mode
        putLedColour(4, 200) // 0xC8
        putLedColour(8, 100) // 0xCA
        putLedColour(12, 50) // 0xCC
        putLedColour(16, 25) // 0xCE
        putU16Le(24, 2) // 0xD2 pedal sensitivity
        putU16Le(26, 0b10101) // 0xD3 drive flags
    }

    private fun speakerBlock(): ByteArray = ByteArray(2).apply {
        putU16Le(0, 100 shl 3)
    }

    private fun bmsSerialPayload(): ByteArray = ByteArray(34).apply {
        "BMS0123456789".encodeToByteArray().copyInto(this)
        this[14] = 0x21 // version 2.1
        this[15] = 0x01
        putU16Le(16, 8_500) // 8.5 Ah factory
        putU16Le(18, 8_100) // 8.1 Ah actual
        putU16Le(22, 37) // full cycles
        putU16Le(24, 61) // charge count
        putU16Le(32, (21 shl 9) or (3 shl 5) or 14)
    }

    private fun bmsLifePayload(): ByteArray = ByteArray(24).apply {
        putU16Le(0, 0x0001) // status
        putU16Le(2, 7_900) // 7.9 Ah remaining
        putU16Le(4, 93) // percent
        putU16Le(6, 0x10000 - 125) // -1.25 A
        putU16Le(8, 5_840) // 58.40 V
        this[10] = 45 // 25 C
        this[11] = 47 // 27 C
        putU16Le(12, 0)
        putU16Le(22, 98) // health
    }
}

private fun ByteArray.putU16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.putU32Le(offset: Int, value: Int) {
    putU16Le(offset, value and 0xFFFF)
    putU16Le(offset + 2, (value shr 16) and 0xFFFF)
}

/** A colour slot as a write leaves it: an `F0` marker, then the value. */
private fun ByteArray.putLedColour(offset: Int, value: Int) {
    this[offset] = 0xF0.toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
    assertTrue(
        abs(expected - actual) <= tolerance,
        "expected $expected but was $actual",
    )
}
