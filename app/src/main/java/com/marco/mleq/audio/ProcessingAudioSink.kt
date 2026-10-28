package com.marco.mleq.audio

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.marco.mleq.util.AppLog
import java.nio.ByteBuffer

/**
 * Applica il DSP PRIMA del sink di Media3.
 *
 * Motivo: DefaultAudioSink, con l'uscita float attiva e un ingresso PCM a 24/32 bit, usa solo il suo
 * convertitore float e salta i processori personalizzati: i FLAC/WAV Hi-Res non passavano né dall'EQ
 * né dall'analizzatore. Qui il processore vede sempre il PCM uscito dal decoder, qualunque sia il formato,
 * e gira al sink già il formato finale (16 bit oppure float 32 bit).
 */
@OptIn(UnstableApi::class)
class ProcessingAudioSink(
    sink: AudioSink,
    private val processor: EqAudioProcessor,
    private val monitor: OutputMonitor,
    private val adaptive: () -> Boolean,
) : ForwardingAudioSink(sink) {

    private var active = false
    private var pending: ByteBuffer? = null
    private var pendingPts = 0L
    private var pendingCount = 1
    private var eosQueued = false

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        active = false
        pending = null
        eosQueued = false
        var fmt = inputFormat
        val raw = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType && inputFormat.pcmEncoding != Format.NO_VALUE
        if (raw) {
            // uscita adattiva: frequenza e profondità richieste dal dispositivo collegato
            val plan = if (adaptive()) monitor.plan(inputFormat.sampleRate) else null
            processor.targetRate = if (plan != null && plan.rate != inputFormat.sampleRate) plan.rate else 0
            processor.outputBits = plan?.bits ?: 32
            monitor.lastPlan = plan?.let { it.describe(inputFormat.sampleRate) } ?: "Standard (uscita non adattata)"
            try {
                val out = processor.configure(AudioProcessor.AudioFormat(inputFormat))
                if (processor.isActive) {
                    processor.flush()
                    active = true
                    fmt = inputFormat.buildUpon().setPcmEncoding(out.encoding).setSampleRate(out.sampleRate).build()
                } else {
                    AppLog.w("Audio", "DSP non applicabile a questo formato (codifica ${inputFormat.pcmEncoding})")
                }
            } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                AppLog.w("Audio", "DSP: formato non gestito: ${e.message}")
            }
        }
        super.configure(fmt, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!active) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (pending == null) {
            if (!buffer.hasRemaining()) return true
            processor.queueInput(buffer)
            // eventuale resto non allineato a un frame intero: scartato
            if (buffer.hasRemaining()) buffer.position(buffer.limit())
            pending = processor.output
            pendingPts = presentationTimeUs
            pendingCount = encodedAccessUnitCount
        }
        val out = pending!!
        if (out.hasRemaining()) {
            if (!super.handleBuffer(out, pendingPts, pendingCount)) return false
        }
        pending = null
        return true
    }

    override fun playToEndOfStream() {
        if (active) {
            if (!eosQueued) {
                processor.queueEndOfStream()
                pending = processor.output
                eosQueued = true
            }
            val out = pending
            if (out != null && out.hasRemaining()) {
                if (!super.handleBuffer(out, pendingPts, pendingCount)) return
            }
            pending = null
        }
        super.playToEndOfStream()
    }

    override fun hasPendingData(): Boolean = super.hasPendingData() || (pending?.hasRemaining() == true)

    override fun flush() {
        pending = null
        eosQueued = false
        if (active) processor.flush()
        super.flush()
    }

    override fun reset() {
        pending = null
        eosQueued = false
        active = false
        processor.reset()
        super.reset()
    }
}
