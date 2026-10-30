package com.marco.mleq.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.marco.mleq.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Legge un file con il decoder di Android e ne misura la loudness (EBU R128) senza riprodurlo.
 * Serve ai brani senza tag ReplayGain: il risultato è salvato in [LoudnessCache].
 */
object LoudnessScanner {
    /** Riferimento ReplayGain 2.0 (-18 LUFS). */
    const val REFERENCE_LUFS = -18.0

    class Result(val lufs: Double, val peak: Double)

    /** Ritorna null se il file non è decodificabile, è più corto di 400 ms o l'analisi è annullata. */
    fun scan(context: Context, uri: String, cancel: AtomicBoolean): Result? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var meter: NativeLoudness? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            var track = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; inFormat = f; break }
            }
            if (track < 0 || inFormat == null) return null
            extractor.selectTrack(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            inFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            var sr = inFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var ch = inFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmFloat = true
            meter = NativeLoudness(sr, ch)
            if (!meter.isReady) return null
            var feedBuf: ByteBuffer? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleLoops = 0
            while (!outputDone) {
                if (cancel.get()) return null
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val nsr = f.optInt(MediaFormat.KEY_SAMPLE_RATE, sr)
                        val nch = f.optInt(MediaFormat.KEY_CHANNEL_COUNT, ch)
                        pcmFloat = f.optInt(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                        if (nsr != sr || nch != ch) {
                            // cambio di formato a metà file: si misura solo il primo tratto
                            sr = nsr; ch = nch
                        }
                    }
                    oi >= 0 -> {
                        idleLoops = 0
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(oi)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            val frames = info.size / ((if (pcmFloat) 4 else 2) * ch)
                            if (frames > 0) {
                                val need = frames * ch * 4
                                if (feedBuf == null || feedBuf.capacity() < need) {
                                    feedBuf = ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
                                }
                                val fb = feedBuf!!
                                fb.clear()
                                if (pcmFloat) {
                                    fb.put(out)
                                } else {
                                    val s = out.asShortBuffer()
                                    while (s.hasRemaining()) fb.putFloat(s.get() / 32768f)
                                }
                                fb.flip()
                                meter.feed(fb, frames)
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(oi, false)
                    }
                    else -> if (inputDone && ++idleLoops > 200) outputDone = true  // decoder fermo: basta
                }
            }
            val (lufs, peak) = meter.result()
            return if (lufs <= -900.0) null else Result(lufs, peak)
        } catch (e: Throwable) {
            AppLog.w("Audio", "Analisi loudness non riuscita: ${e.message}")
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
            meter?.release()
        }
    }

    /** Guadagno ReplayGain (dB) a partire dalla loudness misurata. */
    fun toInfo(r: Result): ReplayGain.Info = ReplayGain.Info(trackGain = REFERENCE_LUFS - r.lufs, trackPeak = r.peak)

    private fun MediaFormat.optInt(key: String, def: Int): Int = if (containsKey(key)) getInteger(key) else def
}
