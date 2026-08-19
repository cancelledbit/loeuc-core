package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KingSongProtocolEngineTest {
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

        assertEquals(listOf(0x98, 0x63, 0x9B, 0x5E, 0x4B), initialReads.map { it[16].u8() })
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
        assertEquals(20.0, telemetry.rideTimeMinutes, 0.0001)
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
