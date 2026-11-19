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
        if (projection != null && projection != next) gap()
        projection = next
    }

    fun observe(key: String, offset: Int) {
        if (!recovering) anchor = ProfileScrollAnchor(key, offset.coerceAtLeast(0))
    }
    fun gap() { if (anchor != null) recovering = true }
    fun needsFacts() = recovering && anchor?.key in setOf("profile-facts", "profile-services")
    fun needsDetail() = recovering && (anchor?.key?.startsWith("listing/") == true ||
        anchor?.key in setOf("profile-analysis", "profile-listings-head"))
    fun needsListings() = recovering && anchor?.key?.startsWith("listing/") == true

    fun target(keys: List<String>): Pair<Int, Int>? {
        if (!recovering || keys.isEmpty()) return null
        val saved = anchor ?: return 0 to 0
        val index = keys.indexOf(saved.key)
        if (index >= 0) return index to saved.offset
        val fallback = when {
            saved.key.startsWith("listing/") -> "profile-listings-head"
            saved.key.startsWith("event/") -> "profile-history-head"
            saved.key.startsWith("candidate/") -> "profile-candidate-head"
            else -> "profile-facts"
        }
        return keys.indexOf(fallback).takeIf { it >= 0 }?.let { it to 0 } ?: (0 to 0)
    }
    fun restored() { recovering = false }
}

@Composable
internal fun rememberProfileScrollMemory(reset: Boolean): ProfileScrollMemory = rememberSaveable(
    saver = listSaver<ProfileScrollMemory, String>(
        save = { listOf(it.anchor?.key.orEmpty(), (it.anchor?.offset ?: 0).toString(), it.recovering.toString()) },
        restore = {
            ProfileScrollMemory(it[0].takeIf(String::isNotEmpty)?.let { key ->
                ProfileScrollAnchor(key, it[1].toInt())
            }, it[2].toBoolean())
        },
    ),
) { ProfileScrollMemory(initialRecovery = reset) }

/** The actual lazy row manifest is also the anchor resolver; there is no parallel index table. */
internal class ProfileFlowRows(val mediaSelection: ProfileMediaSelection) {
    data class Entry(val key: String, val content: @Composable () -> Unit)
    private val rows = mutableListOf<Entry>()
    fun item(key: String, content: @Composable () -> Unit) { rows.add(Entry(key, content)) }
    fun <T> items(values: List<T>, key: (T) -> String, content: @Composable (T) -> Unit) {
        values.forEach { value -> item(key(value)) { content(value) } }
    }
    fun build(): List<Entry> = rows.toList()
}

internal class ProfileMediaSelection(initialIndex: Int = 0, initialId: String? = null) {
    var index by mutableIntStateOf(initialIndex)
        private set
    var id by mutableStateOf(initialId)
        private set

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
