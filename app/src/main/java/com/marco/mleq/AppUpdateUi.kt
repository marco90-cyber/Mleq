package com.marco.mleq

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Da mettere una volta nella radice dell'interfaccia: mostra la finestra quando si chiama [AppUpdate.open]. */
@Composable
fun AppUpdateHost() {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l = AppUpdate.Listener { tick++ }
        AppUpdate.addListener(l)
        onDispose { AppUpdate.removeListener(l) }
    }
    // "tick" fa ricomporre a ogni cambio di stato del motore
    if (tick >= 0 && AppUpdate.visible) AppUpdateDialog(AppUpdate.state)
}

@Composable
private fun AppUpdateDialog(s: AppUpdate.State) {
    val ctx = LocalContext.current
    val downloading = s is AppUpdate.State.Downloading
    AlertDialog(
        onDismissRequest = { AppUpdate.hide() },
        properties = DialogProperties(dismissOnBackPress = !downloading, dismissOnClickOutside = !downloading),
        title = {
            Text(
                when (s) {
                    AppUpdate.State.Idle, AppUpdate.State.Checking -> "Controllo aggiornamenti…"
                    is AppUpdate.State.UpToDate -> "Nessun aggiornamento"
                    is AppUpdate.State.Available -> "Nuova versione disponibile"
                    is AppUpdate.State.Downloading -> "Download in corso…"
                    is AppUpdate.State.Paused -> "Download in pausa"
                    is AppUpdate.State.Ready -> "Pronto da installare"
                    is AppUpdate.State.Failed -> "Aggiornamento non riuscito"
                }
            )
        },
        text = {
            // Lo schermo resta acceso finché la finestra mostra il download
            val view = LocalView.current
            DisposableEffect(downloading) {
                if (downloading) view.keepScreenOn = true
                onDispose { if (downloading) view.keepScreenOn = false }
            }
            Column(Modifier.fillMaxWidth()) {
                when (s) {
                    AppUpdate.State.Idle, AppUpdate.State.Checking ->
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    is AppUpdate.State.UpToDate ->
                        Text("Hai già l'ultima versione (${s.installed}).")
                    is AppUpdate.State.Available -> ReleaseInfo(s.release)
                    is AppUpdate.State.Downloading -> {
                        Text(s.release.name, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Bar(s.done, s.total)
                        Spacer(Modifier.height(8.dp))
                        Text(AppUpdate.progressText(s.done, s.total, s.speed), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Lo schermo resta acceso fino alla fine del download.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is AppUpdate.State.Paused -> {
                        Text(s.release.name, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Bar(s.done, s.total)
                        Spacer(Modifier.height(8.dp))
                        Text(AppUpdate.progressText(s.done, s.total), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tocca «Riprendi» per continuare da dove si è fermato.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is AppUpdate.State.Ready ->
                        Text("${s.release.name} è stata scaricata. Tocca «Installa» e conferma nella finestra di Android.")
                    is AppUpdate.State.Failed -> {
                        Text(s.message)
                        if (s.release != null && s.done > 0) {
                            Spacer(Modifier.height(12.dp))
                            Bar(s.done, s.total)
                            Spacer(Modifier.height(8.dp))
                            Text(AppUpdate.progressText(s.done, s.total), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s) {
                    is AppUpdate.State.Available ->
                        Button(onClick = { AppUpdate.download(ctx) }) { Text("Scarica") }
                    is AppUpdate.State.Downloading -> {
                        TextButton(onClick = { AppUpdate.cancel(ctx) }) { Text("Annulla") }
                        FilledTonalButton(onClick = { AppUpdate.pause() }) { Text("Pausa") }
                    }
                    is AppUpdate.State.Paused -> {
                        TextButton(onClick = { AppUpdate.cancel(ctx) }) { Text("Annulla") }
                        Button(onClick = { AppUpdate.download(ctx) }) { Text("Riprendi") }
                    }
                    is AppUpdate.State.Ready ->
                        Button(onClick = { AppUpdate.install(ctx) }) { Text("Installa") }
                    is AppUpdate.State.Failed ->
                        if (s.release != null) {
                            TextButton(onClick = { AppUpdate.cancel(ctx) }) { Text("Annulla") }
                            Button(onClick = { AppUpdate.download(ctx) }) { Text("Riprendi") }
                        } else {
                            TextButton(onClick = { AppUpdate.openPage(ctx) }) { Text("Apri GitHub") }
                            Button(onClick = { AppUpdate.check(ctx) }) { Text("Riprova") }
                        }
                    else -> TextButton(onClick = { AppUpdate.hide() }) { Text("OK") }
                }
            }
        },
        dismissButton = {
            if (s is AppUpdate.State.Available || s is AppUpdate.State.Paused || s is AppUpdate.State.Ready || s is AppUpdate.State.Failed) {
                TextButton(onClick = { AppUpdate.hide() }) { Text("Chiudi") }
            }
        },
    )
}

@Composable
private fun Bar(done: Long, total: Long) {
    if (total > 0) {
        LinearProgressIndicator(
            progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReleaseInfo(r: AppUpdate.Release) {
    val ctx = LocalContext.current
    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
        Text("${r.name} (installata: ${AppUpdate.installedName(ctx)})", style = MaterialTheme.typography.bodyMedium)
        if (r.size > 0) {
            Spacer(Modifier.height(4.dp))
            Text("Dimensione: ${AppUpdate.mb(r.size)}", style = MaterialTheme.typography.bodySmall)
        }
        if (r.notes.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(r.notes.take(1500), style = MaterialTheme.typography.bodySmall)
        }
    }
}
