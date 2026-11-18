package de.justify87.ladiesexplorer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.justify87.ladiesexplorer.core.*
import de.justify87.ladiesexplorer.LadiesApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val debugLoggingEnabled: Boolean = false,
    val httpCaptureMode: CaptureMode = CaptureMode.OFF,
    val httpCaptureEntries: UInt = 0u,
    val httpCaptureBytes: ULong = 0uL,
    val httpCaptureStopped: Boolean = false,
    val httpCaptureError: String? = null,
    val diagnosticAvailable: Boolean = false,
    val diagnosticSizeBytes: Long = 0,
    val diagnosticLastError: String? = null,
    val error: String? = null,
)

data class DiscoverUiState(
    val cards: List<DiscoveryCard> = emptyList(),
    val favorites: List<DiscoveryCard> = emptyList(),
    val nextCursor: DiscoveryCursor? = null,
    val activeRunId: Long? = null,
    val activeVisibleCount: Int = 0,
    val replayLikes: Boolean = true,
    val canUndoSwipe: Boolean = false,
    val favoritesNextCursor: DiscoveryCursor? = null,
    val loadingMore: Boolean = false,
    val favoritesLoadingMore: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

data class MapUiState(
    val overview: MapOverview? = null,
    val loading: Boolean = false,
    val resolving: Boolean = false,
    val cancelling: Boolean = false,
    val failedLookups: UInt = 0u,
    val favoritesOnly: Boolean = false,
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
    val local: LocalVisibility = defaultLocalVisibility(),
    val frequentFilters: List<FrequentFilter> = emptyList(),
    val preview: SearchPreview? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val activeCards: List<DiscoveryCard> = emptyList(),
    val savedSearches: List<SavedSearchInfo> = emptyList(),
    val showingSavedSearchId: String? = null,
    val savedResultCards: List<SavedSearchCard> = emptyList(),
    val savedResultCursor: SavedResultCursor? = null,
    val savedResultsLoadingMore: Boolean = false,
    val editingSavedSearchId: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val response: SearchResponse? = null,
    val profileOpen: Boolean = false,
    val profileSessionId: Long = 0L,
    val profile: ProfileResponse? = null,
    val profileIdentity: ProfileIdentity? = null,
    val profileFromArchive: Boolean = false,
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
    private val core = (app as LadiesApplication).coreStore
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState
    private val _discoverState = MutableStateFlow(DiscoverUiState())
    val discoverState: StateFlow<DiscoverUiState> = _discoverState
    private val _searchUiState = MutableStateFlow(SearchUiState())
    val searchUiState: StateFlow<SearchUiState> = _searchUiState

    private val _mapState = MutableStateFlow(MapUiState())
    val mapState: StateFlow<MapUiState> = _mapState
    private var mapEpoch = 0L
    private var mapControl: RequestControl? = null

    private var archiveControl: RequestControl? = null
    private var searchControl: RequestControl? = null
    private var previewControl: RequestControl? = null
    private var previewJob: Job? = null
    private val previewGeneration = RequestGeneration()
    private var previewVisible = false
    private var profileRequestId = 0L
    private var discoveryEpoch = 0L
    private val searchGeneration = RequestGeneration()
    private val swipeSession = SwipeSession()

    init {
        refresh()
        refreshDiscovery()
        refreshSearchDefinitions()
    }

    private fun invalidateActiveSearch() {
        searchGeneration.invalidate()
        searchControl?.cancel()
        searchControl = null
        _searchUiState.update { it.copy(savedResultsLoadingMore = false) }
    }

    fun cancelActiveSearch() {
        invalidateActiveSearch()
        _searchUiState.update { it.endRun() }
        schedulePreview()
    }

    private fun invalidateProfileRequest() {
        profileRequestId++
        archiveControl?.cancel()
        archiveControl = null
    }

    override fun onCleared() {
        cancelMapResolution()
        cancelPreview()
        invalidateActiveSearch()
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
            it.copy(profileOpen = false, profile = null, profileIdentity = null, profileFromArchive = false, selectedPersonId = null,
                savedResultsLoadingMore = false,
                selectedPerson = null, profileMedia = emptyList(), loading = false,
                archiving = false, changeEvents = emptyList(), identityCandidates = emptyList(),
                actionBusy = false, actionError = null, error = null)
        }
    }

    private fun cancelPreview() {
        previewGeneration.invalidate()
        previewControl?.cancel()
        previewControl = null
        previewJob?.cancel()
        previewJob = null
        _searchUiState.update { it.copy(previewLoading = false) }
    }

    fun setPreviewVisible(visible: Boolean) {
        if (previewVisible == visible) return
        previewVisible = visible
        if (visible) schedulePreview() else cancelPreview()
    }

    private fun schedulePreview() {
        cancelPreview()
        _searchUiState.update { it.copy(preview = null, previewError = null) }
        val input = _searchUiState.value
        if (!previewVisible || input.loading || input.city.isBlank()) return
        val epoch = previewGeneration.value
        val control = RequestControl()
        previewControl = control
        _searchUiState.update { it.copy(previewLoading = true) }
        previewJob = viewModelScope.launch {
            try {
                delay(350)
                val result = core.preview(request(input), control)
                if (previewGeneration.isCurrent(epoch)) _searchUiState.update {
                    it.copy(preview = result, previewLoading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (previewGeneration.isCurrent(epoch)) _searchUiState.update {
                    it.copy(previewLoading = false, previewError = failure.message ?: "Vorschau nicht verfügbar")
                }
            } finally {
                if (previewGeneration.isCurrent(epoch)) previewControl = null
            }
        }
    }

    private fun editDraft(change: (SearchUiState) -> SearchUiState) {
        val current = _searchUiState.value
        if (current.loading) return
        val next = change(current)
        if (current == next) return
        // Cancel pending editor/paging work, not the previous committed active run.
        invalidateActiveSearch()
        _searchUiState.value = current.applyDraftChange(next)
        // Page cap and local rules do not change the source's total count.
        if (request(current).copy(maxPages = 1u) != request(next).copy(maxPages = 1u)) schedulePreview()
    }

    fun setSearchCity(value: String) = editDraft { it.copy(city = value) }
    fun setSearchRadius(value: UInt) = editDraft { it.copy(radiusKm = value) }
    fun setCategory(value: String) = editDraft { it.copy(category = value) }
    fun setAgeRange(min: UInt, max: UInt) = editDraft { it.copy(ageMin = min, ageMax = max) }
    fun setHeightRange(min: UInt, max: UInt) = editDraft { it.copy(heightMin = min, heightMax = max) }
    fun setMaxPages(value: UInt) = editDraft { it.copy(maxPages = value.coerceIn(1u, 50u)) }

    fun setLocalVisibility(value: LocalVisibility) = editDraft { it.copy(local = value) }

    fun resetSearchDraft() = editDraft {
        it.copy(city = "Leipzig", radiusKm = 20u, category = "", ageMin = 18u, ageMax = 60u,
            heightMin = 140u, heightMax = 200u, maxPages = 10u, selectedFilters = emptyMap(),
            local = defaultLocalVisibility())
    }

    fun setAvailability(mode: AvailabilityMode) =
        editDraft { it.copy(selectedFilters = withAvailability(it.selectedFilters, mode)) }

    fun runAvailability(mode: AvailabilityMode) {
        val state = _searchUiState.value
        val active = state.response
        val query = (active?.query ?: request(state)).withAvailabilityMode(mode)
        startSearch(requestOverride = query, localOverride = active?.local ?: state.local)
    }

    fun toggleFilter(group: String, value: String) = editDraft {
        if (group == "zeiten" && value in setOf("jetzt geöffnet", "jetzt-offen", "heute geöffnet", "heute-offen")) {
            val target = if (value.startsWith("jetzt")) AvailabilityMode.Now else AvailabilityMode.Today
            it.copy(selectedFilters = withAvailability(it.selectedFilters,
                if (availabilityMode(it.selectedFilters) == target) AvailabilityMode.All else target))
        } else {
            val selected = it.selectedFilters[group].orEmpty().toMutableSet()
            if (!selected.add(value)) selected.remove(value)
            it.copy(selectedFilters = if (selected.isEmpty()) it.selectedFilters - group
                else it.selectedFilters + (group to selected.toSet()))
        }
    }

    private fun request(input: SearchUiState): SearchRequest {
        return SearchRequest(
            city = input.city.trim(),
            radiusKm = input.radiusKm,
            category = input.category,
            ageMin = input.ageMin,
            ageMax = input.ageMax,
            heightMin = input.heightMin,
            heightMax = input.heightMax,
            filtersJson = encodeSelectedFilters(input.selectedFilters),
            maxPages = input.maxPages,
        )
    }

    fun refreshSearchDefinitions() {
        viewModelScope.launch {
            try {
                val catalog = core.sourceFilterCatalog()
                val saved = core.savedSearches()
                val frequent = core.frequentFilters()
                _searchUiState.update { it.copy(filterCatalog = catalog, savedSearches = saved, frequentFilters = frequent) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _searchUiState.update { it.copy(error = failure.message ?: "Filter konnten nicht geladen werden") }
            }
        }
    }

    fun createNewSavedSearch() {
        if (_searchUiState.value.loading) return
        invalidateActiveSearch()
        _searchUiState.update { it.copy(editingSavedSearchId = null) }
    }

    fun saveCurrentSearch(name: String) {
        if (_searchUiState.value.saving || _searchUiState.value.loading) return
        val input = _searchUiState.value
        _searchUiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                if (input.editingSavedSearchId != null) {
                    core.updateSavedSearch(input.editingSavedSearchId, name, request(input), input.local)
                } else {
                    core.saveSearch(name, request(input), input.local)
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
        if (_searchUiState.value.loading || _searchUiState.value.saving) return
        invalidateActiveSearch()
        val epoch = searchGeneration.value
        viewModelScope.launch {
            try {
                val definition = core.loadSavedSearch(id)
                val q = definition.query
                val values = decodeSelectedFilters(q.filtersJson)
                if (!searchGeneration.isCurrent(epoch)) return@launch
                editDraft {
                    it.copy(city=q.city,radiusKm=q.radiusKm,category=q.category,
                        ageMin=q.ageMin,ageMax=q.ageMax,heightMin=q.heightMin,
                        heightMax=q.heightMax,maxPages=q.maxPages,selectedFilters=values,
                        local=definition.local, editingSavedSearchId=id)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (searchGeneration.isCurrent(epoch)) {
                    _searchUiState.update { it.copy(error = failure.message ?: "Suche konnte nicht geladen werden") }
                }
            }
        }
    }

    fun deleteSavedSearch(id: String) {
        if (_searchUiState.value.loading || _searchUiState.value.saving) return
        invalidateActiveSearch()
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
        if (_searchUiState.value.loading) return
        invalidateActiveSearch()
        _searchUiState.update {
            it.copy(showingSavedSearchId = null, savedResultCards = emptyList(),
                savedResultCursor = null, savedResultsLoadingMore = false)
        }
    }

    fun showSavedResults(id: String) {
        if (_searchUiState.value.loading) return
        invalidateActiveSearch()
        // Bind the pending load to the new list immediately. Never page the previous cursor
        // under the new generation; Back can invalidate the pending first page as well.
        _searchUiState.update { it.beginSavedResults(id) }
        val epoch = searchGeneration.value
        viewModelScope.launch {
            try {
                val page = core.savedSearchResults(id)
                if (!searchGeneration.isCurrent(epoch)) return@launch
                _searchUiState.update {
                    it.copy(showingSavedSearchId=id,savedResultCards=page.cards,
                        savedResultCursor=page.nextCursor,savedResultsLoadingMore=false,error=null)
                }
                core.acknowledgeSavedSearch(id)
                val saved = core.savedSearches()
                if (searchGeneration.isCurrent(epoch)) {
                    _searchUiState.update { it.copy(savedSearches = saved) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (searchGeneration.isCurrent(epoch)) {
                    _searchUiState.update { it.copy(savedResultsLoadingMore = false, error=failure.message ?: "Gespeicherte Ergebnisse konnten nicht geladen werden") }
                }
            }
        }
    }

    fun loadMoreSavedResults() {
        val state = _searchUiState.value
        val id = state.showingSavedSearchId ?: return
        val cursor = state.savedResultCursor ?: return
        if (state.savedResultsLoadingMore || state.loading) return
        val epoch = searchGeneration.value
        _searchUiState.update { it.copy(savedResultsLoadingMore = true) }
        viewModelScope.launch {
            try {
                val page = core.savedSearchResults(id, cursor)
                if (searchGeneration.isCurrent(epoch)) {
                    _searchUiState.update {
                        if (it.showingSavedSearchId != id) return@update it
                        val known = it.savedResultCards.mapTo(mutableSetOf()) { card -> card.resultKey }
                        it.copy(
                            savedResultCards = it.savedResultCards +
                                page.cards.filter { card -> known.add(card.resultKey) },
                            savedResultCursor = page.nextCursor,
                            savedResultsLoadingMore = false,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                if (searchGeneration.isCurrent(epoch)) {
                    _searchUiState.update { it.copy(savedResultsLoadingMore = false) }
                }
                throw cancellation
            } catch (failure: Exception) {
                if (searchGeneration.isCurrent(epoch)) {
                    _searchUiState.update {
                        it.copy(savedResultsLoadingMore = false,
                            error = failure.message ?: "Weitere Suchergebnisse konnten nicht geladen werden")
                    }
                }
            }
        }
    }

    fun startSearch(savedId: String? = null, requestOverride: SearchRequest? = null, localOverride: LocalVisibility? = null) {
        if (_searchUiState.value.loading || _searchUiState.value.saving || _uiState.value.loading || _discoverState.value.busy) return
        val input = _searchUiState.value
        cancelPreview()
        invalidateActiveSearch()
        val epoch = searchGeneration.value
        val control = RequestControl()
        searchControl = control
        invalidateProfileRequest()
        _searchUiState.update { it.beginRun() }
        viewModelScope.launch {
            try {
                val result = if (savedId == null) core.search(requestOverride ?: request(input), localOverride ?: input.local, control)
                else core.runSavedSearch(savedId, control)
                if (!searchGeneration.isCurrent(epoch)) return@launch
                val cards = core.activeSearchCards(result.searchRunId, result.local)
                if (!searchGeneration.isCurrent(epoch)) return@launch
                swipeSession.reset()
                discoveryEpoch++
                _discoverState.update {
                    it.copy(cards = swipeSession.queue(cards, active = true), canUndoSwipe = false, nextCursor = null, activeRunId = result.searchRunId,
                        activeVisibleCount = cards.size, loading = false, loadingMore = false,
                        favoritesLoadingMore = false, error = null)
                }
                _searchUiState.update { it.acceptRun(result, cards) }
                refreshDiscovery()
                refreshSearchDefinitions()
                refreshMap()
                refresh()
            } catch (cancellation: CancellationException) {
                if (searchGeneration.isCurrent(epoch)) _searchUiState.update { it.endRun() }
                throw cancellation
            } catch (failure: Exception) {
                if (searchGeneration.isCurrent(epoch)) {
                    _searchUiState.update {
                        it.endRun(failure.message ?: "Online-Suche fehlgeschlagen")
                    }
                }
            } finally {
                if (searchControl === control) {
                    searchControl = null
                    if (_searchUiState.value.loading.not()) schedulePreview()
                }
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

    fun refreshMap() {
        if (mapControl != null) return // completion refreshes from the actual database
        val epoch = ++mapEpoch
        val favoritesOnly = _mapState.value.favoritesOnly
        _mapState.update { it.copy(loading = true, error = null, failedLookups = 0u) }
        viewModelScope.launch {
            try {
                val overview = core.mapOverview(favoritesOnly)
                if (epoch == mapEpoch) _mapState.update {
                    it.copy(overview = overview, loading = false)
                }
            } catch (cancelled: CancellationException) {
                if (epoch == mapEpoch) _mapState.update { it.copy(loading = false) }
                throw cancelled
            } catch (failure: Exception) {
                if (epoch == mapEpoch) _mapState.update {
                    it.copy(loading = false, error = failure.message ?: "Karte konnte nicht geladen werden")
                }
            }
        }
    }

    fun setMapFavoritesOnly(value: Boolean) {
        if (_mapState.value.favoritesOnly == value) return
        cancelMapResolution()
        _mapState.update { it.copy(favoritesOnly = value, overview = null, failedLookups = 0u) }
        refreshMap()
    }

    fun cancelMapResolution() {
        val control = mapControl ?: return
        control.cancel()
        _mapState.update { it.copy(cancelling = true) }
    }

    fun resolveMapLocations() {
        if (mapControl != null || _mapState.value.loading) return
        val control = RequestControl()
        val favoritesOnly = _mapState.value.favoritesOnly
        mapControl = control
        _mapState.update { it.copy(resolving = true, cancelling = false, error = null, failedLookups = 0u) }
        viewModelScope.launch {
            var failureMessage: String? = null
            var failedLookups = 0u
            try {
                failedLookups = core.resolveMapLocations(favoritesOnly, control)
            } catch (cancelled: CancellationException) {
                control.cancel()
                throw cancelled
            } catch (failure: Exception) {
                if (!_mapState.value.cancelling) {
                    failureMessage = failure.message ?: "Standortauflösung fehlgeschlagen"
                }
            } finally {
                if (mapControl === control) {
                    mapControl = null
                    _mapState.update { it.copy(resolving = false, cancelling = false) }
                }
            }
            // Re-read even on explicit cancellation: already committed results and
            // intervening search/favorite changes must be visible.
            val epoch = ++mapEpoch
            _mapState.update { it.copy(loading = true) }
            try {
                val latest = core.mapOverview(_mapState.value.favoritesOnly)
                if (epoch == mapEpoch) _mapState.update {
                    it.copy(overview = latest, failedLookups = failedLookups,
                        loading = false, error = failureMessage)
                }
            } catch (cancelled: CancellationException) {
                if (epoch == mapEpoch) _mapState.update { it.copy(loading = false) }
                throw cancelled
            } catch (failure: Exception) {
                if (epoch == mapEpoch) _mapState.update {
                    it.copy(loading = false, error = failure.message ?: "Karte konnte nicht geladen werden")
                }
            }
        }
    }

    private data class DiscoveryProjection(
        val runId: Long?,
        val visible: List<DiscoveryCard>,
        val page: DiscoveryPage,
    )

    private suspend fun discoveryPage(): DiscoveryProjection {
        val active = _searchUiState.value.response
        if (active == null) {
            return DiscoveryProjection(null, emptyList(), core.discoveryCards(excludedPeople = swipeSession.excludedPeople))
        }
        val visible = core.activeSearchCards(active.searchRunId, active.local)
        return DiscoveryProjection(active.searchRunId, visible, DiscoveryPage(visible, null))
    }

    private fun publishDiscovery(
        epoch: Long,
        projection: DiscoveryProjection,
        favorites: DiscoveryPage,
    ) {
        if (epoch != discoveryEpoch || projection.runId != _searchUiState.value.response?.searchRunId) return
        _searchUiState.update { it.copy(activeCards = projection.visible) }
        _discoverState.update {
            it.copy(
                cards = swipeSession.queue(projection.page.cards, active = projection.runId != null),
                replayLikes = swipeSession.replayLikes,
                canUndoSwipe = swipeSession.last != null,
                activeRunId = projection.runId,
                activeVisibleCount = projection.visible.size,
                nextCursor = projection.page.nextCursor,
                favorites = favorites.cards,
                favoritesNextCursor = favorites.nextCursor,
                loading = false, busy = false, loadingMore = false, favoritesLoadingMore = false, error = null,
            )
        }
    }

    fun refreshDiscovery() {
        if (_discoverState.value.busy) return
        val epoch = ++discoveryEpoch
        viewModelScope.launch {
            _discoverState.update {
                it.copy(loading = true, loadingMore = false, favoritesLoadingMore = false)
            }
            try {
                val cards = discoveryPage()
                val favorites = core.discoveryCards(favoritesOnly = true)
                publishDiscovery(epoch, cards, favorites)
            } catch (cancellation: CancellationException) {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(loading = false) }
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        it.copy(loading = false, error = failure.message ?: "Entdecken fehlgeschlagen")
                    }
                }
            }
        }
    }

    fun loadMoreDiscovery() {
        if (_searchUiState.value.response != null) return
        val state = _discoverState.value
        val cursor = state.nextCursor ?: return
        if (state.loading || state.loadingMore || state.busy) return
        val epoch = discoveryEpoch
        _discoverState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = core.discoveryCards(cursor = cursor, excludedPeople = swipeSession.excludedPeople)
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        it.copy(
                            cards = swipeSession.queue(it.cards + page.cards, active = false),
                            nextCursor = page.nextCursor,
                            loadingMore = false,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(loadingMore = false) }
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        it.copy(loadingMore = false, error = failure.message ?: "Weitere Profile konnten nicht geladen werden")
                    }
                }
            }
        }
    }

    fun loadMoreFavorites() {
        val state = _discoverState.value
        val cursor = state.favoritesNextCursor ?: return
        if (state.loading || state.favoritesLoadingMore || state.busy) return
        val epoch = discoveryEpoch
        _discoverState.update { it.copy(favoritesLoadingMore = true) }
        viewModelScope.launch {
            try {
                val page = core.discoveryCards(favoritesOnly = true, cursor = cursor)
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        val known = it.favorites.mapTo(mutableSetOf()) { card -> card.personId }
                        it.copy(
                            favorites = it.favorites + page.cards.filter { card -> known.add(card.personId) },
                            favoritesNextCursor = page.nextCursor,
                            favoritesLoadingMore = false,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(favoritesLoadingMore = false) }
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        it.copy(favoritesLoadingMore = false, error = failure.message ?: "Weitere Favoriten konnten nicht geladen werden")
                    }
                }
            }
        }
    }

    fun resetSwipe() {
        if (_discoverState.value.busy || _searchUiState.value.loading) return
        swipeSession.reset()
        _discoverState.update { it.copy(canUndoSwipe = false) }
        refreshDiscovery()
    }

    fun setReplayLikes(replay: Boolean) {
        if (_discoverState.value.busy || _searchUiState.value.loading || swipeSession.replayLikes == replay) return
        swipeSession.replayLikes = replay
        _discoverState.update { it.copy(replayLikes = replay) }
        refreshDiscovery()
    }

    fun skipCard(listingId: Long) {
        val state = _discoverState.value
        if (state.busy || state.loading || _searchUiState.value.loading) return
        val card = state.cards.firstOrNull()?.takeIf { it.listingId == listingId } ?: return
        swipeSession.visit(SwipeStep(card.listingId, card.personId))
        _discoverState.update {
            it.copy(cards = swipeSession.queue(it.cards, active = it.activeRunId != null), canUndoSwipe = true)
        }
        if (_discoverState.value.cards.size < 10) loadMoreDiscovery()
    }

    fun decideSwipe(listingId: Long, preference: Preference) {
        val state = _discoverState.value
        if (state.busy || state.loading || _searchUiState.value.loading) return
        val card = state.cards.firstOrNull()?.takeIf { it.listingId == listingId } ?: return
        val epoch = ++discoveryEpoch
        _discoverState.update { it.copy(busy = true, error = null, loadingMore = false, favoritesLoadingMore = false) }
        viewModelScope.launch {
            var committed = false
            try {
                val previous = core.setSwipePreference(listingId, card.personId, null, preference)
                committed = true
                swipeSession.visit(SwipeStep(listingId, card.personId, previous, preference))
                val page = discoveryPage()
                val favorites = core.discoveryCards(favoritesOnly = true)
                publishDiscovery(epoch, page, favorites)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        it.copy(cards = if (committed) swipeSession.queue(it.cards, it.activeRunId != null) else it.cards,
                            canUndoSwipe = swipeSession.last != null, error = failure.message ?: "Entscheidung fehlgeschlagen")
                    }
                }
            } finally {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(busy = false) }
            }
        }
    }

    fun undoSwipe() {
        if (_discoverState.value.busy || _discoverState.value.loading || _searchUiState.value.loading) return
        val step = swipeSession.last ?: return
        val epoch = ++discoveryEpoch
        _discoverState.update { it.copy(busy = true, error = null, loadingMore = false, favoritesLoadingMore = false) }
        viewModelScope.launch {
            var restored = false
            try {
                val previous = step.before
                if (previous != null) {
                    core.setSwipePreference(step.listingId, step.personId, step.after, previous)
                } else {
                    check(core.profileIdentity(step.listingId).personId == step.personId) {
                        "Personenzuordnung geändert; Undo nicht möglich. Deck zurücksetzen."
                    }
                }
                swipeSession.undo(step)
                restored = true
                val page = discoveryPage()
                val favorites = core.discoveryCards(favoritesOnly = true)
                publishDiscovery(epoch, page, favorites)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) _discoverState.update {
                    it.copy(canUndoSwipe = swipeSession.last != null, error =
                        if (restored) "Undo gespeichert; bitte Ansicht aktualisieren." else failure.message ?: "Undo fehlgeschlagen")
                }
            } finally {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(busy = false) }
            }
        }
    }

    fun choosePreference(personId: Long, preference: Preference) {
        if (_discoverState.value.busy || _searchUiState.value.actionBusy || (_searchUiState.value.loading && !_searchUiState.value.profileOpen)) return
        val epoch = ++discoveryEpoch
        _discoverState.update { it.copy(busy = true, error = null) }
        _searchUiState.update { it.copy(actionError = null) }
        viewModelScope.launch {
            try {
                core.setPreference(personId, preference)
                val updatedPerson = core.person(personId)
                _searchUiState.update {
                    if (it.selectedPersonId == personId) it.copy(selectedPerson = updatedPerson) else it
                }
                val cards = discoveryPage()
                val favorites = core.discoveryCards(favoritesOnly = true)
                publishDiscovery(epoch, cards, favorites)
            } catch (cancellation: CancellationException) {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(busy = false) }
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) {
                    val message = failure.message ?: "Entscheidung nicht gespeichert"
                    _discoverState.update { it.copy(busy = false, error = message) }
                    _searchUiState.update { if (it.selectedPersonId == personId) it.copy(actionError = message) else it }
                }
            }
        }
    }

    fun toggleFavorite(personId: Long) {
        if (_discoverState.value.busy || _searchUiState.value.actionBusy || (_searchUiState.value.loading && !_searchUiState.value.profileOpen)) return
        val epoch = ++discoveryEpoch
        _discoverState.update { it.copy(busy = true, error = null) }
        _searchUiState.update { it.copy(actionError = null) }
        viewModelScope.launch {
            try {
                val person = core.person(personId) ?: error("Person nicht gefunden")
                core.setFavorite(personId, !person.favorite)
                val personState = core.person(personId)
                _searchUiState.update {
                    if (it.selectedPersonId == personId) it.copy(selectedPerson = personState) else it
                }
                val cards = discoveryPage()
                val favorites = core.discoveryCards(favoritesOnly = true)
                publishDiscovery(epoch, cards, favorites)
                refreshMap()
            } catch (cancellation: CancellationException) {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(busy = false) }
                throw cancellation
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) {
                    val message = failure.message ?: "Favorit konnte nicht geändert werden"
                    _discoverState.update { it.copy(busy = false, error = message) }
                    _searchUiState.update { if (it.selectedPersonId == personId) it.copy(actionError = message) else it }
                }
            }
        }
    }

    fun loadProfile(listingId: Long, personId: Long) {
        if (_discoverState.value.busy) return
        cancelPreview()
        invalidateActiveSearch()
        invalidateProfileRequest()
        val requestId = profileRequestId
        _searchUiState.update {
            it.copy(profileOpen=true,profileSessionId=requestId,selectedPersonId=personId,selectedPerson=null,
                savedResultsLoadingMore=false,
                loading=true,profile=null,profileIdentity=null,profileFromArchive=false,profileMedia=emptyList(),changeEvents=emptyList(),
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
                val identity = core.profileIdentity(listingId)
                val person = core.person(identity.personId)
                val changes = core.changeEvents(identity.personId)
                val candidates = core.identityCandidates(listingId)
                if (requestId != profileRequestId) return@launch
                _searchUiState.update {
                    it.copy(loading=false,profile=profile,profileIdentity=identity,profileFromArchive=showingArchive,
                        selectedPersonId=identity.personId,selectedPerson=person,
                        profileMedia=items,changeEvents=changes,identityCandidates=candidates,
                        mediaWarning=refreshError,archiving=!showingArchive)
                }
                refreshMap()
                if (showingArchive) return@launch
                val control = RequestControl()
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
        if (_discoverState.value.busy || active.actionBusy || !active.profileOpen || active.profile?.listingId != listingId) return
        val requestId = profileRequestId
        val epoch = ++discoveryEpoch
        archiveControl?.cancel()
        // The mutation outlives the detail screen. Keep the shared write guard until
        // it finishes even if Back clears the screen's own actionBusy flag.
        _discoverState.update { it.copy(busy = true, loading = false, loadingMore = false,
            favoritesLoadingMore = false, error = null) }
        _searchUiState.update { it.copy(actionBusy = true, actionError = null) }
        viewModelScope.launch {
            var committed = false
            var profileUpdated = false
            var projectionUpdated = false
            try {
                core.confirmIdentityMatch(listingId, targetPersonId)
                committed = true
                val identity = core.profileIdentity(listingId)
                val history = core.changeEvents(identity.personId)
                val candidates = core.identityCandidates(listingId)
                val person = core.person(identity.personId)
                if (requestId == profileRequestId) {
                    _searchUiState.update {
                        if (it.profile?.listingId == listingId) {
                            it.copy(selectedPersonId = identity.personId, profileIdentity = identity,
                                selectedPerson = person, changeEvents = history, identityCandidates = candidates)
                        } else it
                    }
                }
                profileUpdated = true
                val cards = discoveryPage()
                val favorites = core.discoveryCards(favoritesOnly = true)
                publishDiscovery(epoch, cards, favorites)
                projectionUpdated = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val message = if (committed) "Zusammenführung gespeichert; Ansicht konnte nicht vollständig aktualisiert werden."
                    else failure.message ?: "Zusammenführung fehlgeschlagen"
                if (requestId == profileRequestId) {
                    _searchUiState.update {
                        if (committed && !profileUpdated) it.copy(actionError = message, selectedPersonId = null,
                            selectedPerson = null, profileIdentity = null, identityCandidates = emptyList(),
                            changeEvents = emptyList())
                        else it.copy(actionError = message)
                    }
                } else {
                    _discoverState.update { it.copy(error = message) }
                }
            } finally {
                if (requestId == profileRequestId) _searchUiState.update { it.copy(actionBusy = false) }
                if (epoch == discoveryEpoch && !projectionUpdated) {
                    _discoverState.update {
                        if (committed) it.copy(busy = false, cards = emptyList(), favorites = emptyList(),
                            nextCursor = null, favoritesNextCursor = null, activeVisibleCount = 0)
                        else it.copy(busy = false)
                    }
                    if (committed) {
                        _searchUiState.update { it.copy(activeCards = emptyList()) }
                        refreshDiscovery()
                    }
                }
                if (committed) {
                    refreshMap()
                    refresh()
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
                val capture = core.httpCaptureStatus()
                _uiState.update {
                    it.copy(loading=false,schemaVersion=status.schemaVersion.toString(),
                        personCount=status.personCount.toString(),sourceProfileCount=status.sourceProfileCount.toString(),
                        listingCount=status.listingCount.toString(),diagnosticAvailable=diagnostic.available,
                        diagnosticSizeBytes=diagnostic.sizeBytes.toLong(),
                        debugLoggingEnabled=core.isDebugLoggingEnabled(),
                        httpCaptureMode=capture.mode,
                        httpCaptureEntries=capture.entries,
                        httpCaptureBytes=capture.bytes,
                        httpCaptureStopped=capture.stopped,
                        httpCaptureError=capture.lastError,
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
        if (_uiState.value.loading || _searchUiState.value.loading || _discoverState.value.busy) return
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
                refreshMap()
                refreshSearchDefinitions()
            } catch (cancellation: CancellationException) {
                _uiState.update { it.copy(loading=false) }
                throw cancellation
            } catch (failure: Exception) {
                _uiState.update { it.copy(loading=false,error=failure.message ?: "Datenimport fehlgeschlagen") }
            }
        }
    }

    fun setDebugLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { core.setDebugLoggingEnabled(enabled) }
                .onSuccess { _uiState.update { it.copy(debugLoggingEnabled = enabled) } }
                .onFailure { failure -> _uiState.update { state -> state.copy(error = failure.message) } }
        }
    }

    fun refreshCaptureStatus() {
        viewModelScope.launch {
            runCatching { core.httpCaptureStatus() }
                .onSuccess { capture ->
                    _uiState.update { state -> state.copy(
                        httpCaptureMode = capture.mode,
                        httpCaptureEntries = capture.entries,
                        httpCaptureBytes = capture.bytes,
                        httpCaptureStopped = capture.stopped,
                        httpCaptureError = capture.lastError,
                    ) }
                }
        }
    }

    fun setHttpCaptureMode(mode: CaptureMode) {
        viewModelScope.launch {
            runCatching { core.setHttpCaptureMode(mode) }
                .onSuccess { capture ->
                    _uiState.update { state -> state.copy(
                        httpCaptureMode = capture.mode, httpCaptureEntries = capture.entries,
                        httpCaptureBytes = capture.bytes, httpCaptureStopped = capture.stopped,
                        httpCaptureError = capture.lastError,
                    ) }
                }
                .onFailure { failure ->
                    _uiState.update { state -> state.copy(error = failure.message) }
                }
        }
    }

    fun clearHttpCapture() {
        viewModelScope.launch {
            runCatching { core.clearHttpCapture() }
                .onSuccess { refresh() }
                .onFailure { failure ->
                    _uiState.update { state -> state.copy(error = failure.message) }
                }
        }
    }

    suspend fun prepareHarSnapshot(): File = core.createHarSnapshot()

    suspend fun prepareDiagnosticSnapshot(): File = core.createDiagnosticSnapshot()
}
