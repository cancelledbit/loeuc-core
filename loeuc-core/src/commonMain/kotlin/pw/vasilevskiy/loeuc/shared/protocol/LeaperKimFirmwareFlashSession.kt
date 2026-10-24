package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.concurrent.Volatile
import kotlin.math.abs

/** How a chunk is handed to the stack. */
enum class LeaperKimFlashWriteType {
    WithResponse,
    WithoutResponse,
}

/** What the transport did with a chunk it was handed. */
enum class LeaperKimFlashWriteOutcome {
    /** The stack took the chunk. */
    Accepted,

    /** The stack refused the chunk outright; nothing went out. */
    Refused,

    /**
     * Without response only: the transport is holding the chunk until its link can take it,
     * and calls [LeaperKimFirmwareFlashSession.onWriteReady] once the chunk has gone out. The
     * session sends nothing else meanwhile. A transport without flow control never returns it.
     */
    Deferred,
}

enum class LeaperKimFlashFailure {
    NotConnected,
    FileRejected,
    WheelNotTilted,
    WriteAlreadyInProgress,
    BootloaderNotEntered,

    /** A write with response came back with an error; the ATT status travels alongside. */
    ChunkWriteFailed,

    /** The stack refused a chunk before sending it. */
    ChunkWriteRefused,

    /**
     * The link went down while bytes were still due. Reported only by a transport that tells
     * the session about disconnects ([LeaperKimFirmwareFlashSession.onConnectionLost]).
     */
    ConnectionLost,
}

sealed class LeaperKimFlashState {
    /**
     * Connected with notifications on. [carPose] gates the tilt requirement; [hardwareCode] is
     * what the wheel calls itself, to compare against the image.
     */
    data class Ready(val carPose: Double?, val hardwareCode: String?) : LeaperKimFlashState()
    data object EnteringBootloader : LeaperKimFlashState()
    data class WaitingBootloader(val silenceMillis: Long) : LeaperKimFlashState()
    data class Uploading(val sentChunks: Int, val totalChunks: Int) : LeaperKimFlashState()
    data object Finalizing : LeaperKimFlashState()

    /** The whole file went out. [telemetryRestored] says whether the wheel spoke again within the check window. */
    data class Done(val telemetryRestored: Boolean) : LeaperKimFlashState()
    data class Failed(val failure: LeaperKimFlashFailure, val attStatus: Int) : LeaperKimFlashState()
}

/**
 * Everything the session needs from a platform: a clock, a timer, a characteristic to write
 * and somewhere to report. The session never opens a connection itself.
 */
interface LeaperKimFirmwareFlashPort {
    /** Wall-clock or monotonic milliseconds; only differences are used. */
    fun nowMillis(): Long

    /**
     * The write type the serial characteristic permits: with response when it offers it,
     * otherwise without. Null when there is no characteristic to write to - the port reports
     * the reason itself, and the session stops.
     */
    fun writeType(): LeaperKimFlashWriteType?

    fun write(bytes: ByteArray, type: LeaperKimFlashWriteType): LeaperKimFlashWriteOutcome

    /** Call [LeaperKimFirmwareFlashSession.onTimer] with [token] after [delayMillis]. */
    fun schedule(delayMillis: Long, token: Long)

    fun emit(state: LeaperKimFlashState)
}

/**
 * The LeaperKim firmware flash, as the official app performs it, with the transport left to the
 * platform.
 *
 * The sequence: refuse unless connected, the file passed its checks and the wheel is tilted
 * past [PoseTiltThreshold]; send `AT+RINTOPRO`; wait until telemetry has been silent for the
 * plan's silence window, which is how the app knows the wheel rebooted into its bootloader,
 * and give up after the entry timeout; send the file in chunks; then wait the reset window and
 * report whether telemetry came back. There is no reconnect anywhere: the BLE module is a
 * serial bridge that stays connected while the controller behind it reboots.
 *
 * Chunks go one at a time. With response, the next chunk waits for [onWriteResult]; a write
 * rejected with ATT status 3 (write not permitted) is resent once without response, which is
 * what many serial modules on `ffe1` only accept. Without response, the session paces chunks
 * [NoResponsePaceMillis] apart itself.
 *
 * Not thread-safe except for [onNotify]: every other call, including timer callbacks, has to
 * come from one thread.
 */
@ExperimentalWriteApi
class LeaperKimFirmwareFlashSession(private val port: LeaperKimFirmwareFlashPort) {

    private enum class Purpose { EnterBootloader, Firmware }

    private enum class Stage { Idle, EnteringBootloader, WaitingBootloader, Uploading, Finalizing, Finished }

    private class ChunkedWrite(
        val chunks: List<ByteArray>,
        var writeType: LeaperKimFlashWriteType,
        val purpose: Purpose,
    ) {
        var sent: Int = 0
        var triedOtherWriteType: Boolean = false
        var awaitingReady: Boolean = false
    }

    private var plan: LeaperKimFirmwareUploadPlan? = null
    private var pendingWrite: ChunkedWrite? = null
    private var stage = Stage.Idle

    @Volatile
    private var lastNotifyMillis: Long = 0L

    /** The last tilt the wheel reported, or null before any telemetry frame carried one. */
    var carPose: Double? = null
        private set

    /** What the wheel called itself, once it did. */
    var hardwareCode: String? = null
        private set

    /**
     * The rider's explicit override of the tilt gate, for a wheel lying on its side: its pitch
     * reads level, yet the power stage stays off just as it does when tilted. Off by default.
     */
    var skipTiltCheck: Boolean = false

    private val timers = HashMap<Long, () -> Unit>()
    private var nextToken = 1L

    /** True from `AT+RINTOPRO` until the file is out or the flash has failed. */
    val isWriting: Boolean
        get() = stage == Stage.EnteringBootloader || stage == Stage.WaitingBootloader || stage == Stage.Uploading

    /** Any notification from the wheel, on any characteristic. Safe to call from any thread. */
    fun onNotify() {
        lastNotifyMillis = port.nowMillis()
    }

    /** A decoded telemetry frame. A null [pose] leaves the last known tilt in place. */
    fun onTelemetry(pose: Double?, hardwareCode: String?) {
        hardwareCode?.let { this.hardwareCode = it }
        if (pose != null) {
            carPose = pose
            // The ready readout only refreshes before the flash has begun.
            if (plan == null) port.emit(LeaperKimFlashState.Ready(pose, this.hardwareCode))
        }
    }

    fun start(plan: LeaperKimFirmwareUploadPlan?, connected: Boolean) {
        if (!connected) {
            fail(LeaperKimFlashFailure.NotConnected)
            return
        }
        if (plan == null) {
            fail(LeaperKimFlashFailure.FileRejected)
            return
        }
        val pose = carPose
        if (!skipTiltCheck && (pose == null || abs(pose) < PoseTiltThreshold)) {
            fail(LeaperKimFlashFailure.WheelNotTilted)
            return
        }
        this.plan = plan
        emit(LeaperKimFlashState.EnteringBootloader)
        // The command goes out the same chunked path as the file, so one write callback drives both.
        writeChunked(plan.enterBootloaderCommand, plan.chunkSize, Purpose.EnterBootloader)
    }

    /** The acknowledgement of a write with response. [attStatus] is 0 on success. */
    fun onWriteResult(success: Boolean, attStatus: Int) {
        val write = pendingWrite ?: return
        // Without response the session paces itself; a callback some stacks still deliver for
        // that type would double the pace.
        if (write.writeType != LeaperKimFlashWriteType.WithResponse) return
        if (!success) {
            if (attStatus == AttWriteNotPermitted && !write.triedOtherWriteType) {
                write.triedOtherWriteType = true
                write.writeType = LeaperKimFlashWriteType.WithoutResponse
                sendNextChunk(write)
                return
            }
            pendingWrite = null
            fail(LeaperKimFlashFailure.ChunkWriteFailed, attStatus)
            return
        }
        write.sent += 1
        reportProgress(write)
        sendNextChunk(write)
    }

    /** The chunk a transport answered [LeaperKimFlashWriteOutcome.Deferred] for has gone out. */
    fun onWriteReady() {
        val write = pendingWrite ?: return
        if (!write.awaitingReady) return
        write.awaitingReady = false
        if (write.writeType == LeaperKimFlashWriteType.WithoutResponse) advanceWithoutResponse(write)
    }

    /**
     * The link went down. While bytes are still due this fails the flash at once; in the reset
     * window it is expected - the wheel reboots - and changes nothing.
     */
    fun onConnectionLost() {
        if (!isWriting) return
        timers.clear()
        pendingWrite = null
        fail(LeaperKimFlashFailure.ConnectionLost)
    }

    fun onTimer(token: Long) {
        timers.remove(token)?.invoke()
    }

    /** Stops everything: no further write, timer or state. */
    fun cancel() {
        timers.clear()
        pendingWrite = null
        stage = Stage.Finished
    }

    private fun waitForBootloaderThenSend(startedAtMillis: Long) {
        val builtPlan = plan ?: return
        val now = port.nowMillis()
        val silence = now - lastNotifyMillis
        if (silence >= builtPlan.telemetrySilenceMillis) {
            emit(LeaperKimFlashState.Uploading(0, builtPlan.totalChunks))
            writeChunked(builtPlan.firmwareBytes, builtPlan.chunkSize, Purpose.Firmware)
            return
        }
        if (now - startedAtMillis >= builtPlan.bootloaderEntryTimeoutMillis) {
            fail(LeaperKimFlashFailure.BootloaderNotEntered)
            return
        }
        emit(LeaperKimFlashState.WaitingBootloader(silence))
        schedule(BootloaderPollMillis) { waitForBootloaderThenSend(startedAtMillis) }
    }

    private fun writeChunked(bytes: ByteArray, chunkSize: Int, purpose: Purpose) {
        val writeType = port.writeType() ?: return
        if (pendingWrite != null) {
            fail(LeaperKimFlashFailure.WriteAlreadyInProgress)
            return
        }
        val chunks = (bytes.indices step chunkSize).map { start ->
            bytes.copyOfRange(start, minOf(start + chunkSize, bytes.size))
        }
        val write = ChunkedWrite(chunks, writeType, purpose)
        pendingWrite = write
        sendNextChunk(write)
    }

    private fun sendNextChunk(write: ChunkedWrite) {
        if (write.sent >= write.chunks.size) {
            pendingWrite = null
            schedule(0L) { complete(write) }
            return
        }
        when (port.write(write.chunks[write.sent], write.writeType)) {
            LeaperKimFlashWriteOutcome.Refused -> {
                pendingWrite = null
                fail(LeaperKimFlashFailure.ChunkWriteRefused)
                return
            }
            LeaperKimFlashWriteOutcome.Deferred -> if (write.writeType == LeaperKimFlashWriteType.WithoutResponse) {
                write.awaitingReady = true
                return
            }
            LeaperKimFlashWriteOutcome.Accepted -> Unit
        }
        if (write.writeType == LeaperKimFlashWriteType.WithoutResponse) advanceWithoutResponse(write)
    }

    private fun advanceWithoutResponse(write: ChunkedWrite) {
        write.sent += 1
        reportProgress(write)
        schedule(NoResponsePaceMillis) {
            if (pendingWrite === write) sendNextChunk(write)
        }
    }

    private fun reportProgress(write: ChunkedWrite) {
        if (write.purpose == Purpose.Firmware) {
            emit(LeaperKimFlashState.Uploading(write.sent, write.chunks.size))
        }
    }

    private fun complete(write: ChunkedWrite) {
        when (write.purpose) {
            Purpose.EnterBootloader -> waitForBootloaderThenSend(startedAtMillis = port.nowMillis())
            Purpose.Firmware -> {
                emit(LeaperKimFlashState.Finalizing)
                val uploadedAt = port.nowMillis()
                val resetCheck = plan?.resetCheckDelayMillis ?: LeaperKimFirmwareService.ResetCheckDelayMillis
                schedule(resetCheck) {
                    emit(LeaperKimFlashState.Done(telemetryRestored = lastNotifyMillis > uploadedAt))
                }
            }
        }
    }

    private fun schedule(delayMillis: Long, action: () -> Unit) {
        val token = nextToken++
        timers[token] = action
        port.schedule(delayMillis, token)
    }

    private fun fail(failure: LeaperKimFlashFailure, attStatus: Int = 0) {
        emit(LeaperKimFlashState.Failed(failure, attStatus))
    }

    private fun emit(state: LeaperKimFlashState) {
        when (state) {
            is LeaperKimFlashState.EnteringBootloader -> stage = Stage.EnteringBootloader
            is LeaperKimFlashState.WaitingBootloader -> stage = Stage.WaitingBootloader
            is LeaperKimFlashState.Uploading -> stage = Stage.Uploading
            is LeaperKimFlashState.Finalizing -> stage = Stage.Finalizing
            is LeaperKimFlashState.Done -> stage = Stage.Finished
            // A refusal before the first byte leaves a flash that never started as it was.
            is LeaperKimFlashState.Failed -> if (stage != Stage.Idle) stage = Stage.Finished
            is LeaperKimFlashState.Ready -> Unit
        }
        port.emit(state)
    }

    companion object {
        /** The official app refuses while `carPose` lies within this of level. */
        const val PoseTiltThreshold = 4_000.0

        /** How often the bootloader wait looks at the silence. */
        const val BootloaderPollMillis = 500L

        /** Pause between chunks written without response. */
        const val NoResponsePaceMillis = 40L

        /** ATT "write not permitted". */
        const val AttWriteNotPermitted = 3

        /** Wording for a failure in the interface language. */
        fun failureText(failure: LeaperKimFlashFailure, attStatus: Int, russian: Boolean): String = when (failure) {
            LeaperKimFlashFailure.NotConnected ->
                if (russian) "Нет подключения к колесу." else "Not connected to the wheel."
            LeaperKimFlashFailure.FileRejected ->
                if (russian) "Файл не прошёл проверку — прошивка заблокирована." else "The file failed its checks; sending is blocked."
            LeaperKimFlashFailure.WheelNotTilted ->
                if (russian) "Наклоните колесо — прошивка начинается только в наклонённом положении, как и в родном приложении, — и повторите." else "Tilt the wheel, as the official app asks, and try again."
            LeaperKimFlashFailure.WriteAlreadyInProgress ->
                if (russian) "Прошивка уже идёт — дождитесь окончания." else "The previous write has not finished yet."
            LeaperKimFlashFailure.BootloaderNotEntered ->
                if (russian) {
                    "Колесо не перешло в режим прошивки. Выключите и включите колесо и попробуйте снова."
                } else {
                    "The wheel did not enter programming mode: telemetry did not stop after AT+RINTOPRO."
                }
            LeaperKimFlashFailure.ChunkWriteFailed ->
                if (russian) "Не удалось передать часть прошивки (код Bluetooth $attStatus). Выключите и включите колесо: заработало — прошейте ещё раз; молчит — поможет «Восстановление прошивки» в LoEUC на Android." else "Chunk write failed: status=$attStatus."
            LeaperKimFlashFailure.ChunkWriteRefused ->
                if (russian) "Передача файла оборвалась. Выключите и включите колесо: заработало — прошейте ещё раз; молчит — поможет «Восстановление прошивки» в LoEUC на Android." else "Bluetooth refused to write the chunk."
            LeaperKimFlashFailure.ConnectionLost ->
                if (russian) "Связь с колесом пропала во время прошивки. Выключите и включите колесо: заработало — прошейте ещё раз; молчит — поможет «Восстановление прошивки» в LoEUC на Android." else "The connection to the wheel was lost during the flash."
        }
    }
}

/**
 * The tilt and the hardware code, read off raw notifications by the shared LeaperKim engine -
 * for a platform whose flasher holds its own connection and so sees no decoded telemetry.
 */
class LeaperKimFlashTelemetryTap {
    private val engine = LeaperKimProtocolEngine()

    /** Feeds one notification; returns the telemetry a completed frame produced, or null. */
    fun consume(chunk: ByteArray): LeaperKimFlashTelemetrySample? {
        val telemetry = engine.consume(chunk) ?: return null
        return LeaperKimFlashTelemetrySample(
            carPose = telemetry.carPose.takeIf { it.isFinite() },
            hardwareCode = telemetry.hardwareCode,
        )
    }
}

data class LeaperKimFlashTelemetrySample(val carPose: Double?, val hardwareCode: String?)
