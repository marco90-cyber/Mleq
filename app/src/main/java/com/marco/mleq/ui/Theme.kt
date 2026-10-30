package com.marco.mleq.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

/** Colori usati dalle schermate, ricavati dallo schema Material You (colori dinamici dello sfondo). */
@Immutable
class MlPalette(
    val background: Color,
    val panel: Color,
    val chip: Color,
    val dialog: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val tertiary: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val outline: Color,
    val outlineVariant: Color,
)

/**
 * Accesso alla palette attuale da qualsiasi punto (anche nei blocchi di disegno): è uno stato,
 * quindi le schermate che la leggono si ridisegnano quando cambia.
 */
object Ml {
    private val fallback = fromScheme(fallbackScheme(), amoled = false)
    var palette by mutableStateOf(fallback)

    val background: Color get() = palette.background
    val panel: Color get() = palette.panel
    val chip: Color get() = palette.chip
    val dialog: Color get() = palette.dialog
    val primary: Color get() = palette.primary
    val onPrimary: Color get() = palette.onPrimary
    val primaryContainer: Color get() = palette.primaryContainer
    val tertiary: Color get() = palette.tertiary
    val text: Color get() = palette.text
    val textDim: Color get() = palette.textDim
    val textFaint: Color get() = palette.textFaint
    val outline: Color get() = palette.outline
    val outlineVariant: Color get() = palette.outlineVariant

    fun fromScheme(s: androidx.compose.material3.ColorScheme, amoled: Boolean) = MlPalette(
        background = if (amoled) Color.Black else s.surface,
        panel = if (amoled) lerp(s.surfaceContainerLowest, Color.Black, 0.5f) else s.surfaceContainer,
        chip = if (amoled) s.surfaceContainer else s.surfaceContainerHighest,
        dialog = s.surfaceContainerHigh,
        primary = s.primary,
        onPrimary = s.onPrimary,
        primaryContainer = s.primaryContainer,
        tertiary = s.tertiary,
        text = s.onSurface,
        textDim = s.onSurfaceVariant,
        textFaint = s.outline,
        outline = s.outline,
        outlineVariant = s.outlineVariant,
    )

    /** Schema di riserva (Android 11 o colori dinamici spenti): grafite con accento azzurro. */
    fun fallbackScheme() = darkColorScheme(
        primary = Color(0xFF57D6F0), onPrimary = Color(0xFF00363F),
        primaryContainer = Color(0xFF004E5B), onPrimaryContainer = Color(0xFFA2EEFF),
        secondary = Color(0xFFB2CBD2), tertiary = Color(0xFFA9B8FF),
        surface = Color(0xFF0F1416), onSurface = Color(0xFFDEE3E6), onSurfaceVariant = Color(0xFFBFC8CC),
        surfaceContainerLowest = Color(0xFF0A0F11), surfaceContainerLow = Color(0xFF171C1E),
        surfaceContainer = Color(0xFF1B2023), surfaceContainerHigh = Color(0xFF252B2D),
        surfaceContainerHighest = Color(0xFF303638),
        outline = Color(0xFF899296), outlineVariant = Color(0xFF3F484B),
    )
}

/** Tema dell'app: sempre scuro; colori dinamici su Android 12+ (se attivi), sfondo nero opzionale (AMOLED). */
@Composable
fun MlTheme(dynamic: Boolean, amoled: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(ctx) else Ml.fallbackScheme()
    val palette = Ml.fromScheme(scheme, amoled)
    // la palette va impostata prima che i figli la leggano
    Ml.palette = palette
    SideEffect { Ml.palette = palette }
    MaterialTheme(colorScheme = scheme, content = content)
}
