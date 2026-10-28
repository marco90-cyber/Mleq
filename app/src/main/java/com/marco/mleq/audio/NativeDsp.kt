package com.marco.mleq.audio

import com.marco.mleq.util.AppLog
import java.nio.ByteBuffer

/**
 * Ponte JNI verso il motore DSP nativo (cascata biquad + limiter look-ahead).
 * Tutte le chiamate avvengono sul thread di riproduzione.
 */
class NativeDsp {
    private var handle: Long = if (available) nCreate() else 0L

    val isReady: Boolean get() = handle != 0L

    fun configure(sampleRate: Int, channels: Int) {
        if (handle != 0L) nConfigure(handle, sampleRate, channels)
    }

    fun setFilters(coefs: DoubleArray, stages: Int, preGain: Double) {
        if (handle != 0L) nSetFilters(handle, coefs, stages, preGain)
    }

    fun setLimiter(on: Boolean, thresholdDb: Float, releaseMs: Float, lookaheadMs: Float) {
        if (handle != 0L) nSetLimiter(handle, on, thresholdDb, releaseMs, lookaheadMs)
    }

    fun reset() {
        if (handle != 0L) nReset(handle)
    }

    fun latencyFrames(): Int = if (handle != 0L) nLatency(handle) else 0

    /** Massima riduzione di guadagno del limiter dall'ultima lettura (dB, <= 0). */
    fun limiterReductionDb(reset: Boolean): Float = if (handle != 0L) nLimiterGainDb(handle, reset) else 0f

    /** [inBuf] e [outBuf] devono essere buffer diretti. */
    fun process(
        inBuf: ByteBuffer, inOffset: Int, inEnc: Int, frames: Int,
        outBuf: ByteBuffer, outEnc: Int, bypass: Boolean,
    ): Int = if (handle != 0L) nProcess(handle, inBuf, inOffset, inEnc, frames, outBuf, outEnc, bypass) else 0

    fun setResample(outRate: Int) {
        if (handle != 0L) nSetResample(handle, outRate)
    }

    fun maxOut(inFrames: Int): Int = if (handle != 0L) nMaxOut(handle, inFrames) else inFrames

    fun flushIn(): Int = if (handle != 0L) nFlushIn(handle) else 0

    fun flush(outBuf: ByteBuffer, outEnc: Int, maxFrames: Int): Int =
        if (handle != 0L) nFlush(handle, outBuf, outEnc, maxFrames) else 0

    fun release() {
        if (handle != 0L) {
            nDestroy(handle)
            handle = 0L
        }
    }

    private external fun nCreate(): Long
    private external fun nDestroy(h: Long)
    private external fun nConfigure(h: Long, sr: Int, ch: Int)
    private external fun nSetFilters(h: Long, coefs: DoubleArray, stages: Int, preGain: Double)
    private external fun nSetLimiter(h: Long, on: Boolean, thr: Float, rel: Float, look: Float)
    private external fun nReset(h: Long)
    private external fun nSetResample(h: Long, outRate: Int)
    private external fun nMaxOut(h: Long, inFrames: Int): Int
    private external fun nFlushIn(h: Long): Int
    private external fun nLatency(h: Long): Int
    private external fun nLimiterGainDb(h: Long, reset: Boolean): Float
    private external fun nProcess(
        h: Long, inBuf: ByteBuffer, inOff: Int, inEnc: Int, frames: Int,
        outBuf: ByteBuffer, outEnc: Int, bypass: Boolean,
    ): Int

    private external fun nFlush(h: Long, outBuf: ByteBuffer, outEnc: Int, maxFrames: Int): Int

    companion object {
        const val ENC_16 = 0
        const val ENC_24 = 1
        const val ENC_32 = 2
        const val ENC_FLOAT = 3
        /** Float 32 bit con valori quantizzati a 24 bit (solo uscita). */
        const val ENC_FLOAT24 = 4

        val available: Boolean = try {
            System.loadLibrary("mleqdsp")
            true
        } catch (t: Throwable) {
            AppLog.e("Audio", "Libreria DSP nativa non caricata: equalizzatore non disponibile", t)
            false
        }
    }
}
