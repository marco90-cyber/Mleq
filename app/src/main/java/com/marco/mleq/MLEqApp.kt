package com.marco.mleq

import android.app.Application
import com.marco.mleq.audio.AudioEngine
import com.marco.mleq.data.Settings
import com.marco.mleq.feedback.Feedback
import com.marco.mleq.ui.AppEnv
import com.marco.mleq.util.AppLog

class MLEqApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var engine: AudioEngine
        private set
    lateinit var feedback: Feedback
        private set

    /** Stato dell'interfaccia: sopravvive alla ricreazione dell'Activity. */
    val env: AppEnv by lazy { AppEnv(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        settings = Settings(this)
        AppLog.verbose = settings.verboseLog
        feedback = Feedback(this, settings)
        engine = AudioEngine(this, settings)
        engine.connectService()
    }
}
