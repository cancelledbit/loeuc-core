package pw.vasilevskiy.loeuc.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExtensionManifestTest {

    private val valid = """
        {
          "id": "com.example.range",
          "apiVersion": 1,
          "version": "1.0",
          "name": { "en": "Range", "ru": "Запас хода" },
          "summary": { "en": "Estimates the distance left." },
          "telemetryHz": 2,
          "settings": [{ "key": "reserve", "label": { "en": "Reserve" }, "unit": "%",
                         "min": 0, "max": 50, "step": 5, "default": 10 }],
          "tiles": [{ "key": "range", "label": { "en": "Range" }, "unit": "km",
                      "decimals": 1, "min": 0, "max": 500 }],
          "alerts": [{ "key": "low", "text": { "en": "Range low" }, "persistence": "repeat" }],
          "commands": [
            { "key": "beep", "label": { "en": "Horn" }, "action": "horn" },
            { "key": "level", "label": { "en": "Light level" },
              "template": { "characteristic": "0000FFE1-0000-1000-8000-00805F9B34FB",
                            "bytes": "aa 55 03 00 00 00",
                            "parameter": { "offset": 4, "width": 2, "endian": "little",
                                           "min": 0, "max": 100 } } }
          ]
        }
    """.trimIndent()

    private fun parsed(json: String = valid): ExtensionManifest {
        val result = ExtensionManifest.parse(json)
        assertTrue("$result", result is ManifestParseResult.Parsed)
        return (result as ManifestParseResult.Parsed).manifest
    }

    private fun errors(json: String): List<String> {
        val result = ExtensionManifest.parse(json)
        assertTrue("$result", result is ManifestParseResult.Invalid)
        return (result as ManifestParseResult.Invalid).errors
    }

    @Test
    fun parsesEverySection() {
        val manifest = parsed()
        assertEquals("com.example.range", manifest.id)
        assertEquals("Запас хода", manifest.name.resolve("ru"))
        assertEquals("Range", manifest.name.resolve("de"))
        assertEquals(2, manifest.effectiveTelemetryHz)
        assertEquals(10.0, manifest.setting("reserve")!!.default, 0.0)
        assertEquals(1, manifest.tile("range")!!.decimals)
        assertEquals(AlertPersistence.REPEAT, manifest.alert("low")!!.persistence)
        assertEquals(CommandTarget.Action(NamedAction.HORN), manifest.command("beep")!!.target)
        val template = (manifest.command("level")!!.target as CommandTarget.Template).template
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", template.characteristic)
        assertEquals(6, template.bytes.size)
    }

    @Test
    fun telemetryRateIsHeldUnderTheHostCeiling() {
        val manifest = parsed(valid.replace("\"telemetryHz\": 2", "\"telemetryHz\": 50"))
        assertEquals(ExtensionApi.MAX_TELEMETRY_HZ, manifest.effectiveTelemetryHz)
        val unspecified = parsed(valid.replace("\"telemetryHz\": 2,", ""))
        assertEquals(ExtensionApi.MAX_TELEMETRY_HZ, unspecified.effectiveTelemetryHz)
    }

    @Test
    fun collectsAllProblemsAtOnce() {
        val problems = errors(
            """
            {
              "id": "Bad Id",
              "apiVersion": 0,
              "name": { "en": "x" },
              "summary": {},
              "tiles": [{ "key": "t", "label": { "en": "t" }, "min": 5, "max": 1 }],
              "commands": [{ "key": "c", "label": { "en": "c" } }]
            }
            """.trimIndent(),
        )
        assertTrue(problems.any { it.startsWith("id:") })
        assertTrue(problems.any { it.startsWith("apiVersion:") })
        assertTrue(problems.any { it.startsWith("manifest.version") })
        assertTrue(problems.any { it.startsWith("manifest.summary") })
        assertTrue(problems.any { it.contains("min is greater than max") })
        assertTrue(problems.any { it.contains("needs an action or a template") })
    }

    @Test
    fun refusesDuplicateKeys() {
        val json = valid.replace("\"key\": \"level\"", "\"key\": \"beep\"")
        assertTrue(errors(json).any { it.contains("'beep' is declared more than once") })
    }

    @Test
    fun refusesAParameterRunningPastTheTemplate() {
        val json = valid.replace("\"offset\": 4", "\"offset\": 5")
        assertTrue(errors(json).any { it.contains("runs past the 6-byte template") })
    }

    @Test
    fun refusesBoundsTheFieldCannotHold() {
        val unsigned = valid.replace("\"min\": 0, \"max\": 100 }", "\"min\": -1, \"max\": 100 }")
        assertTrue(errors(unsigned).any { it.contains("does not fit") })
        val tooWide = valid.replace("\"max\": 100 }", "\"max\": 70000 }")
        assertTrue(errors(tooWide).any { it.contains("does not fit") })
    }

    @Test
    fun keepsActionsThisVersionDoesNotKnow() {
        val manifest = parsed(valid.replace("\"action\": \"horn\"", "\"action\": \"pedal.mode\""))
        assertEquals(CommandTarget.Action("pedal.mode"), manifest.command("beep")!!.target)
    }

    @Test
    fun refusesNonJson() {
        assertTrue(errors("not json").single().startsWith("not a JSON object"))
    }

    @Test
    fun consentHashIgnoresWordingAndFormatting() {
        val base = parsed().consentHash
        assertEquals(base, parsed(valid.replace("Light level", "Brightness")).consentHash)
        assertEquals(base, parsed(valid.replace("\"version\": \"1.0\"", "\"version\": \"1.1\"")).consentHash)
        assertEquals(base, parsed(valid.replace("\n", " ")).consentHash)
        assertEquals(base, parsed(valid.replace("\"max\": 50,", "\"max\": 40,")).consentHash)
    }

    @Test
    fun consentHashFollowsWhatTheRiderApproved() {
        val base = parsed().consentHash
        assertNotEquals(base, parsed(valid.replace("\"telemetryHz\": 2", "\"needsLocation\": true, \"telemetryHz\": 2")).consentHash)
        assertNotEquals(base, parsed(valid.replace("aa 55 03", "aa 55 04")).consentHash)
        assertNotEquals(base, parsed(valid.replace("\"action\": \"horn\"", "\"action\": \"shutdown\"")).consentHash)
        assertNotEquals(base, parsed(valid.replace("\"persistence\": \"repeat\"", "\"persistence\": \"once\"")).consentHash)
        assertNotEquals(base, parsed(valid.replace("\"max\": 100 }", "\"max\": 90 }")).consentHash)
    }
}
