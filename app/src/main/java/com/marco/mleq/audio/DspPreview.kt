package com.marco.mleq.audio

/**
 * Copia del motore usata solo per il grafico: stessa matematica di quello che suona, a 48 kHz,
 * così la curva mostrata è quella vera (compresa la "curva fedele" del grafico a 15 bande).
 */
class DspPreview {
    private val dsp = NativeDsp().also { it.configure(48000, 2) }
    private var last: String? = null

    /** Frequenze del grafico: 160 punti da 20 Hz a 20 kHz. */
    val freqs = DoubleArray(160) { i -> 20.0 * Math.pow(1000.0, i / 159.0) }

    class Result(val response: DoubleArray, val graphicApplied: DoubleArray)

    @Synchronized
    fun compute(s: DspSettings): Result {
        // il grafico mostra la curva tonale: niente ReplayGain, uscita in cuffia, volume pieno
        val text = s.copy(master = true).toNative()
        if (text != last) {
            dsp.setParams(text)
            last = text
        }
        return Result(dsp.response(freqs), dsp.graphicApplied())
    }
}
