package de.justify87.ladiesexplorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.justify87.ladiesexplorer.ui.LadiesApp
import de.justify87.ladiesexplorer.ui.LadiesTheme
import de.justify87.ladiesexplorer.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LadiesTheme {
                LadiesApp(viewModel)
            }
        }
    }
}
