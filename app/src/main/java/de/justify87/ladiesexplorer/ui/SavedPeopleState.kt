package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.SavedPersonRow
import de.justify87.ladiesexplorer.core.SavedPersonPage
import de.justify87.ladiesexplorer.core.SavedPersonKind
import de.justify87.ladiesexplorer.core.SavedPersonSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Kotlin remembers capacity; Rust owns membership, order, time boundary and totals. */
internal class SavedWindowMemory {
    private val regular = mutableMapOf<Pair<SavedPersonKind, SavedPersonSort>, UInt>()
    private var recent = 50u

    fun limits(kind: SavedPersonKind, sort: SavedPersonSort): Pair<UInt, UInt> =
        (regular[kind to sort] ?: 50u) to recent

    fun requestMore(kind: SavedPersonKind, sort: SavedPersonSort, watchlist: Boolean, currentSize: Int) {
        val requested = currentSize.toUInt() + 50u
        if (watchlist) recent = maxOf(recent, requested)
        else regular[kind to sort] = maxOf(regular[kind to sort] ?: 50u, requested)
    }
}

/** A saved action belongs to the rendered tab, window, surface and row. */
internal data class SavedPersonAction(
    val row: SavedPersonRow,
    val kind: SavedPersonKind,
    val sort: SavedPersonSort,
    val generation: Long,
)

internal fun savedActionMatches(state: DiscoverUiState, action: SavedPersonAction): Boolean {
    if (state.actionSurface != ProfileActionSurface.SAVED ||
        state.actionGeneration != action.generation ||
        state.savedKind != action.kind || state.savedSort != action.sort ||
        state.loading || state.busy) return false
    val current = state.savedPage?.rows?.firstOrNull { it.personId == action.row.personId }
        ?: state.savedRecent?.rows?.firstOrNull { it.personId == action.row.personId }
    return current == action.row
}

/** A batch check starts only from the saved selection and window that rendered its button. */
internal data class SavedStatusBatchAction(
    val page: SavedPersonPage,
    val kind: SavedPersonKind,
    val sort: SavedPersonSort,
    val generation: Long,
)

internal fun savedStatusBatchMatches(state: DiscoverUiState, action: SavedStatusBatchAction): Boolean =
    state.actionSurface == ProfileActionSurface.SAVED &&
        state.actionGeneration == action.generation &&
        state.savedKind == action.kind && state.savedSort == action.sort &&
        state.savedPage === action.page && !state.loading && !state.busy

internal data class SavedPeoplePageAction(
    val page: SavedPersonPage,
    val kind: SavedPersonKind,
    val sort: SavedPersonSort,
    val generation: Long,
    val watchlist: Boolean,
)

internal fun savedPeoplePageMatches(
    state: DiscoverUiState, search: SearchUiState, action: SavedPeoplePageAction,
): Boolean =
    state.actionSurface == ProfileActionSurface.SAVED &&
        state.actionGeneration == action.generation &&
        state.savedKind == action.kind && state.savedSort == action.sort &&
        (if (action.watchlist) state.savedRecent else state.savedPage) === action.page &&
        action.page.nextCursor != null && !state.loading && !state.busy &&
        !state.savedLoadingMore && !state.savedRecentLoadingMore &&
        !search.profileOpen && !search.loading && !search.actionBusy

/** Cancellation must not replay completed status writes. */
internal suspend fun runSavedStatusBatch(
    targets: List<Long>,
    cancelled: () -> Boolean,
    check: suspend (Long) -> Unit,
    publish: (total: Int, done: Int, failed: Int, lastError: String?) -> Unit,
    pause: suspend () -> Unit = { delay(350) },
) {
    var done = 0
    var failed = 0
    var lastError: String? = null
    publish(targets.size, done, failed, lastError)
    for ((index, target) in targets.withIndex()) {
        if (cancelled()) break
        try {
            check(target)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (cancelled()) break
            failed++
            lastError = failure.message ?: "Status konnte nicht geprüft werden"
        }
        done++
        publish(targets.size, done, failed, lastError)
        if (index < targets.lastIndex && !cancelled()) pause()
    }
}
