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
    fun everyUnitCarriesBothLanguages() {
        // The unit sits next to the value on screen. It used to exist in Russian only, so an
        // English interface printed "0.1 км/ч" under an English description.
        for (field in LeaperKimStrFieldCatalog.all) {
            assertEquals(
                field.unitRu == null,
                field.unitEn == null,
                "${field.tag} has a unit in one language only",
            )
            val en = field.unitEn ?: continue
            assertTrue(en.isNotBlank(), "${field.tag} has a blank English unit")
            assertTrue(
                en.none { it in '\u0400'..'\u04FF' },
                "${field.tag} English unit is not English: $en",
            )
            assertEquals(field.unitRu, field.unit(isRu = true), field.tag)
            assertEquals(field.unitEn, field.unit(isRu = false), field.tag)
        }
    }

    @Test
    fun theKnownUnitsTranslateTheWayTheDocsSpellThem() {
        val expected = mapOf(
            "dKM" to ("0.1 км/ч" to "0.1 km/h"),
            "AA" to ("0.1 А" to "0.1 A"),
            "V" to ("В" to "V"),
            "Cmo" to ("°C" to "°C"),
            "Wht" to ("Втч" to "Wh"),
            "01" to ("мВ" to "mV"),
            "HzPid" to ("Гц" to "Hz"),
            "SkM" to ("км" to "km"),
            "Rtc" to ("с" to "s"),
            "U" to ("%" to "%"),
            "AQ" to ("А" to "A"),
        )
        for ((tag, units) in expected) {
            val field = assertNotNull(LeaperKimStrFieldCatalog.describe(tag), "$tag is missing")
            assertEquals(units.first, field.unitRu, tag)
            assertEquals(units.second, field.unitEn, tag)
        }
    }

    @Test
    fun theDescriptionAccessorFollowsTheLanguageItIsAsked() {
        val field = assertNotNull(LeaperKimStrFieldCatalog.describe("dKM"))
        assertEquals(field.ru, field.description(isRu = true))
        assertEquals(field.en, field.description(isRu = false))
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
            assertEquals("мВ", field.unitRu)
            assertEquals("mV", field.unitEn)
        }
    }
}
