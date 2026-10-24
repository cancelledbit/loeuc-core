package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/** P6-only writes cleared by three-agent official-app review (route 3, Pro 1.8.00).
 * See docs/inmotion-protocol-notes.md, "P6 charging controls". Currents stay in tenths
 * of an amp throughout the settings API, including the unchanged half of a paired write.
 */
class InmotionP6ChargingCommands {
    private var tab = 1

    @ExperimentalWriteApi
    fun build(key: String, value: Int, ac220: Int?, ac110: Int?): ByteArray? {
        val command: Int
        val parameters: ByteArray
        when (key) {
            ChargeCutoffKey -> {
                if (value !in 60..100 || value % 5 != 0) return null
                command = 0x38
                parameters = byteArrayOf(value.toByte())
            }
            Ac220Key, Ac110Key -> {
                val current220 = if (key == Ac220Key) value else ac220 ?: return null
                val current110 = if (key == Ac110Key) value else ac110 ?: return null
                if (value % 10 != 0 || current220 !in 10..160 || current110 !in 10..80) return null
                command = 0x39
                parameters = byteArrayOf(current220.toByte(), current110.toByte())
            }
            else -> return null
        }
        val body = byteArrayOf(0x02, 0x21, 0x60, command.toByte()) + parameters
        val payload = byteArrayOf(0x56, tab.toByte(), body.size.toByte()) + body
        tab = if (tab == 127) 1 else tab + 1
        val checksum = payload.fold(0) { result, byte -> result xor (byte.toInt() and 0xFF) }.toByte()
        val escaped = (payload + checksum).flatMap { byte ->
            if (byte == 0xAA.toByte() || byte == 0xA5.toByte()) listOf(0xA5.toByte(), byte) else listOf(byte)
        }.toByteArray()
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + escaped
    }

    companion object {
        const val ChargeCutoffKey = "inmotion_charge_cut_off_percent"
        const val Ac220Key = "inmotion_max_charge_current_ac220"
        const val Ac110Key = "inmotion_max_charge_current_ac110"
    }
}
