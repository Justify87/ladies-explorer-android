package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.SavedPersonKind
import de.justify87.ladiesexplorer.core.SavedPersonSort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Only the requested presentation capacity is remembered; Rust owns rows,
 * membership, time boundary, ordering, and both section totals. */
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

/** Serial status work with partial results. Cancellation never repeats completed writes. */
internal suspend fun runSavedStatusBatch(
    targets: List<Long>,
    cancelled: () -> Boolean,
    check: suspend (Long) -> Unit,
    publish: (total: Int, done: Int, failed: Int) -> Unit,
    pause: suspend () -> Unit = { delay(350) },
) {
    var done = 0
    var failed = 0
    publish(targets.size, done, failed)
    for ((index, target) in targets.withIndex()) {
        if (cancelled()) break
        try {
            check(target)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (cancelled()) break
            failed++
        }
        done++
        publish(targets.size, done, failed)
        if (index < targets.lastIndex && !cancelled()) pause()
    }
}
