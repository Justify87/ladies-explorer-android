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

/** Detail generation fences delivery; swipe merges must not open detail routes. */
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
    private val projectionGeneration = RequestGeneration()
    private class MissingLocalProfile : IllegalStateException("Kein gespeichertes Profil vorhanden")
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
    private var requestedPersonId: Long = 0L
    private var localProfileKnown = false

    private fun currentDetail(session: Long, listingId: Long): Boolean =
        presentationVisible && profileGeneration.isCurrent(session) &&
            _searchUiState.value.profileOpen && requestedListingId == listingId

    /** Superseded detail successes and failures have no UI effect. */
    private suspend fun readDetail(
        session: Long,
        listingId: Long,
        allowMissing: Boolean = false,
        publish: SearchUiState.(NativeProfileProjection, Long) -> SearchUiState,
    ): NativeProfileProjection? {
        if (!currentDetail(session, listingId)) return null
        projectionGeneration.invalidate()
        val ticket = projectionGeneration.value
        fun admitted() = currentDetail(session, listingId) && projectionGeneration.isCurrent(ticket)
        try {
            val local = io.profileProjection(listingId) ?: throw MissingLocalProfile()
            check(local.profile.listingId == listingId && local.identity.personId == local.person.personId) {
                "Inkonsistente Profilprojektion"
            }
            check(local.identity.personId == requestedPersonId) {
                "Profilzuordnung seit Öffnen geändert. Bitte das aktuelle Profil erneut öffnen."
            }
            coroutineContext.ensureActive()
            if (!admitted()) return null
            localProfileKnown = true
            _searchUiState.update { it.publish(local, ticket) }
            return local
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (!admitted()) return null
            _searchUiState.update {
                it.invalidateMergedProfile(listingId).copy(
                    profileSourceRetryAllowed = allowMissing && !localProfileKnown && failure is MissingLocalProfile,
                )
            }
            throw failure
        }
    }

    private fun revokeDetail(session: Long, listingId: Long) {
        if (!currentDetail(session, listingId)) return
        projectionGeneration.invalidate()
        _searchUiState.update { it.invalidateMergedProfile(listingId) }
    }

    private fun SearchUiState.withProjection(
        local: NativeProfileProjection,
        revision: Long,
        fromArchive: Boolean = profileFromArchive,
        warning: String? = profileRead?.warning ?: mediaWarning,
    ) = copy(
        profileRead = ProfileReadModel(local, fromArchive, warning, revision),
        retryProfileListingId = null,
        profileRetryFromArchive = false,
        profileSourceRetryAllowed = false,
    )

    fun invalidateProfileRequest() {
        profileGeneration.invalidate()
        projectionGeneration.invalidate()
        sourceCall?.cancel(io.cancelSource)
        archiveCall?.cancel(io.cancelArchive)
    }

    private fun admitsSession(session: Long): Boolean =
        presentationVisible && profileGeneration.isCurrent(session) &&
            _searchUiState.value.let { it.profileOpen && it.profileSessionId == session }

    fun cancelMediaArchive(session: Long) {
        if (!admitsSession(session) || !_searchUiState.value.archiving) return
        // Cancellation signals the call; it owns the slot until drain.
        archiveCall?.cancel(io.cancelArchive)
    }

    fun closeProfile(session: Long) {
        if (!admitsSession(session)) return
        requestedListingId = null
        invalidateProfileRequest()
        _searchUiState.update {
            it.copy(profileOpen = false, profileRead = null, profileMatchContext = null, profileRetryFromArchive = false, profileSourceRetryAllowed = false,
                loading = false, archiving = false, actionBusy = false, actionError = null,
                error = null, retryProfileListingId = null, mediaArchive = null, mediaWarning = null)
        }
    }

    fun loadProfile(listingId: Long, personId: Long, matchContext: ProfileMatchContext? = null, beforeOpen: () -> Unit) {
        if (!presentationVisible) return
        invalidateProfileRequest()
        requestedListingId = listingId
        requestedPersonId = personId
        localProfileKnown = false
        beforeOpen()
        val requestId = profileGeneration.value
        _searchUiState.update {
            it.copy(profileOpen=true,profileSessionId=requestId,loading=true,
                profileRead=null,profileMatchContext=matchContext,profileRetryFromArchive=false,profileSourceRetryAllowed=false,error=null,actionBusy=false,actionError=null,
                mediaArchive=null,mediaWarning=null,archiving=false,retryProfileListingId=null)
        }
        startProfileRead(requestId, listingId)
    }

    fun refreshProfile(token: DetailIdentityToken) {
        val active = _searchUiState.value
        if (!admitsSession(token.sessionId) || active.loading || active.actionBusy || active.archiving ||
            _discoverState.value.busy || active.detailIdentityToken() != token) return
        _searchUiState.update { it.copy(loading = true, error = null, actionError = null,
            retryProfileListingId = null, profileSourceRetryAllowed = false, mediaArchive = null, mediaWarning = null) }
        startProfileRead(token.sessionId, token.listingId)
    }

    private fun startProfileRead(requestId: Long, listingId: Long) {
        scope.launch {
            var localReadReady = false
            var missingInitialProfile = false
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
                        withContext(NonCancellable) {
                            io.loadProfile(listingId, call.control)
                            if (requestedListingId == listingId) localProfileKnown = true
                        }
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
                    if (io.cachedProfile(listingId) == null) {
                        missingInitialProfile = true
                        throw failure
                    }
                    localProfileKnown = true
                    showingArchive = true
                    refreshError = "Archivierte Profildaten: Online-Aktualisierung fehlgeschlagen (${failure.message ?: "Netzwerk nicht verfügbar"})."
                }
                if (!profileGeneration.isCurrent(requestId)) return@launch
                localReadReady = true
                val local = readDetail(requestId, listingId) { value, revision ->
                    withProjection(value, revision, showingArchive, refreshError).copy(loading=false,
                        mediaWarning=refreshError,archiving=!showingArchive)
                } ?: return@launch
                if (showingArchive) return@launch
                val profile = local.profile
                val call = NativeProfileCall(io.newArchiveControl())
                check(archiveCall == null)
                archiveCall = call
                try {
                    // Archive commits incrementally; failure does not imply rollback.
                    var archiveWarning: String? = null
                    val report = try {
                        withContext(NonCancellable) {
                            io.archiveProfileMedia(profile.snapshotId, call.control)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        archiveWarning = failure.message ?: "Medienarchivierung fehlgeschlagen"
                        null
                    }
                    coroutineContext.ensureActive()
                    if (profileGeneration.isCurrent(requestId)) {
                        try {
                            readDetail(requestId, listingId) { updated, revision ->
                                withProjection(updated, revision)
                            }
                            if (currentDetail(requestId, listingId)) {
                                _searchUiState.update {
                                    it.copy(
                                        mediaArchive = report?.takeIf { _ -> it.profile?.snapshotId == profile.snapshotId },
                                        mediaWarning = archiveWarning,
                                    )
                                }
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            if (profileGeneration.isCurrent(requestId)) {
                                _searchUiState.update {
                                    // Revoke stale actions and retry the projection, not the archive.
                                    it.copy(
                                        mediaWarning = archiveWarning,
                                        error = "Profilansicht konnte nicht aktualisiert werden.",
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    if (archiveCall === call) archiveCall = null
                    if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(archiving=false) }
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
                            retryProfileListingId = listingId,
                            profileSourceRetryAllowed = missingInitialProfile && !localReadReady,
                            profileRetryFromArchive = showingArchive, mediaWarning = refreshError)
                    }
                }
            }
        }
    }

    /** ON_STOP revokes delivery, not native ownership; resume projects locally. */
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
                    profileRetryFromArchive = it.profileRead?.fromArchive ?: it.profileRetryFromArchive,
                    profileSourceRetryAllowed = false,
                    retryProfileListingId = listing, loading = false, archiving = false,
                    actionBusy = false, actionError = null, mediaArchive = null)
            }
        } else {
            val ticket = profileGeneration.value
            scope.launch {
                _discoverState.first { !it.busy }
                if (admitsSession(ticket)) recoverProfileProjection(explicitSourceRetry = false)
            }
        }
    }

    suspend fun refreshLocalProfileForOwner(personId: Long) {
        val active = _searchUiState.value
        val listingId = active.profile?.listingId ?: return
        if (!active.profileOpen || active.selectedPersonId != personId) return
        refreshLocalProfile(active.profileSessionId, listingId)
    }

    suspend fun refreshLocalProfile(session: Long, listingId: Long) {
        readDetail(session, listingId) { local, revision -> withProjection(local, revision) }
    }

    fun retryProfileProjection(action: DetailRetryAction) {
        if (!admitsSession(action.session) || _searchUiState.value.detailRetryAction() != action) return
        recoverProfileProjection(explicitSourceRetry = action.sourceAllowed)
    }

    /** Resume may recover locally, but never inherits a UI permission to fetch from source. */
    private fun recoverProfileProjection(explicitSourceRetry: Boolean) {
        val active = _searchUiState.value
        val listingId = active.retryProfileListingId ?: return
        if (!admitsSession(active.profileSessionId) || active.loading || active.actionBusy || _discoverState.value.busy) return
        val requestId = active.profileSessionId
        _searchUiState.update { it.copy(actionBusy = true, actionError = null, error = null,
            profileRetryAttempt = it.profileRetryAttempt + 1) }
        scope.launch {
            try {
                sourceCall?.drained?.await()
                archiveCall?.drained?.await()
                if (!presentationVisible || !profileGeneration.isCurrent(requestId)) return@launch
                try {
                    readDetail(requestId, listingId, allowMissing = true) { local, revision ->
                        withProjection(local, revision)
                    }
                } catch (missing: MissingLocalProfile) {
                    if (explicitSourceRetry && !localProfileKnown && currentDetail(requestId, listingId)) {
                        loadProfile(listingId, requestedPersonId) {}
                        return@launch
                    }
                    throw missing
                }
                projections.discovery()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (profileGeneration.isCurrent(requestId)) {
                    _searchUiState.update { it.copy(actionError = if (failure is MissingLocalProfile)
                        if (_searchUiState.value.profileSourceRetryAllowed)
                            "Kein lokales Profil vorhanden. Online-Abruf kann ausdrücklich wiederholt werden."
                        else "Gespeichertes Profil momentan nicht verfügbar."
                        else "Ansicht konnte nicht aktualisiert werden.") }
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
        // Keep the shared write guard across Back until the mutation completes.
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
                    if (detailRequest != null && _searchUiState.value.profileOpen &&
                        requestedListingId == listingId && requestedPersonId == sourceOwner) {
                        // ON_STOP revokes delivery, but the open listing survives the confirmed merge.
                        // Back clears the request; a new open is excluded by the shared write guard.
                        requestedPersonId = targetPersonId
                        revokeDetail(_searchUiState.value.profileSessionId, listingId)
                    }
                }
                coroutineContext.ensureActive()
                var detailFailed = false
                if (token is DetailIdentityToken) {
                    try {
                        refreshLocalProfile(token.sessionId, listingId)
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
                if (!committed) {
                    // Reconcile changed owner/evidence after rejection; never replay the write.
                    projections.profileChanged()
                    if (token is DetailIdentityToken && detailCurrent()) {
                        try {
                            refreshLocalProfile(token.sessionId, listingId)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // readDetail already revoked stale capabilities.
                        }
                    }
                    try {
                        projectionUpdated = projections.projectDiscovery(epoch)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // Projection owners handle read errors; continue reconciliation.
                    }
                    projections.savedSearch()
                    projections.map()
                }
                report(message)
            } finally {
                if (detailCurrent()) _searchUiState.update { it.copy(actionBusy = false) }
                if (epoch == projections.epoch()) {
                    _discoverState.update { it.copy(busy = false) }
                    if (!projectionUpdated || projections.pending()) projections.discovery()
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
