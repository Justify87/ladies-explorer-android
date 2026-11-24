package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.DiscoveryCursor
import de.justify87.ladiesexplorer.core.DiscoveryPage
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.SavedPeopleWindow
import de.justify87.ladiesexplorer.core.MapOverview
import de.justify87.ladiesexplorer.core.MapProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException

enum class ProfileActionSurface { NONE, GRID, SAVED, SEARCH, SAVED_RESULTS, MAP }

internal data class MapProfileOpenAction(
    val overview: MapOverview,
    val profile: MapProfile,
    val favoritesOnly: Boolean,
    val generation: Long,
)

internal fun mapProfileOpenMatches(
    map: MapUiState, discovery: DiscoverUiState, action: MapProfileOpenAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.MAP &&
        discovery.actionGeneration == action.generation &&
        !discovery.busy && !map.loading &&
        map.favoritesOnly == action.favoritesOnly &&
        map.overview === action.overview &&
        action.overview.profiles.any { it == action.profile }

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
        search.response?.searchRunId == action.runId &&
        gridCards(discovery, search).firstOrNull { it.listingId == action.card.listingId } == action.card

internal data class GridPageAction(val cursor: DiscoveryCursor, val generation: Long)

internal fun localDiscoveryPageMatches(discovery: DiscoverUiState, search: SearchUiState, cursor: DiscoveryCursor): Boolean =
    discovery.activeRunId == null && search.response == null && discovery.nextCursor == cursor &&
        !discovery.loading && !discovery.loadingMore && !discovery.busy &&
        !search.loading && !search.actionBusy && !search.profileOpen

internal fun gridPageMatches(discovery: DiscoverUiState, search: SearchUiState, action: GridPageAction): Boolean =
    discovery.actionSurface == ProfileActionSurface.GRID &&
        discovery.actionGeneration == action.generation &&
        localDiscoveryPageMatches(discovery, search, action.cursor)

/** The transient hide affects presentation, not run membership. */
internal fun gridCards(discovery: DiscoverUiState, search: SearchUiState): List<DiscoveryCard> =
    if (search.response != null) search.activeCards.filterNot { it.listingId in search.gridHiddenLikes }
    else discovery.cards

internal fun gridLikeTarget(preference: Preference): Preference =
    if (preference == Preference.LIKE) Preference.NONE else Preference.LIKE

internal fun SearchUiState.hideGridLike(listingId: Long): SearchUiState =
    copy(gridHiddenLikes = gridHiddenLikes + listingId)

internal fun SearchUiState.restoreGridLike(listingId: Long): SearchUiState =
    copy(gridHiddenLikes = gridHiddenLikes - listingId)

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
    private fun publishDiscovery(epoch: Long, projection: DiscoveryProjection) {
        if (epoch != currentEpoch() || projection.runId != _searchUiState.value.response?.searchRunId) return
        if (projection.runId == null) onLocalWindow(projection.page.cards.size)
        _searchUiState.update { it.copy(activeCards = projection.visible) }
        val next = swipeSession.queue(projection.page.cards, active = projection.runId != null)
        // Validate or discard the receipt after a successful read, outside the retryable StateFlow update.
        val restore = swipeSession.restoredPresentation(next.firstOrNull())
        _discoverState.update {
            val mergeNotice = it.pendingMergeListingId?.let { listing ->
                if (next.firstOrNull()?.listingId == listing) "Personen zusammengeführt."
                else "Personen zusammengeführt. Die bisherige Anzeige ist nicht mehr die nächste zulässige Karte."
            } ?: it.mergeNotice
            it.copy(
                cards = next, pendingMergeListingId = null, mergeNotice = mergeNotice,
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
    suspend fun project(epoch: Long): Boolean = readIndependentProjections(
        first = discoveryPage,
        second = savedPage,
        publishFirst = { result ->
            if (epoch == currentEpoch()) result.fold(
                onSuccess = { publishDiscovery(epoch, it) },
                onFailure = { failure ->
                    _searchUiState.update { it.copy(activeCards = emptyList()) }
                    _discoverState.update { it.copy(cards = emptyList(), nextCursor = null,
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
