package com.marco.mleq.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mleq.audio.DspSettings
import com.marco.mleq.audio.EqPart
import com.marco.mleq.audio.GraphicPresets
import com.marco.mleq.audio.Modes
import com.marco.mleq.audio.OutKind
import com.marco.mleq.audio.PresetText
import com.marco.mleq.audio.ReplayGain
import com.marco.mleq.util.AppLog
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/** Schermata principale: lettore, curva e una scheda per ogni modulo del DSP. */
@Composable
fun MainScreen(env: AppEnv) {
    val settings = env.settings
    val s = settings.dsp
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    var exportPart by remember { mutableStateOf<EqPart?>(null) }

    fun readText(uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) {
        AppLog.e("App", "Lettura file non riuscita", e); null
    }

    fun writeText(uri: Uri, text: String): Boolean = try {
        ctx.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
        true
    } catch (e: Exception) {
        AppLog.e("App", "Scrittura file non riuscita", e); false
    }

    val importPreset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readText(uri) ?: return@rememberLauncherForActivityResult
        val list = PresetText.parseMulti(text, "Importato")
        if (list.isEmpty()) {
            message = "Il file non contiene un preset riconoscibile (AutoEQ, Poweramp o GraphicEQ)."
        } else {
            for ((name, part) in list) settings.saveUserPreset(name, part)
            settings.updateDsp { it.withEqPart(list.first().second) }
            message = if (list.size == 1) "Preset \"${list[0].first}\" importato e applicato." else "${list.size} preset importati; applicato \"${list[0].first}\"."
        }
    }
    val exportPreset = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val p = exportPart
        if (uri != null && p != null) message = if (writeText(uri, PresetText.export(p))) "Preset esportato (formato AutoEQ/Poweramp)." else "Esportazione non riuscita."
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = if (writeText(uri, settings.exportBackup())) "Backup salvato: impostazioni e preset." else "Salvataggio non riuscito."
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readText(uri) ?: return@rememberLauncherForActivityResult
        message = if (settings.importBackup(text)) "Backup ripristinato." else "Il file non è un backup di MLEqualizer."
    }

    Column(Modifier.fillMaxSize().background(Ml.background).safeDrawingPadding()) {
        // barra in alto: nome, interruttore generale, menu
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MLEqualizer", color = Ml.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(if (s.master) "DSP attivo" else "DSP spento · audio intatto", color = Ml.textDim, fontSize = 12.sp)
            }
            MlSwitch(s.master) { on -> env.feedback.tapScreen(); settings.updateDsp { it.copy(master = on) } }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro", tint = Ml.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Ml.dialog)) {
                    @Composable
                    fun item(t: String, a: () -> Unit) = DropdownMenuItem(text = { Text(t, color = Ml.text) }, onClick = { menu = false; a() })
                    item("Analizzatore") { env.overlay = Overlay.Analyzer }
                    item("Uscita audio") { env.overlay = Overlay.Output }
                    item("Salva backup su file") { exportBackup.launch("MLEqualizer-backup.json") }
                    item("Ripristina backup da file") { importBackup.launch(arrayOf("application/json", "text/*", "*/*")) }
                    item(if (settings.dynamicColors) "Colori dinamici: attivi" else "Colori dinamici: spenti") { settings.dynamicColors = !settings.dynamicColors }
                    item(if (settings.amoled) "Sfondo nero (AMOLED): sì" else "Sfondo nero (AMOLED): no") { settings.amoled = !settings.amoled }
                    item("Log di debug") { env.overlay = Overlay.Log }
                }
            }
        }
        Box(Modifier.padding(horizontal = 12.dp)) { PlayerBar(env) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { CurveCard(env, s) }
            item {
                EqCard(
                    env, s,
                    onSave = { saveDialog = true },
                    onImport = { importPreset.launch(arrayOf("text/*", "application/json", "*/*")) },
                    onExport = { exportPart = s.eqPart(); exportPreset.launch("MLEqualizer-preset.txt") },
                )
            }
            item { ToneCard(env, s) }
            item { AnalogBassCard(env, s) }
            item { TubeCard(env, s) }
            item { ExciterCard(env, s) }
            item { AirCard(env, s) }
            item { HdrCard(env, s) }
            item { StereoCard(env, s) }
            item { DbfbCard(env, s) }
            item { MobileBassCard(env, s) }
            item { LoudnessCard(env, s) }
            item { CompressorCard(env, s) }
            item { LimiterCard(env, s) }
            item { ReplayGainCard(env, s) }
        }
    }

    if (saveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.text,
            title = { Text("Salva preset") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(24) }, singleLine = true, label = { Text("Nome") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ml.text, unfocusedTextColor = Ml.text, focusedBorderColor = Ml.primary,
                        unfocusedBorderColor = Ml.outlineVariant, focusedLabelColor = Ml.primary, unfocusedLabelColor = Ml.textDim, cursorColor = Ml.primary,
                    )
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { settings.saveUserPreset(name, s.eqPart()); saveDialog = false }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text("Annulla") } },
        )
    }
    message?.let { m -> InfoDialog("MLEqualizer", m) { message = null } }
}

// ------------------------------------------------------------------ schede

@Composable
private fun CurveCard(env: AppEnv, s: DspSettings) {
    val res = remember(s) { env.preview.compute(s) }
    val info = env.engine.eqProcessor.info
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ml.panel).padding(16.dp)
    ) {
        Text("Curva", color = Ml.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (s.master) "Risposta di EQ, toni e moduli, calcolata dal motore" else "DSP spento: la curva è un'anteprima",
            color = Ml.textDim, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        ResponseCurve(env.preview.freqs, res.response, Modifier.fillMaxWidth().height(150.dp))
        FreqAxis()
        Spacer(Modifier.height(6.dp))
        Text(statusLine(info), color = Ml.textFaint, fontSize = 11.sp)
    }
}

private fun statusLine(i: com.marco.mleq.audio.DspInfo): String {
    if (i.sampleRate <= 0) return "In attesa di un brano"
    val hz = if (i.sampleRate % 1000 == 0) "${i.sampleRate / 1000} kHz" else String.format(Locale.ITALY, "%.1f kHz", i.sampleRate / 1000.0)
    val state = if (i.bypass) "Bypass" else "DSP · ${i.modules.joinToString(", ").ifEmpty { "nessun modulo" }}"
    return "$state · uscita ${i.outEncoding.removePrefix("PCM ")} · $hz"
}

@Composable
private fun EqCard(env: AppEnv, s: DspSettings, onSave: () -> Unit, onImport: () -> Unit, onExport: () -> Unit) {
    val settings = env.settings
    var lastMode by remember { mutableStateOf(if (s.eqMode == Modes.EQ_OFF) Modes.EQ_GRAPHIC else s.eqMode) }
    ModuleCard(
        "Equalizzatore",
        if (s.eqMode == Modes.EQ_PARAMETRIC) "Parametrico · 16 bande, 8 tipi di filtro" else "Grafico · 15 bande ISO",
        "Grafico: 15 bande alle frequenze ISO da 25 Hz a 16 kHz. Con \"curva fedele\" il motore ricalcola i guadagni " +
            "perché la curva totale passi esattamente dai cursori (le campane vicine altrimenti si sommano).\n\n" +
            "Parametrico: 16 bande con frequenza, guadagno e Q liberi e 8 tipi di filtro (campana, shelf, passa-basso, " +
            "passa-alto, passa-banda, notch, passa-tutto). Si modifica trascinando i punti sul grafico. " +
            "Accetta i preset AutoEQ e Poweramp.",
        enabled = s.eqMode != Modes.EQ_OFF,
        onToggle = { on ->
            env.feedback.tapScreen()
            settings.updateDsp { it.copy(eqMode = if (on) lastMode else Modes.EQ_OFF) }
        },
    ) {
        Choices(listOf("Grafico", "Parametrico"), if (s.eqMode == Modes.EQ_PARAMETRIC) 1 else 0) { i ->
            val m = if (i == 1) Modes.EQ_PARAMETRIC else Modes.EQ_GRAPHIC
            lastMode = m
            settings.updateDsp { it.copy(eqMode = m) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Apri l'editor", selected = true) {
                env.overlay = if (s.eqMode == Modes.EQ_PARAMETRIC) Overlay.Parametric else Overlay.Graphic
            }
            Pill("Salva", selected = false, onClick = onSave)
            Pill("Importa", selected = false, onClick = onImport)
            Pill("Esporta", selected = false, onClick = onExport)
        }
        Text("Preset", color = Ml.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        PresetRow(env, s)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PresetRow(env: AppEnv, s: DspSettings) {
    val settings = env.settings
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (u in settings.userPresets) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(Ml.primaryContainer)
                .combinedClickable(onClick = { settings.updateDsp { it.withEqPart(u.part) } }, onLongClick = { confirmDelete = u.name })
                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(u.name, color = Ml.text, fontSize = 14.sp)
            }
        }
        for (p in GraphicPresets.all) {
            val sel = s.eqMode == Modes.EQ_GRAPHIC && s.graphic == p.gains
            Pill(p.name, sel) {
                settings.updateDsp { it.copy(eqMode = Modes.EQ_GRAPHIC, graphic = p.gains, master = true) }
            }
        }
    }
    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.textDim,
            title = { Text("Eliminare \"$name\"?") },
            text = { Text("Il preset salvato verrà rimosso.") },
            confirmButton = { TextButton(onClick = { settings.deleteUserPreset(name); confirmDelete = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun ToneCard(env: AppEnv, s: DspSettings) {
    val settings = env.settings
    var dialog by remember { mutableStateOf<Boolean?>(null) }  // true = bassi, false = alti
    ModuleCard(
        "Toni",
        "Bassi e alti come Poweramp: shelf con frequenza e Q",
        "Due filtri a scaffale (shelf), come le manopole di Poweramp. La manopola dà da 0 a +10 dB; la " +
            "frequenza dice da dove parte il rinforzo e il Q quanto è ripida la salita (0,10 = molto dolce, 0,71 = " +
            "netta senza risalite, sopra = gradino con una piccola sovraelongazione). Predefiniti: bassi 90 Hz Q 0,80, " +
            "alti 10 kHz Q 0,60. Con l'headroom automatico il livello scende di quanto rinforzi, così il limiter non interviene.",
        enabled = null,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToneKnob(env, "Bassi", s.bassDb, s.bassFreq, s.bassQ, Ml.tertiary,
                onChange = { v -> settings.updateDsp { it.copy(bassDb = v) } }, onEdit = { dialog = true })
            ToneKnob(env, "Alti", s.trebleDb, s.trebleFreq, s.trebleQ, Ml.primary,
                onChange = { v -> settings.updateDsp { it.copy(trebleDb = v) } }, onEdit = { dialog = false })
        }
    }
    dialog?.let { bass -> ToneDialog(env, s, bass) { dialog = null } }
}

@Composable
private fun ToneKnob(env: AppEnv, title: String, db: Float, freq: Float, q: Float, color: androidx.compose.ui.graphics.Color, onChange: (Float) -> Unit, onEdit: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Ml.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(fmtDb(db), color = Ml.textDim, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        val pct = (db / DspSettings.TONE_MAX_DB * 100f).roundToInt().coerceIn(0, 100)
        Knob(
            value = pct, color = color, size = 84.dp,
            onTick = { env.feedback.tapScreen() }, onDetent = { env.feedback.detent() }, zero = 0, stepSize = 5,
            onChange = { v -> onChange(v / 100f * DspSettings.TONE_MAX_DB) }
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(Ml.chip).clickable(onClick = onEdit).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("${fmtHz(freq)} · Q ${fmt2(q)}", color = Ml.text, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ToneDialog(env: AppEnv, s: DspSettings, bass: Boolean, onDismiss: () -> Unit) {
    val settings = env.settings
    val freq = if (bass) s.bassFreq else s.trebleFreq
    val q = if (bass) s.bassQ else s.trebleQ
    val lo = if (bass) 20f else 1000f
    val hi = if (bass) 500f else 20000f
    fun setF(f: Float) = settings.updateDsp { if (bass) it.copy(bassFreq = f.coerceIn(lo, hi)) else it.copy(trebleFreq = f.coerceIn(lo, hi)) }
    fun setQ(v: Float) = settings.updateDsp { if (bass) it.copy(bassQ = v.coerceIn(0.1f, 2f)) else it.copy(trebleQ = v.coerceIn(0.1f, 2f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ok") } },
        dismissButton = {
            TextButton(onClick = {
                settings.updateDsp { if (bass) it.copy(bassFreq = 90f, bassQ = 0.8f) else it.copy(trebleFreq = 10000f, trebleQ = 0.6f) }
            }) { Text("Predefiniti") }
        },
        containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.text,
        title = { Text(if (bass) "Bassi: frequenza e Q" else "Alti: frequenza e Q") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("Frequenza", freq, 0, "Hz", Modifier.weight(1f)) { setF(it) }
                    NumField("Q", q, 2, "", Modifier.weight(1f)) { setQ(it) }
                }
                val fPos = (kotlin.math.ln(freq / lo) / kotlin.math.ln(hi / lo)).coerceIn(0f, 1f)
                LabeledSlider("Frequenza", fPos, 0f..1f, fmtHz(freq)) { p ->
                    val raw = lo * Math.pow((hi / lo).toDouble(), p.toDouble()).toFloat()
                    val step = if (raw < 100f) 1f else if (raw < 1000f) 5f else if (raw < 10000f) 50f else 100f
                    setF((raw / step).roundToInt() * step)
                }
                LabeledSlider("Q (ripidità)", q, 0.1f..2f, fmt2(q)) { v -> setQ((v * 100).roundToInt() / 100f) }
                Text("0,10 = salita molto dolce · 0,71 = netta senza risalite · 2 = gradino", color = Ml.textFaint, fontSize = 11.sp)
            }
        }
    )
}

@Composable
private fun AnalogBassCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    ModuleCard(
        "Analog Bass", "Bassi da console vintage: stile Pultec e saturazione",
        "Rinforzo a scaffale alla frequenza scelta e, nello stesso momento, un leggero taglio due ottave sopra " +
            "(il trucco dell'equalizzatore Pultec EQP-1A): il sub-basso si gonfia e la zona \"fangosa\" si pulisce.\n\n" +
            "Drive: la banda sotto i 70 Hz passa da una saturazione morbida che aggiunge armoniche, così il basso si " +
            "sente più pieno anche su cuffie piccole. I segnali deboli non cambiano.",
        enabled = s.analogBass, onToggle = { on -> st.updateDsp { it.copy(analogBass = on, master = it.master || on) } },
    ) {
        LabeledSlider("Calore (Pultec)", s.analogWarmth, 0f..1f, String.format(Locale.ITALY, "+%.1f dB", 8f * s.analogWarmth)) { v -> st.updateDsp { it.copy(analogWarmth = v) } }
        LabeledSlider("Drive", s.analogDrive, 0f..1f, fmtPct(s.analogDrive)) { v -> st.updateDsp { it.copy(analogDrive = v) } }
        val freqs = listOf(20f, 30f, 60f, 100f)
        Text("Frequenza Pultec", color = Ml.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Choices(freqs.map { "${it.roundToInt()} Hz" }, freqs.indexOfFirst { kotlin.math.abs(it - s.analogFreq) < 0.5f }) { i -> st.updateDsp { it.copy(analogFreq = freqs[i]) } }
    }
}

@Composable
private fun TubeCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    ModuleCard(
        "Tube Warmth", "Calore da amplificatore a valvole",
        "Saturazione asimmetrica come una valvola: aggiunge soprattutto la seconda armonica, quella che rende il " +
            "suono \"caldo\". Lavora a frequenza doppia, così le armoniche non producono rumore metallico (aliasing). " +
            "In più una leggera \"fioritura\" attorno agli 80 Hz e un dolce arrotondamento sopra i 10 kHz, come il " +
            "trasformatore d'uscita. Utile su registrazioni digitali sottili o troppo cliniche.",
        enabled = s.tube, onToggle = { on -> st.updateDsp { it.copy(tube = on, master = it.master || on) } },
    ) {
        LabeledSlider("Intensità", s.tubeAmount, 0f..1f, fmtPct(s.tubeAmount)) { v -> st.updateDsp { it.copy(tubeAmount = v) } }
    }
}

@Composable
private fun ExciterCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    ModuleCard(
        "Harmonic Exciter", "Presenza e brillantezza (stile BBE/Aphex)",
        "Prende la parte alta del suono (sopra i 3 kHz), ne genera le armoniche e ne aggiunge una piccola quantità " +
            "sopra i 5 kHz: voci e strumenti diventano più chiari e presenti senza alzare semplicemente gli acuti.",
        enabled = s.exciter, onToggle = { on -> st.updateDsp { it.copy(exciter = on, master = it.master || on) } },
    ) {
        LabeledSlider("Quantità", s.exciterAmount, 0f..1f, fmtPct(s.exciterAmount)) { v -> st.updateDsp { it.copy(exciterAmount = v) } }
    }
}

@Composable
private fun AirCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    ModuleCard(
        "Hi-Res Air", "Più aria e dettaglio sopra i 6 kHz",
        "Due shelf morbidi (6 kHz e 12 kHz) che alzano la zona dell'\"aria\": piatti, ambiente, respiro delle voci. " +
            "Fino a circa +4,5 dB in cima alla gamma; sotto i 2 kHz non cambia nulla.",
        enabled = s.air, onToggle = { on -> st.updateDsp { it.copy(air = on, master = it.master || on) } },
    ) {
        LabeledSlider("Quantità", s.airAmount, 0f..1f, fmtPct(s.airAmount)) { v -> st.updateDsp { it.copy(airAmount = v) } }
    }
}

@Composable
private fun HdrCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    ModuleCard(
        "HDR Dynamics", "Contro la \"guerra del volume\"",
        "Pure: percorso quasi trasparente, il limiter si alza a -0,1 dBFS e interviene solo sui veri superamenti.\n\n" +
            "Restoration: per i master schiacciati. Un espansore molto dolce (1,12:1 sotto -38 dBFS) riallarga la " +
            "differenza tra parti deboli e forti, senza toccare i picchi, più un piccolo recupero d'aria a 10 e 16 kHz.",
        enabled = null,
    ) {
        Choices(Modes.hdr, s.hdrMode) { i -> st.updateDsp { it.copy(hdrMode = i, master = it.master || i > 0) } }
    }
}

@Composable
private fun StereoCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    ModuleCard(
        "Surround+ e stereo", "Immagine più ampia con le voci al centro",
        "Surround+: un \"sorriso\" uguale nelle due orecchie (bassi e acuti, i medi delle voci restano intatti) e un " +
            "allargamento del solo canale laterale sopra i 2,5 kHz. Le voci e tutto ciò che è al centro non si muovono.\n\n" +
            "Espansione: allarga il laterale sopra ~150 Hz (i bassi restano al centro). Bilanciamento: abbassa il lato " +
            "opposto, non alza mai. Crossfeed (cuffie): riduce la separazione dei bassi come ascoltando dei diffusori; " +
            "il suono al centro non cambia.",
        enabled = null,
    ) {
        Choices(Modes.surround, s.surroundMode) { i -> st.updateDsp { it.copy(surroundMode = i, master = it.master || i > 0) } }
        val bal = s.balance
        LabeledSlider("Bilanciamento", bal, -1f..1f, when { kotlin.math.abs(bal) < 0.01f -> "Centro"; bal < 0 -> "S ${(-bal * 100).roundToInt()}%"; else -> "D ${(bal * 100).roundToInt()}%" }) { v ->
            st.updateDsp { it.copy(balance = if (kotlin.math.abs(v) < 0.03f) 0f else v, master = true) }
        }
        LabeledSlider("Espansione", s.expand, 0f..1f, fmtPct(s.expand)) { v -> st.updateDsp { it.copy(expand = v, mono = false, master = true) } }
        LabeledSlider("Crossfeed (cuffie)", s.crossfeed, 0f..1f, if (s.crossfeed < 0.01f) "Spento" else fmtPct(s.crossfeed)) { v -> st.updateDsp { it.copy(crossfeed = v, master = true) } }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Stereo", !s.mono) { st.updateDsp { it.copy(mono = false) } }
            Pill("Mono", s.mono) { st.updateDsp { it.copy(mono = true, master = true) } }
        }
    }
}

@Composable
private fun DbfbCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    ModuleCard(
        "DBFB · bassi dinamici", "Più impatto sui colpi di cassa e basso",
        "Sotto i 260 Hz confronta il livello istantaneo con quello medio: quando arriva un colpo lo rinforza (fino a " +
            "+4 dB in Normale, +7 dB in Alto), mentre i suoni costanti restano come sono.",
        enabled = null,
    ) {
        Choices(Modes.dbfb, s.dbfbMode) { i -> st.updateDsp { it.copy(dbfbMode = i, master = it.master || i > 0) } }
    }
}

@Composable
private fun MobileBassCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    val onSpeaker = env.engine.outputMonitor.kind == OutKind.Speaker
    ModuleCard(
        "Mobile Bass", if (onSpeaker) "Attivo ora: uscita sull'altoparlante" else "In attesa: si attiva solo sull'altoparlante",
        "L'altoparlante del telefono non riproduce i bassi profondi. Mobile Bass taglia quello che non può " +
            "riprodurre (meno distorsione) e genera le armoniche dei bassi tra 100 e 400 Hz: l'orecchio ricostruisce la " +
            "nota mancante (\"basso virtuale\"). Con cuffie, Bluetooth o DAC resta spento da solo.",
        enabled = s.mobileBass, onToggle = { on -> st.updateDsp { it.copy(mobileBass = on, master = it.master || on) } },
    ) {
        LabeledSlider("Intensità", s.mobileBassAmount, 0f..1f, fmtPct(s.mobileBassAmount)) { v -> st.updateDsp { it.copy(mobileBassAmount = v) } }
    }
}

@Composable
private fun LoudnessCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    val vol = env.engine.eqProcessor.listenVolume
    val volDb = if (vol > 0) 20 * kotlin.math.log10(vol) else -80.0
    ModuleCard(
        "Loudness", "Bassi e acuti compensati quando il volume è basso",
        "A volume basso l'orecchio sente meno bassi e acuti (curve isofoniche). La loudness li compensa in base al " +
            "volume del telefono: a volume pieno non fa nulla, abbassando aggiunge gradualmente bassi (fino a +12 dB) " +
            "e acuti (fino a +6 dB).",
        enabled = s.loudness, onToggle = { on -> st.updateDsp { it.copy(loudness = on, master = it.master || on) } },
    ) {
        LabeledSlider("Quantità", s.loudnessAmount, 0f..1f, fmtPct(s.loudnessAmount)) { v -> st.updateDsp { it.copy(loudnessAmount = v) } }
        Text(String.format(Locale.ITALY, "Volume attuale: %.0f dB", volDb), color = Ml.textFaint, fontSize = 12.sp)
    }
}

@Composable
private fun CompressorCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    val red by produceState(0f, s.compressor) {
        while (s.compressor) { value = env.engine.eqProcessor.meters(false).compressorDb; delay(400) }
    }
    ModuleCard(
        "Compressore", if (s.compressor && red < -0.05f) String.format(Locale.ITALY, "Riduzione fino a %.1f dB", -red) else "Avvicina parti forti e deboli",
        "Compressore a canali collegati (l'immagine stereo non si sposta), con ginocchio morbido e recupero del livello. " +
            "Soglia: da dove inizia; rapporto: quanto comprime (4:1 = 4 dB in ingresso diventano 1 dB); attacco e " +
            "rilascio: quanto velocemente reagisce.",
        enabled = s.compressor, onToggle = { on -> st.updateDsp { it.copy(compressor = on, master = it.master || on) } },
    ) {
        LabeledSlider("Soglia", s.compThresholdDb, -60f..0f, "${s.compThresholdDb.roundToInt()} dB") { v -> st.updateDsp { it.copy(compThresholdDb = v.roundToInt().toFloat()) } }
        LabeledSlider("Rapporto", s.compRatio, 1f..20f, "${fmt1(s.compRatio)}:1") { v -> st.updateDsp { it.copy(compRatio = (v * 2).roundToInt() / 2f) } }
        LabeledSlider("Attacco", s.compAttackMs, 0.5f..100f, "${fmt1(s.compAttackMs)} ms") { v -> st.updateDsp { it.copy(compAttackMs = (v * 2).roundToInt() / 2f) } }
        LabeledSlider("Rilascio", s.compReleaseMs, 20f..1000f, "${s.compReleaseMs.roundToInt()} ms") { v -> st.updateDsp { it.copy(compReleaseMs = ((v / 10).roundToInt() * 10).toFloat()) } }
        LabeledSlider("Ginocchio", s.compKneeDb, 0f..24f, "${s.compKneeDb.roundToInt()} dB") { v -> st.updateDsp { it.copy(compKneeDb = v.roundToInt().toFloat()) } }
        LabeledSlider("Recupero", s.compMakeupDb, 0f..24f, "+${fmt1(s.compMakeupDb)} dB") { v -> st.updateDsp { it.copy(compMakeupDb = (v * 2).roundToInt() / 2f) } }
    }
}

@Composable
private fun LimiterCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    val red by produceState(0f, s.limiter) {
        while (s.limiter) { value = env.engine.eqProcessor.meters(false).limiterDb; delay(400) }
    }
    ModuleCard(
        "Livello e limiter", if (s.limiter && red < -0.05f) String.format(Locale.ITALY, "Limiter: riduzione fino a %.1f dB", -red) else "Preamp, headroom automatico, limiter true-peak",
        "Preamp: guadagno all'ingresso del DSP.\n\nHeadroom automatico: il motore calcola il rinforzo massimo di EQ, toni " +
            "e moduli e abbassa l'ingresso di altrettanto, così nulla satura e il limiter non deve lavorare.\n\n" +
            "Limiter true-peak: look-ahead di 5 ms, vede anche i picchi tra un campione e l'altro e non distorce i bassi " +
            "profondi. Garantisce che l'uscita non superi la soglia.",
        enabled = null,
    ) {
        LabeledSlider("Preamp", s.preampDb, -12f..12f, fmtDb(s.preampDb)) { v -> st.updateDsp { it.copy(preampDb = (v * 2).roundToInt() / 2f) } }
        SwitchRow("Headroom automatico", "Abbassa l'ingresso di quanto rinforzi", s.autoHeadroom) { on -> st.updateDsp { it.copy(autoHeadroom = on) } }
        SwitchRow("Limiter", null, s.limiter) { on -> st.updateDsp { it.copy(limiter = on) } }
        if (s.limiter) {
            LabeledSlider("Soglia", s.limiterThresholdDb, -12f..0f, "${fmt1(s.limiterThresholdDb)} dBFS") { v -> st.updateDsp { it.copy(limiterThresholdDb = (v * 10).roundToInt() / 10f) } }
            LabeledSlider("Rilascio", s.limiterReleaseMs, 5f..500f, "${s.limiterReleaseMs.roundToInt()} ms") { v -> st.updateDsp { it.copy(limiterReleaseMs = ((v / 5).roundToInt() * 5).toFloat()) } }
        }
    }
}

@Composable
private fun ReplayGainCard(env: AppEnv, s: DspSettings) {
    val st = env.settings
    val info = env.engine.eqProcessor.info
    val rg = info.replayGainDb
    ModuleCard(
        "ReplayGain", when {
            s.replayGain == ReplayGain.OFF -> "Stesso volume per tutti i brani"
            rg == null && env.engine.scanningCurrent -> "Nessun tag: sto misurando il brano…"
            rg == null -> "Nessun valore per il brano attuale"
            else -> String.format(Locale.ITALY, "Brano attuale: %+.1f dB", rg) + if (info.replayGainSource == "analisi") " (misurato)" else " (dai tag)"
        },
        "Livella i brani in base ai tag ReplayGain del file (FLAC, Ogg, MP3, M4A, Opus). Se il file non ha tag, l'app " +
            "ne misura la loudness (EBU R128) in background e la ricorda. Il guadagno non porta mai il brano oltre " +
            "0 dBFS di picco.",
        enabled = null,
    ) {
        Choices(Modes.replayGain, s.replayGain) { i -> st.updateDsp { it.copy(replayGain = i, master = it.master || i > 0) } }
    }
}
