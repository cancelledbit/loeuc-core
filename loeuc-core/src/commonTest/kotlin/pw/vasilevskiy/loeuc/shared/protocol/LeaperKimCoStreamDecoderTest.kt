package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fixtures are verbatim bytes from capture `20260812_152341`, the run where the link carried both
 * producers without losing a byte, so every packet below is known intact.
 */
class LeaperKimCoStreamDecoderTest {
    /** One whole packet: header, 54 fields, 166 bytes of payload. */
    private val packetHex =
        "807f7f2836a60040d0007e2fedfca075f0f4f4f9f0f8f2fff0f5f8f4f8f0f4f4f8f4f4f1f5f0f5f0f0f4" +
            "0000406440e8000040e8000000000000400840084098400c40f000400c400200000000000000000000" +
            "006a104004bcbc989c7035fc02a403ac03a403ac02a402ac02a402a402ac02b002b00a4001bde040d44" +
            "06040e80010074a35263726374044000000000000000040e81803800200ffff030032777009f0f4f4f8" +
            "f4f4f0f4f4f1f4"

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun aWholePacketDecodesIntoFortySamplesOfEveryField() {
        val decoder = LeaperKimCoStreamDecoder()

        val packets = decoder.ingest(hex(packetHex))

        assertEquals(1, packets.size)
        assertEquals(54, packets[0].fieldCount)
        assertEquals(40, packets[0].recordCount)
        assertEquals(0, decoder.damagedPacketCount)
    }

    @Test
    fun theDecodeLandsExactlyOnTheDeclaredLength() {
        // The format carries no per-field length, so a wrong reading desynchronises and the block
        // stops ending where the header said it would. Accepting the packet at all is the assertion.
        val decoder = LeaperKimCoStreamDecoder()

        assertEquals(1, decoder.ingest(hex(packetHex)).size)
        assertEquals(1, decoder.packetCount)
    }

    @Test
    fun everyTransmittedValueIsAMultipleOfFour() {
        val decoder = LeaperKimCoStreamDecoder()
        val packet = decoder.ingest(hex(packetHex)).single()

        var transmitted = 0
        for (record in 0 until packet.recordCount) {
            for (field in 0 until packet.fieldCount) {
                if (!packet.isTransmitted(record, field)) continue
                transmitted++
                // The encoder clears the two low bits of every sample to carry the tag, so a decoded
                // value that is not a multiple of four means the tag was read as data.
                assertEquals(0, packet.value(record, field) % 4, "record $record field $field")
            }
        }
        assertEquals(75, transmitted)
    }

    @Test
    fun fieldEighteenHoldsTheValueTheTextPrintsAsVq() {
        val decoder = LeaperKimCoStreamDecoder()
        val packet = decoder.ingest(hex(packetHex)).single()

        // Field 18 is `0x2000BE50`, which the `StR` text of the same capture prints as `VQ`; across
        // that capture the text read 134..214 and this stream read 0..220 with the same median.
        val series = packet.series(18)
        assertEquals(40, series.size)
        assertTrue(series.all { it == 152 }, series.toList().toString())
    }

    @Test
    fun aSampleNobodySentIsMarkedReconstructed() {
        val decoder = LeaperKimCoStreamDecoder()
        val packet = decoder.ingest(hex(packetHex)).single()

        // 75 of 2160 samples were carried: on a parked wheel almost every field sits inside its
        // deadband, which is why the stream looks small. Telling the two apart is what keeps a
        // predicted value from being read as a measurement.
        val reconstructed = (0 until packet.recordCount).sumOf { record ->
            (0 until packet.fieldCount).count { !packet.isTransmitted(record, it) }
        }
        assertEquals(40 * 54 - 75, reconstructed)
    }

    @Test
    fun aPacketSplitAcrossNotificationsIsStillDecoded() {
        val bytes = hex(packetHex)
        val decoder = LeaperKimCoStreamDecoder()

        // Notifications are 20 bytes, so a 173-byte packet always arrives in pieces.
        var packets = 0
        for (chunk in bytes.asIterable().chunked(20)) {
            packets += decoder.ingest(chunk.toByteArray()).size
        }
        assertEquals(1, packets)
    }

    @Test
    fun aHeaderSplitAcrossNotificationsIsStillFound() {
        val bytes = hex(packetHex)
        val decoder = LeaperKimCoStreamDecoder()

        assertEquals(0, decoder.ingest(bytes.copyOfRange(0, 2)).size)
        assertEquals(1, decoder.ingest(bytes.copyOfRange(2, bytes.size)).size)
    }

    @Test
    fun textBetweenPacketsIsSkippedRatherThanParsed() {
        val bytes = hex(packetHex)
        val text = "  1234>VQ    30.40Cmo\r\n\r\n".encodeToByteArray()
        val decoder = LeaperKimCoStreamDecoder()

        // The two producers share one byte stream and the text lands between packets, 384 bytes of
        // it once per record. It must cost nothing.
        val packets = decoder.ingest(text + bytes + text)

        assertEquals(1, packets.size)
        assertEquals(0, decoder.damagedPacketCount)
    }

    @Test
    fun aPayloadThatLostBytesIsCountedDamagedRatherThanDecodedIntoNumbers() {
        val bytes = hex(packetHex)
        // Cut four bytes out of the middle, keeping the declared length: this is what a ring
        // overflow looks like, and it is exactly the case that must not produce plausible numbers.
        val damaged = bytes.copyOfRange(0, 60) + bytes.copyOfRange(64, bytes.size) +
            byteArrayOf(0, 0, 0, 0)
        val decoder = LeaperKimCoStreamDecoder()

        val packets = decoder.ingest(damaged)

        assertEquals(0, packets.size)
        assertEquals(1, decoder.damagedPacketCount)
    }

    @Test
    fun predictionStateIsDroppedAfterADamagedPacket() {
        val bytes = hex(packetHex)
        val damaged = bytes.copyOfRange(0, 60) + bytes.copyOfRange(64, bytes.size) +
            byteArrayOf(0, 0, 0, 0)
        val decoder = LeaperKimCoStreamDecoder()

        decoder.ingest(damaged)
        val packet = decoder.ingest(bytes).single()

        // Values carry across packets, so after a packet we could not read every field is unknown
        // until it transmits again. Starting from zero is what makes that visible rather than
        // silently continuing from a value that was never confirmed.
        assertEquals(1, decoder.damagedPacketCount)
        assertEquals(1, decoder.packetCount)
        assertTrue(packet.isTransmitted(0, 0))
    }

    @Test
    fun twoPacketsInOneFeedBothCameOut() {
        val bytes = hex(packetHex)
        val decoder = LeaperKimCoStreamDecoder()

        val packets = decoder.ingest(bytes + bytes)

        assertEquals(2, packets.size)
        // The second decodes against the first's ending values, which is what the wheel's encoder
        // assumes: `prevA` lives in RAM and is never re-sent.
        assertEquals(2, decoder.packetCount)
    }

    @Test
    fun resetForgetsEverything() {
        val decoder = LeaperKimCoStreamDecoder()
        decoder.ingest(hex(packetHex))

        decoder.reset()

        assertEquals(0, decoder.packetCount)
        assertEquals(1, decoder.ingest(hex(packetHex)).size)
    }
}
