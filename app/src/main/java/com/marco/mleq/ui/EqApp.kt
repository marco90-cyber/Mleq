package com.marco.mleq.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EqApp(env: AppEnv) {
    MlTheme(dynamic = env.settings.dynamicColors, amoled = env.settings.amoled) {
        val activity = LocalContext.current as? Activity

        // Il tasto indietro chiude la schermata aperta; dalla home manda l'app in secondo piano
        BackHandler {
            if (env.overlay != Overlay.None) env.overlay = Overlay.None
            else activity?.moveTaskToBack(true)
        }

        Box(Modifier.fillMaxSize().background(Ml.background)) {
            MainScreen(env)

            OverlayHost(env, env.overlay == Overlay.Analyzer) { AnalyzerOverlay(env) }
            OverlayHost(env, env.overlay == Overlay.Output) { OutputScreen(env) }
            OverlayHost(env, env.overlay == Overlay.Log) { LogScreen(env) }
            OverlayHost(env, env.overlay == Overlay.Graphic) { GraphicEqScreen(env) }
            OverlayHost(env, env.overlay == Overlay.Parametric) { ParametricEqScreen(env) }
            OverlayHost(env, env.overlay == Overlay.About) { AboutScreen(env) }
        }
        com.marco.mleq.AppUpdateHost()
    }
}

/**
 * Contenitore delle schermate a tutto schermo: intercetta i tocchi, così non arrivano
 * all'equalizzatore che sta sotto.
 */
@Composable
private fun OverlayHost(env: AppEnv, visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } }
        ) { content() }
    }
}

@Composable
private fun AnalyzerOverlay(env: AppEnv) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ml.background)
            .safeDrawingPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { env.overlay = Overlay.None }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = Ml.text)
            }
            Text("Analizzatore", color = Ml.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) { AnalyzerView(env) }
    }
}
