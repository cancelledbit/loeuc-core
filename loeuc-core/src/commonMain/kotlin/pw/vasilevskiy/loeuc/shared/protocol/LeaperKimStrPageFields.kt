package pw.vasilevskiy.loeuc.shared.protocol

/**
 * Which fields each diagnostic page prints.
 *
 * Taken from the five clean captures of 2026-08-11, keeping only fields that appeared in at least
 * 60 % of that page's records - the rest are splice artifacts of the wheel's own `printf` overflow.
 * The wheel never announces its pages, so this is observation, not documentation: a page seen on
 * another model or firmware may carry a different set.
 */
object LeaperKimStrPageFields {
    val byPage: Map<LeaperKimStrPage, Set<String>> = mapOf(
        LeaperKimStrPage.Main to setOf(
            "AA", "AB", "AC", "AD", "AI", "AP", "AQ", "Bem", "CC", "CM", "CdA", "ChgAD", "Cmo",
            "Cmt", "FTC", "FdHz", "Fs", "GC", "IM", "LDpp", "Lm1", "MdKM", "Out", "RA", "TC", "U1",
            "U2", "U3", "U4", "U5", "V", "VD", "VQ", "Vmi", "Vmx", "Vnp", "Vp", "Vpp", "Vqd",
            "dKM",
        ),
        LeaperKimStrPage.Adc to setOf(
            "6TcC", "A", "AD0", "AD1", "AD16T", "AD17R", "AD2", "AD3", "AD4", "AD5", "AD6", "AD7",
            "AD8", "AD9", "AOs", "B", "BOs", "BajT", "BtL", "BtR", "BtS", "C", "COs", "ChgAD",
            "Cmo", "DOs", "GdcC", "HdT", "HeT", "NlT", "ObT", "Off", "OmT", "R", "RC", "Rnd", "S",
            "Se", "SkM", "TkM", "V", "Vgd", "iAD4", "iAD8", "iAD9",
        ),
        LeaperKimStrPage.TemperatureAndPower to setOf(
            "AQ", "Asc", "CTr", "Cci", "Cma", "Cmo", "Cnt", "Cres", "FA", "Gres", "IA", "LwC",
            "OfC", "Pat", "Ple", "Psl", "TA", "Top", "U1", "U2", "U3", "U4", "U5", "VD", "VQ",
            "Vgd", "Vqd", "Wcm", "Wht", "Wma", "bit", "cR", "mO", "mWH", "uEc",
        ),
        LeaperKimStrPage.BmsSummary to setOf(
            "Aa", "Ac", "Aco", "Ap", "BLN", "BrR", "BrS", "Cc", "Co", "Cs", "Len", "Os", "Rqt",
            "RxM", "RxR", "RxS", "StcC", "StmV", "T0", "T1", "T2", "T3", "T4", "T5", "Vbu", "Vo",
            "Vp", "Vsc", "rM",
        ),
        LeaperKimStrPage.MotorAndPhases to setOf(
            "6Dpp", "AC", "AD", "ApS", "BC", "BD", "BpS", "CC", "CD", "CpS", "DS", "DerC", "Di",
            "Dif", "Dir", "FA", "HPS", "Ha", "HdHz", "Her8", "IA", "JA", "LDpp", "Pole", "SDr",
            "U1", "U2", "U3", "U4", "U5", "di1", "di2", "diOis", "dpOis", "fyOis", "isH", "rAB",
        ),
        LeaperKimStrPage.Comms to setOf(
            "AD2", "Btn", "DWTT", "Fs", "Het", "Len", "Ncnt", "NoLV", "Pbtn", "RxR", "RxS",
            "U0RxDm", "U0RxId", "U0TxId", "U1", "U2", "U3", "U4", "U5", "Vb", "Vd", "Vdm", "Vf",
            "Vn", "Vp", "Vum", "cAD0", "cAD1", "cAD2", "hAD0", "hAD1", "hAD2", "lAD", "rAD",
            "type",
        ),
        LeaperKimStrPage.FlashLog to setOf(
            "EvIdx", "ExiEvN", "Flock", "Ka", "Kc", "Kg", "LsVol", "NxtRdy", "PgEvN", "PgIdx",
            "RxN", "WtN",
        ),
        LeaperKimStrPage.Cells to setOf(
            "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14",
            "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28",
            "29", "30", "31", "32", "33", "34", "35", "36", "BL", "Mi", "Mx",
        ),
        LeaperKimStrPage.Imu to setOf(
            "ASqt", "AX", "AY", "AZ", "FyT", "GGY", "GX", "GY", "GZ", "Kg", "Kp", "LiC", "MP",
            "ObT", "OmC", "PAref", "Ptb", "RSqt", "Rtc", "TeT", "U1", "U2", "U3", "U4", "U5", "ex",
            "ey", "ez", "fMS", "iuki", "iukp", "obtT", "p", "q0", "q1", "q2", "q3", "r", "tCNT",
            "y",
        ),
        LeaperKimStrPage.LoopRates to setOf(
            "A", "B", "C", "HzAdc", "HzF10", "HzF25", "HzF50", "HzF5K", "HzPid", "HzWav", "U",
            "dHzDTE",
        ),
    )

    /** Pages that print [tag], empty when nothing observed carries it. */
    fun pagesWith(tag: String): Set<LeaperKimStrPage> =
        byPage.filterValues { tag in it }.keys
}

/**
 * The smallest set of pages that between them print every one of [tags].
 *
 * Exhaustive rather than greedy: there are ten pages, so every subset can be tried in size order and
 * the answer is the true minimum instead of something merely close. Tags nothing is known to carry
 * are ignored - they cannot be covered, and refusing to plan because of one unknown name would be
 * worse than harvesting the rest.
 */
fun strPagesCovering(tags: Set<String>): Set<LeaperKimStrPage> {
    val pages = LeaperKimStrPageFields.byPage.keys.toList()
    val wanted = tags.filter { tag -> pages.any { tag in LeaperKimStrPageFields.byPage.getValue(it) } }
    if (wanted.isEmpty()) return emptySet()
    for (size in 1..pages.size) {
        val found = smallestCover(pages, wanted.toSet(), size)
        if (found != null) return found
    }
    return pages.toSet()
}

private fun smallestCover(
    pages: List<LeaperKimStrPage>,
    wanted: Set<String>,
    size: Int,
): Set<LeaperKimStrPage>? {
    fun search(from: Int, chosen: MutableList<LeaperKimStrPage>): Set<LeaperKimStrPage>? {
        if (chosen.size == size) {
            val covered = chosen.flatMap { LeaperKimStrPageFields.byPage.getValue(it) }.toSet()
            return if (covered.containsAll(wanted)) chosen.toSet() else null
        }
        for (index in from until pages.size) {
            chosen += pages[index]
            search(index + 1, chosen)?.let { return it }
            chosen.removeAt(chosen.size - 1)
        }
        return null
    }
    return search(0, mutableListOf())
}

/**
 * The rotation plan that harvests [tags] with as little page-walking as possible.
 *
 * One page covering everything means never sending another command, so that page keeps its full
 * 2 Hz. Several means a carousel, and the caller should be showing how old each value is: between
 * visits a field is not updating, and a summary that hides that is a summary that lies.
 */
fun strPlanFor(tags: Set<String>, dwellMillis: Long = LeaperKimStrPagePlan.DefaultDwellMillis): LeaperKimStrPagePlan {
    val cover = strPagesCovering(tags)
    return when {
        cover.isEmpty() -> LeaperKimStrPagePlan.Manual
        cover.size == 1 -> LeaperKimStrPagePlan.Hold(cover.first())
        else -> LeaperKimStrPagePlan.Carousel(pages = cover, dwellMillis = dwellMillis)
    }
}
