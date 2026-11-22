package de.justify87.ladiesexplorer.ui

import android.app.Application
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
    val actionSurface: ProfileActionSurface = ProfileActionSurface.NONE,
    val actionGeneration: Long = 0L,
    val mutationError: String? = null,
    val nextCursor: DiscoveryCursor? = null,
    val activeRunId: Long? = null,
    val swipeSessionId: Long = 0L,
    val swipeProfileRevision: Long = 0L,
    val swipeExternalRevision: Long = 0L,
    val localProfileReloadIds: Set<Long> = emptySet(),
    val localProfileStrictIds: Set<Long> = emptySet(),
    val pendingMergeListingId: Long? = null,
    val mergeNotice: String? = null,
    val activeVisibleCount: Int = 0,
    val replayLikes: Boolean = true,
    val canUndoSwipe: Boolean = false,
    val swipeRestore: SwipePresentation? = null,
    val swipePresentationGeneration: Long = 0L,
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
    val profileRead: ProfileReadModel? = null,
    val profileRetryFromArchive: Boolean = false,
    val profileSourceRetryAllowed: Boolean = false,
    val actionBusy: Boolean = false,
    val actionError: String? = null,
    val retryProfileListingId: Long? = null,
    val mediaArchive: MediaArchiveReport? = null,
    val archiving: Boolean = false,
    val mediaWarning: String? = null,
    val error: String? = null,
) {
    val profile get() = profileRead?.profile
    val profileIdentity get() = profileRead?.identity
    val profileFromArchive get() = profileRead?.fromArchive ?: profileRetryFromArchive
    val selectedPersonId get() = profileRead?.identity?.personId
    val selectedPerson get() = profileRead?.person
    val profileMedia get() = profileRead?.media.orEmpty()
    val identityCandidates get() = profileRead?.candidates.orEmpty()
}

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
    private val noteReadTickets = NoteReadTickets()
    internal val noteActions = NoteEditorActions(::loadProfileNote, ::editProfileNote, ::saveProfileNote, ::useStoredNote, ::keepNoteDraft)

    private val _mapState = MutableStateFlow(MapUiState())
    val mapState: StateFlow<MapUiState> = _mapState
    private var mapEpoch = 0L
    private var mapControl: RequestControl? = null
    private var savedStatusControl: RequestControl? = null

    private val searchOwner = DrainingOperation<RequestControl>()
    private val homeOwner = DrainingOperation<Unit>()
    private var homeRefreshPending = false
    private var previewControl: RequestControl? = null
    private var previewJob: Job? = null
    private val previewGeneration = RequestGeneration()
    private var previewRequest: SearchRequest? = null
    private var discoveryEpoch = 0L
    private var swipeVisible = false
    private var renderedSwipe: SwipeActionToken? = null

    internal fun setProfileActionSurface(surface: ProfileActionSurface) {
        _discoverState.update { state ->
            if (state.actionSurface == surface) state
            else state.copy(actionSurface = surface, actionGeneration = state.actionGeneration + 1)
        }
    }

    internal fun setSwipeVisible(visible: Boolean) {
        if (swipeVisible == visible) return
        swipeVisible = visible
        renderedSwipe = null
        _discoverState.update {
            val window = it.cards.take(3).map { card -> card.listingId }.toSet()
            it.copy(swipeSessionId = it.swipeSessionId + 1,
                localProfileReloadIds = if (visible) it.localProfileReloadIds else window,
                localProfileStrictIds = it.localProfileStrictIds.intersect(window))
        }
    }

    internal fun publishRenderedSwipe(token: SwipeActionToken?) {
        renderedSwipe = token?.takeIf {
            swipeVisible && currentSwipeActionCard(_discoverState.value, _searchUiState.value.loading, it) != null &&
                !_searchUiState.value.profileOpen
        }
    }

    private fun admittedSwipe(token: SwipeActionToken): DiscoveryCard? =
        if (!swipeVisible || renderedSwipe != token || _searchUiState.value.profileOpen) null
        else currentSwipeActionCard(_discoverState.value, _searchUiState.value.loading, token)

    internal fun reconcileSwipeProjection(token: SwipeActionToken) {
        val state = _discoverState.value
        val card = state.cards.firstOrNull() ?: return
        if (!swipeVisible || state.busy || state.loading || _searchUiState.value.profileOpen ||
            state.activeRunId != token.runId || state.swipeSessionId != token.session ||
            card.listingId != token.listingId || card.personId != token.ownerId ||
            card.preference == token.preference && card.favorite == token.favorite) return
        invalidateSwipeProfilesLocally(token.listingId)
        refreshDiscovery()
    }

    internal fun isProfileActionCurrent(token: ProfileIdentityToken): Boolean = when (token) {
        is SwipeActionToken -> admittedSwipe(token) != null
        is DetailIdentityToken -> !_discoverState.value.busy && !_searchUiState.value.actionBusy &&
            _searchUiState.value.detailIdentityToken() == token
    }

    internal fun chooseProfilePreference(token: ProfileIdentityToken, preference: Preference) =
        chooseProfilePreference(token, preference, null)

    internal fun chooseSwipePreference(token: SwipeActionToken, preference: Preference, presentation: SwipePresentation?) {
        val card = SwipeCardToken(SwipeDeckToken(token.runId, token.session), token.listingId, token.ownerId)
        chooseProfilePreference(token, preference,
            presentationForDecision(_discoverState.value.swipeRestore,
                presentation?.takeIf { it.snapshotId == token.snapshotId }, card))
    }

    private fun chooseProfilePreference(token: ProfileIdentityToken, preference: Preference, presentation: SwipePresentation?) {
        if (!isProfileActionCurrent(token)) return
        _searchUiState.update { it.copy(actionError = null) }
        suspend fun refreshDetail() {
            if (token is DetailIdentityToken) profileOperations.refreshLocalProfile(token.sessionId, token.listingId)
        }
        mutateDiscovery("Entscheidung nicht gespeichert", write = {
            commitProfilePreference(token, preference, core::setSwipePreference, swipeSession::visit, presentation)
        }, afterWrite = { refreshDetail() },
            // A successful swipe preserves other owners' projections; failure or source/identity changes revoke them.
            refreshProfileWindow = token !is SwipeActionToken || preference == Preference.NONE,
            recoverWriteFailure = { refreshDetail() },
            onFailure = { message ->
                if (token is DetailIdentityToken) _searchUiState.update {
                    if (it.profileSessionId == token.sessionId) it.copy(actionError = message) else it
                }
            })
    }

    internal fun toggleProfileFavorite(token: ProfileIdentityToken) {
        if (!isProfileActionCurrent(token)) return
        _searchUiState.update { it.copy(actionError = null) }
        mutateDiscovery("Favorit konnte nicht geändert werden", write = {
            core.setListingFavorite(token.listingId, token.ownerId, token.favorite, !token.favorite)
        }, afterWrite = {
            refreshMap()
            if (token is DetailIdentityToken) profileOperations.refreshLocalProfile(token.sessionId, token.listingId)
        }, recoverWriteFailure = {
            refreshMap()
            if (token is DetailIdentityToken) profileOperations.refreshLocalProfile(token.sessionId, token.listingId)
        }, onFailure = { message ->
            if (token is DetailIdentityToken) _searchUiState.update {
                if (it.profileSessionId == token.sessionId) it.copy(actionError = message) else it
            }
        })
    }

    private fun admittedDeck(token: SwipeDeckToken): Boolean =
        swipeVisible && !_searchUiState.value.profileOpen &&
            currentSwipeDeck(_discoverState.value, _searchUiState.value.loading, token)

    private fun admittedTraversal(token: SwipeCardToken): DiscoveryCard? =
        if (!admittedDeck(token.deck)) null
        else currentSwipeTraversalCard(_discoverState.value, _searchUiState.value.loading, token)

    private fun invalidateSwipeProfilesLocally(strictListing: Long? = null) {
        renderedSwipe = null
        _discoverState.update {
            val window = it.cards.take(3).map { card -> card.listingId }.toSet()
            val strict = it.localProfileStrictIds.intersect(window) + listOfNotNull(strictListing)
            it.copy(swipeSessionId = it.swipeSessionId + 1, swipeProfileRevision = it.swipeProfileRevision + 1,
                localProfileReloadIds = window + strict, localProfileStrictIds = strict)
        }
    }

    private fun invalidateSwipeActions(invalidateProfiles: Boolean = false) {
        renderedSwipe = null
        _discoverState.update { it.copy(swipeSessionId = it.swipeSessionId + 1,
            swipeProfileRevision = it.swipeProfileRevision + if (invalidateProfiles) 1 else 0,
            localProfileReloadIds = if (invalidateProfiles) emptySet() else it.localProfileReloadIds,
            localProfileStrictIds = if (invalidateProfiles) emptySet() else it.localProfileStrictIds,
            mergeNotice = null, pendingMergeListingId = null) }
    }
    private var localWindow = 0
    private val savedWindows = SavedWindowMemory()
    private val filterGeneration = RequestGeneration()
    private val swipeSession = SwipeSession().apply { replayLikes = core.replayLikes() }

    internal val savedSearch = SavedSearchController(
        scope = viewModelScope,
        store = SavedSearchStore(core),
        canOperate = { !_searchUiState.value.loading && !_uiState.value.loading && !_discoverState.value.busy && !_searchUiState.value.profileOpen },
        ordinaryDraft = { SearchDraft.from(_searchUiState.value) },
        onSourceFinished = { refreshAfterSourceSearch() },
    )


    private val discoveryPublisher by lazy {
        DiscoveryProjectionPublisher(
            _searchUiState, _discoverState, swipeSession, { discoveryEpoch },
            { localWindow = maxOf(localWindow, it) }, ::discoveryPage, ::savedPage,
        )
    }

    private val swipeInvalidation by lazy {
        SwipeProjectionInvalidation(savedSearch::refreshResultProjection, ::refreshDiscovery, ::refreshMap)
    }

    private val profileOperations by lazy {
        StandaloneProfileOperations(
            viewModelScope, _searchUiState, _discoverState, _profileNotes, noteReadTickets,
            StandaloneProfileIo(
                core::loadProfile, core::cachedProfile, core::profileProjection, core::archiveProfileMedia,
                { RequestControl() }, { it.cancel() }, core::confirmIdentityMatch,
                { RequestControl() }, { it.cancel() },
            ),
            ProfileProjectionCallbacks(
                epoch = { discoveryEpoch }, nextEpoch = { ++discoveryEpoch },
                pending = { swipeInvalidation.pending }, projectDiscovery = ::projectDiscovery,
                discovery = ::refreshDiscovery, savedSearch = savedSearch::refreshResultProjection,
                map = ::refreshMap, home = ::refresh,
                admitSwipe = { admittedSwipe(it) != null },
                beginMerge = { invalidateSwipeActions() },
                mergeCommitted = ::onIdentityMergeCommitted,
                profileChanged = { invalidateSwipeProfilesLocally() },
            ),
        )
    }

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

    private fun invalidateProfileRequest() = profileOperations.invalidateProfileRequest()

    override fun onCleared() {
        savedStatusControl?.cancel()
        savedSearch.cancelCheck()
        cancelMapResolution()
        cancelPreview()
        invalidateActiveSearch()
        invalidateProfileRequest()
        super.onCleared()
    }

    fun cancelMediaArchive() = profileOperations.cancelMediaArchive()

    fun closeProfile() = profileOperations.closeProfile()

    internal fun setProfilePresentationVisible(visible: Boolean) {
        if (!visible) setSwipeVisible(false)
        profileOperations.setPresentationVisible(visible)
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

    /** Check swipe admission against live state, not the last Compose frame. */
    internal fun runSwipeAvailability(token: SwipeDeckToken, mode: AvailabilityMode) {
        val search = _searchUiState.value
        if (!swipeVisible || search.profileOpen ||
            !currentSwipeAvailability(_discoverState.value, search.loading, search.response?.searchRunId, token)) return
        runAvailability(mode)
    }

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

    // Searches may persist before delivery fails or is revoked; reconcile local state after drain.
    private fun refreshAfterSourceSearch(invalidateProfileWindow: Boolean = true) {
        if (invalidateProfileWindow && swipeVisible) invalidateSwipeProfilesLocally()
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
        invalidateSwipeActions(invalidateProfiles = true)
        cancelPreview()
        val control = RequestControl()
        val ticket = checkNotNull(searchOwner.start(control))
        invalidateProfileRequest()
        _searchUiState.update { it.beginRun() }
        viewModelScope.launch {
            var acceptedRun = false
            try {
                val result = core.search(requestOverride ?: request(input), localOverride ?: input.local, control)
                if (!searchOwner.accepts(ticket)) return@launch
                val cards = core.activeSearchCards(result.searchRunId, result.local)
                if (!searchOwner.accepts(ticket)) return@launch
                swipeSession.reset()
                discoveryEpoch++
                _discoverState.update {
                    it.copy(cards = swipeSession.queue(cards, active = true), canUndoSwipe = false, swipeRestore = null,
                        swipePresentationGeneration = it.swipePresentationGeneration + 1, nextCursor = null, activeRunId = result.searchRunId,
                        activeVisibleCount = cards.size, loading = false, loadingMore = false,
                        savedLoadingMore = false, savedRecentLoadingMore = false, error = null, actionGeneration = it.actionGeneration + 1)
                }
                _searchUiState.update { it.acceptRun(result, cards) }
                acceptedRun = true
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
                // Reconcile after native drain before admitting another search.
                if (searchOwner.finish(ticket)) {
                    _searchUiState.update { it.copy(loading = false, searchCancelling = false) }
                    savedSearch.refresh()
                    // Preserve source policy for an accepted replacement run.
                    refreshAfterSourceSearch(invalidateProfileWindow = !acceptedRun)
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
            projectMapOverview(_mapState, { epoch == mapEpoch }, { core.mapOverview(favoritesOnly) }) { state, overview ->
                state.copy(overview = overview, loading = false)
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
            // Cancellation may leave committed results; re-read local state.
            val epoch = ++mapEpoch
            _mapState.update { it.copy(loading = true) }
            projectMapOverview(_mapState, { epoch == mapEpoch }, { core.mapOverview(_mapState.value.favoritesOnly) }) { state, latest ->
                state.copy(overview = latest, failedLookups = failedLookups, loading = false, error = failureMessage)
            }
        }
    }

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
        _discoverState.update { it.copy(savedKind = kind, savedPage = null, savedRecent = null,
            actionGeneration = it.actionGeneration + 1) }
        refreshDiscovery()
    }

    fun selectSavedSort(sort: SavedPersonSort) {
        val state = _discoverState.value
        if (state.busy || state.loading || state.savedSort == sort) return
        _discoverState.update { it.copy(savedSort = sort, savedPage = null, savedRecent = null,
            actionGeneration = it.actionGeneration + 1) }
        refreshDiscovery()
    }

    private suspend fun projectDiscovery(epoch: Long): Boolean = discoveryPublisher.project(epoch)

    fun markSwipeSnapshotChanged(oldWindow: Boolean) {
        // A late completion revokes replacement data without recursively cancelling its native work.
        renderedSwipe = null
        _discoverState.update {
            it.copy(swipeSessionId = it.swipeSessionId + 1,
                swipeExternalRevision = it.swipeExternalRevision + if (oldWindow) 1 else 0)
        }
        swipeInvalidation.sourceCompleted()
    }

    fun setDiscoveryProjectionVisibility(listVisible: Boolean, mapVisible: Boolean) =
        swipeInvalidation.setVisibility(listVisible, mapVisible)

    fun refreshDiscovery() = refreshDiscoveryAfter(null)

    internal fun refreshSwipe(token: SwipeDeckToken) {
        if (!admittedDeck(token)) return
        invalidateSwipeActions(invalidateProfiles = true)
        refreshDiscovery()
    }

    private fun refreshDiscoveryAfter(message: String?) {
        if (!swipeInvalidation.beginDiscovery(_discoverState.value.busy)) return
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
                        it.copy(cards = cards, nextCursor = page.nextCursor, loadingMore = false,
                            actionGeneration = it.actionGeneration + 1)
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
                            savedLoadingMore = false, savedRecentLoadingMore = false,
                            actionGeneration = it.actionGeneration + 1)
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

    internal fun resetSwipe(token: SwipeDeckToken) {
        if (!admittedDeck(token)) return
        swipeSession.reset()
        invalidateSwipeActions(invalidateProfiles = true)
        _discoverState.update { it.copy(canUndoSwipe = false, swipeRestore = null,
            swipePresentationGeneration = it.swipePresentationGeneration + 1) }
        refreshDiscovery()
    }

    internal fun setReplayLikes(token: SwipeDeckToken, replay: Boolean) {
        if (!admittedDeck(token) || swipeSession.replayLikes == replay) return
        invalidateSwipeActions()
        swipeSession.replayLikes = replay
        core.setReplayLikes(replay)
        _discoverState.update { it.copy(replayLikes = replay) }
        refreshDiscovery()
    }

    internal fun skipCard(token: SwipeCardToken, presentation: SwipePresentation?) {
        val card = admittedTraversal(token) ?: return
        val position = presentationForDecision(_discoverState.value.swipeRestore, presentation, token)
        invalidateSwipeActions()
        swipeSession.visit(SwipeStep(card.listingId, card.personId, presentation = position))
        _discoverState.update {
            it.copy(cards = swipeSession.queue(it.cards, active = it.activeRunId != null), canUndoSwipe = true, swipeRestore = null)
        }
        if (_discoverState.value.cards.size < 10) loadMoreDiscovery()
    }

    /** A committed write and failed projection are distinct outcomes. */
    private fun mutateDiscovery(
        failureLabel: String,
        write: suspend () -> Unit,
        afterWrite: suspend () -> Unit = {},
        onWriteFailure: () -> Unit = {},
        onFailure: (String) -> Unit = {},
        refreshProfileWindow: Boolean = true,
        recoverWriteFailure: suspend () -> Unit = {},
    ) {
        invalidateSwipeActions()
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
                        check(projectDiscovery(epoch)) { "Ansicht konnte nicht vollständig aktualisiert werden." }
                    },
                    afterWrite = {
                        // Revision can change without a membership change.
                        savedSearch.refreshResultProjection()
                        if (refreshProfileWindow) invalidateSwipeProfilesLocally()
                        afterWrite()
                    },
                    onWriteFailure = onWriteFailure,
                    recoverWriteFailure = {
                        invalidateSwipeProfilesLocally()
                        savedSearch.refreshResultProjection()
                        recoverWriteFailure()
                    },
                )
                when (outcome) {
                    is DiscoveryMutationOutcome.WriteFailed -> {
                        message = outcome.failure.message ?: failureLabel
                        retryProjection = outcome.projectionFailure != null
                    }
                    is DiscoveryMutationOutcome.ProjectionFailed -> {
                        // Feed recovery is independent; report detail failure at mutation level.
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
                    if (retryProjection || swipeInvalidation.pending) refreshDiscoveryAfter(message)
                }
            }
        }
    }

    internal fun undoSwipe(token: SwipeDeckToken) {
        if (!admittedDeck(token)) return
        val step = swipeSession.last ?: return
        mutateDiscovery("Undo fehlgeschlagen", write = {
            undoSwipeStep(step, core::setSwipePreference, { core.profileIdentity(it).personId }, swipeSession::undo)
        }, afterWrite = {
            // Undo prefers local data; a never-loaded skip may still need a source read.
            _discoverState.update { it.copy(localProfileReloadIds = it.localProfileReloadIds + step.listingId) }
        })
    }

    internal fun consumeSwipePresentation(token: SwipeDeckToken, position: SwipePresentation) {
        val state = _discoverState.value
        val card = state.cardToken() ?: return
        if (!swipeVisible || _searchUiState.value.profileOpen || state.deckToken() != token ||
            state.swipeRestore != position || !position.matches(card)) return
        swipeSession.consumePresentation(position)
        _discoverState.update { it.copy(swipeRestore = null) }
    }

    internal fun toggleGridLike(action: GridLikeAction) {
        val state = _discoverState.value
        val search = _searchUiState.value
        if (!gridActionMatches(state, search, action)) return
        val card = action.card
        val target = gridLikeTarget(card.preference)
        val hide = action.runId != null && target == Preference.LIKE
        if (hide) _searchUiState.update { it.hideGridLike(card.listingId) }
        mutateDiscovery("Like konnte nicht gespeichert werden", write = {
            core.setSwipePreference(card.listingId, card.personId, card.preference, target)
        }, onWriteFailure = {
            if (hide) _searchUiState.update { it.restoreGridLike(card.listingId) }
        })
    }

    private suspend fun refreshSelectedPerson(personId: Long) =
        profileOperations.refreshLocalProfileForOwner(personId)

    internal fun checkAllSavedStatus(action: SavedStatusBatchAction) {
        if (!savedStatusBatchMatches(_discoverState.value, action) || _searchUiState.value.profileOpen) return
        checkSavedStatus(null)
    }

    internal fun checkSingleSavedStatus(action: SavedPersonAction) {
        if (!admittedSavedPersonRow(action)) return
        val listingId = action.row.card?.listingId ?: return
        checkSavedStatus(listingId)
    }

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

    private fun admittedSavedPersonRow(action: SavedPersonAction): Boolean =
        !_searchUiState.value.profileOpen && !_searchUiState.value.loading &&
            !_searchUiState.value.actionBusy && savedActionMatches(_discoverState.value, action)

    internal fun choosePreference(action: SavedPersonAction, preference: Preference) {
        if (!admittedSavedPersonRow(action) || action.row.preference == preference) return
        val row = action.row
        _searchUiState.update { it.copy(actionError = null) }
        mutateDiscovery("Entscheidung nicht gespeichert", write = {
            val listing = row.card?.listingId
            if (listing != null) core.setSwipePreference(listing, row.personId, row.preference, preference)
            else core.setPersonPreferenceIf(row.personId, row.preference, preference)
        }, afterWrite = {
            refreshSelectedPerson(row.personId)
        }, recoverWriteFailure = { refreshSelectedPerson(row.personId) }, onFailure = { message ->
            _searchUiState.update { if (it.selectedPersonId == row.personId) it.copy(actionError = message) else it }
        })
    }

    internal fun toggleFavorite(action: SavedPersonAction) {
        if (!admittedSavedPersonRow(action)) return
        val row = action.row
        _searchUiState.update { it.copy(actionError = null) }
        mutateDiscovery("Favorit konnte nicht geändert werden", write = {
            val listing = row.card?.listingId
            if (listing != null) core.setListingFavorite(listing, row.personId, row.favorite, !row.favorite)
            else core.setPersonFavoriteIf(row.personId, row.favorite, !row.favorite)
        }, afterWrite = {
            refreshMap()
            refreshSelectedPerson(row.personId)
        }, recoverWriteFailure = { refreshMap(); refreshSelectedPerson(row.personId) }, onFailure = { message ->
            _searchUiState.update { if (it.selectedPersonId == row.personId) it.copy(actionError = message) else it }
        })
    }

    private fun updateNote(personId: Long, transform: (NoteEditorState) -> NoteEditorState) {
        _profileNotes.update { notes ->
            notes.updateNote(personId, transform)
        }
    }

    private suspend fun loadProfileNote(listingId: Long, personId: Long) {
        val ticket = noteReadTickets.invalidate(personId)
        updateNote(personId) { it.copy(loading = true) }
        try {
            val saved = core.personNote(listingId, personId)
            if (noteReadTickets.isCurrent(personId, ticket)) updateNote(personId) { it.loaded(saved) }
        } catch (cancel: CancellationException) {
            if (noteReadTickets.isCurrent(personId, ticket)) updateNote(personId) { it.copy(loading = false) }
            throw cancel
        } catch (failure: Exception) {
            if (noteReadTickets.isCurrent(personId, ticket)) updateNote(personId) {
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
        noteReadTickets.invalidate(personId)
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

    internal fun openGridProfile(action: GridLikeAction) {
        if (!gridActionMatches(_discoverState.value, _searchUiState.value, action)) return
        loadProfile(action.card.listingId, action.card.personId)
    }

    internal fun openSavedPersonProfile(action: SavedPersonAction) {
        if (!admittedSavedPersonRow(action)) return
        action.row.card?.let { loadProfile(it.listingId, action.row.personId) }
    }

    internal fun openMapProfile(action: MapProfileOpenAction) {
        if (!mapProfileOpenMatches(_mapState.value, _discoverState.value, action)) return
        loadProfile(action.profile.listingId, action.profile.personId)
    }

    internal fun openSearchResultProfile(action: SearchResultOpenAction) {
        if (savedSearch.state.value.pane != SearchPane.Filters ||
            !searchResultOpenMatches(_searchUiState.value, _discoverState.value, action)) return
        loadProfile(action.card.listingId, action.card.personId)
    }

    internal fun openSavedSearchProfile(action: SavedSearchProfileOpenAction) {
        val current = _discoverState.value
        if (current.actionSurface != ProfileActionSurface.SAVED_RESULTS ||
            current.actionGeneration != action.generation || !savedSearch.admitsProfileOpen(action)) return
        val listing = action.card.listingId ?: return
        val person = action.card.personId ?: return
        loadProfile(listing, person)
    }

    private fun loadProfile(listingId: Long, personId: Long) {
        if (_discoverState.value.busy || _searchUiState.value.loading || savedSearch.state.value.blockingNavigation) return
        invalidateSwipeActions()
        cancelPreview()
        invalidateActiveSearch()
        profileOperations.loadProfile(listingId, personId) { savedSearch.setVisible(false) }
    }

    internal fun confirmIdentity(token: ProfileIdentityToken, targetPersonId: Long) =
        profileOperations.confirmIdentity(token, targetPersonId)

    private fun onIdentityMergeCommitted(token: ProfileIdentityToken, target: Long) {
        val current = _discoverState.value
        val preferred = if (token is SwipeActionToken) token.listingId else current.cards.firstOrNull()?.listingId
        swipeSession.reconcileMerge(token.ownerId, target, preferred)
        // Recover merged listings locally; other cards may not yet have snapshots.
        invalidateSwipeProfilesLocally(token.listingId)
        _mapState.update { it.copy(overview = null) }
        _discoverState.update {
            it.copy(pendingMergeListingId = if (token is SwipeActionToken) token.listingId else null,
                canUndoSwipe = swipeSession.last != null)
        }
    }

    internal fun retryProfileProjection() = profileOperations.retryProfileProjection()

    fun refresh() {
        if (homeOwner.occupied) {
            homeRefreshPending = true
            return
        }
        val ticket = checkNotNull(homeOwner.start(Unit))
        _uiState.update { it.copy(loading=true,error=null) }
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
                    _uiState.update { it.copy(loading=false) }
                    if (homeRefreshPending) {
                        homeRefreshPending = false
                        refresh()
                    }
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
