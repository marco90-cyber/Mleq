package com.marco.mleq.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marco.mleq.BuildConfig

private class Credit(val name: String, val what: String, val license: String, val url: String? = null)

private val credits = listOf(
    Credit("Tryptify", "Idea dell'algoritmo del limiter look-ahead (massimo mobile, ritardo, rilascio), riscritto per MLEqualizer", "GPL-3.0", "https://github.com/tryptz/Tryptify"),
    Credit("libsoxr (SoX Resampler Library)", "Ricampionamento dell'uscita adattiva", "LGPL-2.1 o successiva", "https://sourceforge.net/projects/soxr/"),
    Credit("AndroidX Media3 / ExoPlayer", "Lettore, decoder e uscita audio", "Apache-2.0", "https://github.com/androidx/media"),
    Credit("Jetpack Compose e Material 3", "Interfaccia e colori dinamici", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
    Credit("Audio EQ Cookbook (Robert Bristow-Johnson)", "Formule dei filtri biquad", "Pubblico dominio / documento libero"),
    Credit("Martin Vicanek, \"Matched Second Order Digital Filters\"", "Metodo dei filtri \"matched\" più fedeli vicino a Nyquist", "Articolo pubblico"),
    Credit("ITU-R BS.1770-4 / EBU R128", "Misura della loudness per il ReplayGain senza tag", "Norme pubbliche"),
    Credit("Linkwitz-Riley", "Divisione in bande dei bassi (Analog Bass, DBFB)", "Metodo pubblico"),
)

private val inspirations = listOf(
    "Poweramp: toni con frequenza e Q, compatibilità dei preset",
    "JadOO DSP: idea dei moduli Analog Bass, Hi-Res Air, Surround+, DBFB e Loudness",
    "Mornye: grafica Material You a schede",
    "AutoEQ: formato dei preset per cuffie",
)

/** Informazioni sull'app e crediti. */
@Composable
fun AboutScreen(env: AppEnv) {
    val ctx = LocalContext.current
    fun open(url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
    Column(Modifier.fillMaxSize().background(Ml.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { env.overlay = Overlay.None }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Ml.text) }
            Text("Informazioni e crediti", color = Ml.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box24 {
                Text("MLEqualizer", color = Ml.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Versione ${BuildConfig.VERSION_NAME}", color = Ml.textDim, fontSize = 13.sp)
                Text("di Marco · parte di MLapps", color = Ml.textDim, fontSize = 13.sp)
                Text(
                    "Equalizzatore e processore audio con motore DSP nativo scritto per questo progetto. " +
                        "Software libero: GPL-3.0 o successiva.",
                    color = Ml.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Codice sorgente su GitHub", color = Ml.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp).clickable { open(BuildConfig.REPO_URL) }
                )
            }
            Box24 {
                Text("Crediti", color = Ml.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                for (c in credits) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 10.dp)
                            .then(if (c.url != null) Modifier.clickable { open(c.url) } else Modifier)
                    ) {
                        Text(c.name, color = if (c.url != null) Ml.primary else Ml.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(c.what, color = Ml.textDim, fontSize = 12.sp)
                        Text("Licenza: ${c.license}", color = Ml.textFaint, fontSize = 11.sp)
                    }
                }
            }
            Box24 {
                Text("Ispirazioni", color = Ml.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Queste app hanno ispirato alcune funzioni o la grafica. Nessun codice è stato copiato: " +
                        "tutto è stato riscritto da zero.",
                    color = Ml.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                )
                for (i in inspirations) Text("• $i", color = Ml.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Box24(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Ml.panel).padding(16.dp), content = content)
}
