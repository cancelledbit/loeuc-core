package pw.vasilevskiy.loeuc.shared.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Converts byte chunks from a platform transport into typed raw frames. */
fun Flow<ByteArray>.toFrames(decode: (ByteArray) -> List<DeviceFrame>): Flow<DeviceFrame> = flow {
    collect { chunk ->
        for (frame in decode(chunk)) emit(frame)
    }
}
