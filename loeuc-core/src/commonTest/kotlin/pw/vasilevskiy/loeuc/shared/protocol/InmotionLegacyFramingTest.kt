package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real notify captures from legacy InMotion wheels, published in WheelLog's test suite
 * (`InmotionAdapterTest.kt` and the `RAW_inmotion_*.csv` resources). These are recorded wheel
 * bytes - data, not code - kept chunk by chunk exactly as the phone received them.
 */
internal object InmotionLegacyCaptures {
    val v5fSlow = listOf(
        "AAAA1401A5550F7C000000B4720020FE0001001B",
        "0076BA5C28711200000000000000000100000000",
        "000000FA010301FA0103010402020100000000C2",
        "040001C2040001900302010000000000000000A8",
        "6100000010000000000000000000000000000000",
        "0000000100000000000000000000000000000000",
        "0000000200000500000000000000000000000004",
        "020301E35555",
    )
    val v5fFast = listOf(
        "AAAA1301A5550F60000000B4720020FE000100FF",
        "3F00003A18DEFF5D01000029F0FFFF29F0FFFFEC",
        "FFFFFF15200000000000001A1A00000000000000",
        "0000001CE3130000000000000026061A03D20721",
        "0000006F0100006F010000F7010000420C00002B",
        "110000070000000000000000000000265555",
    )
    val v8fSlow = listOf(
        "AAAA1401A5550F8500000000000000FE0201000E",
        "009BBD5E4A601400000000000000000000000000",
        "0000001500020200000000070003020000000026",
        "0301010000000000000A000000000073000000C8",
        "AF00002510000000100000000000000000000000",
        "0000000100000000000000000000000000000000",
        "0000000600000800000000000000000000000000",
        "000000801027000001010A00DC5555",
    )
    val v8fFast1 = listOf(
        "AAAA1301A5550F9500000000000000FE0201008F",
        "020000000000000000000054FAFFFF54FAFFFFFB",
        "FFFFFFBE200000000000001B1B24240000000000",
        "000000AF5400000100000000302B140605E00722",
        "00000023000000C50000005D020000D900000006",
        "000000000000000000000000000000004000081B",
        "0000F221000033060000000000000B0000006216",
        "0000F42A0000030000000E000000110106000000",
        "000000000000C500765555",
    )
    val v8fFast2 = listOf(
        "AAAA1301A5550F9500000000000000FE0201007A",
        "14000000000000000000003CFDFFFF3CFDFFFFF6",
        "FFFFFFA7200000400100001C1C2424F8FFFFFFE7",
        "FFFFFFB75400000900000000042C140605E00722",
        "000000E301000023010000AC0500000302000056",
        "0000004C0000000000000000000000004000081C",
        "0000F221000033060000BF020000070100006F16",
        "0000032B0000100000001D000000380256004C00",
        "F8FFE7FFE7FF2301465555",
    )
    val v8sSlow = listOf(
        "aaaa1401a5550f8500000000000000fe02010006",
        "0146bd5ea5aa7115000000000000000000000000",
        "0000000015000266000000000700036600000000",
        "260301010000000000000a000000000000000800",
        "b888000043100000001000000000000000000000",
        "0000000001000000000000000000000000000000",
        "000000000700000800000000b005004f00000065",
        "00000000801027000001000a01a05555",
    )
    val v8sFast = listOf(
        "aaaa1301a5550f9500000000000000fe02010015",
        "eeffff0000000000000000000000000000000007",
        "00000006200000000000001e1e92920000000004",
        "000000af04000000000000000d370c1203d00723",
        "0000000000000000000000bcfeffff1400000000",
        "0000001100000000000000000000000040000892",
        "00007f0500006600000083b205004f0000006502",
        "0000ca45000000000000d51f0000600000001100",
        "0000040004000000bf5555",
    )

    /** A V8F slow info reply whose check byte is 0x55, sent escaped as `A5 55 55 55`. */
    val escapedChecksumSlow = listOf(
        "aaaa1401a5550f8500000000000000fe02010001",
        "00da7c5e1a611400000000000000000000000000",
        "0000001500020200000000070003020000000026",
        "0301010000000000000a000000000000000200d0",
        "840000ea0f000000100000000000000000000000",
        "0000000100000000000000000000000000000000",
        "00000006000008000000005b0a006f6e01003a00",
        "0000006c3421000001010a00a5555555",
    )

    /**
     * The first 914 ms of a V8S connection (`RAW_inmotion_V8S.csv`), as (ms since the first
     * notify, notify hex): three unsolicited 0x0F060101 frames, two PIN replies, two slow info
     * replies and the first fast info reply.
     */
    val v8sConnect: List<Pair<Long, String>> = listOf(
        0L to "aaaa0101060f1200000000000000fe0201000708",
        7L to "c0060146bd5ea5aa71155e000000000000ef5555",
        126L to "aaaa0101060f1200000000000000fe0201000708",
        130L to "c0060146bd5ea5aa71155e000000000000ef5555",
        223L to "aaaa0101060f1200000000000000fe0201000708",
        227L to "c0060146bd5ea5aa71155e000000000000ef5555",
        271L to "aaaa0703a5550f01000000000000000402000075",
        275L to "5555",
        370L to "aaaa0703a5550f01000000000000000402000075",
        375L to "5555",
        419L to "aaaa1401a5550f8500000000000000fe02010006",
        425L to "0146bd5ea5aa7115000000000000000000000000",
        429L to "0000000015000266000000000700036600000000",
        433L to "260301010000000000000a000000000000000800",
        466L to "b888000043100000001000000000000000000000",
        470L to "0000000001000000000000000000000000000000",
        474L to "000000000700000800000000b005004f00000065",
        516L to "00000000801027000001000a01a05555",
        576L to "aaaa1401a5550f8500000000000000fe02010006",
        580L to "0146bd5ea5aa7115000000000000000000000000",
        585L to "0000000015000266000000000700036600000000",
        588L to "260301010000000000000a000000000000000800",
        592L to "b888000043100000001000000000000000000000",
        613L to "0000000001000000000000000000000000000000",
        617L to "000000000700000800000000b005004f00000065",
        621L to "00000000801027000001000a01a05555",
        760L to "aaaa1301a5550f9500000000000000fe02010015",
        809L to "eeffff0000000000000000000000000000000007",
        814L to "00000006200000000000001e1e92920000000004",
        857L to "000000af04000000000000000d370c1203d00723",
        862L to "0000000000000000000000bcfeffff1400000000",
        866L to "0000001100000000000000000000000040000892",
        871L to "00007f0500006600000083b205004f0000006502",
        909L to "0000ca45000000000000d51f0000600000001100",
        914L to "0000040004000000bf5555",
    )

    /** Pushed alert frames from `RAW_inmotion_alerts.csv`; the last tail byte came in its own notify. */
    val alerts = listOf(
        "aaaa0101780f2801740000000000080001002f55", "55",
        "aaaa0101780f0101e201f51c0000080001008855", "55",
    )

    fun wire(chunks: List<String>): ByteArray = chunks.joinToString("").decodeHex()

    fun message(chunks: List<String>): InmotionLegacyMessage =
        assertNotNull(InmotionLegacyFraming.decodeFrame(wire(chunks)), "capture must decode")

    val allFrames: List<List<String>>
        get() = listOf(v5fSlow, v5fFast, v8fSlow, v8fFast1, v8fFast2, v8sSlow, v8sFast, escapedChecksumSlow)
}

class InmotionLegacyFramingTest {
    @Test
    fun everyCaptureDecodesWithValidChecksumAndLength() {
        val sizes = InmotionLegacyCaptures.allFrames.map { InmotionLegacyCaptures.message(it).extra?.size }
        assertEquals(listOf(124, 96, 133, 149, 149, 133, 149, 133), sizes)
    }

    @Test
    fun encodeReproducesEveryCaptureByteForByte() {
        for (chunks in InmotionLegacyCaptures.allFrames) {
            val wire = InmotionLegacyCaptures.wire(chunks)
            assertContentEquals(wire, InmotionLegacyFraming.encode(InmotionLegacyCaptures.message(chunks)))
        }
    }

    @Test
    fun escapedChecksumCaptureRoundTrips() {
        val wire = InmotionLegacyCaptures.wire(InmotionLegacyCaptures.escapedChecksumSlow)
        val tail = wire.copyOfRange(wire.size - 4, wire.size)
        assertContentEquals("A5555555".decodeHex(), tail)
        val message = InmotionLegacyCaptures.message(InmotionLegacyCaptures.escapedChecksumSlow)
        assertEquals(InmotionLegacyIds.SLOW_INFO, message.id)
        assertContentEquals(wire, InmotionLegacyFraming.encode(message))
    }

    @Test
    fun decodeFrameRejectsCorruptionAndWrongMarkers() {
        val wire = InmotionLegacyCaptures.wire(InmotionLegacyCaptures.v8fFast1)
        val flipped = wire.copyOf().also { it[30] = (it[30] + 1).toByte() }
        assertNull(InmotionLegacyFraming.decodeFrame(flipped))
        assertNull(InmotionLegacyFraming.decodeFrame(wire.copyOfRange(0, wire.size - 1)))
        assertNull(InmotionLegacyFraming.decodeFrame(byteArrayOf(0x55, 0x55) + wire.copyOfRange(2, wire.size)))
        // An extended body whose declared extra length disagrees with the bytes present is
        // rejected even when its checksum is right.
        fun body(declared: Int, extra: String): ByteArray {
            val unchecked = "1301550F${declared.toString(16).padStart(2, '0')}00000000000000FE020100$extra".decodeHex()
            return unchecked + byteArrayOf(unchecked.sumOf { it.toInt() and 0xFF }.toByte())
        }
        assertNotNull(InmotionLegacyFraming.parseUnescapedBody(body(3, "010203")))
        assertNull(InmotionLegacyFraming.parseUnescapedBody(body(3, "0102")))
        assertNull(InmotionLegacyFraming.parseUnescapedBody(body(2, "010203")))
    }

    @Test
    fun reassemblesARealFrameSplitAtEveryByteBoundary() {
        val wire = InmotionLegacyCaptures.wire(InmotionLegacyCaptures.v8fFast1)
        val expected = InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8fFast1)
        for (split in 1 until wire.size) {
            val reassembler = InmotionLegacyReassembler()
            val first = reassembler.push(wire.copyOfRange(0, split))
            val second = reassembler.push(wire.copyOfRange(split, wire.size))
            assertEquals(emptyList(), first, "split at $split")
            assertEquals(listOf(expected), second, "split at $split")
        }
    }

    @Test
    fun reassemblesTheEscapedChecksumSplitInsideTheTail() {
        val wire = InmotionLegacyCaptures.wire(InmotionLegacyCaptures.escapedChecksumSlow)
        for (split in wire.size - 4 until wire.size) {
            val reassembler = InmotionLegacyReassembler()
            assertTrue(reassembler.push(wire.copyOfRange(0, split)).isEmpty())
            assertEquals(1, reassembler.push(wire.copyOfRange(split, wire.size)).size, "split at $split")
        }
    }

    @Test
    fun reassemblesByteByByteAndInCaptureChunks() {
        val reassembler = InmotionLegacyReassembler()
        val everything = InmotionLegacyCaptures.allFrames.flatten()
        val chunked = everything.flatMap { reassembler.push(it.decodeHex()) }
        assertEquals(8, chunked.size)

        reassembler.reset()
        val bytewise = everything.joinToString("").decodeHex().flatMap { reassembler.push(byteArrayOf(it)) }
        assertEquals(chunked, bytewise)
    }

    @Test
    fun resyncsAfterGarbageAndABadChecksum() {
        val good = InmotionLegacyCaptures.wire(InmotionLegacyCaptures.v8sFast)
        val bad = InmotionLegacyCaptures.wire(InmotionLegacyCaptures.v8fFast2).also { it[25] = (it[25] + 1).toByte() }
        val garbage = "00AA0155AA551234A5".decodeHex()
        val truncated = good.copyOfRange(0, 40)
        val stream = garbage + bad + truncated + byteArrayOf(0xAA.toByte()) + good
        val messages = InmotionLegacyReassembler().push(stream)
        assertEquals(listOf(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sFast)), messages)
    }

    @Test
    fun requestsHaveTheExactWireBytes() {
        assertContentEquals(
            "AAAA1301A5550FFFFFFFFFFFFFFFFF080500007D5555".decodeHex(),
            InmotionLegacyRequests.fastInfo(),
        )
        assertContentEquals(
            "AAAA1401A5550FFFFFFFFFFFFFFFFF080500017F5555".decodeHex(),
            InmotionLegacyRequests.slowInfo(),
        )
        assertContentEquals(
            "AAAA0703A5550F3030303030300000080500009B5555".decodeHex(),
            InmotionLegacyRequests.pin(),
        )
    }

    @Test
    fun pinIsLeftPaddedAndValidated() {
        assertEquals("000000", InmotionLegacyRequests.normalizePin(""))
        assertEquals("001234", InmotionLegacyRequests.normalizePin("1234"))
        assertEquals("987654", InmotionLegacyRequests.normalizePin("987654"))
        assertNull(InmotionLegacyRequests.normalizePin("1234567"))
        assertNull(InmotionLegacyRequests.normalizePin("12a4"))
        assertNull(InmotionLegacyRequests.normalizePin(" 1234"))

        val message = assertNotNull(InmotionLegacyFraming.decodeFrame(InmotionLegacyRequests.pin("1234")))
        assertEquals(InmotionLegacyIds.PIN_CODE, message.id)
        assertContentEquals("3030313233340000".decodeHex(), message.data)
        assertEquals(8, message.length)
        assertEquals(5, message.channel)
        assertEquals(0, message.type)

        assertFailsWith<IllegalArgumentException> { InmotionLegacyRequests.pin("12345a") }
        assertFailsWith<IllegalArgumentException> { InmotionLegacySession("1234567") }
    }

    @Test
    fun rawSignatureAcceptsRealFirstChunksAndCompleteFrames() {
        for (chunks in InmotionLegacyCaptures.allFrames) {
            assertTrue(InmotionLegacyDetection.matchesRawNotify(chunks.first().decodeHex()), chunks.first())
            assertTrue(InmotionLegacyDetection.containsValidFrame(InmotionLegacyCaptures.wire(chunks)))
        }
        // The V8S PIN reply is len 4, and its first notify stops just before the tail.
        assertTrue(InmotionLegacyDetection.matchesRawNotify("aaaa0703a5550f01000000000000000402000075".decodeHex()))
        assertTrue(InmotionLegacyDetection.matchesRawNotify(InmotionLegacyRequests.fastInfo()))
    }

    @Test
    fun rawSignatureRejectsOtherBrandsAndBrokenFrames() {
        // Begode (55 AA ... 5A 5A 5A 5A) and KingSong (AA 55 ... 5A 5A) telemetry frames.
        assertFalse(InmotionLegacyDetection.matchesRawNotify("55AA19F0000000000000000000001700145A5A5A5A".decodeHex()))
        assertFalse(InmotionLegacyDetection.matchesRawNotify("AA55B01C0000000000000000000000A9145A5A".decodeHex()))
        // Newer InMotion protocol: AA AA header but no CAN id behind it.
        assertFalse(InmotionLegacyDetection.matchesRawNotify("AAAA1405820000000000000000000000000000".decodeHex()))
        // Right header, unknown CAN id.
        assertFalse(InmotionLegacyDetection.matchesRawNotify("AAAA1399A5550F60000000B4720020FE000100FF".decodeHex()))
        // Too short to carry the header.
        assertFalse(InmotionLegacyDetection.matchesRawNotify("AAAA1301A5550F".decodeHex()))
        // A complete frame with a bad checksum.
        val bad = InmotionLegacyRequests.fastInfo().also { it[it.size - 3] = 0x7E }
        assertFalse(InmotionLegacyDetection.matchesRawNotify(bad))
        assertFalse(InmotionLegacyDetection.containsValidFrame(bad))
    }

    @Test
    fun nameGuessUsesOnlyTheOfficialPrefixes() {
        assertEquals(InmotionLegacyModel.V5, InmotionLegacyDetection.guessModelFromName("V5-1B0076BA"))
        assertEquals(InmotionLegacyModel.V8, InmotionLegacyDetection.guessModelFromName("v8-0123"))
        assertEquals(InmotionLegacyModel.V8F, InmotionLegacyDetection.guessModelFromName("V8F-9B000E"))
        assertEquals(InmotionLegacyModel.V8S, InmotionLegacyDetection.guessModelFromName("V8S-460106"))
        assertEquals(InmotionLegacyModel.V10, InmotionLegacyDetection.guessModelFromName("V10-1234"))
        assertEquals(InmotionLegacyModel.V10F, InmotionLegacyDetection.guessModelFromName("V10F-1234"))
        assertNull(InmotionLegacyDetection.guessModelFromName("V11-1234"))
        assertNull(InmotionLegacyDetection.guessModelFromName("V8"))
        assertNull(InmotionLegacyDetection.guessModelFromName("V10S-1234"))
        assertNull(InmotionLegacyDetection.guessModelFromName(null))
    }
}
