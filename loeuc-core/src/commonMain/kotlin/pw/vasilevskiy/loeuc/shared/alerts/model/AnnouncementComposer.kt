package pw.vasilevskiy.loeuc.shared.alerts.model

/**
 * Turns a rider's phrase template into the sentence that will be spoken.
 *
 * It is deliberately a pure function of (template, metric, value, language) and nothing else,
 * so the same sentence comes out on Android and on iOS. The alternative - each platform
 * building the string from a metric and a number - was rejected while designing this: rounding
 * and Russian plural agreement would be written twice and would drift apart quietly.
 *
 * The language is a parameter rather than engine state because the engine has no business
 * knowing it, and because adding a constructor parameter to `AlertEngine` breaks both Swift
 * call sites - Kotlin default arguments do not survive the interop.
 */
object AnnouncementComposer {

    /**
     * Fills [template]'s placeholders from [value] and returns the sentence to speak.
     *
     * Unknown text is left exactly as the rider typed it: this is their sentence, and the
     * composer's job is substitution, not correction.
     */
    fun compose(template: String, metric: MetricId, value: Double, isRu: Boolean): String {
        val decimals = MetricSpeech.decimals(metric)
        val number = formatNumber(value, decimals, isRu)
        val rounded = roundToDecimals(value, decimals)
        val unit = MetricSpeech.unitFor(metric, rounded, isRu)

        var out = template
        for (token in MetricSpeech.valueTokens) out = out.replace(token, number)
        for (token in MetricSpeech.unitTokens) out = out.replace(token, unit)

        // Substituting an empty unit into "{значение} {единицы}" would leave a double space,
        // and a synthesizer pauses on it.
        return out.split(" ").filter { it.isNotEmpty() }.joinToString(" ").trim()
    }

    /**
     * The number as it will be spoken.
     *
     * Written by hand rather than through `String.format`, which exists only on the JVM: this
     * file has to compile and behave identically on Kotlin/Native.
     */
    fun formatNumber(value: Double, decimals: Int, isRu: Boolean): String {
        if (decimals <= 0) {
            // -0 reads as "minus zero" on some engines. It is never what a metric means.
            val whole = kotlin.math.round(value).toLong()
            return if (whole == 0L) "0" else whole.toString()
        }

        var factor = 1L
        repeat(decimals) { factor *= 10L }
        val scaled = kotlin.math.round(value * factor).toLong()
        val whole = scaled / factor
        val frac = kotlin.math.abs(scaled % factor)
        val sign = if (scaled < 0L && whole == 0L) "-" else ""
        val separator = if (isRu) "," else "."
        return sign + whole.toString() + separator + frac.toString().padStart(decimals, '0')
    }

    private fun roundToDecimals(value: Double, decimals: Int): Double {
        if (decimals <= 0) return kotlin.math.round(value)
        var factor = 1L
        repeat(decimals) { factor *= 10L }
        return kotlin.math.round(value * factor) / factor.toDouble()
    }
}
