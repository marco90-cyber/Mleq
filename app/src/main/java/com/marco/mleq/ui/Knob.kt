package com.marco.mleq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Manopola rotante (come Bassi / Alti di Poweramp).
 * Valore da 0 a 100; si ruota trascinando il dito attorno al centro.
 */
@Composable
fun Knob(
    value: Int,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    onTick: () -> Unit = {},
    onDetent: () -> Unit = {},
    zero: Int = 50,
    stepSize: Int = 5,
    onChange: (Int) -> Unit,
) {
    val current = rememberUpdatedState(value)
    val change = rememberUpdatedState(onChange)
    val tick = rememberUpdatedState(onTick)
    val detent = rememberUpdatedState(onDetent)
    var lastStep by remember { mutableIntStateOf(value / stepSize) }

    Canvas(
        modifier
            .size(size)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val v = magnet(valueAt(o, Offset(this.size.width / 2f, this.size.height / 2f)), zero)
                        change.value(v)
                    },
                    onDrag = { c, _ ->
                        c.consume()
                        val v = magnet(valueAt(c.position, Offset(this.size.width / 2f, this.size.height / 2f)), zero)
                        if (v != current.value) {
                            if (v == zero) {
                                lastStep = v / stepSize
                                detent.value()
                            } else if (v / stepSize != lastStep) {
                                lastStep = v / stepSize
                                tick.value()
                            }
                            change.value(v)
                        }
                    }
                )
            }
    ) {
        val w = this.size.minDimension
        val stroke = 6.dp.toPx()
        val arcSize = Size(w - stroke, w - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)

        // traccia
        drawArc(
            color = Ml.chip, startAngle = START, sweepAngle = SWEEP, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
        )
        // valore
        val frac = value.coerceIn(0, 100) / 100f
        if (frac > 0f) {
            // il colore dell'anello passa da verde acqua a giallo a rosso al crescere del valore
            val ringColor = if (frac < 0.5f)
                androidx.compose.ui.graphics.lerp(Color(0xFF4DD6B0), Color(0xFFE6E35A), frac * 2f)
            else androidx.compose.ui.graphics.lerp(Color(0xFFE6E35A), Color(0xFFFF5A5A), (frac - 0.5f) * 2f)
            drawArc(
                color = ringColor,
                startAngle = START, sweepAngle = SWEEP * frac, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        // corpo
        val bodyR = w / 2f - stroke * 1.9f
        drawCircle(
            brush = Brush.verticalGradient(listOf(Ml.outlineVariant, Ml.panel)),
            radius = bodyR, center = center
        )
        // indicatore
        val ang = (START + SWEEP * frac) * PI / 180.0
        val r1 = bodyR * 0.45f
        val r2 = bodyR * 0.82f
        drawLine(
            color = Ml.text,
            start = Offset(center.x + (cos(ang) * r1).toFloat(), center.y + (sin(ang) * r1).toFloat()),
            end = Offset(center.x + (cos(ang) * r2).toFloat(), center.y + (sin(ang) * r2).toFloat()),
            strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round
        )
    }
}

private const val MAGNET = 3

/** Calamita sullo zero: vicino allo zero il valore si aggancia a [zero]. */
private fun magnet(v: Int, zero: Int): Int = if (kotlin.math.abs(v - zero) <= MAGNET) zero else v

private const val START = 135f
private const val SWEEP = 270f

/** Posizione del dito -> valore 0..100 (la zona morta in basso si aggancia all'estremo più vicino). */
private fun valueAt(p: Offset, c: Offset): Int {
    var deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble()))
    if (deg < 0) deg += 360.0
    var rel = deg - START
    if (rel < 0) rel += 360.0
    val frac = if (rel > SWEEP) {
        if (rel < (SWEEP + 360.0) / 2.0) 1.0 else 0.0
    } else rel / SWEEP
    return (frac * 100.0).roundToInt().coerceIn(0, 100)
}
