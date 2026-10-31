package com.marco.mleq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.marco.mleq.audio.DspSettings
import com.marco.mleq.audio.FilterType
import com.marco.mleq.audio.Modes
import com.marco.mleq.audio.PeqBand
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
private fun EditorTopBar(env: AppEnv, title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { env.overlay = Overlay.None }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Ml.text)
        }
        Text(title, color = Ml.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ------------------------------------------------------------------ EQ grafico

/** Editor del grafico a 15 bande: cursori verticali, preamp, curva vera e preset. */
@Composable
fun GraphicEqScreen(env: AppEnv) {
    val st = env.settings
    val s = st.dsp
    val res = remember(s) { env.preview.compute(s) }
    Column(Modifier.fillMaxSize().background(Ml.background).safeDrawingPadding()) {
        EditorTopBar(env, "EQ grafico") {
            Text("Curva fedele", color = Ml.textDim, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            MlSwitch(s.faithful) { on -> st.updateDsp { it.copy(faithful = on) } }
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp)).background(Ml.panel).padding(12.dp)) {
            ResponseCurve(env.preview.freqs, res.response, Modifier.fillMaxWidth().height(120.dp))
            FreqAxis()
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ml.panel)
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 6.dp)
        ) {
            GainColumn("Pre", s.preampDb, 12f, null, Ml.textDim, onChange = { v -> st.updateDsp { it.copy(preampDb = v) } }, onReset = { st.updateDsp { it.copy(preampDb = 0f) } })
            Spacer(Modifier.width(6.dp))
            for (i in 0 until DspSettings.GRAPHIC_BANDS) {
                val applied = if (s.faithful) res.graphicApplied.getOrNull(i)?.toFloat() else null
                GainColumn(
                    fmtHzShort(DspSettings.GRAPHIC_FREQS[i]), s.graphic.getOrElse(i) { 0f }, DspSettings.GRAPHIC_GAIN, applied, Ml.primary,
                    onChange = { v -> env.feedback.softTick(); st.updateDsp { it.withGraphic(i, v).copy(eqMode = Modes.EQ_GRAPHIC) } },
                    onReset = { st.updateDsp { it.withGraphic(i, 0f) } },
                )
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            PresetRow(env, s)
            Text(
                "Trascina un cursore in su o in giù; doppio tocco per azzerarlo." +
                    if (s.faithful) " Sotto ogni banda, in piccolo, il guadagno reale ricalcolato dalla curva fedele." else "",
                color = Ml.textFaint, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun GainColumn(label: String, value: Float, range: Float, applied: Float?, color: Color, onChange: (Float) -> Unit, onReset: () -> Unit) {
    Column(Modifier.width(46.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(fmt1(value), color = Ml.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        VerticalGain(value, range, color, onChange, onReset, Modifier.weight(1f).fillMaxWidth())
        Text(label, color = Ml.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(applied?.let { fmt1(it) } ?: " ", color = Ml.textFaint, fontSize = 9.sp)
    }
}

/** Cursore verticale: trascinamento relativo (non salta dove si tocca), passi da 0,5 dB, doppio tocco = 0. */
@Composable
private fun VerticalGain(value: Float, range: Float, color: Color, onChange: (Float) -> Unit, onReset: () -> Unit, modifier: Modifier) {
    val change = rememberUpdatedState(onChange)
    val reset = rememberUpdatedState(onReset)
    val cur = rememberUpdatedState(value)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset.value() }) }
            .pointerInput(Unit) {
                var startV = 0f
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { startV = cur.value; acc = 0f },
                    onVerticalDrag = { c, dy ->
                        c.consume()
                        acc += dy
                        val pad = 14.dp.toPx()
                        val h = (size.height - 2 * pad).coerceAtLeast(1f)
                        val v = startV - acc / h * (2 * range)
                        val q = ((v * 2).roundToInt() / 2f).coerceIn(-range, range)
                        if (q != cur.value) change.value(q)
                    }
                )
            }
    ) {
        val pad = 14.dp.toPx()
        val cx = size.width / 2f
        val h = size.height - 2 * pad
        fun y(v: Float) = pad + (1f - (v + range) / (2 * range)) * h
        drawLine(Ml.outlineVariant, Offset(cx, pad), Offset(cx, size.height - pad), 4.dp.toPx(), StrokeCap.Round)
        drawLine(Ml.outline, Offset(cx - 8.dp.toPx(), y(0f)), Offset(cx + 8.dp.toPx(), y(0f)), 1.dp.toPx())
        drawLine(color, Offset(cx, y(0f)), Offset(cx, y(value)), 4.dp.toPx(), StrokeCap.Round)
        val tw = 26.dp.toPx(); val th = 14.dp.toPx()
        drawRoundRect(color, Offset(cx - tw / 2, y(value) - th / 2), Size(tw, th), CornerRadius(th / 2, th / 2))
    }
}

// ------------------------------------------------------------------ EQ parametrico

private val bandColors = listOf(
    0xFFEF5350, 0xFFFFA726, 0xFFFFEE58, 0xFF9CCC65, 0xFF26A69A, 0xFF29B6F6, 0xFF5C6BC0, 0xFFAB47BC,
    0xFFEC407A, 0xFF8D6E63, 0xFF66BB6A, 0xFF26C6DA, 0xFF7E57C2, 0xFFFF7043, 0xFFD4E157, 0xFF78909C,
).map { Color(it) }

private const val PEQ_RANGE = 15f

/** Editor parametrico: tocca il grafico per aggiungere una banda, trascina i punti per regolarla. */
@Composable
fun ParametricEqScreen(env: AppEnv) {
    val st = env.settings
    val s = st.dsp
    val res = remember(s) { env.preview.compute(s) }
    val sel = env.peqSelected.coerceIn(0, DspSettings.PARAM_BANDS - 1)
    val band = s.parametric.getOrElse(sel) { PeqBand() }

    fun update(i: Int, b: PeqBand) = st.updateDsp { it.withBand(i, b).copy(eqMode = Modes.EQ_PARAMETRIC, master = true) }

    Column(Modifier.fillMaxSize().background(Ml.background).safeDrawingPadding()) {
        EditorTopBar(env, "EQ parametrico")
        Box(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp)).background(Ml.panel).padding(12.dp)) {
            Column {
                PeqGraph(
                    s, res.response, env.preview.freqs, sel,
                    onSelect = { env.peqSelected = it },
                    onAdd = { f, g ->
                        val free = s.parametric.indexOfFirst { !it.on }
                        if (free >= 0) {
                            env.peqSelected = free
                            update(free, PeqBand(true, FilterType.Peak, f, g, 1.41f))
                        }
                    },
                    onMove = { i, f, g ->
                        val b = s.parametric[i]
                        update(i, b.copy(freq = f, gain = if (b.type.usesGain) g else b.gain))
                    },
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
                FreqAxis()
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (i in 0 until DspSettings.PARAM_BANDS) {
                val b = s.parametric.getOrElse(i) { PeqBand() }
                Pill("${i + 1}", i == sel, accent = b.on) { env.peqSelected = i }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ml.panel)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(14.dp).height(14.dp).clip(RoundedCornerShape(50)).background(bandColors[sel]))
                Spacer(Modifier.width(8.dp))
                Text("Banda ${sel + 1}", color = Ml.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (band.on) "attiva" else "spenta", color = Ml.textDim, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                MlSwitch(band.on) { on -> update(sel, band.copy(on = on)) }
            }
            Choices(FilterType.entries.map { it.label }, band.type.ordinal) { i -> update(sel, band.copy(type = FilterType.entries[i], on = true)) }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Frequenza", band.freq, 0, "Hz", Modifier.weight(1.2f)) { v -> update(sel, band.copy(freq = v, on = true)) }
                if (band.type.usesGain) NumField("Guadagno", band.gain, 1, "dB", Modifier.weight(1f)) { v -> update(sel, band.copy(gain = v, on = true)) }
                NumField("Q", band.q, 2, "", Modifier.weight(0.8f)) { v -> update(sel, band.copy(q = v, on = true)) }
            }
            LabeledSlider("Frequenza", DspSettings.freqToPos(band.freq), 0f..1f, fmtHz(band.freq)) { p ->
                val raw = DspSettings.posToFreq(p)
                val step = if (raw < 100f) 1f else if (raw < 1000f) 5f else if (raw < 10000f) 50f else 100f
                update(sel, band.copy(freq = (raw / step).roundToInt() * step, on = true))
            }
            if (band.type.usesGain) {
                LabeledSlider("Guadagno", band.gain, -DspSettings.PEQ_GAIN..DspSettings.PEQ_GAIN, fmtDb(band.gain)) { v ->
                    update(sel, band.copy(gain = (v * 10).roundToInt() / 10f, on = true))
                }
            }
            val qPos = (ln(band.q / DspSettings.MIN_Q) / ln(DspSettings.MAX_Q / DspSettings.MIN_Q)).coerceIn(0f, 1f)
            LabeledSlider("Q", qPos, 0f..1f, "${fmt2(band.q)} · ${String.format(java.util.Locale.ITALY, "%.2f", DspSettings.octaves(band.q))} ott.") { p ->
                val q = DspSettings.MIN_Q * (DspSettings.MAX_Q / DspSettings.MIN_Q).pow(p)
                update(sel, band.copy(q = (q * 100).roundToInt() / 100f, on = true))
            }
            Spacer(Modifier.height(8.dp))
            LabeledSlider("Preamp", s.preampDb, -12f..12f, fmtDb(s.preampDb)) { v -> st.updateDsp { it.copy(preampDb = (v * 2).roundToInt() / 2f) } }
            Text(
                "Tocca un punto vuoto del grafico per aggiungere una banda, trascina un punto per spostarla. " +
                    "Passa-basso, passa-alto, passa-banda, notch e passa-tutto non usano il guadagno.",
                color = Ml.textFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PeqGraph(
    s: DspSettings,
    response: DoubleArray,
    freqs: DoubleArray,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: (Float, Float) -> Unit,
    onMove: (Int, Float, Float) -> Unit,
    modifier: Modifier,
) {
    val bands = rememberUpdatedState(s.parametric)
    val sel = rememberUpdatedState(selected)
    val select = rememberUpdatedState(onSelect)
    val add = rememberUpdatedState(onAdd)
    val move = rememberUpdatedState(onMove)

    fun xOf(f: Float, w: Float) = DspSettings.freqToPos(f) * w
    fun yOf(g: Float, h: Float) = (h / 2f - g / PEQ_RANGE * (h / 2f)).coerceIn(0f, h)
    fun nodeGain(b: PeqBand) = if (b.type.usesGain) b.gain else 0f

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    val near = bands.value.withIndex().filter { it.value.on }
                        .minByOrNull { (_, b) -> (Offset(xOf(b.freq, w), yOf(nodeGain(b), h)) - o).getDistance() }
                    if (near != null && (Offset(xOf(near.value.freq, w), yOf(nodeGain(near.value), h)) - o).getDistance() < 28.dp.toPx()) {
                        select.value(near.index)
                    } else {
                        val f = DspSettings.posToFreq(o.x / w)
                        val g = ((h / 2f - o.y) / (h / 2f) * PEQ_RANGE).coerceIn(-DspSettings.PEQ_GAIN, DspSettings.PEQ_GAIN)
                        add.value(f.roundToInt().toFloat(), (g * 2).roundToInt() / 2f)
                    }
                }
            }
            .pointerInput(Unit) {
                var target = -1
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val near = bands.value.withIndex().filter { it.value.on }
                            .minByOrNull { (_, b) -> (Offset(xOf(b.freq, w), yOf(nodeGain(b), h)) - o).getDistance() }
                        target = if (near != null && (Offset(xOf(near.value.freq, w), yOf(nodeGain(near.value), h)) - o).getDistance() < 44.dp.toPx()) near.index else -1
                        if (target >= 0) select.value(target)
                    },
                    onDrag = { c, _ ->
                        if (target < 0) return@detectDragGestures
                        c.consume()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        val f = DspSettings.posToFreq(c.position.x / w)
                        val g = ((h / 2f - c.position.y) / (h / 2f) * PEQ_RANGE).coerceIn(-DspSettings.PEQ_GAIN, DspSettings.PEQ_GAIN)
                        move.value(target, f.roundToInt().toFloat(), (g * 10).roundToInt() / 10f)
                    },
                )
            }
    ) {
        val w = size.width; val h = size.height
        for (f in listOf(100f, 1000f, 10000f)) drawLine(Ml.outlineVariant, Offset(xOf(f, w), 0f), Offset(xOf(f, w), h), 1f)
        for (d in listOf(-12f, -6f, 6f, 12f)) drawLine(Ml.outlineVariant.copy(alpha = 0.5f), Offset(0f, yOf(d, h)), Offset(w, yOf(d, h)), 1f)
        drawLine(Ml.outline, Offset(0f, yOf(0f, h)), Offset(w, yOf(0f, h)), 1.dp.toPx())
        if (response.size == freqs.size && freqs.isNotEmpty()) {
            val line = Path()
            for (i in freqs.indices) {
                val px = xOf(freqs[i].toFloat(), w); val py = yOf(response[i].toFloat(), h)
                if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
            }
            drawPath(line, Ml.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        bands.value.forEachIndexed { i, b ->
            if (!b.on) return@forEachIndexed
            val c = Offset(xOf(b.freq, w), yOf(nodeGain(b), h))
            if (i == sel.value) drawCircle(bandColors[i].copy(alpha = 0.3f), 18.dp.toPx(), c)
            drawCircle(bandColors[i], 9.dp.toPx(), c)
            drawCircle(Ml.background, 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
}
