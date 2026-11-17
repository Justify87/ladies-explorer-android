package de.justify87.ladiesexplorer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.justify87.ladiesexplorer.core.CoreStore
import de.justify87.ladiesexplorer.core.ImportReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class HomeUiState(
    val loading: Boolean = true,
    val schemaVersion: String? = null,
    val personCount: String = "0",
    val sourceProfileCount: String = "0",
    val listingCount: String = "0",
    val lastImport: ImportReport? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val core = CoreStore(app.applicationContext)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.loading && _uiState.value.schemaVersion != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                publishStatus()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = failure.message ?: "Rust-Datenbank nicht verfügbar")
                }
            }
        }
    }

    fun importBundle(uri: Uri) {
        if (_uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    val tempFile = File.createTempFile("ladies-migration-", ".zip", application.cacheDir)
                    try {
                        val stream = application.contentResolver.openInputStream(uri)
                            ?: error("Das ausgewählte Archiv konnte nicht geöffnet werden.")
                        stream.use { input ->
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        core.importLegacyBundle(tempFile)
                    } finally {
                        tempFile.delete()
                    }
                }
                _uiState.update { it.copy(lastImport = result) }
                publishStatus()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _uiState.update {
                    it.copy(loading = false, error = failure.message ?: "Datenimport fehlgeschlagen")
                }
            }
        }
    }

    private suspend fun publishStatus() {
        val status = core.status()
        _uiState.update {
            it.copy(
                loading = false,
                schemaVersion = status.schemaVersion.toString(),
                personCount = status.personCount.toString(),
                sourceProfileCount = status.sourceProfileCount.toString(),
                listingCount = status.listingCount.toString(),
            )
        }
    }
}
