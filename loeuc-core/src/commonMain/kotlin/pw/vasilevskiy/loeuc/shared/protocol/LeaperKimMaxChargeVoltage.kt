package pw.vasilevskiy.loeuc.shared.protocol

/**
 * The LeaperKim / NOSFET max-charge voltage: page 8 byte 64 is an adjustment in tenths of a volt,
 * byte 65 the whole-volt base it is added to, so the ceiling is `base + adjustment / 10` V.
 *
 * Some firmwares write the 0x80 "not reported" sentinel into byte 65. The base is then resolved
 * from the firmware build, and only from builds whose base is known from evidence. It is **not**
 * derived from the pack's series count: the same 36S Lynx-S reports 146 on `009.0.03` and 145 on
 * `009.0.04`, because that update rebased the setter, so a series-to-base table would be a guess
 * that is already known to be wrong for one build. The vendor apps' own fallback of 145 is a
 * guess of the same kind and is not used either.
 *
 * When neither the wire nor this table knows the base there is no absolute voltage to show:
 * [baseVolts] returns null, the settings row shows the adjustment alone, and the telemetry
 * max-charge voltage is NaN. The bytes written for a slider position never depend on the base.
 */
object LeaperKimMaxChargeVoltage {

    /**
     * Base voltage for builds whose base is established, keyed by the full firmware version as
     * the engine reads it from bytes 30, 28, 29 (so `9004` is Lynx-S `009.0.04`, `501008` is
     * NOSFET Apex `501.0.08`). Every row cites its evidence:
     *
     * - `9003` Lynx-S `009.0.03` -> 146: firmware, page-8 builder `FUN_0801914C` writes
     *   `frame[65] = 146` and the setter stores `14600 + v*10` centivolts
     *   (`docs/leaperkim-firmware-re-notes.md`, the `9.0.4` rebase).
     * - `9004` Lynx-S `009.0.04` -> 145: the same builder writes `frame[65] = 145`, setter
     *   `14500 + v*10`; every page 8 of the LK21571 captures reports 145 on the wire.
     * - `8005` Oryx `008.0.05` -> 168: the LK18414 captures report 168 on the wire from a pack
     *   that reports 42 cells.
     * - `501008` NOSFET Apex `501.0.08` -> 145: firmware, the page-8 builder hardcodes 0x80 into
     *   byte 65 and id 29 stores `14500 + value*10` centivolts, so the stock adjustment 62 is
     *   151.2 V, the `SysVol` the vendor config lists for hardware `5010`
     *   (`docs/nosfet-firmware-re-notes.md`); the NF0126 capture carries adjustment 62.
     * - Patton-S `007.0.01`, `007.0.02` -> 120 and `007.0.03` -> 121 (Lynx and Sherman-L builds
     *   are covered per model in [ModelBases]): firmware, the max-charge setter in
     *   each image loads `base*100` and stores `base*100 + v*10` centivolts into the settings
     *   struct, the same shape as the Lynx-S setter above
     *   (`docs/leaperkim-firmware-re-notes.md`, "Max-charge base per build"). The same scan
     *   finds 146/145 in Lynx-S `009.0.03`/`009.0.04` and 168 in Oryx `008.0.05`, matching the
     *   rows above. Sherman, Sherman-S, Sherman Max, Abrams and Patton carry no such setter
     *   and stay unknown.
     */
    /**
     * Base voltage for every build of a model, keyed by the firmware major (`005` Lynx, `006`
     * Sherman-L), where every readable image of that model agrees. Lynx `005.0.09..13` and
     * Sherman-L `006.0.02..10` all load 145; Lynx `005.0.04..07` have no setter of that shape,
     * and the model's pack does not change with the build, so they take the model's base too.
     * Lynx-S and Patton-S are not here: their images disagree between builds (146/145, 120/121).
     */
    private val ModelBases: Map<Int, Int> = mapOf(
        5 to 145,
        6 to 145,
    )

    private val KnownBases: Map<Int, Int> = mapOf(
        9003 to 146,
        9004 to 145,
        8005 to 168,
        501008 to 145,
        7001 to 120,
        7002 to 120,
        7003 to 121,
    )

    /**
     * The base in whole volts: the wheel's own [reportedBase] when it sent one, otherwise the
     * base known for [firmwareVersion], otherwise null.
     */
    fun baseVolts(reportedBase: Int?, firmwareVersion: Int?): Int? =
        reportedBase ?: firmwareVersion?.let { KnownBases[it] ?: ModelBases[it / 1000] }

    /** The absolute ceiling in volts, or null when [baseVolts] is null. */
    fun volts(baseVolts: Int?, adjustment: Int): Double? =
        baseVolts?.let { (it * 10 + adjustment) / 10.0 }
}
