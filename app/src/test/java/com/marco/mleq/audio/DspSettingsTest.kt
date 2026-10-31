package com.marco.mleq.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSettingsTest {
    private fun map(s: String): Map<String, String> =
        s.split(';').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun defaultsMatchPowerampToneAndAreSafe() {
        val d = DspSettings()
        assertFalse(d.master)
        assertEquals(90f, d.bassFreq, 0f); assertEquals(0.8f, d.bassQ, 0f)
        assertEquals(10000f, d.trebleFreq, 0f); assertEquals(0.6f, d.trebleQ, 0f)
        assertTrue(d.limiter)
        assertEquals(DspSettings.GRAPHIC_BANDS, d.graphic.size)
        assertEquals(DspSettings.PARAM_BANDS, d.parametric.size)
    }

    @Test
    fun nativeTextCarriesEveryParameter() {
        val s = DspSettings(master = true, bassDb = 6f, bassQ = 1.2f, stereo = true, surroundMode = 3)
            .withGraphic(3, 4.5f)
            .withBand(2, PeqBand(true, FilterType.Notch, 3000f, 0f, 8f))
        val m = map(s.toNative(rgDb = -6.5, volume = 0.5))
        assertEquals("1", m["master"])
        assertEquals("6.0000", m["bass"]); assertEquals("1.2000", m["bass.q"])
        assertEquals("1", m["stereo"]); assertEquals("3", m["surround"])
        assertEquals("4.5000", m["geq.3"])
        assertEquals("1,6,3000.0000,0.0000,8.0000", m["peq.2"])
        assertEquals("-6.500", m["rg"]); assertEquals("0.5000", m["volume"])
        // il punto decimale e' sempre '.', qualunque sia la lingua del telefono
        assertFalse(s.toNative().contains(",0000;"))
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val s = DspSettings(
            master = true, preampDb = -3f, eqMode = Modes.EQ_PARAMETRIC, faithful = false,
            bassDb = 4f, bassFreq = 120f, bassQ = 0.5f, trebleDb = 2f, trebleFreq = 8000f, trebleQ = 1.1f,
            tone = false, analogBass = true, analogDrive = 0.7f, analogWarmth = 0.2f, analogFreq = 30f,
            air = true, stereo = true, surroundMode = 1, balance = -0.3f, expand = 0.4f, mono = false,
            crossfeed = 0.6f, dbfbMode = 2, loudness = true,
            limiter = false, limiterThresholdDb = -2f, replayGain = ReplayGain.ALBUM,
        ).withGraphic(0, -5f).withBand(15, PeqBand(true, FilterType.HighPass, 40f, 0f, 0.7f))
        assertEquals(s, DspSettings.fromJson(s.toJson().toString()))
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val s = DspSettings.fromJson("""{"v":2,"bass":99,"bassQ":9,"limThr":5,"graphic":[50,-50],"parametric":[{"on":true,"type":42,"f":1,"g":99,"q":0}]}""")
        assertEquals(DspSettings.TONE_MAX_DB, s.bassDb, 0f)
        assertEquals(2f, s.bassQ, 0f)
        assertEquals(0f, s.limiterThresholdDb, 0f)
        assertEquals(DspSettings.GRAPHIC_GAIN, s.graphic[0], 0f)
        assertEquals(-DspSettings.GRAPHIC_GAIN, s.graphic[1], 0f)
        val b = s.parametric[0]
        assertEquals(FilterType.Peak, b.type)
        assertEquals(DspSettings.MIN_FREQ, b.freq, 0f); assertEquals(DspSettings.PEQ_GAIN, b.gain, 0f); assertEquals(DspSettings.MIN_Q, b.q, 0f)
    }

    @Test
    fun oldSettingsAreMigrated() {
        val old = """{"enabled":true,"bandCount":3,"freqs":[100,1000,8000],"gains":[3,-2,4],"qs":[0.7,1.4,2],"types":[0,2,1],
            "toneV":3,"bass":50,"treble":20,"preamp":-4,"limiter":true,"balance":-40,"expand":30,"comp":true,"compRatio":5,"replayGain":1}"""
        val s = DspSettings.fromJson(old)
        assertTrue(s.master)
        assertEquals(Modes.EQ_PARAMETRIC, s.eqMode)
        assertEquals(FilterType.LowShelf, s.parametric[0].type); assertEquals(100f, s.parametric[0].freq, 0f); assertEquals(3f, s.parametric[0].gain, 0f)
        assertEquals(FilterType.HighShelf, s.parametric[2].type)
        assertFalse(s.parametric[3].on)
        assertEquals(5f, s.bassDb, 1e-4f); assertEquals(2f, s.trebleDb, 1e-4f)
        assertEquals(-4f, s.preampDb, 0f)
        assertTrue(s.stereo)
        assertEquals(-0.4f, s.balance, 1e-6f); assertEquals(0.3f, s.expand, 1e-6f)
        assertEquals(ReplayGain.TRACK, s.replayGain)
    }

    @Test
    fun brokenJsonGivesDefaults() {
        assertEquals(DspSettings(), DspSettings.fromJson("non json"))
        assertEquals(DspSettings(), DspSettings.fromJson(null))
    }

    @Test
    fun eqPartRoundTrip() {
        val p = DspSettings().withGraphic(5, 3f).copy(eqMode = Modes.EQ_GRAPHIC, preampDb = -2f).eqPart()
        assertEquals(p, EqPart.fromJson(p.toJson()))
        val applied = DspSettings().withEqPart(p)
        assertTrue(applied.master)
        assertEquals(3f, applied.graphic[5], 0f)
    }

    @Test
    fun graphicPresetsHaveFifteenBands() {
        for (p in GraphicPresets.all) assertEquals(p.name, DspSettings.GRAPHIC_BANDS, p.gains.size)
    }

    @Test
    fun offModulesSendNeutralValues() {
        val s = DspSettings(master = true, tone = false, bassDb = 8f, stereo = false, surroundMode = 3, balance = 1f)
        val m = map(s.toNative())
        assertEquals("0.0000", m["bass"])
        assertEquals("0", m["surround"])
        assertEquals("0", m["stereo"])
    }

    @Test
    fun earlierV2SettingsTurnStereoOnWhenUsed() {
        assertTrue(DspSettings.fromJson("""{"v":2,"balance":0.5}""").stereo)
        assertFalse(DspSettings.fromJson("""{"v":2}""").stereo)
        assertTrue(DspSettings.fromJson("""{"v":2}""").tone)
    }
}
