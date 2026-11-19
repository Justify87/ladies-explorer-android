package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Existing standalone IO calls, bound to CoreStore in MainViewModel. No transport or state. */
internal class StandaloneProfileIo<C : Any>(
    val loadProfile: suspend (Long, C) -> ProfileResponse,
    val cachedProfile: suspend (Long) -> ProfileResponse?,
    val profileProjection: suspend (Long) -> NativeProfileProjection?,
    val archiveProfileMedia: suspend (Long, C) -> MediaArchiveReport,
    val newArchiveControl: () -> C,
    val cancelArchive: (C) -> Unit,
    val confirmIdentityMatch: suspend (Long, Long, Long, Long) -> Unit,
    val newSourceControl: () -> C,
    val cancelSource: (C) -> Unit,
)

/** The existing independent projection owners remain in MainViewModel/SavedSearchController. */
internal class ProfileProjectionCallbacks(
    val epoch: () -> Long,
    val nextEpoch: () -> Long,
    val pending: () -> Boolean,
    val projectDiscovery: suspend (Long) -> Boolean,
    val discovery: () -> Unit,
    val savedSearch: () -> Unit,
    val map: () -> Unit,
    val home: () -> Unit,
    val admitSwipe: (SwipeActionToken) -> Boolean = { false },
    val beginMerge: () -> Unit = {},
    val mergeCommitted: (ProfileIdentityToken, Long) -> Unit = { _, _ -> },
    val profileChanged: () -> Unit = {},
)

/** Standalone read/archive lifecycle and the shared, context-neutral identity operation.
 * Only detail delivery uses profileGeneration. A Swipe merge never opens a detail route. */
internal class StandaloneProfileOperations<C : Any>(
    private val scope: CoroutineScope,
    private val _searchUiState: MutableStateFlow<SearchUiState>,
    private val _discoverState: MutableStateFlow<DiscoverUiState>,
    private val _profileNotes: MutableStateFlow<Map<Long, NoteEditorState>>,
    private val noteReadTickets: NoteReadTickets,
    private val io: StandaloneProfileIo<C>,
    private val projections: ProfileProjectionCallbacks,
) {
    private val profileGeneration = RequestGeneration()
    private var projectionRevision = 0L
    private class NativeProfileCall<C>(val control: C) {
        val drained = CompletableDeferred<Unit>()
        var cancelled = false
        fun cancel(signal: (C) -> Unit) {
            if (!cancelled) {
                cancelled = true
                signal(control)
            }
        }
    }
    private var archiveCall: NativeProfileCall<C>? = null
    private var sourceCall: NativeProfileCall<C>? = null
    private var presentationVisible = true
    private var requestedListingId: Long? = null

    private suspend fun project(listingId: Long): NativeProfileProjection =
        checkNotNull(io.profileProjection(listingId)) { "Profil nicht mehr verfügbar" }

    private fun SearchUiState.withProjection(
        local: NativeProfileProjection,
        fromArchive: Boolean = profileFromArchive,
        warning: String? = profileRead?.warning ?: mediaWarning,
    ) = copy(
        profileRead = ProfileReadModel(local, fromArchive, warning, ++projectionRevision),
        retryProfileListingId = null,
        profileRetryFromArchive = false,
    )

    fun invalidateProfileRequest() {
        profileGeneration.invalidate()
        sourceCall?.cancel(io.cancelSource)
        archiveCall?.cancel(io.cancelArchive)
    }

    fun cancelMediaArchive() {
        // Cancellation is a signal, not completion; the native call still owns the slot.
        archiveCall?.cancel(io.cancelArchive)
    }

    fun closeProfile() {
        requestedListingId = null
        invalidateProfileRequest()
        _searchUiState.update {
            it.copy(profileOpen = false, profileRead = null, profileRetryFromArchive = false,
                loading = false, archiving = false, actionBusy = false, actionError = null,
                error = null, retryProfileListingId = null, mediaArchive = null, mediaWarning = null)
        }
    }

    fun loadProfile(listingId: Long, personId: Long, beforeOpen: () -> Unit) {
        if (!presentationVisible) return
        invalidateProfileRequest()
        requestedListingId = listingId
        beforeOpen()
        val requestId = profileGeneration.value
        _searchUiState.update {
            it.copy(profileOpen=true,profileSessionId=requestId,loading=true,
                profileRead=null,profileRetryFromArchive=false,error=null,actionBusy=false,actionError=null,
                mediaArchive=null,mediaWarning=null,archiving=false,retryProfileListingId=null)
        }
        scope.launch {
            var localReadReady = false
            var showingArchive = false
            var refreshError: String? = null
            try {
                sourceCall?.drained?.await()
                archiveCall?.drained?.await()
                if (!profileGeneration.isCurrent(requestId)) return@launch
                try {
                    val call = NativeProfileCall(io.newSourceControl())
                    check(sourceCall == null)
                    sourceCall = call
                    try {
                        withContext(NonCancellable) { io.loadProfile(listingId, call.control) }
                        coroutineContext.ensureActive()
                    } finally {
                        if (sourceCall === call) sourceCall = null
                        // A source read can persist before a later failure or invalidation.
                        try {
                            projections.profileChanged()
                            projections.savedSearch()
                            projections.discovery()
                            projections.map()
                        } finally {
                            call.drained.complete(Unit)
                        }
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    if (!profileGeneration.isCurrent(requestId)) return@launch
                    io.cachedProfile(listingId) ?: throw failure
                    showingArchive = true
                    refreshError = "Archivierte Profildaten: Online-Aktualisierung fehlgeschlagen (${failure.message ?: "Netzwerk nicht verfügbar"})."
                }
                if (!profileGeneration.isCurrent(requestId)) return@launch
                localReadReady = true
                val local = project(listingId)
                if (!profileGeneration.isCurrent(requestId)) return@launch
                _searchUiState.update {
                    it.withProjection(local, showingArchive, refreshError).copy(loading=false,
                        mediaWarning=refreshError,archiving=!showingArchive)
                }
                if (showingArchive) return@launch
                val profile = local.profile
                val call = NativeProfileCall(io.newArchiveControl())
                check(archiveCall == null)
                archiveCall = call
                try {
                    val report = withContext(NonCancellable) {
                        io.archiveProfileMedia(profile.snapshotId, call.control)
                    }
                    coroutineContext.ensureActive()
                    if (profileGeneration.isCurrent(requestId)) {
                        val updated = project(listingId)
                        if (profileGeneration.isCurrent(requestId)) {
                            _searchUiState.update {
                                it.withProjection(updated).copy(
                                    mediaArchive = report.takeIf { updated.profile.snapshotId == profile.snapshotId },
                                )
                            }
                        }
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
                    if (archiveCall === call) archiveCall = null
                    if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(archiving=false) }
                    // Even a cancelled archive may already have committed individual media.
                    try {
                        projections.profileChanged()
                        projections.savedSearch()
                        projections.discovery()
                    } finally {
                        call.drained.complete(Unit)
                    }
                }
            } catch (cancellation: CancellationException) {
                if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(loading=false,archiving=false) }
                throw cancellation
            } catch (failure: Exception) {
                if (profileGeneration.isCurrent(requestId)) {
                    _searchUiState.update {
                        it.copy(loading=false,archiving=false,error=failure.message ?: "Profil konnte nicht geladen werden",
                            retryProfileListingId = listingId.takeIf { localReadReady },
                            profileRetryFromArchive = showingArchive, mediaWarning = refreshError)
                    }
                }
            }
        }
    }

    /** ON_STOP revokes delivery, not native execution ownership. Resume only projects locally. */
    fun setPresentationVisible(visible: Boolean) {
        if (presentationVisible == visible) return
        presentationVisible = visible
        val active = _searchUiState.value
        val listing = requestedListingId ?: return
        if (!active.profileOpen) return
        if (!visible) {
            invalidateProfileRequest()
            _searchUiState.update {
                it.copy(profileSessionId = profileGeneration.value, profileRead = null,
                    profileRetryFromArchive = it.profileRead?.fromArchive ?: true,
                    retryProfileListingId = listing, loading = false, archiving = false,
                    actionBusy = false, actionError = null, mediaArchive = null)
            }
        } else {
            val ticket = profileGeneration.value
            scope.launch {
                _discoverState.first { !it.busy }
                if (presentationVisible && profileGeneration.isCurrent(ticket)) retryProfileProjection()
            }
        }
    }

    /** Independent preference/favorite changes must refresh the same coherent detail record. */
    suspend fun refreshLocalProfileForOwner(personId: Long) {
        val active = _searchUiState.value
        val listingId = active.profile?.listingId ?: return
        if (!active.profileOpen || active.selectedPersonId != personId) return
        val requestId = profileGeneration.value
        try {
            val local = project(listingId)
            if (profileGeneration.isCurrent(requestId)) {
                _searchUiState.update { it.withProjection(local) }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (profileGeneration.isCurrent(requestId)) {
                _searchUiState.update { it.invalidateMergedProfile(listingId) }
            }
            throw failure
        }
    }

    /** Retry only the local projection; never replay the committed mutation or source request. */
    fun retryProfileProjection() {
        val active = _searchUiState.value
        val listingId = active.retryProfileListingId ?: return
        if (!presentationVisible || !active.profileOpen || active.actionBusy || _discoverState.value.busy) return
        val requestId = profileGeneration.value
        _searchUiState.update { it.copy(actionBusy = true, actionError = null, error = null) }
        scope.launch {
            try {
                sourceCall?.drained?.await()
                archiveCall?.drained?.await()
                if (!presentationVisible || !profileGeneration.isCurrent(requestId)) return@launch
                val local = project(listingId)
                if (profileGeneration.isCurrent(requestId)) {
                    _searchUiState.update { it.withProjection(local) }
                }
                projections.discovery()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (profileGeneration.isCurrent(requestId)) {
                    _searchUiState.update { it.copy(actionError = "Ansicht konnte nicht aktualisiert werden.") }
                }
            } finally {
                if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(actionBusy = false) }
            }
        }
    }

    fun confirmIdentity(token: ProfileIdentityToken, targetPersonId: Long) {
        val active = _searchUiState.value
        if (_discoverState.value.busy || active.actionBusy || active.loading ||
            token.ownerId == targetPersonId || token.candidates.none { it.personId == targetPersonId }) return
        val detailRequest = when (token) {
            is DetailIdentityToken -> {
                if (active.currentIdentityConfirmationOwner(token, targetPersonId, false) == null) return
                token.sessionId
            }
            is SwipeActionToken -> {
                if (active.profileOpen || !projections.admitSwipe(token)) return
                null
            }
        }
        val sourceOwner = token.ownerId
        val listingId = token.listingId
        fun detailCurrent() = detailRequest != null && profileGeneration.isCurrent(detailRequest)
        fun report(message: String) {
            if (detailCurrent()) _searchUiState.update { it.copy(actionError = message) }
            else _discoverState.update { it.copy(mutationError = message) }
        }
        val dirtyOwners = _profileNotes.value.dirtyOwners(sourceOwner, targetPersonId)
        if (dirtyOwners.isNotEmpty()) {
            report("Ungespeicherte Notiz für Person(en) ${dirtyOwners.joinToString()}. Vor Zusammenführung speichern oder verwerfen.")
            return
        }
        projections.beginMerge()
        val epoch = projections.nextEpoch()
        val pendingSource = sourceCall
        val pendingArchive = archiveCall
        pendingSource?.cancel(io.cancelSource)
        pendingArchive?.cancel(io.cancelArchive)
        // The mutation outlives the detail screen. Keep the shared write guard until
        // it finishes even if Back clears the screen's own actionBusy flag.
        _discoverState.update { it.copy(busy = true, loading = false, loadingMore = false,
            savedLoadingMore = false, savedRecentLoadingMore = false, mutationError = null) }
        if (detailCurrent()) _searchUiState.update { it.copy(actionBusy = true, actionError = null) }
        scope.launch {
            var committed = false
            var projectionUpdated = false
            try {
                pendingSource?.drained?.await()
                pendingArchive?.drained?.await()
                coroutineContext.ensureActive()
                // Once sent, the native write and its commit bookkeeping must drain together.
                withContext(NonCancellable) {
                    io.confirmIdentityMatch(listingId, sourceOwner, token.snapshotId, targetPersonId)
                    committed = true
                    for (owner in listOf(sourceOwner, targetPersonId)) {
                        noteReadTickets.invalidate(owner)
                    }
                    _profileNotes.update { it - sourceOwner - targetPersonId }
                    projections.mergeCommitted(token, targetPersonId)
                    if (detailCurrent()) {
                        _searchUiState.update { it.invalidateMergedProfile(listingId) }
                    }
                }
                coroutineContext.ensureActive()
                // A failed detail read must not suppress the independent feed projection.
                var detailFailed = false
                if (token is DetailIdentityToken) {
                    try {
                        val local = project(listingId)
                        if (detailCurrent()) {
                            _searchUiState.update {
                                if (it.retryProfileListingId == listingId) it.withProjection(local) else it
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        detailFailed = true
                    }
                }
                projectionUpdated = projections.projectDiscovery(epoch)
                if (detailFailed || !projectionUpdated) {
                    report("Zusammenführung gespeichert; Ansicht konnte nicht vollständig aktualisiert werden.")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val message = if (committed) "Zusammenführung gespeichert; Ansicht konnte nicht vollständig aktualisiert werden."
                    else failure.message ?: "Zusammenführung fehlgeschlagen"
                report(message)
            } finally {
                if (detailCurrent()) _searchUiState.update { it.copy(actionBusy = false) }
                if (epoch == projections.epoch()) {
                    _discoverState.update { it.copy(busy = false) }
                    if (committed && !projectionUpdated || projections.pending()) projections.discovery()
                }
                if (committed) {
                    projections.savedSearch()
                    if (projections.pending()) projections.discovery()
                    projections.map()
                    projections.home()
                }
            }
        }
    }
}
