package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.DiscoveryCursor
import de.justify87.ladiesexplorer.core.DiscoveryPage
import de.justify87.ladiesexplorer.core.GlobalResultVisibility
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.SavedPeopleWindow
import de.justify87.ladiesexplorer.core.MapOverview
import de.justify87.ladiesexplorer.core.MapProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException

enum class ProfileActionSurface { NONE, GRID, SAVED, SEARCH, SAVED_RESULTS, MAP, SETTINGS }

internal data class SettingsReplayAction(val generation: Long, val deck: SwipeDeckToken, val expected: Boolean)

internal fun DiscoverUiState.settingsReplayAction() =
    SettingsReplayAction(actionGeneration, deckToken(), replayLikes)

internal fun settingsReplayMatches(
    discovery: DiscoverUiState, search: SearchUiState, action: SettingsReplayAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.SETTINGS &&
        discovery.actionGeneration == action.generation &&
        discovery.replayLikes == action.expected &&
        !search.profileOpen && !search.actionBusy &&
        currentSwipeDeck(discovery, search.loading, action.deck)

internal data class SettingsVisibilityAction(
    val generation: Long,
    val deck: SwipeDeckToken,
    val expected: GlobalResultVisibility,
)

internal fun DiscoverUiState.settingsVisibilityAction() =
    SettingsVisibilityAction(actionGeneration, deckToken(), globalVisibility)

internal fun settingsVisibilityMatches(
    discovery: DiscoverUiState, search: SearchUiState, action: SettingsVisibilityAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.SETTINGS &&
        discovery.settingsVisibilityAction() == action &&
        !search.profileOpen && !search.actionBusy &&
        currentSwipeDeck(discovery, search.loading, action.deck)

internal data class MapControlAction(val rendered: MapUiState, val generation: Long)

data class MapMembership(val runId: Long?, val cards: List<DiscoveryCard>) {
    val listingIds get() = cards.map { it.listingId }

    internal fun matches(overview: MapOverview): Boolean {
        val members = cards.associateBy { it.listingId }
        return overview.totalProfiles == cards.size.toUInt() &&
            overview.profiles.size + overview.unlocatedProfiles.toInt() == cards.size &&
            overview.profiles.distinctBy { it.listingId }.size == overview.profiles.size &&
            overview.profiles.all { profile ->
                members[profile.listingId]?.let { it.personId == profile.personId && it.favorite == profile.favorite } == true
            }
    }
}

internal fun currentMapMembership(discovery: DiscoverUiState, search: SearchUiState): MapMembership? =
    if (!search.activeProjectionPending && discovery.activeRunId == search.response?.searchRunId)
        MapMembership(discovery.activeRunId, discoveryMembership(discovery, search))
    else null

internal fun MapUiState.needsLocationPreparation(): Boolean =
    !loading && !resolving && (overview == null || overview.pendingLocations > 0u || error != null)

internal fun mapCancelMatches(
    map: MapUiState, discovery: DiscoverUiState, search: SearchUiState,
    action: MapControlAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.MAP &&
        discovery.actionGeneration == action.generation && !search.profileOpen &&
        map == action.rendered && map.resolving && !map.cancelling

internal data class MapProfileOpenAction(
    val overview: MapOverview,
    val profile: MapProfile,
    val generation: Long,
)

internal fun mapProfileOpenMatches(
    map: MapUiState, discovery: DiscoverUiState, search: SearchUiState, action: MapProfileOpenAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.MAP &&
        discovery.actionGeneration == action.generation &&
        !discovery.busy && !discovery.loading && !map.loading &&
        !search.loading && !search.actionBusy && !search.profileOpen &&
        map.membership != null && map.membership == currentMapMembership(discovery, search) &&
        map.overview === action.overview &&
        action.overview.profiles.any { it == action.profile } &&
        map.membership.cards.any {
            it.listingId == action.profile.listingId && it.personId == action.profile.personId &&
                it.favorite == action.profile.favorite
        }

/** Never reinterpret a stale click using a freshly projected card. */
internal data class GridLikeAction(
    val card: DiscoveryCard,
    val runId: Long?,
    val generation: Long,
)

internal fun gridActionMatches(discovery: DiscoverUiState, search: SearchUiState, action: GridLikeAction): Boolean =
    discovery.actionSurface == ProfileActionSurface.GRID &&
        discovery.actionGeneration == action.generation &&
        !discovery.busy && !discovery.loading && !search.loading && !search.actionBusy && !search.profileOpen &&
        discovery.activeRunId == action.runId && search.response?.searchRunId == action.runId &&
        gridCards(discovery, search).firstOrNull { it.listingId == action.card.listingId } == action.card

internal data class GridRefreshAction(val runId: Long?, val generation: Long)

internal fun gridRefreshMatches(discovery: DiscoverUiState, search: SearchUiState, action: GridRefreshAction): Boolean =
    discovery.actionSurface == ProfileActionSurface.GRID &&
        discovery.actionGeneration == action.generation &&
        search.response?.searchRunId == action.runId &&
        !discovery.busy && !discovery.loading && !search.loading && !search.actionBusy && !search.profileOpen

internal data class GridPageAction(val cursor: DiscoveryCursor, val generation: Long)

internal fun localDiscoveryPageMatches(discovery: DiscoverUiState, search: SearchUiState, cursor: DiscoveryCursor): Boolean =
    discovery.activeRunId == null && search.response == null && discovery.nextCursor == cursor &&
        !discovery.loading && !discovery.loadingMore && !discovery.busy &&
        !search.loading && !search.actionBusy && !search.profileOpen

internal fun gridPageMatches(discovery: DiscoverUiState, search: SearchUiState, action: GridPageAction): Boolean =
    discovery.actionSurface == ProfileActionSurface.GRID &&
        discovery.actionGeneration == action.generation &&
        localDiscoveryPageMatches(discovery, search, action.cursor)

/** Traversal never owns result membership. */
internal fun discoveryMembership(discovery: DiscoverUiState, search: SearchUiState): List<DiscoveryCard> =
    if (search.activeProjectionPending) emptyList()
    else if (search.response != null) search.activeCards else discovery.localCards

internal fun gridCards(discovery: DiscoverUiState, search: SearchUiState): List<DiscoveryCard> =
    discoveryMembership(discovery, search)

internal enum class DiscoveryEmptyState { Loading, Unknown, Empty }

// Only interpret this state when the rendered membership or traversal has no cards.
internal fun discoveryEmptyState(discovery: DiscoverUiState, search: SearchUiState): DiscoveryEmptyState = when {
    discovery.loading || discovery.loadingMore || discovery.busy ||
        search.loading || search.activeProjectionPending -> DiscoveryEmptyState.Loading
    discovery.error != null || search.activeProjectionError != null -> DiscoveryEmptyState.Unknown
    else -> DiscoveryEmptyState.Empty
}

/** A local preference receipt, not a deck visit. */
data class GridUndoReceipt(
    val runId: Long?,
    val listingId: Long,
    val ownerId: Long,
    val before: Preference,
    val after: Preference,
) {
    internal fun step() = SwipeStep(listingId, ownerId, before, after)
}

internal data class GridUndoAction(val receipt: GridUndoReceipt, val generation: Long)

internal fun gridUndoMatches(discovery: DiscoverUiState, search: SearchUiState, action: GridUndoAction): Boolean =
    discovery.actionSurface == ProfileActionSurface.GRID &&
        discovery.actionGeneration == action.generation && discovery.gridUndo == action.receipt &&
        discovery.activeRunId == action.receipt.runId && search.response?.searchRunId == action.receipt.runId &&
        !discovery.busy && !discovery.loading && !search.loading && !search.actionBusy && !search.profileOpen

internal fun gridAnchorKey(key: Any): String = if (key is Long) "listing/$key" else key.toString()

internal fun gridRestorePosition(keys: List<String>, anchor: String?, index: Int, offset: Int): Pair<Int, Int> {
    val match = anchor?.let(keys::indexOf) ?: -1
    return if (match >= 0) match to offset.coerceAtLeast(0)
    else index.coerceIn(0, (keys.size - 1).coerceAtLeast(0)) to 0
}

internal fun nearDiscoveryEnd(lastVisible: Int, totalItems: Int): Boolean =
    lastVisible >= 0 && totalItems > 0 && lastVisible >= (totalItems - 3).coerceAtLeast(0)

/** Always rebuild from the current cursor, including initially empty windows. */
internal suspend fun readDiscoveryWindow(
    minimum: Int,
    read: suspend (DiscoveryCursor?) -> DiscoveryPage,
): DiscoveryPage {
    val cards = mutableListOf<DiscoveryCard>()
    val seenPeople = mutableSetOf<Long>()
    val seenCursors = mutableSetOf<DiscoveryCursor>()
    var cursor: DiscoveryCursor? = null
    do {
        val page = read(cursor)
        cards += page.cards.filter { seenPeople.add(it.personId) }
        cursor = page.nextCursor
        check(cursor == null || seenCursors.add(cursor)) { "Discovery cursor repeated" }
    } while (cursor != null && cards.size < minimum)
    return DiscoveryPage(cards, cursor)
}

internal sealed interface DiscoveryMutationOutcome {
    data object Success : DiscoveryMutationOutcome
    data class WriteFailed(val failure: Exception, val projectionFailure: Exception?) : DiscoveryMutationOutcome
    data class ProjectionFailed(val feedFailed: Boolean, val detailFailed: Boolean = !feedFailed) : DiscoveryMutationOutcome
}

/** Reconcile even on conditional-write failure; never replay the write. */
internal suspend fun writeAndProject(
    write: suspend () -> Unit,
    project: suspend () -> Unit,
    afterWrite: suspend () -> Unit = {},
    onWriteFailure: () -> Unit = {},
    recoverWriteFailure: suspend () -> Unit = {},
): DiscoveryMutationOutcome {
    val writeFailure = failureOf(write)
    if (writeFailure != null) {
        onWriteFailure()
        val recoveryFailure = failureOf(recoverWriteFailure)
        val feedFailure = failureOf(project)
        return DiscoveryMutationOutcome.WriteFailed(writeFailure, feedFailure ?: recoveryFailure)
    }
    val detailFailure = failureOf(afterWrite)
    val feedFailure = failureOf(project)
    return if (detailFailure == null && feedFailure == null) DiscoveryMutationOutcome.Success
    else DiscoveryMutationOutcome.ProjectionFailed(feedFailed = feedFailure != null, detailFailed = detailFailure != null)
}

private suspend fun failureOf(action: suspend () -> Unit): Exception? = try {
    action()
    null
} catch (cancel: CancellationException) {
    throw cancel
} catch (failure: Exception) {
    failure
}


/** One failed projection must not suppress a successful sibling. */
internal suspend fun <A, B> readIndependentProjections(
    first: suspend () -> A,
    second: suspend () -> B,
    publishFirst: (Result<A>) -> Unit,
    publishSecond: (Result<B>) -> Unit,
): Boolean {
    val a = projectionResult(first)
    publishFirst(a)
    val b = projectionResult(second)
    publishSecond(b)
    return a.isSuccess && b.isSuccess
}

private suspend fun <T> projectionResult(read: suspend () -> T): Result<T> = try {
    Result.success(read())
} catch (cancel: CancellationException) {
    throw cancel
} catch (failure: Exception) {
    Result.failure(failure)
}

internal data class DiscoveryProjection(
    val runId: Long?,
    val visible: List<DiscoveryCard>,
    val page: DiscoveryPage,
)

internal class DiscoveryProjectionPublisher(
    private val _searchUiState: MutableStateFlow<SearchUiState>,
    private val _discoverState: MutableStateFlow<DiscoverUiState>,
    private val swipeSession: SwipeSession,
    private val currentEpoch: () -> Long,
    private val onLocalWindow: (Int) -> Unit,
    private val discoveryPage: suspend () -> DiscoveryProjection,
    private val savedPage: suspend () -> SavedPeopleWindow,
) {
    private fun publishDiscovery(epoch: Long, projection: DiscoveryProjection, background: Boolean) {
        if (epoch != currentEpoch() || projection.runId != _searchUiState.value.response?.searchRunId) return
        if (projection.runId == null) onLocalWindow(projection.page.cards.size)
        _searchUiState.update { it.copy(activeCards = projection.visible,
            activeProjectionPending = false, activeProjectionError = null) }
        val next = swipeSession.queue(projection.page.cards, active = projection.runId != null)
        // Validate or discard the receipt after a successful read, outside the retryable StateFlow update.
        val restore = swipeSession.restoredPresentation(next.firstOrNull())
        _discoverState.update {
            val mergeNotice = it.pendingMergeListingId?.let { listing ->
                if (next.firstOrNull()?.listingId == listing) "Personen zusammengeführt."
                else "Personen zusammengeführt. Die bisherige Anzeige ist nicht mehr die nächste zulässige Karte."
            } ?: it.mergeNotice
            it.copy(
                cards = next, localCards = if (projection.runId == null) projection.page.cards else emptyList(),
                swipeSessionId = it.swipeSessionId + if (background &&
                    it.cards.map { card -> card.listingId to card.personId } != next.map { card -> card.listingId to card.personId }) 1 else 0,
                pendingMergeListingId = null, mergeNotice = mergeNotice,
                replayLikes = swipeSession.replayLikes,
                canUndoSwipe = swipeSession.last != null,
                swipeRestore = restore,
                activeRunId = projection.runId,
                activeVisibleCount = projection.visible.size,
                nextCursor = projection.page.nextCursor,
                loadingMore = false, error = null, actionGeneration = it.actionGeneration + 1,
            )
        }
    }

    // Partial publication must not release mutation ownership.
    suspend fun project(epoch: Long, background: Boolean = false): Boolean = readIndependentProjections(
        first = discoveryPage,
        second = savedPage,
        publishFirst = { result ->
            if (epoch == currentEpoch()) result.fold(
                onSuccess = { publishDiscovery(epoch, it, background) },
                onFailure = { failure ->
                    _searchUiState.update { it.copy(activeCards = emptyList(), activeProjectionPending = false,
                        activeProjectionError = if (it.response != null) failure.message ?: "Lokale Ergebnisse konnten nicht geladen werden." else null) }
                    _discoverState.update { it.copy(cards = emptyList(), localCards = emptyList(), nextCursor = null,
                        activeVisibleCount = 0, actionGeneration = it.actionGeneration + 1,
                        error = failure.message ?: "Entdecken fehlgeschlagen") }
                },
            )
        },
        publishSecond = { result ->
            if (epoch == currentEpoch()) result.fold(
                onSuccess = { page -> _discoverState.update { it.copy(savedPage = page.regular,
                    savedRecent = page.recent, savedError = null, actionGeneration = it.actionGeneration + 1) } },
                onFailure = { failure -> _discoverState.update { it.copy(savedPage = null, savedRecent = null,
                    savedError = failure.message ?: "Gespeicherte Personen konnten nicht geladen werden",
                    actionGeneration = it.actionGeneration + 1) } },
            )
        },
    )
}

internal suspend fun projectMapOverview(
    state: MutableStateFlow<MapUiState>,
    current: () -> Boolean,
    read: suspend () -> MapOverview,
    accept: (MapUiState, MapOverview) -> MapUiState,
) {
    try {
        val overview = read()
        if (current()) state.update { accept(it, overview) }
    } catch (cancelled: CancellationException) {
        if (current()) state.update { it.copy(loading = false) }
        throw cancelled
    } catch (failure: Exception) {
        if (current()) state.update {
            it.copy(overview = null, loading = false, error = failure.message ?: "Karte konnte nicht geladen werden")
        }
    }
}
