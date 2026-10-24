package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The InMotion V2 odometer (total statistics, `0x11`) - the total-distance reading on every V2
 * model, P6 included - and the V11 trip, which the odometer does not replace.
 */
class InmotionTotalStatsTest {

    @Test
    fun totalStatsReadWaitsForTheModel() {
        val engine = InmotionProtocolEngine()
        assertNull(engine.totalStatsCommand())

        engine.consume(mainInfo(V11))
        assertEquals("AAAA14011104", assertNotNull(engine.totalStatsCommand()).hex())
    }

    @Test
    fun p6ReadsTheOdometerTooAndKeepsItsOwnTrip() {
        // P6 answers the V11 layout (same parser in the official app), and its realtime trip
        // slot is a real counter, so only the odometer is added.
        val engine = InmotionProtocolEngine()
        engine.consume(mainInfo(P6))
        assertEquals("AAAA14011104", assertNotNull(engine.totalStatsCommand()).hex())

        engine.consume(totalStats(tensOfMeters = 51_234))
        val telemetry = assertNotNull(
            engine.consume(buildFrame(0x14, 0x04, ByteArray(88).apply { putInt16Le(28, 66) }))?.telemetry,
        )
        assertEquals(512_340.0, telemetry.totalDistanceMeters, 0.01)
        assertEquals(660.0, telemetry.tripDistanceMeters, 0.01)
    }

    /**
     * A rider's V11 on a real ride (`loeuc_C6286FC174E2_unknown_20260918_203339.jsonl`), main
     * board 1.4, 68-byte frames. The trip is `@24` in tens of meters - over the 52 s of that
     * capture it stepped 6 -> 22 at 9.9 m a step against integrated speed - and `@26`, where
     * WheelLog reads it, stayed zero. The frames come from the capture store already unescaped
     * (one carries `AA 06` in its payload), hence `consumeFrame`.
     */
    @Test
    fun aRealV11RideReadsItsTripFromOffset24AndKeepsTheOdometerApart() {
        val engine = InmotionProtocolEngine()
        engine.consumeFrame(RIDER_V11_MODEL.bytes())
        engine.consumeFrame(RIDER_V11_VERSION.bytes())

        val before = assertNotNull(engine.consumeFrame(RIDER_V11_MOVING.bytes())?.telemetry)
        assertEquals("inmotion_v11", before.profileId)
        assertEquals(9.82, before.speedKmh, 0.01)
        assertEquals(160.0, before.tripDistanceMeters, 0.01)
        assertTrue(before.totalDistanceMeters.isNaN())

        engine.consume(totalStats(tensOfMeters = 123_400))
        val after = assertNotNull(engine.consumeFrame(RIDER_V11_MOVING.bytes())?.telemetry)
        assertEquals(1_234_000.0, after.totalDistanceMeters, 0.01)
        assertEquals(160.0, after.tripDistanceMeters, 0.01)
    }

    @Test
    fun theOldV11LayoutKeepsItsOwnTripAndGainsTheOdometer() {
        val engine = InmotionProtocolEngine()
        engine.consume(mainInfo(V11))
        engine.consume(totalStats(tensOfMeters = 5_300))

        val telemetry = assertNotNull(engine.consume(v11LegacyRealtime(tripTens = 123))?.telemetry)
        assertEquals(1_230.0, telemetry.tripDistanceMeters, 0.01)
        assertEquals(53_000.0, telemetry.totalDistanceMeters, 0.01)
    }

    @Test
    fun aV11FrameBetweenTheTwoLayoutsIsNoLongerReadAsAV12() {
        val engine = InmotionProtocolEngine()
        engine.consume(mainInfo(V11))
        val data = ByteArray(53).apply { putInt16Le(12, 42) }

        val telemetry = assertNotNull(engine.consume(buildFrame(0x14, 0x04, data))?.telemetry)
        assertEquals("inmotion_v11", telemetry.profileId)
        assertEquals(420.0, telemetry.tripDistanceMeters, 0.01)
    }

    @Test
    fun aShortTotalStatsAnswerIsIgnored() {
        val engine = InmotionProtocolEngine()
        engine.consume(mainInfo(V11))
        engine.consume(buildFrame(0x14, 0x11, ByteArray(8)))

        val telemetry = assertNotNull(engine.consume(v11NewRealtime())?.telemetry)
        assertTrue(telemetry.totalDistanceMeters.isNaN())
    }

    @Test
    fun resetForgetsTheOdometer() {
        val engine = InmotionProtocolEngine()
        engine.consume(mainInfo(V11))
        engine.consume(totalStats(tensOfMeters = 100))

        engine.reset()
        engine.consume(mainInfo(V11))
        val telemetry = assertNotNull(engine.consume(v11NewRealtime())?.telemetry)
        assertTrue(telemetry.totalDistanceMeters.isNaN())
    }

    private companion object {
        const val V11 = 61
        const val P6 = 131

        // Raywing's V11, `loeuc_C6286FC174E2_unknown_20260918_203339.jsonl`.
        const val RIDER_V11_MODEL = "AAAA11088201020601020100" + "9C"
        const val RIDER_V11_VERSION =
            "AAAA111D820622150002040F000602211800040110000602230300030107000001BE"
        const val RIDER_V11_MOVING =
            "AAAA144584BB1F3702D6038B0CAA06CC015F010000910031FB3B00260010000000F024FC22E02E" +
                "7C15641900000000C7B000C8CECD000028000000000049140000000000000000000066"

        fun String.bytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun mainInfo(modelId: Int): ByteArray =
            buildFrame(0x11, 0x02, byteArrayOf(1, 2, (modelId / 10).toByte(), (modelId % 10).toByte()))

        fun versionBlock(major: Int, minor: Int): ByteArray = buildFrame(
            0x11,
            0x02,
            ByteArray(24).apply {
                this[0] = 0x06
                this[13] = minor.toByte()
                this[14] = major.toByte()
            },
        )

        fun totalStats(tensOfMeters: Long): ByteArray = buildFrame(
            0x14,
            0x11,
            ByteArray(20).apply {
                this[0] = (tensOfMeters and 0xFF).toByte()
                this[1] = ((tensOfMeters shr 8) and 0xFF).toByte()
                this[2] = ((tensOfMeters shr 16) and 0xFF).toByte()
                this[3] = ((tensOfMeters shr 24) and 0xFF).toByte()
            },
        )

        fun v11NewRealtime(): ByteArray = buildFrame(
            0x14,
            0x04,
            ByteArray(68).apply {
                putInt16Le(0, 7_931)
                putInt16Le(28, 7_918)
            },
        )

        fun v11LegacyRealtime(tripTens: Int): ByteArray = buildFrame(
            0x14,
            0x04,
            ByteArray(44).apply {
                putInt16Le(0, 7_363)
                putInt16Le(12, tripTens)
            },
        )

        fun ByteArray.putInt16Le(offset: Int, value: Int) {
            this[offset] = (value and 0xFF).toByte()
            this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        fun buildFrame(flag: Int, command: Int, data: ByteArray): ByteArray {
            val body = byteArrayOf((command or 0x80).toByte()) + data
            val payload = byteArrayOf(flag.toByte(), body.size.toByte()) + body
            val checksum = payload.fold(0) { acc, byte -> acc xor (byte.toInt() and 0xFF) }.toByte()
            return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + payload + byteArrayOf(checksum)
        }

        fun ByteArray.hex(): String = joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
        }
    }
}
