package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.DiscoveryCursor
import de.justify87.ladiesexplorer.core.DiscoveryPage
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.SavedPeopleWindow
import de.justify87.ladiesexplorer.core.MapOverview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException

/** Rust owns membership and filtering. The transient hide is only grid presentation. */
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

/** Rebuild the loaded window from Rust's current ordered cursor, not a stale first page.
 * At least one page is read, even for an initially empty window. */
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

/** A write is never retried by recovery. A failed conditional write can also mean stale
 * membership, so the view is reconciled even on failure. */
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


/** Independent local projections: a failed read cannot suppress a successful sibling. */
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

/** Existing independent StateFlow publication; the ViewModel retains epoch and read ownership. */
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
        _discoverState.update {
            val next = swipeSession.queue(projection.page.cards, active = projection.runId != null)
            val mergeNotice = it.pendingMergeListingId?.let { listing ->
                if (next.firstOrNull()?.listingId == listing) "Personen zusammengeführt."
                else "Personen zusammengeführt. Die bisherige Anzeige ist nicht mehr die nächste zulässige Karte."
            } ?: it.mergeNotice
            it.copy(
                cards = next, pendingMergeListingId = null, mergeNotice = mergeNotice,
                replayLikes = swipeSession.replayLikes,
                canUndoSwipe = swipeSession.last != null,
                swipeRestore = swipeSession.restoredPresentation(next.firstOrNull()),
                activeRunId = projection.runId,
                activeVisibleCount = projection.visible.size,
                nextCursor = projection.page.nextCursor,
                loadingMore = false, error = null,
            )
        }
    }

    // The reads share an epoch but not validity or failure. Mutation ownership
    // is released only by its caller, never by publication of a partial view.
    suspend fun project(epoch: Long): Boolean = readIndependentProjections(
        first = discoveryPage,
        second = savedPage,
        publishFirst = { result ->
            if (epoch == currentEpoch()) result.fold(
                onSuccess = { publishDiscovery(epoch, it) },
                onFailure = { failure ->
                    _searchUiState.update { it.copy(activeCards = emptyList()) }
                    _discoverState.update { it.copy(cards = emptyList(), nextCursor = null,
                        activeVisibleCount = 0, error = failure.message ?: "Entdecken fehlgeschlagen") }
                },
            )
        },
        publishSecond = { result ->
            if (epoch == currentEpoch()) result.fold(
                onSuccess = { page -> _discoverState.update { it.copy(savedPage = page.regular,
                    savedRecent = page.recent, savedError = null) } },
                onFailure = { failure -> _discoverState.update { it.copy(savedPage = null, savedRecent = null,
                    savedError = failure.message ?: "Gespeicherte Personen konnten nicht geladen werden") } },
            )
        },
    )
}

/** Existing map read and epoch guard shared by refresh and resolver completion. */
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
