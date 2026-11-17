package de.justify87.ladiesexplorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import de.justify87.ladiesexplorer.ui.LadiesApp
import de.justify87.ladiesexplorer.ui.LadiesTheme
import de.justify87.ladiesexplorer.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val pickLegacyBundle = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> uri?.let(viewModel::importBundle)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LadiesTheme {
                LadiesApp(
                    viewModel = viewModel,
                    onPickLegacyBundle = {
                        pickLegacyBundle.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                )
            }
        }
    }
}
