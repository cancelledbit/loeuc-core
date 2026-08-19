package pw.vasilevskiy.loeuc.shared.protocol

/**
 * The diagnostic pages `CHANGESHOWPAGE` cycles through.
 *
 * The wheel never prints which page is showing, so a page is recognised only by the fields it
 * carries. Twelve shapes have been seen across the 2026-08-11 captures; the ones below are the
 * distinct pages among them.
 */
enum class LeaperKimStrPage {
    /** Speed, phase currents, d/q axes, torque command, MOS temperature. Where the wheel starts. */
    Main,

    /** Hall state, per-phase sense and ADC accumulators, direction, error counters. */
    MotorAndPhases,

    /** Accelerometer, gyroscope, attitude quaternion, controller gains and integrators. */
    Imu,

    /** Every ADC channel, zero offsets, session and total distance. */
    Adc,

    /** Motor temperature, thermistor ADC, watt-hours, current limits. */
    TemperatureAndPower,

    /** 36 cell voltages in millivolts, highest, lowest, balancing mask. */
    Cells,

    /** Task rates and their CPU load, including the 499 Hz control loop. */
    LoopRates,

    /** Flash log indices, event counters, filesystem volume. */
    FlashLog,

    /** UART receive counters and DMA indices, button state, board voltages. */
    Comms,

    /** BMS summary of the left pack: comms counters, bus voltage, six raw thermistors. */
    BmsSummary,

    /** Nothing here matched a known page. */
    Unknown,
}

/**
 * Recognises a page from the names in one record.
 *
 * Each page is keyed on a tag that appears on it and nowhere else, so a record that lost fields
 * still lands on the right page as long as the marker survived. Order matters only where two pages
 * could otherwise both match.
 */
object LeaperKimStrPages {
    private val markers: List<Pair<String, LeaperKimStrPage>> = listOf(
        // `01` alone is too weak - a damaged record could produce it - so the cells page is keyed on
        // the pairing of a cell number with the highest-cell field.
        "Mx" to LeaperKimStrPage.Cells,
        "HzPid" to LeaperKimStrPage.LoopRates,
        "Cci" to LeaperKimStrPage.TemperatureAndPower,
        "q0" to LeaperKimStrPage.Imu,
        "AD16T" to LeaperKimStrPage.Adc,
        "CpS" to LeaperKimStrPage.MotorAndPhases,
        "PgIdx" to LeaperKimStrPage.FlashLog,
        "U0RxId" to LeaperKimStrPage.Comms,
        "StcC" to LeaperKimStrPage.BmsSummary,
        // Last: `dKM` is the main page's marker, and the main page is the only one carrying it.
        "dKM" to LeaperKimStrPage.Main,
    )

    fun of(tags: Set<String>): LeaperKimStrPage {
        for ((marker, page) in markers) {
            if (marker in tags) {
                if (page == LeaperKimStrPage.Cells && "01" !in tags) continue
                return page
            }
        }
        return LeaperKimStrPage.Unknown
    }

    fun of(record: LeaperKimStrRecord): LeaperKimStrPage = of(record.fields.keys)
}

/**
 * What to do with `CHANGESHOWPAGE` while a diagnostic session runs.
 *
 * The command can only step to the next page, never select one, and a full turn of the carousel is
 * about twelve pages. Both plans below are built around that: the only lever is how long to sit on
 * the page currently showing.
 */
sealed interface LeaperKimStrPagePlan {
    /**
     * Walk the carousel forever, dwelling on [pages] and passing straight through the rest.
     *
     * Use when several pages are wanted at once. The cost is arithmetic and worth stating: with two
     * pages of interest at 8 s each and ten skipped at [skipMillis], a full turn is about 17 s, so
     * each wanted page is showing roughly a quarter of the time.
     */
    data class Carousel(
        val pages: Set<LeaperKimStrPage>,
        val dwellMillis: Long,
        val skipMillis: Long = DefaultSkipMillis,
    ) : LeaperKimStrPagePlan

    /** Step until [page] is showing, then stop. Use when one page is wanted at its full 2 Hz. */
    data class Hold(val page: LeaperKimStrPage) : LeaperKimStrPagePlan

    /** Send nothing; whatever page the wheel is on stays. */
    data object Manual : LeaperKimStrPagePlan

    companion object {
        /**
         * How long to sit on a page being passed through.
         *
         * A page change lands 0.1..0.5 s after the command, median 0.3, measured over 22 presses in
         * dumps `20260811_141039` and `20260811_144937`. Stepping faster than the change arrives
         * overshoots, so this is the slowest observed change with room for the write queue on top.
         * A full turn of twelve pages therefore takes about twelve seconds.
         */
        const val DefaultSkipMillis: Long = 1_000

        /** Long enough for four records of the page at its 2 Hz. */
        const val DefaultDwellMillis: Long = 2_000
    }
}

/**
 * Whether `CHANGESHOWPAGE` is due now.
 *
 * [stableMillis] is how long the situation has been unchanged: time since the page last changed
 * **or** since a step was last sent, whichever is later. Both, not just the first - a page change
 * lands up to half a second after the command, and a clock that only restarted on the change would
 * keep firing every tick in between, walking the wheel several pages past the one asked for.
 *
 * An unrecognised page is always passed through: sitting on one would stall the carousel, and
 * holding for a page that never arrives is what a broken marker would look like.
 */
fun strPageStepDue(
    plan: LeaperKimStrPagePlan,
    currentPage: LeaperKimStrPage,
    stableMillis: Long,
): Boolean = when (plan) {
    is LeaperKimStrPagePlan.Manual -> false
    is LeaperKimStrPagePlan.Hold ->
        currentPage != plan.page && stableMillis >= LeaperKimStrPagePlan.DefaultSkipMillis
    is LeaperKimStrPagePlan.Carousel -> {
        val wanted = currentPage in plan.pages && currentPage != LeaperKimStrPage.Unknown
        stableMillis >= if (wanted) plan.dwellMillis else plan.skipMillis
    }
}

/**
 * The two ASCII commands that drive the diagnostic mode.
 *
 * Both are a prefix and a suffix concatenated - the firmware's parser compares the halves separately,
 * which is why the strings read the way they do. Neither has an "off": `CHANGESTRORPACK` only ever
 * increments a counter, and nothing in the image writes it back down except a boot path and the
 * `lfsFileOpt(...)` parser. Leaving the mode means restarting the wheel.
 */
object LeaperKimStrCommands {
    /** Steps the mode counter. At exactly 3 the binary co-stream joins in and ruins the text. */
    const val StreamStep: String = "CHANGESTRORPACK"

    /** Steps to the next diagnostic page. Cannot select one; a full turn is about twelve pages. */
    const val PageStep: String = "CHANGESHOWPAGE"

    const val Terminator: String = "\r\n"

    fun bytes(command: String): ByteArray {
        val text = command + Terminator
        return ByteArray(text.length) { (text[it].code and 0xFF).toByte() }
    }
}
