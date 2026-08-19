plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.10" apply false
}

// Sources are shared with the maintainer's private application build, which compiles this
// library from the same directory rather than from a copy. These checks are what keeps that
// arrangement safe to publish: they fail the build on anything that belongs only to the
// private side - internal paths, credentials, device identifiers, or prose in Russian.
//
// What they deliberately do NOT ban is Russian inside string literals. Wheel setting titles,
// metric names and diagnostic messages ship in both languages; they are data, not commentary.
// An earlier revision banned every Cyrillic character in every file, and the resulting cleanup
// deleted the Russian out of comments instead of translating it, leaving stubs like "*   ."
// behind. `gutted comment` below is the regression test for that mistake.

private val publicDocumentNames = setOf("README.md", "CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md", "CREDITS.md")

private val tripleQuoted = Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL)
// Unrolled rather than `("[^"\\]|\\.)*"`: the alternation form recurses once per character
// and blows the stack on the larger catalogs.
private val singleQuoted = Regex("\"[^\"\\\\\\n]*(?:\\\\.[^\"\\\\\\n]*)*\"")
private val charLiteral = Regex("'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'")

/** Source text with string and character literals blanked, so only code and comments remain. */
private fun outsideLiterals(text: String): String =
    text.replace(tripleQuoted, "\"\"").replace(singleQuoted, "\"\"").replace(charLiteral, "''")

private fun forbiddenRelativePath(relativePath: String): String? {
    val normalized = relativePath.replace('\\', '/')
    val segments = normalized.split('/')
    if (segments.any { it.equals(".claude", ignoreCase = true) }) return "internal tooling directory"
    val name = segments.last()
    if (name !in publicDocumentNames && name.endsWith(".md", ignoreCase = true) &&
        Regex("(?i)(^|[-_.])(internal|handoff|transcript)([-_.]|$)").containsMatchIn(name)
    ) return "internal document filename"
    return null
}

/**
 * Comment text that was deleted rather than translated, e.g. `* ,   :` or a trailing `// 0 =`.
 *
 * The test is "a comment with no words left in it". Punctuation-only bodies are the obvious
 * case; a body that still has numbers counts only when it also ends mid-sentence, so that an
 * arithmetic note like `// 64 * 8 = 512` stays legal. Backticks are dropped first, otherwise a
 * KDoc code fence would read as an empty comment.
 */
private fun guttedCommentLines(text: String): Int =
    text.lineSequence().count { line ->
        val trimmed = line.trim()
        val body = when {
            trimmed.startsWith("//") -> trimmed.removePrefix("//")
            trimmed.startsWith("*") || trimmed.startsWith("/*") -> trimmed.trimStart('/', '*')
            trimmed.contains("//") -> trimmed.substringAfter("//")
            else -> return@count false
        }.replace("`", "").trim()
        val isRuler = body.length >= 3 && body.all { it == body[0] }
        if (body.isEmpty() || isRuler || body.any(Char::isLetter)) {
            false
        } else {
            body.none(Char::isDigit) || body.last() in "=:,;(-"
        }
    }

tasks.register("checkOpenSourceHygiene") {
    doLast {
        val internalPathPattern = Regex("(?i)(/Users/|\\.claude|(?:^|[ (`])(?:app|wear|iosApp)/src/)")
        val internalNamePattern = Regex("(?i)(WheelDashboardScreen|BluetoothScannerScreen|LoEUCAlerts)")
        val macPattern = Regex("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}")
        val serialPattern = Regex("(?i)\\b(?:SN|S/N)[-_ ]?(?=[A-Z0-9]*\\d)[A-Z0-9]{6,}\\b")
        val secretPattern = Regex("(?i)(BEGIN [A-Z ]+ KEY|AKIA[0-9A-Z]{16}|ghp_[0-9A-Za-z]{20,})")
        val cyrillicPattern = Regex("[\\u0400-\\u04FF]")
        val unfinishedPattern = Regex("(?i)\\b(TODO|FIXME|XXX)\\b")
        val failures = mutableListOf<String>()
        fileTree(projectDir) {
            exclude("build.gradle.kts")
            exclude("**/build/**")
            exclude(".gradle/**")
            exclude("gradle/wrapper/gradle-wrapper.jar")
        }.forEach { source ->
            val relative = source.relativeTo(projectDir).invariantSeparatorsPath
            forbiddenRelativePath(relative)?.let { failures += "$relative: $it" }
            val text = runCatching { source.readText() }.getOrNull() ?: return@forEach
            val isSource = source.name.endsWith(".kt", true) || source.name.endsWith(".swift", true)
            val prose = if (isSource) outsideLiterals(text) else text

            // Russian is allowed in source string literals - those are user-facing labels the
            // application shows - and nowhere else.
            if (cyrillicPattern.containsMatchIn(prose)) {
                failures += "$relative: Russian outside a string literal"
            }
            if (internalPathPattern.containsMatchIn(text)) failures += "$relative: internal path"
            if (macPattern.containsMatchIn(text)) failures += "$relative: address-shaped value"
            if (serialPattern.containsMatchIn(text)) failures += "$relative: identifier or credential marker"
            if (secretPattern.containsMatchIn(text)) failures += "$relative: secret-shaped value"
            if (unfinishedPattern.containsMatchIn(prose)) failures += "$relative: unfinished-work marker"
            if (isSource && guttedCommentLines(text) > 0) failures += "$relative: gutted comment"
            if (source.name.endsWith(".md", true) && internalNamePattern.containsMatchIn(text)) {
                failures += "$relative: private application name"
            }
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}

tasks.register("checkOpenSourceHygieneFixture") {
    doLast {
        check(forbiddenRelativePath(".claude/clean.md") != null)
        check(forbiddenRelativePath("docs/handoff-notes.md") != null)
        check(forbiddenRelativePath("README.md") == null)
        check(forbiddenRelativePath("docs/SECURITY.md") == null)
        // A label in a literal passes; the same words in a comment do not.
        check(!Regex("[\\u0400-\\u04FF]").containsMatchIn(outsideLiterals("val a = \"Педали\"")))
        check(Regex("[\\u0400-\\u04FF]").containsMatchIn(outsideLiterals("// Педали")))
        check(guttedCommentLines("     *    ,   :") == 1)
        check(guttedCommentLines("     * Pedal stiffness leads the section.") == 0)
        check(guttedCommentLines("val timeoutMs = 0L // 0 =") == 1)
        check(guttedCommentLines("val timeoutMs = 0L // 0 means no reset") == 0)
        check(guttedCommentLines("assertEquals(x, y) // 64 * 8 = 512") == 0)
        check(guttedCommentLines("     * ```") == 0)
        check(guttedCommentLines("     */") == 0)
    }
}
