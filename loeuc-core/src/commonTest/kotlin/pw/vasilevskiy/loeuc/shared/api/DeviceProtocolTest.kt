package pw.vasilevskiy.loeuc.shared.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceProtocolTest {

    /**
     * Every id riders already have on disk, spelled exactly as the Android
     * `protocol/<Brand>ProtocolDecoder.kt` files write it into `SavedWheel.decoderId`.
     * `:shared` cannot see those files, so the contract
     * is pinned here by hand: this test exists to fail the day someone renames an id.
     */
    @Test
    fun everyPersistedDecoderIdResolves() {
        val persisted = listOf(
            "begode", "kingsong", "inmotion", "leaperkim_lynxs", "nosfet",
            "ninebot_z", "solowheel_xtreme", "hw_charger", "skat_charger",
        )
        val unresolved = persisted.filter { DeviceProtocol.fromId(it) == null }
        assertEquals(emptyList(), unresolved, "these ids no longer resolve")
    }

    @Test
    fun chargersAreNotWheelProtocols() {
        assertEquals(
            listOf("hw_charger", "skat_charger"),
            ChargerProtocol.entries.map { it.id },
        )
        assertTrue(WheelProtocol.entries.none { it.id in setOf("hw_charger", "skat_charger") })
    }

    @Test
    fun idsAndAliasesAreUnique() {
        val everyName = DeviceProtocol.all.flatMap { listOf(it.id) + it.aliases }
        assertEquals(everyName.size, everyName.toSet().size, "duplicate id or alias: $everyName")
    }

    @Test
    fun unknownIdResolvesToNull() {
        assertNull(DeviceProtocol.fromId("veteran"))
    }

    /**
     * `SavedWheel.decoderId` on Android stores `leaperkim_lynxs`, while
     * `SharedEngineDumpCandidates` names the same protocol `leaperkim`. Both spellings are
     * already written to riders' devices, so both have to resolve - otherwise a saved wheel
     * silently loses its protocol.
     */
    @Test
    fun legacyLeaperKimIdResolvesThroughAlias() {
        assertEquals(WheelProtocol.LeaperKim, DeviceProtocol.fromId("leaperkim"))
    }
}
