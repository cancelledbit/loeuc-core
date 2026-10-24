package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dragy / UBX. The expected command frames were computed outside this codebase (a separate
 * Fletcher implementation over the payloads the u-blox interface description defines), so a
 * shared mistake in [ubxChecksum] cannot make both sides agree. The NAV-STATUS poll is the
 * frame u-blox documentation and every UBX tool print verbatim.
 */
@OptIn(ExperimentalWriteApi::class)
class DragyProtocolTest {

    @Test
    fun pollFramesMatchPublishedBytes() {
        assertEquals("B5 62 01 03 00 00 04 0D", UbxCommands.pollNavStatus().hex())
        assertEquals("B5 62 0A 38 00 00 42 D0", UbxCommands.pollMonRf().hex())
    }

    @Test
    fun measurementRateIsValsetOfCfgRateMeas() {
        assertEquals("B5 62 06 8A 0A 00 00 03 00 00 01 00 21 30 64 00 53 CB", UbxCommands.setMeasurementRate(10)!!.hex())
        assertEquals("B5 62 06 8A 0A 00 00 03 00 00 01 00 21 30 32 00 21 67", UbxCommands.setMeasurementRate(20)!!.hex())
        assertEquals("B5 62 06 8A 0A 00 00 03 00 00 01 00 21 30 28 00 17 53", UbxCommands.setMeasurementRate(25)!!.hex())
        assertNull(UbxCommands.setMeasurementRate(5))
        assertNull(UbxCommands.setMeasurementRate(50))
    }

    @Test
    fun initialTimeCarriesUtcAndTwoSecondAccuracy() {
        // 2026-10-14 07:35:12.345 UTC
        assertEquals(
            "B5 62 13 40 18 00 10 00 00 80 EA 07 0A 0E 07 23 0C 00 40 48 90 14 02 00 00 00 00 00 00 00 68 54",
            UbxCommands.initialTime(1_791_963_312_345L).hex(),
        )
    }

    @Test
    fun initialPositionScalesToDegreesE7AndCentimetres() {
        assertEquals(
            "B5 62 13 40 14 00 01 00 00 00 F0 A8 3B 21 C8 F1 6B 16 F0 3C 00 00 88 13 00 00 5D 3F",
            UbxCommands.initialPosition(55.7558, 37.6173, 156.0, 50.0).hex(),
        )
    }

    @Test
    fun authResponseIsXorAndAndOfChallenge() {
        assertEquals("5A C3 99 42", dragyAuthResponse("5AC3".decodeHex())!!.hex())
        assertEquals("5A C3 99 42", dragyAuthResponse("5AC3FFFF".decodeHex())!!.hex())
        assertNull(dragyAuthResponse("5A".decodeHex()))
    }

    @Test
    fun batteryIsSecondStatusByteWhenItIsAPercentage() {
        assertEquals(84, dragyBatteryPercent("0154".decodeHex()))
        assertNull(dragyBatteryPercent("01C8".decodeHex()))
        assertNull(dragyBatteryPercent("01".decodeHex()))
    }

    @Test
    fun onlyDrg71GetsTheHighRates() {
        assertEquals(listOf(10, 20, 25), dragySupportedRates("DRG71"))
        assertEquals(listOf(10), dragySupportedRates("DRG70"))
        assertEquals(listOf(10), dragySupportedRates(null))
    }

    @Test
    fun recognisesDragyByNameNotByServiceAlone() {
        assertTrue(DragyGatt.looksLikeDragy("DRG71-ABC123", advertisesService = true))
        assertTrue(DragyGatt.looksLikeDragy("DRG70-ABC123", advertisesService = false))
        assertTrue(DragyGatt.looksLikeDragy(null, advertisesService = true))
        assertTrue(!DragyGatt.looksLikeDragy("JL-Badge", advertisesService = true))
        assertTrue(!DragyGatt.looksLikeDragy("LK21652", advertisesService = false))
    }

    @Test
    fun navPvtFieldsComeFromTheirDocumentedOffsets() {
        val fix = UbxDecoder.navPvt(UbxStreamReassembler().consume(sampleNavPvtFrame()).single())
        assertNotNull(fix)
        assertEquals(123_456_789L, fix.iTowMillis)
        assertEquals(1_791_963_312_345L, fix.utcEpochMillis)
        assertEquals(GnssFixType.Fix3D, fix.fixType)
        assertTrue(fix.gnssFixOk)
        assertTrue(fix.isUsable)
        assertEquals(13, fix.satellitesUsed)
        assertClose(37.6173, fix.longitude, 1e-7)
        assertClose(55.7558, fix.latitude, 1e-7)
        assertClose(170.5, fix.heightEllipsoidMeters, 1e-9)
        assertClose(156.25, fix.altitudeMslMeters, 1e-9)
        assertClose(0.9, fix.horizontalAccuracyMeters, 1e-9)
        assertClose(1.4, fix.verticalAccuracyMeters, 1e-9)
        assertClose(12.0, fix.velocityNorthMps, 1e-9)
        assertClose(-5.0, fix.velocityEastMps, 1e-9)
        assertClose(0.25, fix.velocityDownMps, 1e-9)
        assertClose(27.834, fix.groundSpeedMps, 1e-9)
        assertClose(100.2024, fix.groundSpeedKmh, 1e-9)
        assertClose(337.38, fix.headingDegrees, 1e-9)
        assertClose(0.15, fix.speedAccuracyMps, 1e-9)
        assertClose(1.5, fix.headingAccuracyDegrees, 1e-9)
        assertClose(1.23, fix.pDop, 1e-9)
    }

    @Test
    fun noFixIsNotUsableAndHasNoTimeWhenTimeIsInvalid() {
        val frame = sampleNavPvtFrame(fixType = 0, flags = 0, valid = 0)
        val fix = UbxDecoder.navPvt(UbxStreamReassembler().consume(frame).single())!!
        assertEquals(GnssFixType.NoFix, fix.fixType)
        assertTrue(!fix.isUsable)
        assertNull(fix.utcEpochMillis)
    }

    @Test
    fun reassemblesFramesSplitAcrossNotifications() {
        val stream = byteArrayOf(0x00, 0x11) + sampleNavPvtFrame() + UbxCommands.pollNavStatus() + sampleNavPvtFrame()
        val reassembler = UbxStreamReassembler()
        val messages = ArrayList<UbxMessage>()
        // 20-byte notifications, and a cut that lands between the two sync bytes.
        var offset = 0
        for (size in generateSequence(20) { it }.take(100)) {
            if (offset >= stream.size) break
            val end = minOf(stream.size, offset + if (offset == 0) 3 else size)
            messages += reassembler.consume(stream.copyOfRange(offset, end))
            offset = end
        }
        assertEquals(listOf(UbxDecoder.NAV_PVT, UbxDecoder.NAV_STATUS, UbxDecoder.NAV_PVT), messages.map { it.key })
        assertEquals(0, reassembler.rejectedFrames)
    }

    @Test
    fun corruptedFrameIsDroppedAndTheNextOneSurvives() {
        val bad = sampleNavPvtFrame().also { it[40] = (it[40] + 1).toByte() }
        val reassembler = UbxStreamReassembler()
        val messages = reassembler.consume(bad + sampleNavPvtFrame())
        assertEquals(1, messages.size)
        assertEquals(1, reassembler.rejectedFrames)
    }

    @Test
    fun navSatCountsHeardAndUsedSatellites() {
        val payload = ByteArray(8 + 3 * 12)
        payload[5] = 3
        // sat 0: cn0 40, used; sat 1: cn0 30, used; sat 2: cn0 20, heard only
        payload[8 + 2] = 40; payload[8 + 8] = 0x08
        payload[20 + 2] = 30; payload[20 + 8] = 0x08
        payload[32 + 2] = 20
        val summary = UbxDecoder.navSat(UbxMessage(0x01, 0x35, payload))!!
        assertEquals(3, summary.heard)
        assertEquals(2, summary.used)
        assertEquals(40, summary.medianUsedCn0)
        assertEquals(40, summary.maxCn0)
    }

    @Test
    fun interferenceFieldsComeFromStatusAndRfBlocks() {
        val status = ByteArray(16).also { it[7] = (2 shl 3).toByte() }
        assertEquals(2, UbxDecoder.navStatusSpoofState(UbxMessage(0x01, 0x03, status)))
        val rf = ByteArray(4 + 24).also { it[1] = 1; it[4 + 1] = 2; it[4 + 16] = 77 }
        assertEquals(2 to 77, UbxDecoder.monRf(UbxMessage(0x0A, 0x38, rf)))
    }

    /** A NAV-PVT built field by field from the u-blox interface description's offsets. */
    private fun sampleNavPvtFrame(fixType: Int = 3, flags: Int = 0x01, valid: Int = 0x07): ByteArray {
        val p = ByteArray(92)
        fun put(offset: Int, value: Long, size: Int) {
            for (i in 0 until size) p[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        put(0, 123_456_789, 4)
        put(4, 2026, 2); p[6] = 10; p[7] = 14; p[8] = 7; p[9] = 35; p[10] = 12
        p[11] = valid.toByte()
        put(16, 345_000_000, 4)
        p[20] = fixType.toByte()
        p[21] = flags.toByte()
        p[23] = 13
        put(24, 376_173_000, 4)
        put(28, 557_558_000, 4)
        put(32, 170_500, 4)
        put(36, 156_250, 4)
        put(40, 900, 4)
        put(44, 1_400, 4)
        put(48, 12_000, 4)
        put(52, -5_000, 4)
        put(56, 250, 4)
        put(60, 27_834, 4)
        put(64, 33_738_000, 4)
        put(68, 150, 4)
        put(72, 150_000, 4)
        put(76, 123, 2)
        return UbxCommands.frame(0x01, 0x07, p)
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    private fun ByteArray.hex(): String = joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
}
