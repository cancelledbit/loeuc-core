package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Protocol fixtures are pasted as hex straight out of a capture, so every test that carries one
 * needs this. Shared rather than copied per file: the point of a verbatim fixture is that nobody
 * retypes bytes, and that goes for the decoder too.
 */
internal fun String.decodeHex(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(length / 2) { index ->
        val high = this[index * 2].digitToInt(16)
        val low = this[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}
