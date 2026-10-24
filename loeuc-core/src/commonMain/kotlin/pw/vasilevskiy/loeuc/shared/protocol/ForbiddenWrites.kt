package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Writes LoEUC never sends to a stock wheel, whoever asks: gyroscope calibration and the
 * bootloader entry. Their bytes are known so they can be recognised and refused, which is what
 * these predicates are for - a caller that assembles bytes it did not write itself, such as a
 * command template declared by a third party, checks them here before the write.
 *
 * Only commands whose bytes two independent readings agree on are listed. A brand missing
 * from here has no such command confirmed, not a permission to send one.
 */
object ForbiddenWrites {

    /**
     * Begode reads its commands as single ASCII bytes. `c` starts gyroscope calibration (it is
     * followed by `y` after a pause, and decouples the motor until the wheel is powered off and
     * levelled by hand); `!` and `@` put the controller into the firmware receiver.
     */
    fun begode(bytes: ByteArray): Boolean {
        val first = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        return first == 'c'.code || first == '!'.code || first == '@'.code
    }

    /**
     * KingSong frames carry the command at byte 16: `0x89` is calibration, and `0x8A` with a
     * sub-command 0..4 in byte 2 is the gyroscope family. Other `0x8A` sub-commands, such as
     * the charge limit (9), are ordinary settings.
     */
    fun kingSong(bytes: ByteArray): Boolean {
        if (bytes.size < 17) return false
        if ((bytes[0].toInt() and 0xFF) != 0xAA || (bytes[1].toInt() and 0xFF) != 0x55) return false
        val command = bytes[16].toInt() and 0xFF
        if (command == 0x89) return true
        return command == 0x8A && (bytes[2].toInt() and 0xFF) in 0..4
    }
}
