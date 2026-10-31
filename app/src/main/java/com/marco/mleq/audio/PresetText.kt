package com.marco.mleq.audio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * Import/export dei preset nel formato testo di Poweramp / AutoEQ:
 *
 *   Preamp: -6.4 dB
 *   Filter 1: ON LS Fc 105 Hz Gain 6.5 dB Q 0.70
 *   Filter 2: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41
 *
 * I filtri diventano bande dell'equalizzatore parametrico (fino a 16); la riga
 * "GraphicEQ: 20 0; 25 1.5; ..." diventa invece la curva del grafico a 15 bande.
 * Accetta anche il JSON di Poweramp e il separatore decimale con la virgola.
 */
object PresetText {
    private val filterRe = Regex(
        """Filter\s*\d*\s*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+([-\d.,]+)\s*(?:Hz)?(?:\s+Gain\s+([-\d.,]+)\s*(?:dB)?)?(?:\s+Q\s+([-\d.,]+))?""",
        RegexOption.IGNORE_CASE
    )
    private val preampRe = Regex("""Preamp\s*:\s*([-+\d.,]+)\s*(?:dB)?""", RegexOption.IGNORE_CASE)

    private fun num(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()

    private fun typeOf(code: String): FilterType? = when (code.uppercase(Locale.ROOT)) {
        "PK", "PEQ", "PEAK" -> FilterType.Peak
        "LS", "LSC", "LSQ" -> FilterType.LowShelf
        "HS", "HSC", "HSQ" -> FilterType.HighShelf
        "LP", "LPQ" -> FilterType.LowPass
        "HP", "HPQ" -> FilterType.HighPass
        "BP" -> FilterType.BandPass
        "NO", "NOTCH" -> FilterType.Notch
        "AP" -> FilterType.AllPass
        else -> null
    }

    /** Nome + preset. Accetta il JSON di Poweramp (elenco di preset) oppure il testo AutoEQ. */
    fun parseMulti(text: String, fallbackName: String): List<Pair<String, EqPart>> {
        val t = text.trim().removePrefix("﻿")
        if (t.startsWith("[") || t.startsWith("{")) {
            return try {
                val arr = if (t.startsWith("[")) JSONArray(t) else JSONArray().put(JSONObject(t))
                val out = ArrayList<Pair<String, EqPart>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sb = StringBuilder("Preamp: ${o.optDouble("preamp", 0.0)} dB\n")
                    val bands = o.optJSONArray("bands") ?: continue
                    for (k in 0 until bands.length()) {
                        val b = bands.getJSONObject(k)
                        val ty = when (b.optInt("type", 2)) { 0 -> "LS"; 1 -> "HS"; 2 -> "PK"; else -> continue }
                        val q = b.optDouble("q", 0.0).let { if (it <= 0.0) 1.0 else it }
                        sb.append("Filter ${k + 1}: ON $ty Fc ${b.optDouble("frequency")} Hz Gain ${b.optDouble("gain")} dB Q $q\n")
                    }
                    val part = parse(sb.toString()) ?: continue
                    val name = o.optString("name", "").replace(Regex("\\s+"), " ").trim().take(24).ifEmpty { fallbackName }
                    out.add(name to part)
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }
        return parse(t)?.let { listOf(fallbackName to it) } ?: emptyList()
    }

    /** Preset letto, oppure null se il testo non è riconoscibile. */
    fun parse(text: String): EqPart? {
        var preamp = 0f
        val bands = ArrayList<PeqBand>()
        var graphic: List<Pair<Float, Float>>? = null
        var found = false
        for (raw in text.lines()) {
            val line = raw.trim()
            preampRe.find(line)?.let { m -> num(m.groupValues[1])?.let { preamp = it; found = true } }
            if (line.startsWith("GraphicEQ", ignoreCase = true)) {
                val pts = line.substringAfter(':').split(';').mapNotNull { seg ->
                    val p = seg.trim().split(Regex("\\s+"))
                    val f = p.getOrNull(0)?.let(::num); val g = p.getOrNull(1)?.let(::num)
                    if (f != null && g != null && f > 0f) f to g else null
                }
                if (pts.size >= 2) { graphic = pts.sortedBy { it.first }; found = true }
                continue
            }
            val m = filterRe.find(line) ?: continue
            found = true
            val on = m.groupValues[1].equals("ON", ignoreCase = true)
            val type = typeOf(m.groupValues[2]) ?: continue
            val f = num(m.groupValues[3]) ?: continue
            val g = m.groupValues[4].let { if (it.isEmpty()) 0f else num(it) ?: 0f }
            val q = m.groupValues[5].let { if (it.isEmpty()) 0.707f else num(it) ?: 0.707f }
            if (bands.size < DspSettings.PARAM_BANDS) bands.add(PeqBand(on, type, f, g, q).clamped())
        }
        if (!found) return null
        val pre = preamp.coerceIn(-24f, 24f)
        val gr = graphic
        if (gr != null) {
            val gains = DspSettings.GRAPHIC_FREQS.map { interpolate(gr, it).coerceIn(-DspSettings.GRAPHIC_GAIN, DspSettings.GRAPHIC_GAIN) }
            return EqPart(Modes.EQ_GRAPHIC, gains, true, DspSettings.defaultParametric(), pre)
        }
        val def = DspSettings.defaultParametric()
        val list = List(DspSettings.PARAM_BANDS) { i -> bands.getOrNull(i) ?: def[i].copy(on = false) }
        return EqPart(Modes.EQ_PARAMETRIC, List(DspSettings.GRAPHIC_BANDS) { 0f }, true, list, pre)
    }

    /** Interpolazione lineare in dB su scala logaritmica di frequenza. */
    private fun interpolate(pts: List<Pair<Float, Float>>, f: Float): Float {
        if (f <= pts.first().first) return pts.first().second
        if (f >= pts.last().first) return pts.last().second
        for (i in 1 until pts.size) {
            val (f1, g1) = pts[i]
            if (f <= f1) {
                val (f0, g0) = pts[i - 1]
                val t = (ln(f / f0) / ln(f1 / f0)).toFloat()
                return g0 + (g1 - g0) * t
            }
        }
        return pts.last().second
    }

    /** Testo AutoEQ/Poweramp del preset (il grafico diventa 15 campane). */
    fun export(p: EqPart): String {
        fun f(v: Float, d: Int) = String.format(Locale.US, "%.${d}f", v)
        val sb = StringBuilder()
        sb.append("Preamp: ").append(f(p.preampDb, 1)).append(" dB\n")
        var n = 1
        if (p.mode == Modes.EQ_GRAPHIC) {
            for (i in 0 until DspSettings.GRAPHIC_BANDS) {
                val g = p.graphic.getOrElse(i) { 0f }
                if (abs(g) < 0.05f) continue
                sb.append("Filter ${n++}: ON PK Fc ${f(DspSettings.GRAPHIC_FREQS[i], 0)} Hz Gain ${f(g, 1)} dB Q 2.15\n")
            }
        } else {
            for (b in p.parametric) {
                if (!b.on) continue
                sb.append("Filter ${n++}: ON ${b.type.short} Fc ${f(b.freq, 0)} Hz Gain ${f(b.gain, 1)} dB Q ${f(b.q, 2)}\n")
            }
        }
        return sb.toString()
    }

    /** JSON di Poweramp (elenco con un preset; solo campane e shelf). */
    fun exportPoweramp(name: String, p: EqPart): String {
        val bands = JSONArray()
        fun band(type: Int, f: Float, q: Float, g: Float) = JSONObject()
            .put("type", type).put("channels", 0).put("frequency", f.toDouble())
            .put("q", q.toDouble()).put("gain", g.toDouble()).put("color", 0)
        if (p.mode == Modes.EQ_GRAPHIC) {
            for (i in 0 until DspSettings.GRAPHIC_BANDS) bands.put(band(2, DspSettings.GRAPHIC_FREQS[i], 2.15f, p.graphic.getOrElse(i) { 0f }))
        } else {
            for (b in p.parametric) {
                if (!b.on) continue
                val t = when (b.type) { FilterType.LowShelf -> 0; FilterType.HighShelf -> 1; FilterType.Peak -> 2; else -> continue }
                bands.put(band(t, b.freq, b.q, b.gain))
            }
        }
        val o = JSONObject().put("name", name).put("preamp", p.preampDb.toDouble()).put("parametric", true).put("bands", bands)
        return JSONArray().put(o).toString(2)
    }
}
