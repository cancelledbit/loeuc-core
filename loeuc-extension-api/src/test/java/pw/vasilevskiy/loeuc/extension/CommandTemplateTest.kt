package pw.vasilevskiy.loeuc.extension

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandTemplateTest {

    private fun template(parameter: TemplateParameter?) = CommandTemplate(
        service = null,
        characteristic = "0000ffe1-0000-1000-8000-00805f9b34fb",
        bytes = byteArrayOf(0xAA.toByte(), 0x55, 0, 0, 0, 0x0F),
        parameter = parameter,
        withResponse = true,
    )

    private fun bytes(build: TemplateBuild): ByteArray = (build as TemplateBuild.Bytes).bytes

    @Test
    fun fixedTemplateSendsItsBytesAndTakesNoValue() {
        val fixed = template(null)
        assertArrayEquals(fixed.bytes, bytes(fixed.build(null)))
        assertEquals(TemplateBuild.Refused(RefusalReason.OUT_OF_BOUNDS), fixed.build(1.0))
    }

    @Test
    fun writesLittleAndBigEndianFields() {
        val little = template(TemplateParameter(2, 2, true, false, 1.0, 0.0, 65535.0))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x55, 0x34, 0x12, 0, 0x0F), bytes(little.build(0x1234.toDouble())))
        val big = template(TemplateParameter(2, 2, false, false, 1.0, 0.0, 65535.0))
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x55, 0x12, 0x34, 0, 0x0F), bytes(big.build(0x1234.toDouble())))
    }

    @Test
    fun appliesScaleAndRounds() {
        val scaled = template(TemplateParameter(2, 1, true, false, 0.5, 0.0, 100.0))
        assertEquals(41.toByte(), bytes(scaled.build(20.4))[2])
    }

    @Test
    fun writesNegativeValuesAsTwosComplement() {
        val signed = template(TemplateParameter(1, 4, true, true, 1.0, -10.0, 10.0))
        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), -2, -1, -1, -1, 0x0F),
            bytes(signed.build(-2.0)),
        )
    }

    @Test
    fun refusesMissingOrOutOfBoundsValues() {
        val ranged = template(TemplateParameter(2, 1, true, false, 1.0, 10.0, 20.0))
        listOf(null, 9.0, 21.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertEquals("$value", TemplateBuild.Refused(RefusalReason.OUT_OF_BOUNDS), ranged.build(value))
        }
    }

    @Test
    fun templateBytesCannotBeChangedFromOutside() {
        val source = byteArrayOf(1, 2, 3)
        val fixed = CommandTemplate(null, "0000ffe1-0000-1000-8000-00805f9b34fb", source, null, true)
        source[0] = 9
        fixed.bytes[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes(fixed.build(null)))
    }
}
