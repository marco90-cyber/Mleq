package com.marco.mleq.audio

import java.nio.ByteBuffer

/** Misuratore di loudness nativo (ITU-R BS.1770-4). Una istanza per brano, usata da un solo thread. */
class NativeLoudness(sampleRate: Int, channels: Int) {
    private var handle: Long = if (NativeDsp.available) nCreate(sampleRate, channels) else 0L

    val isReady: Boolean get() = handle != 0L

    /** [buf] = float interlacciati in un buffer diretto, little endian nativo. */
    fun feed(buf: ByteBuffer, frames: Int) {
        if (handle != 0L) nFeed(handle, buf, frames)
    }

    /** (LUFS integrati, picco dei campioni). LUFS <= -900 = non misurabile. */
    fun result(): Pair<Double, Double> {
        if (handle == 0L) return -1000.0 to 0.0
        val out = DoubleArray(2)
        nResult(handle, out)
        return out[0] to out[1]
    }

    fun release() {
        if (handle != 0L) {
            nDestroy(handle)
            handle = 0L
        }
    }

    private external fun nCreate(sr: Int, ch: Int): Long
    private external fun nFeed(h: Long, buf: ByteBuffer, frames: Int)
    private external fun nResult(h: Long, out: DoubleArray)
    private external fun nDestroy(h: Long)
}
