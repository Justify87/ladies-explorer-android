package de.justify87.ladiesexplorer

import android.content.ClipData
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
                        val snapshot = viewModel.prepareDiagnosticSnapshot()
                        try {
                            val output = contentResolver.openOutputStream(uri, "wt")
                                ?: error("Die Zieldatei konnte nicht geöffnet werden.")
                            snapshot.inputStream().use { input ->
                                output.use { destination -> input.copyTo(destination) }
                            }
                        } finally {
                            snapshot.delete()
                        }
                    }
                }
                toast(result.fold(
                    onSuccess = { "Debug-Protokoll gespeichert." },
                    onFailure = { "Export fehlgeschlagen: ${it.message}" },
                ))
                viewModel.refresh()
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun shareDiagnostics() {
        lifecycleScope.launch {
            val snapshot = runCatching { viewModel.prepareDiagnosticSnapshot() }
                .getOrElse { failure ->
                    toast("Log-Snapshot fehlgeschlagen: ${failure.message}")
                    viewModel.refresh()
                    return@launch
                }
            try {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", snapshot)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, "Ladies Explorer Debug-Log", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Debug-Protokoll teilen").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(chooser)
                // Retain the immutable snapshot in cache while the receiving app reads it.
            } catch (failure: Exception) {
                snapshot.delete()
                toast("Teilen fehlgeschlagen: ${failure.message}")
            }
            viewModel.refresh()
        }
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
