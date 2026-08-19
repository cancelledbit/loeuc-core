package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeaperKimStrFieldCatalogTest {
    @Test
    fun everyTagAppearsOnce() {
        val duplicates = LeaperKimStrFieldCatalog.all
            .groupingBy { it.tag }
            .eachCount()
            .filterValues { it > 1 }

        assertEquals(emptyMap(), duplicates)
    }

    @Test
    fun everyEntryCarriesBothLanguages() {
        for (field in LeaperKimStrFieldCatalog.all) {
            assertTrue(field.ru.isNotBlank(), "${field.tag} has no Russian description")
            assertTrue(field.en.isNotBlank(), "${field.tag} has no English description")
        }
    }

    @Test
    fun theMeasuredFieldsAreMarkedConfirmed() {
        // These are the ones an actual capture or the firmware settled. If any of them ever slips to
        // a lower confidence the UI would start hedging about things we know.
        for (tag in listOf("dKM", "AA", "AB", "AC", "V", "Cmo", "Cci", "FdHz", "Out", "HzPid")) {
            val field = assertNotNull(LeaperKimStrFieldCatalog.describe(tag), "$tag is missing")
            assertEquals(
                LeaperKimStrFieldConfidence.Confirmed,
                field.confidence,
                "$tag should be confirmed",
            )
        }
    }

    @Test
    fun cmtSaysWhatItIsNot() {
        val field = assertNotNull(LeaperKimStrFieldCatalog.describe("Cmt"))

        // The one field where the useful description is a negative: it looks like the motor
        // temperature and is not one, and a user reading `Cmt` next to `Cci` deserves to know.
        assertEquals(LeaperKimStrFieldConfidence.Confirmed, field.confidence)
        assertTrue(field.en.contains("Not the motor temperature"), field.en)
    }

    @Test
    fun anUnknownTagHasNoDescriptionRatherThanAnInventedOne() {
        assertNull(LeaperKimStrFieldCatalog.describe("Zzz"))
        assertNull(LeaperKimStrFieldCatalog.describe(""))
    }

    @Test
    fun theCatalogueCoversTheWholeMainPage() {
        // The 40 names of the page the wheel starts on, verbatim from dump 20260811_125239. This is
        // the page a user looks at first, so a gap here is the most visible kind.
        val mainPage = listOf(
            "IM", "Fs", "VQ", "VD", "Vqd", "Out", "Bem", "RA", "ChgAD", "CM", "CdA", "TC", "FTC",
            "LDpp", "FdHz", "dKM", "MdKM", "GC", "CC", "Lm1", "U1", "U2", "U3", "U4", "U5", "V",
            "Vpp", "Vnp", "Vmx", "Vmi", "Cmo", "Cmt", "Vp", "AA", "AB", "AC", "AP", "AQ", "AD", "AI",
        )

        val missing = mainPage.filter { LeaperKimStrFieldCatalog.describe(it) == null }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun theCatalogueCoversTheMotorAndTemperaturePages() {
        val motorPage = listOf(
            "Ha", "Pole", "rAB", "di1", "di2", "Dif", "HPS", "Di", "JA", "IA", "FA", "AC", "BC",
            "CC", "ApS", "BpS", "CpS", "AD", "BD", "CD", "Dir", "SDr", "isH", "DS", "LDpp", "6Dpp",
            "HdHz", "DerC", "Her8", "dpOis", "fyOis", "diOis",
        )
        val temperaturePage = listOf(
            "Top", "Cnt", "TA", "FA", "IA", "LwC", "CTr", "VQ", "VD", "Vqd", "Cres", "Gres", "mWH",
            "Vgd", "Cmo", "AQ", "Asc", "Wht", "Wcm", "Wma", "Cci", "Cma", "OfC",
        )

        val missing = (motorPage + temperaturePage)
            .filter { LeaperKimStrFieldCatalog.describe(it) == null }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun allThirtySixCellsAreDescribed() {
        for (index in 1..36) {
            val tag = index.toString().padStart(2, '0')
            val field = assertNotNull(LeaperKimStrFieldCatalog.describe(tag), "cell $tag is missing")
            assertEquals("мВ", field.unit)
        }
    }
}
