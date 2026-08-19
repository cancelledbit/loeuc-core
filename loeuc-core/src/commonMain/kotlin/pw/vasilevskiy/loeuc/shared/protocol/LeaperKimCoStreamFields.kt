package pw.vasilevskiy.loeuc.shared.protocol

/**
 * One of the 54 fields the co-stream writer samples, as far as anyone has established.
 *
 * [tag] is what the screen shows. Where a field has been matched to a name the `StR` text prints,
 * that name is used; otherwise the tag is the field's index, because inventing a label for a RAM
 * address would dress a guess up as a reading.
 */
data class LeaperKimCoStreamField(
    val index: Int,
    val tag: String,
    /** The RAM address the writer samples, from the firmware. Always known - it is what defines it. */
    val source: String,
    val ru: String?,
    val en: String?,
    val confidence: LeaperKimStrFieldConfidence?,
    /**
     * What the `StR` printer divides the raw word by before showing it, read from the same
     * instructions that gave the name. 1 where the printer shows the raw word.
     */
    val divisor: Int = 1,
)

/**
 * The co-stream field table, read out of the writer at `0x0801B07C` instruction by instruction.
 *
 * The writer is a straight run of 54 load-store pairs: each field's value goes to `[r0, #2n]` and
 * its deadband to `[r1, #2n]`, with `r1 = 0x200080CC`. Walking it settles both the address and the
 * width, and it caught two errors in the table this file used to carry - fields 7-9 are `float`
 * loads from `0x20000224/28/2C`, not words at `0x20000260/70/80`, and field 48 is field 47 shifted
 * rather than a second address. The other 48 entries reproduce exactly.
 *
 * Unlike the `StR` text, this set is **fixed**: the writer samples the same 54 addresses every time,
 * and `CHANGESHOWPAGE` does not reach it. The page byte `0x20000031` has exactly one reference in
 * the whole image and it is not in this writer, so there is no rotation to plan, no dwell to trade
 * off and no field that goes stale - every one of the 54 arrives 500 times a second, together.
 *
 * ## How the names were established
 *
 * By address, not by correlation. The `StR` text printer at `0x08023E10` is a twelve-way switch on
 * the page byte, and each arm is a straight run of `printf(fmt, value)` calls: the format string
 * carries the name the rider sees, and the instruction feeding it carries the RAM address the value
 * was read from. Walking that function per page recovers 187 name-address pairs, and 27 of those
 * addresses are ones the co-stream writer samples. An equal address is an identification - the two
 * producers read the same word - so these names are facts about the firmware rather than readings
 * of a capture.
 *
 * The method validates on the one field that had been checked value for value against the text
 * beforehand: it puts `ChgAD` on field 43, which is where the capture had put it.
 *
 * It also **refuted** three earlier labels. Field 18 was marked `VQ`; the printer reads `VQ` from
 * `0x2000BE56` and field 18 samples `0x2000BE50`, so it is a neighbour in the same structure that
 * correlated at 0.98 because everything in that structure tracks motor demand. Field 48 was marked
 * `VD`; `0x200002D8` is `CdA`. Fields 7-9 were marked as filtered accelerometer axes; the writer
 * reads them as floats from addresses the printer shows under no name at all. Correlation on a
 * parked wheel produced all three, which is why the address is the evidence and the capture is not.
 *
 * The remaining 27 are unnamed here on purpose. The printer never shows them, and they have no
 * literal-pool reference anywhere in the image - the code reaches them through a base pointer - so
 * naming them needs the structures decompiled, not another capture. See
 * `docs/leaperkim-firmware-re-notes.md`.
 *
 * ## Two caveats that matter to a consumer
 *
 * - The co-stream samples 16 bits. Where the printer reads a wider word - `TC` on field 16 - the
 *   co-stream carries the low half only, and it wraps.
 * - [divisor] is the printer's scaling, so `AA` at 123 is 12.3 A. It is not a unit conversion the
 *   wheel applies internally.
 */
object LeaperKimCoStreamFields {
    const val Count: Int = 54

    private fun field(
        index: Int,
        source: String,
        tag: String? = null,
        ru: String? = null,
        en: String? = null,
        confidence: LeaperKimStrFieldConfidence? = null,
        divisor: Int = 1,
    ) = LeaperKimCoStreamField(
        index = index,
        tag = tag ?: "f%02d".format2(index),
        source = source,
        ru = ru,
        en = en,
        confidence = confidence,
        divisor = divisor,
    )

    val all: List<LeaperKimCoStreamField> = listOf(
        field(0, "0x20004DDC"),
        field(1, "0x20000232", "GX", "Гироскоп X", "Gyroscope X", Confirmed),
        field(2, "0x20000234", "GY", "Гироскоп Y", "Gyroscope Y", Confirmed),
        field(3, "0x20000236", "GZ", "Гироскоп Z", "Gyroscope Z", Confirmed),
        field(4, "0x20000216"),
        field(5, "0x20000218"),
        field(6, "0x2000021A"),
        // The only three the writer reads as `float` rather than as a word: it loads each with
        // `vldr` and truncates through `vcvt.s32.f32`, so the co-stream carries the whole part and
        // nothing else. The printer shows none of the three under any name.
        field(7, "0x20000224"),
        field(8, "0x20000228"),
        field(9, "0x2000022C"),
        field(10, "0x2000024C", "p", "Тангаж", "Pitch", Confirmed),
        field(11, "0x20000248", "r", "Крен", "Roll", Confirmed),
        field(12, "0x20000250", "y", "Рыскание", "Yaw", Confirmed),
        field(13, "0x20000256", "GGY", null, null, Confirmed),
        field(14, "0x20005E84", "Ptb", null, null, Confirmed),
        field(15, "0x20005E86"),
        // 32-bit at the source; the co-stream carries the low half.
        field(16, "0x20005E8C", "TC", null, null, Confirmed),
        field(17, "0x200005B2", "FTC", null, null, Confirmed),
        field(18, "0x2000BE50"),
        field(19, "0x2000BE34", "AA", "Ток фазы A", "Phase A current", Confirmed, divisor = 10),
        field(20, "0x2000BE36", "AB", "Ток фазы B", "Phase B current", Confirmed, divisor = 10),
        field(21, "0x2000BE38", "AC", "Ток фазы C", "Phase C current", Confirmed, divisor = 10),
        field(22, "0x2000BE42"),
        field(23, "0x2000BE44"),
        field(24, "0x2000BE46", "AQ", "Ток по оси q", "q-axis current", Confirmed, divisor = 10),
        field(25, "0x2000BEBC"),
        field(26, "0x2000BEBD"),
        field(27, "0x2000BEBE"),
        field(28, "0x2000BED0", "isH", null, null, Confirmed),
        field(29, "0x2000BF06", "LDpp", null, null, Confirmed),
        field(30, "0x2000BEF8"),
        field(31, "0x2000BEFC"),
        field(32, "0x2000BF00"),
        field(33, "0x2000BE70"),
        field(34, "0x2000BE78"),
        field(35, "0x2000BE76"),
        field(36, "0x2000BE7A"),
        field(37, "0x20000210", "AX", "Акселерометр X", "Accelerometer X", Confirmed),
        field(38, "0x20000212", "AY", "Акселерометр Y", "Accelerometer Y", Confirmed),
        field(39, "0x20000214", "AZ", "Акселерометр Z", "Accelerometer Z", Confirmed),
        field(40, "0x200005B6", "Se", null, null, Confirmed),
        field(41, "0x20005E94", "V", "Напряжение батареи", "Battery voltage", Confirmed, divisor = 100),
        field(42, "0x20005E98", "Vf", null, null, Confirmed, divisor = 100),
        field(43, "0x20006086", "ChgAD", "АЦП зарядного входа", "Charge input ADC", Confirmed),
        field(44, "0x20005EA4", "Vn", null, null, Confirmed, divisor = 100),
        field(45, "0x2000058A", "AI", null, null, Confirmed, divisor = 10),
        // The one field the writer transforms rather than copies: it ships `(word shr 2) - 128`.
        // The printer never names this address either, and what it does with it does not settle the
        // meaning - `dKM` is computed as `u16[0x2000BDFA] * u16[0x200000C6]`, which reads like a
        // constant, while an offset-and-shift reads like a sensor. Unresolved, so unnamed.
        field(46, "0x200000C6"),
        // Fields 47 and 48 are the same address at two scales: the writer ships the word, then
        // ships it again shifted right by four. `CdA2` therefore arrives already coarsened, and
        // carries no divisor of its own - dividing here would scale it twice. The suffix is there
        // because a chart is keyed on the tag and the two cannot share one.
        field(47, "0x200002D8", "CdA", null, null, Confirmed),
        field(48, "0x200002D8", "CdA2", null, null, Confirmed),
        field(49, "0x2000021C"),
        field(50, "0x2000021E"),
        field(51, "0x20000220"),
        field(52, "0x2000023E"),
        field(53, "0x20000258"),
    )

    private val byIndex: Map<Int, LeaperKimCoStreamField> = all.associateBy { it.index }

    fun at(index: Int): LeaperKimCoStreamField? = byIndex[index]

    /** The tag to show for [index]; never null, because the index itself is always a valid label. */
    fun tagOf(index: Int): String = byIndex[index]?.tag ?: "f%02d".format2(index)

    /**
     * [raw] in the units the `StR` text shows, so a chart of `AA` reads in amps rather than in
     * tenths. Unscaled fields pass through unchanged.
     */
    fun scaled(index: Int, raw: Int): Double {
        val divisor = byIndex[index]?.divisor ?: 1
        return if (divisor == 1) raw.toDouble() else raw.toDouble() / divisor
    }

    fun describe(index: Int, ru: Boolean): String? =
        byIndex[index]?.let { if (ru) it.ru else it.en }
}

private val Confirmed = LeaperKimStrFieldConfidence.Confirmed
private val Likely = LeaperKimStrFieldConfidence.Likely

/** `String.format` is JVM-only, and this catalogue is common code. */
private fun String.format2(value: Int): String {
    val digits = value.toString()
    return replace("%02d", if (digits.length >= 2) digits else "0$digits")
}
