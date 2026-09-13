package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KingSongProtocolEngineTest {
    @Test
    fun exposesTheNameResponseForPlatformCatalogueLookup() {
        val engine = KingSongProtocolEngine()
        val frame = kingSongFrame(command = 0xBB).apply {
            "KS-S22-2104".encodeToByteArray().copyInto(this, destinationOffset = 2)
        }

        engine.consume(frame)

        assertEquals("KS-S22-2104", engine.modelName())
    }

    @Test
    fun commandsMatchOfficialAppShape() {
        val engine = KingSongProtocolEngine()
        val read = engine.initialReadCommand()
        val initialReads = engine.initialReadCommands()
        val poll = engine.telemetryPollingCommand()

        assertEquals(20, read.size)
        assertEquals(0xAA, read[0].u8())
        assertEquals(0x55, read[1].u8())
        assertEquals(0x98, read[16].u8())
        assertEquals(0x14, read[17].u8())
        assertEquals(0x5A, read[18].u8())
        assertEquals(0x5A, read[19].u8())

        assertEquals(
            listOf(0x98, 0x63, 0x9B, 0x5E, 0x4B, 0x6D, 0x54, 0x3F, 0x8A),
            initialReads.map { it[16].u8() },
        )
        // The only 0x8A frame a connection sends is the charge-limit read, sub-command 8.
        assertEquals(8, initialReads.last()[2].u8())
        initialReads.forEach { command ->
            assertEquals(20, command.size)
            assertEquals(0xAA, command[0].u8())
            assertEquals(0x55, command[1].u8())
            assertEquals(0x14, command[17].u8())
            assertEquals(0x5A, command[18].u8())
            assertEquals(0x5A, command[19].u8())
        }

        assertEquals(20, poll.size)
        assertEquals(0x00, poll[16].u8())
        assertEquals(0x14, poll[17].u8())
    }

    @Test
    fun lightCommandsMatchApkDerivedShape() {
        val on = buildKingSongLightCommand(command = 18)
        val off = buildKingSongLightCommand(command = 19)

        assertEquals(20, on.size)
        assertEquals(0xAA, on[0].u8())
        assertEquals(0x55, on[1].u8())
        assertEquals(18, on[2].u8())
        assertEquals(1, on[3].u8())
        assertEquals(0x73, on[16].u8())
        assertEquals(0x14, on[17].u8())
        assertEquals(0x5A, on[18].u8())
        assertEquals(0x5A, on[19].u8())

        assertEquals(19, off[2].u8())
        assertEquals(on.copyOfRange(0, 2).toList(), off.copyOfRange(0, 2).toList())
        assertEquals(on.copyOfRange(3, 20).toList(), off.copyOfRange(3, 20).toList())
    }

    @Test
    fun reassemblyKeepsPartialFrameUntilComplete() {
        val frame = kingSongFrame(command = 0xA9)
        val firstChunk = frame.copyOfRange(0, 11)
        val secondChunk = frame.copyOfRange(11, frame.size)

        val partial = reassembleKingSongFrames(firstChunk)
        val complete = reassembleKingSongFrames(partial.remainingBuffer + secondChunk)

        assertTrue(partial.frames.isEmpty())
        assertEquals(firstChunk.toList(), partial.remainingBuffer.toList())
        assertEquals(1, complete.frames.size)
        assertEquals(0xA9, complete.frames.single().command)
        assertEquals(frame.toList(), complete.frames.single().bytes.toList())
        assertTrue(complete.remainingBuffer.isEmpty())
    }

    @Test
    fun liveA9TelemetryMatchesOfficialOffsets() {
        val engine = KingSongProtocolEngine()
        val frame = kingSongFrame(command = 0xA9).apply {
            putInt16Le(offset = 2, value = 13_456)
            putInt16Le(offset = 4, value = 1_234)
            putKingSongDistance(offset = 6, value = 123_456)
            putInt16Le(offset = 10, value = -250)
            putUInt16Le(offset = 12, value = 2_934)
            this[14] = 1
            this[15] = 0xE0.toByte()
        }

        val telemetry = requireNotNull(engine.consume(frame))

        assertEquals(12.34, telemetry.speedKmh, 0.0001)
        assertEquals(134.56, telemetry.voltage, 0.0001)
        assertEquals(-2.5, telemetry.current, 0.0001)
        assertEquals(-336.4, telemetry.power, 0.0001)
        assertEquals(29.34, telemetry.temperature, 0.0001)
        assertEquals(123.456, telemetry.totalDistanceKm, 0.0001)
        assertEquals(1.0, telemetry.rideMode, 0.0001)
        assertEquals(0xA9, telemetry.lastCommand)
    }

    @Test
    fun legacyC2ParsesVoltageSpeedAndTime() {
        val engine = KingSongProtocolEngine()
        val frame = kingSongFrame(command = 0xC2, legacy = true).apply {
            putInt16Le(offset = 2, value = 6_721)
            putInt16Le(offset = 4, value = 456)
            putUInt16Le(offset = 10, value = 1_200)
        }

        val telemetry = requireNotNull(engine.consume(frame))

        assertEquals(4.56, telemetry.speedKmh, 0.0001)
        assertEquals(67.21, telemetry.voltage, 0.0001)
        assertEquals(1_200.0, telemetry.rideTimeSeconds, 0.0001)
        assertEquals(0xC2, telemetry.lastCommand)
        assertTrue(telemetry.isLegacy)
    }

    @Test
    fun rideStatsTemperatureWinsAndStopsTheTwoSensorsFromFlipping() {
        val engine = KingSongProtocolEngine()
        val live = kingSongFrame(command = 0xA9).apply { putUInt16Le(offset = 12, value = 2_800) }
        val stats = kingSongFrame(command = 0xB9).apply { putInt16Le(offset = 14, value = 2_400) }

        engine.consume(live)
        val afterStats = requireNotNull(engine.consume(stats))
        val afterSecondLive = requireNotNull(engine.consume(live))

        assertEquals(24.0, afterStats.temperature, 0.0001)
        // The whole point: a second 0xA9 must not drag the reading back to its own sensor.
        assertEquals(24.0, afterSecondLive.temperature, 0.0001)
        assertEquals(28.0, afterSecondLive.secondaryTemperature, 0.0001)
    }

    @Test
    fun liveTemperatureStandsInUntilRideStatsArrive() {
        val engine = KingSongProtocolEngine()
        val live = kingSongFrame(command = 0xA9).apply { putUInt16Le(offset = 12, value = 2_800) }

        val telemetry = requireNotNull(engine.consume(live))

        assertEquals(28.0, telemetry.temperature, 0.0001)
        assertEquals(28.0, telemetry.secondaryTemperature, 0.0001)
    }

    @Test
    fun outputFrameCarriesPwm() {
        val engine = KingSongProtocolEngine()
        val frame = kingSongFrame(command = 0xF5).apply { this[15] = 44 }

        val telemetry = requireNotNull(engine.consume(frame))

        assertEquals(44.0, telemetry.pwmPercent, 0.0001)
    }

    @Test
    fun limitsFrameCarriesSpeedCeilingAndEnergyCounters() {
        val engine = KingSongProtocolEngine()
        val nominal = kingSongFrame(command = 0xF6).apply {
            putUInt16Le(offset = 2, value = 6_000)
            putUInt16Le(offset = 8, value = 141)
            putUInt16Le(offset = 10, value = 32)
        }
        val reduced = kingSongFrame(command = 0xF6).apply {
            putUInt16Le(offset = 2, value = 5_755)
            this[4] = 1
        }

        val atNominal = requireNotNull(engine.consume(nominal))
        assertEquals(60.0, atNominal.speedLimitKmh, 0.0001)
        assertEquals(false, atNominal.speedLimitReduced)
        assertEquals(141.0, atNominal.sessionWattHours, 0.0001)
        assertEquals(32.0, atNominal.tripWattHours, 0.0001)

        val underLoad = requireNotNull(engine.consume(reduced))
        assertEquals(57.55, underLoad.speedLimitKmh, 0.0001)
        assertTrue(underLoad.speedLimitReduced)
    }

    @Test
    fun bmsPagesRebuildAPackThatAgreesWithItsOwnSummary() {
        // Verbatim 0xF1 sweep from the S22 capture loeuc_C2C5C301609E_..._20260810_194404.jsonl.
        val pack = decodeKingSongBmsPacks(
            listOf(
                "AA 55 4C 2A 00 00 A2 01 E8 03 20 00 E8 03 1F 0E F1 00 5A 5A",
                "AA 55 AB 0B AC 0B A8 0B AC 0B C8 0B AE 0B B4 0B F1 01 5A 5A",
                "AA 55 1B 0E 1B 0E 1B 0E 1D 0E 1F 0E 1B 0E 1B 0E F1 02 5A 5A",
                "AA 55 0C 0E 1B 0E 1B 0E 21 0E 21 0E 21 0E 1B 0E F1 03 5A 5A",
                "AA 55 1D 0E 14 0E 16 0E 17 0E 16 0E 16 0E 17 0E F1 04 5A 5A",
                "AA 55 FD 0D FF 0D FD 0D FD 0D FD 0D FF 0D FF 0D F1 05 5A 5A",
                "AA 55 FF 0D FD 0D 00 00 00 00 B8 0B 00 00 00 00 F1 06 5A 5A",
            ).map { it.hexToByteArray() },
        ).single()

        assertEquals(1, pack.index)
        assertEquals(108.28, requireNotNull(pack.voltage), 0.0001)
        assertEquals(0.0, requireNotNull(pack.currentAmps), 0.0001)
        assertEquals(4.18, requireNotNull(pack.remainingAh), 0.0001)
        assertEquals(10.0, requireNotNull(pack.fullAh), 0.0001)
        assertEquals(32, pack.chargeCycles)
        assertEquals(3.615, requireNotNull(pack.maxCellVoltage), 0.0001)
        assertEquals(41.8, requireNotNull(pack.chargePercent), 0.0001)

        assertEquals(30, pack.cells.size)
        assertEquals(3.611, pack.cells.first(), 0.0001)
        assertEquals(3.581, pack.cells.last(), 0.0001)
        // The decode is only credible if the cells add up to the voltage the pack itself reports.
        assertTrue(
            kotlin.math.abs(pack.cells.sum() - 108.28) < 0.5,
            "cell sum ${pack.cells.sum()} should track the reported 108.28 V",
        )

        assertEquals(
            listOf(25.7, 25.8, 25.4, 25.8, 28.6, 26.0, 26.6, 27.0),
            pack.temperatures.map { (it * 10.0).toInt() / 10.0 },
        )
    }

    @Test
    fun streamedBmsPagesSurviveTheFramesThatCarriedThem() {
        // The iOS path has no frame history to replay, so the engine has to hold the pages
        // itself: a pack is only complete several frames after the first page arrived.
        val engine = KingSongProtocolEngine()
        val sweep = listOf(
            "AA 55 4C 2A 00 00 A2 01 E8 03 20 00 E8 03 1F 0E F1 00 5A 5A",
            "AA 55 AB 0B AC 0B A8 0B AC 0B C8 0B AE 0B B4 0B F1 01 5A 5A",
            "AA 55 1B 0E 1B 0E 1B 0E 1D 0E 1F 0E 1B 0E 1B 0E F1 02 5A 5A",
            "AA 55 0C 0E 1B 0E 1B 0E 21 0E 21 0E 21 0E 1B 0E F1 03 5A 5A",
            "AA 55 1D 0E 14 0E 16 0E 17 0E 16 0E 16 0E 17 0E F1 04 5A 5A",
            "AA 55 FD 0D FF 0D FD 0D FD 0D FD 0D FF 0D FF 0D F1 05 5A 5A",
            "AA 55 FF 0D FD 0D 00 00 00 00 B8 0B 00 00 00 00 F1 06 5A 5A",
        )

        assertTrue(engine.bmsPacks().isEmpty())
        sweep.forEach { line -> engine.consume(line.hexToByteArray()) }

        val pack = engine.bmsPacks().single()
        assertEquals(30, pack.cells.size)
        assertEquals(108.28, requireNotNull(pack.voltage), 0.0001)
        assertEquals(41.8, requireNotNull(pack.chargePercent), 0.0001)

        engine.reset()
        assertTrue(engine.bmsPacks().isEmpty())
    }

    @Test
    fun reassemblyKeepsBmsFramesWhoseLengthByteIsAPageIndex() {
        val bmsFrame = "AA 55 4C 2A 00 00 A2 01 E8 03 20 00 E8 03 1F 0E F1 00 5A 5A".hexToByteArray()

        val result = reassembleKingSongFrames(bmsFrame)

        assertEquals(1, result.frames.size)
        assertEquals(0xF1, result.frames.single().command)
    }

    /**
     * Frames lifted verbatim from a KSN-S18Pro+ capture. That wheel answers no BMS page at all -
     * 25 seconds of it carry only `0xA9`, `0xB9`, `0xF5` and `0xF6` - and King Song states no
     * charge anywhere in the protocol, so the pack voltage against the catalogue's cell count is
     * the only answer there is.
     */
    @Test
    fun derivesChargeFromPackVoltageOnceTheCellCountIsKnown() {
        val engine = KingSongProtocolEngine()
        val live = byteArrayOf(
            0xAA.toByte(), 0x55, 0x93.toByte(), 0x1D, 0x4F, 0x02, 0x4B, 0x00,
            0x35, 0xE0.toByte(), 0x24, 0x00, 0x60, 0x09, 0x00, 0xE0.toByte(),
            0xA9.toByte(), 0x14, 0x5A, 0x5A,
        )

        val unknown = engine.consumeFrame(live)
        assertEquals(75.71, unknown?.voltage ?: 0.0, 0.001)
        assertTrue(unknown!!.batteryPercent.isNaN(), "charge must stay unknown without a cell count")

        engine.setPackSeriesCells(20)
        val known = engine.consumeFrame(live)

        // 75.71 V over 20 cells is 3.7855 V per cell, between the 3.78 and 3.85 points.
        assertEquals(20, engine.packSeriesCells())
        assertEquals(60.79, known?.batteryPercent ?: 0.0, 0.01)
    }

    @Test
    fun anImpossibleCellCountLeavesTheChargeUnknown() {
        val engine = KingSongProtocolEngine()
        engine.setPackSeriesCells(0)
        assertEquals(0, engine.packSeriesCells())
        engine.setPackSeriesCells(200)
        assertEquals(0, engine.packSeriesCells())

        val live = kingSongFrame(command = 0xA9).apply {
            putInt16Le(offset = 2, value = 7571)
            this[15] = 0xE0.toByte()
        }

        assertTrue(engine.consumeFrame(live)!!.batteryPercent.isNaN())
    }

    /** A new session is a new wheel, so the previous pack's cell count must not survive it. */
    @Test
    fun resetForgetsThePackCellCount() {
        val engine = KingSongProtocolEngine()
        engine.setPackSeriesCells(20)

        engine.reset()

        assertEquals(0, engine.packSeriesCells())
    }
}

class KingSongIdentityAndSettingsDecodeTest {
    @Test
    fun realS22AnswersDecodeIntoIdentityAndSettings() {
        // Verbatim frames from the S22 capture loeuc_C2C5C301609E_..._20260810_194404.jsonl.
        val engine = KingSongProtocolEngine()
        listOf(
            "AA 55 00 00 32 00 37 00 38 00 3C 00 00 00 00 00 B5 14 5A 5A",
            "AA 55 4B 53 2D 53 32 32 2D 30 32 33 37 00 00 00 BB 14 84 FD",
            "AA 55 E4 2A 79 06 79 00 D4 76 2A 00 F0 0A 01 E0 A9 14 5A 5A",
            "AA 55 00 00 A1 03 E7 04 5D 0B 12 01 00 00 60 09 B9 14 5A 5A",
        ).forEach { engine.consume(it.hexToByteArray()) }

        val identity = engine.identity()
        // The name answer closes on a checksum, not on 5A 5A; it used to be dropped as noise.
        assertEquals("KS-S22-0237", identity.name)
        assertEquals("KS-S22", identity.model)
        assertEquals("2.37", identity.firmwareVersion)
        assertEquals("KS-S22-0237", engine.modelName())

        val settings = engine.settings()
        assertEquals(50, settings.alarm1SpeedKmh)
        assertEquals(55, settings.alarm2SpeedKmh)
        assertEquals(56, settings.alarm3SpeedKmh)
        // The same wheel's 0xF6 ceiling read 60.00 km/h for the whole capture.
        assertEquals(60, settings.maxSpeedKmh)
        assertEquals(KingSongPedalMode.Medium.wire, settings.pedalMode)
        assertEquals(KingSongLightMode.On.ordinal, settings.lightMode)
        assertEquals(true, settings.voicePromptsOff)
        assertNull(settings.ledStripOn)
        assertNull(settings.chargeLimitPercent)

        val telemetry = requireNotNull(
            engine.consumeFrame("AA 55 00 00 A1 03 E7 04 5D 0B 12 01 00 00 60 09 B9 14 5A 5A".hexToByteArray()),
        )
        assertEquals(false, telemetry.fanRunning)
        assertEquals(false, telemetry.charging)
    }

    @Test
    fun aNameFrameWithABrokenChecksumIsNotTakenForOne() {
        val frame = "AA 55 4B 53 2D 53 32 32 2D 30 32 33 37 00 00 00 BB 14 84 FE".hexToByteArray()

        assertTrue(reassembleKingSongFrames(frame).frames.isEmpty())
    }

    @Test
    fun serialAnswerSpillsIntoTheTrailerBytes() {
        val serial = "KSXX00TEST012345Z"
        val frame = ByteArray(20).apply {
            this[0] = 0xAA.toByte()
            this[1] = 0x55
            serial.substring(0, 14).encodeToByteArray().copyInto(this, destinationOffset = 2)
            this[16] = 0xB3.toByte()
            serial.substring(14).encodeToByteArray().copyInto(this, destinationOffset = 17)
        }
        val engine = KingSongProtocolEngine()

        engine.consume(frame)

        assertEquals(serial, engine.identity().serialNumber)
    }

    @Test
    fun settingsAnswersFromTheOfficialAppReadsDecode() {
        val engine = KingSongProtocolEngine()
        engine.consume(kingSongFrame(command = 0x6E).apply { this[2] = 0 })
        engine.consume(kingSongFrame(command = 0x55).apply { this[2] = 1 })
        engine.consume(kingSongFrame(command = 0x3F).apply { this[2] = 0; this[4] = 0x10; this[5] = 0x0E })
        engine.consume(kingSongFrame(command = 0x8A).apply { this[2] = 8; this[4] = 80 })

        val settings = engine.settings()
        assertEquals(true, settings.ledStripOn)
        assertEquals(true, settings.strobeOn)
        assertEquals(3_600, settings.standbyDelaySeconds)
        assertEquals(80, settings.chargeLimitPercent)
    }

    @Test
    fun gyroAnswersInTheSameCommandFamilyAreNotReadAsTheChargeLimit() {
        val engine = KingSongProtocolEngine()
        engine.consume(kingSongFrame(command = 0x8A).apply { this[2] = 1; this[4] = 80 })

        assertNull(engine.settings().chargeLimitPercent)
    }
}

class KingSongExtendedBmsTest {
    @Test
    fun extendedFrameInOneNotificationDecodesAPack() {
        val engine = KingSongProtocolEngine()

        engine.consume(extendedBmsFrame(command = 0xF1))

        val pack = engine.bmsPacks().single()
        assertEquals(1, pack.index)
        assertEquals(42, pack.cells.size)
        assertEquals(4.081, pack.cells.first(), 0.0001)
        assertEquals(171.66, requireNotNull(pack.voltage), 0.0001)
        assertEquals(-0.11, requireNotNull(pack.currentAmps), 0.0001)
        assertEquals(95.0, requireNotNull(pack.chargePercent), 0.0001)
        assertEquals(48, pack.chargeCycles)
        assertEquals(10.0, requireNotNull(pack.factoryAh), 0.0001)
        assertEquals(9.5, requireNotNull(pack.remainingAh), 0.0001)
        assertEquals(listOf(27.0, 29.0, 29.0, 28.0, 29.0, 30.0, 29.0, 27.0), pack.temperatures)
        // The second environment sensor reads zero on both channels: absent, not 0 C at 0 %.
        assertEquals(listOf(24.9), pack.environmentTemperatures)
        assertEquals(listOf(52.2), pack.environmentHumidity)
        assertTrue(kotlin.math.abs(pack.cells.sum() - 171.66) < 0.2, "cells ${pack.cells.sum()}")
    }

    @Test
    fun extendedFrameSplitAcrossTwentyByteNotificationsIsReassembled() {
        val frame = extendedBmsFrame(command = 0xF2)
        val live = kingSongFrame(command = 0xF5).apply { this[15] = 33 }
        val stream = frame + live
        var buffer = ByteArray(0)
        val frames = mutableListOf<KingSongFrame>()

        stream.toList().chunked(20).forEach { chunk ->
            val result = reassembleKingSongFrames(buffer + chunk.toByteArray())
            frames += result.frames
            buffer = result.remainingBuffer
        }

        assertEquals(listOf(0xF2, 0xF5), frames.map { it.command })
        assertEquals(frame.size, frames.first().bytes.size)
        assertEquals(2, decodeKingSongBmsPacks(frames.map { it.bytes }).single().index)
    }

    @Test
    fun aTruncatedExtendedHeaderDoesNotSwallowTheFramesBehindIt() {
        // At the default MTU the stack cuts the notification to its first 20 bytes.
        val header = extendedBmsFrame(command = 0xF1).copyOfRange(0, 20)
        val live = kingSongFrame(command = 0xF5).apply { this[15] = 33 }
        val engine = KingSongProtocolEngine()

        engine.consume(header)
        val telemetry = requireNotNull(engine.consume(live))

        assertEquals(33.0, telemetry.pwmPercent, 0.0001)
        assertTrue(engine.bmsPacks().isEmpty())
    }

    @Test
    fun theS22PageSweepStillDecodesAlongsideTheExtendedPath() {
        val sweep = listOf(
            "AA 55 4C 2A 00 00 A2 01 E8 03 20 00 E8 03 1F 0E F1 00 5A 5A",
            "AA 55 FF 0D FD 0D 00 00 00 00 B8 0B 00 00 00 00 F1 06 5A 5A",
        ).map { it.hexToByteArray() }

        val packs = decodeKingSongBmsPacks(sweep + extendedBmsFrame(command = 0xF2))

        assertEquals(listOf(1, 2), packs.map { it.index })
        assertEquals(41.8, requireNotNull(packs[0].chargePercent), 0.0001)
        assertEquals(95.0, requireNotNull(packs[1].chargePercent), 0.0001)
    }
}

/**
 * A synthetic F-series `0xD0` frame whose values are modelled on the F22P capture in WheelLog's
 * adapter tests: 42 cells, eight temperatures, one environment sensor.
 */
private fun extendedBmsFrame(command: Int): ByteArray {
    val payload = mutableListOf<Byte>()
    fun u16(value: Int) {
        payload += (value and 0xFF).toByte()
        payload += ((value shr 8) and 0xFF).toByte()
    }
    payload += 0
    payload += 42
    val cells = listOf(4081, 4082, 4083, 4084, 4085, 4086, 4087, 4088, 4089)
    repeat(42) { u16(cells[it % cells.size]) }
    payload += 8
    listOf(270, 290, 290, 280, 290, 300, 290, 270).forEach { u16(2730 + it) }
    u16(-11)
    u16(17_166)
    u16(950)
    payload += 0
    u16(999)
    u16(48)
    u16(1_000)
    payload += 0
    u16(249)
    u16(0)
    u16(522)
    u16(0)
    u16(0)
    return ByteArray(20).apply {
        this[0] = 0xAA.toByte()
        this[1] = 0x55
        this[15] = payload.size.toByte()
        this[16] = command.toByte()
        this[17] = 0xD0.toByte()
        this[18] = 0x5A
        this[19] = 0x5A
    } + payload.toByteArray()
}

private fun String.hexToByteArray(): ByteArray {
    return split(" ").map { it.toInt(radix = 16).toByte() }.toByteArray()
}

private fun kingSongFrame(command: Int, legacy: Boolean = false): ByteArray {
    return ByteArray(20).apply {
        if (legacy) {
            this[0] = 0xF1.toByte()
            this[1] = 0xEF.toByte()
        } else {
            this[0] = 0xAA.toByte()
            this[1] = 0x55
            this[18] = 0x5A
            this[19] = 0x5A
        }
        this[16] = command.toByte()
        this[17] = 0x14
    }
}

private fun ByteArray.putInt16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.putUInt16Le(offset: Int, value: Int) {
    putInt16Le(offset = offset, value = value)
}

private fun ByteArray.putKingSongDistance(offset: Int, value: Long) {
    this[offset] = ((value shr 16) and 0xFF).toByte()
    this[offset + 1] = ((value shr 24) and 0xFF).toByte()
    this[offset + 2] = (value and 0xFF).toByte()
    this[offset + 3] = ((value shr 8) and 0xFF).toByte()
}

private fun Byte.u8(): Int {
    return toInt() and 0xFF
}
