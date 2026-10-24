package pw.vasilevskiy.loeuc.shared.protocol

import pw.vasilevskiy.loeuc.shared.api.ExperimentalWriteApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The flash session against a scripted wheel: a virtual clock, a timer queue, and a transport
 * that records every write. Nothing here waits in real time.
 */
@OptIn(ExperimentalWriteApi::class)
class LeaperKimFirmwareFlashSessionTest {

    /** A port whose clock only moves when the test moves it. */
    private class FakePort(var writeType: LeaperKimFlashWriteType? = LeaperKimFlashWriteType.WithoutResponse) :
        LeaperKimFirmwareFlashPort {
        var now = 10_000L
        val states = mutableListOf<LeaperKimFlashState>()
        val writes = mutableListOf<Pair<LeaperKimFlashWriteType, ByteArray>>()
        val outcomes = ArrayDeque<LeaperKimFlashWriteOutcome>()
        private val timers = mutableListOf<Triple<Long, Long, Long>>() // due, order, token
        private var order = 0L
        lateinit var session: LeaperKimFirmwareFlashSession

        override fun nowMillis() = now
        override fun writeType() = writeType
        override fun write(bytes: ByteArray, type: LeaperKimFlashWriteType): LeaperKimFlashWriteOutcome {
            writes += type to bytes.copyOf()
            return outcomes.removeFirstOrNull() ?: LeaperKimFlashWriteOutcome.Accepted
        }
        override fun schedule(delayMillis: Long, token: Long) {
            timers += Triple(now + delayMillis, order++, token)
        }
        override fun emit(state: LeaperKimFlashState) {
            states += state
        }

        /** Runs every timer due up to [millis] from now, in due order, moving the clock with them. */
        fun advance(millis: Long) {
            val until = now + millis
            while (true) {
                val next = timers.filter { it.first <= until }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                timers.remove(next)
                now = maxOf(now, next.first)
                session.onTimer(next.third)
            }
            now = until
        }

        fun pendingTimers() = timers.size
    }

    private fun session(port: FakePort): LeaperKimFirmwareFlashSession =
        LeaperKimFirmwareFlashSession(port).also { port.session = it }

    private fun plan(image: ByteArray = syntheticContainer()): LeaperKimFirmwareUploadPlan =
        LeaperKimFirmwareService.buildOriginalUploadPlan(
            image,
            LeaperKimFirmwareService.analyze(image, LeaperKimFirmwareManifest.Empty),
        )!!

    /** Connect, telemetry with a tilt, start, silence: the wheel is in its bootloader. */
    private fun flashToFinalizing(port: FakePort, flash: LeaperKimFirmwareFlashSession, ackWithResponse: Boolean) {
        flash.onNotify()
        flash.onTelemetry(-4_200.0, "0090")
        flash.start(plan(), connected = true)
        var guard = 0
        while (port.states.last() !is LeaperKimFlashState.Finalizing) {
            if (ackWithResponse && port.writes.isNotEmpty() && pendingAck(port)) {
                acked++
                flash.onWriteResult(success = true, attStatus = 0)
            } else {
                port.advance(10)
            }
            assertTrue(++guard < 100_000, "flash never finished: ${port.states.last()}")
        }
    }

    private var acked = 0
    private fun pendingAck(port: FakePort): Boolean =
        port.writes.count { it.first == LeaperKimFlashWriteType.WithResponse } > acked

    // ---- the path -----------------------------------------------------------------------

    @Test
    fun without_response_the_wire_carries_the_command_then_the_file_and_matches_the_pinned_transcript() {
        val port = FakePort(LeaperKimFlashWriteType.WithoutResponse)
        val flash = session(port)

        flashToFinalizing(port, flash, ackWithResponse = false)

        assertEquals("AT+RINTOPRO", port.writes.first().second.decodeToString())
        assertContentEquals(syntheticContainer(), port.writes.drop(1).map { it.second }.reduce { a, b -> a + b })
        assertTrue(port.writes.all { it.second.size <= 20 })
        assertEquals(55, port.writes.size)
        assertEquals(PinnedWithoutResponse, transcriptSha(port.writes))
        assertEquals((0..54).toList(), port.states.filterIsInstance<LeaperKimFlashState.Uploading>().map { it.sentChunks })
    }

    @Test
    fun with_response_each_chunk_waits_for_its_acknowledgement_and_matches_the_pinned_transcript() {
        val port = FakePort(LeaperKimFlashWriteType.WithResponse)
        val flash = session(port)
        flash.onNotify()
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)

        assertEquals(1, port.writes.size, "the command is one chunk and nothing follows it unacknowledged")
        port.advance(1_000)
        assertEquals(1, port.writes.size, "no acknowledgement, no second write")

        acked = 0
        flashToFinalizingFromHere(port, flash)

        assertEquals(55, port.writes.size)
        assertEquals(PinnedWithResponse, transcriptSha(port.writes))
    }

    private fun flashToFinalizingFromHere(port: FakePort, flash: LeaperKimFirmwareFlashSession) {
        var guard = 0
        while (port.states.last() !is LeaperKimFlashState.Finalizing) {
            if (pendingAck(port)) {
                acked++
                flash.onWriteResult(success = true, attStatus = 0)
            } else {
                port.advance(10)
            }
            assertTrue(++guard < 100_000)
        }
    }

    @Test
    fun a_write_rejected_as_not_permitted_is_resent_once_without_response() {
        val port = FakePort(LeaperKimFlashWriteType.WithResponse)
        val flash = session(port)
        flash.onNotify()
        flash.onTelemetry(4_500.0, null)
        flash.start(plan(), connected = true)
        flash.onWriteResult(success = false, attStatus = 3)
        acked = 1 // the rejected write was answered; its resend goes without response

        flashToFinalizingFromHere(port, flash)

        assertEquals(LeaperKimFlashWriteType.WithResponse, port.writes[0].first)
        assertEquals(LeaperKimFlashWriteType.WithoutResponse, port.writes[1].first)
        assertContentEquals(port.writes[0].second, port.writes[1].second)
        assertEquals(LeaperKimFlashWriteType.WithResponse, port.writes[2].first, "the file goes out with response again")
        assertEquals(56, port.writes.size)
        assertEquals(PinnedWithResponseRejectedOnce, transcriptSha(port.writes))
    }

    @Test
    fun the_reset_window_reports_whether_telemetry_came_back() {
        for (restored in listOf(true, false)) {
            val port = FakePort()
            val flash = session(port)
            flashToFinalizing(port, flash, ackWithResponse = false)
            port.advance(5_000)
            if (restored) flash.onNotify()
            port.advance(10_000)

            assertEquals(LeaperKimFlashState.Done(telemetryRestored = restored), port.states.last())
        }
    }

    @Test
    fun a_disconnect_in_the_reset_window_is_not_a_failure() {
        val port = FakePort()
        val flash = session(port)
        flashToFinalizing(port, flash, ackWithResponse = false)

        flash.onConnectionLost()
        port.advance(15_000)

        assertEquals(LeaperKimFlashState.Done(telemetryRestored = false), port.states.last())
        assertTrue(port.states.none { it is LeaperKimFlashState.Failed })
    }

    // ---- the bootloader wait ------------------------------------------------------------

    @Test
    fun a_wheel_that_keeps_talking_never_gets_the_file_and_the_wait_times_out() {
        val port = FakePort()
        val flash = session(port)
        flash.onNotify()
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)

        repeat(40) {
            port.advance(400)
            flash.onNotify()
        }

        assertEquals(1, port.writes.size, "nothing but AT+RINTOPRO went out")
        assertEquals(LeaperKimFlashState.Failed(LeaperKimFlashFailure.BootloaderNotEntered, 0), port.states.last())
        assertTrue(port.states.any { it is LeaperKimFlashState.WaitingBootloader })
        assertTrue(port.states.none { it is LeaperKimFlashState.Uploading })
    }

    @Test
    fun the_file_waits_for_the_full_silence_window() {
        val port = FakePort()
        val flash = session(port)
        flash.onNotify()
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)

        port.advance(1_400)
        assertEquals(1, port.writes.size)
        port.advance(600)
        assertTrue(port.writes.size > 1, "silence reached, the file starts")
    }

    // ---- refusals before the first byte -------------------------------------------------

    @Test
    fun refusals_happen_before_anything_is_written() {
        val cases = listOf(
            Triple(false, -4_200.0, plan()) to LeaperKimFlashFailure.NotConnected,
            Triple(true, -4_200.0, null) to LeaperKimFlashFailure.FileRejected,
            Triple(true, 300.0, plan()) to LeaperKimFlashFailure.WheelNotTilted,
            Triple(true, null, plan()) to LeaperKimFlashFailure.WheelNotTilted,
        )
        for ((input, expected) in cases) {
            val port = FakePort()
            val flash = session(port)
            flash.onTelemetry(input.second, null)
            flash.start(input.third, connected = input.first)

            assertEquals(LeaperKimFlashState.Failed(expected, 0), port.states.last(), "case $input")
            assertTrue(port.writes.isEmpty(), "case $input wrote something")
            assertFalse(flash.isWriting)
        }
    }

    @Test
    fun skipping_the_tilt_check_lets_a_level_wheel_start_but_keeps_the_other_refusals() {
        for (pose in listOf(300.0, null)) {
            // Hold the entry command pending its ACK: this checks the tilt gate, not file pacing.
            val port = FakePort(writeType = LeaperKimFlashWriteType.WithResponse)
            val flash = session(port)
            flash.skipTiltCheck = true
            flash.onTelemetry(pose, null)
            flash.start(plan(), connected = true)
            assertTrue(port.states.any { it is LeaperKimFlashState.EnteringBootloader }, "pose $pose")
            assertTrue(port.states.none { it is LeaperKimFlashState.Failed }, "pose $pose")
            port.advance(1_400)
            assertEquals(1, port.writes.size, "pose $pose: AT+RINTOPRO went out")
        }
        for ((connected, plan) in listOf(false to plan(), true to null)) {
            val port = FakePort()
            val flash = session(port)
            flash.skipTiltCheck = true
            flash.onTelemetry(300.0, null)
            flash.start(plan, connected)
            assertTrue(port.states.last() is LeaperKimFlashState.Failed)
            assertTrue(port.writes.isEmpty())
        }
    }

    @Test
    fun no_write_characteristic_stops_silently_for_the_port_to_explain() {
        val port = FakePort(writeType = null)
        val flash = session(port)
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)

        assertTrue(port.writes.isEmpty())
        assertEquals(LeaperKimFlashState.EnteringBootloader, port.states.last())
    }

    // ---- failures mid-flash -------------------------------------------------------------

    @Test
    fun a_refused_chunk_fails_the_flash_and_nothing_follows() {
        val port = FakePort()
        val flash = session(port)
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)
        port.advance(2_000)
        val before = port.writes.size
        port.outcomes.addLast(LeaperKimFlashWriteOutcome.Refused)
        port.advance(40)
        port.advance(10_000)

        assertEquals(LeaperKimFlashState.Failed(LeaperKimFlashFailure.ChunkWriteRefused, 0), port.states.last())
        assertEquals(before + 1, port.writes.size)
    }

    @Test
    fun a_failed_acknowledgement_reports_its_status() {
        val port = FakePort(LeaperKimFlashWriteType.WithResponse)
        val flash = session(port)
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)
        flash.onWriteResult(success = false, attStatus = 14)

        assertEquals(LeaperKimFlashState.Failed(LeaperKimFlashFailure.ChunkWriteFailed, 14), port.states.last())
        assertEquals("Не удалось передать часть прошивки (код Bluetooth 14). Выключите и включите колесо: заработало — прошейте ещё раз; молчит — поможет «Восстановление прошивки» в LoEUC на Android.", LeaperKimFirmwareFlashSession.failureText(LeaperKimFlashFailure.ChunkWriteFailed, 14, russian = true))
    }

    @Test
    fun a_connection_lost_while_bytes_are_due_fails_at_once_in_every_writing_stage() {
        // Entering the bootloader, waiting for it, and mid-file.
        for (stepsBeforeLoss in listOf(0L, 600L, 2_500L)) {
            val port = FakePort()
            val flash = session(port)
            flash.onNotify()
            flash.onTelemetry(-4_200.0, null)
            flash.start(plan(), connected = true)
            port.advance(stepsBeforeLoss)
            val written = port.writes.size

            flash.onConnectionLost()
            port.advance(60_000)

            assertEquals(LeaperKimFlashState.Failed(LeaperKimFlashFailure.ConnectionLost, 0), port.states.last(), "after $stepsBeforeLoss ms")
            assertEquals(written, port.writes.size, "nothing written after the loss")
            assertEquals(0, port.pendingTimers())
        }
    }

    @Test
    fun an_acknowledgement_for_a_write_without_response_is_ignored() {
        val port = FakePort()
        val flash = session(port)
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)
        port.advance(2_000)
        val before = port.writes.size

        flash.onWriteResult(success = true, attStatus = 0)

        assertEquals(before, port.writes.size, "without response the session paces itself")
    }

    // ---- flow control -------------------------------------------------------------------

    @Test
    fun a_deferred_chunk_holds_everything_until_the_link_is_ready() {
        val port = FakePort()
        val flash = session(port)
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)
        port.advance(2_000)
        val before = port.writes.size
        port.outcomes.addLast(LeaperKimFlashWriteOutcome.Deferred)
        port.advance(40) // the deferred write
        port.advance(5_000)

        assertEquals(before + 1, port.writes.size, "no write while the link is not ready")
        val progress = port.states.filterIsInstance<LeaperKimFlashState.Uploading>().last().sentChunks

        flash.onWriteReady()
        assertEquals(progress + 1, port.states.filterIsInstance<LeaperKimFlashState.Uploading>().last().sentChunks)
        port.advance(40)
        assertEquals(before + 2, port.writes.size, "the pace resumes from the ready")

        while (port.states.last() !is LeaperKimFlashState.Finalizing) port.advance(40)
        assertContentEquals(syntheticContainer(), port.writes.drop(1).map { it.second }.reduce { a, b -> a + b })
    }

    @Test
    fun cancel_stops_writes_timers_and_states() {
        val port = FakePort()
        val flash = session(port)
        flash.onTelemetry(-4_200.0, null)
        flash.start(plan(), connected = true)
        port.advance(2_000)
        val writes = port.writes.size
        val states = port.states.size

        flash.cancel()
        port.advance(60_000)
        flash.onWriteReady()
        flash.onWriteResult(success = true, attStatus = 0)

        assertEquals(writes, port.writes.size)
        assertEquals(states, port.states.size)
        assertFalse(flash.isWriting)
    }

    @Test
    fun ready_refreshes_the_tilt_only_before_the_flash() {
        val port = FakePort()
        val flash = session(port)

        flash.onTelemetry(null, "0090")
        assertTrue(port.states.isEmpty(), "a frame without a tilt says nothing")
        flash.onTelemetry(-4_100.0, null)
        assertEquals(LeaperKimFlashState.Ready(-4_100.0, "0090"), port.states.last())

        flash.start(plan(), connected = true)
        val count = port.states.size
        flash.onTelemetry(-4_300.0, null)
        assertEquals(count, port.states.size)
    }

    @Test
    fun the_telemetry_tap_reads_tilt_and_hardware_code_off_raw_notifications() {
        val tap = LeaperKimFlashTelemetryTap()
        val frame = tiltedFrame(-4_200)
        var sample: LeaperKimFlashTelemetrySample? = null
        frame.toList().chunked(20).forEach { chunk -> tap.consume(chunk.toByteArray())?.let { sample = it } }

        assertEquals(-4_200.0, sample?.carPose)
    }

    // ---- fixtures -----------------------------------------------------------------------

    private fun transcriptSha(writes: List<Pair<LeaperKimFlashWriteType, ByteArray>>): String {
        val hex = "0123456789abcdef"
        val text = buildString {
            writes.forEach { (type, bytes) ->
                append(if (type == LeaperKimFlashWriteType.WithResponse) 'w' else 'n')
                append(' ')
                bytes.forEach { byte ->
                    append(hex[(byte.toInt() ushr 4) and 0xF])
                    append(hex[byte.toInt() and 0xF])
                }
                append('\n')
            }
        }
        return LeaperKimFirmwareDigests.sha256Hex(text.encodeToByteArray())
    }

    private fun tiltedFrame(pose: Int): ByteArray {
        val frame = ByteArray(84)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame[3] = (84 - 4).toByte()
        for (index in 40..45) frame[index] = 0x80.toByte()
        frame[46] = 2
        frame[32] = (pose ushr 8).toByte()
        frame[33] = pose.toByte()
        val crc = LeaperKimFirmwareDigests.crc32(frame, 0, 80)
        frame[80] = (crc ushr 24).toByte()
        frame[81] = (crc ushr 16).toByte()
        frame[82] = (crc ushr 8).toByte()
        frame[83] = crc.toByte()
        return frame
    }

    private companion object {
        // The synthetic container's transcripts, computed from the official app's sequence
        // rather than from this code. The application that embeds this library pins the same
        // digests against its Android and iOS flashers.
        const val PinnedWithoutResponse = "2814050de27082ef3ea874c7460b417bf252b7227e1353448fb6dd695a897904"
        const val PinnedWithResponse = "138efa2bab3f73441ef18311de9d7cdd94f434d7a8e47eafe0eb75a1acc42fb3"
        const val PinnedWithResponseRejectedOnce = "dcdaaabfe81533905e60b6e439a1c969a4991e59056c016c34a8d6b5ee1f8228"

        fun syntheticContainer(): ByteArray {
            val firmware = ByteArray(0x430) { index -> (index and 0xff).toByte() }
            "LdCm".encodeToByteArray().copyInto(firmware)
            firmware.putUInt32Le(0x04, firmware.size.toLong())
            firmware.putUInt32Le(0x0c, 0x8D4B8206)
            val version = "SysEdition:0090xx"
            version.encodeToByteArray().copyInto(firmware, destinationOffset = 0x10)
            firmware[0x10 + version.length] = 0
            firmware.putUInt32Le(0x08, LeaperKimFirmwareDigests.crc32(firmware, 0x400))
            return firmware
        }
    }
}
