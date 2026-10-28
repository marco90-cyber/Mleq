package com.marco.mleq.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PresetTextTest {
    @Test
    fun parsesAutoEqText() {
        val t = "Preamp: -6.4 dB\nFilter 1: ON LS Fc 105 Hz Gain 6.5 dB Q 0.70\nFilter 2: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.41\nFilter 3: OFF PK Fc 3000 Hz Gain 3.0 dB Q 2\nFilter 4: ON HS Fc 8000 Hz Gain 12 dB Q 0.7\n"
        val c = PresetText.parse(t, EqConfig())!!
        assertEquals(-6.4f, c.preampDb, 1e-4f)
        assertEquals(0, c.bassPercent)   // il tono resta separato dall'equalizzatore
        assertEquals(0, c.treblePercent)
        assertEquals(3, c.bandCount)
        assertEquals(0, c.typeAt(0))
        assertEquals(105f, c.freqs[0], 1e-3f)
        assertEquals(2, c.typeAt(1))
        assertEquals(1, c.typeAt(2))
    }

    @Test
    fun exportRoundTrip() {
        val c = EqConfig(enabled = true).withGain(2, 4f).withGain(5, -3f).copy(preampDb = -2f)
        val r = PresetText.parse(PresetText.export(c), EqConfig())!!
                assertEquals(-2f, r.preampDb, 1e-4f)
        assertEquals(2, r.freqs.indices.count { r.gains[it] != 0f })
    }

    @Test
    fun rejectsGarbage() = assertNull(PresetText.parse("ciao", EqConfig()))

    @Test
    fun graphicEq() = assertNotNull(PresetText.parse("GraphicEQ: 20 0; 1000 3; 20000 -2", EqConfig()))
}

class PowerampJsonTest {
    @Test
    fun importsPowerampJson() {
        val j = """[{"name":"One\n\nOdio","preamp":4.86,"parametric":false,"bands":[
          {"type":0,"channels":0,"frequency":90,"q":0.8,"gain":2.46,"color":0},
          {"type":1,"channels":0,"frequency":10000,"q":0.6,"gain":5.15,"color":0},
          {"type":2,"channels":0,"frequency":16,"q":0.0,"gain":1.6,"color":0},
          {"type":2,"channels":0,"frequency":1000,"q":0.0,"gain":-1.0,"color":0},
          {"type":2,"channels":0,"frequency":20000,"q":0.0,"gain":6.0,"color":0}]}]"""
        val l = PresetText.parseMulti(j, EqConfig(), "x")
        assertEquals(1, l.size)
        assertEquals("One Odio", l[0].first)
        val c = l[0].second
        assertEquals(5, c.bandCount)
        assertEquals(0, c.bassPercent)
        assertEquals(0, c.typeAt(1))
        assertEquals(1, c.typeAt(3))
        assertEquals(4.86f, c.preampDb, 1e-3f)
        assert(c.qs.all { it > 0.2f })
    }

    @Test
    fun exportJsonRoundTrip() {
        val c = EqConfig(enabled = true).withGain(3, 2f).copy(preampDb = -1f)
        val r = PresetText.parseMulti(PresetText.exportPoweramp("T", c), EqConfig(), "x")[0].second
                assertEquals(10, r.bandCount)
        assertEquals(2f, r.gains[3], 1e-3f)
    }
}
