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
        val cfg = EqConfig(enabled = false, bassPercent = 100, toneHeadroom = false)
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

    /** Curva analogica ideale di un peaking (prototipo del Cookbook) in dB a f/f0 = x. */
    private fun idealPeakingDb(gainDb: Double, q: Double, x: Double): Double {
        val a = Math.pow(10.0, gainDb / 40.0)
        val re = 1.0 - x * x
        return 20.0 * Math.log10(Math.hypot(re, x * a / q) / Math.hypot(re, x / (a * q)))
    }

    private fun worstPeakingError(fs: Double, f0: Double, q: Double, g: Double): Double {
        val c = EqMath.peaking(fs, f0, q, g)
        var worst = 0.0
        for (i in 0 until 200) {
            val f = 20.0 * Math.pow(0.45 * fs / 20.0, i / 199.0)
            worst = maxOf(worst, abs(EqMath.magnitudeDb(c, fs, f) - idealPeakingDb(g, q, f / f0)))
        }
        return worst
    }

    @Test
    fun peakingTracksTheAnalogCurveNearNyquist() {
        // il progetto classico sbaglia di 7,9 dB (16 kHz, Q1, +12 dB a 44,1 kHz) e 6,1 dB (Q3, -12 dB)
        assertTrue(worstPeakingError(44100.0, 16000.0, 1.0, 12.0) < 3.0)
        assertTrue(worstPeakingError(44100.0, 16000.0, 3.0, -12.0) < 3.0)
        assertTrue(worstPeakingError(48000.0, 12000.0, 2.0, 9.0) < 2.0)
    }

    @Test
    fun cutIsTheExactInverseOfTheBoost() {
        val fs = 44100.0
        val up = EqMath.peaking(fs, 14000.0, 1.5, 9.0)
        val down = EqMath.peaking(fs, 14000.0, 1.5, -9.0)
        for (f in listOf(100.0, 3000.0, 9000.0, 14000.0, 19000.0)) {
            assertEquals(0.0, EqMath.magnitudeDb(up, fs, f) + EqMath.magnitudeDb(down, fs, f), 0.05)
        }
    }

    @Test
    fun filtersStayStableAtAnySettingAndRate() {
        for (fs in listOf(44100.0, 48000.0, 96000.0, 192000.0)) {
            for (f in listOf(40.0, 500.0, 5000.0, 12000.0, 16000.0, 20000.0)) {
                if (f >= fs * 0.45) continue
                for (g in listOf(-15.0, -6.0, 6.0, 15.0)) {
                    val all = listOf(
                        EqMath.peaking(fs, f, 0.4, g), EqMath.peaking(fs, f, 8.0, g),
                        EqMath.lowShelf(fs, f, g), EqMath.highShelf(fs, f, g, 0.75),
                    )
                    for (c in all) {
                        assertTrue(c.a2 > -1.0 && c.a2 < 1.0 && abs(c.a1) < 1.0 + c.a2)
                        assertTrue(c.b0.isFinite() && c.b1.isFinite() && c.b2.isFinite())
                    }
                }
            }
        }
    }

    @Test
    fun faithfulCurvePassesThroughTheSliderValues() {
        val fs = 48000.0
        val plain = EqConfig(enabled = true, bandCount = 10, gains = List(10) { 6f })
        val fixed = plain.copy(faithful = true)
        // senza compensazione le campane vicine si sommano e superano i +6 dB
        val over = plain.freqs.indices.maxOf { plain.responseDb(plain.freqs[it].toDouble(), fs, false) }
        assertTrue("senza compensazione dovrebbe superare +6 dB: $over", over > 7.0)
        for (i in 0 until 10) {
            val v = fixed.responseDb(fixed.freqs[i].toDouble(), fs, false)
            assertEquals("banda $i", 6.0, v, 0.05)
        }
    }

    @Test
    fun faithfulCurveHandlesAlternatingGainsAndStaysInRange() {
        val fs = 48000.0
        val gains = List(10) { if (it % 2 == 0) 8f else -5f }
        val cfg = EqConfig(enabled = true, bandCount = 10, gains = gains, faithful = true)
        val eff = cfg.effectiveGains(fs)
        assertTrue(eff.all { it.isFinite() && abs(it) <= EqConfig.GAIN_RANGE + 1e-3f })
        for (i in 0 until 10) {
            val v = cfg.responseDb(cfg.freqs[i].toDouble(), fs, false)
            assertEquals("banda $i", gains[i].toDouble(), v, 0.3)
        }
    }

    @Test
    fun faithfulCurveIsOffByDefaultAndKeepsGainsUntouched() {
        val cfg = EqConfig(enabled = true, gains = List(10) { 3f })
        assertEquals(cfg.gains, cfg.effectiveGains(48000.0))
        assertFalse(EqConfig.fromJson(cfg.toJson()).faithful)
        assertTrue(EqConfig.fromJson(cfg.copy(faithful = true).toJson()).faithful)
    }

    @Test
    fun manualTypeAndPreampEditsAreClampedAndCompact() {
        val cfg = EqConfig(bandCount = 10)
        val t = cfg.withType(0, 0).withType(9, 1)
        assertEquals(0, t.typeAt(0)); assertEquals(1, t.typeAt(9)); assertEquals(2, t.typeAt(4))
        assertTrue(t.withType(0, 2).withType(9, 2).types.isEmpty())
        assertEquals(cfg, cfg.withType(99, 0))
        assertEquals(EqConfig.GAIN_RANGE, cfg.withPreamp(40f).preampDb, 0f)
        assertEquals(-EqConfig.GAIN_RANGE, cfg.withPreamp(-40f).preampDb, 0f)
    }

    @Test
    fun toneHeadroomKeepsTheBoostOutOfTheLimiterEvenWithEqOff() {
        val fs = 48000.0
        val cfg = EqConfig(enabled = false, bassPercent = 100, treblePercent = 100)
        val peak = cfg.tonePeakDb(fs)
        assertTrue("rinforzo atteso > 9 dB: $peak", peak > 9.0)
        assertEquals(-peak, cfg.effectivePreampDb(fs), 1e-9)
        var top = -100.0
        for (i in 0 until 200) top = maxOf(top, cfg.responseDb(20.0 * Math.pow(1000.0, i / 199.0), fs))
        assertTrue("picco totale atteso ~0 dB: $top", top <= 0.05 && top > -0.5)
        // spento: nessun headroom
        assertEquals(0.0, cfg.copy(toneHeadroom = false).effectivePreampDb(fs), 1e-9)
        // manopole a zero: nessun abbassamento
        assertEquals(0.0, EqConfig().effectivePreampDb(fs), 1e-9)
        // la curva dell'EQ (senza tono) non si sposta quando si girano le manopole
        val eq = EqConfig(enabled = true).withGain(4, 3f)
        val withKnobs = eq.copy(bassPercent = 80)
        assertEquals(eq.responseDb(1000.0, fs, false), withKnobs.responseDb(1000.0, fs, false), 1e-6)
    }

    @Test
    fun autoHeadroomWithEqOnStillCoversTheKnobs() {
        val fs = 48000.0
        val cfg = EqConfig(enabled = true, bassPercent = 100, autoHeadroom = true).withGain(0, 6f)
        assertEquals(-cfg.boostPeakDb(fs), cfg.effectivePreampDb(fs), 1e-9)
        assertTrue(cfg.boostPeakDb(fs) >= cfg.tonePeakDb(fs))
    }

    @Test
    fun stereoSettingsActivateDspAndRoundTrip() {
        val base = EqConfig()
        assertFalse(base.dspActive())
        assertTrue(base.copy(mono = true).dspActive())
        assertTrue(base.copy(balance = -30).dspActive())
        assertTrue(base.copy(stereoExpand = 50).dspActive())
        assertEquals(0f, base.copy(mono = true, stereoExpand = 80).widthFactor(), 0f)
        val c = base.copy(balance = 40, stereoExpand = 60, mono = true)
        assertEquals(c, EqConfig.fromJson(c.toJson()))
        // allargare abbassa il livello di (1 + w) / 2 con l'headroom attivo
        assertEquals(1.5f, base.copy(stereoExpand = 50).widthFactor(), 1e-6f)
        // vecchio formato 0..200%: 160% -> espansione 60%, 80% -> nessuna espansione
        assertEquals(60, EqConfig.fromJson("""{"width":160}""").stereoExpand)
        assertEquals(0, EqConfig.fromJson("""{"width":80}""").stereoExpand)
        val wide = base.copy(stereoExpand = 100)
        assertEquals(-20 * Math.log10(1.5), wide.effectivePreampDb(48000.0), 1e-9)
        assertEquals(0.0, wide.copy(toneHeadroom = false).effectivePreampDb(48000.0), 1e-9)
    }

    @Test
    fun compressorSettingsActivateDspAndRoundTrip() {
        val base = EqConfig()
        assertFalse(base.dspActive())
        val c = base.copy(compressorOn = true, compThresholdDb = -24f, compRatio = 4.5f, compAttackMs = 7f, compReleaseMs = 220f, compKneeDb = 9f, compMakeupDb = 3f)
        assertTrue(c.dspActive())
        assertEquals(c, EqConfig.fromJson(c.toJson()))
        val wild = EqConfig.fromJson("""{"comp":true,"compThr":-99,"compRatio":99,"compAtk":0,"compRel":9999,"compKnee":99,"compMakeup":99}""")
        assertEquals(-60f, wild.compThresholdDb, 0f); assertEquals(20f, wild.compRatio, 0f)
        assertEquals(1f, wild.compAttackMs, 0f); assertEquals(1000f, wild.compReleaseMs, 0f)
        assertEquals(24f, wild.compKneeDb, 0f); assertEquals(24f, wild.compMakeupDb, 0f)
    }
}
