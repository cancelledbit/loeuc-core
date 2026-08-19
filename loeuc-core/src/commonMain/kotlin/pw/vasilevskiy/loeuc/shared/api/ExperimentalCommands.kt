@file:OptIn(ExperimentalWriteApi::class)

package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.protocol.KIND_READONLY
import pw.vasilevskiy.loeuc.shared.protocol.LeaperKimProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.NinebotAddress
import pw.vasilevskiy.loeuc.shared.protocol.NinebotCommand
import pw.vasilevskiy.loeuc.shared.protocol.NinebotFrameCodec
import pw.vasilevskiy.loeuc.shared.protocol.NinebotParam
import pw.vasilevskiy.loeuc.shared.protocol.NinebotSettingsCatalog
import pw.vasilevskiy.loeuc.shared.protocol.buildLeaperKimLowLightCommand
import pw.vasilevskiy.loeuc.shared.protocol.buildNinebotSettingCommand

/** Evidence level for a command's wire bytes. This is not a hardware safety guarantee. */
enum class CommandStatus { VALIDATED, UNTESTED }

data class CommandValidation(
    val status: CommandStatus,
    val evidence: String,
)

/** A write payload that an integration may inspect and send through its own transport. */
class ExperimentalWriteCommand private constructor(
    val protocol: WheelProtocol,
    val purpose: String,
    bytes: ByteArray,
    val validation: CommandValidation,
) {
    private val wireBytes: ByteArray = bytes.copyOf()
    val bytes: ByteArray get() = wireBytes.copyOf()

    companion object {
        internal fun create(
            protocol: WheelProtocol,
            purpose: String,
            bytes: ByteArray,
            validation: CommandValidation,
        ) = ExperimentalWriteCommand(protocol, purpose, bytes, validation)
    }
}

/** A read/poll payload that an integration may inspect and send through its own transport. */
class ExperimentalReadCommand(
    val protocol: WheelProtocol,
    val purpose: String,
    bytes: ByteArray,
    val validation: CommandValidation,
) {
    private val wireBytes: ByteArray = bytes.copyOf()
    val bytes: ByteArray get() = wireBytes.copyOf()
}

/**
 * The curated command surface: the same bytes the protocol engines build, wrapped with what is
 * actually known about each one.
 *
 * Every builder here delegates to its engine rather than reproducing the byte layout, because
 * a second copy of a checksum is a second thing to get wrong. What this object adds over
 * calling the engine directly is [CommandValidation]: how the bytes were established, and
 * whether anyone has confirmed them against real hardware. Most have not.
 */
object ExperimentalCommands {
    /** Stable keys accepted by [ninebotSetting]. Every entry is explicitly UNTESTED. */
    val ninebotWritableSettingKeys: List<String>
        get() = NinebotSettingsCatalog.rows
            .filter { it.kind != KIND_READONLY }
            .map { it.key }

    fun leaperKimLight(enabled: Boolean): ExperimentalWriteCommand =
        write(
            protocol = WheelProtocol.LeaperKim,
            purpose = if (enabled) "light_on" else "light_off",
            bytes = LeaperKimProtocolEngine().buildLightCommand(enabled),
            evidence = "Built by LeaperKimProtocolEngine and covered byte for byte by this " +
                "library's tests; not confirmed on hardware.",
        )

    fun leaperKimLowLight(enabled: Boolean): ExperimentalWriteCommand =
        write(
            protocol = WheelProtocol.LeaperKim,
            purpose = if (enabled) "low_light_on" else "low_light_off",
            bytes = buildLeaperKimLowLightCommand(enabled),
            evidence = "Built by the LeaperKim engine; not confirmed on hardware.",
        )

    fun leaperKimShutdownTimer(): ExperimentalWriteCommand =
        write(
            protocol = WheelProtocol.LeaperKim,
            purpose = "shutdown_timer",
            bytes = LeaperKimProtocolEngine().buildFixedShutdownTimerCommand(),
            evidence = "Built by LeaperKimProtocolEngine; not confirmed on hardware.",
        )

    fun ninebotTelemetryPoll(): ExperimentalReadCommand =
        read(
            purpose = "telemetry_poll",
            bytes = NinebotFrameCodec.build(
                destination = NinebotAddress.Controller,
                command = NinebotCommand.Read,
                param = NinebotParam.LiveData,
                data = byteArrayOf(0x20),
            ),
            evidence = "Built by NinebotFrameCodec and covered by the framing fixtures; a read, " +
                "so it changes nothing on the wheel.",
        )

    fun ninebotSetting(key: String, value: Int, currentWord: Int? = null): ExperimentalWriteCommand {
        val bytes = buildNinebotSettingCommand(key, value, currentWord)
            ?: throw IllegalArgumentException("Unsupported or unsafe Ninebot setting: $key")
        return write(
            protocol = WheelProtocol.Ninebot,
            purpose = key,
            bytes = bytes,
            evidence = "Built from the Ninebot settings catalog and covered byte for byte by " +
                "this library's tests; not confirmed on hardware.",
        )
    }

    private fun write(
        protocol: WheelProtocol,
        purpose: String,
        bytes: ByteArray,
        evidence: String,
    ) = ExperimentalWriteCommand.create(
        protocol = protocol,
        purpose = purpose,
        bytes = bytes,
        validation = CommandValidation(CommandStatus.UNTESTED, evidence),
    )

    private fun read(purpose: String, bytes: ByteArray, evidence: String) = ExperimentalReadCommand(
        protocol = WheelProtocol.Ninebot,
        purpose = purpose,
        bytes = bytes,
        validation = CommandValidation(CommandStatus.VALIDATED, evidence),
    )
}
