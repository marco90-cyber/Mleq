package com.marco.mleq.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class OutKind(val label: String) {
    Speaker("Altoparlante del telefono"),
    Wired("Cuffie / jack"),
    Bluetooth("Bluetooth"),
    Usb("DAC / audio USB"),
    Hdmi("HDMI"),
    Other("Altra uscita"),
}

/** Segue l'uscita audio attiva (altoparlante, Bluetooth, USB DAC...). */
class OutputMonitor(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var kind by mutableStateOf(OutKind.Speaker)
        private set
    var name by mutableStateOf("")
        private set

    /** true se il dispositivo è quello realmente instradato (Android 13+), false se stimato. */
    var exact by mutableStateOf(false)
        private set

    private var device: AudioDeviceInfo? = null
    private var lastSig = ""

    /** Dopo un errore di apertura dell'uscita con questo dispositivo: formato prudente. */
    @Volatile
    var safeMode = false

    /** Ultima scelta di uscita (testo per la schermata Uscita audio). */
    var lastPlan by mutableStateOf("In attesa di un brano")

    class Plan(val rate: Int, val bits: Int) {
        fun describe(fromRate: Int): String {
            val r = "${rate / 1000.0} kHz".replace(".0 ", " ")
            val src = if (fromRate != rate) " (ricampionato da ${fromRate / 1000.0} kHz)".replace(".0 ", " ") else ""
            return "$r · ${if (bits == 16) "16 bit" else "32 bit float"}$src"
        }
    }

    /**
     * Sceglie frequenza e profondità adatte al dispositivo attivo, in base a quanto dichiara Android.
     * Dichiarazione vuota = accetta tutto: nessuna conversione di frequenza.
     */
    fun plan(fileRate: Int): Plan {
        refresh()
        if (safeMode) {
            // modalità prudente (dopo un errore con questo dispositivo): 16 bit a 44,1 o 48 kHz
            val r = if (fileRate == 44100 || fileRate == 48000) fileRate else if (fileRate % 44100 == 0) 44100 else 48000
            return Plan(r, 16)
        }
        val d = device
        val rates = d?.sampleRates?.filter { it > 0 }?.sorted().orEmpty()
        val encs = d?.encodings?.toList().orEmpty()
        val target = when {
            rates.isEmpty() || fileRate in rates -> fileRate
            else -> {
                val fam44 = fileRate % 44100 == 0
                val below = rates.filter { it <= fileRate }
                below.lastOrNull { (it % 44100 == 0) == fam44 && (it % 48000 == 0) == !fam44 }
                    ?: below.lastOrNull()
                    ?: rates.first()
            }
        }
        val wide = encs.isEmpty() || encs.any {
            it == AudioFormat.ENCODING_PCM_FLOAT || it == AudioFormat.ENCODING_PCM_32BIT ||
                it == AudioFormat.ENCODING_PCM_24BIT_PACKED
        }
        return Plan(target, if (wide) 32 else 16)
    }

    /** Chiamato quando cambia il tipo di uscita (per Mobile Bass). */
    var onKindChanged: ((OutKind) -> Unit)? = null

    init {
        refresh()
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
        }, null)
    }

    fun refresh() {
        var dev: AudioDeviceInfo? = null
        var ex = false
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                dev = am.getAudioDevicesForAttributes(attrs).firstOrNull()
                ex = dev != null
            } catch (_: Throwable) {
            }
        }
        if (dev == null) {
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            dev = outs.minByOrNull { priority(kindOf(it.type)) }
        }
        device = dev
        val sig = "${dev?.type}:${dev?.productName}"
        if (sig != lastSig) {
            lastSig = sig
            safeMode = false
            if (dev != null) {
                com.marco.mleq.util.AppLog.i(
                    "Audio",
                    "Uscita attiva: ${dev.productName} (tipo ${dev.type}); frequenze=${dev.sampleRates.sorted()}; " +
                        "codifiche=${dev.encodings.sorted()}; canali=${dev.channelCounts.sorted()}"
                )
            }
        }
        val newKind = dev?.let { kindOf(it.type) } ?: OutKind.Speaker
        val changed = newKind != kind
        kind = newKind
        if (changed) onKindChanged?.invoke(newKind)
        name = dev?.productName?.toString().orEmpty()
        exact = ex
    }

    private fun priority(k: OutKind) = when (k) {
        OutKind.Usb -> 0
        OutKind.Bluetooth -> 1
        OutKind.Wired -> 2
        OutKind.Hdmi -> 3
        OutKind.Other -> 4
        OutKind.Speaker -> 5
    }

    companion object {
        fun kindOf(type: Int): OutKind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutKind.Speaker
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> OutKind.Wired
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID -> OutKind.Bluetooth
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutKind.Usb
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> OutKind.Hdmi
            else -> OutKind.Other
        }
    }
}
