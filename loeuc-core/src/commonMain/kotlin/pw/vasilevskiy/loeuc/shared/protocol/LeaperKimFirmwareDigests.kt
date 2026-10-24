package pw.vasilevskiy.loeuc.shared.protocol

/**
 * The two digests firmware checking needs, written out so that every platform computes them
 * the same way: `java.util.zip.CRC32` and `MessageDigest` exist on the JVM only.
 */
internal object LeaperKimFirmwareDigests {

    private val crcTable = LongArray(256) { index ->
        var value = index.toLong()
        repeat(8) { value = if (value and 1L != 0L) (value ushr 1) xor 0xEDB88320L else value ushr 1 }
        value
    }

    /** CRC-32/ISO-HDLC, the `zlib.crc32` the `LdCm` header carries at `@08`. */
    fun crc32(bytes: ByteArray, startOffset: Int, endOffset: Int = bytes.size): Long {
        var crc = 0xFFFFFFFFL
        for (index in startOffset until endOffset) {
            crc = crcTable[((crc xor (bytes[index].toLong() and 0xFF)) and 0xFF).toInt()] xor (crc ushr 8)
        }
        return crc xor 0xFFFFFFFFL
    }

    /** The 64 FIPS 180-4 round constants. */
    private val sha256RoundConstants: IntArray = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L,
        0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L,
        0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL,
        0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L,
        0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L,
        0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L,
        0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L,
        0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L,
        0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L,
    ).let { values -> IntArray(values.size) { values[it].toInt() } }

    /** SHA-256 as lower-case hex, the key the firmware manifest indexes builds by. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = sha256(bytes)
        val hex = "0123456789abcdef"
        return buildString(64) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hex[value ushr 4])
                append(hex[value and 0x0F])
            }
        }
    }

    fun sha256(message: ByteArray): ByteArray {
        val h = longArrayOf(
            0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL,
            0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L,
        ).let { values -> IntArray(values.size) { values[it].toInt() } }
        val bitLength = message.size.toLong() * 8
        val paddedSize = ((message.size + 9 + 63) / 64) * 64
        val padded = message.copyOf(paddedSize)
        padded[message.size] = 0x80.toByte()
        for (index in 0 until 8) {
            padded[paddedSize - 1 - index] = (bitLength ushr (8 * index)).toByte()
        }
        val w = IntArray(64)
        for (block in 0 until paddedSize step 64) {
            for (t in 0 until 16) {
                val at = block + t * 4
                w[t] = ((padded[at].toInt() and 0xFF) shl 24) or
                    ((padded[at + 1].toInt() and 0xFF) shl 16) or
                    ((padded[at + 2].toInt() and 0xFF) shl 8) or
                    (padded[at + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (t in 0 until 64) {
                val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) +
                    ((e and f) xor (e.inv() and g)) + sha256RoundConstants[t] + w[t]
                val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) +
                    ((a and b) xor (a and c) xor (b and c))
                hh = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        val out = ByteArray(32)
        for (index in 0 until 8) {
            out[index * 4] = (h[index] ushr 24).toByte()
            out[index * 4 + 1] = (h[index] ushr 16).toByte()
            out[index * 4 + 2] = (h[index] ushr 8).toByte()
            out[index * 4 + 3] = h[index].toByte()
        }
        return out
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}
