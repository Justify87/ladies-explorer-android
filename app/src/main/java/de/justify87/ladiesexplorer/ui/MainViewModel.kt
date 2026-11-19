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

enum class HomeOperation { Status, Import }

data class HomeUiState(
    val operation: HomeOperation? = HomeOperation.Status,
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
) {
    val loading get() = operation != null
    val importing get() = operation == HomeOperation.Import
}

data class SavedStatusProgress(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val running: Boolean = true,
    val cancelled: Boolean = false,
    val lastError: String? = null,
)

data class DiscoverUiState(
    val cards: List<DiscoveryCard> = emptyList(),
    val savedKind: SavedPersonKind = SavedPersonKind.FAVORITE,
    val savedSort: SavedPersonSort = SavedPersonSort.DATE,
    val savedPage: SavedPersonPage? = null,
    val savedRecent: SavedPersonPage? = null,
    val savedError: String? = null,
    val mutationError: String? = null,
    val nextCursor: DiscoveryCursor? = null,
    val activeRunId: Long? = null,
    val activeVisibleCount: Int = 0,
    val replayLikes: Boolean = true,
    val canUndoSwipe: Boolean = false,
    val loadingMore: Boolean = false,
    val savedLoadingMore: Boolean = false,
    val savedRecentLoadingMore: Boolean = false,
    val savedStatusProgress: SavedStatusProgress? = null,
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
    val filterError: String? = null,
    val preview: SearchPreview? = null,
    val previewTarget: SearchRequest? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val activeCards: List<DiscoveryCard> = emptyList(),
    val gridHiddenLikes: Set<Long> = emptySet(),
    val loading: Boolean = false,
    val searchCancelling: Boolean = false,
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
    private val _profileNotes = MutableStateFlow<Map<Long, NoteEditorState>>(emptyMap())
    internal val profileNotes: StateFlow<Map<Long, NoteEditorState>> = _profileNotes
    private val noteReadTickets = mutableMapOf<Long, Long>()
    private var nextNoteTicket = 0L
    internal val noteActions = NoteEditorActions(::loadProfileNote, ::editProfileNote, ::saveProfileNote, ::useStoredNote, ::keepNoteDraft)

    private val _mapState = MutableStateFlow(MapUiState())
    val mapState: StateFlow<MapUiState> = _mapState
    private var mapEpoch = 0L
    private var mapControl: RequestControl? = null
    private var savedStatusControl: RequestControl? = null

    private var archiveControl: RequestControl? = null
    private val searchOwner = DrainingOperation<RequestControl>()
    private val homeOwner = DrainingOperation<HomeOperation>()
    private var homeRefreshPending = false
    private var previewControl: RequestControl? = null
    private var previewJob: Job? = null
    private val previewGeneration = RequestGeneration()
    private var previewRequest: SearchRequest? = null
    private val profileGeneration = RequestGeneration()
    private var discoveryEpoch = 0L
    private var localWindow = 0
    private val savedWindows = SavedWindowMemory()
    private var discoveryRefreshPending = false
    private var swipeSnapshotDirty = false
    private var discoveryListVisible = false
    private var mapProjectionVisible = false
    private val filterGeneration = RequestGeneration()
    private val swipeSession = SwipeSession().apply { replayLikes = core.replayLikes() }

    internal val savedSearch = SavedSearchController(
        scope = viewModelScope,
        store = SavedSearchStore(core),
        canOperate = { !_searchUiState.value.loading && !_uiState.value.loading && !_discoverState.value.busy && !_searchUiState.value.profileOpen },
        ordinaryDraft = { SearchDraft.from(_searchUiState.value) },
        onSourceFinished = ::refreshAfterSourceSearch,
    )

    init {
        refresh()
        refreshDiscovery()
        refreshSearchDefinitions()
    }

    private fun invalidateActiveSearch() {
        searchOwner.cancel { it.cancel() }
    }

    fun cancelActiveSearch() {
        if (!searchOwner.occupied) return
        invalidateActiveSearch()
        _searchUiState.update { it.copy(searchCancelling = true) }
    }

    private fun invalidateProfileRequest() {
        profileGeneration.invalidate()
        archiveControl?.cancel()
        archiveControl = null
    }

    override fun onCleared() {
        savedStatusControl?.cancel()
        savedSearch.cancelCheck()
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

    fun setPreviewTarget(target: SearchRequest?) {
        if (previewRequest == target) return
        previewRequest = target
        schedulePreview()
    }

    private fun schedulePreview() {
        cancelPreview()
        val input = previewRequest
        _searchUiState.update { it.copy(preview = null, previewError = null, previewTarget = input) }
        if (input == null || _searchUiState.value.loading || input.city.isBlank()) return
        val epoch = previewGeneration.value
        val control = RequestControl()
        previewControl = control
        _searchUiState.update { it.copy(previewLoading = true) }
        previewJob = viewModelScope.launch {
            try {
                delay(350)
                val result = core.preview(input, control)
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

    internal fun editSearchDraft(change: (SearchDraft) -> SearchDraft) {
        val current = _searchUiState.value
        if (current.loading || savedSearch.state.value.busy) return
        val draft = SearchDraft.from(current)
        val next = change(draft)
        if (draft == next) return
        _searchUiState.update { it.applyDraftChange(next.applyTo(it)) }
    }

    fun resetSearchDraft() = editSearchDraft { SearchDraft() }

    fun runAvailability(mode: AvailabilityMode) {
        val state = _searchUiState.value
        val active = state.response
        val draft = SearchDraft.from(state)
        val query = (active?.query ?: draft.request()).withAvailabilityMode(mode)
        startSearch(requestOverride = query, localOverride = active?.local ?: draft.local)
    }

    private fun request(input: SearchUiState) = SearchDraft.from(input).request()

    fun refreshSearchDefinitions() {
        savedSearch.refresh()
        refreshFilterCatalog()
    }

    // Both ordinary and saved searches can persist before a later projection failure
    // or generation invalidation. Refresh local projections after the native call
    // finishes, independently of whether its response became the active UI run.
    private fun refreshAfterSourceSearch() {
        refreshDiscovery()
        refreshMap()
        refresh()
        refreshFilterCatalog()
    }

    private fun refreshFilterCatalog() {
        filterGeneration.invalidate()
        val ticket = filterGeneration.value
        viewModelScope.launch {
            try {
                val catalog = core.sourceFilterCatalog()
                val frequent = core.frequentFilters()
                if (filterGeneration.isCurrent(ticket)) _searchUiState.update {
                    it.copy(filterCatalog = catalog, frequentFilters = frequent, filterError = null)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (filterGeneration.isCurrent(ticket)) _searchUiState.update {
                    it.copy(filterError = failure.message ?: "Filter konnten nicht geladen werden")
                }
            }
        }
    }

    fun startSearch(requestOverride: SearchRequest? = null, localOverride: LocalVisibility? = null) {
        if (searchOwner.occupied || _searchUiState.value.loading || savedSearch.state.value.busy || _uiState.value.loading || _discoverState.value.busy) return
        val input = _searchUiState.value
        cancelPreview()
        val control = RequestControl()
        val ticket = checkNotNull(searchOwner.start(control))
        invalidateProfileRequest()
        _searchUiState.update { it.beginRun() }
        viewModelScope.launch {
            try {
                val result = core.search(requestOverride ?: request(input), localOverride ?: input.local, control)
                if (!searchOwner.accepts(ticket)) return@launch
                val cards = core.activeSearchCards(result.searchRunId, result.local)
                if (!searchOwner.accepts(ticket)) return@launch
                swipeSession.reset()
                discoveryEpoch++
                _discoverState.update {
                    it.copy(cards = swipeSession.queue(cards, active = true), canUndoSwipe = false, nextCursor = null, activeRunId = result.searchRunId,
                        activeVisibleCount = cards.size, loading = false, loadingMore = false,
                        savedLoadingMore = false, savedRecentLoadingMore = false, error = null)
                }
                _searchUiState.update { it.acceptRun(result, cards) }
            } catch (cancellation: CancellationException) {
                if (searchOwner.accepts(ticket)) _searchUiState.update { it.endRun() }
                throw cancellation
            } catch (failure: Exception) {
                if (searchOwner.accepts(ticket)) {
                    _searchUiState.update {
                        it.endRun(failure.message ?: "Online-Suche fehlgeschlagen")
                    }
                }
            } finally {
                // Native ownership lasts until the call has drained. Reconciliation
                // happens before another search/import can acquire the UI.
                if (searchOwner.finish(ticket)) {
                    _searchUiState.update { it.copy(loading = false, searchCancelling = false) }
                    savedSearch.refresh()
                    refreshAfterSourceSearch()
                    schedulePreview()
                }
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
            return DiscoveryProjection(null, emptyList(), readDiscoveryWindow(localWindow) { cursor ->
                core.discoveryCards(cursor = cursor, excludedPeople = swipeSession.excludedPeople)
            })
        }
        val visible = core.activeSearchCards(active.searchRunId, active.local)
        return DiscoveryProjection(active.searchRunId, visible, DiscoveryPage(visible, null))
    }

    private suspend fun savedPage(): SavedPeopleWindow {
        val state = _discoverState.value
        return core.savedPeopleWindow(state.savedKind, state.savedSort,
            savedWindows.limits(state.savedKind, state.savedSort))
    }

    fun selectSavedKind(kind: SavedPersonKind) {
        val state = _discoverState.value
        if (state.busy || state.loading || state.savedKind == kind) return
        _discoverState.update { it.copy(savedKind = kind, savedPage = null, savedRecent = null) }
        refreshDiscovery()
    }

    fun selectSavedSort(sort: SavedPersonSort) {
        val state = _discoverState.value
        if (state.busy || state.loading || state.savedSort == sort) return
        _discoverState.update { it.copy(savedSort = sort, savedPage = null, savedRecent = null) }
        refreshDiscovery()
    }

    private fun publishDiscovery(epoch: Long, projection: DiscoveryProjection) {
        if (epoch != discoveryEpoch || projection.runId != _searchUiState.value.response?.searchRunId) return
        if (projection.runId == null) localWindow = maxOf(localWindow, projection.page.cards.size)
        _searchUiState.update { it.copy(activeCards = projection.visible) }
        _discoverState.update {
            it.copy(
                cards = swipeSession.queue(projection.page.cards, active = projection.runId != null),
                replayLikes = swipeSession.replayLikes,
                canUndoSwipe = swipeSession.last != null,
                activeRunId = projection.runId,
                activeVisibleCount = projection.visible.size,
                nextCursor = projection.page.nextCursor,
                loadingMore = false, error = null,
            )
        }
    }

    // The reads share an epoch but not validity or failure. Mutation ownership
    // is released only by its caller, never by publication of a partial view.
    private suspend fun projectDiscovery(epoch: Long): Boolean = readIndependentProjections(
        first = ::discoveryPage,
        second = ::savedPage,
        publishFirst = { result ->
            if (epoch == discoveryEpoch) result.fold(
                onSuccess = { publishDiscovery(epoch, it) },
                onFailure = { failure ->
                    _searchUiState.update { it.copy(activeCards = emptyList()) }
                    _discoverState.update { it.copy(cards = emptyList(), nextCursor = null,
                        activeVisibleCount = 0, error = failure.message ?: "Entdecken fehlgeschlagen") }
                },
            )
        },
        publishSecond = { result ->
            if (epoch == discoveryEpoch) result.fold(
                onSuccess = { page -> _discoverState.update { it.copy(savedPage = page.regular,
                    savedRecent = page.recent, savedError = null) } },
                onFailure = { failure -> _discoverState.update { it.copy(savedPage = null, savedRecent = null,
                    savedError = failure.message ?: "Gespeicherte Personen konnten nicht geladen werden") } },
            )
        },
    )

    fun markSwipeSnapshotChanged() {
        swipeSnapshotDirty = true
        savedSearch.refreshResultProjection()
        if (discoveryListVisible) refreshDiscovery()
        if (mapProjectionVisible) refreshMap()
    }

    fun setDiscoveryProjectionVisibility(listVisible: Boolean, mapVisible: Boolean) {
        val mapEntered = mapVisible && !mapProjectionVisible
        discoveryListVisible = listVisible
        mapProjectionVisible = mapVisible
        if (listVisible && swipeSnapshotDirty) refreshDiscovery()
        if (mapEntered) refreshMap()
    }

    fun refreshDiscovery() = refreshDiscoveryAfter(null)

    private fun refreshDiscoveryAfter(message: String?) {
        if (_discoverState.value.busy) {
            discoveryRefreshPending = true
            return
        }
        discoveryRefreshPending = false
        swipeSnapshotDirty = false
        val epoch = ++discoveryEpoch
        _discoverState.update {
            it.copy(loading = true, loadingMore = false, savedLoadingMore = false, savedRecentLoadingMore = false)
        }
        viewModelScope.launch {
            try {
                projectDiscovery(epoch)
                if (epoch == discoveryEpoch && message != null) _discoverState.update { it.copy(mutationError = message) }
            } finally {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(loading = false) }
            }
        }
    }

    fun loadMoreDiscovery() {
        if (_searchUiState.value.response != null) return
        val state = _discoverState.value
        val cursor = state.nextCursor ?: return
        if (state.loading || state.loadingMore || state.busy) return
        val epoch = discoveryEpoch
        _discoverState.update { it.copy(loadingMore = true, error = null) }
        viewModelScope.launch {
            try {
                val page = core.discoveryCards(cursor = cursor, excludedPeople = swipeSession.excludedPeople)
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        val cards = swipeSession.queue(it.cards + page.cards, active = false)
                        localWindow = maxOf(localWindow, cards.size)
                        it.copy(cards = cards, nextCursor = page.nextCursor, loadingMore = false)
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

    fun loadMoreSavedPeople() = loadMoreSaved(false)

    fun loadMoreRecentPeople() = loadMoreSaved(true)

    private fun loadMoreSaved(recent: Boolean) {
        val state = _discoverState.value
        val page = (if (recent) state.savedRecent else state.savedPage) ?: return
        if (page.nextCursor == null || state.loading || state.savedLoadingMore ||
            state.savedRecentLoadingMore || state.busy) return
        val epoch = discoveryEpoch
        savedWindows.requestMore(state.savedKind, state.savedSort, recent, page.rows.size)
        _discoverState.update {
            it.copy(savedLoadingMore = !recent, savedRecentLoadingMore = recent, savedError = null)
        }
        viewModelScope.launch {
            try {
                val next = savedPage()
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        it.copy(savedPage = next.regular, savedRecent = next.recent,
                            savedLoadingMore = false, savedRecentLoadingMore = false)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        it.copy(savedError = failure.message ?: "Weitere Profile konnten nicht geladen werden")
                    }
                }
            } finally {
                if (epoch == discoveryEpoch) _discoverState.update {
                    it.copy(savedLoadingMore = false, savedRecentLoadingMore = false)
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
        core.setReplayLikes(replay)
        _discoverState.update { it.copy(replayLikes = replay) }
        refreshDiscovery()
    }

    fun skipCard(listingId: Long) {
        val card = currentSwipeActionCard(_discoverState.value, _searchUiState.value.loading, listingId) ?: return
        swipeSession.visit(SwipeStep(card.listingId, card.personId))
        _discoverState.update {
            it.copy(cards = swipeSession.queue(it.cards, active = it.activeRunId != null), canUndoSwipe = true)
        }
        if (_discoverState.value.cards.size < 10) loadMoreDiscovery()
    }

    /** One shared mutation owner. Persistence, UI traversal and re-reading the loaded window
     * remain distinct; a failed read must never masquerade as a failed database write. */
    private fun mutateDiscovery(
        failureLabel: String,
        write: suspend () -> Unit,
        afterWrite: suspend () -> Unit = {},
        onWriteFailure: () -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) {
        val epoch = ++discoveryEpoch
        _discoverState.update { it.copy(busy = true, loading = false, mutationError = null,
            loadingMore = false, savedLoadingMore = false, savedRecentLoadingMore = false) }
        viewModelScope.launch {
            var message: String? = null
            var retryProjection = false
            try {
                val outcome = writeAndProject(
                    write = write,
                    project = {
                        // A profile-side failure must not keep the discovery feed stale.
                        check(projectDiscovery(epoch)) { "Ansicht konnte nicht vollständig aktualisiert werden." }
                    },
                    afterWrite = afterWrite,
                    onWriteFailure = onWriteFailure,
                )
                when (outcome) {
                    is DiscoveryMutationOutcome.WriteFailed -> {
                        message = outcome.failure.message ?: failureLabel
                        retryProjection = outcome.projectionFailure != null
                    }
                    is DiscoveryMutationOutcome.ProjectionFailed -> {
                        // A feed read owns its own error/recovery. Only an independent
                        // detail failure belongs to the mutation-level message.
                        if (outcome.detailFailed) message = "Änderung gespeichert; Profil konnte nicht vollständig aktualisiert werden."
                        retryProjection = outcome.feedFailed
                    }
                    DiscoveryMutationOutcome.Success -> Unit
                }
            } finally {
                if (epoch == discoveryEpoch) {
                    _discoverState.update { it.copy(busy = false, mutationError = message,
                        canUndoSwipe = swipeSession.last != null) }
                    message?.let(onFailure)
                    if (retryProjection || discoveryRefreshPending) refreshDiscoveryAfter(message)
                }
            }
        }
    }

    fun decideSwipe(listingId: Long, preference: Preference) {
        val card = currentSwipeActionCard(_discoverState.value, _searchUiState.value.loading, listingId) ?: return
        mutateDiscovery("Entscheidung fehlgeschlagen", write = {
            val previous = core.setSwipePreference(listingId, card.personId, null, preference)
            swipeSession.visit(SwipeStep(listingId, card.personId, previous, preference))
        })
    }

    fun undoSwipe() {
        if (_discoverState.value.busy || _discoverState.value.loading || _searchUiState.value.loading) return
        val step = swipeSession.last ?: return
        mutateDiscovery("Undo fehlgeschlagen", write = {
            val previous = step.before
            if (previous != null) {
                core.setSwipePreference(step.listingId, step.personId, step.after, previous)
            } else {
                check(core.profileIdentity(step.listingId).personId == step.personId) {
                    "Personenzuordnung geändert; Undo nicht möglich. Deck zurücksetzen."
                }
            }
            swipeSession.undo(step)
        })
    }

    /** The temporary hide belongs only to the grid of the executed run. */
    fun toggleGridLike(listingId: Long) {
        val state = _discoverState.value
        val search = _searchUiState.value
        if (state.busy || state.loading || search.loading || search.actionBusy) return
        val card = gridCards(state, search).firstOrNull { it.listingId == listingId } ?: return
        val target = gridLikeTarget(card.preference)
        val hide = search.response != null && target == Preference.LIKE
        if (hide) _searchUiState.update { it.hideGridLike(listingId) }
        mutateDiscovery("Like konnte nicht gespeichert werden", write = {
            core.setSwipePreference(card.listingId, card.personId, card.preference, target)
        }, onWriteFailure = {
            if (hide) _searchUiState.update { it.restoreGridLike(listingId) }
        })
    }

    private suspend fun refreshSelectedPerson(personId: Long) {
        try {
            val person = core.person(personId)
            _searchUiState.update { if (it.selectedPersonId == personId) it.copy(selectedPerson = person) else it }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            // Do not leave stale actionable detail state after a committed write.
            _searchUiState.update { if (it.selectedPersonId == personId) it.copy(selectedPerson = null) else it }
            throw failure
        }
    }

    fun checkAllSavedStatus() = checkSavedStatus(null)

    fun checkSingleSavedStatus(listingId: Long) = checkSavedStatus(listingId)

    fun cancelSavedStatus() {
        val control = savedStatusControl ?: return
        control.cancel()
        _discoverState.update { state ->
            state.copy(savedStatusProgress = state.savedStatusProgress?.copy(cancelled = true))
        }
    }

    private fun checkSavedStatus(listingId: Long?) {
        val state = _discoverState.value
        if (state.busy || state.loading || _searchUiState.value.loading ||
            _searchUiState.value.actionBusy || savedStatusControl != null) return
        val control = RequestControl()
        savedStatusControl = control
        _discoverState.update { it.copy(savedStatusProgress = SavedStatusProgress()) }
        mutateDiscovery("Statusprüfung fehlgeschlagen", write = {
            try {
                val targets = if (listingId == null) core.savedStatusTargets() else listOf(listingId)
                runSavedStatusBatch(
                    targets = targets,
                    cancelled = { _discoverState.value.savedStatusProgress?.cancelled == true },
                    check = { core.checkSavedStatus(it, control) },
                    publish = { total, done, failed, lastError ->
                        _discoverState.update { current ->
                            current.copy(savedStatusProgress = current.savedStatusProgress?.copy(
                                total = total, done = done, failed = failed, lastError = lastError))
                        }
                    },
                )
            } finally {
                savedStatusControl = null
                savedSearch.refreshResultProjection()
                _discoverState.update { current ->
                    current.copy(savedStatusProgress = current.savedStatusProgress?.copy(running = false))
                }
            }
        }, afterWrite = {
            refreshMap()
        })
    }

    fun choosePreference(personId: Long, preference: Preference) {
        if (_discoverState.value.busy || _searchUiState.value.actionBusy || (_searchUiState.value.loading && !_searchUiState.value.profileOpen)) return
        _searchUiState.update { it.copy(actionError = null) }
        mutateDiscovery("Entscheidung nicht gespeichert", write = {
            core.setPreference(personId, preference)
        }, afterWrite = {
            refreshSelectedPerson(personId)
        }, onFailure = { message ->
            _searchUiState.update { if (it.selectedPersonId == personId) it.copy(actionError = message) else it }
        })
    }

    fun toggleFavorite(personId: Long) {
        if (_discoverState.value.busy || _searchUiState.value.actionBusy || (_searchUiState.value.loading && !_searchUiState.value.profileOpen)) return
        _searchUiState.update { it.copy(actionError = null) }
        mutateDiscovery("Favorit konnte nicht geändert werden", write = {
            val person = core.person(personId) ?: error("Person nicht gefunden")
            core.setFavorite(personId, !person.favorite)
        }, afterWrite = {
            refreshMap()
            refreshSelectedPerson(personId)
        }, onFailure = { message ->
            _searchUiState.update { if (it.selectedPersonId == personId) it.copy(actionError = message) else it }
        })
    }

    private fun updateNote(personId: Long, transform: (NoteEditorState) -> NoteEditorState) {
        _profileNotes.update { notes ->
            notes.updateNote(personId, transform)
        }
    }

    private suspend fun loadProfileNote(listingId: Long, personId: Long) {
        val ticket = ++nextNoteTicket
        noteReadTickets[personId] = ticket
        updateNote(personId) { it.copy(loading = true) }
        try {
            val saved = core.personNote(listingId, personId)
            if (noteReadTickets[personId] == ticket) updateNote(personId) { it.loaded(saved) }
        } catch (cancel: CancellationException) {
            if (noteReadTickets[personId] == ticket) updateNote(personId) { it.copy(loading = false) }
            throw cancel
        } catch (failure: Exception) {
            if (noteReadTickets[personId] == ticket) updateNote(personId) {
                it.copy(loading = false, error = failure.message ?: "Notiz nicht verfügbar", readError = true)
            }
        }
    }

    private fun editProfileNote(personId: Long, text: String) {
        if (_discoverState.value.busy) return
        updateNote(personId) { it.copy(draft = it.draft?.edit(text), error = if (it.readError) it.error else null) }
    }

    private fun useStoredNote(personId: Long) {
        if (_discoverState.value.busy) return
        updateNote(personId) { it.copy(draft = it.draft?.useStored(), error = if (it.readError) it.error else null) }
    }

    private fun keepNoteDraft(personId: Long) {
        if (_discoverState.value.busy) return
        updateNote(personId) { it.copy(draft = it.draft?.keepDraft(), error = null) }
    }

    private fun saveProfileNote(listingId: Long, personId: Long) {
        val entry = _profileNotes.value[personId] ?: return
        val draft = entry.draft ?: return
        if (_discoverState.value.busy || entry.loading || entry.readError || !draft.dirty || draft.conflict) return
        val note = draft.text.takeUnless { it.isBlank() }
        // Invalidate any older editor read. The same mutation guard and ViewModel
        // lifetime cover navigation, preference writes and identity merges.
        noteReadTickets[personId] = ++nextNoteTicket
        updateNote(personId) { it.copy(error = null) }
        mutateDiscovery("Notiz konnte nicht gespeichert werden", write = {
            try {
                core.savePersonNote(listingId, personId, draft.expected, note)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                loadProfileNote(listingId, personId)
                throw failure
            }
        }, afterWrite = {
            updateNote(personId) { it.committed(note) }
        }, onFailure = { message ->
            updateNote(personId) { it.copy(error = message) }
        })
    }

    fun loadProfile(listingId: Long, personId: Long) {
        if (_discoverState.value.busy || _searchUiState.value.loading || savedSearch.state.value.blockingNavigation) return
        cancelPreview()
        invalidateActiveSearch()
        invalidateProfileRequest()
        savedSearch.setVisible(false)
        val requestId = profileGeneration.value
        _searchUiState.update {
            it.copy(profileOpen=true,profileSessionId=requestId,selectedPersonId=personId,selectedPerson=null,
                loading=true,profile=null,profileIdentity=null,profileFromArchive=false,profileMedia=emptyList(),changeEvents=emptyList(),
                identityCandidates=emptyList(),error=null,actionBusy=false,actionError=null,
                mediaArchive=null,mediaWarning=null,archiving=false)
        }
        viewModelScope.launch {
            try {
                var showingArchive = false
                var refreshError: String? = null
                val profile = try {
                    try {
                        core.loadProfile(listingId)
                    } finally {
                        // A source read can persist before a later failure or invalidation.
                        savedSearch.refreshResultProjection()
                        refreshDiscovery()
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    val archived = core.cachedProfile(listingId) ?: throw failure
                    showingArchive = true
                    refreshError = "Archivierte Profildaten: Online-Aktualisierung fehlgeschlagen (${failure.message ?: "Netzwerk nicht verfügbar"})."
                    archived
                }
                if (!profileGeneration.isCurrent(requestId)) return@launch
                val items = core.profileMedia(profile.snapshotId)
                val identity = core.profileIdentity(listingId)
                val person = core.person(identity.personId)
                val changes = core.changeEvents(identity.personId)
                val candidates = core.identityCandidates(listingId)
                if (!profileGeneration.isCurrent(requestId)) return@launch
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
                    if (profileGeneration.isCurrent(requestId)) {
                        _searchUiState.update { it.copy(profileMedia=updatedMedia,mediaArchive=report) }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (profileGeneration.isCurrent(requestId)) {
                        _searchUiState.update {
                            it.copy(mediaWarning=failure.message ?: "Medienarchivierung fehlgeschlagen")
                        }
                    }
                } finally {
                    if (archiveControl === control) archiveControl = null
                    if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(archiving=false) }
                    // Even a cancelled archive may already have committed individual media.
                    savedSearch.refreshResultProjection()
                    refreshDiscovery()
                }
            } catch (cancellation: CancellationException) {
                if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(loading=false,archiving=false) }
                throw cancellation
            } catch (failure: Exception) {
                if (profileGeneration.isCurrent(requestId)) {
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
        val sourceOwner = active.profileIdentity?.personId ?: return
        val dirtyOwners = _profileNotes.value.dirtyOwners(sourceOwner, targetPersonId)
        if (dirtyOwners.isNotEmpty()) {
            _searchUiState.update { it.copy(actionError =
                "Ungespeicherte Notiz für Person(en) ${dirtyOwners.joinToString()}. Vor Zusammenführung speichern oder verwerfen.") }
            return
        }
        val requestId = profileGeneration.value
        val epoch = ++discoveryEpoch
        archiveControl?.cancel()
        // The mutation outlives the detail screen. Keep the shared write guard until
        // it finishes even if Back clears the screen's own actionBusy flag.
        _discoverState.update { it.copy(busy = true, loading = false, loadingMore = false,
            savedLoadingMore = false, savedRecentLoadingMore = false, mutationError = null) }
        _searchUiState.update { it.copy(actionBusy = true, actionError = null) }
        viewModelScope.launch {
            var committed = false
            var profileUpdated = false
            var projectionUpdated = false
            try {
                core.confirmIdentityMatch(listingId, targetPersonId)
                committed = true
                for (owner in listOf(sourceOwner, targetPersonId)) {
                    noteReadTickets[owner] = ++nextNoteTicket
                }
                _profileNotes.update { it - sourceOwner - targetPersonId }
                val identity = core.profileIdentity(listingId)
                val history = core.changeEvents(identity.personId)
                val candidates = core.identityCandidates(listingId)
                val person = core.person(identity.personId)
                if (profileGeneration.isCurrent(requestId)) {
                    _searchUiState.update {
                        if (it.profile?.listingId == listingId) {
                            it.copy(selectedPersonId = identity.personId, profileIdentity = identity,
                                selectedPerson = person, changeEvents = history, identityCandidates = candidates)
                        } else it
                    }
                }
                profileUpdated = true
                projectionUpdated = projectDiscovery(epoch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val message = if (committed) "Zusammenführung gespeichert; Ansicht konnte nicht vollständig aktualisiert werden."
                    else failure.message ?: "Zusammenführung fehlgeschlagen"
                if (profileGeneration.isCurrent(requestId)) {
                    _searchUiState.update {
                        if (committed && !profileUpdated) it.copy(actionError = message, selectedPersonId = null,
                            selectedPerson = null, profileIdentity = null, identityCandidates = emptyList(),
                            changeEvents = emptyList())
                        else it.copy(actionError = message)
                    }
                } else {
                    _discoverState.update { it.copy(mutationError = message) }
                }
            } finally {
                if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(actionBusy = false) }
                if (epoch == discoveryEpoch) {
                    _discoverState.update { it.copy(busy = false) }
                    if (committed && !projectionUpdated || discoveryRefreshPending) refreshDiscovery()
                }
                if (committed) {
                    savedSearch.refreshResultProjection()
                    if (discoveryRefreshPending) refreshDiscovery()
                    refreshMap()
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        if (homeOwner.occupied) {
            homeRefreshPending = true
            return
        }
        val ticket = checkNotNull(homeOwner.start(HomeOperation.Status))
        _uiState.update { it.copy(operation=HomeOperation.Status,error=null) }
        viewModelScope.launch {
            try {
                val status = core.status()
                val diagnostic = core.diagnosticStatus()
                val capture = core.httpCaptureStatus()
                _uiState.update {
                    it.copy(schemaVersion=status.schemaVersion.toString(),
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
                throw cancellation
            } catch (failure: Exception) {
                _uiState.update { it.copy(error=failure.message ?: "Rust-Datenbank nicht verfügbar") }
            } finally {
                if (homeOwner.finish(ticket)) {
                    _uiState.update { it.copy(operation=null) }
                    if (homeRefreshPending) {
                        homeRefreshPending = false
                        refresh()
                    }
                }
            }
        }
    }

    fun importBundle(uri: Uri) {
        if (!canAcquireImport(searchOwner, homeOwner, _uiState.value.loading || _searchUiState.value.loading ||
                _discoverState.value.busy || savedSearch.state.value.busy)) return
        if (savedSearch.state.value.editor != null || savedSearch.state.value.dialog != null) {
            _uiState.update { it.copy(error = "Gespeicherte Suchentwürfe vor dem Import speichern oder verwerfen.") }
            return
        }
        if (_profileNotes.value.values.any { it.draft?.dirty == true }) {
            _uiState.update { it.copy(error = "Ungespeicherte Notizen vor dem Import speichern oder verwerfen.") }
            return
        }
        val ticket = checkNotNull(homeOwner.start(HomeOperation.Import))
        _uiState.update { it.copy(operation=HomeOperation.Import,error=null) }
        viewModelScope.launch {
            var imported = false
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
                noteReadTickets.clear()
                _profileNotes.value = emptyMap()
                savedSearch.resetAfterImport()
                _uiState.update { it.copy(lastImport=result) }
                imported = true
                refreshDiscovery()
                refreshMap()
                refreshSearchDefinitions()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                _uiState.update { it.copy(error=failure.message ?: "Datenimport fehlgeschlagen") }
            } finally {
                if (homeOwner.finish(ticket)) {
                    homeRefreshPending = false
                    _uiState.update { it.copy(operation=null) }
                    if (imported) refresh()
                }
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
