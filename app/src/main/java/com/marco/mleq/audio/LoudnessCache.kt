package com.marco.mleq.audio

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Risultati delle analisi di loudness, per indirizzo del file (al massimo [MAX] voci, le più vecchie escono). */
class LoudnessCache(private val sp: SharedPreferences) {
    private val map = LinkedHashMap<String, Pair<Double, Double>>()

    init {
        try {
            val o = JSONObject(sp.getString(KEY, "{}") ?: "{}")
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                map[k] = a.getDouble(0) to a.getDouble(1)
            }
        } catch (_: Exception) {
            map.clear()
        }
    }

    @Synchronized
    fun get(uri: String): ReplayGain.Info? =
        map[uri]?.let { ReplayGain.Info(trackGain = LoudnessScanner.REFERENCE_LUFS - it.first, trackPeak = it.second) }

    @Synchronized
    fun put(uri: String, r: LoudnessScanner.Result) {
        map.remove(uri)
        map[uri] = r.lufs to r.peak
        while (map.size > MAX) map.remove(map.keys.first())
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONArray().put(v.first).put(v.second))
        sp.edit().putString(KEY, o.toString()).apply()
    }

    companion object {
        const val MAX = 2000
        private const val KEY = "loudness_cache"
    }
}
