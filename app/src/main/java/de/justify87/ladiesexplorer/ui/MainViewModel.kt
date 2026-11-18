package de.justify87.ladiesexplorer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.justify87.ladiesexplorer.core.CoreStore
import de.justify87.ladiesexplorer.core.MediaArchiveReport
import de.justify87.ladiesexplorer.core.ImportReport
import de.justify87.ladiesexplorer.core.ProfileResponse
import de.justify87.ladiesexplorer.core.SearchRequest
import de.justify87.ladiesexplorer.core.SearchResponse
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
    val diagnosticAvailable: Boolean = false,
    val diagnosticSizeBytes: Long = 0,
    val diagnosticLastError: String? = null,
    val error: String? = null,
)

data class SearchUiState(
    val city: String = "Leipzig",
    val radiusKm: UInt = 20u,
    val loading: Boolean = false,
    val response: SearchResponse? = null,
    val profile: ProfileResponse? = null,
    val mediaArchive: MediaArchiveReport? = null,
    val mediaWarning: String? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val core = CoreStore(app.applicationContext)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    private val _searchUiState = MutableStateFlow(SearchUiState())
    val searchUiState: StateFlow<SearchUiState> = _searchUiState


    fun setSearchCity(city: String) {
        _searchUiState.update {
            it.copy(city = city, response = null, profile = null, mediaArchive = null, mediaWarning = null, error = null)
        }
    }

    fun setSearchRadius(radiusKm: UInt) {
        _searchUiState.update {
            it.copy(radiusKm = radiusKm, response = null, profile = null, mediaArchive = null, mediaWarning = null, error = null)
        }
    }

    fun startSearch() {
        if (_searchUiState.value.loading || _uiState.value.loading) return
        val input = _searchUiState.value
        _searchUiState.update {
            it.copy(loading = true, error = null, profile = null, mediaArchive = null, mediaWarning = null, response = null)
        }
        viewModelScope.launch {
            try {
                val request = SearchRequest(
                    city = input.city.trim(),
                    radiusKm = input.radiusKm,
                    category = "",
                    ageMin = 18u,
                    ageMax = 60u,
                    heightMin = 140u,
                    heightMax = 200u,
                    filtersJson = "{}",
                    maxPages = 10u,
                )
                val result = core.search(request)
                _searchUiState.update {
                    it.copy(loading = false, response = result, error = null)
                }
                publishStatus()
            } catch (cancellation: CancellationException) {
                _searchUiState.update { it.copy(loading = false) }
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update {
                    it.copy(loading = false, error = failure.message ?: "Online-Suche fehlgeschlagen")
                }
            }
        }
    }

    fun loadProfile(listingId: Long) {
        if (_searchUiState.value.loading || _uiState.value.loading) return
        _searchUiState.update { it.copy(loading = true, error = null, profile = null, mediaArchive = null, mediaWarning = null) }
        viewModelScope.launch {
            try {
                val profile = core.loadProfile(listingId)
                _searchUiState.update {
                    it.copy(loading = false, profile = profile)
                }
                // Media archiving must never invalidate a successfully stored profile.
                try {
                    val media = core.archiveProfileMedia(profile.snapshotId)
                    _searchUiState.update { it.copy(mediaArchive = media) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    _searchUiState.update {
                        it.copy(mediaWarning = failure.message ?: "Medienarchivierung fehlgeschlagen")
                    }
                }
            } catch (cancellation: CancellationException) {
                _searchUiState.update { it.copy(loading = false) }
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update {
                    it.copy(loading = false, error = failure.message ?: "Profil konnte nicht geladen werden")
                }
            }
        }
    }

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
        if (_uiState.value.loading || _searchUiState.value.loading) return
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

    suspend fun prepareDiagnosticSnapshot(): File = core.createDiagnosticSnapshot()

    private suspend fun publishStatus() {
        val status = core.status()
        val diagnostic = core.diagnosticStatus()
        _uiState.update {
            it.copy(
                loading = false,
                schemaVersion = status.schemaVersion.toString(),
                diagnosticAvailable = diagnostic.available,
                diagnosticSizeBytes = diagnostic.sizeBytes.toLong(),
                diagnosticLastError = diagnostic.lastWriteError,
                personCount = status.personCount.toString(),
                sourceProfileCount = status.sourceProfileCount.toString(),
                listingCount = status.listingCount.toString(),
            )
        }
    }
}
