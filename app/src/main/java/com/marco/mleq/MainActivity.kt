package com.marco.mleq

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.marco.mleq.ui.Actions
import com.marco.mleq.ui.EqApp
import com.marco.mleq.util.AppLog

class MainActivity : ComponentActivity(), Actions {

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isEmpty()) return@registerForActivityResult
            for (u in uris) keepAccess(u)
            (application as MLEqApp).engine.playUris(uris)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("App", "Permesso notifiche: ${if (granted) "concesso" else "negato"}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val app = application as MLEqApp
        val env = app.env
        env.actions = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { EqApp(env) }
        // alla ricreazione dell'Activity l'intent di apertura non va rieseguito (riavvierebbe il brano)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Apri con MLEqualizer" da un file manager. */
    private fun handleIntent(i: Intent?) {
        val data = i?.data ?: return
        if (i.action == Intent.ACTION_VIEW) {
            keepAccess(data)
            (application as MLEqApp).engine.playUris(listOf(data))
        }
    }

    private fun keepAccess(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // non tutte le sorgenti lo permettono: il permesso temporaneo basta per la sessione
        }
    }

    override fun onDestroy() {
        val env = (application as MLEqApp).env
        if (env.actions === this) env.actions = null
        super.onDestroy()
    }

    // ---- Actions

    override fun pickFiles() {
        try {
            filePicker.launch(arrayOf("audio/*", "application/ogg"))
        } catch (e: Exception) {
            AppLog.e("App", "Selettore file non disponibile", e)
        }
    }

    override fun shareLog() {
        try {
            val file = AppLog.exportFile(this)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Log di MLEqualizer")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Condividi log"))
        } catch (e: Exception) {
            AppLog.e("App", "Condivisione del log non riuscita", e)
        }
    }
}
