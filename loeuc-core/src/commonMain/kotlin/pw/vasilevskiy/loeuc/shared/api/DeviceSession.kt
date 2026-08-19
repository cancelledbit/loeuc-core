package pw.vasilevskiy.loeuc.shared.api

import pw.vasilevskiy.loeuc.shared.alerts.model.TelemetrySnapshot
import pw.vasilevskiy.loeuc.shared.protocol.BegodeProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.BegodeTelemetry
import pw.vasilevskiy.loeuc.shared.protocol.HwChargerProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.InmotionProtocolEngine
import pw.vasilevskiy.loeuc.shared.protocol.InmotionProtocolUpdate
import pw.vasilevskiy.loeuc.shared.protocol.KingSongProtocolEngine
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

/** A setting discovered by a protocol engine. This public API exposes no write bytes. */
data class DeviceSettingCapability(val key: String, val value: Int?, val writable: Boolean)

/** Brand-specific update, avoiding an untyped `Any` boundary for Kotlin/Native consumers. */
sealed interface WheelBrandUpdate {
    data class Begode(val telemetry: BegodeTelemetry) : WheelBrandUpdate
    data class KingSong(val telemetry: KingSongTelemetry) : WheelBrandUpdate
    data class Inmotion(val update: InmotionProtocolUpdate) : WheelBrandUpdate
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

/** Command surface backed directly by the existing protocol engines. */
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
            WheelProtocol.LeaperKim, WheelProtocol.Nosfet -> leaperKim.consume(chunk)?.let { listOf(WheelBrandUpdate.LeaperKim(it) to it.toDeviceTelemetry()) }.orEmpty()
            WheelProtocol.Ninebot -> ninebot.consume(chunk).map { it to it.toDeviceTelemetry() }.map { (update, mapped) -> WheelBrandUpdate.Ninebot(update) to mapped }
            WheelProtocol.SolowheelXtreme -> solowheel.consume(chunk)?.let { listOf(WheelBrandUpdate.SolowheelXtreme(it) to it.toDeviceTelemetry()) }.orEmpty()
        }
        return results.map { (brand, delta) -> publish(timestampMs, brand, delta) }
    }

    /** Resets the decoder and every accumulated projection. */
    fun reset() {
        begode.reset(); kingSong.reset(); inmotion.reset(); leaperKim.reset(); ninebot.reset(); solowheel.reset()
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
        else -> emptyList()
    }

    private fun batteryFor(brand: WheelBrandUpdate): DeviceBatterySnapshot? = when (brand) {
        is WheelBrandUpdate.Begode -> null
        is WheelBrandUpdate.KingSong -> kingSong.bmsPacks().toKingSongBatterySnapshot()
        is WheelBrandUpdate.Inmotion -> brand.update.bmsSnapshot?.toBatterySnapshot() ?: accumulatedBattery
        is WheelBrandUpdate.LeaperKim -> leaperKim.bmsSnapshot().toBatterySnapshot()
        is WheelBrandUpdate.Ninebot -> (brand.update.bmsPacks ?: ninebot.bmsPacks()).toNinebotBatterySnapshot()
        is WheelBrandUpdate.SolowheelXtreme -> null
    }

    companion object {
        /** Creates a session for a wheel protocol and keeps no raw frames by default. */
        fun of(protocol: WheelProtocol, options: SessionOptions = SessionOptions()): WheelSession = WheelSession(protocol, options)
    }

    private fun commandsFor(protocol: WheelProtocol): WheelCommands = when (protocol) {
        WheelProtocol.KingSong -> WheelCommands(protocol, kingSong.initialReadCommands(), kingSong.telemetryPollingCommand(), emptyList())
        WheelProtocol.Ninebot -> WheelCommands(protocol, ninebot.initialReadCommands(), ninebot.telemetryPollingCommand(), ninebot.bmsPollingCommands())
        WheelProtocol.Inmotion -> WheelCommands(protocol, listOf(inmotion.mainInfoCommand(), inmotion.versionInfoCommand(), inmotion.settingsCommand(), inmotion.settingsAllCommand()), inmotion.realtimeCommand(), inmotion.bmsReadCommands())
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
