package de.justify87.ladiesexplorer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

data class DiscoverUiState(
    val cards: List<DiscoveryCard> = emptyList(),
    val favorites: List<DiscoveryCard> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

data class SearchUiState(
    val city: String = "Leipzig",
    val radiusKm: UInt = 20u,
    val category: String = "",
    val ageMin: UInt = 18u,
    val ageMax: UInt = 60u,
    val heightMin: UInt = 140u,
    val heightMax: UInt = 200u,
    val maxPages: UInt = 10u,
    val selectedFilters: Map<String, Set<String>> = emptyMap(),
    val filterCatalog: SourceFilterCatalog? = null,
    val savedSearches: List<SavedSearchInfo> = emptyList(),
    val showingSavedSearchId: String? = null,
    val savedResultCards: List<SavedSearchCard> = emptyList(),
    val editingSavedSearchId: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val response: SearchResponse? = null,
    val profileOpen: Boolean = false,
    val profile: ProfileResponse? = null,
    val selectedPersonId: Long? = null,
    val selectedPerson: PersonSummary? = null,
    val profileMedia: List<ProfileMediaItem> = emptyList(),
    val changeEvents: List<ChangeRecord> = emptyList(),
    val identityCandidates: List<IdentityCandidate> = emptyList(),
    val actionBusy: Boolean = false,
    val actionError: String? = null,
    val mediaArchive: MediaArchiveReport? = null,
    val archiving: Boolean = false,
    val mediaWarning: String? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val core = CoreStore(app.applicationContext)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState
    private val _discoverState = MutableStateFlow(DiscoverUiState())
    val discoverState: StateFlow<DiscoverUiState> = _discoverState
    private val _searchUiState = MutableStateFlow(SearchUiState())
    val searchUiState: StateFlow<SearchUiState> = _searchUiState

    private var archiveControl: MediaArchiveControl? = null
    private var profileRequestId = 0L
    private var discoveryEpoch = 0L
    private val skippedPeople = mutableSetOf<Long>()

    init {
        refresh()
        refreshDiscovery()
        refreshSearchDefinitions()
    }

    private fun invalidateProfileRequest() {
        profileRequestId++
        archiveControl?.cancel()
        archiveControl = null
    }

    override fun onCleared() {
        invalidateProfileRequest()
        super.onCleared()
    }

    fun cancelMediaArchive() {
        archiveControl?.cancel()
        _searchUiState.update { it.copy(archiving = false) }
    }

    fun closeProfile() {
        invalidateProfileRequest()
        _searchUiState.update {
            it.copy(profileOpen = false, profile = null, selectedPersonId = null,
                selectedPerson = null, profileMedia = emptyList(), loading = false,
                archiving = false, changeEvents = emptyList(), identityCandidates = emptyList(),
                actionBusy = false, actionError = null, error = null)
        }
    }

    private fun invalidateSearchResults() {
        invalidateProfileRequest()
        _searchUiState.update {
            it.copy(response = null, profileOpen = false, profile = null, error = null,
                showingSavedSearchId = null, savedResultCards = emptyList(),
                mediaArchive = null, mediaWarning = null, archiving = false)
        }
    }

    fun setSearchCity(value: String) {
        invalidateSearchResults()
        _searchUiState.update { it.copy(city = value) }
    }

    fun setSearchRadius(value: UInt) {
        invalidateSearchResults()
        _searchUiState.update { it.copy(radiusKm = value) }
    }

    fun setCategory(value: String) {
        invalidateSearchResults()
        _searchUiState.update { it.copy(category = value) }
    }

    fun setAgeRange(min: UInt, max: UInt) {
        invalidateSearchResults()
        _searchUiState.update { it.copy(ageMin = min, ageMax = max) }
    }

    fun setHeightRange(min: UInt, max: UInt) {
        invalidateSearchResults()
        _searchUiState.update { it.copy(heightMin = min, heightMax = max) }
    }

    fun setMaxPages(value: UInt) {
        invalidateSearchResults()
        _searchUiState.update { it.copy(maxPages = value.coerceIn(1u,50u)) }
    }

    fun toggleFilter(group: String, value: String) {
        invalidateSearchResults()
        _searchUiState.update {
            val updated = it.selectedFilters.toMutableMap()
            val selected = (updated[group] ?: emptySet()).toMutableSet()
            if (!selected.add(value)) selected.remove(value)
            if (selected.isEmpty()) updated.remove(group) else updated[group] = selected
            it.copy(selectedFilters = updated)
        }
    }

    private fun request(input: SearchUiState): SearchRequest {
        val filters = JSONObject()
        input.selectedFilters.forEach { (group, values) ->
            filters.put(group, JSONArray(values.sorted()))
        }
        return SearchRequest(
            city = input.city.trim(),
            radiusKm = input.radiusKm,
            category = input.category,
            ageMin = input.ageMin,
            ageMax = input.ageMax,
            heightMin = input.heightMin,
            heightMax = input.heightMax,
            filtersJson = filters.toString(),
            maxPages = input.maxPages,
        )
    }

    fun refreshSearchDefinitions() {
        viewModelScope.launch {
            try {
                val catalog = core.sourceFilterCatalog()
                val saved = core.savedSearches()
                _searchUiState.update { it.copy(filterCatalog = catalog, savedSearches = saved) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update { it.copy(error = failure.message ?: "Filter konnten nicht geladen werden") }
            }
        }
    }

    fun createNewSavedSearch() {
        _searchUiState.update { it.copy(editingSavedSearchId = null) }
    }

    fun saveCurrentSearch(name: String) {
        if (_searchUiState.value.saving) return
        val input = _searchUiState.value
        _searchUiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                if (input.editingSavedSearchId != null) {
                    core.updateSavedSearch(input.editingSavedSearchId, name, request(input))
                } else {
                    core.saveSearch(name, request(input))
                }
                _searchUiState.update { it.copy(savedSearches = core.savedSearches(), saving = false) }
            } catch (cancellation: CancellationException) {
                _searchUiState.update { it.copy(saving = false) }
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update { it.copy(saving = false,error = failure.message ?: "Suche konnte nicht gespeichert werden") }
            }
        }
    }

    fun loadSavedSearch(id: String) {
        if (_searchUiState.value.loading) return
        viewModelScope.launch {
            try {
                val q = core.loadSavedSearch(id)
                val selected = JSONObject(q.filtersJson)
                val values = mutableMapOf<String,Set<String>>()
                for (key in selected.keys()) {
                    val array = selected.getJSONArray(key)
                    values[key] = (0 until array.length()).map { array.getString(it) }.toSet()
                }
                invalidateSearchResults()
                _searchUiState.update {
                    it.copy(city=q.city,radiusKm=q.radiusKm,category=q.category,
                        ageMin=q.ageMin,ageMax=q.ageMax,heightMin=q.heightMin,
                        heightMax=q.heightMax,maxPages=q.maxPages,selectedFilters=values,
                        editingSavedSearchId=id)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update { it.copy(error = failure.message ?: "Suche konnte nicht geladen werden") }
            }
        }
    }

    fun deleteSavedSearch(id: String) {
        viewModelScope.launch {
            try {
                core.deleteSavedSearch(id)
                _searchUiState.update { it.copy(savedSearches = core.savedSearches(),
                    editingSavedSearchId = if (it.editingSavedSearchId == id) null else it.editingSavedSearchId) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update { it.copy(error = failure.message ?: "Gespeicherte Suche konnte nicht entfernt werden") }
            }
        }
    }

    fun closeSavedResults() {
        _searchUiState.update { it.copy(showingSavedSearchId = null, savedResultCards = emptyList()) }
    }

    fun showSavedResults(id: String) {
        viewModelScope.launch {
            try {
                val results = core.savedSearchResults(id)
                core.acknowledgeSavedSearch(id)
                _searchUiState.update {
                    it.copy(showingSavedSearchId=id,savedResultCards=results,
                        savedSearches=core.savedSearches(),error=null)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update { it.copy(error=failure.message ?: "Gespeicherte Ergebnisse konnten nicht geladen werden") }
            }
        }
    }

    fun startSearch(savedId: String? = null) {
        if (_searchUiState.value.loading || _uiState.value.loading) return
        val input = _searchUiState.value
        invalidateProfileRequest()
        _searchUiState.update { it.copy(loading = true,error = null,profile = null,profileOpen = false,response = null,
                showingSavedSearchId = null,savedResultCards = emptyList()) }
        viewModelScope.launch {
            try {
                val result = if (savedId == null) core.search(request(input)) else core.runSavedSearch(savedId)
                skippedPeople.clear()
                _searchUiState.update {
                    it.copy(loading = false,response = result,savedSearches = core.savedSearches())
                }
                refreshDiscovery()
                refresh()
            } catch (cancellation: CancellationException) {
                _searchUiState.update { it.copy(loading = false) }
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update { it.copy(loading = false,error = failure.message ?: "Online-Suche fehlgeschlagen") }
            }
        }
    }

    fun acknowledgeSavedSearch(id: String) {
        viewModelScope.launch {
            try {
                core.acknowledgeSavedSearch(id)
                _searchUiState.update { it.copy(savedSearches = core.savedSearches()) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update { it.copy(error = failure.message ?: "Status konnte nicht gespeichert werden") }
            }
        }
    }

    fun refreshDiscovery() {
        if (_discoverState.value.busy) return
        val epoch = ++discoveryEpoch
        viewModelScope.launch {
            _discoverState.update { it.copy(loading = true) }
            try {
                val cards = core.discoveryCards().filterNot { it.personId in skippedPeople }
                val favorites = core.discoveryCards(true)
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        it.copy(cards=cards,favorites=favorites,loading=false,error=null)
                    }
                }
            } catch (cancellation: CancellationException) {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(loading=false) }
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) {
                    _discoverState.update { it.copy(loading=false,error=failure.message ?: "Entdecken fehlgeschlagen") }
                }
            }
        }
    }

    fun skipCard(personId: Long) {
        skippedPeople.add(personId)
        _discoverState.update { it.copy(cards=it.cards.filterNot { card -> card.personId==personId }) }
    }

    fun choosePreference(personId: Long, preference: Preference) {
        if (_discoverState.value.busy) return
        val epoch = ++discoveryEpoch
        _discoverState.update { it.copy(busy=true,error=null) }
        viewModelScope.launch {
            try {
                core.setPreference(personId,preference)
                skippedPeople.remove(personId)
                val cards = core.discoveryCards().filterNot { it.personId in skippedPeople }
                val favorites = core.discoveryCards(true)
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(cards=cards,favorites=favorites,busy=false) }
                val updatedPerson = core.person(personId)
                _searchUiState.update {
                    if (it.selectedPersonId == personId) it.copy(selectedPerson=updatedPerson) else it
                }
            } catch (cancellation: CancellationException) {
                _discoverState.update { it.copy(busy=false) }
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(busy=false,error=failure.message ?: "Entscheidung nicht gespeichert") }
            }
        }
    }

    fun toggleFavorite(personId: Long) {
        if (_discoverState.value.busy || _searchUiState.value.actionBusy) return
        val epoch = ++discoveryEpoch
        _discoverState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val person = core.person(personId) ?: error("Person nicht gefunden")
                core.setFavorite(personId,!person.favorite)
                val cards = core.discoveryCards().filterNot { it.personId in skippedPeople }
                val favorites = core.discoveryCards(true)
                if (epoch == discoveryEpoch) {
                    _discoverState.update { it.copy(cards=cards,favorites=favorites,busy=false,error=null) }
                }
                val personState = core.person(personId)
                _searchUiState.update {
                    if (it.selectedPersonId == personId) it.copy(selectedPerson=personState) else it
                }
            } catch (cancellation: CancellationException) {
                _discoverState.update { it.copy(busy=false) }
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(busy=false,error=failure.message ?: "Favorit konnte nicht geändert werden") }
            }
        }
    }

    fun loadProfile(listingId: Long, personId: Long) {
        invalidateProfileRequest()
        val requestId = profileRequestId
        _searchUiState.update {
            it.copy(profileOpen=true,selectedPersonId=personId,selectedPerson=null,
                loading=true,profile=null,profileMedia=emptyList(),changeEvents=emptyList(),
                identityCandidates=emptyList(),error=null,actionBusy=false,actionError=null,
                mediaArchive=null,mediaWarning=null,archiving=false)
        }
        viewModelScope.launch {
            try {
                var showingArchive = false
                var refreshError: String? = null
                val profile = try {
                    core.loadProfile(listingId)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    val archived = core.cachedProfile(listingId) ?: throw failure
                    showingArchive = true
                    refreshError = "Archivierte Profildaten: Online-Aktualisierung fehlgeschlagen (${failure.message ?: "Netzwerk nicht verfügbar"})."
                    archived
                }
                if (requestId != profileRequestId) return@launch
                val items = core.profileMedia(profile.snapshotId)
                val person = core.person(personId)
                val changes = core.changeEvents(personId)
                val candidates = core.identityCandidates(listingId)
                if (requestId != profileRequestId) return@launch
                _searchUiState.update {
                    it.copy(loading=false,profile=profile,selectedPerson=person,
                        profileMedia=items,changeEvents=changes,identityCandidates=candidates,
                        mediaWarning=refreshError,archiving=!showingArchive)
                }
                if (showingArchive) return@launch
                val control = MediaArchiveControl()
                archiveControl = control
                try {
                    val report = core.archiveProfileMedia(profile.snapshotId,control)
                    val updatedMedia = core.profileMedia(profile.snapshotId)
                    if (requestId == profileRequestId) {
                        _searchUiState.update { it.copy(profileMedia=updatedMedia,mediaArchive=report) }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (requestId == profileRequestId) {
                        _searchUiState.update {
                            it.copy(mediaWarning=failure.message ?: "Medienarchivierung fehlgeschlagen")
                        }
                    }
                } finally {
                    if (archiveControl === control) archiveControl = null
                    if (requestId == profileRequestId) _searchUiState.update { it.copy(archiving=false) }
                }
            } catch (cancellation: CancellationException) {
                if (requestId == profileRequestId) _searchUiState.update { it.copy(loading=false,archiving=false) }
                throw cancellation
            } catch (failure: Exception) {
                if (requestId == profileRequestId) {
                    _searchUiState.update {
                        it.copy(loading=false,archiving=false,error=failure.message ?: "Profil konnte nicht geladen werden")
                    }
                }
            }
        }
    }

    fun confirmIdentity(listingId: Long, targetPersonId: Long) {
        val active = _searchUiState.value
        if (active.actionBusy || !active.profileOpen || active.profile?.listingId != listingId) return
        val requestId = profileRequestId
        archiveControl?.cancel()
        _searchUiState.update { it.copy(actionBusy=true,actionError=null) }
        viewModelScope.launch {
            try {
                core.confirmIdentityMatch(listingId,targetPersonId)
                val history = core.changeEvents(targetPersonId)
                val candidates = core.identityCandidates(listingId)
                val person = core.person(targetPersonId)
                if (requestId == profileRequestId) {
                    _searchUiState.update {
                        if (it.profile?.listingId == listingId) {
                            it.copy(actionBusy=false,selectedPersonId=targetPersonId,
                                selectedPerson=person,changeEvents=history,identityCandidates=candidates)
                        } else it
                    }
                }
                refreshDiscovery()
                refresh()
            } catch (cancellation: CancellationException) {
                if (requestId == profileRequestId) {
                    _searchUiState.update { it.copy(actionBusy=false) }
                }
                throw cancellation
            } catch (failure: Exception) {
                if (requestId == profileRequestId) {
                    _searchUiState.update {
                        it.copy(actionBusy=false,actionError=failure.message ?: "Zusammenführung fehlgeschlagen")
                    }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading=true,error=null) }
            try {
                val status = core.status()
                val diagnostic = core.diagnosticStatus()
                _uiState.update {
                    it.copy(loading=false,schemaVersion=status.schemaVersion.toString(),
                        personCount=status.personCount.toString(),sourceProfileCount=status.sourceProfileCount.toString(),
                        listingCount=status.listingCount.toString(),diagnosticAvailable=diagnostic.available,
                        diagnosticSizeBytes=diagnostic.sizeBytes.toLong(),
                        diagnosticLastError=diagnostic.lastWriteError)
                }
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(loading=false) }
                throw cancellation
            } catch (failure: Exception) {
                _uiState.update { it.copy(loading=false,error=failure.message ?: "Rust-Datenbank nicht verfügbar") }
            }
        }
    }

    fun importBundle(uri: Uri) {
        if (_uiState.value.loading || _searchUiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading=true,error=null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val temp = File.createTempFile("ladies-migration-",".zip",context.cacheDir)
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Das ausgewählte Archiv konnte nicht geöffnet werden")
                        input.use { stream -> temp.outputStream().use { stream.copyTo(it) } }
                        core.importLegacyBundle(temp)
                    } finally {
                        temp.delete()
                    }
                }
                _uiState.update { it.copy(lastImport=result) }
                refresh()
                refreshDiscovery()
                refreshSearchDefinitions()
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(loading=false) }
                throw cancellation
            } catch (failure: Exception) {
                _uiState.update { it.copy(loading=false,error=failure.message ?: "Datenimport fehlgeschlagen") }
            }
        }
    }

    suspend fun prepareDiagnosticSnapshot(): File = core.createDiagnosticSnapshot()
}
