package com.marco.mleq.audio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/** Tipi di filtro dell'equalizzatore parametrico (stesso ordine del motore nativo). */
enum class FilterType(val label: String, val short: String, val usesGain: Boolean) {
    Peak("Campana", "PK", true),
    LowShelf("Shelf bassi", "LS", true),
    HighShelf("Shelf alti", "HS", true),
    LowPass("Passa-basso", "LP", false),
    HighPass("Passa-alto", "HP", false),
    BandPass("Passa-banda", "BP", false),
    Notch("Notch", "NO", false),
    AllPass("Passa-tutto", "AP", false),
}

/** Banda dell'equalizzatore parametrico. */
data class PeqBand(
    val on: Boolean = false,
    val type: FilterType = FilterType.Peak,
    val freq: Float = 1000f,
    val gain: Float = 0f,
    val q: Float = 1f,
) {
    fun clamped() = copy(
        freq = freq.coerceIn(DspSettings.MIN_FREQ, DspSettings.MAX_FREQ),
        gain = gain.coerceIn(-DspSettings.PEQ_GAIN, DspSettings.PEQ_GAIN),
        q = q.coerceIn(DspSettings.MIN_Q, DspSettings.MAX_Q),
    )
}

/** Modalità di un modulo a scelta multipla. */
object Modes {
    const val EQ_OFF = 0
    const val EQ_GRAPHIC = 1
    const val EQ_PARAMETRIC = 2

    val surround = listOf("Spento", "Tradizionale", "Palco frontale", "Ultra Wide")
    val dbfb = listOf("Spento", "Normale", "Alto")
    val replayGain = listOf("Spento", "Brano", "Album")
}

/**
 * Tutte le impostazioni del motore DSP. Il motore nativo le riceve come testo "chiave=valore"
 * ([toNative]); qui stanno solo i valori, la matematica è tutta nel motore.
 */
data class DspSettings(
    /** Spento = audio intatto (bit-exact). */
    val master: Boolean = false,
    val preampDb: Float = 0f,
    val autoHeadroom: Boolean = true,

    // Equalizzatore
    val eqMode: Int = Modes.EQ_GRAPHIC,
    val graphic: List<Float> = List(GRAPHIC_BANDS) { 0f },
    val faithful: Boolean = true,
    val parametric: List<PeqBand> = defaultParametric(),

    // Toni (come Poweramp: shelf con frequenza e Q)
    val tone: Boolean = true,
    val bassDb: Float = 0f,
    val bassFreq: Float = 90f,
    val bassQ: Float = 0.8f,
    val trebleDb: Float = 0f,
    val trebleFreq: Float = 10000f,
    val trebleQ: Float = 0.6f,

    // Moduli
    val analogBass: Boolean = false,
    val analogDrive: Float = 0.4f,
    val analogWarmth: Float = 0.5f,
    val analogFreq: Float = 60f,
    val air: Boolean = false,
    val airAmount: Float = 0.5f,
    /** Scheda "Surround+ e stereo" accesa. */
    val stereo: Boolean = false,
    val surroundMode: Int = 0,
    val balance: Float = 0f,
    val expand: Float = 0f,
    val mono: Boolean = false,
    val crossfeed: Float = 0f,
    val dbfbMode: Int = 0,
    val loudness: Boolean = false,
    val loudnessAmount: Float = 0.7f,

    // Livello
    val limiter: Boolean = true,
    val limiterThresholdDb: Float = -0.5f,
    val limiterReleaseMs: Float = 80f,

    /** ReplayGain: 0 = spento, 1 = brano, 2 = album (vedi [ReplayGain]). */
    val replayGain: Int = ReplayGain.OFF,
) {
    /** Testo per il motore nativo. [rgDb] ReplayGain del brano, [volume] volume del telefono 0..1 lineare. */
    fun toNative(rgDb: Double = 0.0, volume: Double = 1.0): String {
        val sb = StringBuilder(1024)
        fun put(k: String, v: Any) { sb.append(k).append('=').append(v).append(';') }
        fun f(v: Float) = String.format(Locale.US, "%.4f", v)
        fun b(v: Boolean) = if (v) 1 else 0
        put("master", b(master)); put("pre", f(preampDb)); put("rg", String.format(Locale.US, "%.3f", rgDb))
        put("headroom", b(autoHeadroom)); put("eq", eqMode); put("geq.faithful", b(faithful))
        for (i in 0 until GRAPHIC_BANDS) put("geq.$i", f(graphic.getOrElse(i) { 0f }))
        for (i in 0 until PARAM_BANDS) {
            val p = parametric.getOrElse(i) { PeqBand() }
            put("peq.$i", "${b(p.on)},${p.type.ordinal},${f(p.freq)},${f(p.gain)},${f(p.q)}")
        }
        put("bass", f(if (tone) bassDb else 0f)); put("bass.f", f(bassFreq)); put("bass.q", f(bassQ))
        put("treble", f(if (tone) trebleDb else 0f)); put("treble.f", f(trebleFreq)); put("treble.q", f(trebleQ))
        put("abass", b(analogBass)); put("abass.drive", f(analogDrive)); put("abass.warmth", f(analogWarmth)); put("abass.f", f(analogFreq))
        put("air", b(air)); put("air.amount", f(airAmount))
        put("surround", if (stereo) surroundMode else 0); put("stereo", b(stereo))
        put("balance", f(balance)); put("expand", f(expand)); put("mono", b(mono)); put("crossfeed", f(crossfeed))
        put("dbfb", dbfbMode)
        put("loud", b(loudness)); put("loud.amount", f(loudnessAmount)); put("volume", String.format(Locale.US, "%.4f", volume))
        put("lim", b(limiter)); put("lim.thr", f(limiterThresholdDb)); put("lim.rel", f(limiterReleaseMs))
        return sb.toString()
    }

    fun withGraphic(i: Int, db: Float): DspSettings {
        if (i !in 0 until GRAPHIC_BANDS) return this
        val g = graphic.toMutableList()
        while (g.size < GRAPHIC_BANDS) g.add(0f)
        g[i] = db.coerceIn(-GRAPHIC_GAIN, GRAPHIC_GAIN)
        return copy(graphic = g)
    }

    fun withBand(i: Int, band: PeqBand): DspSettings {
        if (i !in 0 until PARAM_BANDS) return this
        val l = parametric.toMutableList()
        while (l.size < PARAM_BANDS) l.add(PeqBand())
        l[i] = band.clamped()
        return copy(parametric = l)
    }

    /** Parte "equalizzatore" (per i preset): modalità, curve e preamp. */
    fun eqPart(): EqPart = EqPart(eqMode, graphic, faithful, parametric, preampDb)

    fun withEqPart(p: EqPart): DspSettings =
        copy(eqMode = p.mode, graphic = p.graphic, faithful = p.faithful, parametric = p.parametric, preampDb = p.preampDb, master = true)

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", 2)
        o.put("master", master); o.put("pre", preampDb.toDouble()); o.put("headroom", autoHeadroom)
        o.put("eqMode", eqMode); o.put("faithful", faithful)
        o.put("graphic", JSONArray(graphic.map { it.toDouble() }))
        o.put("parametric", bandsToJson(parametric))
        o.put("tone", tone); o.put("bass", bassDb.toDouble()); o.put("bassF", bassFreq.toDouble()); o.put("bassQ", bassQ.toDouble())
        o.put("treble", trebleDb.toDouble()); o.put("trebleF", trebleFreq.toDouble()); o.put("trebleQ", trebleQ.toDouble())
        o.put("abass", analogBass); o.put("abassDrive", analogDrive.toDouble()); o.put("abassWarmth", analogWarmth.toDouble()); o.put("abassF", analogFreq.toDouble())
        o.put("air", air); o.put("airAmount", airAmount.toDouble())
        o.put("stereo", stereo); o.put("surround", surroundMode)
        o.put("balance", balance.toDouble()); o.put("expand", expand.toDouble()); o.put("mono", mono); o.put("crossfeed", crossfeed.toDouble())
        o.put("dbfb", dbfbMode)
        o.put("loud", loudness); o.put("loudAmount", loudnessAmount.toDouble())
        o.put("lim", limiter); o.put("limThr", limiterThresholdDb.toDouble()); o.put("limRel", limiterReleaseMs.toDouble())
        o.put("rg", replayGain)
        return o
    }

    companion object {
        const val GRAPHIC_BANDS = 15
        const val PARAM_BANDS = 16
        const val GRAPHIC_GAIN = 12f
        const val PEQ_GAIN = 15f
        const val MIN_FREQ = 20f
        const val MAX_FREQ = 20000f
        const val MIN_Q = 0.1f
        const val MAX_Q = 18f
        const val TONE_MAX_DB = 10f

        /** Centri ISO del grafico a 15 bande (uguali al motore nativo). */
        val GRAPHIC_FREQS = listOf(25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f)

        fun defaultParametric(): List<PeqBand> {
            val f = listOf(32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f, 100f, 300f, 800f, 3000f, 6000f, 12000f)
            return List(PARAM_BANDS) { i -> PeqBand(on = i < 10, freq = f[i], q = 1.41f) }
        }

        fun bandsToJson(l: List<PeqBand>): JSONArray {
            val a = JSONArray()
            for (b in l) a.put(JSONObject().put("on", b.on).put("type", b.type.ordinal).put("f", b.freq.toDouble()).put("g", b.gain.toDouble()).put("q", b.q.toDouble()))
            return a
        }

        fun bandsFromJson(a: JSONArray?): List<PeqBand> {
            val def = defaultParametric()
            if (a == null) return def
            return List(PARAM_BANDS) { i ->
                val o = a.optJSONObject(i) ?: return@List def[i]
                PeqBand(
                    on = o.optBoolean("on", false),
                    type = FilterType.entries.getOrElse(o.optInt("type", 0)) { FilterType.Peak },
                    freq = o.optDouble("f", def[i].freq.toDouble()).toFloat(),
                    gain = o.optDouble("g", 0.0).toFloat(),
                    q = o.optDouble("q", 1.0).toFloat(),
                ).clamped()
            }
        }

        private fun JSONObject.f(k: String, d: Float, lo: Float, hi: Float): Float {
            val v = optDouble(k, d.toDouble())
            return if (v.isFinite()) v.toFloat().coerceIn(lo, hi) else d
        }

        /** Legge le impostazioni; riconosce anche il formato della versione precedente dell'app. */
        fun fromJson(s: String?): DspSettings {
            if (s.isNullOrBlank()) return DspSettings()
            return try {
                val o = JSONObject(s)
                if (o.optInt("v", 1) < 2) migrateV1(o) else parseV2(o)
            } catch (_: Exception) {
                DspSettings()
            }
        }

        private fun parseV2(o: JSONObject): DspSettings {
            val ga = o.optJSONArray("graphic")
            val d = DspSettings()
            return DspSettings(
                master = o.optBoolean("master", false),
                preampDb = o.f("pre", 0f, -24f, 24f),
                autoHeadroom = o.optBoolean("headroom", true),
                eqMode = o.optInt("eqMode", Modes.EQ_GRAPHIC).coerceIn(0, 2),
                graphic = List(GRAPHIC_BANDS) { i -> (ga?.optDouble(i, 0.0) ?: 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-GRAPHIC_GAIN, GRAPHIC_GAIN) ?: 0f },
                faithful = o.optBoolean("faithful", true),
                parametric = bandsFromJson(o.optJSONArray("parametric")),
                tone = o.optBoolean("tone", true),
                bassDb = o.f("bass", 0f, 0f, TONE_MAX_DB), bassFreq = o.f("bassF", d.bassFreq, 20f, 500f), bassQ = o.f("bassQ", d.bassQ, 0.1f, 2f),
                trebleDb = o.f("treble", 0f, 0f, TONE_MAX_DB), trebleFreq = o.f("trebleF", d.trebleFreq, 1000f, 20000f), trebleQ = o.f("trebleQ", d.trebleQ, 0.1f, 2f),
                analogBass = o.optBoolean("abass", false), analogDrive = o.f("abassDrive", d.analogDrive, 0f, 1f),
                analogWarmth = o.f("abassWarmth", d.analogWarmth, 0f, 1f), analogFreq = o.f("abassF", d.analogFreq, 20f, 120f),
                air = o.optBoolean("air", false), airAmount = o.f("airAmount", d.airAmount, 0f, 1f),
                stereo = if (o.has("stereo")) o.optBoolean("stereo", false) else
                    o.optInt("surround", 0) > 0 || o.optDouble("balance", 0.0) != 0.0 || o.optDouble("expand", 0.0) > 0.0 ||
                        o.optDouble("crossfeed", 0.0) > 0.0 || o.optBoolean("mono", false),
                surroundMode = o.optInt("surround", 0).coerceIn(0, 3),
                balance = o.f("balance", 0f, -1f, 1f), expand = o.f("expand", 0f, 0f, 1f), mono = o.optBoolean("mono", false),
                crossfeed = o.f("crossfeed", 0f, 0f, 1f), dbfbMode = o.optInt("dbfb", 0).coerceIn(0, 2),
                loudness = o.optBoolean("loud", false), loudnessAmount = o.f("loudAmount", d.loudnessAmount, 0f, 1f),
                limiter = o.optBoolean("lim", true), limiterThresholdDb = o.f("limThr", d.limiterThresholdDb, -12f, 0f),
                limiterReleaseMs = o.f("limRel", d.limiterReleaseMs, 5f, 1000f),
                replayGain = o.optInt("rg", 0).coerceIn(0, 2),
            )
        }

        /**
         * Vecchio formato (EqConfig): le bande diventano l'equalizzatore parametrico (fino a 16),
         * manopole, stereo, limiter e ReplayGain restano quelli scelti.
         */
        private fun migrateV1(o: JSONObject): DspSettings {
            val d = DspSettings()
            val n = o.optInt("bandCount", 10)
            val fa = o.optJSONArray("freqs"); val ga = o.optJSONArray("gains"); val qa = o.optJSONArray("qs"); val ta = o.optJSONArray("types")
            val bands = ArrayList<PeqBand>()
            if (fa != null && ga != null && fa.length() == n && ga.length() == n) {
                for (i in 0 until minOf(n, PARAM_BANDS)) {
                    val type = when (ta?.optInt(i, 2) ?: 2) { 0 -> FilterType.LowShelf; 1 -> FilterType.HighShelf; else -> FilterType.Peak }
                    bands.add(PeqBand(true, type, fa.getDouble(i).toFloat(), ga.getDouble(i).toFloat(), (qa?.optDouble(i, 1.0) ?: 1.0).toFloat()).clamped())
                }
            }
            while (bands.size < PARAM_BANDS) bands.add(d.parametric[bands.size].copy(on = false))
            val tv = o.optInt("toneV", 1)
            fun tone(key: String): Float {
                val raw = o.optInt(key, if (tv == 2) 50 else 0).coerceIn(0, 100)
                val db = when (tv) { 1 -> raw / 100.0 * 12.0; 2 -> (raw - 50) / 50.0 * 12.0; else -> raw / 10.0 }
                return db.toFloat().coerceIn(0f, TONE_MAX_DB)
            }
            return d.copy(
                master = true,
                eqMode = if (o.optBoolean("enabled", false)) Modes.EQ_PARAMETRIC else Modes.EQ_OFF,
                parametric = bands,
                preampDb = o.optDouble("preamp", 0.0).toFloat().coerceIn(-24f, 24f),
                bassDb = tone("bass"), trebleDb = tone("treble"),
                limiter = o.optBoolean("limiter", true),
                limiterThresholdDb = o.optDouble("limThr", -0.5).toFloat().coerceIn(-12f, 0f),
                limiterReleaseMs = o.optDouble("limRel", 80.0).toFloat().coerceIn(5f, 1000f),
                stereo = o.optInt("balance", 0) != 0 || o.optInt("expand", 0) > 0 || o.optBoolean("mono", false),
                balance = (o.optInt("balance", 0) / 100f).coerceIn(-1f, 1f),
                expand = (o.optInt("expand", 0) / 100f).coerceIn(0f, 1f),
                mono = o.optBoolean("mono", false),
                replayGain = o.optInt("replayGain", 0).coerceIn(0, 2),
            )
        }

        /** Larghezza in ottave di una campana con questo Q (per mostrarla accanto al valore). */
        fun octaves(q: Float): Double = 2.0 * ln(0.5 / q + kotlin.math.sqrt(0.25 / (q * q) + 1.0)) / ln(2.0)

        /** Posizione 0..1 su scala logaritmica 20 Hz - 20 kHz. */
        fun freqToPos(f: Float): Float = (ln(f / MIN_FREQ) / ln(MAX_FREQ / MIN_FREQ)).coerceIn(0f, 1f)
        fun posToFreq(p: Float): Float = MIN_FREQ * (MAX_FREQ / MIN_FREQ).pow(p.coerceIn(0f, 1f))
    }
}

/** Parte dell'equalizzatore salvata nei preset. */
data class EqPart(
    val mode: Int,
    val graphic: List<Float>,
    val faithful: Boolean,
    val parametric: List<PeqBand>,
    val preampDb: Float,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode).put("graphic", JSONArray(graphic.map { it.toDouble() })).put("faithful", faithful)
        .put("parametric", DspSettings.bandsToJson(parametric)).put("pre", preampDb.toDouble())

    companion object {
        fun fromJson(o: JSONObject): EqPart {
            val ga = o.optJSONArray("graphic")
            return EqPart(
                mode = o.optInt("mode", Modes.EQ_GRAPHIC).coerceIn(0, 2),
                graphic = List(DspSettings.GRAPHIC_BANDS) { i -> (ga?.optDouble(i, 0.0) ?: 0.0).toFloat().coerceIn(-DspSettings.GRAPHIC_GAIN, DspSettings.GRAPHIC_GAIN) },
                faithful = o.optBoolean("faithful", true),
                parametric = DspSettings.bandsFromJson(o.optJSONArray("parametric")),
                preampDb = o.optDouble("pre", 0.0).toFloat().coerceIn(-24f, 24f),
            )
        }
    }
}

/** Preset di fabbrica dell'EQ grafico (curve in dB sulle 15 bande ISO). */
object GraphicPresets {
    class Preset(val name: String, val gains: List<Float>)

    val all = listOf(
        Preset("Piatto", List(15) { 0f }),
        Preset("Bassi", listOf(6f, 6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("Alti", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3.5f, 4.5f, 5f, 5f)),
        Preset("Voce", listOf(-3f, -3f, -2f, -1f, 0f, 1f, 2f, 3f, 3.5f, 3f, 2f, 1f, 0f, -1f, -1f)),
        Preset("Rock", listOf(4f, 4f, 3f, 1.5f, 0f, -1f, -1.5f, -1f, 0f, 1f, 2.5f, 3.5f, 4f, 4f, 3.5f)),
        Preset("Pop", listOf(-1f, 0f, 1f, 2.5f, 3.5f, 3.5f, 2f, 0.5f, -0.5f, -0.5f, 0f, 1f, 1.5f, 2f, 2f)),
        Preset("Jazz", listOf(3f, 3f, 2f, 1.5f, 0f, -1f, -1f, 0f, 1f, 1.5f, 2f, 2.5f, 3f, 3f, 3f)),
        Preset("Classica", listOf(3.5f, 3f, 2.5f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3f, 3.5f)),
        Preset("Elettronica", listOf(5f, 5f, 4f, 2.5f, 0.5f, -1f, -1f, 0f, 0.5f, 1.5f, 2.5f, 3f, 3.5f, 4f, 4f)),
        Preset("Loudness", listOf(6f, 5.5f, 4.5f, 3f, 1f, 0f, -0.5f, -0.5f, 0f, 0.5f, 1.5f, 3f, 4f, 4.5f, 5f)),
        Preset("Podcast", listOf(-6f, -5f, -3f, -1f, 0f, 1f, 2f, 3f, 3f, 2.5f, 2f, 1f, 0f, -2f, -4f)),
    )
}
