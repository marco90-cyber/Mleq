package com.marco.mleq.audio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
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
    /**
     * "Curva fedele": i cursori sono i valori che si vogliono ottenere sulla curva totale; i
     * guadagni reali dei filtri vengono ricalcolati per compensare la sovrapposizione delle
     * campane vicine (vedi [EqMath.fitGains]).
     */
    val faithful: Boolean = false,
    /**
     * Headroom delle manopole: abbassa il livello di quanto rinforzano Bassi/Alti, così il
     * rinforzo non finisce nel limiter (anche con l'equalizzatore spento).
     */
    val toneHeadroom: Boolean = true,
    /** Bilanciamento -100 (tutto a sinistra) .. +100 (tutto a destra). */
    val balance: Int = 0,
    /** Espansione stereo in %: 0 = mono, 100 = invariata, 200 = doppia. */
    val stereoWidth: Int = 100,
    /** Uscita mono (somma dei canali). */
    val mono: Boolean = false,
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

    /** Guadagni realmente applicati ai filtri (con "curva fedele" sono ricalcolati). */
    fun effectiveGains(fs: Double): List<Float> =
        if (faithful && enabled && bandCount > 1) EqMath.fitGains(this, fs) else gains

    /** Tipo di filtro di una banda: 0 = low shelf, 1 = high shelf, 2 = peaking. */
    fun withType(index: Int, type: Int): EqConfig {
        if (index !in 0 until bandCount) return this
        val list = MutableList(bandCount) { typeAt(it) }
        list[index] = type.coerceIn(0, 2)
        return copy(types = if (list.all { it == 2 }) emptyList() else list)
    }

    /** Preamp scelto a mano. */
    fun withPreamp(db: Float): EqConfig = copy(preampDb = db.coerceIn(-GAIN_RANGE, GAIN_RANGE))

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

    /** Picco del solo rinforzo delle manopole (dB, >= 0). */
    fun tonePeakDb(fs: Double = 48000.0): Double {
        if (bassPercent == 0 && treblePercent == 0) return 0.0
        return EqMath.cachedTonePeak(this, fs) { computeTonePeak(fs) }
    }

    private fun computeTonePeak(fs: Double): Double {
        var peak = 0.0
        for (i in 0 until 256) {
            val f = 20.0 * (20000.0 / 20.0).pow(i / 255.0)
            val t = toneResponseDb(f, fs)
            if (t > peak) peak = t
        }
        return peak
    }

    /**
     * Preamp effettivo in dB: preamp scelto (solo con EQ acceso) meno l'headroom.
     * Con auto-headroom e EQ acceso si compensa tutto il rinforzo (bande + manopole);
     * altrimenti, se attivo, solo quello delle manopole.
     */
    fun effectivePreampDb(fs: Double): Double = EqMath.cachedPreamp(this, fs) {
        val manual = if (enabled) preampDb.toDouble() else 0.0
        val head = when {
            enabled && autoHeadroom -> boostPeakDb(fs)
            toneHeadroom -> tonePeakDb(fs)
            else -> 0.0
        }
        manual - head - (if (toneHeadroom) widthHeadroomDb() else 0.0)
    }

    /** True se serve elaborare l'audio (altrimenti bypass reale). */
    fun dspActive(): Boolean =
        enabled || bassPercent != 0 || treblePercent != 0 || stereoActive()

    /** True se l'immagine stereo è diversa da quella originale. */
    fun stereoActive(): Boolean = mono || balance != 0 || stereoWidth != 100

    /** Larghezza effettiva per il DSP (0..2). */
    fun widthFactor(): Float = if (mono) 0f else stereoWidth.coerceIn(0, 200) / 100f

    /** Bilanciamento per il DSP (-1..1). */
    fun balanceFactor(): Float = balance.coerceIn(-100, 100) / 100f

    /**
     * Allargare lo stereo rinforza il canale laterale: per un suono tutto su un lato il picco
     * cresce di (1 + w) / 2. Con l'headroom attivo il livello scende di altrettanto.
     */
    fun widthHeadroomDb(): Double {
        val w = widthFactor().toDouble()
        return if (w > 1.0) 20.0 * log10((1.0 + w) / 2.0) else 0.0
    }

    /** Risposta in dB a una certa frequenza (per il grafico). */
    fun responseDb(f: Double, fs: Double = 48000.0, includeTone: Boolean = true): Double {
        var total = 0.0
        for (c in EqMath.stages(this, fs)) total += EqMath.magnitudeDb(c, fs, f)
        total += effectivePreampDb(fs)
        if (!includeTone) {
            total -= toneResponseDb(f, fs)
            // la curva dell'EQ non deve "scendere" quando si girano le manopole
            if (!(enabled && autoHeadroom) && toneHeadroom) total += tonePeakDb(fs)
            if (toneHeadroom) total += widthHeadroomDb()
        }
        return total
    }

    /** Solo il contributo delle manopole Bassi/Alti (il Tono è separato dall'equalizzatore). */
    fun toneResponseDb(f: Double, fs: Double = 48000.0): Double {
        var t = 0.0
        if (bassPercent != 0) for (c in EqMath.bassStages(this, fs)) t += EqMath.magnitudeDb(c, fs, f)
        if (treblePercent != 0) for (c in EqMath.trebleStages(this, fs)) t += EqMath.magnitudeDb(c, fs, f)
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
        o.put("faithful", faithful)
        o.put("toneHeadroom", toneHeadroom)
        o.put("balance", balance)
        o.put("width", stereoWidth)
        o.put("mono", mono)
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
                    faithful = o.optBoolean("faithful", false),
                    toneHeadroom = o.optBoolean("toneHeadroom", true),
                    balance = o.optInt("balance", 0).coerceIn(-100, 100),
                    stereoWidth = o.optInt("width", 100).coerceIn(0, 200),
                    mono = o.optBoolean("mono", false),
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

/** Matematica dei filtri biquad (Audio EQ Cookbook + variante matched, vedi sotto). */
object EqMath {
    /** Coefficienti normalizzati: b0, b1, b2, a1, a2. */
    class Coef(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    val IDENTITY = Coef(1.0, 0.0, 0.0, 0.0, 0.0)

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Coef(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    @Volatile
    private var preampCache: Triple<EqConfig, Double, Double>? = null

    /** Il calcolo dell'headroom costa centinaia di risposte: il grafico lo chiede per ogni punto. */
    fun cachedPreamp(cfg: EqConfig, fs: Double, compute: () -> Double): Double {
        preampCache?.let { if (it.second == fs && it.first == cfg) return it.third }
        val v = compute()
        preampCache = Triple(cfg, fs, v)
        return v
    }

    @Volatile
    private var toneCache: Triple<List<Any>, Double, Double>? = null

    fun cachedTonePeak(cfg: EqConfig, fs: Double, compute: () -> Double): Double {
        // dipende solo dalle manopole
        val key = listOf<Any>(cfg.bassPercent, cfg.treblePercent, cfg.bassFreq, cfg.trebleFreq)
        toneCache?.let { if (it.first == key && it.second == fs) return it.third }
        val v = compute()
        toneCache = Triple(key, fs, v)
        return v
    }

    private fun bandCoef(type: Int, fs: Double, f: Double, q: Double, g: Double): Coef = when (type) {
        0 -> lowShelf(fs, f, g)
        1 -> highShelf(fs, f, g)
        else -> peaking(fs, f, q, g)
    }

    private data class FitKey(
        val fs: Double, val freqs: List<Float>, val qs: List<Float>, val types: List<Int>, val targets: List<Float>,
    )

    @Volatile
    private var fitCache: Pair<FitKey, List<Float>>? = null

    /** Risolve A x = b (eliminazione di Gauss con pivot parziale). Null se singolare. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (kotlin.math.abs(a[r][col]) > kotlin.math.abs(a[piv][col])) piv = r
            if (kotlin.math.abs(a[piv][col]) < 1e-12) return null
            if (piv != col) {
                val t = a[piv]; a[piv] = a[col]; a[col] = t
                val tb = b[piv]; b[piv] = b[col]; b[col] = tb
            }
            for (r in col + 1 until n) {
                val k = a[r][col] / a[col][col]
                if (k == 0.0) continue
                for (c in col until n) a[r][c] -= k * a[col][c]
                b[r] -= k * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var sum = b[r]
            for (c in r + 1 until n) sum -= a[r][c] * x[c]
            x[r] = sum / a[r][r]
        }
        return x
    }

    /**
     * "Curva fedele": trova i guadagni dei filtri per cui la curva TOTALE delle bande (senza
     * manopole né preamp) passa esattamente dai valori dei cursori alle frequenze delle bande.
     * Le campane vicine si sommano, quindi senza compensazione due bande a +6 dB danno più di
     * +6 dB fra l'una e l'altra. Metodo: Newton con jacobiano numerico, passo smorzato (ridge) per
     * non esplodere quando le bande sono molto sovrapposte; i guadagni restano entro +-GAIN_RANGE.
     */
    fun fitGains(cfg: EqConfig, fs: Double): List<Float> {
        val key = FitKey(fs, cfg.freqs, cfg.qs, cfg.types, cfg.gains)
        fitCache?.let { if (it.first == key) return it.second }
        val n = cfg.bandCount
        val limit = fs * 0.45
        val f = DoubleArray(n) { cfg.freqs[it].toDouble() }
        val active = BooleanArray(n) { f[it] < limit }
        val target = DoubleArray(n) { cfg.gains[it].toDouble() }
        val g = target.copyOf()
        val range = EqConfig.GAIN_RANGE.toDouble()
        val h = 0.25
        for (iter in 0 until 12) {
            val coefs = Array(n) { j -> if (active[j]) bandCoef(cfg.typeAt(j), fs, f[j], cfg.qFor(j), g[j]) else IDENTITY }
            val r = DoubleArray(n)
            var worst = 0.0
            for (i in 0 until n) {
                if (!active[i]) continue
                var tot = 0.0
                for (j in 0 until n) tot += magnitudeDb(coefs[j], fs, f[i])
                r[i] = target[i] - tot
                worst = maxOf(worst, kotlin.math.abs(r[i]))
            }
            if (worst < 0.005) break
            val jac = Array(n) { DoubleArray(n) }
            for (j in 0 until n) {
                if (!active[j]) continue
                val cp = bandCoef(cfg.typeAt(j), fs, f[j], cfg.qFor(j), g[j] + h)
                val cm = bandCoef(cfg.typeAt(j), fs, f[j], cfg.qFor(j), g[j] - h)
                for (i in 0 until n) if (active[i]) jac[i][j] = (magnitudeDb(cp, fs, f[i]) - magnitudeDb(cm, fs, f[i])) / (2 * h)
            }
            // equazioni normali con ridge: (J'J + lambda I) d = J' r
            val a = Array(n) { DoubleArray(n) }
            val b = DoubleArray(n)
            for (p in 0 until n) {
                for (q in 0 until n) {
                    var sum = 0.0
                    for (i in 0 until n) sum += jac[i][p] * jac[i][q]
                    a[p][q] = sum
                }
                a[p][p] += 1e-3
                var sb = 0.0
                for (i in 0 until n) sb += jac[i][p] * r[i]
                b[p] = sb
            }
            val d = solve(a, b) ?: break
            for (j in 0 until n) {
                if (!active[j]) continue
                g[j] = (g[j] + d[j].coerceIn(-6.0, 6.0)).coerceIn(-range, range)
            }
        }
        val result = List(n) { g[it].toFloat() }
        fitCache = key to result
        return result
    }

    // ---- Progetto dei filtri
    //
    // Per ogni filtro si calcolano due candidati: la forma classica dell'Audio EQ Cookbook (RBJ) e
    // una forma "matched" (poli dal matched-z, zeri imposti con tre vincoli di modulo a DC, f0 e
    // Nyquist, come nella letteratura sui filtri digitali che imitano il prototipo analogico).
    // Vince il candidato con l'errore massimo minore rispetto alla curva analogica ideale
    // (20 Hz .. 0,45*fs). Così non peggiora mai: vicino a Nyquist il RBJ "si schiaccia" fino a 8 dB.

    private const val ERR_POINTS = 48

    /** Modulo del prototipo analogico a f/f0 = x per il rinforzo A (A<1 = taglio). */
    private fun analogPeaking(a: Double, q: Double, x: Double): Double {
        val re = 1.0 - x * x
        val n = hypot(re, x * a / q)
        val d = hypot(re, x / (a * q))
        return n / d
    }

    private fun analogShelf(a: Double, invQ: Double, low: Boolean, x: Double): Double {
        val rq = sqrt(a) * invQ * x
        val x2 = x * x
        return if (low) a * hypot(a - x2, rq) / hypot(1.0 - a * x2, rq)
        else a * hypot(1.0 - a * x2, rq) / hypot(a - x2, rq)
    }

    private fun maxErrDb(c: Coef, fs: Double, f0: Double, analog: (Double) -> Double): Double {
        var worst = 0.0
        val top = fs * 0.45
        for (i in 0 until ERR_POINTS) {
            val f = 20.0 * (top / 20.0).pow(i.toDouble() / (ERR_POINTS - 1))
            val ref = 20.0 * log10(analog(f / f0))
            val d = magnitudeDb(c, fs, f)
            worst = maxOf(worst, kotlin.math.abs(d - ref))
        }
        return worst
    }

    private fun isStable(c: Coef): Boolean =
        c.b0.isFinite() && c.b1.isFinite() && c.b2.isFinite() && c.a1.isFinite() && c.a2.isFinite() &&
            kotlin.math.abs(c.a2) < 1.0 && kotlin.math.abs(c.a1) < 1.0 + c.a2

    private fun invert(c: Coef) = Coef(1.0 / c.b0, c.a1 / c.b0, c.a2 / c.b0, c.b1 / c.b0, c.b2 / c.b0)

    /** Filtro "matched" per un rinforzo (A >= 1). Null se non realizzabile. */
    private fun matched(fs: Double, f0: Double, zeta: Double, wpRatio: Double, analog: (Double) -> Double): Coef? {
        val w0 = 2.0 * PI * f0 / fs
        val th = wpRatio * w0
        val e = exp(-zeta * th)
        val a1 = if (zeta < 1.0) -2.0 * e * cos(sqrt(1.0 - zeta * zeta) * th)
        else -2.0 * e * cosh(sqrt(zeta * zeta - 1.0) * th)
        val a2 = e * e
        val ca0 = (1.0 + a1 + a2).let { it * it }
        val ca1 = (1.0 - a1 + a2).let { it * it }
        val ca2 = -4.0 * a2
        fun phi1(w: Double) = sin(w / 2.0).let { it * it }
        val pc1 = phi1(w0)
        val pc0 = 1.0 - pc1
        val pc2 = 4.0 * pc0 * pc1
        val aw0 = ca0 * pc0 + ca1 * pc1 + ca2 * pc2
        val m0 = analog(0.0).let { it * it }
        val mc = analog(1.0).let { it * it }
        val mn = analog(PI / w0).let { it * it }
        val bb0 = ca0 * m0
        val bb1 = ca1 * mn
        val bb2 = (aw0 * mc - bb0 * pc0 - bb1 * pc1) / pc2
        if (!(bb0 > 0.0 && bb1 > 0.0)) return null
        val s0 = sqrt(bb0)
        val s1 = sqrt(bb1)
        val w = 0.5 * (s0 + s1)
        val disc = w * w + bb2
        if (!(disc >= 0.0)) return null
        val b0 = 0.5 * (w + sqrt(disc))
        val c = Coef(b0, 0.5 * (s0 - s1), w - b0, a1, a2)
        return if (isStable(c)) c else null
    }

    private fun pickBest(fs: Double, f0: Double, rbj: Coef, m: Coef?, analog: (Double) -> Double): Coef {
        if (m == null) return rbj
        return if (maxErrDb(m, fs, f0, analog) < maxErrDb(rbj, fs, f0, analog) - 1e-6) m else rbj
    }

    private fun rbjPeaking(fs: Double, f: Double, q: Double, gainDb: Double): Coef {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / fs
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        return norm(
            1.0 + alpha * a, -2.0 * c, 1.0 - alpha * a,
            1.0 + alpha / a, -2.0 * c, 1.0 - alpha / a
        )
    }

    fun peaking(fs: Double, f: Double, q: Double, gainDb: Double): Coef {
        if (kotlin.math.abs(gainDb) < 0.01) return IDENTITY
        val rbj = rbjPeaking(fs, f, q, gainDb)
        val a = 10.0.pow(gainDb / 40.0)
        val g = kotlin.math.abs(gainDb)
        val ab = 10.0.pow(g / 40.0)
        val boost = matched(fs, f, 1.0 / (2.0 * ab * q), 1.0) { x -> analogPeaking(ab, q, x) }
        val m = if (gainDb < 0.0) boost?.let { invert(it) } else boost
        return pickBest(fs, f, rbj, m) { x -> analogPeaking(a, q, x) }
    }

    private fun rbjShelf(fs: Double, f: Double, gainDb: Double, slope: Double, low: Boolean): Coef {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / fs
        val c = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        val sa = 2.0 * sqrt(a) * alpha
        return if (low) norm(
            a * ((a + 1) - (a - 1) * c + sa),
            2.0 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa,
            -2.0 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - sa
        ) else norm(
            a * ((a + 1) + (a - 1) * c + sa),
            -2.0 * a * ((a - 1) + (a + 1) * c),
            a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa,
            2.0 * ((a - 1) - (a + 1) * c),
            (a + 1) - (a - 1) * c - sa
        )
    }

    private fun shelf(fs: Double, f: Double, gainDb: Double, slope: Double, low: Boolean): Coef {
        if (kotlin.math.abs(gainDb) < 0.01) return IDENTITY
        val rbj = rbjShelf(fs, f, gainDb, slope, low)
        val a = 10.0.pow(gainDb / 40.0)
        val ab = 10.0.pow(kotlin.math.abs(gainDb) / 40.0)
        val invQ = sqrt((ab + 1.0 / ab) * (1.0 / slope - 1.0) + 2.0)
        val wp = if (low) 1.0 / sqrt(ab) else sqrt(ab)
        val boost = matched(fs, f, invQ / 2.0, wp) { x -> analogShelf(ab, invQ, low, x) }
        val m = if (gainDb < 0.0) boost?.let { invert(it) } else boost
        val invQa = sqrt((a + 1.0 / a) * (1.0 / slope - 1.0) + 2.0)
        return pickBest(fs, f, rbj, m) { x -> analogShelf(a, invQa, low, x) }
    }

    /**
     * [slope] = parametro S dell'Audio EQ Cookbook: 1 = pendenza massima senza sovraelongazione
     * (valore storico), sotto 1 la transizione è più dolce e larga.
     */
    fun lowShelf(fs: Double, f: Double, gainDb: Double, slope: Double = 1.0): Coef =
        shelf(fs, f, gainDb, slope, true)

    fun highShelf(fs: Double, f: Double, gainDb: Double, slope: Double = 1.0): Coef =
        shelf(fs, f, gainDb, slope, false)

    /** Posizione delle manopole -> dB (0..100, 50 = 0 dB, estremi = -12 / +12 dB). */
    /** Manopole Bassi/Alti: 0..100 = 0..+10 dB (solo rinforzo). */
    fun toneDb(percent: Int): Double = percent.coerceIn(0, 100) / 10.0

    /**
     * Stadi delle manopole Bassi: [shelf morbida, "corpo" nei medio-bassi].
     * Gli stadi neutri valgono IDENTITY. Vedi [ToneVoicing] per la voce scelta.
     */
    fun bassStages(cfg: EqConfig, fs: Double): List<Coef> {
        val limit = fs * 0.45
        val db = toneDb(cfg.bassPercent)
        val f = cfg.bassFreq.toDouble()
        val bodyF = ToneVoicing.bassBodyFreq(f)
        return listOf(
            if (f < limit) lowShelf(fs, f, db, ToneVoicing.BASS_SLOPE) else IDENTITY,
            if (bodyF < limit) peaking(fs, bodyF, ToneVoicing.BASS_BODY_Q, db * ToneVoicing.BASS_BODY_RATIO) else IDENTITY,
        )
    }

    /** Stadi delle manopole Alti: [pulizia dei medio-bassi, shelf netta, "aria"]. */
    fun trebleStages(cfg: EqConfig, fs: Double): List<Coef> {
        val limit = fs * 0.45
        val db = toneDb(cfg.treblePercent)
        val f = cfg.trebleFreq.toDouble()
        val airF = ToneVoicing.trebleAirFreq(f)
        return listOf(
            peaking(fs, ToneVoicing.CLEAN_FREQ, ToneVoicing.CLEAN_Q, db * ToneVoicing.TREBLE_CLEAN_RATIO),
            if (f < limit) highShelf(fs, f, db, ToneVoicing.TREBLE_SLOPE) else IDENTITY,
            if (airF < limit) peaking(fs, airF, ToneVoicing.TREBLE_AIR_Q, db * ToneVoicing.TREBLE_AIR_RATIO) else IDENTITY,
        )
    }

    /** Numero di stadi fissi: [bassi x2, banda 0..N-1, alti x3]. */
    fun stageCount(bandCount: Int): Int = bandCount + 5

    /**
     * Elenco fisso di stadi: [bassi x2, banda 0..N-1, alti x3].
     * Gli stadi neutri valgono IDENTITY, così la struttura non cambia mentre si regola.
     */
    fun stages(cfg: EqConfig, fs: Double): List<Coef> {
        val nyquistLimit = fs * 0.45
        val out = ArrayList<Coef>(stageCount(cfg.bandCount))
        out.addAll(bassStages(cfg, fs))
        val gains = cfg.effectiveGains(fs)
        for (i in 0 until cfg.bandCount) {
            val f = cfg.freqs[i].toDouble()
            out.add(
                if (cfg.enabled && f < nyquistLimit) bandCoef(cfg.typeAt(i), fs, f, cfg.qFor(i), gains[i].toDouble())
                else IDENTITY
            )
        }
        out.addAll(trebleStages(cfg, fs))
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

/**
 * Voce delle manopole Bassi e Alti. Ogni manopola non è una semplice shelf: aggiunge piccoli
 * stadi che ne cambiano il carattere. Tutti i valori sono qui, per poterli ritoccare all'orecchio.
 *
 * Bassi "caldi": shelf morbida (pendenza dolce, niente scalino) più una campana larga a 90-220 Hz
 * che dà corpo, circa il 30% del guadagno della shelf.
 *
 * Alti "freddi": shelf netta, una campana d'"aria" sopra i 9 kHz (circa il 30% del guadagno) e un
 * leggero taglio attorno ai 320 Hz (12% del guadagno, al massimo -1,2 dB) che toglie un po' di
 * calore e rende il suono più pulito e brillante.
 */
object ToneVoicing {
    const val BASS_SLOPE = 0.75
    const val BASS_BODY_RATIO = 0.30
    const val BASS_BODY_Q = 0.6

    const val TREBLE_SLOPE = 1.0
    const val TREBLE_AIR_RATIO = 0.30
    const val TREBLE_AIR_Q = 0.8
    const val TREBLE_CLEAN_RATIO = -0.12
    const val CLEAN_FREQ = 320.0
    const val CLEAN_Q = 0.7

    /** Frequenza del "corpo": circa un'ottava sopra la shelf, tra 90 e 220 Hz. */
    fun bassBodyFreq(bassFreq: Double): Double = (bassFreq * 2.0).coerceIn(90.0, 220.0)

    /** Frequenza dell'"aria": circa 1,6 volte la shelf, tra 9 e 16 kHz. */
    fun trebleAirFreq(trebleFreq: Double): Double = (trebleFreq * 1.6).coerceIn(9000.0, 16000.0)
}
