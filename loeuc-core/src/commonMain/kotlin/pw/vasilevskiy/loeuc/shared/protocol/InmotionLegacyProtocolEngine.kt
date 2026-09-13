package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi

/** Which request the session is currently sending. */
enum class InmotionLegacySessionPhase {
    PIN,
    SLOW_INFO,
    /** A queued setting command or quick action. */
    COMMAND,
    FAST_INFO,
}

/**
 * Request pacing for a legacy InMotion session, with the clock passed in so Android, iOS and a
 * test drive identical logic.
 *
 * The pacing is the reference client's: a request goes out every 250 ms, or at the next
 * opportunity once any reply has arrived, which in practice ties the rate to the wheel's own
 * response time. The request chosen at each slot is, in priority order:
 *
 * 1. the PIN, at most [MAX_PIN_ATTEMPTS] times, until the wheel answers with the PIN id (the
 *    reply content is not checked, as the reference does not check it either);
 * 2. slow info, until a slow info reply has yielded an identity, and again after every command
 *    so the settings the wheel reports replace the value that was sent;
 * 3. a command queued with [enqueue], oldest first;
 * 4. fast info, which doubles as the keep-alive.
 *
 * The reference client keeps a single pending command and a newer one replaces it; this session
 * queues them, so a headlight toggle and a volume change made in the same second both land. Each
 * one is still followed by its own slow info re-read before the next goes out.
 *
 * A caller writes whatever [nextRequest] returns, calls it again after each [onMessage] and on a
 * short timer (25 ms is what the reference uses), and reports a failed write with
 * [onWriteFailed].
 */
class InmotionLegacySession @Throws(IllegalArgumentException::class) constructor(pin: String) {
    constructor() : this(InmotionLegacyRequests.DEFAULT_PIN)

    private val pinRequest: ByteArray = InmotionLegacyRequests.pin(pin)
    private val slowInfoRequest: ByteArray = InmotionLegacyRequests.slowInfo()
    private val fastInfoRequest: ByteArray = InmotionLegacyRequests.fastInfo()

    var pinAttempts: Int = 0
        private set

    /** True once the wheel answered the PIN message. */
    var pinAcknowledged: Boolean = false
        private set

    var identity: InmotionLegacyIdentity? = null
        private set

    /** Time of the last fast info reply, meaningful only when [hasFastReply] is true. */
    var lastFastReplyAtMillis: Long = 0
        private set

    var hasFastReply: Boolean = false
        private set

    private val commands = ArrayDeque<ByteArray>()

    /** Set when a command went out or the wheel acknowledged one; cleared by a slow info reply. */
    private var slowInfoStale = false

    private var lastCommand: ByteArray? = null

    private var hasSentRequest = false
    private var lastRequestAtMillis = 0L
    private var replyArrived = false
    private var lastPhase: InmotionLegacySessionPhase? = null

    /** The phase the next request will belong to. */
    val phase: InmotionLegacySessionPhase
        get() = when {
            !pinAcknowledged && pinAttempts < MAX_PIN_ATTEMPTS -> InmotionLegacySessionPhase.PIN
            identity == null || slowInfoStale -> InmotionLegacySessionPhase.SLOW_INFO
            commands.isNotEmpty() -> InmotionLegacySessionPhase.COMMAND
            else -> InmotionLegacySessionPhase.FAST_INFO
        }

    /** Commands waiting to be sent. */
    val pendingCommandCount: Int
        get() = commands.size

    /**
     * Queues [command], one complete wire frame from [InmotionLegacyCommands], to go out at the next
     * free slot after the session has an identity. Returns false, queuing nothing, when the bytes are
     * not exactly one valid frame, when the frame is one this library never sends
     * ([InmotionLegacyCommands.isForbidden]), or when [MAX_PENDING_COMMANDS] are already waiting.
     */
    fun enqueue(command: ByteArray): Boolean {
        val message = InmotionLegacyFraming.decodeFrame(command) ?: return false
        if (InmotionLegacyCommands.isForbidden(message)) return false
        if (commands.size >= MAX_PENDING_COMMANDS) return false
        commands.addLast(command.copyOf())
        return true
    }

    /**
     * Asks for slow info at the next free slot, ahead of the keep-alive: the settings readback for
     * a command that went out by another path than [enqueue]. Nothing happens before the session
     * has an identity, since slow info is being asked for anyway until then.
     */
    fun requestSlowInfo() {
        if (identity != null) slowInfoStale = true
    }

    /** True while fast info replies keep arriving within [TELEMETRY_TIMEOUT_MILLIS]. */
    fun isTelemetryFlowing(nowMillis: Long): Boolean =
        hasFastReply && nowMillis - lastFastReplyAtMillis <= TELEMETRY_TIMEOUT_MILLIS

    /** Records a message the wheel sent. Any valid message makes the next request due at once. */
    fun onMessage(message: InmotionLegacyMessage, nowMillis: Long) {
        replyArrived = true
        when (message.id) {
            InmotionLegacyIds.PIN_CODE -> pinAcknowledged = true
            InmotionLegacyIds.SLOW_INFO -> InmotionLegacyDecoder.decodeIdentity(message)?.let {
                identity = it
                slowInfoStale = false
            }
            InmotionLegacyIds.FAST_INFO -> if (message.isExtended) {
                hasFastReply = true
                lastFastReplyAtMillis = nowMillis
            }
            // The wheel answers a setting command with a short message of the same id. A command
            // sent by another path - another client, or a caller that writes on its own - still
            // gets its readback that way.
            else -> if (!message.isExtended && message.id in InmotionLegacyCommands.WRITE_IDS && identity != null) {
                slowInfoStale = true
            }
        }
    }

    /** The request to write now, or null when nothing is due yet. Returns a fresh array. */
    fun nextRequest(nowMillis: Long): ByteArray? {
        val due = replyArrived || !hasSentRequest || nowMillis - lastRequestAtMillis >= POLL_INTERVAL_MILLIS
        if (!due) return null
        replyArrived = false
        hasSentRequest = true
        lastRequestAtMillis = nowMillis
        val current = phase
        lastPhase = current
        return when (current) {
            InmotionLegacySessionPhase.PIN -> {
                pinAttempts += 1
                pinRequest.copyOf()
            }
            InmotionLegacySessionPhase.SLOW_INFO -> slowInfoRequest.copyOf()
            InmotionLegacySessionPhase.COMMAND -> {
                val command = commands.removeFirst()
                lastCommand = command
                slowInfoStale = true
                command.copyOf()
            }
            InmotionLegacySessionPhase.FAST_INFO -> fastInfoRequest.copyOf()
        }
    }

    /**
     * The last request could not be written. It does not count as a PIN attempt, a command goes
     * back to the front of the queue, and the next try is due [WRITE_RETRY_DELAY_MILLIS] later, as
     * in the reference client.
     */
    fun onWriteFailed(nowMillis: Long) {
        if (lastPhase == InmotionLegacySessionPhase.PIN && pinAttempts > 0) pinAttempts -= 1
        if (lastPhase == InmotionLegacySessionPhase.COMMAND) {
            lastCommand?.let { commands.addFirst(it) }
            slowInfoStale = false
        }
        lastCommand = null
        lastPhase = null
        replyArrived = false
        hasSentRequest = true
        lastRequestAtMillis = nowMillis - POLL_INTERVAL_MILLIS + WRITE_RETRY_DELAY_MILLIS
    }

    /** Back to the start of a session, for a new connection. Queued commands are dropped. */
    fun reset() {
        commands.clear()
        lastCommand = null
        slowInfoStale = false
        pinAttempts = 0
        pinAcknowledged = false
        identity = null
        hasFastReply = false
        lastFastReplyAtMillis = 0
        hasSentRequest = false
        lastRequestAtMillis = 0
        replyArrived = false
        lastPhase = null
    }

    companion object {
        const val MAX_PIN_ATTEMPTS: Int = 6
        const val MAX_PENDING_COMMANDS: Int = 8
        const val POLL_INTERVAL_MILLIS: Long = 250
        const val WRITE_RETRY_DELAY_MILLIS: Long = 125
        const val TELEMETRY_TIMEOUT_MILLIS: Long = 1000
    }
}

/** What one notify chunk produced. */
data class InmotionLegacyUpdate(
    /** Every message completed by the chunk, in arrival order. */
    val messages: List<InmotionLegacyMessage>,
    /** The last telemetry in the chunk, if any. */
    val telemetry: InmotionLegacyTelemetry?,
    /** Identity from a slow info reply in the chunk, if any. */
    val identity: InmotionLegacyIdentity?,
    /** Settings from a slow info reply in the chunk, if any. */
    val settings: InmotionLegacySettings?,
    val alerts: List<InmotionLegacyAlert>,
    /** True when the chunk carried the wheel's answer to the PIN. */
    val pinReplied: Boolean,
)

/**
 * Client for legacy InMotion wheels (V5, V8, V10 families, Solowheel Glide 3 and the older
 * R-series, V3, L6, Lively): reassembly, session pacing and decoding in one object.
 *
 * Subscribe to [INMOTION_LEGACY_NOTIFY_CHAR_UUID], feed every notify to [consume], and write
 * what [nextRequest] returns to [INMOTION_LEGACY_WRITE_CHAR_UUID] in chunks of at most 20 bytes.
 * On its own the engine only reads. A setting change or quick action is built with
 * [InmotionLegacyCommands] (or [buildSetting]) and handed to [enqueue]; it then goes out through
 * [nextRequest] between keep-alives, followed by a slow info re-read that brings back the wheel's
 * own value in [settings].
 */
class InmotionLegacyProtocolEngine @Throws(IllegalArgumentException::class) constructor(pin: String) {
    constructor() : this(InmotionLegacyRequests.DEFAULT_PIN)

    val session: InmotionLegacySession = InmotionLegacySession(pin)
    private val reassembler = InmotionLegacyReassembler()

    /** Use the reference client's alternative battery curves. */
    var betterBatteryPercents: Boolean = false

    /** The settings from the latest slow info reply, or null before one arrives. */
    var settings: InmotionLegacySettings? = null
        private set

    val identity: InmotionLegacyIdentity?
        get() = session.identity

    /** The settings [enqueue] can change on this wheel right now; see [InmotionLegacyCommands.writableSettings]. */
    fun writableSettings(): List<InmotionLegacySetting> =
        InmotionLegacyCommands.writableSettings(session.identity, settings)

    /**
     * The command setting [setting] to [value] on the connected wheel, or null when the setting is
     * not in [writableSettings] or the value is out of range.
     */
    @ExperimentalWriteApi
    fun buildSetting(setting: InmotionLegacySetting, value: Int): ByteArray? {
        val model = session.identity?.model ?: return null
        if (setting !in writableSettings()) return null
        return InmotionLegacyCommands.build(setting, value, model)
    }

    /** Queues a command for the session; see [InmotionLegacySession.enqueue]. */
    fun enqueue(command: ByteArray): Boolean = session.enqueue(command)

    /** See [InmotionLegacySession.requestSlowInfo]. */
    fun requestSlowInfo() = session.requestSlowInfo()

    fun consume(chunk: ByteArray, nowMillis: Long): InmotionLegacyUpdate {
        val messages = reassembler.push(chunk)
        var telemetry: InmotionLegacyTelemetry? = null
        var identity: InmotionLegacyIdentity? = null
        var settings: InmotionLegacySettings? = null
        var pinReplied = false
        val alerts = ArrayList<InmotionLegacyAlert>()
        for (message in messages) {
            session.onMessage(message, nowMillis)
            when (message.id) {
                InmotionLegacyIds.FAST_INFO -> {
                    val model = session.identity?.decodingModel ?: InmotionLegacyModel.FALLBACK
                    InmotionLegacyDecoder.decodeTelemetry(message, model, betterBatteryPercents)?.let { telemetry = it }
                }
                InmotionLegacyIds.SLOW_INFO -> {
                    InmotionLegacyDecoder.decodeIdentity(message)?.let { identity = it }
                    InmotionLegacyDecoder.decodeSettings(message)?.let {
                        settings = it
                        this.settings = it
                    }
                }
                InmotionLegacyIds.ALERT -> InmotionLegacyDecoder.decodeAlert(message)?.let(alerts::add)
                InmotionLegacyIds.PIN_CODE -> pinReplied = true
            }
        }
        return InmotionLegacyUpdate(messages, telemetry, identity, settings, alerts, pinReplied)
    }

    fun nextRequest(nowMillis: Long): ByteArray? = session.nextRequest(nowMillis)

    fun onWriteFailed(nowMillis: Long) = session.onWriteFailed(nowMillis)

    fun reset() {
        reassembler.reset()
        session.reset()
        settings = null
    }
}
