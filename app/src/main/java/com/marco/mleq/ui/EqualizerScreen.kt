package com.marco.mleq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.marco.mleq.audio.DspInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mleq.audio.EqConfig
import com.marco.mleq.audio.ReplayGain
import com.marco.mleq.audio.EqMath
import com.marco.mleq.audio.EqPresets
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val BgColor = Color(0xFF0C0C0E)
private val PanelColor = Color(0xFF17181C)
private val ChipColor = Color(0xFF26282E)
private val Accent = Color(0xFF00E5FF)
private val BassColor = Color(0xFF2979FF)

private sealed class FreqTarget {
    data class Band(val index: Int) : FreqTarget()
    data object Bass : FreqTarget()
    data object Treble : FreqTarget()
}

private fun fmtDb(v: Float): String = String.format(Locale.ITALY, "%.1f", v)

private fun fmtFreqShort(f: Float): String =
    if (f >= 1000f) {
        val k = f / 1000f
        (if (abs(k - k.roundToInt()) < 0.05f) "${k.roundToInt()}" else String.format(Locale.ITALY, "%.1f", k)) + "k"
    } else "${f.roundToInt()}"

private fun fmtFreqLong(f: Float): String =
    if (f >= 1000f) String.format(Locale.ITALY, "%.2f kHz", f / 1000f) else "${f.roundToInt()} Hz"

@Composable
fun EqualizerScreen(env: AppEnv) {
    val settings = env.settings
    val eq = settings.eq
    val info = env.engine.eqProcessor.info
    var mode by remember { mutableIntStateOf(0) } // 0 = Eq., 1 = Tono, 2 = Limite
    var target by remember { mutableStateOf<FreqTarget?>(null) }
    var presetsDialog by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var deletePreset by remember { mutableStateOf<String?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val ok = try {
                val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                var name = "Importato"
                run {
                    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0).substringBeforeLast('.')
                            .replace(Regex("(?i)_?(parametric|fixedband)eq$"), "")
                    }
                }
                val list = com.marco.mleq.audio.PresetText.parseMulti(text, settings.eq, name)
                if (list.isNotEmpty()) {
                    for ((n, c) in list) settings.saveUserPreset(n, c)
                    settings.eq = list.last().second
                    true
                } else false
            } catch (_: Exception) { false }
            android.widget.Toast.makeText(ctx, if (ok) "Preset importato" else "File non riconosciuto", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = try {
                ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(com.marco.mleq.audio.PresetText.exportPoweramp("MLEqualizer", settings.eq))
                }
                true
            } catch (_: Exception) { false }
            android.widget.Toast.makeText(ctx, if (ok) "Preset esportato" else "Esportazione non riuscita", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val scroll = rememberScrollState()
    var viewW by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgColor)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp)
    ) {
        // barra superiore
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(8.dp))
            Text(
                "MLEqualizer", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Text(if (eq.enabled) "Attivo" else "Spento", color = Color(0xFF9AA0AA), fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            NeonSwitch(
                checked = eq.enabled,
                onCheckedChange = { on ->
                    env.feedback.tapScreen()
                    settings.updateEq { it.copy(enabled = on) }
                }
            )
            Spacer(Modifier.width(4.dp))
        }

        PlayerBar(env)
        Spacer(Modifier.height(6.dp))

        // preamp fisso + bande scorrevoli
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(
                Modifier
                    .width(54.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelColor)
                    .padding(vertical = 6.dp)
            ) {
                BandColumn(
                    label = "Preamp", value = eq.preampDb, color = Color(0xFFB0B4BE),
                    modifier = Modifier.fillMaxSize(),
                    onChange = { v -> settings.updateEq { it.copy(preampDb = v) } },
                    onReset = { settings.updateEq { it.copy(preampDb = 0f) } },
                    onDetent = { env.feedback.detent() },
                    onStep = { env.feedback.softTick() },
                    onLabelClick = null,
                )
            }
            Spacer(Modifier.width(6.dp))
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(PanelColor)
                    .onSizeChanged { viewW = it.width }
            ) {
                val colW = maxOf(40.dp, maxWidth / minOf(eq.bandCount, 9))
                Row(
                    Modifier
                        .fillMaxHeight()
                        .horizontalScroll(scroll)
                        .padding(vertical = 6.dp),
                ) {
                    for (i in 0 until eq.bandCount) {
                        BandColumn(
                            label = fmtFreqShort(eq.freqs[i]).uppercase(), value = eq.gains[i], color = NeonGreen,
                            modifier = Modifier.width(colW),
                            onChange = { v -> settings.updateEq { it.withGain(i, v) } },
                            onReset = { settings.updateEq { it.withGain(i, 0f) } },
                            onDetent = { env.feedback.detent() },
                            onStep = { env.feedback.softTick() },
                            onLabelClick = { target = FreqTarget.Band(i) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        // curva di risposta con finestra di scorrimento
        ResponseGraph(
            eq = eq, scroll = scroll, viewW = viewW,
            modifier = Modifier.fillMaxWidth().height(58.dp)
        )
        Spacer(Modifier.height(4.dp))

        // stato reale dell'uscita DSP
        Text(
            text = dspLine(info),
            color = Color(0xFF8E96A8), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))

        // comandi: modalità a sinistra, contenuto a destra
        Row(Modifier.fillMaxWidth().height(272.dp)) {
            Column(
                Modifier.width(78.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeButton("Eq.", mode == 0, Modifier.weight(1f)) { env.feedback.tapScreen(); mode = 0 }
                ModeButton("Tono", mode == 1, Modifier.weight(1f)) { env.feedback.tapScreen(); mode = 1 }
                ModeButton("Stereo", mode == 4, Modifier.weight(1f)) { env.feedback.tapScreen(); mode = 4 }
                ModeButton("Param.", mode == 3, Modifier.weight(1f)) { env.feedback.tapScreen(); mode = 3 }
                ModeButton("Limite", mode == 2, Modifier.weight(1f)) { env.feedback.tapScreen(); mode = 2 }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, Color(0xFF5A5E68), RoundedCornerShape(50))
                            .clickable { env.feedback.tapScreen(); presetsDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Preimpostazioni", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, Color(0xFF5A5E68), RoundedCornerShape(50))
                                .clickable { env.feedback.tapScreen(); moreMenu = true },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.MoreVert, "Altro", tint = Color.White) }
                        DropdownMenu(
                            expanded = moreMenu, onDismissRequest = { moreMenu = false },
                            modifier = Modifier.background(PanelColor)
                        ) {
                            MenuRow("Azzera curva") {
                                settings.updateEq { c -> c.copy(gains = List(c.bandCount) { 0f }, preampDb = 0f) }
                                moreMenu = false
                            }
                            MenuRow("Salva come preimpostazione") { saveName = ""; saveDialog = true; moreMenu = false }
                            MenuRow("Analizzatore") { env.overlay = Overlay.Analyzer; moreMenu = false }
                            MenuRow("Uscita audio") { env.overlay = Overlay.Output; moreMenu = false }
                            MenuRow("Log di debug") { env.overlay = Overlay.Log; moreMenu = false }
                            MenuRow("Aggiornamenti") { com.marco.mleq.AppUpdate.open(env.app); moreMenu = false }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (mode) {
                        0 -> BandsPanel(env, eq)
                        1 -> TonePanel(env, eq) { target = it }
                        3 -> ParamsPanel(env, eq)
                        4 -> StereoPanel(env, eq)
                        else -> LimiterPanel(env, eq)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    target?.let { t ->
        FreqDialog(target = t, eq = eq, onDismiss = { target = null }) { newEq -> settings.eq = newEq }
    }

    if (presetsDialog) {
        AlertDialog(
            onDismissRequest = { presetsDialog = false },
            containerColor = Color(0xFF1E1F24),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("Preimpostazioni") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    PresetRow("+ Salva la configurazione attuale", accent = true) {
                        presetsDialog = false; saveName = ""; saveDialog = true
                    }
                    PresetRow("Importa da file (Poweramp .json / AutoEQ .txt)", accent = true) {
                        presetsDialog = false
                        importLauncher.launch(arrayOf("text/plain", "application/json", "application/octet-stream", "*/*"))
                    }
                    PresetRow("Esporta la configurazione attuale", accent = true) {
                        presetsDialog = false
                        exportLauncher.launch("MLEqualizer_preset.json")
                    }
                    val mine = settings.userPresets
                    if (mine.isNotEmpty()) {
                        Text("Le mie", color = Color(0xFF9AA0AA), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        for (up in mine) {
                            PresetRow(up.name, onDelete = { deletePreset = up.name }) {
                                settings.eq = up.config.copy(enabled = true, bassPercent = eq.bassPercent, bassFreq = eq.bassFreq, treblePercent = eq.treblePercent, trebleFreq = eq.trebleFreq)
                                presetsDialog = false
                            }
                        }
                    }
                    Text("Di fabbrica", color = Color(0xFF9AA0AA), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    for (p in EqPresets.all) {
                        PresetRow(p.name) {
                            settings.updateEq { it.withCurve(p.points).copy(enabled = true) }
                            presetsDialog = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { presetsDialog = false }) { Text("Chiudi") } }
        )
    }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            containerColor = Color(0xFF1E1F24),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("Salva preimpostazione") },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it.take(24) },
                    singleLine = true,
                    label = { Text("Nome") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Accent, unfocusedLabelColor = Color(0xFF9AA0AA),
                        focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF4A4E58),
                        cursorColor = Accent,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = saveName.isNotBlank(),
                    onClick = { settings.saveUserPreset(saveName, eq); saveDialog = false }
                ) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Annulla") } }
        )
    }

    deletePreset?.let { name ->
        AlertDialog(
            onDismissRequest = { deletePreset = null },
            containerColor = Color(0xFF1E1F24),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = { Text("Elimina preimpostazione") },
            text = { Text("Vuoi eliminare \"$name\"?") },
            confirmButton = {
                TextButton(onClick = { settings.deleteUserPreset(name); deletePreset = null }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { deletePreset = null }) { Text("Annulla") } }
        )
    }
}

internal val NeonGreen = Color(0xFF00E676)

/** Interruttore nel tema dell'equalizzatore (verde neon su grafite). */
@Composable
private fun NeonSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .background(if (checked) NeonGreen else Color(0xFF3A3D45))
            .clickable { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) Color(0xFF0C0C0E) else Color(0xFF9AA0AA))
        )
    }
}

/** Slider orizzontale sottile, con linea neon e maniglia chiara come gli slider delle bande. */
@Composable
internal fun NeonSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val change = rememberUpdatedState(onValueChange)
    val lo = valueRange.start
    val hi = valueRange.endInclusive
    fun valueAt(x: Float, w: Float, pad: Float): Float {
        val frac = ((x - pad) / (w - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return lo + frac * (hi - lo)
    }
    Canvas(
        modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { o -> change.value(valueAt(o.x, this.size.width.toFloat(), 14.dp.toPx())) }
            }
            .pointerInput(Unit) {
                detectDragGestures { c, _ ->
                    c.consume()
                    change.value(valueAt(c.position.x, this.size.width.toFloat(), 14.dp.toPx()))
                }
            }
    ) {
        val pad = 14.dp.toPx()
        val cy = this.size.height / 2f
        val frac = ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
        val xv = pad + frac * (this.size.width - 2 * pad)
        drawLine(Color(0xFF3A3D45), Offset(pad, cy), Offset(this.size.width - pad, cy), 3.dp.toPx(), StrokeCap.Round)
        drawLine(NeonGreen.copy(alpha = 0.25f), Offset(pad, cy), Offset(xv, cy), 8.dp.toPx(), StrokeCap.Round)
        drawLine(NeonGreen, Offset(pad, cy), Offset(xv, cy), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(Color(0xFF26282E), 11.dp.toPx(), Offset(xv, cy))
        drawCircle(Color(0xFF4A4E58), 11.dp.toPx(), Offset(xv, cy), style = Stroke(1.dp.toPx()))
        drawCircle(Color(0xFFE8EAF0), 4.dp.toPx(), Offset(xv, cy))
    }
}

/** Riga di stato: solo valori realmente in uso dal motore DSP. */
private fun dspLine(i: DspInfo): String {
    if (i.sampleRate <= 0) return "DSP IN ATTESA DI UN BRANO"
    val fmt = i.outEncoding.removePrefix("PCM ").uppercase()
    val hz = if (i.sampleRate % 1000 == 0) "${i.sampleRate / 1000} KHZ" else String.format(Locale.ITALY, "%.1f KHZ", i.sampleRate / 1000.0)
    val state = when {
        i.bypass -> "BYPASS"
        i.limiter -> "DSP + LIMITE"
        else -> "DSP"
    }
    return "$state · USCITA $fmt · $hz"
}

@Composable
private fun ModeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) NeonGreen else ChipColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (selected) Color(0xFF0C0C0E) else Color.White, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, color = Color.White) }, onClick = onClick)
}

@Composable
private fun PresetRow(text: String, accent: Boolean = false, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text, color = if (accent) Accent else Color.White, fontSize = 16.sp,
            fontWeight = if (accent) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, "Elimina", tint = Color(0xFF9AA0AA))
            }
        }
    }
}

/** Modalità "Eq.": numero di bande e scorciatoie. */
@Composable
private fun BandsPanel(env: AppEnv, eq: EqConfig) {
    val settings = env.settings
    var menu by remember { mutableStateOf(false) }
    val options = (listOf(5, 10, 15, 31) + eq.bandCount).distinct().sorted()
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(PanelColor).padding(12.dp)) {
        Text("Numero di bande", color = Color(0xFF9AA0AA), fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(ChipColor)
                    .clickable { env.feedback.tapScreen(); menu = true }
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${eq.bandCount} bande", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(PanelColor)) {
                for (n in options) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "$n bande",
                                color = if (n == eq.bandCount) Accent else Color.White,
                                fontWeight = if (n == eq.bandCount) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            env.feedback.tapScreen()
                            settings.updateEq { it.withBandCount(n) }
                            menu = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Tocca la frequenza sotto uno slider per scrivere frequenza, guadagno e Q o cambiare il tipo di filtro. Doppio tocco: azzera la banda.",
            color = Color(0xFF7C828C), fontSize = 12.sp
        )
    }
}

/** Modalità "Tono": manopole Bassi / Alti e headroom. */
@Composable
private fun TonePanel(env: AppEnv, eq: EqConfig, onFreq: (FreqTarget) -> Unit) {
    val settings = env.settings
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(PanelColor).padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToneKnob(
                title = "Bassi caldi", percent = eq.bassPercent, freq = eq.bassFreq, color = BassColor,
                onTick = { env.feedback.tapScreen() },
                onDetent = { env.feedback.detent() },
                onChange = { v -> settings.updateEq { it.copy(bassPercent = v) } },
                onFreqClick = { onFreq(FreqTarget.Bass) },
            )
            ToneKnob(
                title = "Alti freddi", percent = eq.treblePercent, freq = eq.trebleFreq, color = Accent,
                onTick = { env.feedback.tapScreen() },
                onDetent = { env.feedback.detent() },
                onChange = { v -> settings.updateEq { it.copy(treblePercent = v) } },
                onFreqClick = { onFreq(FreqTarget.Treble) },
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val peak = eq.tonePeakDb()
            Text("Headroom", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (!eq.toneHeadroom) "spento" else if (peak < 0.05) "automatico"
                else String.format(Locale.ITALY, "-%.1f dB", peak),
                color = Color(0xFF7C828C), fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f)
            )
            NeonSwitch(
                checked = eq.toneHeadroom,
                onCheckedChange = { on -> env.feedback.tapScreen(); settings.updateEq { it.copy(toneHeadroom = on) } }
            )
        }
    }
}

/** Modalità "Stereo": bilanciamento, espansione e mono/stereo. */
@Composable
private fun StereoPanel(env: AppEnv, eq: EqConfig) {
    val settings = env.settings
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(PanelColor).padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bal = eq.balance
            StereoKnob(
                title = "Bilanciamento",
                valueText = when {
                    bal == 0 -> "Centro"
                    bal < 0 -> "S ${-bal}%"
                    else -> "D $bal%"
                },
                knob = (bal + 100) / 2, color = BassColor,
                onTick = { env.feedback.tapScreen() }, onDetent = { env.feedback.detent() },
                onChange = { v -> settings.updateEq { it.copy(balance = ((v - 50) * 2).coerceIn(-100, 100)) } },
                onReset = { env.feedback.tapScreen(); settings.updateEq { it.copy(balance = 0) } },
            )
            StereoKnob(
                title = "Espansione",
                valueText = if (eq.mono) "Mono" else "${eq.stereoExpand}%",
                knob = eq.stereoExpand, color = Accent, zero = 0,
                onTick = { env.feedback.tapScreen() }, onDetent = { env.feedback.detent() },
                onChange = { v -> settings.updateEq { it.copy(stereoExpand = v.coerceIn(0, 100), mono = false) } },
                onReset = { env.feedback.tapScreen(); settings.updateEq { it.copy(stereoExpand = 0) } },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Chip("Stereo", selected = !eq.mono) { env.feedback.tapScreen(); settings.updateEq { it.copy(mono = false) } }
            Chip("Mono", selected = eq.mono) { env.feedback.tapScreen(); settings.updateEq { it.copy(mono = true) } }
        }
    }
}

@Composable
private fun StereoKnob(
    title: String,
    valueText: String,
    knob: Int,
    color: Color,
    zero: Int = 50,
    onTick: () -> Unit,
    onDetent: () -> Unit,
    onChange: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(valueText, color = Color(0xFF9AA0AA), fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Knob(value = knob, color = color, size = 68.dp, onTick = onTick, onDetent = onDetent, zero = zero, stepSize = 5, onChange = onChange)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(ChipColor)
                .clickable(onClick = onReset)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) { Text("Azzera", color = Color.White, fontSize = 12.sp) }
    }
}

/** Modalità "Param.": Q automatico, curva fedele e preamp a mano. */
@Composable
private fun ParamsPanel(env: AppEnv, eq: EqConfig) {
    val settings = env.settings
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Q automatico", color = Color.White, fontSize = 14.sp)
                Text("Larghezza delle bande dalla loro distanza", color = Color(0xFF7C828C), fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Chip("Applica", selected = false) { env.feedback.tapScreen(); settings.updateEq { it.withAutoQ() } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Curva fedele", color = Color.White, fontSize = 14.sp)
                Text("La curva passa dai valori degli slider", color = Color(0xFF7C828C), fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            NeonSwitch(
                checked = eq.faithful,
                onCheckedChange = { on -> env.feedback.tapScreen(); settings.updateEq { it.copy(faithful = on) } }
            )
        }
        CompressorSection(env, eq)
        Column {
            Text("ReplayGain", color = Color.White, fontSize = 14.sp)
            val rg = env.engine.eqProcessor.info.replayGainDb
            Text(
                when {
                    eq.replayGain == ReplayGain.OFF -> "Livella i brani con i tag del file"
                    rg == null && env.engine.scanningCurrent -> "Nessun tag: sto misurando il brano…"
                    rg == null -> "Nessun tag e misura non riuscita"
                    else -> String.format(Locale.ITALY, "Brano attuale: %+.1f dB", rg) +
                        if (env.engine.eqProcessor.info.replayGainSource == "analisi") " (misurato dall'app)" else " (dai tag)"
                },
                color = Color(0xFF7C828C), fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Spento", eq.replayGain == ReplayGain.OFF) { env.feedback.tapScreen(); settings.updateEq { it.copy(replayGain = ReplayGain.OFF) } }
                Chip("Brano", eq.replayGain == ReplayGain.TRACK) { env.feedback.tapScreen(); settings.updateEq { it.copy(replayGain = ReplayGain.TRACK) } }
                Chip("Album", eq.replayGain == ReplayGain.ALBUM) { env.feedback.tapScreen(); settings.updateEq { it.copy(replayGain = ReplayGain.ALBUM) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Preamp", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            NumField(
                label = "dB", value = eq.preampDb, decimals = 1, suffix = "dB",
                modifier = Modifier.width(120.dp)
            ) { v -> settings.updateEq { it.withPreamp(v) } }
        }
        Text(
            "Valori esatti: tocca la frequenza sotto uno slider (frequenza, guadagno, Q e tipo di filtro).",
            color = Color(0xFF7C828C), fontSize = 11.sp
        )
    }
}

@Composable
private fun CompressorSection(env: AppEnv, eq: EqConfig) {
    val settings = env.settings
    val red by produceState(0f) {
        while (true) {
            value = env.engine.eqProcessor.compressorReductionDb(true)
            delay(400)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Compressore", color = Color.White, fontSize = 14.sp)
                Text(
                    if (!eq.compressorOn) "Riduce la differenza tra parti forti e deboli"
                    else if (red > -0.05f) "nessuna riduzione" else String.format(Locale.ITALY, "riduzione fino a %.1f dB", -red),
                    color = Color(0xFF7C828C), fontSize = 11.sp
                )
            }
            NeonSwitch(
                checked = eq.compressorOn,
                onCheckedChange = { on -> env.feedback.tapScreen(); settings.updateEq { it.copy(compressorOn = on) } }
            )
        }
        if (eq.compressorOn) {
            @Composable
            fun line(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, unit: String, set: (EqConfig, Float) -> EqConfig) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$label ${String.format(Locale.ITALY, if (step < 1f) "%.1f" else "%.0f", value)} $unit",
                        color = Color(0xFFDDE1EA), fontSize = 12.sp, modifier = Modifier.width(112.dp)
                    )
                    NeonSlider(
                        value = value, valueRange = range,
                        onValueChange = { v -> settings.updateEq { set(it, (v / step).roundToInt() * step) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            line("Soglia", eq.compThresholdDb, -60f..0f, 1f, "dB") { c, v -> c.copy(compThresholdDb = v) }
            line("Rapporto", eq.compRatio, 1f..20f, 0.5f, ": 1") { c, v -> c.copy(compRatio = v) }
            line("Attacco", eq.compAttackMs, 1f..100f, 1f, "ms") { c, v -> c.copy(compAttackMs = v) }
            line("Rilascio", eq.compReleaseMs, 20f..1000f, 10f, "ms") { c, v -> c.copy(compReleaseMs = v) }
            line("Ginocchio", eq.compKneeDb, 0f..24f, 1f, "dB") { c, v -> c.copy(compKneeDb = v) }
            line("Recupero", eq.compMakeupDb, 0f..24f, 0.5f, "dB") { c, v -> c.copy(compMakeupDb = v) }
        }
    }
}

/** Campo numerico: il valore viene applicato con Fine o uscendo dal campo. Accetta anche la virgola. */
@Composable
private fun NumField(
    label: String,
    value: Float,
    decimals: Int,
    suffix: String,
    modifier: Modifier = Modifier,
    onCommit: (Float) -> Unit,
) {
    fun fmt(v: Float) = String.format(Locale.ITALY, "%.${decimals}f", v)
    var text by remember(value) { mutableStateOf(fmt(value)) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val v = text.replace(',', '.').trim().toFloatOrNull()
        if (v != null && v.isFinite()) onCommit(v) else text = fmt(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(8) },
        singleLine = true,
        label = { Text(label) },
        suffix = { Text(suffix, color = Color(0xFF9AA0AA)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged { st ->
            if (focused && !st.isFocused) commit()
            focused = st.isFocused
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Accent, unfocusedLabelColor = Color(0xFF9AA0AA),
            focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF4A4E58),
            cursorColor = Accent,
        )
    )
}

/** Modalità "Limite": limiter, soglia, rilascio e auto-headroom. */
@Composable
private fun LimiterPanel(env: AppEnv, eq: EqConfig) {
    val settings = env.settings
    val red by produceState(0f) {
        while (true) {
            value = env.engine.eqProcessor.limiterReductionDb(true)
            delay(500)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Limiter", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                if (!eq.limiterOn) "spento" else if (red > -0.05f) "nessuna riduzione" else String.format(Locale.ITALY, "%.1f dB", red),
                color = Color(0xFF9AA0AA), fontSize = 12.sp
            )
            Spacer(Modifier.width(8.dp))
            NeonSwitch(
                checked = eq.limiterOn,
                onCheckedChange = { on -> env.feedback.tapScreen(); settings.updateEq { c -> c.copy(limiterOn = on) } }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Soglia ${String.format(Locale.ITALY, "%.1f", eq.limiterThresholdDb)} dB", color = Color(0xFFDDE1EA), fontSize = 12.sp, modifier = Modifier.width(96.dp))
            NeonSlider(
                value = eq.limiterThresholdDb, valueRange = -12f..0f,
                onValueChange = { v -> settings.updateEq { it.copy(limiterThresholdDb = (v * 2).roundToInt() / 2f) } },
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rilascio ${eq.limiterReleaseMs.roundToInt()} ms", color = Color(0xFFDDE1EA), fontSize = 12.sp, modifier = Modifier.width(96.dp))
            NeonSlider(
                value = eq.limiterReleaseMs, valueRange = 20f..500f,
                onValueChange = { v -> settings.updateEq { it.copy(limiterReleaseMs = (v / 10f).roundToInt() * 10f) } },
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-headroom", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            NeonSwitch(
                checked = eq.autoHeadroom,
                onCheckedChange = { on -> env.feedback.tapScreen(); settings.updateEq { it.copy(autoHeadroom = on) } }
            )
        }
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    accent: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val click = rememberUpdatedState(onClick)
    val long = rememberUpdatedState(onLongClick)
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else if (accent) Color(0xFF1B3A40) else ChipColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { click.value() },
                    onLongPress = { long.value?.invoke() }
                )
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (selected) Color.Black else Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ToneKnob(
    title: String,
    percent: Int,
    freq: Float,
    color: Color,
    onTick: () -> Unit,
    onDetent: () -> Unit,
    onChange: (Int) -> Unit,
    onFreqClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            "$percent%  (+" + String.format(Locale.ITALY, "%.1f", EqMath.toneDb(percent)) + " dB)",
            color = Color(0xFF9AA0AA), fontSize = 12.sp
        )
        Spacer(Modifier.height(4.dp))
        Knob(value = percent, color = color, size = 70.dp, onTick = onTick, onDetent = onDetent, zero = 0, stepSize = 10, onChange = onChange)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(ChipColor)
                .clickable(onClick = onFreqClick)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text("Freq ${fmtFreqLong(freq)}", color = Color.White, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BandColumn(
    label: String,
    value: Float,
    color: Color,
    modifier: Modifier,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    onDetent: () -> Unit,
    onStep: () -> Unit,
    onLabelClick: (() -> Unit)?,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        VerticalGainSlider(
            value = value, range = EqConfig.GAIN_RANGE, color = color,
            onChange = onChange, onReset = onReset, onDetent = onDetent, onStep = onStep,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        val labelMod = if (onLabelClick != null) {
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onLabelClick).padding(horizontal = 3.dp, vertical = 3.dp)
        } else Modifier.padding(horizontal = 3.dp, vertical = 3.dp)
        Text(
            label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, textAlign = TextAlign.Center, modifier = labelMod
        )
        Text(
            String.format(Locale.ITALY, "%.1f", value), color = Color(0xFF9AA0AA), fontSize = 10.sp,
            maxLines = 1, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun VerticalGainSlider(
    value: Float,
    range: Float,
    color: Color,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    onDetent: () -> Unit = {},
    onStep: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val step = rememberUpdatedState(onStep)
    val lastInt = remember { intArrayOf(value.roundToInt()) }
    val change = rememberUpdatedState(onChange)
    val reset = rememberUpdatedState(onReset)
    val detent = rememberUpdatedState(onDetent)
    val cur = rememberUpdatedState(value)

    // calamita sullo zero: entro +-0,6 dB il valore si aggancia a 0 e vibra più a lungo
    fun valueAt(y: Float, h: Float, pad: Float): Float {
        val usable = (h - 2 * pad).coerceAtLeast(1f)
        val frac = ((y - pad) / usable).coerceIn(0f, 1f)
        val v = range - frac * 2f * range
        val r = (v * 10f).roundToInt() / 10f
        return if (abs(r) <= 0.6f) 0f else r
    }

    val atZero = remember { booleanArrayOf(value == 0f) }
    androidx.compose.runtime.SideEffect { atZero[0] = cur.value == 0f }

    fun emit(v: Float) {
        val n = v.roundToInt()
        if (v == 0f && !atZero[0]) detent.value()
        else if (n != lastInt[0] && v != 0f) step.value()   // un passo ogni 1 dB
        lastInt[0] = n
        atZero[0] = v == 0f
        change.value(v)
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { reset.value() },
                    onTap = { o -> emit(valueAt(o.y, this.size.height.toFloat(), 20.dp.toPx())) }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { c, _ ->
                    c.consume()
                    emit(valueAt(c.position.y, this.size.height.toFloat(), 20.dp.toPx()))
                }
            }
    ) {
        val pad = 20.dp.toPx()
        val cx = this.size.width / 2f
        val usable = this.size.height - 2 * pad
        fun yOf(v: Float): Float = pad + (range - v) / (2f * range) * usable

        // guida
        drawLine(
            Color(0xFF3A3D45), Offset(cx, pad), Offset(cx, this.size.height - pad),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
        )
        // tacche +12 / 0 / -12
        for (v in listOf(range, 0f, -range)) {
            val y = yOf(v)
            drawLine(Color(0xFF5A5E68), Offset(cx - 7.dp.toPx(), y), Offset(cx - 3.dp.toPx(), y), 1.dp.toPx())
            drawLine(Color(0xFF5A5E68), Offset(cx + 3.dp.toPx(), y), Offset(cx + 7.dp.toPx(), y), 1.dp.toPx())
        }
        // linea neon
        val y0 = yOf(0f)
        val yv = yOf(value.coerceIn(-range, range))
        if (abs(yv - y0) > 1f) {
            drawLine(color.copy(alpha = 0.25f), Offset(cx, y0), Offset(cx, yv), 9.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(cx, y0), Offset(cx, yv), 3.5.dp.toPx(), StrokeCap.Round)
        }
        // maniglia
        val tw = minOf(this.size.width - 4.dp.toPx(), 30.dp.toPx())
        val th = 38.dp.toPx()
        drawRoundRect(
            Color(0xFF26282E), Offset(cx - tw / 2f, yv - th / 2f), Size(tw, th),
            CornerRadius(10.dp.toPx())
        )
        drawRoundRect(
            Color(0xFF4A4E58), Offset(cx - tw / 2f, yv - th / 2f), Size(tw, th),
            CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx())
        )
        drawLine(
            Color(0xFFE8EAF0), Offset(cx - tw * 0.28f, yv), Offset(cx + tw * 0.28f, yv),
            3.dp.toPx(), StrokeCap.Round
        )
    }
}

/** Grafico della curva di risposta complessiva (bande + bassi + alti). */
/** Posizione 0..1 (asse logaritmico 20 Hz - 20 kHz) di una frequenza. */
private fun freqPos(f: Float): Float = (ln(f / 20f) / ln(1000f)).coerceIn(0f, 1f)

/** Curva di risposta complessiva con la finestra delle bande visibili; trascinala per scorrere. */
@Composable
private fun ResponseGraph(eq: EqConfig, scroll: ScrollState, viewW: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val total = (scroll.maxValue + viewW).coerceAtLeast(1)
    val f0 = scroll.value.toFloat() / total
    val f1 = (scroll.value + viewW).toFloat() / total

    // indice frazionario di banda -> frequenza (interpolazione logaritmica)
    fun freqAtIndex(t: Float): Float {
        val n = eq.freqs.size
        val x = (t * n - 0.5f).coerceIn(0f, (n - 1).toFloat())
        val i = x.toInt().coerceAtMost(n - 2).coerceAtLeast(0)
        if (n < 2) return eq.freqs.firstOrNull() ?: 1000f
        val fr = x - i
        return eq.freqs[i] * (eq.freqs[i + 1] / eq.freqs[i]).pow(fr)
    }
    fun indexAtFreq(f: Float): Float {
        val n = eq.freqs.size
        if (f <= eq.freqs.first()) return 0.5f / n
        if (f >= eq.freqs.last()) return (n - 0.5f) / n
        for (i in 0 until n - 1) {
            if (f <= eq.freqs[i + 1]) {
                val fr = ln(f / eq.freqs[i]) / ln(eq.freqs[i + 1] / eq.freqs[i])
                return (i + fr + 0.5f) / n
            }
        }
        return 1f
    }

    fun scrollToX(x: Float, w: Float) {
        val f = 20f * 1000f.pow((x / w).coerceIn(0f, 1f))
        val center = indexAtFreq(f) * total
        scope.launch { scroll.scrollTo((center - viewW / 2f).roundToInt().coerceIn(0, scroll.maxValue)) }
    }

    Canvas(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF17181C))
            .pointerInput(eq.freqs, viewW, scroll.maxValue) {
                detectTapGestures(onTap = { o -> scrollToX(o.x, this.size.width.toFloat()) })
            }
            .pointerInput(eq.freqs, viewW, scroll.maxValue) {
                detectDragGestures { c, _ ->
                    c.consume()
                    scrollToX(c.position.x, this.size.width.toFloat())
                }
            }
    ) {
        val w = this.size.width
        val h = this.size.height
        val rangeDb = 15f
        fun yOf(db: Float): Float = h / 2f - (db.coerceIn(-rangeDb, rangeDb) / rangeDb) * (h / 2f - 4.dp.toPx())

        // finestra delle bande visibili
        if (eq.freqs.isNotEmpty() && viewW > 0 && scroll.maxValue > 0) {
            val xa = freqPos(freqAtIndex(f0)) * w
            val xb = freqPos(freqAtIndex(f1)) * w
            drawRoundRect(
                Color(0x33FFFFFF), Offset(xa, 0f), Size((xb - xa).coerceAtLeast(6.dp.toPx()), h),
                CornerRadius(8.dp.toPx())
            )
        }
        drawLine(Color(0xFF4A4E58), Offset(0f, yOf(0f)), Offset(w, yOf(0f)), 1.dp.toPx())

        val points = 160
        val path = Path()
        for (i in 0 until points) {
            val t = i / (points - 1.0)
            val f = 20.0 * 1000.0.pow(t)
            val x = (t * w).toFloat()
            val y = yOf(eq.responseDb(f, includeTone = false).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Tono (Bassi/Alti): curva separata, riempita, come in Poweramp
        if (eq.bassPercent != 0 || eq.treblePercent != 0) {
            val tp = Path()
            val y0 = yOf(0f)
            tp.moveTo(0f, y0)
            val line = Path()
            for (i in 0 until points) {
                val t = i / (points - 1.0)
                val f = 20.0 * 1000.0.pow(t)
                val x = (t * w).toFloat()
                val y = yOf(eq.toneResponseDb(f).toFloat())
                tp.lineTo(x, y)
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            tp.lineTo(w, y0); tp.close()
            drawPath(tp, Color(0x55E8EAF0))
            drawPath(line, Color(0xFFE8EAF0), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
        drawPath(path, NeonGreen, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun FreqDialog(
    target: FreqTarget,
    eq: EqConfig,
    onDismiss: () -> Unit,
    onEq: (EqConfig) -> Unit,
) {
    val title: String
    val min: Float
    val max: Float
    val current: Float
    when (target) {
        is FreqTarget.Band -> {
            title = "Banda ${target.index + 1}: frequenza e Q"
            min = EqConfig.MIN_FREQ
            max = EqConfig.MAX_FREQ
            current = eq.freqs.getOrElse(target.index) { 1000f }
        }
        FreqTarget.Bass -> {
            title = "Frequenza dei bassi"
            min = 30f
            max = 500f
            current = eq.bassFreq
        }
        FreqTarget.Treble -> {
            title = "Frequenza degli alti"
            min = 2000f
            max = 16000f
            current = eq.trebleFreq
        }
    }
    val pos = (ln(current / min) / ln(max / min)).coerceIn(0f, 1f)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ok") } },
        containerColor = Color(0xFF1E1F24),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(fmtFreqLong(current), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Accent)
                Spacer(Modifier.height(4.dp))
                NumField(
                    label = "Frequenza", value = current, decimals = 0, suffix = "Hz", modifier = Modifier.fillMaxWidth()
                ) { v ->
                    val f = v.coerceIn(min, max)
                    onEq(
                        when (target) {
                            is FreqTarget.Band -> eq.withFreq(target.index, f)
                            FreqTarget.Bass -> eq.copy(bassFreq = f)
                            FreqTarget.Treble -> eq.copy(trebleFreq = f)
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.height(8.dp))
                NeonSlider(
                    modifier = Modifier.fillMaxWidth(),
                    value = pos, valueRange = 0f..1f,
                    onValueChange = { p ->
                        val raw = min * (max / min).pow(p)
                        val step = when {
                            raw < 100f -> 1f
                            raw < 1000f -> 5f
                            raw < 10000f -> 50f
                            else -> 100f
                        }
                        val f = ((raw / step).roundToInt() * step).coerceIn(min, max)
                        val newEq = when (target) {
                            is FreqTarget.Band -> eq.withFreq(target.index, f)
                            FreqTarget.Bass -> eq.copy(bassFreq = f)
                            FreqTarget.Treble -> eq.copy(trebleFreq = f)
                        }
                        onEq(newEq)
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtFreqLong(min), fontSize = 12.sp, color = Color(0xFF9AA0AA))
                    Text(fmtFreqLong(max), fontSize = 12.sp, color = Color(0xFF9AA0AA))
                }
                if (target is FreqTarget.Band) {
                    val idx = target.index
                    val q = eq.qFor(idx).toFloat()
                    val qPos = (ln(q / EqConfig.MIN_Q) / ln(EqConfig.MAX_Q / EqConfig.MIN_Q)).coerceIn(0f, 1f)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField(
                            label = "Guadagno", value = eq.gains.getOrElse(idx) { 0f }, decimals = 1, suffix = "dB",
                            modifier = Modifier.weight(1f)
                        ) { v -> onEq(eq.withGain(idx, v)) }
                        NumField(
                            label = "Q", value = q, decimals = 2, suffix = "",
                            modifier = Modifier.weight(1f)
                        ) { v -> onEq(eq.withQ(idx, v)) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Shelf bassi", eq.typeAt(idx) == 0) { onEq(eq.withType(idx, 0)) }
                        Chip("Campana", eq.typeAt(idx) == 2) { onEq(eq.withType(idx, 2)) }
                        Chip("Shelf alti", eq.typeAt(idx) == 1) { onEq(eq.withType(idx, 1)) }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Q " + String.format(Locale.ITALY, "%.2f", q) + "  (larghezza: " +
                            String.format(Locale.ITALY, "%.2f", 2.0 * Math.log(0.5 / q + Math.sqrt(0.25 / (q * q) + 1.0)) / Math.log(2.0)) +
                            " ott.)",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Accent
                    )
                    NeonSlider(
                        modifier = Modifier.fillMaxWidth(),
                        value = qPos, valueRange = 0f..1f,
                        onValueChange = { p ->
                            val v = EqConfig.MIN_Q * (EqConfig.MAX_Q / EqConfig.MIN_Q).pow(p)
                            onEq(eq.withQ(idx, (v * 20f).roundToInt() / 20f))
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("largo", fontSize = 12.sp, color = Color(0xFF9AA0AA))
                        Text("stretto", fontSize = 12.sp, color = Color(0xFF9AA0AA))
                    }
                }
            }
        }
    )
}
