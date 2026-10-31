package com.marco.mleq.audio

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.marco.mleq.data.Settings
import com.marco.mleq.data.Track
import com.marco.mleq.util.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Motore audio condiviso da interfaccia e servizio in background:
 * ExoPlayer + equalizzatore DSP + uscita Hi-Res (float a 32 bit).
 * Il lettore serve solo a provare l'equalizzatore: coda di file scelti dal selettore di Android.
 */
class AudioEngine(
    private val app: Application,
    private val settings: Settings,
) {
    val analyzer = Analyzer()
    val eqProcessor = EqAudioProcessor(analyzer)
    val outputMonitor = OutputMonitor(app)

    val player: ExoPlayer

    var currentTrack by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var queueIndex by mutableIntStateOf(0)
        private set
    var queueSize by mutableIntStateOf(0)
        private set

    /** Formato realmente aperto verso Android (AudioTrack), dichiarato da Media3. Vuoto = nessun dato. */
    var trackSampleRate by mutableIntStateOf(0)
        private set
    var trackEncoding by mutableStateOf("")
        private set
    var trackOffload by mutableStateOf(false)
        private set

    /** True mentre il brano attuale, senza tag ReplayGain, viene analizzato. */
    var scanningCurrent by mutableStateOf(false)
        private set

    private val scanExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "mleq-loudness").apply { priority = Thread.MIN_PRIORITY } }
    private var scanCancel = AtomicBoolean(false)
    private val scanQueued = HashSet<String>()

    private var queue: List<Track> = emptyList()
    private var runtimeSampleRate = 0
    private var runtimePcmBits = 0
    private var consecutiveErrors = 0
    private var controllerFuture: Any? = null

    init {
        val factory = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val inner = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
                // Il DSP gira prima del sink: il sink di Media3 salta i processori per il PCM 24/32 bit.
                return ProcessingAudioSink(inner, eqProcessor, outputMonitor) { settings.adaptiveOutput }
            }
        }
        player = ExoPlayer.Builder(app, factory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.volume = settings.volume.coerceIn(0f, 1f)

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                trackSampleRate = audioTrackConfig.sampleRate
                trackEncoding = when (audioTrackConfig.encoding) {
                    C.ENCODING_PCM_16BIT -> "PCM 16 bit"
                    C.ENCODING_PCM_24BIT -> "PCM 24 bit"
                    C.ENCODING_PCM_32BIT -> "PCM 32 bit"
                    C.ENCODING_PCM_FLOAT -> "PCM float 32 bit"
                    else -> "codifica ${audioTrackConfig.encoding}"
                }
                trackOffload = audioTrackConfig.offload
                AppLog.i("Audio", "AudioTrack aperto: $trackSampleRate Hz, $trackEncoding, offload=$trackOffload")
            }
        })

        eqProcessor.setSettings(settings.dsp)
        var lastRgMode = settings.dsp.replayGain
        settings.onDspChanged = { cfg ->
            eqProcessor.setSettings(cfg)
            if (cfg.replayGain != lastRgMode) {
                lastRgMode = cfg.replayGain
                if (cfg.replayGain != ReplayGain.OFF) planScans()
            }
        }
        // Mobile Bass agisce solo sull'altoparlante; la loudness segue il volume del telefono
        eqProcessor.speaker = outputMonitor.kind == OutKind.Speaker
        outputMonitor.onKindChanged = { k -> eqProcessor.speaker = k == OutKind.Speaker }
        updateListenVolume()
        app.contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true,
            object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = updateListenVolume()
            }
        )

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrent()
            }

            override fun onTracksChanged(tracks: Tracks) {
                for (g in tracks.groups) {
                    if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected && g.length > 0) {
                        val f = g.getTrackFormat(0)
                        runtimeSampleRate = if (f.sampleRate > 0) f.sampleRate else 0
                        runtimePcmBits = when (f.pcmEncoding) {
                            C.ENCODING_PCM_16BIT -> 16
                            C.ENCODING_PCM_24BIT -> 24
                            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                            else -> 0
                        }
                        AppLog.i(
                            "Audio",
                            "Formato: ${f.sampleMimeType} ${f.sampleRate} Hz, ${f.channelCount} ch, " +
                                "pcm=${f.pcmEncoding}, bitrate=${f.bitrate}"
                        )
                        break
                    }
                }
                updateCurrent()
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("Audio", "Errore di riproduzione: ${error.errorCodeName}", error)
                val sinkError = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                if (sinkError && settings.adaptiveOutput && !outputMonitor.safeMode) {
                    // il dispositivo ha rifiutato il formato scelto: riprovo con 16 bit a 44,1/48 kHz
                    outputMonitor.safeMode = true
                    AppLog.w("Audio", "Uscita rifiutata dal dispositivo: riprovo in modalità prudente (16 bit, 44,1/48 kHz)")
                    val pos = player.currentPosition
                    player.prepare()
                    player.seekTo(pos)
                    player.play()
                    return
                }
                consecutiveErrors++
                val bad = currentTrack
                if (bad != null) AppLog.w("Audio", "Brano non riproducibile: ${bad.title} (${bad.formatLabel})")
                if (player.hasNextMediaItem() && consecutiveErrors < 10) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    player.stop()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) consecutiveErrors = 0
            }
        })
        AppLog.i("Audio", "Motore audio pronto (uscita adattiva: ${if (settings.adaptiveOutput) "sì" else "no"})")
    }

    /** Collega il servizio in background (notifica, controlli multimediali). */
    fun connectService() {
        try {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(app, token).buildAsync()
        } catch (e: Throwable) {
            AppLog.e("Audio", "Servizio di riproduzione non collegato", e)
        }
    }

    private fun updateCurrent() {
        val idx = player.currentMediaItemIndex
        val base = queue.getOrNull(idx)
        queueIndex = idx
        queueSize = queue.size
        val prev = currentTrack?.uri
        currentTrack = base?.copy(
            sampleRate = if (runtimeSampleRate > 0) runtimeSampleRate else base.sampleRate,
            bits = if (runtimePcmBits > 0) runtimePcmBits else base.bits,
        )
        if (base?.uri != prev) {
            eqProcessor.scannedGain = base?.let { settings.loudnessCache.get(it.uri) }
            planScans()
        }
    }

    /** Volume multimediale del telefono come fattore lineare (0 dB = 1). */
    private fun updateListenVolume() {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val idx = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val db = if (android.os.Build.VERSION.SDK_INT >= 28) {
                am.getStreamVolumeDb(android.media.AudioManager.STREAM_MUSIC, idx, outputDeviceType())
            } else {
                // stima: la scala di Android copre circa 60 dB
                if (idx == 0) -60f else -60f * (1f - idx.toFloat() / max)
            }
            eqProcessor.listenVolume = if (db.isFinite()) Math.pow(10.0, db.coerceIn(-80f, 0f) / 20.0) else 1.0
        } catch (_: Throwable) {
            eqProcessor.listenVolume = 1.0
        }
    }

    private fun outputDeviceType(): Int = when (outputMonitor.kind) {
        OutKind.Speaker -> android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        OutKind.Wired -> android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        OutKind.Bluetooth -> android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        OutKind.Usb -> android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        OutKind.Hdmi -> android.media.AudioDeviceInfo.TYPE_HDMI
        OutKind.Other -> android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    /** Analizza il brano attuale e il successivo (se mancano nella cache), in background. */
    private fun planScans() {
        if (settings.dsp.replayGain == ReplayGain.OFF) {
            scanningCurrent = false
            return
        }
        val idx = player.currentMediaItemIndex
        scanningCurrent = false
        for (i in listOf(idx, idx + 1)) {
            val t = queue.getOrNull(i) ?: continue
            if (settings.loudnessCache.get(t.uri) != null) continue
            if (i == idx) scanningCurrent = true
            if (!scanQueued.add(t.uri)) continue
            val cancel = scanCancel
            scanExecutor.execute {
                val r = if (cancel.get()) null else LoudnessScanner.scan(app, t.uri, cancel)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    scanQueued.remove(t.uri)
                    if (r != null) {
                        settings.loudnessCache.put(t.uri, r)
                        AppLog.i("Audio", "Loudness di ${t.title}: ${"%.1f".format(r.lufs)} LUFS, picco ${"%.3f".format(r.peak)}")
                        if (currentTrack?.uri == t.uri) eqProcessor.scannedGain = LoudnessScanner.toInfo(r)
                    } else if (!cancel.get()) {
                        AppLog.w("Audio", "Loudness di ${t.title}: analisi non riuscita")
                    }
                    if (currentTrack?.uri == t.uri) scanningCurrent = false
                }
            }
        }
    }

    // ------------------------------------------------------------------ comandi

    private fun describe(uri: Uri): Track {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
            }
        } catch (_: Exception) {
        }
        val ext = if ('.' in name) name.substringAfterLast('.').take(5) else ""
        return Track(uri = uri.toString(), title = name.substringBeforeLast('.'), ext = ext)
    }

    /** Sostituisce la coda con i file scelti e avvia la riproduzione dal primo. */
    fun playUris(uris: List<Uri>, startIndex: Int = 0) {
        if (uris.isEmpty()) return
        scanCancel.set(true)            // annulla le analisi della coda precedente
        scanCancel = AtomicBoolean(false)
        scanQueued.clear()
        queue = uris.map { describe(it) }
        runtimeSampleRate = 0
        runtimePcmBits = 0
        consecutiveErrors = 0
        val items = queue.map { t ->
            MediaItem.Builder()
                .setMediaId(t.uri)
                .setUri(t.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist("MLEqualizer").build())
                .build()
        }
        player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        player.prepare()
        player.play()
        updateCurrent()
        AppLog.i("Audio", "Coda di ${queue.size} file, avvio da #$startIndex (${queue[startIndex.coerceIn(0, queue.lastIndex)].title})")
    }

    fun togglePlayPause() {
        if (player.mediaItemCount == 0) return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    fun next() {
        if (player.mediaItemCount > 0) player.seekToNext()
    }

    fun previous() {
        if (player.mediaItemCount > 0) player.seekToPrevious()
    }

    fun seekToFraction(frac: Float) {
        val dur = player.duration
        if (dur > 0) player.seekTo((dur * frac.coerceIn(0f, 1f)).toLong())
    }
}
