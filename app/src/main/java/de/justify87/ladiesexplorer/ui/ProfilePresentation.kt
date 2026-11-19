package de.justify87.ladiesexplorer.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** No profile payloads or callback closures are retained through a projection gap. */
internal data class ProfileScrollAnchor(val key: String, val offset: Int)

/** Session-only presentation reference; no payload, native handle, or persisted preference. */
data class SwipePresentation(
    val listingId: Long, val ownerId: Long, val snapshotId: Long, val filters: String,
    val mediaId: String?, val anchorKey: String?, val anchorOffset: Int, val sections: List<String>,
    val runId: Long? = null,
    val mediaIndex: Int = 0,
) {
    internal fun matches(projection: ProfileProjectionKey?, run: Long?) = projection != null &&
        runId == run && listingId == projection.listingId && ownerId == projection.ownerId

    internal fun matches(token: SwipeActionToken) = runId == token.runId &&
        listingId == token.listingId && ownerId == token.ownerId

    // Stable keys survive a changed snapshot; pixel offsets do not necessarily do so.
    internal fun anchorFor(projection: ProfileProjectionKey) = anchorKey?.let {
        ProfileScrollAnchor(it, if (snapshotId == projection.snapshotId && filters == projection.filters)
            anchorOffset.coerceAtLeast(0) else 0)
    }

    internal fun matches(token: SwipeCardToken) = runId == token.deck.runId &&
        listingId == token.listingId && ownerId == token.ownerId
}

/** The flow owns actual presentation state; the deck captures it synchronously at admission. */
internal class SwipePresentationCapture {
    var read: () -> SwipePresentation? = { null }
}

/** Pending receipt is authoritative until the entire presentation has been applied.
 * A newer live capture must not overwrite it during a projection/layout gap. */
internal fun presentationForDecision(
    pending: SwipePresentation?, captured: SwipePresentation?, card: SwipeCardToken,
): SwipePresentation? = pending?.takeIf { it.matches(card) } ?: captured?.takeIf { it.matches(card) }

/** Coordinates existing presentation owners; no copied media, section or scroll state.
 * A composition replacement deliberately starts a pending receipt again against its own layout. */
internal class ProfileRestoreCoordinator {
    enum class Phase { Idle, Waiting, Restoring, Applied, Invalid }
    var phase by mutableStateOf(Phase.Idle)
        private set
    private var request: SwipePresentation? = null
    private var prepared: ProfileProjectionKey? = null

    fun synchronize(
        receipt: SwipePresentation?, projection: ProfileProjectionKey?, run: Long?,
        media: ProfileMediaSelection, sections: ProfileSections, scroll: ProfileScrollMemory,
    ) {
        if (request != receipt) {
            request = receipt
            prepared = null
            phase = if (receipt == null) Phase.Idle else Phase.Waiting
        }
        if (receipt == null || projection == null || phase == Phase.Applied || phase == Phase.Invalid) return
        if (!receipt.matches(projection, run)) {
            media.restore(0, null)
            sections.restore(emptyList())
            scroll.restore(null)
            phase = Phase.Invalid
            return
        }
        if (phase != Phase.Restoring || prepared?.snapshotId != projection.snapshotId ||
            prepared?.filters != projection.filters) {
            media.restore(receipt.mediaIndex, receipt.mediaId)
            sections.restore(receipt.sections)
            scroll.restore(receipt.anchorFor(projection))
        }
        prepared = projection
        phase = Phase.Restoring
    }

    fun canRestore(receipt: SwipePresentation?) =
        receipt == null || request == receipt && phase == Phase.Restoring

    fun complete(receipt: SwipePresentation?) {
        if (receipt != null && request == receipt && phase == Phase.Restoring) phase = Phase.Applied
    }

    fun terminal(receipt: SwipePresentation?) = receipt != null && request == receipt &&
        (phase == Phase.Applied || phase == Phase.Invalid)
}
internal data class ProfileProjectionKey(
    val listingId: Long, val ownerId: Long, val snapshotId: Long, val revision: Long, val filters: String,
)
internal class ProfileScrollMemory(
    initialAnchor: ProfileScrollAnchor? = null,
    initialRecovery: Boolean = false,
) {
    var anchor: ProfileScrollAnchor? = initialAnchor
        private set
    var recovering by mutableStateOf(initialRecovery)
        private set
    private var projection: ProfileProjectionKey? = null

    /** Also detects a direct revision replacement, where model never becomes null. */
    fun projectionChanged(next: ProfileProjectionKey?) {
        if (projection != next && (projection != null || next == null)) gap()
        projection = next
    }

    fun observe(key: String, offset: Int) {
        if (!recovering) anchor = ProfileScrollAnchor(key, offset.coerceAtLeast(0))
    }
    fun gap() { if (anchor != null) recovering = true }
    fun restore(value: ProfileScrollAnchor?) {
        anchor = value
        recovering = true
    }

    fun dependency(rows: List<ProfileFlowRows.Entry>): ProfileRowRead? =
        target(rows.map { it.key })?.let { (index, _) -> rows[index].read }

    fun target(keys: List<String>, readFailed: Boolean = false): Pair<Int, Int>? {
        if (!recovering || keys.isEmpty()) return null
        val saved = anchor ?: return 0 to 0
        val index = keys.indexOf(saved.key)
        if (index >= 0) return index to if (readFailed) 0 else saved.offset
        val fallback = when {
            saved.key.startsWith("listing/") -> "profile-listings-head"
            saved.key.startsWith("event/") -> "profile-history-head"
            saved.key.startsWith("candidate/") -> "profile-candidate-head"
            else -> "profile-facts"
        }
        return keys.indexOf(fallback).takeIf { it >= 0 }?.let { it to 0 } ?: (0 to 0)
    }
    fun restored() { recovering = false }
    fun returnToHero(key: String) {
        anchor = ProfileScrollAnchor(key, 0)
        recovering = false
    }
}

/** A replacement row may be shorter (new content, empty data, error). Its
 * previous offset must never skip into a different row. */
internal fun restoredRowOffset(offset: Int, measuredSize: Int) =
    offset.coerceIn(0, (measuredSize - 1).coerceAtLeast(0))

@Composable
internal fun rememberProfileScrollMemory(reset: Boolean): ProfileScrollMemory = rememberSaveable(
    saver = listSaver<ProfileScrollMemory, String>(
        save = { listOf(it.anchor?.key.orEmpty(), (it.anchor?.offset ?: 0).toString()) },
        restore = {
            val anchor = it[0].takeIf(String::isNotEmpty)?.let { key -> ProfileScrollAnchor(key, it[1].toInt()) }
            // The old LazyColumn indices and projection are not transferable to a new layout.
            ProfileScrollMemory(anchor, initialRecovery = anchor != null || reset)
        },
    ),
) { ProfileScrollMemory(initialRecovery = reset) }

/** The actual lazy row manifest is also the anchor resolver; there is no parallel index table. */
internal enum class ProfileRowRead { Facts, Detail, Note }

internal class ProfileFlowRows(val mediaSelection: ProfileMediaSelection) {
    data class Entry(val key: String, val read: ProfileRowRead?, val content: @Composable () -> Unit)
    private val rows = mutableListOf<Entry>()
    fun item(key: String, read: ProfileRowRead? = null, content: @Composable () -> Unit) {
        rows.add(Entry(key, read, content))
    }
    fun <T> items(values: List<T>, key: (T) -> String, read: ProfileRowRead? = null, content: @Composable (T) -> Unit) {
        values.forEach { value -> item(key(value), read) { content(value) } }
    }
    fun build(): List<Entry> = rows.toList()
}

internal fun visibleProfileReads(rows: List<ProfileFlowRows.Entry>, keys: Set<Any>): Set<ProfileRowRead> =
    rows.filter { it.key in keys }.mapNotNull { it.read }.toSet()

internal class ProfileMediaSelection(initialIndex: Int = 0, initialId: String? = null) {
    var index by mutableIntStateOf(initialIndex)
        private set
    var id by mutableStateOf(initialId)
        private set

    fun restore(position: Int, mediaId: String?) {
        index = position.coerceAtLeast(0)
        id = mediaId
    }

    private fun ProfileMediaItem.key() = "${kind}:${url.ifBlank { localPath.orEmpty() }}"
    fun resolve(media: List<ProfileMediaItem>, available: Boolean): Int {
        if (!available) return 0
        return media.indexOfFirst { it.key() == id }.takeIf { it >= 0 } ?: mediaIndex(index, media.size)
    }
    fun select(media: List<ProfileMediaItem>, position: Int) {
        index = mediaIndex(position, media.size)
        id = media.getOrNull(index)?.key()
    }
}

@Composable
internal fun rememberProfileMediaSelection(): ProfileMediaSelection = rememberSaveable(
    saver = listSaver<ProfileMediaSelection, String>(
        save = { listOf(it.index.toString(), it.id.orEmpty()) },
        restore = { ProfileMediaSelection(it[0].toInt(), it[1].ifEmpty { null }) },
    ),
) { ProfileMediaSelection() }

/** Owns only demand for one immutable local projection, never the draft or its write.
 * Both automatic demand and explicit retry live outside lazy items. */
internal class ProfileNoteDemand(private val scope: CoroutineScope) {
    enum class Status { Idle, Loading, Settled }
    var status by mutableStateOf(Status.Idle)
        private set
    private var closed = false
    private var job: Job? = null

    fun request(
        token: ProfileIdentityToken?,
        retry: Boolean,
        isCurrent: (ProfileIdentityToken) -> Boolean,
        load: suspend (Long, Long) -> Unit,
    ) {
        if (closed || token == null || status == Status.Loading ||
            (!retry && status != Status.Idle) || !isCurrent(token)) return
        status = Status.Loading
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                load(token.listingId, token.ownerId)
            } finally {
                if (!closed) status = Status.Settled
            }
        }
    }
    fun close() {
        closed = true
        job?.cancel()
    }
}
