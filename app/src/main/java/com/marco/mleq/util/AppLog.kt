package com.marco.mleq.util

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log dell'app: tiene le ultime righe in memoria, le scrive su file e le mostra
 * nel menu Debug. Il file si può esportare/condividere.
 */
object AppLog {
    private const val MAX_LINES = 2000
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY)

    /** Se vero registra anche i messaggi di dettaglio (tocchi, ghiera, ecc.). */
    @Volatile
    var verbose: Boolean = false

    /** Cambia a ogni nuova riga: serve alla UI per aggiornarsi. */
    var revision by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        val f = File(context.filesDir, "mleq.log")
        file = f
        try {
            if (f.exists() && f.length() > 512 * 1024) f.delete()
        } catch (_: Exception) {
        }
        i(
            "App",
            "MLEqualizer avviata - Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    fun d(tag: String, msg: String) {
        if (verbose) add("D", tag, msg)
    }

    fun i(tag: String, msg: String) = add("I", tag, msg)

    fun w(tag: String, msg: String, t: Throwable? = null) =
        add("W", tag, if (t != null) "$msg (${t.javaClass.simpleName}: ${t.message})" else msg)

    fun e(tag: String, msg: String, t: Throwable? = null) =
        add("E", tag, if (t != null) "$msg (${t.javaClass.simpleName}: ${t.message})" else msg)

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            android.util.Log.println(
                when (level) {
                    "E" -> android.util.Log.ERROR
                    "W" -> android.util.Log.WARN
                    "D" -> android.util.Log.DEBUG
                    else -> android.util.Log.INFO
                },
                "MLEqualizer/$tag",
                msg
            )
            file?.appendText(line + "\n")
        } catch (_: Exception) {
        }
        revision++
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
        revision++
    }

    /** Scrive il log in un file temporaneo condivisibile. */
    fun exportFile(context: Context): File {
        val out = File(context.cacheDir, "mleq-log.txt")
        out.writeText(text() + "\n")
        return out
    }
}
