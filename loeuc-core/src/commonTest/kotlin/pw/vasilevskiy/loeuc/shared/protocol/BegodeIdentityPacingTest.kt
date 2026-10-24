package pw.vasilevskiy.loeuc.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How often [BegodeIdentityRequests] may interrupt a wheel that stops streaming to listen.
 *
 * The notifies below are copied from three captures of `GotWay_012960` (stock `GW2026304`), taken
 * on 2026-09-15 while the phone kept reconnecting to it: `20A71631EF9E/20260915_171748`, `_172124`
 * and `_172235`. Times are milliseconds from the first notify of each capture. In all three the
 * wheel finished the frame set it was sending and went silent within 150-300 ms of the first
 * query, stayed silent while `N` and `V` kept arriving every 100 ms, and never answered `N`. In the
 * one capture that lasted past the queries, its version line came 0.98 s after the last byte and
 * frames 0.25 s after that - 4.9 s of silence in all, against a 5 s connection-lost watchdog.
 */
class BegodeIdentityPacingTest {

    @Test
    fun theRecordedStallCostsOneQueryInsteadOfEighteen() {
        // `_172235`: five notifies, then only our writes - eighteen of them in 1.93 s.
        val notifies = listOf(
            0L to "5A5A5A5A55AA00500000059100000016001302C7",
            42L to "7F0101035A5A5A5A55AA0DE40DE70DE70DE60DE6",
            73L to "0DE60DE80DE503005A5A5A5A55AA0021AD950900",
            88L to "19C3005100010000001204185A5A5A5A55AA0001",
            150L to "000300140000000000000000000007185A5A5A5A",
        )
        val requests = BegodeIdentityRequests()

        val sent = drive(requests, notifies, untilMillis = 2_100L)

        assertEquals(listOf("N"), sent.map { it.second })
    }

    @Test
    fun aVersionLineHeardBeforeAnyQueryIsNeverAskedFor() {
        // `_171748`: the version line arrives before the first query, a frame set follows, then the
        // wheel is silent until 5418 ms - where it printed the line again after the storm.
        val notifies = listOf(
            0L to "475732303236333034",
            347L to "55AA1644000000370000000AF3420C8800090018",
            389L to "5A5A5A5A55AA00500000059100000015001302C8",
            421L to "7F0101015A5A5A5A55AA0DE80DE80DE90DE90DE8",
            450L to "0DE80DE80DE902035A5A5A5A55AA0021AD950900",
            479L to "1AE5005100010000001204185A5A5A5A55AA0004",
            527L to "000300140000000000000000000007185A5A5A5A",
            5418L to "475732303236333034",
            5669L to "55AA1644000000370000FFD8F33D0C8800090018",
            5672L to "5A5A5A5A55AA00500000059100000015001302C7",
        )
        val requests = BegodeIdentityRequests()

        val sent = drive(requests, notifies, untilMillis = 6_000L)

        assertEquals("2026304", requests.firmware?.version)
        assertTrue(sent.none { it.second == "V" }, "asked for a version it had already heard: $sent")
        assertTrue(sent.none { (at, _) -> at in 528L until 5_418L }, "asked into the silence: $sent")
    }

    @Test
    fun anAnswerTheEngineAlreadyReadIsNotAskedFor() {
        val requests = BegodeIdentityRequests()
        requests.seed(modelName = null, firmware = BegodeIdentityReplies.firmware("GW2026304".encodeToByteArray()))

        requests.onNotify(hex("55AA"), 0L)
        assertEquals("N", requests.nextRequest(0L)?.decodeToString())
        requests.onNotify(hex("55AA"), BegodeIdentityRequests.RETRY_INTERVAL_MILLIS)
        assertEquals("N", requests.nextRequest(BegodeIdentityRequests.RETRY_INTERVAL_MILLIS)?.decodeToString())
        assertTrue(requests.isFinished)
    }

    @Test
    fun nothingIsAskedBeforeTheWheelHasSaidAnything() {
        val requests = BegodeIdentityRequests()

        assertNull(requests.nextRequest(0L))
        assertNull(requests.nextRequest(10_000L))
    }

    /**
     * The whole connection against a model of that wheel: it streams a notify every 50 ms, stops
     * 180 ms after a byte arrives (the rest of the set), resumes 1.25 s after the last byte, and
     * then prints its version line if that byte was `V`. It never answers `N`.
     */
    @Test
    fun aWheelThatStopsToListenKeepsStreamingWellInsideTheWatchdog() {
        val requests = BegodeIdentityRequests()
        var lastByteAt: Long? = null
        var lastByte: String? = null
        var paused = false
        var lastNotifyAt = 0L
        var longestSilence = 0L
        val sent = mutableListOf<String>()

        for (now in 0L..30_000L step 10L) {
            val byteAt = lastByteAt
            val listening = byteAt != null && now - byteAt in 180L until 1_250L
            if (!listening && now % 50L == 0L) {
                if (paused && lastByte == "V") {
                    requests.onNotify("GW2026304".encodeToByteArray(), now)
                }
                paused = false
                longestSilence = maxOf(longestSilence, now - lastNotifyAt)
                lastNotifyAt = now
                requests.onNotify(hex("55AA0000"), now)
            }
            if (listening) paused = true
            requests.nextRequest(now)?.decodeToString()?.let { query ->
                sent += query
                lastByte = query
                lastByteAt = now
            }
        }

        assertEquals("2026304", requests.firmware?.version)
        assertTrue(requests.isFinished)
        assertEquals(listOf("N", "V", "N"), sent)
        assertTrue(longestSilence < 2_000L, "longest silence $longestSilence ms")
    }

    /** Feeds [notifies] at their times and asks for a request on each and on every 100 ms tick. */
    private fun drive(
        requests: BegodeIdentityRequests,
        notifies: List<Pair<Long, String>>,
        untilMillis: Long,
    ): List<Pair<Long, String>> {
        val sent = mutableListOf<Pair<Long, String>>()
        val ticks = (0L..untilMillis step 100L).map { it to null as String? }
        (notifies.map { it.first to it.second as String? } + ticks)
            .sortedBy { it.first }
            .forEach { (at, hexText) ->
                hexText?.let { requests.onNotify(hex(it), at) }
                requests.nextRequest(at)?.let { sent += at to it.decodeToString() }
            }
        return sent
    }

    private fun hex(text: String): ByteArray {
        return ByteArray(text.length / 2) { index -> text.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
