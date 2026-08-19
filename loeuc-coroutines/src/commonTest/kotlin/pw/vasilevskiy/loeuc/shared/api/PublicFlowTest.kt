package pw.vasilevskiy.loeuc.shared.api

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PublicFlowTest {
    @Test
    fun convertsTransportChunksInOrder() = runTest {
        val result = flowOf(byteArrayOf(1), byteArrayOf(2)).toFrames { bytes ->
            listOf(DeviceFrame(bytes[0].toLong(), "notify", "FFE1", bytes))
        }.toList()

        assertEquals(listOf(1L, 2L), result.map { it.timestampMs })
    }
}
