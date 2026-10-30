package com.marco.mleq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

private val PanelColor: Color get() = Ml.panel

private fun fmtTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return String.format(Locale.ITALY, "%d:%02d", s / 60, s % 60)
}

/**
 * Mini lettore per provare l'equalizzatore: scegli uno o più file, ascolti con la curva attiva.
 * Mostra frequenza e profondità realmente lette dal decoder.
 */
@Composable
fun PlayerBar(env: AppEnv) {
    val engine = env.engine
    val track = engine.currentTrack
    var progress by remember { mutableFloatStateOf(0f) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }

    // la posizione si legge dal player a intervalli: nessun listener da gestire
    LaunchedEffect(track, engine.isPlaying) {
        while (true) {
            if (!dragging) {
                val d = engine.player.duration
                duration = if (d > 0) d else 0L
                position = engine.player.currentPosition
                progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            }
            delay(400)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(PanelColor)
            .padding(horizontal = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { env.feedback.tapScreen(); env.actions?.pickFiles() }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Scegli file audio", tint = NeonGreen)
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = track == null) { env.actions?.pickFiles() }
            ) {
                Text(
                    track?.title ?: "Scegli un file per provare l'equalizzatore",
                    color = Ml.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (track != null) {
                    Text(
                        trackLine(track.formatLabel, track.sampleRate, track.bits, engine.queueIndex, engine.queueSize),
                        color = Ml.textDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { env.feedback.tapScreen(); engine.previous() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Precedente", tint = Ml.text)
            }
            IconButton(onClick = { env.feedback.tapScreen(); engine.togglePlayPause() }, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (engine.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (engine.isPlaying) "Pausa" else "Riproduci",
                    tint = Ml.text, modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = { env.feedback.tapScreen(); engine.next() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Successivo", tint = Ml.text)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(position), color = Ml.textDim, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            NeonSlider(
                value = progress, valueRange = 0f..1f,
                onValueChange = { v ->
                    dragging = true
                    progress = v
                    position = (duration * v).toLong()
                    engine.seekToFraction(v)
                    dragging = false
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
            )
            Text(fmtTime(duration), color = Ml.textDim, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

private fun trackLine(format: String, rate: Int, bits: Int, index: Int, size: Int): String {
    val parts = ArrayList<String>()
    parts.add(format)
    if (rate > 0) parts.add(if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format(Locale.ITALY, "%.1f kHz", rate / 1000.0))
    if (bits > 0) parts.add("$bits bit")
    if (size > 1) parts.add("${index + 1}/$size")
    return parts.joinToString(" · ")
}
