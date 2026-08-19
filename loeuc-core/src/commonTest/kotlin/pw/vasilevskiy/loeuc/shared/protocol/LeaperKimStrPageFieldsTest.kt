package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeaperKimStrPageFieldsTest {
    @Test
    fun everyKnownPageHasFields() {
        for ((page, fields) in LeaperKimStrPageFields.byPage) {
            assertTrue(fields.isNotEmpty(), "$page has no fields")
        }
        // Ten distinct pages were recognised across the captures; Unknown is not one of them.
        assertEquals(10, LeaperKimStrPageFields.byPage.size)
        assertTrue(LeaperKimStrPage.Unknown !in LeaperKimStrPageFields.byPage)
    }

    @Test
    fun aFieldOnSeveralPagesReportsAllOfThem() {
        // Cmo is printed by three pages, which is exactly why a cover has to be computed rather than
        // assumed: picking Cmo alone must not force a visit to any particular one of them.
        val pages = LeaperKimStrPageFields.pagesWith("Cmo")

        assertTrue(LeaperKimStrPage.Main in pages, pages.toString())
        assertTrue(LeaperKimStrPage.TemperatureAndPower in pages, pages.toString())
        assertTrue(pages.size >= 2)
    }

    @Test
    fun oneMetricNeedsOnePage() {
        assertEquals(setOf(LeaperKimStrPage.Main), strPagesCovering(setOf("dKM")))
        assertEquals(
            setOf(LeaperKimStrPage.TemperatureAndPower),
            strPagesCovering(setOf("Cci")),
        )
    }

    @Test
    fun metricsThatShareAPageNeedOnlyThatPage() {
        // Speed and phase currents are all on the main page, so a summary of them must never rotate.
        val cover = strPagesCovering(setOf("dKM", "AA", "AB", "AC", "V", "Cmo"))

        assertEquals(setOf(LeaperKimStrPage.Main), cover)
        assertEquals(LeaperKimStrPagePlan.Hold(LeaperKimStrPage.Main), strPlanFor(cover.let { setOf("dKM", "AA", "AB", "AC", "V", "Cmo") }))
    }

    @Test
    fun theMotorTemperatureForcesASecondPage() {
        // The one combination the whole feature exists for: motor temperature is not on the page that
        // carries speed and currents, so these two together cannot be had without rotating.
        val cover = strPagesCovering(setOf("dKM", "Cci"))

        assertEquals(setOf(LeaperKimStrPage.Main, LeaperKimStrPage.TemperatureAndPower), cover)
        val plan = strPlanFor(setOf("dKM", "Cci"))
        assertTrue(plan is LeaperKimStrPagePlan.Carousel, plan.toString())
        assertEquals(cover, (plan as LeaperKimStrPagePlan.Carousel).pages)
    }

    @Test
    fun theCoverIsTheTrueMinimumNotMerelyASmallOne() {
        // Cmo is on Main, Adc and TemperatureAndPower. Asking for Cmo with dKM must pick Main alone
        // rather than Main plus whichever page happened to be considered first.
        assertEquals(setOf(LeaperKimStrPage.Main), strPagesCovering(setOf("dKM", "Cmo")))

        // Three metrics that pair up two-and-one must not produce three pages.
        val cover = strPagesCovering(setOf("dKM", "AA", "Cci", "Cma"))
        assertEquals(2, cover.size, cover.toString())
    }

    @Test
    fun aNameNothingCarriesIsIgnoredRatherThanBlockingThePlan() {
        // A tag from a page nobody has captured yet, or a typo, must not stop the rest being
        // harvested - and must not silently drag in every page either.
        assertEquals(setOf(LeaperKimStrPage.Main), strPagesCovering(setOf("dKM", "Zzz")))
        assertEquals(emptySet(), strPagesCovering(setOf("Zzz")))
        assertEquals(LeaperKimStrPagePlan.Manual, strPlanFor(setOf("Zzz")))
    }

    @Test
    fun anEmptySelectionSendsNothing() {
        assertEquals(LeaperKimStrPagePlan.Manual, strPlanFor(emptySet()))
    }

    @Test
    fun everyFieldOfEveryPageAppearsInTheCatalogue() {
        // The two tables are built from the same captures, so a name in one and not the other means
        // one of them was edited without the other.
        val missing = LeaperKimStrPageFields.byPage.values
            .flatten()
            .distinct()
            .filter { LeaperKimStrFieldCatalog.describe(it) == null }
            .sorted()

        assertEquals(emptyList(), missing)
    }
}
