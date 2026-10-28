package com.marco.mleq.audio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Configurazione completa dell'equalizzatore:
 *  - da 3 a 32 bande parametriche, con frequenza, guadagno e Q per ogni banda
 *  - preamp e (opzionale) headroom automatico
 *  - manopole Bassi / Alti (shelving, boost e attenuazione) con frequenza selezionabile
 *  - limiter look-ahead (attivo solo quando c'è elaborazione)
 */
data class EqConfig(
    val enabled: Boolean = false,
    val bandCount: Int = 10,
    val freqs: List<Float> = defaultFreqs(10),
    val gains: List<Float> = List(10) { 0f },
    val qs: List<Float> = autoQs(defaultFreqs(10)),
    /** Tipo di filtro per banda: 0 = low shelf, 1 = high shelf, 2 = peaking (vuoto = tutte peaking). */
    val types: List<Int> = emptyList(),
    val preampDb: Float = 0f,
    /** 0..100, 50 = neutro (0 dB), 0 = -12 dB, 100 = +12 dB. */
    val bassPercent: Int = 0,
    val bassFreq: Float = 100f,
    val treblePercent: Int = 0,
    val trebleFreq: Float = 10000f,
    val limiterOn: Boolean = true,
    val autoHeadroom: Boolean = false,
    /** Soglia del limiter in dBFS (-12..0). */
    val limiterThresholdDb: Float = -0.5f,
    /** Rilascio del limiter in ms (20..500). */
    val limiterReleaseMs: Float = 80f,
) {
    /** Cambia il numero di bande ricampionando la curva attuale. */
    fun typeAt(i: Int): Int = types.getOrNull(i) ?: 2

    fun withBandCount(n: Int): EqConfig {
        val count = n.coerceIn(MIN_BANDS, MAX_BANDS)
        if (count == bandCount) return this
        val newFreqs = defaultFreqs(count)
        val newGains = newFreqs.map { f -> interpolate(freqs, gains, f) }
        return copy(bandCount = count, freqs = newFreqs, gains = newGains, qs = autoQs(newFreqs), types = emptyList())
    }

    fun withQ(index: Int, q: Float): EqConfig {
        if (index !in qs.indices) return this
        val list = qs.toMutableList()
        list[index] = q.coerceIn(MIN_Q, MAX_Q)
        return copy(qs = list)
    }

    /** Ripristina il Q automatico (in base alla distanza fra le bande). */
    fun withAutoQ(): EqConfig = copy(qs = autoQs(freqs))

    fun withGain(index: Int, gainDb: Float): EqConfig {
        if (index !in gains.indices) return this
        val g = gains.toMutableList()
        g[index] = gainDb.coerceIn(-GAIN_RANGE, GAIN_RANGE)
        return copy(gains = g)
    }

    fun withFreq(index: Int, f: Float): EqConfig {
        if (index !in freqs.indices) return this
        val list = freqs.toMutableList()
        val lo = if (index > 0) list[index - 1] * 1.05f else MIN_FREQ
        val hi = if (index < list.lastIndex) list[index + 1] / 1.05f else MAX_FREQ
        list[index] = f.coerceIn(lo, hi.coerceAtLeast(lo))
        return copy(freqs = list)
    }

    fun withCurve(points: List<Pair<Float, Float>>): EqConfig {
        val fs = points.map { it.first }
        val gs = points.map { it.second }
        return copy(gains = freqs.map { f -> interpolate(fs, gs, f).coerceIn(-GAIN_RANGE, GAIN_RANGE) })
    }

    /** Q effettivo di una banda. */
    fun qFor(index: Int): Double = (qs.getOrNull(index) ?: 1f).toDouble().coerceIn(MIN_Q.toDouble(), MAX_Q.toDouble())

    /**
     * Picco massimo di amplificazione (dB, >= 0) di bande + bassi + alti, preamp escluso.
     * Serve per l'headroom automatico.
     */
    fun boostPeakDb(fs: Double = 48000.0): Double {
        val st = EqMath.stages(this, fs)
        var peak = 0.0
        for (i in 0 until 256) {
            val f = 20.0 * (20000.0 / 20.0).pow(i / 255.0)
            var t = 0.0
            for (c in st) t += EqMath.magnitudeDb(c, fs, f)
            if (t > peak) peak = t
        }
        return peak
    }

    /** Preamp effettivo in dB: preamp scelto meno l'headroom automatico (se attivo). */
    fun effectivePreampDb(fs: Double): Double =
        if (!enabled) 0.0 else preampDb - (if (autoHeadroom) boostPeakDb(fs) else 0.0)

    /** True se serve elaborare l'audio (altrimenti bypass reale). */
    fun dspActive(): Boolean =
        enabled || bassPercent != 0 || treblePercent != 0

    /** Risposta in dB a una certa frequenza (per il grafico). */
    fun responseDb(f: Double, fs: Double = 48000.0, includeTone: Boolean = true): Double {
        var total = 0.0
        for (c in EqMath.stages(this, fs)) total += EqMath.magnitudeDb(c, fs, f)
        if (enabled) total += preampDb - (if (autoHeadroom) boostPeakDb(fs) else 0.0)
        if (!includeTone) total -= toneResponseDb(f, fs)
        return total
    }

    /** Solo il contributo delle manopole Bassi/Alti (il Tono è separato dall'equalizzatore). */
    fun toneResponseDb(f: Double, fs: Double = 48000.0): Double {
        var t = 0.0
        if (bassPercent != 0) t += EqMath.magnitudeDb(EqMath.lowShelf(fs, bassFreq.toDouble(), EqMath.toneDb(bassPercent)), fs, f)
        if (treblePercent != 0) t += EqMath.magnitudeDb(EqMath.highShelf(fs, trebleFreq.toDouble(), EqMath.toneDb(treblePercent)), fs, f)
        return t
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("enabled", enabled)
        o.put("bandCount", bandCount)
        o.put("freqs", JSONArray(freqs.map { it.toDouble() }))
        o.put("gains", JSONArray(gains.map { it.toDouble() }))
        o.put("qs", JSONArray(qs.map { it.toDouble() }))
        if (types.any { it != 2 }) o.put("types", JSONArray(types))
        o.put("toneV", 3)
        o.put("limiter", limiterOn)
        o.put("autoHeadroom", autoHeadroom)
        o.put("limThr", limiterThresholdDb.toDouble())
        o.put("limRel", limiterReleaseMs.toDouble())
        o.put("preamp", preampDb.toDouble())
        o.put("bass", bassPercent)
        o.put("bassFreq", bassFreq.toDouble())
        o.put("treble", treblePercent)
        o.put("trebleFreq", trebleFreq.toDouble())
        return o.toString()
    }

    companion object {
        const val MIN_BANDS = 3
        const val MAX_BANDS = 32
        const val MIN_Q = 0.2f
        const val MAX_Q = 12f
        const val GAIN_RANGE = 12f
        const val MIN_FREQ = 20f
        const val MAX_FREQ = 20000f

        /** Q automatico: sovrappone le bande adiacenti in base alla loro distanza in ottave. */
        fun autoQs(freqs: List<Float>): List<Float> = List(freqs.size) { i -> autoQ(freqs, i).toFloat() }

        fun autoQ(freqs: List<Float>, index: Int): Double {
            val n = freqs.size
            if (n < 2) return 1.0
            val lo = if (index > 0) freqs[index - 1] else freqs[index]
            val hi = if (index < n - 1) freqs[index + 1] else freqs[index]
            val octaves = if (hi > lo) (ln((hi / lo).toDouble()) / ln(2.0)) / (if (index in 1 until n - 1) 2.0 else 1.0) else 1.0
            val q = 1.0 / (2.0 * sinh(ln(2.0) / 2.0 * octaves.coerceAtLeast(0.2)))
            return q.coerceIn(0.4, 5.0)
        }

        fun defaultFreqs(n: Int): List<Float> = when (n) {
            3 -> listOf(100f, 1000f, 10000f)
            4 -> listOf(60f, 400f, 2500f, 10000f)
            5 -> listOf(60f, 230f, 910f, 3600f, 14000f)
            // 15 bande: terzi d'ottava ISO a passo 2/3 d'ottava
            15 -> listOf(25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f)
            // 31 bande: terzi d'ottava ISO, da 20 Hz a 20 kHz
            31 -> listOf(
                20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
                800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f,
                12500f, 16000f, 20000f
            )
            10 -> listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
            else -> {
                val lo = 32.0
                val hi = 16000.0
                List(n) { i ->
                    val f = lo * (hi / lo).pow(i.toDouble() / (n - 1))
                    niceRound(f).toFloat()
                }
            }
        }

        private fun niceRound(f: Double): Double {
            val step = when {
                f < 100 -> 1.0
                f < 1000 -> 10.0
                f < 10000 -> 100.0
                else -> 500.0
            }
            return (f / step).roundToInt() * step
        }

        /** Interpolazione lineare sull'asse logaritmico delle frequenze. */
        fun interpolate(xs: List<Float>, ys: List<Float>, f: Float): Float {
            if (xs.isEmpty()) return 0f
            if (f <= xs.first()) return ys.first()
            if (f >= xs.last()) return ys.last()
            for (i in 0 until xs.size - 1) {
                if (f >= xs[i] && f <= xs[i + 1]) {
                    val t = (ln(f / xs[i]) / ln(xs[i + 1] / xs[i])).toFloat()
                    return ys[i] + (ys[i + 1] - ys[i]) * t
                }
            }
            return ys.last()
        }

        fun fromJson(s: String?): EqConfig {
            if (s.isNullOrBlank()) return EqConfig()
            return try {
                val o = JSONObject(s)
                val n = o.optInt("bandCount", 10).coerceIn(MIN_BANDS, MAX_BANDS)
                val fa = o.optJSONArray("freqs")
                val ga = o.optJSONArray("gains")
                val freqs = if (fa != null && fa.length() == n) List(n) { fa.getDouble(it).toFloat() } else defaultFreqs(n)
                val gains = if (ga != null && ga.length() == n) List(n) { ga.getDouble(it).toFloat() } else List(n) { 0f }
                val qa = o.optJSONArray("qs")
                val qs = if (qa != null && qa.length() == n) {
                    List(n) { qa.getDouble(it).toFloat().coerceIn(MIN_Q, MAX_Q) }
                } else autoQs(freqs)
                val ta = o.optJSONArray("types")
                val types = if (ta != null && ta.length() == n) List(n) { ta.optInt(it, 2) } else emptyList()
                // Formati: v1 manopole 0..100% = 0..+12 dB; v2 50% = neutro (-12..+12 dB);
                // v3 (attuale) 0..100% = 0..+10 dB, solo rinforzo.
                val tv = o.optInt("toneV", 1)
                fun tone(key: String): Int {
                    val raw = o.optInt(key, if (tv == 2) 50 else 0).coerceIn(0, 100)
                    val db = when (tv) {
                        1 -> raw / 100.0 * 12.0
                        2 -> (raw - 50) / 50.0 * 12.0
                        else -> return raw
                    }
                    return Math.round(db / 10.0 * 100.0).toInt().coerceIn(0, 100)
                }
                EqConfig(
                    enabled = o.optBoolean("enabled", false),
                    bandCount = n,
                    freqs = freqs,
                    gains = gains,
                    qs = qs,
                    types = types,
                    preampDb = o.optDouble("preamp", 0.0).toFloat(),
                    bassPercent = tone("bass"),
                    bassFreq = o.optDouble("bassFreq", 100.0).toFloat(),
                    treblePercent = tone("treble"),
                    trebleFreq = o.optDouble("trebleFreq", 10000.0).toFloat(),
                    limiterOn = o.optBoolean("limiter", true),
                    autoHeadroom = o.optBoolean("autoHeadroom", false),
                    limiterThresholdDb = o.optDouble("limThr", -0.5).toFloat().coerceIn(-12f, 0f),
                    limiterReleaseMs = o.optDouble("limRel", 80.0).toFloat().coerceIn(20f, 500f),
                )
            } catch (_: Exception) {
                EqConfig()
            }
        }
    }
}

/** Preset: curve definite su 10 punti ISO, adattate a qualunque numero di bande. */
object EqPresets {
    private val iso = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    class Preset(val name: String, private val g: List<Float>) {
        val points: List<Pair<Float, Float>> get() = iso.zip(g)
    }

    val all = listOf(
        Preset("Piatto", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Bassi", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Alti", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
        Preset("Voce", listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f)),
        Preset("Rock", listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
        Preset("Pop", listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 1f, 2f)),
        Preset("Jazz", listOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 2f, 3f)),
        Preset("Classica", listOf(4f, 3f, 2f, 2f, -1f, -1f, 0f, 2f, 3f, 4f)),
        Preset("Dance", listOf(6f, 5f, 3f, 0f, 0f, -2f, -3f, -3f, 0f, 0f)),
        Preset("Loudness", listOf(6f, 4f, 1f, 0f, -1f, -1f, 0f, 2f, 4f, 5f)),
    )
}

/** Matematica dei filtri biquad (formule dell'Audio EQ Cookbook). */
object EqMath {
    /** Coefficienti normalizzati: b0, b1, b2, a1, a2. */
    class Coef(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    val IDENTITY = Coef(1.0, 0.0, 0.0, 0.0, 0.0)

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Coef(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    fun peaking(fs: Double, f: Double, q: Double, gainDb: Double): Coef {
        if (kotlin.math.abs(gainDb) < 0.01) return IDENTITY
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / fs
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        return norm(
            1.0 + alpha * a, -2.0 * c, 1.0 - alpha * a,
            1.0 + alpha / a, -2.0 * c, 1.0 - alpha / a
        )
    }

    fun lowShelf(fs: Double, f: Double, gainDb: Double): Coef {
        if (kotlin.math.abs(gainDb) < 0.01) return IDENTITY
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / fs
        val c = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val sa = 2.0 * sqrt(a) * alpha
        return norm(
            a * ((a + 1) - (a - 1) * c + sa),
            2.0 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa,
            -2.0 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - sa
        )
    }

    fun highShelf(fs: Double, f: Double, gainDb: Double): Coef {
        if (kotlin.math.abs(gainDb) < 0.01) return IDENTITY
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / fs
        val c = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt(2.0)
        val sa = 2.0 * sqrt(a) * alpha
        return norm(
            a * ((a + 1) + (a - 1) * c + sa),
            -2.0 * a * ((a - 1) + (a + 1) * c),
            a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa,
            2.0 * ((a - 1) - (a + 1) * c),
            (a + 1) - (a - 1) * c - sa
        )
    }

    /** Posizione delle manopole -> dB (0..100, 50 = 0 dB, estremi = -12 / +12 dB). */
    /** Manopole Bassi/Alti: 0..100 = 0..+10 dB (solo rinforzo). */
    fun toneDb(percent: Int): Double = percent.coerceIn(0, 100) / 10.0

    /**
     * Elenco fisso di stadi: [bassi, banda 0..N-1, alti].
     * Gli stadi neutri valgono IDENTITY, così la struttura non cambia mentre si regola.
     */
    fun stages(cfg: EqConfig, fs: Double): List<Coef> {
        val nyquistLimit = fs * 0.45
        val out = ArrayList<Coef>(cfg.bandCount + 2)
        out.add(
            if (cfg.bassFreq < nyquistLimit)
                lowShelf(fs, cfg.bassFreq.toDouble(), toneDb(cfg.bassPercent)) else IDENTITY
        )
        for (i in 0 until cfg.bandCount) {
            val f = cfg.freqs[i].toDouble()
            out.add(
                if (cfg.enabled && f < nyquistLimit)
                    when (cfg.typeAt(i)) {
                        0 -> lowShelf(fs, f, cfg.gains[i].toDouble())
                        1 -> highShelf(fs, f, cfg.gains[i].toDouble())
                        else -> peaking(fs, f, cfg.qFor(i), cfg.gains[i].toDouble())
                    } else IDENTITY
            )
        }
        out.add(
            if (cfg.trebleFreq < nyquistLimit)
                highShelf(fs, cfg.trebleFreq.toDouble(), toneDb(cfg.treblePercent)) else IDENTITY
        )
        return out
    }

    fun magnitudeDb(c: Coef, fs: Double, f: Double): Double {
        val w = 2.0 * PI * f / fs
        val cw = cos(w)
        val sw = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val nr = c.b0 + c.b1 * cw + c.b2 * c2
        val ni = -(c.b1 * sw + c.b2 * s2)
        val dr = 1.0 + c.a1 * cw + c.a2 * c2
        val di = -(c.a1 * sw + c.a2 * s2)
        val num = nr * nr + ni * ni
        val den = dr * dr + di * di
        if (den <= 0.0 || num <= 0.0) return 0.0
        return 10.0 * log10(num / den)
    }
}
