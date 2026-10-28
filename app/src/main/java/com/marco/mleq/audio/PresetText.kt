package com.marco.mleq.audio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Import/export dei preset nel formato testo di Poweramp / AutoEQ:
 *
 *   Preamp: -6.4 dB
 *   Filter 1: ON LS Fc 105 Hz Gain 6.5 dB Q 0.70
 *   Filter 2: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41
 *
 * Supporta anche la riga "GraphicEQ: 20 0; 25 1.5; ..." e, in lettura, il separatore decimale con virgola.
 */
object PresetText {
    private val filterRe = Regex(
        """Filter\s*\d*\s*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+([-\d.,]+)\s*(?:Hz)?\s+Gain\s+([-\d.,]+)\s*(?:dB)?(?:\s+Q\s+([-\d.,]+))?""",
        RegexOption.IGNORE_CASE
    )
    private val preampRe = Regex("""Preamp\s*:\s*([-+\d.,]+)\s*(?:dB)?""", RegexOption.IGNORE_CASE)

    private class F(val f: Float, val g: Float, val q: Float, val type: Int)

    private fun num(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()

    /** Nome + configurazione. Accetta il JSON di Poweramp (elenco di preset) oppure il testo AutoEQ. */
    fun parseMulti(text: String, base: EqConfig, fallbackName: String): List<Pair<String, EqConfig>> {
        val t = text.trim().removePrefix("\uFEFF")
        if (t.startsWith("[") || t.startsWith("{")) {
            try {
                val arr = if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONArray().put(org.json.JSONObject(t))
                val out = ArrayList<Pair<String, EqConfig>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sb = StringBuilder("Preamp: ${o.optDouble("preamp", 0.0)} dB\n")
                    val bands = o.optJSONArray("bands") ?: continue
                    for (k in 0 until bands.length()) {
                        val b = bands.getJSONObject(k)
                        val ty = when (b.optInt("type", 2)) { 0 -> "LS"; 1 -> "HS"; 2 -> "PK"; else -> continue }
                        sb.append("Filter ${k + 1}: ON $ty Fc ${b.optDouble("frequency")} Hz Gain ${b.optDouble("gain")} dB Q ${b.optDouble("q", 0.0)}\n")
                    }
                    val cfg = parse(sb.toString(), base) ?: continue
                    val name = o.optString("name", "").replace(Regex("\\s+"), " ").trim().take(24).ifEmpty { fallbackName }
                    out.add(name to cfg)
                }
                return out
            } catch (_: Exception) {
                return emptyList()
            }
        }
        return parse(t, base)?.let { listOf(fallbackName to it) } ?: emptyList()
    }

    /** Esporta nel JSON di Poweramp (elenco con un preset). */
    fun exportPoweramp(name: String, cfg: EqConfig): String {
        val bands = org.json.JSONArray()
        fun band(type: Int, f: Float, q: Float, g: Float) = org.json.JSONObject()
            .put("type", type).put("channels", 0).put("frequency", f.toDouble())
            .put("q", q.toDouble()).put("gain", g.toDouble()).put("color", 0)
        for (i in 0 until cfg.bandCount) bands.put(band(cfg.typeAt(i), cfg.freqs[i], cfg.qFor(i).toFloat(), cfg.gains[i]))
        val o = org.json.JSONObject().put("name", name).put("preamp", cfg.preampDb.toDouble())
            .put("parametric", true).put("bands", bands)
        return org.json.JSONArray().put(o).toString(2)
    }

    /** Restituisce la configurazione letta (partendo da [base]) oppure null se il testo non è riconoscibile. */
    fun parse(text: String, base: EqConfig): EqConfig? {
        val lines = text.lines()
        var preamp = 0f
        val pk = ArrayList<F>()
        var graphic: List<Pair<Float, Float>>? = null
        var found = false
        for (raw in lines) {
            val line = raw.trim()
            preampRe.find(line)?.let { m -> num(m.groupValues[1])?.let { preamp = it; found = true } }
            if (line.startsWith("GraphicEQ", ignoreCase = true)) {
                val pts = line.substringAfter(':').split(';').mapNotNull { seg ->
                    val p = seg.trim().split(Regex("\\s+"))
                    if (p.size >= 2) {
                        val f = num(p[0]); val g = num(p[1])
                        if (f != null && g != null) f to g else null
                    } else null
                }
                if (pts.size >= 2) { graphic = pts; found = true }
                continue
            }
            val m = filterRe.find(line) ?: continue
            found = true
            if (!m.groupValues[1].equals("ON", ignoreCase = true)) continue
            val f = num(m.groupValues[3]) ?: continue
            val g = num(m.groupValues[4]) ?: continue
            val q = m.groupValues[5].let { if (it.isEmpty()) 0.707f else num(it) ?: 0.707f }
            when (m.groupValues[2].uppercase(Locale.ROOT)) {
                "LS", "LSC", "LSQ" -> pk.add(F(f, g, 0f, 0))
                "HS", "HSC", "HSQ" -> pk.add(F(f, g, 0f, 1))
                "PK", "PEQ", "PEAK" -> pk.add(F(f, g, q, 2))
                else -> {}
            }
        }
        if (!found) return null
        var cfg = base.copy(
            enabled = true,
            preampDb = preamp.coerceIn(-24f, 12f),
        )
        val g = graphic
        if (g != null) {
            return cfg.copy(gains = List(cfg.bandCount) { 0f }).withCurve(g)
        }
        if (pk.isNotEmpty()) {
            // ordina e rispetta la distanza minima fra le bande
            val sorted = pk.sortedBy { it.f }
            val kept = ArrayList<F>()
            for (t in sorted) {
                val fr = t.f.coerceIn(EqConfig.MIN_FREQ, EqConfig.MAX_FREQ)
                val minF = if (kept.isEmpty()) 0f else kept.last().f * 1.05f
                if (fr >= minF) kept.add(F(fr, t.g, t.q, t.type))
                else if (minF <= EqConfig.MAX_FREQ) kept.add(F(minF, t.g, t.q, t.type))
            }
            val list = kept.take(EqConfig.MAX_BANDS)
            val n = list.size.coerceAtLeast(EqConfig.MIN_BANDS)
            val freqs = ArrayList(list.map { it.f })
            val gains = ArrayList(list.map { it.g.coerceIn(-EqConfig.GAIN_RANGE, EqConfig.GAIN_RANGE) })
            val autoQ = EqConfig.autoQs(freqs)
            val qs = ArrayList(list.mapIndexed { i, t -> if (t.q <= 0f) autoQ[i] else t.q.coerceIn(EqConfig.MIN_Q, EqConfig.MAX_Q) })
            val types = ArrayList(list.map { it.type })
            var f = freqs.last()
            while (freqs.size < n) { f = (f * 1.3f).coerceAtMost(EqConfig.MAX_FREQ); freqs.add(f); gains.add(0f); qs.add(1f); types.add(2) }
            cfg = cfg.copy(bandCount = n, freqs = freqs, gains = gains, qs = qs, types = if (types.any { it != 2 }) types else emptyList())
        } else {
            cfg = cfg.copy(gains = List(cfg.bandCount) { 0f })
        }
        return cfg
    }

    fun export(cfg: EqConfig): String {
        fun f(v: Float, d: Int) = String.format(Locale.US, "%.${d}f", v)
        val sb = StringBuilder()
        sb.append("Preamp: ").append(f(cfg.preampDb, 1)).append(" dB\n")
        var n = 1
        for (i in 0 until cfg.bandCount) {
            if (abs(cfg.gains[i]) < 0.05f) continue
            val ty = when (cfg.typeAt(i)) { 0 -> "LS"; 1 -> "HS"; else -> "PK" }
            sb.append("Filter ${n++}: ON $ty Fc ${f(cfg.freqs[i], 0)} Hz Gain ${f(cfg.gains[i], 1)} dB Q ${f(cfg.qs.getOrElse(i) { 1f }, 2)}\n")
        }
        return sb.toString()
    }
}
