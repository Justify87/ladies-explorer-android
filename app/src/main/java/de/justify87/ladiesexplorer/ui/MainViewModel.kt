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
    val coreVersion: String? = null,
    val storedNotes: String = "0",
    val draftNote: String = "",
    val lastSavedNote: String? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val core = CoreStore(app.applicationContext)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        refresh()
    }

    fun updateNote(value: String) {
        _uiState.update { it.copy(draftNote = value) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching {
                val status = core.status()
                val saved = core.loadTestNote()
                status to saved
            }.onSuccess { (status, saved) ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        coreVersion = status.apiVersion.toString(),
                        storedNotes = status.storedNotes.toString(),
                        lastSavedNote = saved,
                    )
                }
            }.onFailure { failure ->
                _uiState.update { it.copy(loading = false, error = failure.message ?: "Rust core unavailable") }
            }
        }
    }

    fun saveTestNote() {
        val note = _uiState.value.draftNote
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            runCatching { core.saveTestNote(note) }
                .onSuccess { status ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            storedNotes = status.storedNotes.toString(),
                            lastSavedNote = note,
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(loading = false, error = failure.message ?: "Storage failed") }
                }
        }
    }
}
