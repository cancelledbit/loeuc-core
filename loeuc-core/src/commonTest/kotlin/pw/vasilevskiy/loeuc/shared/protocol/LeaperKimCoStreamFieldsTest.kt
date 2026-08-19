package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaperKimCoStreamFieldsTest {
    @Test
    fun everyFieldTheWriterSamplesHasAnEntry() {
        assertEquals(LeaperKimCoStreamFields.Count, LeaperKimCoStreamFields.all.size)
        for (index in 0 until LeaperKimCoStreamFields.Count) {
            assertNotNull(LeaperKimCoStreamFields.at(index), "field $index")
        }
    }

    @Test
    fun indicesAreDenseAndInOrder() {
        assertEquals(
            (0 until LeaperKimCoStreamFields.Count).toList(),
            LeaperKimCoStreamFields.all.map { it.index },
        )
    }

    @Test
    fun everyFieldKnowsTheAddressItComesFrom() {
        // The RAM address is the one thing always known - it is what defines the field - so an entry
        // without one would mean the table was filled in from something other than the writer.
        assertTrue(LeaperKimCoStreamFields.all.all { it.source.startsWith("0x2000") })
    }

    @Test
    fun anUnidentifiedFieldIsLabelledByItsIndexRatherThanGuessedAt() {
        val field = assertNotNull(LeaperKimCoStreamFields.at(15))

        assertEquals("f15", field.tag)
        assertNull(field.ru)
        assertNull(field.en)
        // No confidence either: there is no claim to rate.
        assertNull(field.confidence)
    }

    @Test
    fun tagsAreUniqueSoAChartCanBeKeyedOnThem() {
        val tags = LeaperKimCoStreamFields.all.map { it.tag }

        assertEquals(tags.size, tags.toSet().size, tags.toString())
    }

    @Test
    fun namedFieldsAreExactlyTheOnesThePrinterReadsFromTheSameAddress() {
        val confirmed = LeaperKimCoStreamFields.all
            .filter { it.confidence == LeaperKimStrFieldConfidence.Confirmed }
            .map { it.index to it.tag }

        // Every one of these is an equal RAM address between the co-stream writer's field table and
        // an instruction in the `StR` printer at `0x08023E10` that feeds a named format string.
        // Nothing here rests on a capture, so nothing here may be added from one.
        assertEquals(
            listOf(
                1 to "GX", 2 to "GY", 3 to "GZ",
                10 to "p", 11 to "r", 12 to "y",
                13 to "GGY", 14 to "Ptb", 16 to "TC", 17 to "FTC",
                19 to "AA", 20 to "AB", 21 to "AC", 24 to "AQ",
                28 to "isH", 29 to "LDpp",
                37 to "AX", 38 to "AY", 39 to "AZ",
                40 to "Se", 41 to "V", 42 to "Vf", 43 to "ChgAD", 44 to "Vn", 45 to "AI",
                47 to "CdA", 48 to "CdA2",
            ),
            confirmed,
        )
    }

    @Test
    fun theLabelsRefutedByTheAddressesAreGone() {
        // Field 18 carried `VQ` and field 48 carried `VD` from correlating a parked capture, where
        // everything in the motor structure moves together. The printer reads `VQ` from
        // `0x2000BE56`, which no co-stream field samples, and `0x200002D8` is `CdA`.
        assertEquals("f18", LeaperKimCoStreamFields.tagOf(18))
        assertEquals("0x2000BE50", assertNotNull(LeaperKimCoStreamFields.at(18)).source)
        assertNull(LeaperKimCoStreamFields.at(18)?.confidence)
        assertEquals("CdA2", LeaperKimCoStreamFields.tagOf(48))
        // Fields 7-9 were read as filtered accelerometer axes, then briefly as `Kp` and two
        // neighbours. Reading the writer settled it: all three are `float` loads from addresses the
        // table did not even have, and none of the three is printed anywhere.
        assertEquals(listOf("f07", "f08", "f09"), (7..9).map(LeaperKimCoStreamFields::tagOf))
        assertEquals(
            listOf("0x20000224", "0x20000228", "0x2000022C"),
            (7..9).mapNotNull { LeaperKimCoStreamFields.at(it)?.source },
        )
    }

    @Test
    fun aTagIsAlwaysAvailableEvenPastTheTable() {
        assertEquals("AQ", LeaperKimCoStreamFields.tagOf(24))
        assertEquals("f00", LeaperKimCoStreamFields.tagOf(0))
        // A field index the table does not cover still yields a usable label rather than crashing.
        assertEquals("f99", LeaperKimCoStreamFields.tagOf(99))
    }

    @Test
    fun scalingFollowsWhatThePrinterDivides() {
        // `AA` prints as tenths of an amp and `V` as hundredths of a volt; both divisors were read
        // from the printer's own instructions alongside the names.
        assertEquals(12.3, LeaperKimCoStreamFields.scaled(19, 123))
        assertEquals(84.21, LeaperKimCoStreamFields.scaled(41, 8421))
        // A field the printer shows raw passes through untouched, negatives included.
        assertEquals(-750.0, LeaperKimCoStreamFields.scaled(1, -750))
        assertEquals(4.0, LeaperKimCoStreamFields.scaled(99, 4))
    }

    @Test
    fun describeFollowsTheLanguage() {
        assertEquals("Ток по оси q", LeaperKimCoStreamFields.describe(24, ru = true))
        assertEquals("q-axis current", LeaperKimCoStreamFields.describe(24, ru = false))
        // A field with a name but no established meaning describes as nothing rather than as a guess.
        assertNull(LeaperKimCoStreamFields.describe(16, ru = true))
        assertNull(LeaperKimCoStreamFields.describe(15, ru = true))
    }
}
