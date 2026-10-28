package com.marco.mleq.data

/** Brano aperto per provare l'equalizzatore. Frequenza e profondità arrivano dal decoder (0 = non note). */
data class Track(
    val uri: String,
    val title: String,
    val ext: String,
    val sampleRate: Int = 0,
    val bits: Int = 0,
) {
    val formatLabel: String get() = ext.uppercase().ifEmpty { "AUDIO" }

    /** Hi-Res: oltre 48 kHz oppure più di 16 bit. */
    val isHiRes: Boolean get() = sampleRate > 48000 || bits > 16
}
