package com.marco.mleq.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Raccoglie i campioni audio (dopo l'equalizzatore) per l'analizzatore:
 * buffer circolare L/R e picchi (true peak approssimato a 2x).
 * Scrive il thread audio, legge la UI: tutto senza blocchi.
 * Attivo solo quando la schermata dell'analizzatore è aperta.
 */
class Analyzer {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    var sampleRate: Int = 44100

    /** Indice (totale campioni scritti) pubblicato ogni 64 campioni. */
    @Volatile
    var published: Long = 0L
        private set

    private val ringL = FloatArray(SIZE)
    private val ringR = FloatArray(SIZE)
    private var idx = 0L

    private var l0 = 0f
    private var l1 = 0f
    private var l2 = 0f
    private var l3 = 0f
    private var r0 = 0f
    private var r1 = 0f
    private var r2 = 0f
    private var r3 = 0f
    private var peakL = 0f
    private var peakR = 0f

    fun push(l: Float, r: Float) {
        val i = (idx and MASK_L).toInt()
        ringL[i] = l
        ringR[i] = r
        idx++

        l0 = l1; l1 = l2; l2 = l3; l3 = l
        r0 = r1; r1 = r2; r2 = r3; r3 = r
        val midL = (-l0 + 9f * l1 + 9f * l2 - l3) / 16f
        val midR = (-r0 + 9f * r1 + 9f * r2 - r3) / 16f
        val al = maxOf(abs(l), abs(midL))
        val ar = maxOf(abs(r), abs(midR))
        if (al > peakL) peakL = al
        if (ar > peakR) peakR = ar

        if ((idx and 63L) == 0L) published = idx
    }

    /** Picchi (lineari) dall'ultima lettura; li azzera. */
    fun takePeaks(out: FloatArray) {
        out[0] = peakL
        out[1] = peakR
        peakL = 0f
        peakR = 0f
    }

    /** Copia gli ultimi [n] campioni (n <= SIZE) in ordine cronologico. */
    fun copyLatest(dstL: FloatArray, dstR: FloatArray, n: Int) {
        val p = published
        for (k in 0 until n) {
            val s = ((p - n + k) and MASK_L).toInt()
            dstL[k] = ringL[s]
            dstR[k] = ringR[s]
        }
    }

    companion object {
        const val SIZE = 16384
        private const val MASK_L = (SIZE - 1).toLong()
    }
}

/** FFT radice 2 in-place (decimazione nel tempo). */
class Fft(private val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        for (i in 0 until n / 2) {
            val a = -2.0 * PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var x = i
            var r = 0
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
