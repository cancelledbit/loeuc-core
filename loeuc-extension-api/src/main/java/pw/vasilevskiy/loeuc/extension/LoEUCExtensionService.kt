package pw.vasilevskiy.loeuc.extension

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Base class of an extension's service. Subclass it, export it with the
 * [ExtensionApi.ACTION_BIND] intent filter and `android:permission` set to
 * [ExtensionApi.PERMISSION_BIND], and point [ExtensionApi.META_DATA_MANIFEST] at the manifest.
 *
 * It verifies that every call comes from LoEUC (see [HostVerifier]) and delivers the
 * callbacks on the main thread, in the order the host sent them. A call from anyone else is
 * dropped and logged.
 */
abstract class LoEUCExtensionService : Service() {

    /**
     * Accept a LoEUC build that is not signed with a release certificate, such as a debug
     * build of the app. Honoured only while this extension is itself a debuggable build.
     */
    protected open val acceptDebugHost: Boolean = false

    /** The session's host, or `null` between sessions. */
    protected var host: Host? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val verifier by lazy { HostVerifier.create(this, acceptDebugHost) }

    /** A session with [device] begins; [host] is valid until [onSessionEnd]. */
    protected open fun onSessionStart(host: Host, device: DeviceDescriptor) = Unit

    protected open fun onTelemetry(snapshot: TelemetrySnapshot) = Unit

    protected open fun onSettingChanged(key: String, value: Double) = Unit

    protected open fun onCommandResult(result: CommandResult) = Unit

    /** [reason] is one of [DetachReason]. */
    protected open fun onSessionEnd(reason: Int) = Unit

    private val binder = object : ILoEUCExtension.Stub() {
        override fun onAttach(host: ILoEUCHost?, device: DeviceDescriptor?) = fromHost {
            if (host == null || device == null) return@fromHost
            val session = Host(host)
            this@LoEUCExtensionService.host = session
            onSessionStart(session, device)
        }

        override fun onTelemetry(snapshot: TelemetrySnapshot?) = fromHost {
            snapshot?.let { this@LoEUCExtensionService.onTelemetry(it) }
        }

        override fun onSettingChanged(key: String?, value: Double) = fromHost {
            key?.let { this@LoEUCExtensionService.onSettingChanged(it, value) }
        }

        override fun onCommandResult(result: CommandResult?) = fromHost {
            result?.let { this@LoEUCExtensionService.onCommandResult(it) }
        }

        override fun onDetach(reason: Int) = fromHost {
            host = null
            onSessionEnd(reason)
        }
    }

    private inline fun fromHost(crossinline block: () -> Unit) {
        val uid = Binder.getCallingUid()
        if (!verifier.isHost(uid)) {
            Log.w(LogTag, "dropped a call from uid $uid: not a trusted LoEUC build")
            return
        }
        mainHandler.post { block() }
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ExtensionApi.ACTION_BIND) binder else null

    override fun onUnbind(intent: Intent?): Boolean {
        mainHandler.post {
            if (host != null) {
                host = null
                onSessionEnd(DetachReason.HOST_STOPPING)
            }
        }
        return false
    }

    /**
     * The host as the extension sees it. Calls never throw: a host that went away makes them
     * return `false`, and the session ends with [onSessionEnd] shortly after.
     */
    class Host internal constructor(private val remote: ILoEUCHost) {
        private val requestIds = AtomicInteger()

        fun capabilities(): HostCapabilities? = call { remote.capabilities() }

        /** Shows [value] on the tile declared as [key]. [Double.NaN] clears it. */
        fun publishTile(key: String, value: Double): Boolean =
            call { remote.publishTile(key, TileValue(value)) } != null

        fun raiseAlert(key: String, active: Boolean, intensity: Double = 1.0): Boolean =
            call { remote.raiseAlert(key, AlertRequest(active, intensity)) } != null

        /**
         * Asks for the declared command [key] with [value] (`null` for a command without a
         * parameter). Returns the request id the [CommandResult] will carry, or `null` when
         * the host is gone.
         */
        fun sendCommand(key: String, value: Double? = null): Int? {
            val id = requestIds.incrementAndGet()
            return call { remote.sendCommand(key, CommandValue(id, value)) }?.let { id }
        }

        private inline fun <T> call(block: () -> T): T? = try {
            block()
        } catch (error: RemoteException) {
            Log.w(LogTag, "host call failed", error)
            null
        }
    }

    private companion object {
        const val LogTag = "LoEUCExtension"
    }
}
