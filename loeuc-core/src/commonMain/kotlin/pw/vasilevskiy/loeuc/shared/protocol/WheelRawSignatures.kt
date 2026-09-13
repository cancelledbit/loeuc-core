package pw.vasilevskiy.loeuc.shared.protocol

/**
 * The wire formats [WheelRawSignatures] can name from bytes alone.
 *
 * [LeaperKim] is a family, not a brand: NOSFET wheels speak the same `DC 5A 5C` protocol, and
 * nothing in the bytes separates the two. Which of them a wheel is stays a question for its name.
 */
enum class WheelRawFamily { Begode, KingSong, LeaperKim }

/**
 * Recognising Begode, KingSong and LeaperKim/NOSFET from one raw notify, for a wheel whose
 * advertised name did not say what it is.
 *
 * All three brands sit on the same generic FFE0/FFE1 service, so the service cannot tell them
 * apart, and a renamed wheel would otherwise stay with whichever decoder claimed the service.
 * Each signature is built from the frame structure the brand's engine already relies on, and each
 * works on a single notify - usually 20 bytes, which is less than a whole Begode frame.
 *
 * The checks are deliberately stricter than the reassemblers: a reassembler may skip garbage and
 * resync, but a false match here would move a live connection to the wrong decoder. Every
 * signature was run over the recorded dumps of all brands (Begode, KingSong, LeaperKim, NOSFET,
 * InMotion V2, Solowheel Xtreme, both chargers) and over WheelLog's test captures with no
 * cross-brand match.
 */
object WheelRawSignatures {

    /** The family [value] proves, or null when it proves none. */
    fun detectFamily(value: ByteArray): WheelRawFamily? = when {
        matchesKingSong(value) -> WheelRawFamily.KingSong
        matchesLeaperKim(value) -> WheelRawFamily.LeaperKim
        matchesBegode(value) -> WheelRawFamily.Begode
        else -> null
    }

    /**
     * A Begode frame is 24 bytes: `55 AA`, sixteen payload bytes, a frame type at 18, a page at
     * 19, and the `5A 5A 5A 5A` trailer. The wheel streams frames back to back while the BLE module
     * cuts that stream into 20-byte notifies, so a frame can start anywhere inside a notify.
     *
     * Two shapes count, and either one alone is enough:
     * - `5A 5A 5A 5A 55 AA`: the end of one frame running into the start of the next. This is what
     *   most notifies of a drifting stream carry.
     * - `55 AA` with the type and page bytes inside the notify, as a known pair (type 0, 4 or 7 on
     *   page 24; type 1 on pages 0..3; types 2 and 3, the BMS cell pages, on 0..7), and at least
     *   the first two trailer bytes reading `5A`.
     * - A notify that ends a frame: a known type/page pair followed by the whole `5A 5A 5A 5A`
     *   trailer as its last six bytes. This covers a stream whose notifies line up with frame ends.
     *
     * A header whose trailer lies beyond the notify does not count, even with a known pair: that
     * leaves 16 header bits and a pair from a small set, and an InMotion V2 stream - whose decoder
     * never settles - produces such a notify often enough to be moved to Begode mid-ride. Such a
     * wheel is identified one notify later, by the boundary it sends next.
     *
     * Type `0xFF` (a custom-firmware settings frame in WheelLog) is not in the pair list: its page
     * byte has no known range, and without it the header check would rest on 24 bits. Such a
     * wheel still streams ordinary frames, and the next boundary notify identifies it.
     */
    fun matchesBegode(value: ByteArray): Boolean {
        for (index in 0 until value.size - 1) {
            if (value.u8(index) != 0x55 || value.u8(index + 1) != 0xAA) continue
            if (index >= BEGODE_TRAILER_SIZE && value.isBegodeTrailer(index - BEGODE_TRAILER_SIZE)) {
                return true
            }
            if (index + BEGODE_PAGE_OFFSET + BEGODE_MIN_VISIBLE_TRAILER < value.size &&
                isKnownBegodeTypePage(value.u8(index + BEGODE_TYPE_OFFSET), value.u8(index + BEGODE_PAGE_OFFSET)) &&
                value.trailerSoFarIsBegode(index + BEGODE_PAGE_OFFSET + 1)
            ) {
                return true
            }
        }
        val tail = value.size - BEGODE_TRAILER_SIZE
        return tail >= 2 && value.isBegodeTrailer(tail) &&
            isKnownBegodeTypePage(value.u8(tail - 2), value.u8(tail - 1))
    }

    /**
     * A KingSong frame is exactly one 20-byte notify: `AA 55`, fourteen payload bytes, a command
     * at 16, and `5A 5A` at 18..19. The command has to be one the wheel is known to send, which
     * keeps a Begode notify whose bytes happen to line up with the envelope from matching.
     *
     * The name (`0xBB`) and serial (`0xB3`) answers carry text where the `5A 5A` would be, so
     * they do not match; the live frames that arrive several times a second do.
     */
    fun matchesKingSong(value: ByteArray): Boolean {
        if (value.size < KINGSONG_FRAME_SIZE) return false
        return value.u8(0) == 0xAA && value.u8(1) == 0x55 &&
            value.u8(18) == 0x5A && value.u8(19) == 0x5A &&
            value.u8(16) in KINGSONG_RESPONSE_COMMANDS
    }

    /**
     * A LeaperKim (and NOSFET) frame opens with `DC 5A 5C` and a length byte, and the wheel
     * flushes each frame into its own run of notifies, so the marker opens the notify: in every
     * recorded dump of both brands it sat at offset 0. The length byte has to be one a frame can
     * carry - the 36-byte legacy Sherman frame is the shortest (`0x20`), and the longest page seen
     * so far declares `0x5F`; the bound below leaves room for pages not yet recorded.
     *
     * The `StR` diagnostic text and the `80 7F 7F 28` co-stream do not match. A wheel in diagnostic
     * mode is therefore not identified by this signature until it sends a regular frame.
     */
    fun matchesLeaperKim(value: ByteArray): Boolean {
        if (value.size < LEAPERKIM_HEADER_SIZE) return false
        return value.u8(0) == 0xDC && value.u8(1) == 0x5A && value.u8(2) == 0x5C &&
            value.u8(3) in LEAPERKIM_LENGTH_RANGE
    }

    private fun isKnownBegodeTypePage(type: Int, page: Int): Boolean = when (type) {
        0, 4, 7 -> page == BEGODE_LIVE_PAGE
        1 -> page in 0..3
        2, 3 -> page in 0..7
        else -> false
    }

    private fun ByteArray.isBegodeTrailer(start: Int): Boolean =
        (start until start + BEGODE_TRAILER_SIZE).all { offset -> u8(offset) == 0x5A }

    private fun ByteArray.trailerSoFarIsBegode(start: Int): Boolean {
        val end = minOf(size, start + BEGODE_TRAILER_SIZE)
        return (start until end).all { offset -> u8(offset) == 0x5A }
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private const val BEGODE_TYPE_OFFSET = 18
    private const val BEGODE_PAGE_OFFSET = 19
    private const val BEGODE_TRAILER_SIZE = 4
    private const val BEGODE_MIN_VISIBLE_TRAILER = 2
    private const val BEGODE_LIVE_PAGE = 24

    private const val KINGSONG_FRAME_SIZE = 20

    /**
     * Commands a KingSong wheel sends with the `5A 5A` envelope intact: live data (`A9`), trip and
     * fan (`B9`), alarms and top speed (`A4`, `B5`), CPU load and output (`F5`, `F6`), BMS pages
     * (`F1`..`F4`, `E1`..`E6`), the keep-alive (`C9`) and the answer to the initial read (`5F`).
     * Taken from WheelLog's adapter and its captures and from a recorded S22 dump.
     */
    private val KINGSONG_RESPONSE_COMMANDS = setOf(
        0xA4, 0xA9, 0xB5, 0xB9, 0xC9, 0x5F,
        0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6,
        0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6,
    )

    private const val LEAPERKIM_HEADER_SIZE = 4
    private val LEAPERKIM_LENGTH_RANGE = 16..200
}
