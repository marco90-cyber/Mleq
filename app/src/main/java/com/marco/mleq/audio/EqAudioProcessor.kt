package com.marco.mleq.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.marco.mleq.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

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
    val preampDb: Float = 0f,
)

/**
 * Elaborazione DSP in tempo reale tramite il motore nativo:
 * preamp + bassi + N bande parametriche + alti (biquad in doppia precisione) e limiter look-ahead.
 * I coefficienti sono calcolati in Kotlin ([EqMath]) e passati al motore nativo.
 *
 * Ingressi: PCM 16/24/32 bit e float. Uscita scelta dall'utente: 16 bit (con dither), 24 bit o 32 bit (float).
 * Con DSP spento si ha un bypass reale (PCM16 bit-exact).
 */
class EqAudioProcessor(private val analyzer: Analyzer) : BaseAudioProcessor() {

    private val dsp = NativeDsp()

    @Volatile
    private var pending: EqConfig = EqConfig()

    @Volatile
    private var configVersion = 0

    /** Profondità di uscita decisa dall'uscita adattiva: 16 (con dither) oppure 32 (float). */
    @Volatile
    var outputBits: Int = 32

    /** Frequenza di uscita se diversa da quella d'ingresso (ricampionamento), 0 = nessuno. */
    @Volatile
    var targetRate: Int = 0
    private var outRate = 0

    @Volatile
    var info: DspInfo = DspInfo()
        private set

    private var appliedVersion = -1
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var inEnc = NativeDsp.ENC_16
    private var outEnc = NativeDsp.ENC_16
    private var bypass = true
    private var limiterOn = false
    private var stageCount = 0
    private var preDb = 0f
    private var scratchIn: ByteBuffer? = null
    private var firstBuffer = true

    fun setConfig(config: EqConfig) {
        pending = config
        configVersion++
    }

    /** Massima riduzione del limiter dall'ultima lettura (dB). */
    fun limiterReductionDb(reset: Boolean = true): Float = dsp.limiterReductionDb(reset)

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
        info = DspInfo(
            sampleRate = sampleRate, channels = channels,
            inEncoding = encName(inEnc), outEncoding = encName(outEnc),
        )
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
        val cfg = pending
        val fs = sampleRate.toDouble()
        val list = EqMath.stages(cfg, fs)
        val flat = DoubleArray(list.size * 5)
        for ((i, c) in list.withIndex()) {
            flat[i * 5] = c.b0
            flat[i * 5 + 1] = c.b1
            flat[i * 5 + 2] = c.b2
            flat[i * 5 + 3] = c.a1
            flat[i * 5 + 4] = c.a2
        }
        val preDbEff = cfg.effectivePreampDb(fs)
        val preGain = 10.0.pow(preDbEff / 20.0)
        stageCount = list.size
        dsp.setFilters(flat, list.size, preGain)
        dsp.setStereo(cfg.balanceFactor(), cfg.widthFactor())

        val active = cfg.dspActive()
        bypass = !active
        limiterOn = cfg.limiterOn && active
        dsp.setLimiter(limiterOn, cfg.limiterThresholdDb, cfg.limiterReleaseMs, LIMITER_LOOKAHEAD_MS)
        preDb = preDbEff.toFloat()
        appliedVersion = v
        info = DspInfo(
            active = active, sampleRate = sampleRate, channels = channels,
            inEncoding = encName(inEnc), outEncoding = encName(outEnc),
            bypass = bypass, limiter = limiterOn,
            latencyMs = if (limiterOn) dsp.latencyFrames() * 1000f / sampleRate else 0f,
            stages = if (active) list.count { it !== EqMath.IDENTITY } else 0,
            preampDb = preDb,
        )
        AppLog.d(
            "Audio",
            "DSP: ${if (bypass) "bypass" else "attivo"}, limiter=${limiterOn}, stadi=${info.stages}, " +
                "preamp=${"%.1f".format(preDb)} dB"
        )
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
            AppLog.i("Audio", "DSP: primo buffer ricevuto ($remaining byte, diretto=${inputBuffer.isDirect}, analizzatore=${analyzer.enabled})")
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
                l = b.getShort((base) * 2) / 32768f
                r = if (ch > 1) b.getShort((base + 1) * 2) / 32768f else l
            } else {
                l = b.getFloat((base) * 4)
                r = if (ch > 1) b.getFloat((base + 1) * 4) else l
            }
            analyzer.push(l, r)
        }
    }

    override fun onQueueEndOfStream() {
        val resampling = outRate != sampleRate
        if (!limiterOn && !resampling) return
        val outBps = if (outEnc == NativeDsp.ENC_16) 2 else 4
        val cap = dsp.maxOut(dsp.flushIn() * 2) + 16
        val out = replaceOutputBuffer(cap * channels * outBps)
        val n = dsp.flush(out, outEnc, cap)
        out.position(n * channels * outBps)
        out.flip()
    }

    companion object {
        const val LIMITER_LOOKAHEAD_MS = 5f
    }
}
