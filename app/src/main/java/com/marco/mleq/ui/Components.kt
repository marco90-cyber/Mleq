package com.marco.mleq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/** Colore d'accento (dalla tavolozza Material You). */
internal val NeonGreen: Color get() = Ml.primary

internal fun fmt1(v: Float): String = String.format(Locale.ITALY, "%.1f", v)
internal fun fmt2(v: Float): String = String.format(Locale.ITALY, "%.2f", v)
internal fun fmtDb(v: Float): String = String.format(Locale.ITALY, "%+.1f dB", v)
internal fun fmtPct(v: Float): String = "${(v * 100).roundToInt()}%"
internal fun fmtHz(f: Float): String =
    if (f >= 1000f) String.format(Locale.ITALY, if (f >= 10000f) "%.1f kHz" else "%.2f kHz", f / 1000f).replace(",0 kHz", " kHz").replace(",00 kHz", " kHz")
    else "${f.roundToInt()} Hz"
internal fun fmtHzShort(f: Float): String =
    if (f >= 1000f) { val k = f / 1000f; if (k == k.roundToInt().toFloat()) "${k.roundToInt()}K" else String.format(Locale.ITALY, "%.1fK", k) } else "${f.roundToInt()}"

/** Slider orizzontale sottile, usato dalla barra del lettore. */
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
            .pointerInput(Unit) { detectTapGestures { o -> change.value(valueAt(o.x, size.width.toFloat(), 14.dp.toPx())) } }
            .pointerInput(Unit) {
                detectDragGestures { c, _ -> c.consume(); change.value(valueAt(c.position.x, size.width.toFloat(), 14.dp.toPx())) }
            }
    ) {
        val pad = 14.dp.toPx()
        val cy = size.height / 2f
        val frac = ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
        val xv = pad + frac * (size.width - 2 * pad)
        drawLine(Ml.outlineVariant, Offset(pad, cy), Offset(size.width - pad, cy), 4.dp.toPx(), StrokeCap.Round)
        drawLine(Ml.primary, Offset(pad, cy), Offset(xv, cy), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(Ml.primary, 8.dp.toPx(), Offset(xv, cy))
    }
}

/** Interruttore Material 3 con i colori del tema. */
@Composable
internal fun MlSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Ml.onPrimary, checkedTrackColor = Ml.primary,
            uncheckedThumbColor = Ml.outline, uncheckedTrackColor = Ml.chip, uncheckedBorderColor = Ml.outline,
        )
    )
}

/**
 * Scheda di un modulo: titolo, descrizione breve, (i) con la spiegazione, interruttore.
 * Il contenuto si vede quando il modulo è acceso (o sempre, se [enabled] è null).
 */
@Composable
internal fun ModuleCard(
    title: String,
    subtitle: String,
    info: String,
    enabled: Boolean?,
    onToggle: (Boolean) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Ml.panel)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ml.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Ml.textDim, fontSize = 12.sp, lineHeight = 15.sp)
            }
            IconButton(onClick = { showInfo = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = "Cos'è", tint = Ml.textDim)
            }
            if (enabled != null) MlSwitch(enabled, onToggle)
        }
        AnimatedVisibility(visible = enabled != false) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
    if (showInfo) InfoDialog(title, info) { showInfo = false }
}

@Composable
internal fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ok") } },
        containerColor = Ml.dialog, titleContentColor = Ml.text, textContentColor = Ml.textDim,
        title = { Text(title) },
        text = { Text(text, fontSize = 14.sp, lineHeight = 20.sp) },
    )
}

/** Slider con etichetta e valore. */
@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ml.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Ml.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Ml.primary, activeTrackColor = Ml.primary, inactiveTrackColor = Ml.chip,
            ),
            modifier = Modifier.height(32.dp)
        )
    }
}

/** Riga di scelte esclusive (pillole). */
@Composable
internal fun Choices(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, label -> Pill(label, i == selected) { onSelect(i) } }
    }
}

@Composable
internal fun Pill(text: String, selected: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Ml.primary else if (accent) Ml.primaryContainer else Ml.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Ml.onPrimary else Ml.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Riga con testo e interruttore. */
@Composable
internal fun SwitchRow(label: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Ml.text, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Ml.textFaint, fontSize = 12.sp)
        }
        MlSwitch(checked, onChange)
    }
}

/** Campo numerico: il valore si applica con Fine o uscendo dal campo. Accetta la virgola. */
@Composable
internal fun NumField(
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
        onValueChange = { text = it.take(9) },
        singleLine = true,
        label = { Text(label) },
        suffix = { Text(suffix, color = Ml.textDim) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged { st ->
            if (focused && !st.isFocused) commit()
            focused = st.isFocused
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ml.text, unfocusedTextColor = Ml.text,
            focusedLabelColor = Ml.primary, unfocusedLabelColor = Ml.textDim,
            focusedBorderColor = Ml.primary, unfocusedBorderColor = Ml.outlineVariant,
            cursorColor = Ml.primary,
        )
    )
}

/**
 * Grafico della risposta (dB su scala logaritmica di frequenza). [freqs] e [db] vengono dal motore
 * nativo, quindi la curva è quella che si sente. [markers] = punti da evidenziare (es. bande).
 */
@Composable
internal fun ResponseCurve(
    freqs: DoubleArray,
    db: DoubleArray,
    modifier: Modifier = Modifier,
    rangeDb: Float = 15f,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun x(f: Double) = (kotlin.math.ln(f / 20.0) / kotlin.math.ln(1000.0)).toFloat() * w
        fun y(v: Double) = (h / 2f - (v.toFloat() / rangeDb) * (h / 2f)).coerceIn(0f, h)
        // griglia: decadi e linee a +-6/12 dB
        for (f in listOf(100.0, 1000.0, 10000.0)) drawLine(Ml.outlineVariant, Offset(x(f), 0f), Offset(x(f), h), 1f)
        for (d in listOf(-12.0, -6.0, 6.0, 12.0)) if (kotlin.math.abs(d) < rangeDb) drawLine(Ml.outlineVariant.copy(alpha = 0.5f), Offset(0f, y(d)), Offset(w, y(d)), 1f)
        drawLine(Ml.outline, Offset(0f, y(0.0)), Offset(w, y(0.0)), 1.dp.toPx())
        if (freqs.isEmpty() || db.size != freqs.size) return@Canvas
        val line = Path()
        val fill = Path()
        fill.moveTo(0f, y(0.0))
        for (i in freqs.indices) {
            val px = x(freqs[i]); val py = y(db[i])
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
            fill.lineTo(px, py)
        }
        fill.lineTo(w, y(0.0))
        fill.close()
        drawPath(fill, Ml.primary.copy(alpha = 0.18f))
        drawPath(line, Ml.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Etichette delle frequenze sotto un grafico (stessa scala logaritmica 20 Hz - 20 kHz). */
@Composable
internal fun FreqAxis(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(14.dp)) {
        val w = maxWidth
        for ((f, label) in listOf(20f to "20", 100f to "100", 1000f to "1k", 10000f to "10k")) {
            val pos = com.marco.mleq.audio.DspSettings.freqToPos(f)
            Text(
                label, color = Ml.textFaint, fontSize = 10.sp,
                modifier = Modifier.padding(start = (w * pos).coerceAtMost(w - 24.dp))
            )
        }
    }
}
