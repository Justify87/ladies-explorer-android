package de.justify87.ladiesexplorer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.justify87.ladiesexplorer.core.CoreStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val schemaVersion: String? = null,
    val personCount: String = "0",
    val sourceProfileCount: String = "0",
    val listingCount: String = "0",
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
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { core.status() }
                .onSuccess { status ->
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
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(loading = false, error = failure.message ?: "Rust-Datenbank nicht verfügbar")
                    }
                }
        }
    }
}
