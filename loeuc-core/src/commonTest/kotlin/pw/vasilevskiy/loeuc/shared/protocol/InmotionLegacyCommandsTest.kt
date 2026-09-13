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
 * Expected frames are the command vectors of WheelLog's `InmotionAdapterTest.kt`, the reference
 * client's own record of the bytes it sends. The official app's builders produce the same bytes
 * wherever the notes say the two agree.
 */
class InmotionLegacyCommandsTest {
    private fun assertFrame(expectedHex: String, actual: ByteArray?) =
        assertContentEquals(expectedHex.decodeHex(), assertNotNull(actual), expectedHex)

    @Test
    fun headlightLedAndHandleButtonMatchTheReferenceVectors() {
        assertFrame("aaaa0d01a5550f010000000000000008050000805555", InmotionLegacyCommands.headlight(true))
        assertFrame("aaaa0d01a5550f0000000000000000080500007f5555", InmotionLegacyCommands.headlight(false))
        assertFrame("aaaa1601a5550fb20000000f00000008050000495555", InmotionLegacyCommands.decorativeLight(true))
        assertFrame("aaaa1601a5550fb200000010000000080500004a5555", InmotionLegacyCommands.decorativeLight(false))
        // WheelLog's builder is called with "handle button disabled": its "on" vector writes 0.
        assertFrame("aaaa2E01a5550f000000000000000008050000a05555", InmotionLegacyCommands.handleButton(enabled = false))
        assertFrame("aaaa2E01a5550f010000000000000008050000a15555", InmotionLegacyCommands.handleButton(enabled = true))
    }

    @Test
    fun maxSpeedIsUnsignedSoTheThirtyFiveKmhVectorHolds() {
        assertFrame("aaaa1501a5550f01000000204e000008050000f65555", InmotionLegacyCommands.maxSpeed(20))
        assertFrame("aaaa1501a5550f01000000a861000008050000915555", InmotionLegacyCommands.maxSpeed(25))
        assertFrame("aaaa1501a5550f010000001027000008050000bf5555", InmotionLegacyCommands.maxSpeed(10))
        // 35 000 does not fit a signed short; WheelLog's vector is its unsigned bytes.
        assertFrame("aaaa1501a5550f01000000b888000008050000c85555", InmotionLegacyCommands.maxSpeed(35))
        val v45 = assertNotNull(InmotionLegacyFraming.decodeFrame(InmotionLegacyCommands.maxSpeed(45)))
        assertEquals(45000L, v45.data.ezU32Le(4))
        assertFailsWith<IllegalArgumentException> { InmotionLegacyCommands.maxSpeed(2) }
        assertFailsWith<IllegalArgumentException> { InmotionLegacyCommands.maxSpeed(66) }
    }

    @Test
    fun tiltVolumeSensitivityAndRideModeMatchTheReferenceVectors() {
        assertFrame("aaaa1501a5550f000000000000000008050000875555", InmotionLegacyCommands.pedalTilt(0))
        assertFrame("aaaa1501a5550f0000000000000800080500008f5555", InmotionLegacyCommands.pedalTilt(80))
        assertFrame("aaaa1501a5550f000000000000f8ff080500007e5555", InmotionLegacyCommands.pedalTilt(-80))
        assertFrame("aaaa0a06a5550f000000000000000008050000815555", InmotionLegacyCommands.speakerVolume(0))
        assertFrame("aaaa0a06a5550f7c1500000000000008050000125555", InmotionLegacyCommands.speakerVolume(55))
        assertFrame("aaaa0a06a5550f102700000000000008050000b85555", InmotionLegacyCommands.speakerVolume(100))
        assertFrame("AAAA1501A5550F060000000004000008050000915555", InmotionLegacyCommands.pedalSensitivity(4))
        assertFrame("AAAA1501A5550F060000004009000008050000D65555", InmotionLegacyCommands.pedalSensitivity(46))
        assertFrame("AAAA1501A5550F0600000000100000080500009D5555", InmotionLegacyCommands.pedalSensitivity(100))
        assertFrame("aaaa1501a5550f0a0000000100000008050000925555", InmotionLegacyCommands.rideMode(classic = true))
        assertFrame("aaaa1501a5550f0a0000000000000008050000915555", InmotionLegacyCommands.rideMode(classic = false))
        assertFailsWith<IllegalArgumentException> { InmotionLegacyCommands.pedalTilt(81) }
        assertFailsWith<IllegalArgumentException> { InmotionLegacyCommands.speakerVolume(101) }
        assertFailsWith<IllegalArgumentException> { InmotionLegacyCommands.pedalSensitivity(3) }
    }

    @Test
    fun hornIsTheOfficialPlaySoundOnTheModelsThatHaveIt() {
        // Same message as WheelLog's play-sound vector (sound 2: AAAA0906A5550F02...82 5555), sound 24.
        val expected = "aaaa0906a5550f180000000000000008050000985555"
        for (model in listOf(InmotionLegacyModel.V5F, InmotionLegacyModel.V8, InmotionLegacyModel.V8F, InmotionLegacyModel.V10F)) {
            assertFrame(expected, InmotionLegacyCommands.horn(model))
        }
        assertNull(InmotionLegacyCommands.horn(InmotionLegacyModel.GLIDE_3))
        assertNull(InmotionLegacyCommands.horn(InmotionLegacyModel.V10T))
        assertNull(InmotionLegacyCommands.horn(InmotionLegacyModel.R1N))
    }

    @Test
    fun checkByteIsEscapedWhenItCollidesWithAMarker() {
        // 28 km/h sums to a check byte of 0x55. The wheel escapes that byte, so this encoder does;
        // WheelLog's sender would put an ambiguous `55 55 55` on the wire here.
        val wire = InmotionLegacyCommands.maxSpeed(28)
        assertFrame("aaaa1501a5550f01000000606d000008050000a5555555", wire)
        val message = assertNotNull(InmotionLegacyFraming.decodeFrame(wire))
        assertEquals(28000L, message.data.ezU32Le(4))
    }

    @Test
    fun modelGatesFollowTheReferenceClient() {
        val allModels = InmotionLegacyModel.entries
        assertEquals(
            setOf(InmotionLegacyModel.V8F, InmotionLegacyModel.V8S) + allModels.filter { it.isV10Family },
            allModels.filter { InmotionLegacyCommands.range(InmotionLegacySetting.RIDE_MODE, it) != null }.toSet(),
        )
        assertEquals(
            allModels.filter { InmotionLegacyCommands.range(InmotionLegacySetting.RIDE_MODE, it) != null }.toSet(),
            allModels.filter { InmotionLegacyCommands.range(InmotionLegacySetting.PEDAL_SENSITIVITY, it) != null }.toSet(),
        )
        assertEquals(
            setOf(InmotionLegacyModel.GLIDE_3, InmotionLegacyModel.V8, InmotionLegacyModel.V8F, InmotionLegacyModel.V8S) +
                allModels.filter { it.isV10Family },
            allModels.filter { InmotionLegacyCommands.range(InmotionLegacySetting.DECORATIVE_LIGHT, it) != null }.toSet(),
        )
        assertEquals(3..25, InmotionLegacyCommands.maxSpeedRange(InmotionLegacyModel.V5F))
        assertEquals(3..35, InmotionLegacyCommands.maxSpeedRange(InmotionLegacyModel.GLIDE_3))
        assertEquals(3..45, InmotionLegacyCommands.maxSpeedRange(InmotionLegacyModel.V10F))
        // WheelLog's 70 for the rest cannot be read back from a 16-bit field.
        assertEquals(3..65, InmotionLegacyCommands.maxSpeedRange(InmotionLegacyModel.R1N))

        assertNull(InmotionLegacyCommands.build(InmotionLegacySetting.RIDE_MODE, 1, InmotionLegacyModel.V5F))
        assertNull(InmotionLegacyCommands.build(InmotionLegacySetting.DECORATIVE_LIGHT, 1, InmotionLegacyModel.V5F))
        assertNull(InmotionLegacyCommands.build(InmotionLegacySetting.MAX_SPEED, 30, InmotionLegacyModel.V5F))
        assertNotNull(InmotionLegacyCommands.build(InmotionLegacySetting.MAX_SPEED, 25, InmotionLegacyModel.V5F))
        assertNull(InmotionLegacyCommands.build(InmotionLegacySetting.HEADLIGHT, 2, InmotionLegacyModel.V8F))
    }

    @Test
    fun onlySettingsTheWheelReportsBackAreWritable() {
        fun writable(chunks: List<String>): List<InmotionLegacySetting> {
            val message = InmotionLegacyCaptures.message(chunks)
            return InmotionLegacyCommands.writableSettings(
                InmotionLegacyDecoder.decodeIdentity(message),
                InmotionLegacyDecoder.decodeSettings(message),
            )
        }
        // 124-byte V5F slow info carries headlight, max speed and tilt only.
        assertEquals(
            listOf(InmotionLegacySetting.HEADLIGHT, InmotionLegacySetting.MAX_SPEED, InmotionLegacySetting.PEDAL_TILT),
            writable(InmotionLegacyCaptures.v5fSlow),
        )
        assertEquals(InmotionLegacySetting.entries, writable(InmotionLegacyCaptures.v8sSlow))
        assertTrue(InmotionLegacyCommands.writableSettings(null, null).isEmpty())

        // An unknown model id writes nothing even though decoding falls back to V8.
        val unknown = InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8fSlow).let { real ->
            val extra = real.extra!!.copyOf().also { it[104] = 99 }
            InmotionLegacyMessage(real.id, real.data, real.length, real.channel, real.format, real.type, extra)
        }
        assertTrue(
            InmotionLegacyCommands.writableSettings(
                InmotionLegacyDecoder.decodeIdentity(unknown),
                InmotionLegacyDecoder.decodeSettings(unknown),
            ).isEmpty(),
        )
    }

    @Test
    fun everyValueReadsBackAsWritten() {
        // Put each builder's value where slow info reports it and decode it again.
        fun readBack(setting: InmotionLegacySetting, value: Int): Int? {
            val message = assertNotNull(InmotionLegacyFraming.decodeFrame(assertNotNull(
                InmotionLegacyCommands.build(setting, value, InmotionLegacyModel.V8S),
            )))
            val extra = InmotionLegacyCaptures.message(InmotionLegacyCaptures.v8sSlow).extra!!.copyOf()
            val field = message.data.ezU32Le(4).toInt()
            when (setting) {
                InmotionLegacySetting.HEADLIGHT -> extra[80] = message.data[0]
                InmotionLegacySetting.HANDLE_BUTTON -> extra[129] = message.data[0]
                InmotionLegacySetting.DECORATIVE_LIGHT -> extra[130] = if (message.data[4].toInt() == 0x0F) 1 else 0
                InmotionLegacySetting.RIDE_MODE -> extra[132] = field.toByte()
                InmotionLegacySetting.SPEAKER_VOLUME -> {
                    val raw = message.data.ezU32Le(0).toInt()
                    extra[125] = raw.toByte()
                    extra[126] = (raw shr 8).toByte()
                }
                InmotionLegacySetting.MAX_SPEED -> {
                    extra[60] = field.toByte()
                    extra[61] = (field shr 8).toByte()
                }
                InmotionLegacySetting.PEDAL_TILT -> for (i in 0 until 4) extra[56 + i] = (field shr (8 * i)).toByte()
                // The wheel stores the top byte of the 16-bit raw value.
                InmotionLegacySetting.PEDAL_SENSITIVITY -> extra[124] = (field shr 5).toByte()
            }
            val slow = InmotionLegacyMessage(InmotionLegacyIds.SLOW_INFO, extraLength(extra.size), 0xFE, 2, 1, 0, extra)
            return InmotionLegacyCommands.currentValue(setting, assertNotNull(InmotionLegacyDecoder.decodeSettings(slow)))
        }
        for (setting in InmotionLegacySetting.entries) {
            val range = assertNotNull(InmotionLegacyCommands.range(setting, InmotionLegacyModel.V8S))
            for (value in range) assertEquals(value, readBack(setting, value), "$setting $value")
        }
    }

    @Test
    fun noBuilderEverProducesCalibrationLockTransportPowerOffOrPinChange() {
        val forbiddenIds = setOf(
            InmotionLegacyCommands.CALIBRATION_ID,
            InmotionLegacyCommands.PIN_CHANGE_ID,
            InmotionLegacyCommands.BERM_ANGLE_ID,
        )
        val produced = buildList {
            for (model in InmotionLegacyModel.entries) {
                for (setting in InmotionLegacySetting.entries) {
                    for (value in (-100..200) + (0..70).map { it * 1000 }) {
                        InmotionLegacyCommands.build(setting, value, model)?.let(::add)
                    }
                }
                InmotionLegacyCommands.horn(model)?.let(::add)
            }
            add(InmotionLegacyRequests.fastInfo())
            add(InmotionLegacyRequests.slowInfo())
            add(InmotionLegacyRequests.pin())
        }
        assertTrue(produced.isNotEmpty())
        for (bytes in produced) {
            val message = assertNotNull(InmotionLegacyFraming.decodeFrame(bytes))
            assertFalse(message.id in forbiddenIds, message.toString())
            assertFalse(InmotionLegacyCommands.isForbidden(message), message.toString())
            if (message.id == InmotionLegacyCommands.REMOTE_CONTROL_ID) {
                assertTrue(message.data.ezU8(4) == 0x0F || message.data.ezU8(4) == 0x10, message.toString())
            }
        }
    }

    @Test
    fun forbiddenFramesAreRecognised() {
        fun frame(id: Int, data: String) = InmotionLegacyMessage(id, data.decodeHex(), 8, 5, 0, 0, null)
        assertTrue(InmotionLegacyCommands.isForbidden(frame(InmotionLegacyCommands.CALIBRATION_ID, "3254769800000000")))
        assertTrue(InmotionLegacyCommands.isForbidden(frame(InmotionLegacyCommands.PIN_CHANGE_ID, "3132333435360000")))
        assertTrue(InmotionLegacyCommands.isForbidden(frame(InmotionLegacyCommands.BERM_ANGLE_ID, "1700000000000000")))
        for (sub in listOf("03", "04", "05", "11", "12", "13", "00")) {
            assertTrue(
                InmotionLegacyCommands.isForbidden(frame(InmotionLegacyCommands.REMOTE_CONTROL_ID, "b2000000${sub}000000")),
                sub,
            )
        }
        assertFalse(InmotionLegacyCommands.isForbidden(frame(InmotionLegacyCommands.REMOTE_CONTROL_ID, "b20000000f000000")))
    }

    private fun extraLength(size: Int) = ByteArray(8).also {
        it[0] = size.toByte()
        it[1] = (size shr 8).toByte()
    }
}
