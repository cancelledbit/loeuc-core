package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyModel
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacySettingWrite
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacySettingsCatalog
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingWrite
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingsCatalog
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A refusal is read by the rider, so it exists in both languages.
 *
 * The library used to hand the application one English sentence, which the Russian interface
 * then showed inside a translated frame: a Russian "write unavailable:" followed by English.
 */
@OptIn(ExperimentalWriteApi::class)
class SettingWriteRefusalTest {

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    @Test
    fun textFollowsTheInterfaceLanguage() {
        val refusal = SettingWriteRefusal.readOnly()

        assertEquals(refusal.russian, refusal.text(isRu = true))
        assertEquals(refusal.english, refusal.text(isRu = false))
        // A log line and a test failure read the same everywhere.
        assertEquals(refusal.english, refusal.toString())
    }

    @Test
    fun everySharedRefusalCarriesBothLanguages() {
        val shared = listOf(
            SettingWriteRefusal.unknownSetting("some_key"),
            SettingWriteRefusal.readOnly(),
            SettingWriteRefusal.notInCapabilities(),
            SettingWriteRefusal.noValidatedCommand(),
            SettingWriteRefusal.outsideValidatedRange(),
            SettingWriteRefusal.modelUnknown(),
            SettingWriteRefusal.noReadback(),
        )

        shared.forEach { refusal ->
            assertTrue(refusal.english.isNotBlank(), "empty English in $refusal")
            assertTrue(cyrillic.containsMatchIn(refusal.russian), "no Russian in $refusal")
            assertTrue(!cyrillic.containsMatchIn(refusal.english), "Russian leaked into English in $refusal")
        }
    }

    @Test
    fun aKingSongRefusalIsNotEnglishOnly() {
        val write = KingSongSettingsCatalog.write(KingSongSettingsCatalog.MaxSpeedKey, 45, KingSongSettingsState())

        assertIs<KingSongSettingWrite.Rejected>(write)
        assertTrue(cyrillic.containsMatchIn(write.refusal.russian))
        assertEquals(write.refusal.english, write.reason)
    }

    @Test
    fun aLegacyInmotionRefusalIsNotEnglishOnly() {
        val write = InmotionLegacySettingsCatalog.write("no_such_setting", 1, InmotionLegacyModel.V8)

        assertIs<InmotionLegacySettingWrite.Rejected>(write)
        assertTrue(cyrillic.containsMatchIn(write.refusal.russian))
        assertEquals(write.refusal.english, write.reason)
    }
}
