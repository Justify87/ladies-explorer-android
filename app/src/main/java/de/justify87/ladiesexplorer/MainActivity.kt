package de.justify87.ladiesexplorer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import de.justify87.ladiesexplorer.ui.LadiesApp
import de.justify87.ladiesexplorer.ui.LadiesTheme
import de.justify87.ladiesexplorer.ui.MainViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val pickLegacyBundle = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> uri?.let(viewModel::importBundle)
    }
    private val saveDiagnostics = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val log = diagnosticFile()
                        require(log.isFile) { "Noch kein Diagnoseprotokoll vorhanden." }
                        val output = contentResolver.openOutputStream(uri, "wt")
                            ?: error("Die Zieldatei konnte nicht geöffnet werden.")
                        log.inputStream().use { source ->
                            output.use { destination -> source.copyTo(destination) }
                        }
                    }
                }
                val message = result.fold(
                    onSuccess = { "Debug-Protokoll gespeichert." },
                    onFailure = { "Export fehlgeschlagen: ${it.message}" },
                )
                toast(message)
            }
        }
    }

    private fun diagnosticFile(): File = File(filesDir, "diagnostics/ladies-debug.log")

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun shareDiagnostics() {
        val log = diagnosticFile()
        if (!log.isFile) {
            toast("Noch kein Diagnoseprotokoll vorhanden. Bitte App neu öffnen.")
            return
        }
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            log,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Debug-Protokoll teilen"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LadiesTheme {
                LadiesApp(
                    viewModel = viewModel,
                    onPickLegacyBundle = {
                        pickLegacyBundle.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                            )
                        )
                    },
                    onSaveDiagnostics = {
                        saveDiagnostics.launch("ladies-debug-${System.currentTimeMillis()}.txt")
                    },
                    onShareDiagnostics = ::shareDiagnostics,
                )
            }
        }
    }
}
