package com.marco.mleq.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.marco.mleq.data.Settings
import com.marco.mleq.util.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Feedback tattile e sonoro per schermo touch e ghiera.
 * Il "click" è sintetizzato al volo (nessun file audio da includere).
 */
class Feedback(context: Context, private val settings: Settings) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    private var pool: SoundPool? = null
    private var clickId = 0
    private var ready = false

    init {
        try {
            val wav = File(context.cacheDir, "click.wav")
            wav.writeBytes(buildClickWav())
            val sp = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            sp.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
            clickId = sp.load(wav.path, 1)
            pool = sp
        } catch (e: Throwable) {
            AppLog.w("Feedback", "Suono del click non disponibile", e)
        }
    }

    fun tapScreen() {
        if (settings.hapticScreen) tick()
        if (settings.soundScreen) click()
    }

    /** Piccola vibrazione leggera (un passo di 1 dB degli slider), senza suono. */
    fun softTick() {
        if (!settings.hapticScreen) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            else VibrationEffect.createOneShot(6, 60)
            v.vibrate(effect)
        } catch (e: Throwable) {
            AppLog.d("Feedback", "Vibrazione non riuscita: ${e.message}")
        }
    }

    /** "Scatto" di centraggio (zero dello slider/manopola): vibrazione più lunga e netta. */
    fun detent() {
        if (settings.hapticScreen) {
            val v = vibrator
            if (v != null && v.hasVibrator()) {
                try {
                    v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                } catch (e: Throwable) {
                    AppLog.d("Feedback", "Vibrazione non riuscita: ${e.message}")
                }
            }
        }
        if (settings.soundScreen) click()
    }

    private fun tick(strong: Boolean = false) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val level = if (strong) maxOf(settings.hapticStrength, 1) else settings.hapticStrength
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(
                    when (level) {
                        0 -> VibrationEffect.EFFECT_TICK
                        1 -> VibrationEffect.EFFECT_CLICK
                        else -> VibrationEffect.EFFECT_HEAVY_CLICK
                    }
                )
            } else {
                when (level) {
                    0 -> VibrationEffect.createOneShot(6, 60)
                    1 -> VibrationEffect.createOneShot(10, 130)
                    else -> VibrationEffect.createOneShot(16, 255)
                }
            }
            v.vibrate(effect)
        } catch (e: Throwable) {
            AppLog.d("Feedback", "Vibrazione non riuscita: ${e.message}")
        }
    }

    private fun click() {
        val sp = pool ?: return
        if (!ready) return
        val vol = when (settings.clickVolume) {
            0 -> 0.06f
            1 -> 0.15f
            else -> 0.30f
        }
        try {
            sp.play(clickId, vol, vol, 1, 0, 1f)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            pool?.release()
        } catch (_: Throwable) {
        }
        pool = null
    }

    /** WAV mono 16 bit a 44,1 kHz: un breve "tic" metallico come la ghiera dell'iPod. */
    private fun buildClickWav(): ByteArray {
        val sampleRate = 44100
        val n = (sampleRate * 0.03).toInt()
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        val rnd = Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate
            val env = exp(-t * 220.0)
            val tone = sin(2.0 * PI * 2300.0 * t) * 0.55 + sin(2.0 * PI * 4100.0 * t) * 0.25
            val noise = (rnd.nextDouble() * 2.0 - 1.0) * 0.35 * exp(-t * 600.0)
            val s = ((tone + noise) * env).coerceIn(-1.0, 1.0)
            pcm.putShort((s * 16000).toInt().toShort())
        }
        val data = pcm.array()
        val out = ByteArrayOutputStream()
        fun w32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun w16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray()); w32(36 + data.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); w32(16); w16(1); w16(1)
        w32(sampleRate); w32(sampleRate * 2); w16(2); w16(16)
        out.write("data".toByteArray()); w32(data.size)
        out.write(data)
        return out.toByteArray()
    }
}
