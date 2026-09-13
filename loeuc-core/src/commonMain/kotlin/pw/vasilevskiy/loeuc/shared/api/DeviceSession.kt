package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot
import pw.vasilevskiy.loeuc.shared.protocol.BegodeFirmware
import pw.vasilevskiy.loeuc.shared.protocol.BegodeProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.BegodeSettingCapability
import pw.vasilevskiy.loeuc.shared.protocol.BegodeTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.HwChargerProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyIdentity
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyRequests
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacySettingCapability
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacySettingWrite
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacySettings
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacySettingsCatalog
import pw.vasilevskiy.loeuc.shared.protocol.InmotionLegacyUpdate
import pw.vasilevskiy.loeuc.shared.protocol.InmotionProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.InmotionProtocolUpdate
import pw.vasilevskiy.loeuc.shared.protocol.KingSongIdentity
import pw.vasilevskiy.loeuc.shared.protocol.KingSongProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingCapability
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingWrite
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingsCatalog
import pw.vasilevskiy.loeuc.shared.protocol.KingSongSettingsState
import pw.vasilevskiy.loeuc.shared.protocol.KingSongTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.LeaperKimProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.LeaperKimTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.NinebotProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.NinebotProtocolUpdate
import pw.vasilevskiy.loeuc.shared.protocol.NinebotBmsPack
import pw.vasilevskiy.loeuc.shared.protocol.NinebotDiagnostics
import pw.vasilevskiy.loeuc.shared.protocol.NinebotSettingCapability
import pw.vasilevskiy.loeuc.shared.protocol.KingSongBmsPack
import pw.vasilevskiy.loeuc.shared.protocol.LeaperKimBmsSnapshot
import pw.vasilevskiy.loeuc.shared.protocol.InmotionBmsSnapshot
import pw.vasilevskiy.loeuc.shared.protocol.SkatChargerProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.SkatChargerTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.SolowheelXtremeProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.SolowheelXtremeTelemetry

/** Options controlling state retained by a wheel session. */
data class SessionOptions(
    val emitFrames: Boolean = false,
    val deviceName: String? = null,
    val frameWindowCapacity: Int = 0,
)

/** A copied notification or response chunk observed by a session. */
class DeviceFrame(
    val timestampMs: Long,
    val type: String,
    val characteristicUuid: String,
    bytes: ByteArray,
) {
    val bytes: ByteArray = bytes.copyOf()
}

/** Bounded storage for raw frames; capacity zero disables retention. */
class FrameWindow(private val capacity: Int) {
    private val frames = ArrayDeque<DeviceFrame>()

    /** Adds a frame and drops the oldest frame when the configured capacity is exceeded. */
    fun add(frame: DeviceFrame) {
        if (capacity <= 0) return
        frames += frame
        while (frames.size > capacity) frames.removeFirst()
    }

    /** Returns a snapshot whose byte arrays are safe for callers to retain. */
    fun snapshot(): List<DeviceFrame> = frames.map {
        DeviceFrame(it.timestampMs, it.type, it.characteristicUuid, it.bytes)
    }

    /** Removes every retained frame. */
    fun clear() = frames.clear()
}

/** A battery pack with the fields shared by the supported protocol engines. */
data class DeviceBatteryPack(
    val index: Int,
    val cells: List<Double> = emptyList(),
    val temperaturesC: List<Double> = emptyList(),
    val voltageV: Double? = null,
    val currentA: Double? = null,
)

/** Battery state accumulated from all BMS pages seen by a session. */
data class DeviceBatterySnapshot(val packs: List<DeviceBatteryPack>) {
    /** Average of every finite cell voltage across every reported pack. */
    val cellVoltageAverageV: Double?
        get() = packs.flatMap { it.cells }.filter(Double::isFinite).takeIf { it.isNotEmpty() }?.average()

    /** Difference between the highest and lowest finite reported cell voltage. */
    val cellVoltageDeltaV: Double?
        get() {
            val cells = packs.flatMap { it.cells }.filter(Double::isFinite)
            return if (cells.isEmpty()) null else cells.maxOrNull()!! - cells.minOrNull()!!
        }
}

/** Diagnostic codes exposed without manufacturing localized UI text in the protocol core. */
data class DeviceDiagnostics(val codes: List<Int>)

/**
 * A setting discovered by a protocol engine: its key, the value the device reported (null until
 * it has) and whether a write for it can be built. The write itself is [WheelSession.writeSetting]
 * or, for Ninebot, [WheelCommands.settingWrite].
 */
data class DeviceSettingCapability(val key: String, val value: Int?, val writable: Boolean)

/** What [WheelSession.writeSetting] made of a request. */
sealed interface SettingWriteResult {
    /**
     * Bytes for the caller to write, followed by [readbacks] - the reads that bring the device's
     * own value back into the session's settings once the write has landed.
     */
    class Send(val command: ExperimentalWriteCommand, readbacks: List<ByteArray>) : SettingWriteResult {
        private val reads: List<ByteArray> = readbacks.map { it.copyOf() }
        val readbacks: List<ByteArray> get() = reads.map { it.copyOf() }
    }

    /**
     * Queued inside the session. It goes out through [WheelSession.nextRequest] when the device is
     * free, and the session re-reads the settings afterwards on its own; [command] is for
     * inspection, not for sending a second time.
     */
    class Queued(val command: ExperimentalWriteCommand) : SettingWriteResult

    /** Nothing was built or queued. */
    data class Rejected(val reason: String) : SettingWriteResult
}

/** Brand-specific update, avoiding an untyped `Any` boundary for Kotlin/Native consumers. */
sealed interface WheelBrandUpdate {
    data class Begode(val telemetry: BegodeTelemetry) : WheelBrandUpdate
    data class KingSong(val telemetry: KingSongTelemetry) : WheelBrandUpdate
    data class Inmotion(val update: InmotionProtocolUpdate) : WheelBrandUpdate
    data class InmotionLegacy(val update: InmotionLegacyUpdate) : WheelBrandUpdate
    data class LeaperKim(val telemetry: LeaperKimTelemetry) : WheelBrandUpdate
    data class Ninebot(val update: NinebotProtocolUpdate) : WheelBrandUpdate
    data class SolowheelXtreme(val telemetry: SolowheelXtremeTelemetry) : WheelBrandUpdate
}

/** One cumulative result emitted by [WheelSession.ingest]. */
data class TelemetryUpdate(
    val protocol: WheelProtocol,
    val timestampMs: Long,
    val telemetry: DeviceTelemetry,
    val snapshot: TelemetrySnapshot,
    val battery: DeviceBatterySnapshot?,
    val diagnostics: DeviceDiagnostics?,
    val settings: List<DeviceSettingCapability>,
    val frames: List<DeviceFrame>,
    val brand: WheelBrandUpdate,
)

/**
 * Command surface backed directly by the existing protocol engines.
 *
 * For [WheelProtocol.InmotionLegacy] these are the three requests of its session - PIN and slow
 * info as [initialReads], fast info as [telemetryPoll] - for a transport that can only send a
 * fixed schedule. A wheel of that family answers one request at a time and the next question
 * depends on the last answer, so a transport that can react to notifications should drive
 * [WheelSession.nextRequest] instead, and never both.
 */
class WheelCommands internal constructor(
    private val protocol: WheelProtocol,
    val initialReads: List<ByteArray>,
    val telemetryPoll: ByteArray?,
    val bmsReads: List<ByteArray>,
) {
    /** Builds an explicitly untested write for the selected protocol; callers must not send it blindly. */
    fun settingWrite(key: String, value: Int, currentWord: Int? = null): ExperimentalWriteCommand =
        when (protocol) {
            WheelProtocol.Ninebot -> ExperimentalCommands.ninebotSetting(key, value, currentWord)
            else -> throw IllegalArgumentException("No experimental setting builder for $protocol")
        }
}

/** Stateful synchronous decoder for one wheel protocol. */
class WheelSession private constructor(
    val protocol: WheelProtocol,
    private val options: SessionOptions,
) {
    private val begode = BegodeProtocolEngine()
    private val kingSong = KingSongProtocolEngine()
    private val inmotion = InmotionProtocolEngine()
    private val inmotionLegacy = InmotionLegacyProtocolEngine()
    private val leaperKim = LeaperKimProtocolEngine()
    private val ninebot = NinebotProtocolEngine()
    private val solowheel = SolowheelXtremeProtocolEngine()
    private val frameWindow = FrameWindow(options.frameWindowCapacity)
    private var accumulated = DeviceTelemetry.of()
    private var accumulatedBattery: DeviceBatterySnapshot? = null
    private var accumulatedDiagnostics: DeviceDiagnostics? = null
    private var last: TelemetryUpdate? = null

    /** Latest cumulative update, or null before the first valid protocol result. */
    val latest: TelemetryUpdate? get() = last

    /** Latest cumulative neutral telemetry, or an empty container before decoding. */
    val telemetry: DeviceTelemetry get() = last?.telemetry ?: accumulated

    /** Read-only commands supported by the selected engine. */
    val commands: WheelCommands = commandsFor(protocol)

    /** Feeds one BLE chunk and returns every update resolved by that chunk. */
    fun ingest(chunk: ByteArray, timestampMs: Long, characteristicUuid: String = ""): List<TelemetryUpdate> {
        if (chunk.isEmpty()) return emptyList()
        if (options.emitFrames) {
            frameWindow.add(DeviceFrame(timestampMs, "notify", characteristicUuid, chunk))
        }
        val results = when (protocol) {
            WheelProtocol.Begode -> begode.consume(chunk)?.let { listOf(WheelBrandUpdate.Begode(it) to it.toDeviceTelemetry()) }.orEmpty()
            WheelProtocol.KingSong -> kingSong.consume(chunk)?.let { listOf(WheelBrandUpdate.KingSong(it) to it.toDeviceTelemetry()) }.orEmpty()
            WheelProtocol.Inmotion -> inmotion.consume(chunk)?.let { update ->
                listOf(WheelBrandUpdate.Inmotion(update) to (update.telemetry?.toDeviceTelemetry() ?: DeviceTelemetry.of()))
            }.orEmpty()
            WheelProtocol.InmotionLegacy -> inmotionLegacy.consume(chunk, timestampMs)
                .takeIf { it.messages.isNotEmpty() }
                ?.let { update ->
                    listOf(WheelBrandUpdate.InmotionLegacy(update) to (update.telemetry?.toDeviceTelemetry() ?: DeviceTelemetry.of()))
                }.orEmpty()
            WheelProtocol.LeaperKim, WheelProtocol.Nosfet -> leaperKim.consume(chunk)?.let { listOf(WheelBrandUpdate.LeaperKim(it) to it.toDeviceTelemetry()) }.orEmpty()
            WheelProtocol.Ninebot -> ninebot.consume(chunk).map { it to it.toDeviceTelemetry() }.map { (update, mapped) -> WheelBrandUpdate.Ninebot(update) to mapped }
            WheelProtocol.SolowheelXtreme -> solowheel.consume(chunk)?.let { listOf(WheelBrandUpdate.SolowheelXtreme(it) to it.toDeviceTelemetry()) }.orEmpty()
        }
        return results.map { (brand, delta) -> publish(timestampMs, brand, delta) }
    }

    /**
     * The request to write now, or null when nothing is due. Only [WheelProtocol.InmotionLegacy]
     * has such a conversation; every other protocol returns null and is served by [commands].
     *
     * A legacy wheel sends nothing until asked. Write what this returns (in chunks of at most
     * 20 bytes), call it again after every [ingest] and on a short timer - 25 ms is what the
     * reference client uses - and report a write that did not go out with [onWriteFailed]. The
     * sequence is the default PIN, slow info until the wheel identifies itself, then fast info as
     * telemetry and keep-alive; none of it changes anything stored on the wheel.
     *
     * @param timestampMs the same monotonic clock passed to [ingest]; the pacing is measured on it.
     */
    fun nextRequest(timestampMs: Long): ByteArray? = when (protocol) {
        WheelProtocol.InmotionLegacy -> inmotionLegacy.nextRequest(timestampMs)
        else -> null
    }

    /** The request [nextRequest] last returned could not be written; it is retried shortly. */
    fun onWriteFailed(timestampMs: Long) {
        if (protocol == WheelProtocol.InmotionLegacy) inmotionLegacy.onWriteFailed(timestampMs)
    }

    /** KingSong: name, firmware and serial as far as the wheel has answered; null for other protocols. */
    val kingSongIdentity: KingSongIdentity?
        get() = kingSong.identity().takeIf { protocol == WheelProtocol.KingSong }

    /** KingSong: the settings the wheel has reported back; null for other protocols. */
    val kingSongSettings: KingSongSettingsState?
        get() = kingSong.settings().takeIf { protocol == WheelProtocol.KingSong }

    /** Begode: the firmware that answered `V`, which decides how custom-firmware frames read; null until then. */
    val begodeFirmware: BegodeFirmware?
        get() = begode.firmware().takeIf { protocol == WheelProtocol.Begode }

    /** Legacy InMotion: the identity from the latest slow info reply, or null. */
    val inmotionLegacyIdentity: InmotionLegacyIdentity?
        get() = inmotionLegacy.identity.takeIf { protocol == WheelProtocol.InmotionLegacy }

    /** Legacy InMotion: the settings from the latest slow info reply, or null. */
    val inmotionLegacySettings: InmotionLegacySettings?
        get() = inmotionLegacy.settings.takeIf { protocol == WheelProtocol.InmotionLegacy }

    /** KingSong settings rows with titles, ranges and options; empty for other protocols. */
    fun kingSongSettingRows(): List<KingSongSettingCapability> =
        kingSongSettings?.let(KingSongSettingsCatalog::capabilities).orEmpty()

    /** Begode settings rows, including the rows a custom firmware adds; empty for other protocols. */
    fun begodeSettingRows(): List<BegodeSettingCapability> =
        if (protocol == WheelProtocol.Begode) begode.settingsCapabilities() else emptyList()

    /** Legacy InMotion settings rows with titles, value text and the model's ranges; empty before slow info. */
    fun inmotionLegacySettingRows(): List<InmotionLegacySettingCapability> {
        val settings = inmotionLegacySettings ?: return emptyList()
        return InmotionLegacySettingsCatalog.capabilities(inmotionLegacy.identity, settings)
    }

    /**
     * Builds the write that sets [key] to [value] from what the device has reported so far.
     *
     * - [WheelProtocol.KingSong]: [SettingWriteResult.Send] with the frame and the reads that
     *   confirm it. The speed alarms share one frame, so they are rejected until the wheel has
     *   reported all four.
     * - [WheelProtocol.InmotionLegacy]: [SettingWriteResult.Queued]. The command waits in the
     *   session and leaves through [nextRequest], followed by a slow info re-read that replaces
     *   the sent value with the wheel's in [inmotionLegacySettings]. Rejected until slow info has
     *   named the model and carried the field.
     * - Every other protocol: [SettingWriteResult.Rejected]; Ninebot writes are
     *   [WheelCommands.settingWrite].
     *
     * Nothing here has been confirmed on hardware; see [ExperimentalCommands].
     */
    @ExperimentalWriteApi
    fun writeSetting(key: String, value: Int): SettingWriteResult = when (protocol) {
        WheelProtocol.KingSong -> when (val write = KingSongSettingsCatalog.write(key, value, kingSong.settings())) {
            is KingSongSettingWrite.Command -> SettingWriteResult.Send(
                command = ExperimentalWriteCommand.create(
                    protocol = protocol,
                    purpose = key,
                    bytes = write.bytes,
                    validation = CommandValidation(
                        CommandStatus.UNTESTED,
                        "Built by KingSongSettingsCatalog; WheelLog and the official app agree on the " +
                            "bytes. Not confirmed on hardware.",
                    ),
                ),
                readbacks = KingSongSettingsCatalog.readsFor(key),
            )
            is KingSongSettingWrite.Rejected -> SettingWriteResult.Rejected(write.reason)
        }
        WheelProtocol.InmotionLegacy -> when (
            val write = InmotionLegacySettingsCatalog.write(key, value, inmotionLegacy.identity, inmotionLegacy.settings)
        ) {
            is InmotionLegacySettingWrite.Command -> {
                val bytes = write.bytes
                if (inmotionLegacy.enqueue(bytes)) {
                    SettingWriteResult.Queued(
                        ExperimentalWriteCommand.create(
                            protocol = protocol,
                            purpose = key,
                            bytes = bytes,
                            validation = CommandValidation(
                                CommandStatus.UNTESTED,
                                "Built by InmotionLegacySettingsCatalog after WheelLog's adapter and " +
                                    "cross-checked against the official app; not confirmed on hardware.",
                            ),
                        ),
                    )
                } else {
                    SettingWriteResult.Rejected("The session already holds the most commands it queues.")
                }
            }
            is InmotionLegacySettingWrite.Rejected -> SettingWriteResult.Rejected(write.reason)
        }
        else -> SettingWriteResult.Rejected("No session setting writes for $protocol.")
    }

    /** Resets the decoder and every accumulated projection. */
    fun reset() {
        begode.reset(); kingSong.reset(); inmotion.reset(); leaperKim.reset(); ninebot.reset(); solowheel.reset()
        inmotionLegacy.reset()
        accumulated = DeviceTelemetry.of()
        accumulatedBattery = null
        accumulatedDiagnostics = null
        last = null
        frameWindow.clear()
    }

    private fun publish(timestampMs: Long, brand: WheelBrandUpdate, delta: DeviceTelemetry): TelemetryUpdate {
        accumulated = accumulated.mergedWith(delta)
        val battery = batteryFor(brand)
        if (brand is WheelBrandUpdate.Inmotion && battery != null) accumulatedBattery = battery
        diagnosticsFor(brand)?.let { accumulatedDiagnostics = it }
        val update = TelemetryUpdate(
            protocol = protocol,
            timestampMs = timestampMs,
            telemetry = accumulated,
            snapshot = accumulated.toSnapshot(timestampMs),
            battery = battery,
            diagnostics = accumulatedDiagnostics,
            settings = settingsFor(brand),
            frames = frameWindow.snapshot(),
            brand = brand,
        )
        last = update
        return update
    }

    private fun diagnosticsFor(brand: WheelBrandUpdate): DeviceDiagnostics? = when (brand) {
        is WheelBrandUpdate.Ninebot -> ninebot.latestDiagnostics().toDeviceDiagnostics()
        is WheelBrandUpdate.Inmotion -> brand.update.diagnosticsSnapshot?.let { snapshot ->
            DeviceDiagnostics(
                codes = listOf(snapshot.errorCode.toInt())
                    .filter { it != 0 } + snapshot.decodedItems.filter { it.isActive }.map { it.index },
            )
        }
        else -> null
    }

    private fun settingsFor(brand: WheelBrandUpdate): List<DeviceSettingCapability> = when (brand) {
        is WheelBrandUpdate.Ninebot -> ninebot.settingsCapabilities().map { it.toDeviceSettingCapability() }
        is WheelBrandUpdate.Inmotion -> inmotion.settingsCapabilities().map { it.toDeviceSettingCapability() }
        is WheelBrandUpdate.LeaperKim -> leaperKim.settingsCapabilities().map { it.toDeviceSettingCapability() }
        is WheelBrandUpdate.InmotionLegacy ->
            inmotionLegacy.settings?.toDeviceSettingCapabilities(inmotionLegacy.identity).orEmpty()
        is WheelBrandUpdate.KingSong -> KingSongSettingsCatalog.capabilities(kingSong.settings()).map { it.toDeviceSettingCapability() }
        is WheelBrandUpdate.Begode -> begode.settingsCapabilities().map { it.toDeviceSettingCapability() }
        is WheelBrandUpdate.SolowheelXtreme -> emptyList()
    }

    private fun batteryFor(brand: WheelBrandUpdate): DeviceBatterySnapshot? = when (brand) {
        is WheelBrandUpdate.Begode -> null
        is WheelBrandUpdate.KingSong -> kingSong.bmsPacks().toKingSongBatterySnapshot()
        is WheelBrandUpdate.Inmotion -> brand.update.bmsSnapshot?.toBatterySnapshot() ?: accumulatedBattery
        is WheelBrandUpdate.LeaperKim -> leaperKim.bmsSnapshot().toBatterySnapshot()
        is WheelBrandUpdate.Ninebot -> (brand.update.bmsPacks ?: ninebot.bmsPacks()).toNinebotBatterySnapshot()
        is WheelBrandUpdate.SolowheelXtreme -> null
        is WheelBrandUpdate.InmotionLegacy -> null
    }

    companion object {
        /** Creates a session for a wheel protocol and keeps no raw frames by default. */
        fun of(protocol: WheelProtocol, options: SessionOptions = SessionOptions()): WheelSession = WheelSession(protocol, options)
    }

    private fun commandsFor(protocol: WheelProtocol): WheelCommands = when (protocol) {
        WheelProtocol.KingSong -> WheelCommands(protocol, kingSong.initialReadCommands(), kingSong.telemetryPollingCommand(), emptyList())
        WheelProtocol.Ninebot -> WheelCommands(protocol, ninebot.initialReadCommands(), ninebot.telemetryPollingCommand(), ninebot.bmsPollingCommands())
        WheelProtocol.Inmotion -> WheelCommands(protocol, listOf(inmotion.mainInfoCommand(), inmotion.versionInfoCommand(), inmotion.settingsCommand(), inmotion.settingsAllCommand()), inmotion.realtimeCommand(), inmotion.bmsReadCommands())
        WheelProtocol.InmotionLegacy -> WheelCommands(protocol, listOf(InmotionLegacyRequests.pin(), InmotionLegacyRequests.slowInfo()), InmotionLegacyRequests.fastInfo(), emptyList())
        else -> WheelCommands(protocol, emptyList(), null, emptyList())
    }
}

private fun NinebotDiagnostics.toDeviceDiagnostics(): DeviceDiagnostics = DeviceDiagnostics(
    codes = listOf(errorCode1, errorCode2, warningCode1, warningCode2, liveErrorCode, liveAlarmCode)
        .filter { it != 0 }
        .distinct(),
)

private fun NinebotSettingCapability.toDeviceSettingCapability(): DeviceSettingCapability =
    DeviceSettingCapability(key, rawValue, kind != "readonly")

private fun pw.vasilevskiy.loeuc.shared.protocol.InmotionSettingCapability.toDeviceSettingCapability(): DeviceSettingCapability =
    DeviceSettingCapability(key, rawValue, kind != pw.vasilevskiy.loeuc.shared.protocol.KIND_READONLY)

private fun pw.vasilevskiy.loeuc.shared.protocol.LeaperKimSettingCapability.toDeviceSettingCapability(): DeviceSettingCapability =
    DeviceSettingCapability(key, rawValue.takeIf { isSupported }, isSupported && commandId != null)

private fun List<KingSongBmsPack>.toKingSongBatterySnapshot(): DeviceBatterySnapshot? =
    filter(KingSongBmsPack::hasData).map { DeviceBatteryPack(it.index, it.cells, it.temperatures, it.voltage, it.currentAmps) }
        .takeIf { it.isNotEmpty() }?.let(::DeviceBatterySnapshot)

private fun List<NinebotBmsPack>.toNinebotBatterySnapshot(): DeviceBatterySnapshot? =
    filter(NinebotBmsPack::hasData).map { DeviceBatteryPack(it.index, it.cells, listOfNotNull(it.temperature1, it.temperature2), it.voltage, it.currentAmps) }
        .takeIf { it.isNotEmpty() }?.let(::DeviceBatterySnapshot)

private fun LeaperKimBmsSnapshot.toBatterySnapshot(): DeviceBatterySnapshot =
    DeviceBatterySnapshot(batteries.map { DeviceBatteryPack(it.index, it.cells.map { cell -> cell.voltage }, it.temperatures.map { temperature -> temperature.celsius }, currentA = it.currentAmps, voltageV = it.maxChargeVoltage) })

private fun InmotionBmsSnapshot.toBatterySnapshot(): DeviceBatterySnapshot =
    DeviceBatterySnapshot(batteries.map { battery -> DeviceBatteryPack(battery.index, voltageV = battery.voltage, currentA = battery.chargeCurrent) })

/** Stateful synchronous decoder for the two supported charger protocols. */
class ChargerSession private constructor(val protocol: ChargerProtocol) {
    private val hw = HwChargerProtocolEngine()
    private val skat = SkatChargerProtocolEngine()
    private var telemetryState = DeviceTelemetry.of()

    /** Latest charger telemetry, or empty before the first valid frame. */
    val telemetry: DeviceTelemetry get() = telemetryState

    /** Feeds one charger notification and returns zero or one update. */
    fun ingest(chunk: ByteArray, timestampMs: Long): ChargerTelemetryUpdate? {
        val mapped = when (protocol) {
            ChargerProtocol.HwSmart -> hw.consume(chunk)?.let { it to it.toDeviceTelemetry() }
            ChargerProtocol.SkatCanControl -> skat.consume(chunk)?.let { it to it.toDeviceTelemetry() }
        } ?: return null
        telemetryState = telemetryState.mergedWith(mapped.second)
        return ChargerTelemetryUpdate(protocol, timestampMs, telemetryState, telemetryState.toSnapshot(timestampMs))
    }

    /** Clears charger decoder state and accumulated telemetry. */
    fun reset() {
        hw.reset(); skat.reset(); telemetryState = DeviceTelemetry.of()
    }

    /** Poll bytes for a charger protocol; SKAT is unsolicited and returns no poll. */
    fun pollCommands(): List<ByteArray> = when (protocol) {
        ChargerProtocol.HwSmart -> listOf(hw.telemetryPollingCommand())
        ChargerProtocol.SkatCanControl -> skat.pollingCommands()
    }

    companion object {
        /** Creates a charger session. */
        fun of(protocol: ChargerProtocol): ChargerSession = ChargerSession(protocol)
    }
}

/** One cumulative charger update. */
data class ChargerTelemetryUpdate(
    val protocol: ChargerProtocol,
    val timestampMs: Long,
    val telemetry: DeviceTelemetry,
    val snapshot: TelemetrySnapshot,
)
