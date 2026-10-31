package com.marco.mleq.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetTextTest {
    @Test
    fun autoEqFiltersBecomeParametricBands() {
        val t = """
            Preamp: -6,4 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70
            Filter 2: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41
            Filter 3: OFF PK Fc 3000 Hz Gain 4.0 dB Q 2.00
            Filter 4: ON HP Fc 25 Hz Q 0.71
        """.trimIndent()
        val p = PresetText.parse(t)
        assertNotNull(p)
        p!!
        assertEquals(Modes.EQ_PARAMETRIC, p.mode)
        assertEquals(-6.4f, p.preampDb, 1e-4f)
        assertEquals(FilterType.LowShelf, p.parametric[0].type)
        assertEquals(105f, p.parametric[0].freq, 0f)
        assertEquals(6.5f, p.parametric[0].gain, 0f)
        assertEquals(1.41f, p.parametric[1].q, 1e-6f)
        assertFalse(p.parametric[2].on)
        assertEquals(FilterType.HighPass, p.parametric[3].type)
        assertFalse(p.parametric[4].on)
    }

    @Test
    fun graphicEqLineBecomesTheFifteenBandCurve() {
        val p = PresetText.parse("GraphicEQ: 20 2; 100 4; 1000 0; 10000 -3; 20000 -3")!!
        assertEquals(Modes.EQ_GRAPHIC, p.mode)
        assertEquals(4f, p.graphic[3], 1e-4f)          // 100 Hz
        assertEquals(0f, p.graphic[8], 1e-4f)          // 1 kHz
        assertEquals(-3f, p.graphic[13], 1e-4f)        // 10 kHz
    }

    @Test
    fun powerampJsonIsRead() {
        val json = """[{"name":"Prova","preamp":-3,"bands":[{"type":2,"frequency":500,"q":1.5,"gain":3},{"type":0,"frequency":80,"q":0,"gain":4}]}]"""
        val l = PresetText.parseMulti(json, "x")
        assertEquals(1, l.size)
        assertEquals("Prova", l[0].first)
        val p = l[0].second
        assertEquals(500f, p.parametric[0].freq, 0f)
        assertEquals(FilterType.LowShelf, p.parametric[1].type)
        assertEquals(1f, p.parametric[1].q, 0f)  // Q mancante -> 1
    }

    @Test
    fun exportAndImportAgree() {
        val s = DspSettings(eqMode = Modes.EQ_PARAMETRIC, preampDb = -2f)
            .withBand(0, PeqBand(true, FilterType.Peak, 250f, 3f, 2f))
        val text = PresetText.export(s.eqPart())
        val back = PresetText.parse(text)!!
        assertEquals(250f, back.parametric[0].freq, 0.5f)
        assertEquals(3f, back.parametric[0].gain, 0.05f)
        assertTrue(text.startsWith("Preamp: -2.0 dB"))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(PresetText.parse("ciao"))
        assertTrue(PresetText.parseMulti("[{\"x\":1}]", "x").isEmpty())
    }
}
