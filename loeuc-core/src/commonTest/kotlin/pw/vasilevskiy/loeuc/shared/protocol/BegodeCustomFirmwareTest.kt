package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Firmware family detection from the answer to `V`, and what the two custom firmwares change.
 *
 * The version and name replies are WheelLog's captured bytes (`GotwayAdapterTest`, "decode name
 * ver data"). The stock frames are WheelLog's captured 2020-board and new-board frames. There is
 * no public capture of a Freestyl3r or SmirnoV wheel, so their frames are built here from the
 * offsets WheelLog's `GotwayAdapter` reads, and say so.
 */
class BegodeCustomFirmwareTest {

    @Test
    fun theVersionReplyFromWheelLogsCaptureNamesStockFirmware() {
        val engine = BegodeProtocolEngine()

        engine.consume(hex("475732303032303031"))
        engine.consume(hex("4e414d453a45584e0d0a"))
        engine.consume(hex("204d505536353030b3f5cabcbbafb3c9b9a620"))

        val firmware = assertNotNull(engine.firmware())
        assertEquals(BegodeFirmwareFamily.Begode, firmware.family)
        assertEquals("GW", firmware.prefix)
        assertEquals("2002001", firmware.version)
        assertEquals("EXN", engine.modelName())
    }

    @Test
    fun everyKnownPrefixMapsToItsFamily() {
        fun family(reply: String) = BegodeIdentityReplies.firmware(reply.encodeToByteArray())?.family

        assertEquals(BegodeFirmwareFamily.Begode, family("GW2002001"))
        assertEquals(BegodeFirmwareFamily.Begode, family("JL1234567"))
        assertEquals(BegodeFirmwareFamily.ExtremeBull, family("JN2002001"))
        assertEquals(BegodeFirmwareFamily.Freestyl3r, family("CF2002001"))
        assertEquals(BegodeFirmwareFamily.SmirnoV, family("BF2002001\r\n"))
        assertEquals(BegodeFirmwareFamily.Freestyl3r, family("  CF2002001"))
    }

    @Test
    fun textThatOnlyLooksLikeAReplyIsNotOne() {
        fun firmware(reply: String) = BegodeIdentityReplies.firmware(reply.encodeToByteArray())

        assertNull(firmware("GWx2002001"), "a version starts with a digit")
        assertEquals("2002001", firmware("GW2002001UZ")?.version, "frame bytes after it are not version")
        assertNull(firmware("CF12"), "too short to be a version")
        assertNull(firmware("XX2002001"), "unknown prefix")
        assertNull(firmware("NAME:CF2002001"), "a name is not a version")
        assertNull(BegodeIdentityReplies.firmware(hex("55aa19c1000000000000008cf0000001fff80018")))
    }

    @Test
    fun aReplyGluedBetweenOtherBytesIsFoundAndOnlyItsOwnBytesAreTaken() {
        val buffer = hex("5a5a5a5a") + "CF2002001".encodeToByteArray() + hex("55aa0000")

        val range = assertNotNull(BegodeIdentityReplies.locateFirmware(buffer))

        assertEquals(4, range.first)
        assertEquals(12, range.last)
        assertEquals("CF2002001", BegodeIdentityReplies.firmware(buffer, range)?.label)
        assertNull(BegodeIdentityReplies.locateFirmware(hex("5a5a5a5a55aa0000") + "CFx2002001".encodeToByteArray()))
    }

    @Test
    fun theFirstAnswerSticks() {
        val engine = BegodeProtocolEngine()

        engine.consume("CF2002001".encodeToByteArray())
        engine.consume("GW2002001".encodeToByteArray())

        assertEquals(BegodeFirmwareFamily.Freestyl3r, engine.firmware()?.family)
    }

    /**
     * A wheel that names stock firmware decodes exactly as a wheel that names none: the same
     * captured frames, the same numbers in every field but the firmware itself.
     */
    @Test
    fun stockFirmwareDecodesExactlyAsBefore() {
        val legacy = listOf(
            hex("55AA19C1000000000000008CF0000001FFF80018"),
            hex("5A5A5A5A55AA000060D248001C20006400010007"),
            hex("000804185A5A5A5A"),
        )
        val modern = listOf(
            hex("55aa17750538007602eefb64f494148100090018"),
            hex("5a5a5a5a55aa0032000004b10000000013880000"),
            hex("000001005a5a5a5a55aa00000000000000000000"),
            hex("00000000000003005a5a5a5a55aa003c278c4900"),
            hex("1c2000c800000000001204185a5a5a5a55aa022c"),
            hex("000000000000000000000000000007185a5a5a5a"),
        )
        listOf(legacy, modern).forEach { chunks ->
            val silent = BegodeProtocolEngine()
            val stock = BegodeProtocolEngine().apply { consume("GW2002001".encodeToByteArray()) }
            val bull = BegodeProtocolEngine().apply { consume("JN2002001".encodeToByteArray()) }
            var silentState: BegodeTelemetry? = null
            var stockState: BegodeTelemetry? = null
            var bullState: BegodeTelemetry? = null
            repeat(2) {
                chunks.forEach { chunk ->
                    silent.consume(chunk)?.let { silentState = it }
                    stock.consume(chunk)?.let { stockState = it }
                    bull.consume(chunk)?.let { bullState = it }
                }
            }

            val expected = assertNotNull(silentState)
            assertTrue(expected.speedKmh.isFinite(), "the capture must decode to something")
            assertEquals(expected, assertNotNull(stockState).copy(firmware = null))
            assertEquals(expected, assertNotNull(bullState).copy(firmware = null))
        }
    }

    /** Stock firmware keeps settings bits at `@14`; only custom firmware has PWM there. */
    @Test
    fun freestyl3rReadsTheMotorPwmFromTheLiveFrame() {
        val frame = legacyLiveFrame(speedRaw = 500, phaseRaw = 1_500).apply { putInt16Be(14, -425) }

        val stock = requireNotNull(BegodeProtocolEngine().consume(frame))
        val custom = BegodeProtocolEngine().run {
            consume("CF2002001".encodeToByteArray())
            requireNotNull(consume(frame))
        }

        assertEquals(42.5, custom.pwmPercent, 0.0001)
        assertTrue(abs(stock.pwmPercent - 42.5) > 1.0, "stock must not read @14 as PWM")
        assertEquals(stock.speedKmh, custom.speedKmh)
        assertEquals(stock.phaseCurrent, custom.phaseCurrent)
        assertEquals(stock.temperature, custom.temperature)
    }

    @Test
    fun freestyl3rOnAModernWheelPrefersAProvenPowerFrame() {
        val engine = BegodeProtocolEngine()
        engine.consume("CF2002001".encodeToByteArray())
        engine.consume(frame(type = 1, page = 0).apply { putUInt16Be(6, 1_220) })
        val live = frame(type = 0, page = 24).apply {
            putInt16Be(4, 1_000)
            putInt16Be(14, 300)
        }

        assertEquals(30.0, requireNotNull(engine.consume(live)).pwmPercent, 0.0001)

        engine.consume(frame(type = 7, page = 24).apply { putInt16Be(8, 55) })
        assertEquals(55.0, requireNotNull(engine.consume(live)).pwmPercent, 0.0001)
    }

    @Test
    fun smirnoVReadsItsOwnUnitsFromTheLiveFrame() {
        val engine = BegodeProtocolEngine()
        engine.consume("BF1000001".encodeToByteArray())
        val frame = legacyLiveFrame(speedRaw = 1_000, phaseRaw = 450).apply {
            this[7] = 0x01
            putInt16Be(8, 1_234)
            putInt16Be(12, 3_339)
            putInt16Be(14, 612)
            this[16] = 2
        }

        val telemetry = requireNotNull(engine.consume(frame))

        assertEquals(12.34, telemetry.current, 0.0001)
        assertEquals(45.0, telemetry.phaseCurrent, 0.0001)
        assertEquals(3_339 / 333.87 + 21.0, telemetry.temperature, 0.0001)
        assertEquals(61.2, telemetry.pwmPercent, 0.0001)
        assertEquals(telemetry.voltage * 12.34, telemetry.power, 0.0001)
        assertEquals(2, telemetry.smirnovTrick)
        assertTrue(telemetry.tripDistanceMeters.isNaN(), "@8 is current here, not a trip counter")
    }

    @Test
    fun smirnoVWithoutTheCurrentFlagFallsBackToTheEstimate() {
        val engine = BegodeProtocolEngine()
        engine.consume("BF1000001".encodeToByteArray())
        val frame = legacyLiveFrame(speedRaw = 1_000, phaseRaw = 450).apply {
            putInt16Be(8, 1_234)
            putInt16Be(14, 500)
        }

        val telemetry = requireNotNull(engine.consume(frame))

        assertEquals(45.0 * 0.5, abs(telemetry.current), 0.0001)
    }

    /**
     * On SmirnoV, `type=1 @6` is the pedal mode. A mode of 2 with `@7 = 0xFF` reads as a 76.7 V
     * pack to the stock decoder, which is exactly how the wheel would be taken for a modern one.
     */
    @Test
    fun smirnoVPedalModeIsNotMistakenForAPackVoltage() {
        val pedal = frame(type = 1, page = 0).apply {
            this[6] = 0x02
            this[7] = 0xFF.toByte()
        }
        val stock = BegodeProtocolEngine()
        val custom = BegodeProtocolEngine().apply { consume("BF1000001".encodeToByteArray()) }

        assertEquals(76.7, requireNotNull(stock.consume(pedal)).voltage, 0.0001)
        val telemetry = requireNotNull(custom.consume(pedal))
        assertTrue(telemetry.voltage.isNaN())
        assertEquals(2, telemetry.smirnovPedalMode)
    }

    @Test
    fun smirnoVSettingsFrameDecodesEveryField() {
        val engine = BegodeProtocolEngine()
        engine.consume("BF1000001".encodeToByteArray())
        val settings = frame(type = 0xFF, page = 0).apply {
            (2..17).forEach { offset -> this[offset] = (offset * 3).toByte() }
            this[2] = 0x01
            this[4] = 0x00
            this[6] = 0x03
        }

        val decoded = assertNotNull(requireNotNull(engine.consume(settings)).smirnovSettings)

        assertEquals(
            BegodeSmirnovSettings(
                extremeMode = true,
                brakingCurrent = 9,
                rotationControl = false,
                rotationAngleDegrees = 15 + 260,
                advancedSettings = true,
                proportionalFactor = 21,
                integralFactor = 24,
                differentialFactor = 27,
                dynamicCompensation = 30,
                dynamicCompensationFilter = 33,
                accelerationCompensation = 36,
                currentQProportional = 42,
                currentQIntegral = 45,
                currentDProportional = 48,
                currentDIntegral = 51,
            ),
            decoded,
        )
        assertNull(
            BegodeProtocolEngine().consume(settings)?.smirnovSettings,
            "a wheel that has not named SmirnoV firmware does not get its settings frame read",
        )
    }

    @OptIn(ExperimentalWriteApi::class)
    @Test
    fun freestyl3rGetsThePwmTiltbackSwitchWithTheWheelsOwnValue() {
        val status = frame(type = 4, page = 24).apply { putUInt16Be(6, 3 shl 10) }
        val engine = BegodeProtocolEngine()
        engine.consume("CF2002001".encodeToByteArray())
        engine.consume(status)

        val row = engine.settingsCapabilities().single { it.key == BegodeSettingsCatalog.CfPwmTiltbackKey }

        assertEquals(1, row.rawValue)
        assertEquals("toggle", row.kind)
        assertEquals("Ib", ascii(BegodeSettingsCatalog.writeSteps(row.key, 1)))
        assertEquals("ob", ascii(BegodeSettingsCatalog.writeSteps(row.key, 0)))
        assertEquals(
            "Freestyl3r CF2002001",
            engine.settingsCapabilities().single { it.key == BegodeSettingsCatalog.FirmwareKey }.valueTextEn,
        )
    }

    @Test
    fun theCustomRowsStayOffEveryOtherFirmware() {
        val status = frame(type = 4, page = 24).apply { putUInt16Be(6, 3 shl 10) }
        listOf(null, "GW2002001", "JN2002001", "BF1000001").forEach { reply ->
            val engine = BegodeProtocolEngine()
            reply?.let { engine.consume(it.encodeToByteArray()) }
            engine.consume(status)
            val keys = engine.settingsCapabilities().map { it.key }

            assertTrue(BegodeSettingsCatalog.CfPwmTiltbackKey !in keys, "$reply got the CF switch")
            assertEquals(reply != null, BegodeSettingsCatalog.FirmwareKey in keys)
        }
    }

    @Test
    fun smirnoVRowsAreAllReadOnly() {
        val engine = BegodeProtocolEngine()
        engine.consume("BF1000001".encodeToByteArray())
        engine.consume(frame(type = 1, page = 0).apply { this[6] = 0x03 })
        engine.consume(frame(type = 0xFF, page = 0).apply { this[5] = 40 })

        val rows = engine.settingsCapabilities().filter { it.key.startsWith("begode_bf_") }

        assertEquals(16, rows.size)
        assertTrue(rows.all { it.commandId == null && it.kind == KIND_READONLY })
        assertEquals("Mode 4", rows.single { it.key == BegodeSettingsCatalog.BfPedalModeKey }.valueTextEn)
        assertEquals(300, rows.single { it.key == BegodeSettingsCatalog.BfRotationAngleKey }.rawValue)
    }

    @Test
    fun aNameReplyInsideANotifyDoesNotStopTheQueriesTheEngineCannotAnswer() {
        // The engine records `NAME:` only at the start of a notify; the query loop must follow the
        // same rule, or it stops asking while the model hint stays missing.
        val requests = BegodeIdentityRequests()
        val engine = BegodeProtocolEngine()
        val midNotify = hex("5a5a5a5a") + "NAME:RS".encodeToByteArray()
        requests.onNotify(midNotify, 0L)
        engine.consume(midNotify)
        assertNull(requests.modelName)
        assertNull(engine.modelName())

        val atStart = "NAME:RS".encodeToByteArray()
        requests.onNotify(atStart, 0L)
        engine.consume(atStart)
        assertEquals("RS", requests.modelName)
        assertEquals("RS", engine.modelName())
    }

    @Test
    fun identityQueriesAlternateUntilAnswered() {
        val requests = BegodeIdentityRequests()
        val interval = BegodeIdentityRequests.RETRY_INTERVAL_MILLIS
        val stream = hex("55aa")

        requests.onNotify(stream, 0L)
        assertEquals("N", ascii(requests.nextRequest(0L)))
        requests.onNotify(stream, interval / 2)
        assertNull(requests.nextRequest(interval / 2), "not before the retry interval")
        requests.onNotify(stream, interval)
        assertEquals("V", ascii(requests.nextRequest(interval)))

        requests.onNotify("NAME:RS\r\n".encodeToByteArray(), 2 * interval)
        assertEquals("V", ascii(requests.nextRequest(2 * interval)), "the name is known, only V is left")

        requests.onNotify(hex("475732303032303031"), 3 * interval)
        assertNull(requests.nextRequest(3 * interval))
        assertTrue(requests.isFinished)
        assertEquals("RS", requests.modelName)
        assertEquals("2002001", requests.firmware?.version)
    }

    @Test
    fun aWheelThatNeverAnswersIsAskedABoundedNumberOfTimes() {
        val requests = BegodeIdentityRequests()
        val sent = (0 until 1_000).mapNotNull { tick ->
            requests.onNotify(hex("55aa"), tick * 100L)
            requests.nextRequest(tick * 100L)
        }

        assertEquals(2 * BegodeIdentityRequests.MAX_ATTEMPTS, sent.size)
        assertEquals(BegodeIdentityRequests.MAX_ATTEMPTS, sent.count { it.single() == BegodeIdentityRequests.VersionQuery })
        assertTrue(sent.all { it.single() == 'N'.code.toByte() || it.single() == 'V'.code.toByte() })
        assertTrue(requests.isFinished)
    }

    @Test
    fun aFailedWriteDoesNotCountAsAnAttempt() {
        val requests = BegodeIdentityRequests()
        val interval = BegodeIdentityRequests.RETRY_INTERVAL_MILLIS

        requests.onNotify(hex("55aa"), 0L)
        requests.nextRequest(0L)
        requests.onWriteFailed(10L)

        assertEquals(0, requests.nameAttempts)
        requests.onNotify(hex("55aa"), 50L)
        assertNull(requests.nextRequest(50L))
        requests.onNotify(hex("55aa"), 10L + interval)
        assertEquals("N", ascii(requests.nextRequest(10L + interval)))
    }

    private fun legacyLiveFrame(speedRaw: Int, phaseRaw: Int): ByteArray = frame(type = 0, page = 24).apply {
        putUInt16Be(2, 6_000)
        putInt16Be(4, speedRaw)
        putInt16Be(10, phaseRaw)
    }

    private fun frame(type: Int, page: Int): ByteArray = ByteArray(24).apply {
        this[0] = 0x55
        this[1] = 0xAA.toByte()
        this[18] = type.toByte()
        this[19] = page.toByte()
        (20..23).forEach { this[it] = 0x5A }
    }

    private fun ascii(steps: List<BegodeWriteStep>?): String =
        steps.orEmpty().map { it.byte.toInt().toChar() }.joinToString("")

    private fun ascii(bytes: ByteArray?): String? = bytes?.decodeToString()

    private fun abs(value: Double): Double = kotlin.math.abs(value)

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.putInt16Be(offset: Int, value: Int) {
        val raw = value and 0xffff
        this[offset] = ((raw ushr 8) and 0xff).toByte()
        this[offset + 1] = (raw and 0xff).toByte()
    }

    private fun ByteArray.putUInt16Be(offset: Int, value: Int) {
        this[offset] = ((value ushr 8) and 0xff).toByte()
        this[offset + 1] = (value and 0xff).toByte()
    }
}
