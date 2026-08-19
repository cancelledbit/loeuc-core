@file:OptIn(ExperimentalWriteApi::class)

package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.LeaperKimProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.buildLeaperKimLowLightCommand
import pw.vasilevskiy.loeuc.shared.protocol.buildNinebotSettingCommand
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class ExperimentalWriteCommandApiTest {
    @Test
    fun writeCommandConstructorIsNotPublic() {
        val publicConstructors = ExperimentalWriteCommand::class.java.constructors

        assertFalse(publicConstructors.any { !it.isSynthetic })
    }

    @Test
    fun builtInWritesAreNeverMarkedValidated() {
        val writes = listOf(
            ExperimentalCommands.leaperKimLight(enabled = true),
            ExperimentalCommands.leaperKimLowLight(enabled = false),
            ExperimentalCommands.leaperKimShutdownTimer(),
            ExperimentalCommands.ninebotSetting("ninebot_limited_mode", 1),
        )

        assertFalse(writes.any { it.validation.status == CommandStatus.VALIDATED })
    }

    /**
     * The curated surface must stay a wrapper. It used to carry its own copies of the byte
     * layouts and of a CRC32, which is how two builders for the same command drift apart; the
     * engines are the only place a command is spelled out, and the exact bytes are pinned by
     * the engine tests.
     */
    @Test
    fun curatedCommandsCarryExactlyTheBytesTheEnginesBuild() {
        val engine = LeaperKimProtocolEngine()

        assertContentEquals(
            engine.buildLightCommand(enabled = true),
            ExperimentalCommands.leaperKimLight(enabled = true).bytes,
        )
        assertContentEquals(
            buildLeaperKimLowLightCommand(enabled = false),
            ExperimentalCommands.leaperKimLowLight(enabled = false).bytes,
        )
        assertContentEquals(
            engine.buildFixedShutdownTimerCommand(),
            ExperimentalCommands.leaperKimShutdownTimer().bytes,
        )
        assertContentEquals(
            buildNinebotSettingCommand("ninebot_limited_mode", 1),
            ExperimentalCommands.ninebotSetting("ninebot_limited_mode", 1).bytes,
        )
    }
}
