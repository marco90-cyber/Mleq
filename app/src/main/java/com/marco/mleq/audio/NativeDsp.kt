package com.marco.mleq.audio

import com.marco.mleq.util.AppLog
import java.nio.ByteBuffer

/**
 * Ponte JNI verso il motore DSP nativo (app/src/main/cpp/dsp_engine.h).
 * Le chiamate di elaborazione avvengono sul thread di riproduzione.
 */
class NativeDsp {
    private var handle: Long = if (available) nCreate() else 0L

    val isReady: Boolean get() = handle != 0L

    fun configure(sampleRate: Int, channels: Int) {
        if (handle != 0L) nConfigure(handle, sampleRate, channels)
    }

    /** Parametri come testo "chiave=valore" ([DspSettings.toNative]). */
    fun setParams(text: String) {
        if (handle != 0L) nSetParams(handle, text)
    }

    fun reset() {
        if (handle != 0L) nReset(handle)
    }

    fun latencyFrames(): Int = if (handle != 0L) nLatency(handle) else 0

    /** Misure: riduzione limiter, riduzione compressore, headroom, guadagno d'ingresso (dB), stadi attivi. */
    fun meters(reset: Boolean): DoubleArray {
        val out = DoubleArray(5)
        if (handle != 0L) nMeters(handle, out, reset)
        return out
    }

    /** Risposta in dB della pila di filtri alle frequenze date. */
    fun response(freqs: DoubleArray): DoubleArray {
        val out = DoubleArray(freqs.size)
        if (handle != 0L) nResponse(handle, freqs, out)
        return out
    }

    /** Guadagni realmente applicati alle 15 bande del grafico (curva fedele). */
    fun graphicApplied(): DoubleArray {
        val out = DoubleArray(DspSettings.GRAPHIC_BANDS)
        if (handle != 0L) nGraphicApplied(handle, out)
        return out
    }

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
    private external fun nSetParams(h: Long, text: String)
    private external fun nReset(h: Long)
    private external fun nSetResample(h: Long, outRate: Int)
    private external fun nMaxOut(h: Long, inFrames: Int): Int
    private external fun nFlushIn(h: Long): Int
    private external fun nLatency(h: Long): Int
    private external fun nMeters(h: Long, out: DoubleArray, reset: Boolean)
    private external fun nResponse(h: Long, freqs: DoubleArray, out: DoubleArray)
    private external fun nGraphicApplied(h: Long, out: DoubleArray)
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
