package com.marco.mleq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mleq.MLEqApp

/** Schermate a tutto schermo sopra l'equalizzatore. */
enum class Overlay { None, Analyzer, Output, Log, Graphic, Parametric }

/** Azioni che richiedono l'Activity (selettore file, condivisione del log). */
interface Actions {
    fun pickFiles()
    fun shareLog()
}

/** Stato globale dell'interfaccia. */
class AppEnv(val app: MLEqApp) {
    val settings get() = app.settings
    val engine get() = app.engine
    val feedback get() = app.feedback

    var actions: Actions? = null

    var overlay by mutableStateOf(Overlay.None)

    /** Motore "ombra" che calcola la curva mostrata nei grafici. */
    val preview by lazy { com.marco.mleq.audio.DspPreview() }

    /** Banda selezionata nell'editor parametrico. */
    var peqSelected by mutableIntStateOf(0)

    /** Banda dell'analizzatore selezionata (0..30, 17 = 1 kHz) e richiesta di azzeramento picchi. */
    var analyzerBand by mutableIntStateOf(17)
    var analyzerResetToken by mutableIntStateOf(0)
}
