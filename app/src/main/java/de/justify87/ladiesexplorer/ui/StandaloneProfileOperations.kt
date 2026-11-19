package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Existing standalone IO calls, bound to CoreStore in MainViewModel. No transport or state. */
internal class StandaloneProfileIo<C : Any>(
    val loadProfile: suspend (Long) -> ProfileResponse,
    val cachedProfile: suspend (Long) -> ProfileResponse?,
    val profileProjection: suspend (Long) -> NativeProfileProjection?,
    val archiveProfileMedia: suspend (Long, C) -> MediaArchiveReport,
    val newArchiveControl: () -> C,
    val cancelArchive: (C) -> Unit,
    val confirmIdentityMatch: suspend (Long, Long, Long) -> Unit,
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
)

/**
 * Extracted standalone operation bodies, not the future unified profile controller.
 * The ViewModel still owns the same StateFlows and coroutine lifetime. Generation and
 * archive control are moved here, not duplicated. Known baseline gaps remain for T10.2.
 */
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
    private var archiveControl: C? = null

    private suspend fun project(listingId: Long): NativeProfileProjection =
        checkNotNull(io.profileProjection(listingId)) { "Profil nicht mehr verfügbar" }

    private fun SearchUiState.withProjection(local: NativeProfileProjection) = copy(
        profile = local.profile, profileIdentity = local.identity,
        selectedPersonId = local.identity.personId, selectedPerson = local.person,
        profileMedia = local.media, changeEvents = local.history, identityCandidates = local.candidates,
    )

    fun invalidateProfileRequest() {
        profileGeneration.invalidate()
        archiveControl?.let(io.cancelArchive)
        archiveControl = null
    }

    fun cancelMediaArchive() {
        archiveControl?.let(io.cancelArchive)
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

    fun loadProfile(listingId: Long, personId: Long, beforeOpen: () -> Unit) {
        invalidateProfileRequest()
        beforeOpen()
        val requestId = profileGeneration.value
        _searchUiState.update {
            it.copy(profileOpen=true,profileSessionId=requestId,selectedPersonId=personId,selectedPerson=null,
                loading=true,profile=null,profileIdentity=null,profileFromArchive=false,profileMedia=emptyList(),changeEvents=emptyList(),
                identityCandidates=emptyList(),error=null,actionBusy=false,actionError=null,
                mediaArchive=null,mediaWarning=null,archiving=false)
        }
        scope.launch {
            try {
                var showingArchive = false
                var refreshError: String? = null
                try {
                    try {
                        io.loadProfile(listingId)
                    } finally {
                        // A source read can persist before a later failure or invalidation.
                        projections.savedSearch()
                        projections.discovery()
                        projections.map()
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    io.cachedProfile(listingId) ?: throw failure
                    showingArchive = true
                    refreshError = "Archivierte Profildaten: Online-Aktualisierung fehlgeschlagen (${failure.message ?: "Netzwerk nicht verfügbar"})."
                }
                if (!profileGeneration.isCurrent(requestId)) return@launch
                val local = project(listingId)
                if (!profileGeneration.isCurrent(requestId)) return@launch
                _searchUiState.update {
                    it.withProjection(local).copy(loading=false,profileFromArchive=showingArchive,
                        mediaWarning=refreshError,archiving=!showingArchive)
                }
                if (showingArchive) return@launch
                val profile = local.profile
                val control = io.newArchiveControl()
                archiveControl = control
                try {
                    val report = io.archiveProfileMedia(profile.snapshotId,control)
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
                    if (archiveControl === control) archiveControl = null
                    if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(archiving=false) }
                    // Even a cancelled archive may already have committed individual media.
                    projections.savedSearch()
                    projections.discovery()
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

    fun confirmIdentity(token: DetailIdentityToken, targetPersonId: Long) {
        val active = _searchUiState.value
        val sourceOwner = active.currentIdentityConfirmationOwner(token, targetPersonId, _discoverState.value.busy) ?: return
        val listingId = token.listingId
        val dirtyOwners = _profileNotes.value.dirtyOwners(sourceOwner, targetPersonId)
        if (dirtyOwners.isNotEmpty()) {
            _searchUiState.update { it.copy(actionError =
                "Ungespeicherte Notiz für Person(en) ${dirtyOwners.joinToString()}. Vor Zusammenführung speichern oder verwerfen.") }
            return
        }
        val requestId = profileGeneration.value
        val epoch = projections.nextEpoch()
        archiveControl?.let(io.cancelArchive)
        // The mutation outlives the detail screen. Keep the shared write guard until
        // it finishes even if Back clears the screen's own actionBusy flag.
        _discoverState.update { it.copy(busy = true, loading = false, loadingMore = false,
            savedLoadingMore = false, savedRecentLoadingMore = false, mutationError = null) }
        _searchUiState.update { it.copy(actionBusy = true, actionError = null) }
        scope.launch {
            var committed = false
            var profileUpdated = false
            var projectionUpdated = false
            try {
                io.confirmIdentityMatch(listingId, sourceOwner, targetPersonId)
                committed = true
                for (owner in listOf(sourceOwner, targetPersonId)) {
                    noteReadTickets.invalidate(owner)
                }
                _profileNotes.update { it - sourceOwner - targetPersonId }
                val local = project(listingId)
                if (profileGeneration.isCurrent(requestId)) {
                    _searchUiState.update {
                        if (it.profile?.listingId == listingId) it.withProjection(local) else it
                    }
                }
                profileUpdated = true
                projectionUpdated = projections.projectDiscovery(epoch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val message = if (committed) "Zusammenführung gespeichert; Ansicht konnte nicht vollständig aktualisiert werden."
                    else failure.message ?: "Zusammenführung fehlgeschlagen"
                if (profileGeneration.isCurrent(requestId)) {
                    _searchUiState.update {
                        it.identityConfirmationFailure(message, committed, profileUpdated)
                    }
                } else {
                    _discoverState.update { it.copy(mutationError = message) }
                }
            } finally {
                if (profileGeneration.isCurrent(requestId)) _searchUiState.update { it.copy(actionBusy = false) }
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
