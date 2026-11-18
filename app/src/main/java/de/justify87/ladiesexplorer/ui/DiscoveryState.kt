package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.DiscoveryCursor
import de.justify87.ladiesexplorer.core.DiscoveryPage
import de.justify87.ladiesexplorer.core.Preference
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

internal fun nearGridEnd(lastVisible: Int, totalItems: Int): Boolean =
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
    data class ProjectionFailed(val feedFailed: Boolean) : DiscoveryMutationOutcome
}

/** A write is never retried by recovery. A failed conditional write can also mean stale
 * membership, so the view is reconciled even on failure. */
internal suspend fun writeAndProject(
    write: suspend () -> Unit,
    project: suspend () -> Unit,
    afterWrite: suspend () -> Unit = {},
    onWriteFailure: () -> Unit = {},
): DiscoveryMutationOutcome {
    val writeFailure = failureOf(write)
    if (writeFailure != null) {
        onWriteFailure()
        return DiscoveryMutationOutcome.WriteFailed(writeFailure, failureOf(project))
    }
    val detailFailure = failureOf(afterWrite)
    val feedFailure = failureOf(project)
    return if (detailFailure == null && feedFailure == null) DiscoveryMutationOutcome.Success
    else DiscoveryMutationOutcome.ProjectionFailed(feedFailed = feedFailure != null)
}

private suspend fun failureOf(action: suspend () -> Unit): Exception? = try {
    action()
    null
} catch (cancel: CancellationException) {
    throw cancel
} catch (failure: Exception) {
    failure
}
