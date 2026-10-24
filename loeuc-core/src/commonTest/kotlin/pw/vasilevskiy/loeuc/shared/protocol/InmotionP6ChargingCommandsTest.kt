package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InmotionP6ChargingCommandsTest {
    @Test
    fun matchesTheOfficialAppFramesAndPreservesTheOtherCurrent() {
        val commands = InmotionP6ChargingCommands()
        assertEquals("AAAA560105022160385F76", commands.build("inmotion_charge_cut_off_percent", 95, null, null).hex())
        assertEquals("AAAA56020602216039321E04", commands.build("inmotion_max_charge_current_ac220", 50, 30, 30).hex())
        // Changing the 110 V limit must preserve the reported 5.1 A at 220 V exactly.
        assertEquals("AAAA56030602216039332832", commands.build("inmotion_max_charge_current_ac110", 40, 51, 30).hex())
    }

    @Test
    fun rejectsUnsupportedValuesAndMissingPairedReadingsWithoutAdvancingTheTab() {
        val commands = InmotionP6ChargingCommands()
        for (value in listOf(59, 61, 101)) assertNull(commands.build("inmotion_charge_cut_off_percent", value, null, null))
        for (value in listOf(0, 9, 11, 161)) assertNull(commands.build("inmotion_max_charge_current_ac220", value, 30, 30))
        for (value in listOf(0, 9, 11, 81)) assertNull(commands.build("inmotion_max_charge_current_ac110", value, 30, 30))
        assertNull(commands.build("inmotion_max_charge_current_ac220", 50, 30, null))
        assertNull(commands.build("inmotion_max_charge_current_ac110", 30, null, 30))
        assertNull(commands.build("inmotion_max_charge_current_ac220", 50, 30, 0))
        assertNull(commands.build("other", 95, 30, 30))
        assertEquals("AAAA560105022160385F76", commands.build("inmotion_charge_cut_off_percent", 95, null, null).hex())
    }

    @Test
    fun escapesTheChecksumAndWrapsTheSingleByteTransactionTab() {
        val commands = InmotionP6ChargingCommands()
        repeat(128) { index ->
            val bytes = requireNotNull(commands.build("inmotion_max_charge_current_ac220", 160, 30, 30))
            assertEquals(index % 127 + 1, bytes[3].toInt() and 0xFF)
            assertEquals(160.toByte(), bytes[9])
            if (index == 61) assertEquals("AAAA563E0602216039A01EA5AA", bytes.hex())
        }
    }

    private fun ByteArray?.hex() = this?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
}
