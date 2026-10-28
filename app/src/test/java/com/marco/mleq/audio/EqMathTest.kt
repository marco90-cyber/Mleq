package com.marco.mleq.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EqMathTest {

    private fun db(c: EqMath.Coef, fs: Double, f: Double) = EqMath.magnitudeDb(c, fs, f)

    @Test
    fun peakingGainAtCenter() {
        for (fs in listOf(44100.0, 48000.0, 96000.0, 192000.0)) {
            for (g in listOf(-12.0, -6.0, 3.0, 12.0)) {
                val c = EqMath.peaking(fs, 1000.0, 1.0, g)
                assertEquals("fs=$fs g=$g", g, db(c, fs, 1000.0), 0.01)
            }
        }
    }

    @Test
    fun peakingIsFlatFarFromCenter() {
        val c = EqMath.peaking(48000.0, 1000.0, 4.0, 12.0)
        assertEquals(0.0, db(c, 48000.0, 30.0), 0.2)
        assertEquals(0.0, db(c, 48000.0, 18000.0), 0.5)
    }

    @Test
    fun shelvesBoostAndCut() {
        val fs = 48000.0
        for (g in listOf(-12.0, 12.0)) {
            val low = EqMath.lowShelf(fs, 200.0, g)
            assertEquals(g, db(low, fs, 20.0), 0.3)
            assertEquals(0.0, db(low, fs, 10000.0), 0.3)
            val high = EqMath.highShelf(fs, 5000.0, g)
            assertEquals(g, db(high, fs, 20000.0), 0.7)
            assertEquals(0.0, db(high, fs, 50.0), 0.3)
        }
    }

    @Test
    fun smallGainIsIdentity() {
        assertTrue(EqMath.peaking(48000.0, 1000.0, 1.0, 0.0) === EqMath.IDENTITY)
        assertTrue(EqMath.lowShelf(48000.0, 100.0, 0.0) === EqMath.IDENTITY)
    }

    @Test
    fun coefficientsAreFiniteAndStableForExtremeParameters() {
        for (fs in listOf(8000.0, 22050.0, 44100.0, 192000.0)) {
            for (f in listOf(20.0, 100.0, 1000.0, 3500.0, 19999.0)) {
                if (f >= fs * 0.45) continue
                for (q in listOf(0.2, 1.0, 12.0)) {
                    for (g in listOf(-12.0, 12.0)) {
                        val c = EqMath.peaking(fs, f, q, g)
                        listOf(c.b0, c.b1, c.b2, c.a1, c.a2).forEach {
                            assertTrue("fs=$fs f=$f q=$q g=$g", it.isFinite())
                        }
                        // stabilità: poli dentro il cerchio unitario (|a2| < 1, |a1| < 1 + a2)
                        assertTrue(abs(c.a2) < 1.0)
                        assertTrue(abs(c.a1) < 1.0 + c.a2)
                    }
                }
            }
        }
    }

    @Test
    fun stageCountMatchesBandCountForEveryBandNumber() {
        for (n in EqConfig.MIN_BANDS..EqConfig.MAX_BANDS) {
            val cfg = EqConfig(enabled = true).withBandCount(n)
            assertEquals(n, cfg.bandCount)
            assertEquals(n, cfg.freqs.size)
            assertEquals(n, cfg.gains.size)
            assertEquals(n, cfg.qs.size)
            assertEquals(n + 5, EqMath.stages(cfg, 48000.0).size)
        }
    }

    @Test
    fun defaultFrequenciesAreStrictlyIncreasingAndInRange() {
        for (n in EqConfig.MIN_BANDS..EqConfig.MAX_BANDS) {
            val f = EqConfig.defaultFreqs(n)
            for (i in 1 until f.size) assertTrue("n=$n i=$i", f[i] > f[i - 1])
            assertTrue(f.first() >= EqConfig.MIN_FREQ)
            assertTrue(f.last() <= EqConfig.MAX_FREQ)
        }
    }

    @Test
    fun toneKnobMapping() {
        assertEquals(0.0, EqMath.toneDb(0), 1e-9)
        assertEquals(5.0, EqMath.toneDb(50), 1e-9)
        assertEquals(10.0, EqMath.toneDb(100), 1e-9)
    }

    @Test
    fun responseMatchesGainSettingAtBandFrequency() {
        val base = EqConfig(enabled = true).withBandCount(10)
        val cfg = base.withGain(5, 6f) // 1 kHz
        val r = cfg.responseDb(cfg.freqs[5].toDouble(), 48000.0)
        assertTrue("risposta a ${cfg.freqs[5]} Hz = $r", r in 5.5..6.5)
    }

    @Test
    fun disabledEqIsFlat() {
        val cfg = EqConfig(enabled = false).withBandCount(10).withGain(3, 9f).copy(preampDb = 6f)
        for (f in listOf(31.0, 1000.0, 16000.0)) assertEquals(0.0, cfg.responseDb(f, 48000.0), 1e-9)
        assertFalse(cfg.dspActive())
    }

    @Test
    fun toneKnobsWorkEvenWhenEqIsOff() {
        val cfg = EqConfig(enabled = false, bassPercent = 100)
        assertTrue(cfg.dspActive())
        assertTrue(cfg.responseDb(40.0, 48000.0) > 5.0)
    }

    @Test
    fun autoHeadroomCompensatesBoost() {
        val cfg = EqConfig(enabled = true).withBandCount(10).withGain(0, 12f).withGain(1, 12f).copy(autoHeadroom = true)
        val peak = cfg.boostPeakDb(48000.0)
        assertTrue(peak >= 12.0)
        assertEquals(-peak, cfg.effectivePreampDb(48000.0), 1e-9)
        assertEquals(0.0, cfg.copy(autoHeadroom = false).effectivePreampDb(48000.0), 1e-9)
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val cfg = EqConfig(enabled = true).withBandCount(24).withGain(3, 4.5f).withQ(3, 3.3f)
            .withFreq(5, 400f).copy(preampDb = -3f, bassPercent = 20, trebleFreq = 8000f, limiterOn = false, autoHeadroom = true, limiterThresholdDb = -3f, limiterReleaseMs = 120f)
        val back = EqConfig.fromJson(cfg.toJson())
        assertEquals(cfg, back)
    }

    @Test
    fun oldJsonToneKnobsAreMigrated() {
        // vecchio formato: manopole 0..100% = 0..+12 dB, nessun campo toneV / qs
        val old = """{"enabled":true,"bandCount":10,"bass":100,"treble":0,"preamp":0.0}"""
        val cfg = EqConfig.fromJson(old)
        assertEquals(100, cfg.bassPercent)   // +12 dB -> limitato a +10 dB
        assertEquals(0, cfg.treblePercent)   // 0 dB come prima
        assertEquals(10, cfg.qs.size)
        val mid = EqConfig.fromJson("""{"bass":50}""")
        assertEquals(60, mid.bassPercent)    // +6 dB come prima
        // formato intermedio (50 = neutro, -12..+12 dB): il rinforzo resta, i tagli diventano 0
        assertEquals(60, EqConfig.fromJson("""{"toneV":2,"bass":75}""").bassPercent)
        assertEquals(0, EqConfig.fromJson("""{"toneV":2,"bass":30}""").bassPercent)
        assertEquals(0, EqConfig.fromJson("""{"toneV":2,"treble":50}""").treblePercent)
    }

    @Test
    fun invalidJsonFallsBackToDefaults() {
        assertEquals(EqConfig(), EqConfig.fromJson("non è json"))
        assertEquals(EqConfig(), EqConfig.fromJson(null))
    }

    @Test
    fun selectableBandSetsUseIsoFrequencies() {
        for (n in listOf(5, 10, 15, 31)) {
            val cfg = EqConfig(enabled = true).withBandCount(n)
            assertEquals(n, cfg.bandCount)
            assertEquals(n, cfg.freqs.size)
        }
        assertEquals(20f, EqConfig.defaultFreqs(31).first(), 0f)
        assertEquals(20000f, EqConfig.defaultFreqs(31).last(), 0f)
        assertEquals(1000f, EqConfig.defaultFreqs(31)[17], 0f)
    }

    @Test
    fun bandCountChangeKeepsCurveShape() {
        val cfg = EqConfig(enabled = true).withBandCount(10).withCurve(EqPresets.all[1].points) // Bassi
        val more = cfg.withBandCount(31)
        assertTrue(more.gains.first() > 3f)
        assertTrue(more.gains.last() < 1f)
    }

    // ---- voce delle manopole: bassi caldi, alti freddi

    @Test
    fun toneStagesAreNeutralWhenKnobsAreZero() {
        val st = EqMath.stages(EqConfig(enabled = false), 48000.0)
        assertEquals(EqMath.stageCount(10), st.size)
        assertTrue(st.all { it === EqMath.IDENTITY })
    }

    @Test
    fun warmBassAddsBodyAboveTheShelf() {
        val fs = 48000.0
        val cfg = EqConfig(bassPercent = 100)            // +10 dB, shelf a 100 Hz
        val plain = EqMath.lowShelf(fs, 100.0, 10.0)      // shelf semplice, come prima
        // in fondo alla gamma la shelf morbida arriva a +10 dB come prima
        assertEquals(10.0, cfg.toneResponseDb(20.0, fs), 0.6)
        // nei medio-bassi c'è corpo in più rispetto alla sola shelf
        for (f in listOf(150.0, 200.0, 300.0)) {
            assertTrue("a $f Hz", cfg.toneResponseDb(f, fs) > EqMath.magnitudeDb(plain, fs, f) + 2.0)
        }
        // niente effetto sopra i 2 kHz
        assertEquals(0.0, cfg.toneResponseDb(2000.0, fs), 0.3)
        // nessun picco oltre shelf + corpo
        var peak = 0.0
        for (i in 0 until 256) peak = maxOf(peak, cfg.toneResponseDb(20.0 * Math.pow(1000.0, i / 255.0), fs))
        assertTrue("picco $peak dB", peak <= 10.0 + 0.3)
    }

    @Test
    fun coldTrebleAddsAirAndCleansTheLowMids() {
        val fs = 48000.0
        val cfg = EqConfig(treblePercent = 100)           // +10 dB, shelf a 10 kHz
        // aria: sopra la shelf si supera i +10 dB della sola shelf
        assertTrue(cfg.toneResponseDb(16000.0, fs) > 11.0)
        assertTrue(cfg.toneResponseDb(16000.0, fs) < 14.0)
        // pulizia: attorno ai 320 Hz un leggero taglio, mai oltre -1,5 dB
        val clean = cfg.toneResponseDb(320.0, fs)
        assertTrue("a 320 Hz = $clean", clean in -1.5..-0.8)
        // le frequenze basse restano quasi intatte
        assertEquals(0.0, cfg.toneResponseDb(50.0, fs), 0.2)
        // nessun picco eccessivo
        var peak = 0.0
        for (i in 0 until 256) peak = maxOf(peak, cfg.toneResponseDb(20.0 * Math.pow(1000.0, i / 255.0), fs))
        assertTrue("picco $peak dB", peak < 13.5)
    }

    @Test
    fun toneStagesStayFiniteAtAnySampleRate() {
        for (fs in listOf(8000.0, 22050.0, 44100.0, 96000.0, 192000.0)) {
            val cfg = EqConfig(bassPercent = 100, treblePercent = 100, bassFreq = 400f, trebleFreq = 16000f)
            for (c in EqMath.stages(cfg, fs)) {
                for (v in listOf(c.b0, c.b1, c.b2, c.a1, c.a2)) assertTrue("fs=$fs", v.isFinite())
            }
            for (f in listOf(20.0, 1000.0, 15000.0)) assertTrue(cfg.toneResponseDb(f, fs).isFinite())
        }
    }

    @Test
    fun shelfSlopeKeepsPlainShelfAtDefault() {
        val fs = 48000.0
        val a = EqMath.lowShelf(fs, 200.0, 8.0)
        val b = EqMath.lowShelf(fs, 200.0, 8.0, 1.0)
        for (f in listOf(30.0, 200.0, 2000.0)) assertEquals(EqMath.magnitudeDb(a, fs, f), EqMath.magnitudeDb(b, fs, f), 1e-12)
        // pendenza più dolce: a metà della transizione la shelf morbida sale prima
        val soft = EqMath.lowShelf(fs, 200.0, 8.0, 0.75)
        assertTrue(EqMath.magnitudeDb(soft, fs, 400.0) > EqMath.magnitudeDb(a, fs, 400.0))
    }
}

