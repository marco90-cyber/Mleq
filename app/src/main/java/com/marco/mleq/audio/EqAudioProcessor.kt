package com.marco.mleq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.marco.mleq.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stato reale del DSP, per la schermata "Uscita audio" (solo valori effettivamente in uso). */
data class DspInfo(
    val active: Boolean = false,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val inEncoding: String = "",
    val outEncoding: String = "",
    val bypass: Boolean = true,
    val limiter: Boolean = false,
    val latencyMs: Float = 0f,
    val stages: Int = 0,
    /** Guadagno all'ingresso del DSP: preamp + ReplayGain - headroom (dB). */
    val inputGainDb: Float = 0f,
    val headroomDb: Float = 0f,
    /** Guadagno ReplayGain applicato (dB); null = tag assenti o funzione spenta. */
    val replayGainDb: Float? = null,
    /** "tag" = letto dal file, "analisi" = misurato dall'app. */
    val replayGainSource: String = "",
    /** Moduli accesi, per la riga di stato. */
    val modules: List<String> = emptyList(),
)

/** Riduzioni massime di limiter e compressore dall'ultima lettura (dB, <= 0). */
class Meters(val limiterDb: Float, val compressorDb: Float)

/**
 * Processore audio di Media3 che passa il PCM al motore nativo ([NativeDsp]).
 * Ingressi: PCM 16/24/32 bit e float. Uscita: 16 bit (con dither), float con 24 bit utili o float 32 bit.
 * Con il DSP spento l'audio passa intatto (PCM16 bit-exact).
 */
class EqAudioProcessor(private val analyzer: Analyzer) : BaseAudioProcessor() {

    private val dsp = NativeDsp()

    @Volatile private var pending: DspSettings = DspSettings()
    @Volatile private var configVersion = 0

    /** Profondità di uscita decisa dall'uscita adattiva: 16, 24 (float) oppure 32 (float). */
    @Volatile var outputBits: Int = 32

    /** Frequenza di uscita se diversa da quella d'ingresso (ricampionamento), 0 = nessuno. */
    @Volatile var targetRate: Int = 0
    private var outRate = 0

    @Volatile var info: DspInfo = DspInfo()
        private set

    /** Uscita sull'altoparlante del telefono (per Mobile Bass). */
    @Volatile var speaker: Boolean = false
        set(value) { if (field != value) { field = value; configVersion++ } }

    /** Volume di ascolto lineare 0..1 (per la loudness legata al volume). */
    @Volatile var listenVolume: Double = 1.0
        set(value) { if (kotlin.math.abs(field - value) > 1e-4) { field = value; configVersion++ } }

    /** Tag ReplayGain del brano in arrivo (impostati dal sink a ogni cambio di formato). */
    @Volatile var replayGain: ReplayGain.Info? = null
        set(value) { field = value; configVersion++ }

    /** Loudness misurata dall'app per il brano attuale, usata se il file non ha tag. */
    @Volatile var scannedGain: ReplayGain.Info? = null
        set(value) { field = value; configVersion++ }

    private var appliedVersion = -1
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var inEnc = NativeDsp.ENC_16
    private var outEnc = NativeDsp.ENC_16
    private var bypass = true
    private var scratchIn: ByteBuffer? = null
    private var firstBuffer = true

    fun setSettings(s: DspSettings) {
        pending = s
        configVersion++
    }

    fun meters(reset: Boolean = true): Meters {
        val m = dsp.meters(reset)
        return Meters(m[0].toFloat(), m[1].toFloat())
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (!dsp.isReady) return AudioProcessor.AudioFormat.NOT_SET
        val enc = inputAudioFormat.encoding
        inEnc = when (enc) {
            C.ENCODING_PCM_16BIT -> NativeDsp.ENC_16
            C.ENCODING_PCM_24BIT -> NativeDsp.ENC_24
            C.ENCODING_PCM_32BIT -> NativeDsp.ENC_32
            C.ENCODING_PCM_FLOAT -> NativeDsp.ENC_FLOAT
            else -> return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount !in 1..8) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        encoding = enc
        outEnc = when (outputBits) {
            16 -> NativeDsp.ENC_16
            24 -> NativeDsp.ENC_FLOAT24
            else -> NativeDsp.ENC_FLOAT
        }
        dsp.configure(sampleRate, channels)
        outRate = if (targetRate > 0 && targetRate != sampleRate) targetRate else sampleRate
        dsp.setResample(if (outRate != sampleRate) outRate else 0)
        analyzer.sampleRate = outRate
        info = DspInfo(sampleRate = sampleRate, channels = channels, inEncoding = encName(inEnc), outEncoding = encName(outEnc))
        firstBuffer = true
        AppLog.i("Audio", "DSP configurato: ${encName(inEnc)}, $sampleRate Hz, $channels can. -> ${encName(outEnc)}")
        appliedVersion = -1
        val outC = if (outEnc == NativeDsp.ENC_16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT
        return AudioProcessor.AudioFormat(outRate, channels, outC)
    }

    override fun onFlush() {
        dsp.reset()
    }

    override fun onReset() {
        appliedVersion = -1
        scratchIn = null
        info = DspInfo()
    }

    private fun encName(e: Int) = when (e) {
        NativeDsp.ENC_16 -> "PCM 16 bit"
        NativeDsp.ENC_24 -> "PCM 24 bit"
        NativeDsp.ENC_32 -> "PCM 32 bit"
        NativeDsp.ENC_FLOAT24 -> "float 32 bit (24 bit utili)"
        else -> "PCM float 32 bit"
    }

    private fun refreshIfNeeded() {
        val v = configVersion
        if (v == appliedVersion) return
        val s = pending
        val rgInfo = replayGain ?: scannedGain
        val rgDb = ReplayGain.gainDb(rgInfo, s.replayGain)
        dsp.setParams(s.toNative(rgDb, speaker, listenVolume))
        bypass = !s.master
        appliedVersion = v
        val m = dsp.meters(false)
        info = DspInfo(
            active = s.master, sampleRate = sampleRate, channels = channels,
            inEncoding = encName(inEnc), outEncoding = encName(outEnc),
            bypass = bypass, limiter = s.master && s.limiter,
            latencyMs = if (sampleRate > 0) dsp.latencyFrames() * 1000f / sampleRate else 0f,
            stages = if (s.master) m[4].toInt() else 0,
            inputGainDb = m[3].toFloat(), headroomDb = m[2].toFloat(),
            replayGainDb = if (s.master && s.replayGain != ReplayGain.OFF && rgInfo != null) rgDb.toFloat() else null,
            replayGainSource = if (rgInfo == null) "" else if (replayGain != null) "tag" else "analisi",
            modules = if (s.master) activeModules(s, speaker) else emptyList(),
        )
        AppLog.d("Audio", "DSP: ${if (bypass) "bypass" else "attivo"}, stadi=${info.stages}, ingresso=${"%.1f".format(info.inputGainDb)} dB")
    }

    private fun directInput(src: ByteBuffer, bytes: Int): Pair<ByteBuffer, Int> {
        if (src.isDirect) return src to src.position()
        var s = scratchIn
        if (s == null || s.capacity() < bytes) {
            s = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
            scratchIn = s
        }
        s.clear()
        val dup = src.duplicate()
        dup.limit(dup.position() + bytes)
        s.put(dup)
        return s to 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        refreshIfNeeded()
        if (firstBuffer) {
            firstBuffer = false
            AppLog.i("Audio", "DSP: primo buffer ricevuto ($remaining byte, diretto=${inputBuffer.isDirect})")
        }
        val bps = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            else -> 4
        }
        val frames = remaining / (bps * channels)
        if (frames == 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val inBytes = frames * bps * channels
        val outBps = if (outEnc == NativeDsp.ENC_16) 2 else 4
        val (src, off) = directInput(inputBuffer, inBytes)
        val out = replaceOutputBuffer(dsp.maxOut(frames) * channels * outBps)
        val outFrames = dsp.process(src, off, inEnc, frames, out, outEnc, bypass)
        out.position(outFrames * channels * outBps)
        out.flip()
        if (analyzer.enabled) tap(out, outFrames)
        inputBuffer.position(inputBuffer.position() + inBytes)
    }

    private fun tap(out: ByteBuffer, frames: Int) {
        val b = out.duplicate().order(ByteOrder.nativeOrder())
        val ch = channels
        val is16 = outEnc == NativeDsp.ENC_16
        for (f in 0 until frames) {
            val base = f * ch
            val l: Float
            val r: Float
            if (is16) {
                l = b.getShort(base * 2) / 32768f
                r = if (ch > 1) b.getShort((base + 1) * 2) / 32768f else l
            } else {
                l = b.getFloat(base * 4)
                r = if (ch > 1) b.getFloat((base + 1) * 4) else l
            }
            analyzer.push(l, r)
        }
    }

    override fun onQueueEndOfStream() {
        val resampling = outRate != sampleRate
        if (dsp.latencyFrames() == 0 && !resampling) return
        val outBps = if (outEnc == NativeDsp.ENC_16) 2 else 4
        val cap = dsp.maxOut(dsp.flushIn() * 2) + 16
        val out = replaceOutputBuffer(cap * channels * outBps)
        val n = dsp.flush(out, outEnc, cap)
        out.position(n * channels * outBps)
        out.flip()
    }

    companion object {
        /** Nomi brevi dei moduli accesi. */
        fun activeModules(s: DspSettings, speaker: Boolean): List<String> = buildList {
            when (s.eqMode) { Modes.EQ_GRAPHIC -> add("EQ grafico"); Modes.EQ_PARAMETRIC -> add("EQ parametrico") }
            if (s.bassDb > 0f || s.trebleDb > 0f) add("Toni")
            if (s.analogBass) add("Analog Bass")
            if (s.tube) add("Tube")
            if (s.exciter) add("Exciter")
            if (s.air) add("Hi-Res Air")
            if (s.hdrMode > 0) add("HDR")
            if (s.surroundMode > 0) add("Surround+")
            if (s.mono || s.balance != 0f || s.expand > 0f || s.crossfeed > 0f) add("Stereo")
            if (s.dbfbMode > 0) add("DBFB")
            if (s.mobileBass && speaker) add("Mobile Bass")
            if (s.loudness) add("Loudness")
            if (s.compressor) add("Compressore")
            if (s.limiter) add("Limiter")
        }
    }
}
