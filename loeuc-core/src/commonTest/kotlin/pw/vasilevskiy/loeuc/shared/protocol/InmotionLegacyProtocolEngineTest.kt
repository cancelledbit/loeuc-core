package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expected values are the ones WheelLog asserts for the same captures; WheelLog truncates speed
 * to 0.01 km/h, so speed is compared within that.
 */
class InmotionLegacyProtocolEngineTest {
    private fun identity(chunks: List<String>) =
        assertNotNull(InmotionLegacyDecoder.decodeIdentity(InmotionLegacyCaptures.message(chunks)))

    private fun settings(chunks: List<String>) =
        assertNotNull(InmotionLegacyDecoder.decodeSettings(InmotionLegacyCaptures.message(chunks)))

    private fun telemetry(chunks: List<String>, model: InmotionLegacyModel) =
        assertNotNull(InmotionLegacyDecoder.decodeTelemetry(InmotionLegacyCaptures.message(chunks), model))

    @Test
    fun v5fSlowInfoGivesIdentityAndOnlyTheSettingsItCarries() {
        val identity = identity(InmotionLegacyCaptures.v5fSlow)
        assertEquals("1271285CBA76001B", identity.serialNumber)
        assertEquals("1.3.506", identity.firmwareVersion)
        assertEquals("52", identity.modelId)
        assertEquals(InmotionLegacyModel.V5F, identity.model)
        assertEquals("V5F", identity.displayName)

        val settings = settings(InmotionLegacyCaptures.v5fSlow)
        assertEquals(25, settings.maxSpeedKmh)
        assertEquals(false, settings.headlightOn)
        assertEquals(0.0, settings.pedalTiltDegrees)
        // 124 bytes: too short for volume, hardness, handle button, LED and ride mode.
        assertNull(settings.speakerVolume)
        assertNull(settings.pedalHardness)
        assertNull(settings.handleButtonDisabled)
        assertNull(settings.decorativeLightOn)
        assertNull(settings.rideModeClassic)
    }

    @Test
    fun v8fAndV8sSlowInfoDecode() {
        val v8f = identity(InmotionLegacyCaptures.v8fSlow)
        assertEquals("14604A5EBD9B000E", v8f.serialNumber)
        assertEquals("2.2.21", v8f.firmwareVersion)
        assertEquals(InmotionLegacyModel.V8F, v8f.model)
        assertEquals(
            InmotionLegacySettings(
                maxSpeedKmh = 45, headlightOn = false, pedalTiltDegrees = 0.0, speakerVolume = 100,
                pedalHardness = 100, handleButtonDisabled = false, decorativeLightOn = true, rideModeClassic = false,
            ),
            settings(InmotionLegacyCaptures.v8fSlow),
        )

        val v8s = identity(InmotionLegacyCaptures.v8sSlow)
        assertEquals("1571AA5EBD460106", v8s.serialNumber)
        assertEquals("102.2.21", v8s.firmwareVersion)
        assertEquals(InmotionLegacyModel.V8S, v8s.model)
        assertEquals(
            InmotionLegacySettings(
                maxSpeedKmh = 35, headlightOn = false, pedalTiltDegrees = 8.0, speakerVolume = 100,
                pedalHardness = 100, handleButtonDisabled = false, decorativeLightOn = false, rideModeClassic = true,
            ),
            settings(InmotionLegacyCaptures.v8sSlow),
        )

        val escaped = identity(InmotionLegacyCaptures.escapedChecksumSlow)
        assertEquals("14611A5E7CDA0001", escaped.serialNumber)
        assertEquals(InmotionLegacyModel.V8F, escaped.model)
        assertEquals("2.2.21", escaped.firmwareVersion)
    }

    @Test
    fun v5fFastInfoDecodes() {
        val t = telemetry(InmotionLegacyCaptures.v5fFast, InmotionLegacyModel.V5F)
        assertEquals(3.82, t.speedKmh, 0.01)
        assertEquals(82.13, t.voltage, 1e-9)
        assertEquals(-0.20, t.current, 1e-9)
        assertEquals(82.13 * -0.20, t.power, 1e-9)
        assertEquals(26, t.temperature)
        assertEquals(0, t.secondTemperature)
        assertEquals(1303324.0, t.totalDistanceMeters)
        assertEquals(0.0, t.tripDistanceMeters)
        assertEquals(97, t.batteryPercent)
        assertEquals(0.2499847412109375, t.pitchDegrees)
        assertEquals(5.588888888888889, assertNotNull(t.rollDegrees), 1e-12)
        assertEquals(0x21, t.workModeRaw)
        assertEquals(InmotionLegacyWorkMode.DRIVE, t.workMode)
        assertEquals("Drive", t.workModeText)
        assertNull(t.engineOff)
    }

    @Test
    fun v8fFastInfoDecodes() {
        val first = telemetry(InmotionLegacyCaptures.v8fFast1, InmotionLegacyModel.V8F)
        assertEquals(1.37, first.speedKmh, 0.01)
        assertEquals(83.82, first.voltage, 1e-9)
        assertEquals(-0.05, first.current, 1e-9)
        assertEquals(27, first.temperature)
        assertEquals(36, first.secondTemperature)
        assertEquals(21679.0, first.totalDistanceMeters)
        assertEquals(1.0, first.tripDistanceMeters)
        assertEquals(100, first.batteryPercent)
        assertEquals(0.0099945068359375, first.pitchDegrees)
        // The payload has a non-zero roll field; WheelLog reports 0 for this generation.
        assertNull(first.rollDegrees)
        assertEquals(InmotionLegacyWorkMode.DRIVE, first.workMode)
        assertEquals("Drive", first.workModeText)
        assertEquals(false, first.engineOff)

        val second = telemetry(InmotionLegacyCaptures.v8fFast2, InmotionLegacyModel.V8F)
        assertEquals(0.66, second.speedKmh, 0.01)
        assertEquals(83.59, second.voltage, 1e-9)
        assertEquals(-0.10, second.current, 1e-9)
        assertEquals(28, second.temperature)
        assertEquals(36, second.secondTemperature)
        assertEquals(21687.0, second.totalDistanceMeters)
        assertEquals(9.0, second.tripDistanceMeters)
        assertEquals(0.079986572265625, second.pitchDegrees)
    }

    @Test
    fun v8sFastInfoTreatsTheImplausibleSecondTemperatureAsAbsent() {
        val t = telemetry(InmotionLegacyCaptures.v8sFast, InmotionLegacyModel.V8S)
        assertEquals(0.0, t.speedKmh)
        assertEquals(81.98, t.voltage, 1e-9)
        assertEquals(0.07, t.current, 1e-9)
        assertEquals(30, t.temperature)
        assertEquals(-110, t.secondTemperatureRaw)
        assertNull(t.secondTemperature)
        assertEquals(1199.0, t.totalDistanceMeters)
        assertEquals(96, t.batteryPercent)
        assertEquals(-0.0699920654296875, t.pitchDegrees)
        assertEquals("Drive", t.workModeText)
    }

    @Test
    fun speedFactorAndRollDependOnTheModel() {
        val message = InmotionLegacyCaptures.message(InmotionLegacyCaptures.v5fFast)
        val r1s = assertNotNull(InmotionLegacyDecoder.decodeTelemetry(message, InmotionLegacyModel.R1S))
        assertEquals(4055 / 1000.0 * 3.6, r1s.speedKmh, 1e-9)
        val v10 = assertNotNull(InmotionLegacyDecoder.decodeTelemetry(message, InmotionLegacyModel.V10))
        assertNull(v10.rollDegrees)
        // 0x21 read as the modern word: high part 2 is drive, low nibble 1 is engine off.
        assertEquals("Drive - Engine off", v10.workModeText)
        assertEquals(true, v10.engineOff)
    }

    @Test
    fun temperaturesAreSignedAndShortPayloadsLeaveFieldsNull() {
        fun fast(size: Int): InmotionLegacyMessage {
            val extra = ByteArray(size).also {
                it[32] = (-5).toByte()
                it[34] = (-12).toByte()
                it[24] = 0x10 // 0x1F10 = 79.52 V
                it[25] = 0x1F
            }
            return InmotionLegacyMessage(
                id = InmotionLegacyIds.FAST_INFO,
                data = byteArrayOf(size.toByte(), 0, 0, 0, 0, 0, 0, 0),
                length = 0xFE, channel = 0, format = 1, type = 0, extra = extra,
            )
        }
        val winter = assertNotNull(InmotionLegacyDecoder.decodeTelemetry(fast(96), InmotionLegacyModel.V5))
        assertEquals(-5, winter.temperature)
        assertEquals(-12, winter.secondTemperature)
        assertEquals(79.52, winter.voltage, 1e-9)

        val short = assertNotNull(InmotionLegacyDecoder.decodeTelemetry(fast(60), InmotionLegacyModel.V5))
        assertNull(short.rollDegrees)
        assertNull(short.workModeRaw)
        assertNull(short.workModeText)
        assertNull(InmotionLegacyDecoder.decodeTelemetry(fast(51), InmotionLegacyModel.V5))
    }

    @Test
    fun messagesOfTheWrongKindDoNotDecode() {
        val slow = InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sSlow)
        val fast = InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sFast)
        assertNull(InmotionLegacyDecoder.decodeTelemetry(slow, InmotionLegacyModel.V8S))
        assertNull(InmotionLegacyDecoder.decodeIdentity(fast))
        assertNull(InmotionLegacyDecoder.decodeSettings(fast))
        assertNull(InmotionLegacyDecoder.decodeAlert(fast))
    }

    @Test
    fun modelIdUsesByte107OnlyWhenNonZeroAndUnknownIdsFallBackToV8() {
        fun slowWith(b107: Int, b104: Int): InmotionLegacyMessage {
            val extra = ByteArray(133).also { it[104] = b104.toByte(); it[107] = b107.toByte() }
            return InmotionLegacyMessage(
                id = InmotionLegacyIds.SLOW_INFO,
                data = byteArrayOf(133.toByte(), 0, 0, 0, 0, 0, 0, 0),
                length = 0xFE, channel = 2, format = 1, type = 0, extra = extra,
            )
        }
        assertEquals(InmotionLegacyModel.V10F, InmotionLegacyDecoder.decodeIdentity(slowWith(14, 1))?.model)
        assertEquals(InmotionLegacyModel.V10S, InmotionLegacyDecoder.decodeIdentity(slowWith(10, 0))?.model)
        assertEquals(InmotionLegacyModel.GLIDE_3, InmotionLegacyDecoder.decodeIdentity(slowWith(8, 5))?.model)
        assertEquals(InmotionLegacyModel.R1N, InmotionLegacyDecoder.decodeIdentity(slowWith(0, 0))?.model)

        val unknown = assertNotNull(InmotionLegacyDecoder.decodeIdentity(slowWith(5, 6)))
        assertEquals("56", unknown.modelId)
        assertNull(unknown.model)
        assertEquals(InmotionLegacyModel.V8, unknown.decodingModel)
        assertEquals("Unknown model 56", unknown.displayName)
    }

    @Test
    fun batteryCurvesFollowTheModelFamilies() {
        assertEquals(97, InmotionLegacyDecoder.batteryPercent(82.13, InmotionLegacyModel.V5F, false))
        assertEquals(87, InmotionLegacyDecoder.batteryPercent(82.13, InmotionLegacyModel.V5F, true))
        assertEquals(0, InmotionLegacyDecoder.batteryPercent(60.0, InmotionLegacyModel.V8, false))
        assertEquals(88, InmotionLegacyDecoder.batteryPercent(81.5, InmotionLegacyModel.V10, true))
        assertEquals(6, InmotionLegacyDecoder.batteryPercent(67.0, InmotionLegacyModel.V10, true))
        assertEquals(90, InmotionLegacyDecoder.batteryPercent(79.9, InmotionLegacyModel.R2, false))
        assertNull(InmotionLegacyDecoder.batteryPercent(80.0, InmotionLegacyModel.L6, false))
    }

    @Test
    fun realAlertFramesDecode() {
        val engine = InmotionLegacyProtocolEngine()
        val alerts = InmotionLegacyCaptures.alerts.flatMap { engine.consume(it.decodeHex(), 0).alerts }
        assertEquals(listOf(0x28, 0x01), alerts.map { it.alertId })
        assertEquals("Unknown alert", alerts[0].name)
        assertEquals(116, alerts[0].rawValue)
        assertEquals(482, alerts[1].rawValue)
        assertEquals(7413, alerts[1].rawValue2)
        assertNull(alerts[1].voltage)
    }

    @Test
    fun knownAlertIdsCarryTheirParameters() {
        fun alert(data: String) = assertNotNull(
            InmotionLegacyDecoder.decodeAlert(
                InmotionLegacyMessage(InmotionLegacyIds.ALERT, data.decodeHex(), 8, 0, 1, 0, null),
            ),
        )
        val tiltback = alert("0600C8AFA0680000") // limit 45000, speed 26784 counts
        assertEquals("Tiltback", tiltback.name)
        assertEquals(-20536, tiltback.rawValue)
        assertEquals(26784 / 3812.0 * 3.6, assertNotNull(tiltback.speedKmh), 1e-9)
        assertEquals(-20.536, tiltback.tiltbackLimit)

        val lowBattery = alert("200000001C1B0000")
        assertEquals("Low battery", lowBattery.name)
        assertEquals(69.40, assertNotNull(lowBattery.voltage), 1e-9)
        assertNull(lowBattery.speedKmh)

        val highLoad = alert("2600E80300000000")
        assertEquals(1.0, highLoad.current)
        assertEquals(0.0, highLoad.speedKmh)

        assertEquals(12.5, alert("0500E20400000000").tiltAngleDegrees)
        assertEquals("Fall down", alert("1900000000000000").name)
        assertEquals("Bad battery cell", alert("1D00000060180000").name)
    }

    @Test
    fun sessionSendsPinAtMostSixTimesThenSlowInfo() {
        val session = InmotionLegacySession()
        var now = 0L
        repeat(InmotionLegacySession.MAX_PIN_ATTEMPTS) { attempt ->
            assertContentEquals(InmotionLegacyRequests.pin(), session.nextRequest(now), "attempt $attempt")
            assertNull(session.nextRequest(now + 249))
            now += 250
        }
        assertEquals(6, session.pinAttempts)
        assertFalse(session.pinAcknowledged)
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(now))
        assertEquals(InmotionLegacySessionPhase.SLOW_INFO, session.phase)
    }

    @Test
    fun sessionWalksPinSlowFastWithImmediateRepollAndFallback() {
        val session = InmotionLegacySession("1234")
        assertContentEquals(InmotionLegacyRequests.pin("1234"), session.nextRequest(0))
        assertNull(session.nextRequest(100))

        session.onMessage(InmotionLegacyMessage(InmotionLegacyIds.PIN_CODE, ByteArray(8).also { it[0] = 1 }, 4, 2, 0, 0, null), 120)
        assertTrue(session.pinAcknowledged)
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(120))

        // Slow info repeats until a reply yields an identity.
        assertNull(session.nextRequest(369))
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(370))
        session.onMessage(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sSlow), 400)
        assertEquals(InmotionLegacyModel.V8S, session.identity?.model)
        assertContentEquals(InmotionLegacyRequests.fastInfo(), session.nextRequest(400))

        // No reply: the 250 ms fallback.
        assertFalse(session.isTelemetryFlowing(500))
        assertNull(session.nextRequest(649))
        assertContentEquals(InmotionLegacyRequests.fastInfo(), session.nextRequest(650))

        // A fast reply makes the next poll due at once.
        session.onMessage(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sFast), 700)
        assertTrue(session.isTelemetryFlowing(700))
        assertContentEquals(InmotionLegacyRequests.fastInfo(), session.nextRequest(701))
        assertNull(session.nextRequest(702))
        assertFalse(session.isTelemetryFlowing(1701))

        session.reset()
        assertFalse(session.pinAcknowledged)
        assertNull(session.identity)
        assertEquals(0, session.pinAttempts)
        assertContentEquals(InmotionLegacyRequests.pin("1234"), session.nextRequest(702))
    }

    @Test
    fun failedWriteIsRetriedAfter125msWithoutCountingAPinAttempt() {
        val session = InmotionLegacySession()
        assertNotNull(session.nextRequest(0))
        session.onWriteFailed(10)
        assertEquals(0, session.pinAttempts)
        assertNull(session.nextRequest(134))
        assertContentEquals(InmotionLegacyRequests.pin(), session.nextRequest(135))
        assertEquals(1, session.pinAttempts)
    }

    @Test
    fun engineReplaysARealV8sConnection() {
        val engine = InmotionLegacyProtocolEngine()
        var pinReplies = 0
        var identity: InmotionLegacyIdentity? = null
        var telemetry: InmotionLegacyTelemetry? = null
        val ids = mutableListOf<Int>()
        for ((time, hex) in InmotionLegacyCaptures.v8sConnect) {
            val update = engine.consume(hex.decodeHex(), time)
            ids += update.messages.map { it.id }
            if (update.pinReplied) pinReplies++
            update.identity?.let { identity = it }
            update.telemetry?.let { telemetry = it }
        }
        assertEquals(
            listOf(
                InmotionLegacyIds.WHEEL_HELLO, InmotionLegacyIds.WHEEL_HELLO, InmotionLegacyIds.WHEEL_HELLO,
                InmotionLegacyIds.PIN_CODE, InmotionLegacyIds.PIN_CODE,
                InmotionLegacyIds.SLOW_INFO, InmotionLegacyIds.SLOW_INFO,
                InmotionLegacyIds.FAST_INFO,
            ),
            ids,
        )
        assertEquals(2, pinReplies)
        assertTrue(engine.session.pinAcknowledged)
        assertEquals(InmotionLegacyModel.V8S, identity?.model)
        val t = assertNotNull(telemetry)
        assertEquals(InmotionLegacyModel.V8S, t.model)
        assertEquals(81.98, t.voltage, 1e-9)
        assertTrue(engine.session.isTelemetryFlowing(914))
        assertEquals(InmotionLegacySessionPhase.FAST_INFO, engine.session.phase)
        assertContentEquals(InmotionLegacyRequests.fastInfo(), engine.nextRequest(915))

        engine.reset()
        assertNull(engine.session.identity)
        // Reset also drops a half-received frame.
        engine.consume(InmotionLegacyCaptures.v8sFast.first().decodeHex(), 1000)
        engine.reset()
        assertTrue(engine.consume(InmotionLegacyCaptures.v8sFast.drop(1).joinToString("").decodeHex(), 1001).messages.isEmpty())
    }

    @Test
    fun engineDecodesTelemetryWithV8ScalingBeforeIdentity() {
        val engine = InmotionLegacyProtocolEngine()
        val update = engine.consume(InmotionLegacyCaptures.wire(InmotionLegacyCaptures.v5fFast), 0)
        assertEquals(InmotionLegacyModel.V8, update.telemetry?.model)
        // With V8 rules the roll field is still read, unlike the modern generation.
        assertEquals(5.588888888888889, assertNotNull(update.telemetry?.rollDegrees), 1e-12)
    }

    /** A session past PIN and identity, polling fast info. */
    private fun identifiedSession(): InmotionLegacySession {
        val session = InmotionLegacySession()
        session.nextRequest(0)
        session.onMessage(InmotionLegacyMessage(InmotionLegacyIds.PIN_CODE, ByteArray(8), 4, 2, 0, 0, null), 10)
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(10))
        session.onMessage(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sSlow), 20)
        assertContentEquals(InmotionLegacyRequests.fastInfo(), session.nextRequest(20))
        return session
    }

    @Test
    fun queuedCommandGoesBeforeTheKeepAliveThenSlowInfoIsReadAgain() {
        val session = identifiedSession()
        val light = InmotionLegacyCommands.headlight(true)
        val volume = InmotionLegacyCommands.speakerVolume(55)
        assertTrue(session.enqueue(light))
        assertTrue(session.enqueue(volume))
        assertEquals(2, session.pendingCommandCount)

        // Pacing is unchanged: nothing goes out before the slot is due.
        assertNull(session.nextRequest(100))
        assertEquals(InmotionLegacySessionPhase.COMMAND, session.phase)
        assertContentEquals(light, session.nextRequest(270))
        assertEquals(InmotionLegacySessionPhase.SLOW_INFO, session.phase)
        // The wheel's acknowledgement makes the next slot due at once: slow info, not the next command.
        session.onMessage(InmotionLegacyMessage(InmotionLegacyCommands.HEADLIGHT_ID, ByteArray(8), 8, 5, 0, 0, null), 280)
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(280))
        // Until slow info answers, it keeps being asked for.
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(530))
        session.onMessage(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sSlow), 540)
        assertContentEquals(volume, session.nextRequest(540))
        session.onMessage(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sFast), 550)
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(550))
        session.onMessage(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sSlow), 560)
        assertEquals(0, session.pendingCommandCount)
        assertContentEquals(InmotionLegacyRequests.fastInfo(), session.nextRequest(560))
    }

    @Test
    fun commandsWaitForPinAndIdentity() {
        val session = InmotionLegacySession()
        assertTrue(session.enqueue(InmotionLegacyCommands.headlight(false)))
        assertContentEquals(InmotionLegacyRequests.pin(), session.nextRequest(0))
        session.onMessage(InmotionLegacyMessage(InmotionLegacyIds.PIN_CODE, ByteArray(8), 4, 2, 0, 0, null), 10)
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(10))
        // An acknowledgement before identity changes nothing.
        session.onMessage(InmotionLegacyMessage(InmotionLegacyCommands.HEADLIGHT_ID, ByteArray(8), 8, 5, 0, 0, null), 20)
        assertContentEquals(InmotionLegacyRequests.slowInfo(), session.nextRequest(20))
        session.onMessage(InmotionLegacyCaptures.message(InmotionLegacyCaptures.v5fSlow), 30)
        assertContentEquals(InmotionLegacyCommands.headlight(false), session.nextRequest(30))
    }

    @Test
    fun failedCommandWriteIsRetriedFirst() {
        val session = identifiedSession()
        val light = InmotionLegacyCommands.headlight(true)
        session.enqueue(light)
        session.enqueue(InmotionLegacyCommands.rideMode(classic = true))
        assertContentEquals(light, session.nextRequest(300))
        session.onWriteFailed(300)
        assertEquals(2, session.pendingCommandCount)
        assertEquals(InmotionLegacySessionPhase.COMMAND, session.phase)
        assertNull(session.nextRequest(424))
        assertContentEquals(light, session.nextRequest(425))
    }

    @Test
    fun sessionRefusesForbiddenMalformedAndExcessCommands() {
        val session = identifiedSession()
        fun wire(id: Int, data: String) = InmotionLegacyFraming.encode(InmotionLegacyMessage(id, data.decodeHex(), 8, 5, 0, 0, null))
        assertFalse(session.enqueue(wire(InmotionLegacyCommands.CALIBRATION_ID, "3254769800000000")))
        assertFalse(session.enqueue(wire(InmotionLegacyCommands.REMOTE_CONTROL_ID, "b200000005000000")))
        assertFalse(session.enqueue(wire(InmotionLegacyCommands.REMOTE_CONTROL_ID, "b200000003000000")))
        assertFalse(session.enqueue(wire(InmotionLegacyCommands.REMOTE_CONTROL_ID, "b200000012000000")))
        assertFalse(session.enqueue(wire(InmotionLegacyCommands.PIN_CHANGE_ID, "3132333435360000")))
        val light = InmotionLegacyCommands.headlight(true)
        assertFalse(session.enqueue(light.copyOf(light.size - 1)))
        assertFalse(session.enqueue(light + light))
        repeat(InmotionLegacySession.MAX_PENDING_COMMANDS) { assertTrue(session.enqueue(light)) }
        assertFalse(session.enqueue(light))
        session.reset()
        assertEquals(0, session.pendingCommandCount)
    }

    @Test
    fun engineBuildsOnlyWhatTheConnectedWheelReportsAndReadsItBack() {
        val engine = InmotionLegacyProtocolEngine()
        assertNull(engine.buildSetting(InmotionLegacySetting.HEADLIGHT, 1))
        engine.consume(InmotionLegacyCaptures.wire(InmotionLegacyCaptures.v5fSlow), 0)
        assertEquals(
            listOf(InmotionLegacySetting.HEADLIGHT, InmotionLegacySetting.MAX_SPEED, InmotionLegacySetting.PEDAL_TILT),
            engine.writableSettings(),
        )
        assertNull(engine.buildSetting(InmotionLegacySetting.SPEAKER_VOLUME, 50))
        assertNull(engine.buildSetting(InmotionLegacySetting.MAX_SPEED, 26))
        assertContentEquals(InmotionLegacyCommands.maxSpeed(25), engine.buildSetting(InmotionLegacySetting.MAX_SPEED, 25))

        // A synthetic slow info with the headlight byte set replaces the settings the engine holds.
        val real = InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sSlow)
        val extra = real.extra!!.copyOf().also { it[80] = 1 }
        val lit = InmotionLegacyFraming.encode(InmotionLegacyMessage(real.id, real.data, real.length, real.channel, real.format, real.type, extra))
        assertTrue(engine.enqueue(assertNotNull(engine.buildSetting(InmotionLegacySetting.HEADLIGHT, 1))))
        val update = engine.consume(lit, 100)
        assertEquals(true, update.settings?.headlightOn)
        assertEquals(1, InmotionLegacyCommands.currentValue(InmotionLegacySetting.HEADLIGHT, assertNotNull(engine.settings)))
        assertEquals(InmotionLegacySetting.entries, engine.writableSettings())
        engine.reset()
        assertNull(engine.settings)
        assertEquals(0, engine.session.pendingCommandCount)
    }
}
