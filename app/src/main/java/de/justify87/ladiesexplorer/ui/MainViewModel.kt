package de.justify87.ladiesexplorer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.justify87.ladiesexplorer.core.*
import de.justify87.ladiesexplorer.LadiesApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    val attemptId: Long = 0L,
)

data class DiscoverUiState(
    val cards: List<DiscoveryCard> = emptyList(),
    val localCards: List<DiscoveryCard> = emptyList(),
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
    val globalVisibility: GlobalResultVisibility = GlobalResultVisibility(),
    val canUndoSwipe: Boolean = false,
    val gridUndo: GridUndoReceipt? = null,
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
    val membership: MapMembership? = null,
    val error: String? = null,
    val actionGeneration: Long = 0L,
)

data class SearchUiState(
    val city: String = "Leipzig",
    val radiusKm: UInt = 20u,
    val category: String = "",
    val ageMin: UInt = 18u,
    val ageMax: UInt = 60u,
    val heightMin: UInt = 140u,
    val heightMax: UInt = 200u,
    val selectedFilters: Map<String, Set<String>> = emptyMap(),
    val filterCatalog: SourceFilterCatalog? = null,
    val local: LocalVisibility = defaultLocalVisibility(),
    val frequentFilters: List<FrequentFilter> = emptyList(),
    val filterError: String? = null,
    val preview: SearchPreview? = null,
    val previewTarget: SearchRequest? = null,
    val previewEditorSession: Long? = null,
    val previewLoading: Boolean = false,
    val previewError: String? = null,
    val defaultReady: Boolean = true,
    val defaultSaving: Boolean = false,
    val defaultStatus: String? = null,
    val activeCards: List<DiscoveryCard> = emptyList(),
    val activeProjectionPending: Boolean = false,
    val activeProjectionError: String? = null,
    val loading: Boolean = false,
    val searchCancelling: Boolean = false,
    val searchAttemptId: Long = 0L,
    val response: SearchResponse? = null,
    val profileOpen: Boolean = false,
    val profileSessionId: Long = 0L,
    val profileRead: ProfileReadModel? = null,
    val profileMatchContext: ProfileMatchContext? = null,
    val profileRetryFromArchive: Boolean = false,
    val profileSourceRetryAllowed: Boolean = false,
    val actionBusy: Boolean = false,
    val actionError: String? = null,
    val retryProfileListingId: Long? = null,
    val profileRetryAttempt: Long = 0L,
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
    private val _discoverState = MutableStateFlow(DiscoverUiState(
        replayLikes = core.replayLikes(), globalVisibility = core.globalResultVisibility(),
    ))
    val discoverState: StateFlow<DiscoverUiState> = _discoverState
    private val _searchUiState = MutableStateFlow(SearchUiState(defaultReady = false))
    private var defaultSearchDraft = SearchDraft()
    val searchUiState: StateFlow<SearchUiState> = _searchUiState
    private val _profileNotes = MutableStateFlow<Map<Long, NoteEditorState>>(emptyMap())
    internal val profileNotes: StateFlow<Map<Long, NoteEditorState>> = _profileNotes
    private val noteReadTickets = NoteReadTickets()
    internal val noteActions = NoteEditorActions(::loadProfileNote, ::editProfileNote, ::saveProfileNote, ::useStoredNote, ::keepNoteDraft)
    internal val encounters: EncounterEditor = EncounterEditor(
        ::isProfileContextCurrent, core::encounters, ::submitEncounter, { !hasProfileEditor },
    )
    internal val prices: PriceEditor = PriceEditor(
        ::isProfileContextCurrent, core::priceOverview, ::submitPrice, { !hasProfileEditor },
    )
    internal val hasProfileEditor get() = encounters.edit != null || prices.edit != null

    private fun <R, V, D, S> submitProfileRecord(
        editor: ProfileRecordEditor<R, V, D, S>,
        edit: ProfileRecordEdit<R, D>,
        failureLabel: String,
        write: suspend () -> Unit,
    ) {
        if (_discoverState.value.loading || !isProfileContextCurrent(edit.token)) {
            editor.failed(edit, "Profilkontext hat sich geändert.")
            return
        }
        mutateDiscovery(failureLabel, localRecordOnly = true, write = {
            withContext(NonCancellable) {
                write()
                editor.committed(edit)
            }
        }, onFailure = { message -> editor.failed(edit, message) })
    }

    private fun submitEncounter(edit: EncounterEdit, values: EncounterValues?) =
        submitProfileRecord(encounters, edit, "Begegnung konnte nicht gespeichert werden") {
            val token = edit.token
            when {
                edit.deleting -> core.deleteEncounter(token.listingId, token.ownerId, checkNotNull(edit.expected))
                edit.expected == null -> core.createEncounter(token.listingId, token.ownerId, checkNotNull(values))
                else -> core.updateEncounter(token.listingId, token.ownerId, edit.expected, checkNotNull(values))
            }
            prices.invalidateAfterCommit(token.ownerId)
        }

    private fun submitPrice(edit: PriceEdit, values: PriceListValues?) =
        submitProfileRecord(prices, edit, "Preisliste konnte nicht gespeichert werden") {
            val token = edit.token
            when {
                edit.deleting -> core.deletePriceList(token.listingId, token.ownerId, checkNotNull(edit.expected))
                edit.expected == null -> core.createPriceList(token.listingId, token.ownerId, checkNotNull(values))
                else -> core.updatePriceList(token.listingId, token.ownerId, edit.expected, checkNotNull(values))
            }
        }

    private val _mapState = MutableStateFlow(MapUiState())
    val mapState: StateFlow<MapUiState> = _mapState
    private var mapEpoch = 0L
    private var mapControl: RequestControl? = null
    private var mapRefreshPending = false
    private var mapUserCancelled = false
    private var locationPreparationActive = false
    private var savedStatusControl: RequestControl? = null
    private var savedStatusAttemptId = 0L

    private val searchOwner = DrainingOperation<RequestControl>()
    private val homeOwner = DrainingOperation<Unit>()
    private var homeRefreshPending = false
    private var previewControl: RequestControl? = null
    private var previewJob: Job? = null
    private val previewGeneration = RequestGeneration()
    private val previewOwner = SearchPreviewOwner()
    private var discoveryEpoch = 0L
    private var swipeVisible = false
    private var renderedSwipe: SwipeActionToken? = null

    internal fun setProfileActionSurface(surface: ProfileActionSurface) {
        _discoverState.update { state ->
            if (state.actionSurface == surface) state
            else state.copy(actionSurface = surface, actionGeneration = state.actionGeneration + 1,
                gridUndo = if (surface == ProfileActionSurface.GRID) state.gridUndo else null)
        }
    }

    internal fun setSwipeVisible(visible: Boolean) {
        if (swipeVisible == visible) return
        swipeVisible = visible
        renderedSwipe = null
        _discoverState.update {
            val window = swipeReadWindow(it.cards).map { card -> card.listingId }.toSet()
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

    internal fun isProfileActionCurrent(token: ProfileIdentityToken): Boolean =
        !hasProfileEditor && isProfileContextCurrent(token)

    private fun isProfileContextCurrent(token: ProfileIdentityToken): Boolean = when (token) {
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
            if (token is DetailIdentityToken) profileOperations.refreshLocalProfile(token.sessionId, token.listingId)
        }, recoverWriteFailure = {
            if (token is DetailIdentityToken) profileOperations.refreshLocalProfile(token.sessionId, token.listingId)
        }, onFailure = { message ->
            if (token is DetailIdentityToken) _searchUiState.update {
                if (it.profileSessionId == token.sessionId) it.copy(actionError = message) else it
            }
        })
    }

    private fun admittedDeck(token: SwipeDeckToken): Boolean =
        !hasProfileEditor && swipeVisible && !_searchUiState.value.profileOpen &&
            currentSwipeDeck(_discoverState.value, _searchUiState.value.loading, token)

    private fun admittedTraversal(token: SwipeCardToken): DiscoveryCard? =
        if (!admittedDeck(token.deck)) null
        else currentSwipeTraversalCard(_discoverState.value, _searchUiState.value.loading, token)

    private fun invalidateSwipeProfilesLocally(strictListing: Long? = null) {
        renderedSwipe = null
        _discoverState.update {
            val window = swipeReadWindow(it.cards).map { card -> card.listingId }.toSet()
            val strict = it.localProfileStrictIds.intersect(window) + listOfNotNull(strictListing)
            it.copy(swipeSessionId = it.swipeSessionId + 1, swipeProfileRevision = it.swipeProfileRevision + 1,
                localProfileReloadIds = window + strict, localProfileStrictIds = strict)
        }
    }

    private fun invalidateSwipeActions(invalidateProfiles: Boolean = false, clearMergeNotice: Boolean = true) {
        renderedSwipe = null
        _discoverState.update { it.copy(swipeSessionId = it.swipeSessionId + 1,
            swipeProfileRevision = it.swipeProfileRevision + if (invalidateProfiles) 1 else 0,
            localProfileReloadIds = if (invalidateProfiles) emptySet() else it.localProfileReloadIds,
            localProfileStrictIds = if (invalidateProfiles) emptySet() else it.localProfileStrictIds,
            mergeNotice = if (clearMergeNotice) null else it.mergeNotice,
            pendingMergeListingId = if (clearMergeNotice) null else it.pendingMergeListingId) }
    }
    private var localWindow = 0
    private val savedWindows = SavedWindowMemory()
    private val filterGeneration = RequestGeneration()
    private val swipeSession = SwipeSession().apply { replayLikes = core.replayLikes() }

    internal val savedSearch = SavedSearchController(
        scope = viewModelScope,
        store = SavedSearchStore(core),
        canOperate = { !hasProfileEditor && _searchUiState.value.defaultReady && !_searchUiState.value.defaultSaving && !_searchUiState.value.loading && !_uiState.value.loading && !_discoverState.value.busy && !_searchUiState.value.profileOpen },
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
        SwipeProjectionInvalidation(savedSearch::refreshProjection,
            { refreshDiscoveryAfter(null, background = swipeVisible) }, ::ensureMap)
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
                discovery = ::refreshDiscovery, savedSearch = savedSearch::refreshProjection,
                map = { refreshMap() }, home = ::refresh,
                admitSwipe = { admittedSwipe(it) != null },
                beginMerge = { invalidateSwipeActions() },
                mergeCommitted = ::onIdentityMergeCommitted,
                profileChanged = { invalidateSwipeProfilesLocally() },
            ),
        )
    }

    init {
        viewModelScope.launch {
            try {
                core.loadDefaultSearch()?.let { definition ->
                    defaultSearchDraft = SearchDraft.from(definition.query, definition.local)
                    _searchUiState.update { defaultSearchDraft.applyTo(it) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                _searchUiState.update {
                    it.copy(defaultStatus = "Suchstandard konnte nicht geladen werden: ${failure.message.orEmpty()}")
                }
            } finally {
                _searchUiState.update { it.copy(defaultReady = true) }
            }
        }
        refresh()
        refreshDiscovery()
        refreshSearchDefinitions()
    }

    private fun invalidateActiveSearch() {
        searchOwner.cancel { it.cancel() }
    }

    internal fun cancelActiveSearch(action: SearchCancelAction) {
        if (!searchOwner.occupied ||
            !searchCancelMatches(_discoverState.value, _searchUiState.value, savedSearch.state.value, swipeVisible, action)) return
        invalidateActiveSearch()
        _searchUiState.update { it.copy(searchCancelling = true) }
    }

    private fun invalidateProfileRequest() = profileOperations.invalidateProfileRequest()

    override fun onCleared() {
        savedStatusControl?.cancel()
        savedSearch.cancelCheck()
        cancelMapResolution()
        clearPreview()
        invalidateActiveSearch()
        invalidateProfileRequest()
        super.onCleared()
    }

    internal fun cancelMediaArchive(session: Long) = profileOperations.cancelMediaArchive(session)

    internal fun closeProfile(session: Long) {
        if (!hasProfileEditor) profileOperations.closeProfile(session)
    }

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

    internal fun beginPreview(lease: SearchPreviewLease) {
        if (searchPreviewTarget(_searchUiState.value, savedSearch.state.value) != lease.target) return
        previewOwner.replace(lease)
        schedulePreview()
    }

    internal fun endPreview(lease: SearchPreviewLease) {
        if (previewOwner.release(lease)) clearPreview()
    }

    internal fun clearPreview() {
        previewOwner.clear()
        cancelPreview()
        _searchUiState.update { it.copy(preview = null, previewError = null, previewTarget = null,
            previewEditorSession = null) }
    }

    private fun schedulePreview() {
        cancelPreview()
        val lease = previewOwner.current
        val target = lease?.target?.takeIf { it == searchPreviewTarget(_searchUiState.value, savedSearch.state.value) }
        val input = target?.draft?.request()
        _searchUiState.update { it.copy(preview = null, previewError = null, previewTarget = input,
            previewEditorSession = target?.editorSession) }
        if (lease == null || input == null || input.city.isBlank()) return
        val epoch = previewGeneration.value
        fun current() = previewGeneration.isCurrent(epoch) && previewOwner.accepts(lease) &&
            searchPreviewTarget(_searchUiState.value, savedSearch.state.value) == lease.target
        val control = RequestControl()
        previewControl = control
        _searchUiState.update { it.copy(previewLoading = true) }
        previewJob = viewModelScope.launch {
            try {
                delay(350)
                if (!current()) return@launch
                val result = core.preview(input, control)
                if (current()) _searchUiState.update { it.copy(preview = result, previewLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current()) _searchUiState.update {
                    it.copy(previewLoading = false, previewError = failure.message ?: "Vorschau nicht verfügbar")
                }
            } finally {
                if (previewGeneration.isCurrent(epoch)) {
                    previewControl = null
                    previewJob = null
                    _searchUiState.update { it.copy(previewLoading = false) }
                }
            }
        }
    }

    internal fun editSearchDraft(action: SearchDraftEditAction, change: (SearchDraft) -> SearchDraft) {
        val current = _searchUiState.value
        if (!searchDraftEditMatches(_discoverState.value, current, savedSearch.state.value, action)) return
        val draft = SearchDraft.from(current)
        val next = change(draft)
        if (draft == next) return
        _searchUiState.update { it.applyDraftChange(next.applyTo(it)).copy(defaultStatus = null) }
    }

    internal fun resetSearchDraft(action: SearchDraftEditAction, renderedDraft: SearchDraft): Boolean {
        if (!searchDraftResetMatches(_discoverState.value, _searchUiState.value, savedSearch.state.value,
                action, renderedDraft)) return false
        editSearchDraft(action) { defaultSearchDraft }
        _discoverState.update { it.copy(actionGeneration = it.actionGeneration + 1) }
        return true
    }

    internal fun saveSearchDefault(action: SearchDraftEditAction, renderedDraft: SearchDraft) {
        if (!searchDraftResetMatches(_discoverState.value, _searchUiState.value, savedSearch.state.value,
                action, renderedDraft) || renderedDraft.city.isBlank()) return
        _discoverState.update { it.copy(actionGeneration = it.actionGeneration + 1) }
        _searchUiState.update { it.copy(defaultSaving = true, defaultStatus = null) }
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    val definition = core.saveDefaultSearch(renderedDraft.request(), renderedDraft.local)
                    defaultSearchDraft = SearchDraft.from(definition.query, definition.local)
                    _searchUiState.update { it.copy(defaultStatus = "Suchstandard gespeichert.") }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                _searchUiState.update {
                    it.copy(defaultStatus = "Suchstandard konnte nicht gespeichert werden: ${failure.message.orEmpty()}")
                }
            } finally {
                _searchUiState.update { it.copy(defaultSaving = false) }
            }
        }
    }

    /** Check swipe admission against live state, not the last Compose frame. */
    internal fun runSwipeAvailability(token: SwipeDeckToken, mode: AvailabilityMode) {
        val search = _searchUiState.value
        if (hasProfileEditor || !swipeVisible || search.profileOpen ||
            !currentSwipeAvailability(_discoverState.value, search.loading, search.response?.searchRunId, token)) return
        runAvailability(mode)
    }

    internal fun runDiscoveryAvailability(action: DiscoveryAvailabilityAction, mode: AvailabilityMode) {
        if (hasProfileEditor ||
            !discoveryAvailabilityMatches(_discoverState.value, _searchUiState.value, action)) return
        runAvailability(mode)
    }

    private fun runAvailability(mode: AvailabilityMode) {
        val state = _searchUiState.value
        val active = state.response
        val draft = SearchDraft.from(state)
        val query = (active?.query ?: draft.request()).withAvailabilityMode(mode)
        startSearch(query, active?.local ?: draft.local)
    }

    internal fun submitSearch(action: SearchSubmitAction) {
        if (!searchSubmitMatches(_discoverState.value, _searchUiState.value, savedSearch.state.value, action)) return
        startSearch(action.draft.request(), action.draft.local)
    }

    fun refreshSearchDefinitions() {
        savedSearch.refresh()
        refreshFilterCatalog()
    }

    // Searches may persist before delivery fails or is revoked; reconcile local state after drain.
    private fun refreshAfterSourceSearch(invalidateProfileWindow: Boolean = true) {
        if (invalidateProfileWindow && swipeVisible) invalidateSwipeProfilesLocally()
        refreshDiscovery()
        refresh()
        refreshFilterCatalog()
    }

    private fun refreshFilterCatalog() {
        filterGeneration.invalidate()
        val ticket = filterGeneration.value
        viewModelScope.launch {
            refreshSearchFilters(_searchUiState, { filterGeneration.isCurrent(ticket) },
                core::sourceFilterCatalog, core::frequentFilters)
        }
    }

    private fun startSearch(request: SearchRequest, local: LocalVisibility) {
        if (hasProfileEditor || searchOwner.occupied || !_searchUiState.value.defaultReady || _searchUiState.value.defaultSaving || _searchUiState.value.loading || savedSearch.state.value.busy || _uiState.value.loading || _discoverState.value.busy) return
        invalidateSwipeActions(invalidateProfiles = true)
        // Revoke rendered actions even if this source run fails or is cancelled.
        _discoverState.update { it.copy(actionGeneration = it.actionGeneration + 1) }
        cancelPreview()
        val control = RequestControl()
        val ticket = checkNotNull(searchOwner.start(control))
        invalidateProfileRequest()
        _searchUiState.update { it.beginRun(ticket) }
        viewModelScope.launch {
            var acceptedRun = false
            try {
                val result = core.search(request, local, control)
                if (!searchOwner.accepts(ticket)) return@launch
                val cards = core.activeSearchCards(result.searchRunId, _discoverState.value.globalVisibility.effective(result.local))
                if (!searchOwner.accepts(ticket)) return@launch
                swipeSession.reset()
                discoveryEpoch++
                _discoverState.update {
                    it.copy(cards = swipeSession.queue(cards, active = true), localCards = emptyList(), canUndoSwipe = false, gridUndo = null, swipeRestore = null,
                        swipePresentationGeneration = it.swipePresentationGeneration + 1, nextCursor = null, activeRunId = result.searchRunId,
                        activeVisibleCount = cards.size, loading = false, loadingMore = false,
                        savedLoadingMore = false, savedRecentLoadingMore = false, error = null, actionGeneration = it.actionGeneration + 1)
                }
                _searchUiState.update { it.acceptRun(result, cards) }
                refreshMap()
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
                    if (acceptedRun) clearPreview() else schedulePreview()
                }
            }
        }
    }

    private fun mapMembership(): MapMembership? =
        currentMapMembership(_discoverState.value, _searchUiState.value)

    private fun ensureMap() {
        val membership = mapMembership() ?: return
        val state = _mapState.value
        if (_discoverState.value.error != null) {
            refreshDiscovery()
        } else if (state.membership != membership || state.needsLocationPreparation()) {
            refreshMap()
        }
    }

    private fun refreshMap(allowBackfill: Boolean = true) {
        if (!locationPreparationActive) return
        val membership = mapMembership()
        if (mapControl != null) {
            mapRefreshPending = membership != null
            // A new local observation can change coordinates without changing membership.
            ++mapEpoch
            if (_mapState.value.membership != membership) {
                cancelMapResolution()
                _mapState.update { it.copy(membership = membership, overview = null, loading = false,
                    error = null, actionGeneration = it.actionGeneration + 1) }
            }
            return
        }
        if (membership == null) return
        val epoch = ++mapEpoch
        val discoveryError = _discoverState.value.error
        if (discoveryError != null) {
            _mapState.update { it.copy(membership = membership, overview = null, loading = false,
                error = discoveryError, actionGeneration = it.actionGeneration + 1) }
            return
        }
        val control = RequestControl()
        mapControl = control
        mapUserCancelled = false
        _mapState.update {
            val cached = it.overview.takeIf { _ -> it.membership == membership }
            it.copy(membership = membership, overview = cached, loading = cached == null,
                resolving = true, cancelling = false, error = null, actionGeneration = it.actionGeneration + 1)
        }
        viewModelScope.launch {
            fun current() = epoch == mapEpoch && mapMembership() == membership
            suspend fun readOverview(failureMessage: String? = null) {
                projectMapOverview(_mapState, ::current, {
                    core.mapOverview(membership.listingIds, membership.runId).also {
                        check(membership.matches(it)) { "Kartenmitgliedschaft hat sich geändert" }
                    }
                }) { state, overview ->
                    state.copy(overview = overview, loading = false, error = failureMessage)
                }
            }
            try {
                // Publish already prepared locations before any remaining backfill.
                readOverview()
                if (allowBackfill && current() && !_mapState.value.cancelling &&
                    (_mapState.value.overview?.pendingLocations ?: 0u) > 0u) {
                    var failureMessage: String? = null
                    try {
                        core.prepareMapLocations(membership.listingIds, membership.runId, control)
                    } catch (cancelled: CancellationException) {
                        control.cancel()
                        throw cancelled
                    } catch (failure: Exception) {
                        if (!_mapState.value.cancelling) {
                            failureMessage = failure.message ?: "Standortaufbereitung fehlgeschlagen"
                        }
                    }
                    if (current()) readOverview(failureMessage)
                }
            } finally {
                if (mapControl === control) {
                    mapControl = null
                    _mapState.update { it.copy(resolving = false, cancelling = false) }
                }
            }
            val nextMembership = mapMembership()
            if (mapRefreshPending || nextMembership != membership) {
                mapRefreshPending = false
                // Cancel stops backfill, not reconciliation of already committed observations.
                refreshMap(allowBackfill = !mapUserCancelled || nextMembership != membership)
            }
        }
    }

    internal fun cancelRenderedMapResolution(action: MapControlAction) {
        if (mapCancelMatches(_mapState.value, _discoverState.value, _searchUiState.value, action)) {
            mapUserCancelled = true
            cancelMapResolution()
        }
    }

    private fun cancelMapResolution() {
        val control = mapControl ?: return
        control.cancel()
        _mapState.update { it.copy(cancelling = true, actionGeneration = it.actionGeneration + 1) }
    }

    internal fun setLocationPreparationActive(active: Boolean) {
        if (locationPreparationActive == active) return
        locationPreparationActive = active
        if (active) {
            mapUserCancelled = false
            refreshMap()
        } else {
            ++mapEpoch
            mapRefreshPending = false
            cancelMapResolution()
        }
    }

    private suspend fun discoveryPage(): DiscoveryProjection {
        val active = _searchUiState.value.response
        if (active == null) {
            return DiscoveryProjection(null, emptyList(), readDiscoveryWindow(localWindow) { cursor ->
                core.discoveryCards(cursor = cursor)
            })
        }
        val visible = core.activeSearchCards(active.searchRunId, _discoverState.value.globalVisibility.effective(active.local))
        return DiscoveryProjection(active.searchRunId, visible, DiscoveryPage(visible, null))
    }

    private suspend fun savedPage(): SavedPeopleWindow {
        val state = _discoverState.value
        return core.savedPeopleWindow(state.savedKind, state.savedSort,
            savedWindows.limits(state.savedKind, state.savedSort))
    }

    private fun admitsSavedPeopleControl(action: SavedPeopleControlAction): Boolean =
        savedPeopleControlMatches(_discoverState.value, _searchUiState.value, action)

    internal fun selectSavedKind(action: SavedPeopleControlAction, kind: SavedPersonKind) {
        if (!admitsSavedPeopleControl(action) || action.kind == kind) return
        _discoverState.update { it.copy(savedKind = kind, savedPage = null, savedRecent = null,
            actionGeneration = it.actionGeneration + 1) }
        refreshDiscovery()
    }

    internal fun selectSavedSort(action: SavedPeopleControlAction, sort: SavedPersonSort) {
        if (!admitsSavedPeopleControl(action) || action.sort == sort) return
        _discoverState.update { it.copy(savedSort = sort, savedPage = null, savedRecent = null,
            actionGeneration = it.actionGeneration + 1) }
        refreshDiscovery()
    }

    internal fun refreshRenderedSavedPeople(action: SavedPeopleControlAction) {
        if (!admitsSavedPeopleControl(action)) return
        _discoverState.update { it.copy(actionGeneration = it.actionGeneration + 1) }
        refreshDiscovery()
    }

    private suspend fun projectDiscovery(epoch: Long): Boolean {
        val result = discoveryPublisher.project(epoch)
        if (epoch == discoveryEpoch) refreshMap()
        return result
    }

    fun markSwipeSnapshotChanged(oldWindow: Boolean) {
        // A late completion revokes replacement data without recursively cancelling its native work.
        renderedSwipe = null
        if (oldWindow) _discoverState.update {
            it.copy(swipeSessionId = it.swipeSessionId + 1, swipeExternalRevision = it.swipeExternalRevision + 1)
        }
        swipeInvalidation.sourceCompleted()
    }

    fun setDiscoveryProjectionVisibility(listVisible: Boolean, mapVisible: Boolean) {
        swipeInvalidation.setVisibility(listVisible, mapVisible)
    }

    fun refreshDiscovery() = refreshDiscoveryAfter(null)

    internal fun refreshRenderedGrid(action: GridRefreshAction) {
        if (!gridRefreshMatches(_discoverState.value, _searchUiState.value, action)) return
        _discoverState.update { it.copy(actionGeneration = it.actionGeneration + 1) }
        refreshDiscovery()
    }

    internal fun refreshSwipe(token: SwipeDeckToken) {
        if (!admittedDeck(token)) return
        invalidateSwipeActions(invalidateProfiles = true)
        refreshDiscovery()
    }

    private fun refreshDiscoveryAfter(message: String?, background: Boolean = false) {
        if (!swipeInvalidation.beginDiscovery(_discoverState.value.busy)) return
        val epoch = ++discoveryEpoch
        if (!background) _discoverState.update {
            it.copy(loading = true, loadingMore = false, savedLoadingMore = false, savedRecentLoadingMore = false)
        }
        viewModelScope.launch {
            try {
                if (background) {
                    discoveryPublisher.project(epoch, background = true)
                    if (epoch == discoveryEpoch) refreshMap()
                } else projectDiscovery(epoch)
                if (epoch == discoveryEpoch && message != null) _discoverState.update { it.copy(mutationError = message) }
            } finally {
                if (epoch == discoveryEpoch) _discoverState.update { it.copy(loading = false) }
            }
        }
    }

    internal fun loadMoreDiscovery(action: GridPageAction) {
        if (!gridPageMatches(_discoverState.value, _searchUiState.value, action)) return
        loadMoreLocalDiscovery(action.cursor)
    }

    private fun loadMoreLocalDiscovery(cursor: DiscoveryCursor) {
        if (!localDiscoveryPageMatches(_discoverState.value, _searchUiState.value, cursor)) return
        val epoch = discoveryEpoch
        _discoverState.update {
            it.copy(loadingMore = true, error = null, actionGeneration = it.actionGeneration + 1)
        }
        viewModelScope.launch {
            try {
                val page = core.discoveryCards(cursor = cursor)
                if (epoch == discoveryEpoch) {
                    _discoverState.update {
                        val membership = (it.localCards + page.cards).distinctBy { card -> card.personId }
                        val cards = swipeSession.queue(membership, active = false)
                        localWindow = maxOf(localWindow, membership.size)
                        it.copy(cards = cards, localCards = membership, nextCursor = page.nextCursor, loadingMore = false,
                            actionGeneration = it.actionGeneration + 1)
                    }
                    refreshMap()
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

    internal fun loadMoreSavedPeople(action: SavedPeoplePageAction) {
        if (!savedPeoplePageMatches(_discoverState.value, _searchUiState.value, action)) return
        val recent = action.watchlist
        val epoch = discoveryEpoch
        savedWindows.requestMore(action.kind, action.sort, recent, action.page.rows.size)
        val limits = savedWindows.limits(action.kind, action.sort)
        _discoverState.update {
            it.copy(savedLoadingMore = !recent, savedRecentLoadingMore = recent, savedError = null)
        }
        viewModelScope.launch {
            try {
                val next = core.savedPeopleWindow(action.kind, action.sort, limits)
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
        if (!admittedDeck(token)) return
        applyReplayLikes(replay)
    }

    internal fun setSettingsReplayLikes(action: SettingsReplayAction, replay: Boolean) {
        if (hasProfileEditor || swipeVisible || _uiState.value.loading ||
            !settingsReplayMatches(_discoverState.value, _searchUiState.value, action)) return
        applyReplayLikes(replay)
    }

    internal fun setSettingsGlobalVisibility(action: SettingsVisibilityAction, value: GlobalResultVisibility) {
        if (hasProfileEditor || swipeVisible || _uiState.value.loading ||
            !settingsVisibilityMatches(_discoverState.value, _searchUiState.value, action) ||
            action.expected == value) return
        invalidateSwipeActions(clearMergeNotice = false)
        core.setGlobalResultVisibility(value)
        // Old results must not become actionable under the newly confirmed policy.
        if (_searchUiState.value.response != null) {
            _searchUiState.update { it.copy(activeProjectionPending = true, activeProjectionError = null) }
        }
        _discoverState.update { it.copy(globalVisibility = value, actionGeneration = it.actionGeneration + 1) }
        refreshDiscovery()
    }

    private fun applyReplayLikes(replay: Boolean) {
        if (swipeSession.replayLikes == replay) return
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
            it.copy(cards = swipeSession.queue(discoveryMembership(it, _searchUiState.value), active = it.activeRunId != null),
                canUndoSwipe = true, swipeRestore = null)
        }
        val state = _discoverState.value
        if (state.cards.size < 10) state.nextCursor?.let(::loadMoreLocalDiscovery)
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
        localRecordOnly: Boolean = false,
        retainGridUndo: Boolean = false,
    ) {
        invalidateSwipeActions(clearMergeNotice = !localRecordOnly)
        val epoch = ++discoveryEpoch
        _discoverState.update { it.copy(busy = true, loading = false, mutationError = null,
            gridUndo = if (retainGridUndo) it.gridUndo else null,
            loadingMore = false, savedLoadingMore = false, savedRecentLoadingMore = false) }
        viewModelScope.launch {
            var message: String? = null
            var retryProjection = false
            try {
                val outcome = writeAndProject(
                    write = write,
                    project = {
                        if (!localRecordOnly) check(projectDiscovery(epoch)) { "Ansicht konnte nicht vollständig aktualisiert werden." }
                    },
                    afterWrite = {
                        // Revision can change without a membership change.
                        if (!localRecordOnly) {
                            savedSearch.refreshProjection()
                            if (refreshProfileWindow) invalidateSwipeProfilesLocally()
                        }
                        afterWrite()
                    },
                    onWriteFailure = onWriteFailure,
                    recoverWriteFailure = {
                        if (!localRecordOnly) {
                            invalidateSwipeProfilesLocally()
                            savedSearch.refreshProjection()
                        }
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

    internal fun chooseGridPreference(action: GridLikeAction, target: Preference) {
        val state = _discoverState.value
        val search = _searchUiState.value
        if (hasProfileEditor || !gridActionMatches(state, search, action)) return
        val card = action.card
        if (card.preference == target) return
        mutateDiscovery("Entscheidung konnte nicht gespeichert werden", write = {
            val before = core.setSwipePreference(card.listingId, card.personId, card.preference, target)
            _discoverState.update {
                if (it.actionSurface == ProfileActionSurface.GRID && it.activeRunId == action.runId)
                    it.copy(gridUndo = GridUndoReceipt(action.runId, card.listingId, card.personId, before, target))
                else it
            }
        }, retainGridUndo = true)
    }

    internal fun undoGridPreference(action: GridUndoAction) {
        if (hasProfileEditor || !gridUndoMatches(_discoverState.value, _searchUiState.value, action)) return
        val receipt = action.receipt
        mutateDiscovery("Undo fehlgeschlagen", write = {
            undoSwipeStep(receipt.step(), core::setSwipePreference, { core.profileIdentity(it).personId }) {
                _discoverState.update { state -> state.copy(gridUndo = null) }
            }
        }, onWriteFailure = {
            _discoverState.update { it.copy(gridUndo = null) }
        }, retainGridUndo = true)
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

    internal fun cancelRenderedSavedStatus(action: SavedStatusCancelAction) {
        if (!savedStatusCancelMatches(_discoverState.value, _searchUiState.value, action)) return
        cancelSavedStatus()
    }

    fun cancelSavedStatus() {
        val control = savedStatusControl ?: return
        val progress = _discoverState.value.savedStatusProgress ?: return
        if (!progress.running || progress.cancelled) return
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
        val attemptId = ++savedStatusAttemptId
        _discoverState.update { it.copy(savedStatusProgress = SavedStatusProgress(attemptId = attemptId)) }
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
            } catch (cancel: CancellationException) {
                // Coroutine cancellation bypasses writeAndProject's normal reconciliation.
                savedSearch.refreshProjection()
                throw cancel
            } finally {
                savedStatusControl = null
                _discoverState.update { current ->
                    current.copy(savedStatusProgress = current.savedStatusProgress?.copy(running = false))
                }
            }
        }, afterWrite = { refreshMap() }, recoverWriteFailure = { refreshMap() })
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
        if (_discoverState.value.busy || _profileNotes.value[personId]?.canEdit() != true) return
        updateNote(personId) { it.copy(draft = it.draft?.edit(text), error = null) }
    }

    private fun useStoredNote(personId: Long, draft: NoteDraft) {
        if (_discoverState.value.busy || _profileNotes.value[personId]?.canDiscard(draft) != true) return
        updateNote(personId) { it.copy(draft = draft.useStored(), error = if (it.readError) it.error else null) }
    }

    private fun keepNoteDraft(personId: Long, draft: NoteDraft) {
        if (_discoverState.value.busy || _profileNotes.value[personId]?.canKeep(draft) != true) return
        updateNote(personId) { it.copy(draft = draft.keepDraft(), error = null) }
    }

    private fun saveProfileNote(listingId: Long, personId: Long, draft: NoteDraft) {
        if (_discoverState.value.busy || _profileNotes.value[personId]?.canSave(draft) != true) return
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

    internal fun openGridProfile(action: GridLikeAction): Boolean {
        if (!gridActionMatches(_discoverState.value, _searchUiState.value, action)) return false
        return openDiscoverySwipe(action.card.listingId, action.card.personId)
    }

    private fun openDiscoverySwipe(listingId: Long, ownerId: Long): Boolean {
        val state = _discoverState.value
        val search = _searchUiState.value
        if (hasProfileEditor || state.busy || state.loading || search.loading || search.actionBusy ||
            search.profileOpen || savedSearch.state.value.blockingNavigation ||
            state.activeRunId != search.response?.searchRunId) return false
        val membership = discoveryMembership(state, search)
        if (!swipeSession.focus(membership, listingId, ownerId)) return false
        setProfileActionSurface(ProfileActionSurface.NONE)
        invalidateSwipeActions()
        _discoverState.update {
            val queue = swipeSession.queue(membership, active = state.activeRunId != null)
            it.copy(cards = queue, swipeRestore = null,
                swipePresentationGeneration = it.swipePresentationGeneration + 1,
                localProfileReloadIds = it.localProfileReloadIds + swipeReadWindow(queue).map { card -> card.listingId })
        }
        return true
    }

    internal fun openSavedPersonProfile(action: SavedPersonAction) {
        if (!admittedSavedPersonRow(action)) return
        action.row.card?.let { loadProfile(it.listingId, action.row.personId) }
    }

    internal fun openMapProfile(action: MapProfileOpenAction): Boolean {
        if (!mapProfileOpenMatches(_mapState.value, _discoverState.value, _searchUiState.value, action)) return false
        return openDiscoverySwipe(action.profile.listingId, action.profile.personId)
    }

    internal fun openSearchResultProfile(action: SearchResultOpenAction) {
        if (savedSearch.state.value.pane != SearchPane.Filters ||
            !searchResultOpenMatches(_searchUiState.value, _discoverState.value, action)) return
        val response = _searchUiState.value.response?.takeIf { it.searchRunId == action.runId }
        loadProfile(action.card.listingId, action.card.personId,
            executedProfileMatchContext(response, action.card.personId, _discoverState.value.globalVisibility))
    }

    internal fun openSavedSearchProfile(action: SavedSearchProfileOpenAction) {
        val current = _discoverState.value
        if (current.actionSurface != ProfileActionSurface.SAVED_RESULTS ||
            current.actionGeneration != action.generation || !savedSearch.admitsProfileOpen(action)) return
        val listing = action.card.listingId ?: return
        val person = action.card.personId ?: return
        loadProfile(listing, person, ProfileMatchContext.Saved(action.window.id, person))
    }

    private fun loadProfile(listingId: Long, personId: Long, matchContext: ProfileMatchContext? = null) {
        if (hasProfileEditor || _discoverState.value.busy || _searchUiState.value.loading || savedSearch.state.value.blockingNavigation) return
        invalidateSwipeActions()
        clearPreview()
        invalidateActiveSearch()
        profileOperations.loadProfile(listingId, personId, matchContext) { savedSearch.setVisible(false) }
    }

    internal fun confirmIdentity(token: ProfileIdentityToken, targetPersonId: Long) {
        if (hasProfileEditor) {
            if (isProfileContextCurrent(token)) _discoverState.update { it.copy(mutationError = "Profilentwurf zuerst speichern oder verwerfen.") }
            return
        }
        profileOperations.confirmIdentity(token, targetPersonId)
    }

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

    internal fun retryProfileProjection(action: DetailRetryAction) = profileOperations.retryProfileProjection(action)

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
