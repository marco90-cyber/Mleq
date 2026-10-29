package com.marco.mleq.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTest {
    private fun info(vararg kv: Pair<String, String>): ReplayGain.Info =
        kv.fold(ReplayGain.Info()) { acc, (k, v) -> ReplayGain.apply(acc, k, v) }

    @Test
    fun parsesTagValuesInAnyCommonSpelling() {
        val i = info(
            "replaygain_track_gain" to "-7.25 dB", "REPLAYGAIN_TRACK_PEAK" to "0.988",
            "REPLAYGAIN_ALBUM_GAIN" to "-6,50 dB", "REPLAYGAIN_ALBUM_PEAK" to "1.000000",
        )
        assertEquals(-7.25, i.trackGain!!, 1e-9)
        assertEquals(0.988, i.trackPeak!!, 1e-9)
        assertEquals(-6.5, i.albumGain!!, 1e-9)
        assertNull(info("ARTIST" to "x").trackGain)
    }

    @Test
    fun opusR128IsConvertedToTheReplayGainReference() {
        // -2304 / 256 = -9 dB a -23 LUFS -> -4 dB al riferimento ReplayGain
        assertEquals(-4.0, info("R128_TRACK_GAIN" to "-2304").trackGain!!, 1e-9)
    }

    @Test
    fun gainFollowsModeFallsBackAndNeverClips() {
        val i = info("REPLAYGAIN_TRACK_GAIN" to "-8 dB", "REPLAYGAIN_ALBUM_GAIN" to "-6 dB")
        assertEquals(0.0, ReplayGain.gainDb(i, ReplayGain.OFF), 0.0)
        assertEquals(-8.0, ReplayGain.gainDb(i, ReplayGain.TRACK), 1e-9)
        assertEquals(-6.0, ReplayGain.gainDb(i, ReplayGain.ALBUM), 1e-9)
        val onlyTrack = info("REPLAYGAIN_TRACK_GAIN" to "-3 dB")
        assertEquals(-3.0, ReplayGain.gainDb(onlyTrack, ReplayGain.ALBUM), 1e-9)
        // brano tranquillo: +6 dB chiesti, ma il picco a 0.708 (-3 dB) consente solo +3 dB
        val quiet = info("REPLAYGAIN_TRACK_GAIN" to "+6 dB", "REPLAYGAIN_TRACK_PEAK" to "0.70795")
        assertEquals(3.0, ReplayGain.gainDb(quiet, ReplayGain.TRACK), 0.01)
        assertEquals(0.0, ReplayGain.gainDb(null, ReplayGain.TRACK), 0.0)
    }
}
