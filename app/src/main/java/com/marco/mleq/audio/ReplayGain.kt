package com.marco.mleq.audio

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlin.math.log10
import kotlin.math.min

/**
 * ReplayGain: livella i brani in base ai tag scritti da chi ha codificato il file, così i
 * master molto "forti" arrivano al DSP già abbassati e i rinforzi non finiscono nel limiter.
 *
 * Tag letti: REPLAYGAIN_TRACK/ALBUM_GAIN e _PEAK (commenti Vorbis di FLAC/Ogg, TXXX degli MP3,
 * atomi "----" iTunes degli M4A) e R128_TRACK/ALBUM_GAIN degli Opus (EBU R128, -23 LUFS: +5 dB
 * per riportarli al riferimento ReplayGain).
 */
object ReplayGain {
    const val OFF = 0
    const val TRACK = 1
    const val ALBUM = 2

    data class Info(
        val trackGain: Double? = null,
        val trackPeak: Double? = null,
        val albumGain: Double? = null,
        val albumPeak: Double? = null,
    ) {
        val isEmpty: Boolean get() = trackGain == null && albumGain == null
    }

    private val number = Regex("[-+]?\\d+(?:[.,]\\d+)?")

    private fun num(s: String?): Double? =
        s?.let { number.find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }?.takeIf { it.isFinite() }

    /** Interpreta una coppia chiave/valore; ritorna l'Info aggiornata. */
    fun apply(info: Info, key: String, value: String): Info {
        return when (key.trim().uppercase()) {
            "REPLAYGAIN_TRACK_GAIN" -> num(value)?.let { info.copy(trackGain = it) } ?: info
            "REPLAYGAIN_TRACK_PEAK" -> num(value)?.let { info.copy(trackPeak = it) } ?: info
            "REPLAYGAIN_ALBUM_GAIN" -> num(value)?.let { info.copy(albumGain = it) } ?: info
            "REPLAYGAIN_ALBUM_PEAK" -> num(value)?.let { info.copy(albumPeak = it) } ?: info
            // R128: intero Q7.8 in dB rispetto a -23 LUFS
            "R128_TRACK_GAIN" -> num(value)?.let { if (info.trackGain == null) info.copy(trackGain = it / 256.0 + 5.0) else info } ?: info
            "R128_ALBUM_GAIN" -> num(value)?.let { if (info.albumGain == null) info.copy(albumGain = it / 256.0 + 5.0) else info } ?: info
            else -> info
        }
    }

    @OptIn(UnstableApi::class)
    @Suppress("DEPRECATION")
    fun parse(metadata: Metadata?): Info? {
        if (metadata == null) return null
        var info = Info()
        for (i in 0 until metadata.length()) {
            when (val e = metadata.get(i)) {
                is androidx.media3.extractor.metadata.flac.VorbisComment -> info = apply(info, e.key, e.value)
                is TextInformationFrame -> if (e.id == "TXXX" && e.description != null && e.values.isNotEmpty()) {
                    info = apply(info, e.description!!, e.values[0])
                }
                is InternalFrame -> info = apply(info, e.description, e.text)
            }
        }
        return if (info.isEmpty) null else info
    }

    /**
     * Guadagno da applicare (dB). Album ricade sul brano se manca (e viceversa). Se c'è il picco,
     * il guadagno non lo porta mai oltre 0 dBFS (niente rinforzi che finiscono nel limiter).
     */
    fun gainDb(info: Info?, mode: Int): Double {
        if (info == null || mode == OFF) return 0.0
        val (g, p) = if (mode == ALBUM) {
            (info.albumGain ?: info.trackGain) to (if (info.albumGain != null) info.albumPeak else info.trackPeak)
        } else {
            (info.trackGain ?: info.albumGain) to (if (info.trackGain != null) info.trackPeak else info.albumPeak)
        }
        if (g == null) return 0.0
        var gain = g.coerceIn(-24.0, 12.0)
        if (p != null && p > 0.0) gain = min(gain, -20.0 * log10(p))
        return gain
    }
}
