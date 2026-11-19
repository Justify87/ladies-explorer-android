package de.justify87.ladiesexplorer.ui

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class SwipeProfile(
    val profile: ProfileResponse,
    val identity: ProfileIdentity,
    val person: PersonSummary?,
    val media: List<ProfileMediaItem>,
    val history: List<ChangeRecord>,
    val candidates: List<IdentityCandidate>,
    val fromArchive: Boolean,
    val warning: String? = null,
)

/** Source policy is context-specific; the local projection is shared with standalone. */
internal class SwipeProfileReads(
    val cached: suspend (Long) -> ProfileResponse?,
    val source: suspend (Long) -> ProfileResponse,
    val projection: suspend (Long) -> NativeProfileProjection?,
)

/** Reuse the existing source/persistence contract, but never archive media for a swipe read. */
internal suspend fun CoreStore.readSwipeProfile(
    listingId: Long,
    control: RequestControl,
    refresh: Boolean,
    allowNetwork: Boolean = true,
    onSourceRead: () -> Unit = {},
): SwipeProfile = readSwipeProfile(
    listingId, refresh, allowNetwork, onSourceRead,
    SwipeProfileReads(::cachedProfile, { loadSwipeProfile(it, control) }, ::profileProjection),
)

internal suspend fun readSwipeProfile(
    listingId: Long,
    refresh: Boolean,
    allowNetwork: Boolean,
    onSourceRead: () -> Unit,
    reads: SwipeProfileReads,
): SwipeProfile {
    var cached = reads.cached(listingId)
    var fromArchive = cached != null
    var warning: String? = null
    if (allowNetwork && (refresh || cached == null)) {
        // Completion matters even if delivery was cancelled after a native commit.
        try {
            cached = reads.source(listingId)
            fromArchive = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (cached == null) throw failure
            fromArchive = true
            warning = "Online-Aktualisierung fehlgeschlagen; archivierte Daten."
        } finally {
            onSourceRead()
        }
    }
    checkNotNull(cached) { "Kein archiviertes Profil vorhanden" }
    currentCoroutineContext().ensureActive()
    val local = checkNotNull(reads.projection(listingId)) { "Profil nicht mehr verfügbar" }
    return SwipeProfile(local.profile, local.identity, local.person,
        local.media, local.history, local.candidates, fromArchive, warning)
}

/** Only first two images; Coil shares the app's cache/HTTP diagnostics and suspending cancellation. */
internal suspend fun warmSwipeImages(context: Context, card: DiscoveryCard, profile: SwipeProfile?) {
    val models = profile?.media.orEmpty().filter { it.kind == "IMAGE" }
        .mapNotNull { mediaModels(it.localPath, it.url).firstOrNull() }.take(2)
        .ifEmpty { mediaModels(card.localPath, card.thumbnailUrl).take(1) }
    val loader = SingletonImageLoader.get(context)
    for (model in models) {
        currentCoroutineContext().ensureActive()
        loader.execute(ImageRequest.Builder(context).data(model).size(512).build())
    }
}

/** The existing composition-owned listing cache. Its original admission is intentionally
 * characterized here; T10.2 replaces listing-only publication with the full context token. */
internal class SwipeProfileWindow(private val entries: MutableMap<Long, SwipeProfile>) {
    operator fun get(listingId: Long): SwipeProfile? = entries[listingId]
    fun remove(listingId: Long) { entries.remove(listingId) }
    fun clear() = entries.clear()
    fun retain(listingIds: List<Long>) {
        entries.keys.toList().filterNot { it in listingIds }.forEach(entries::remove)
    }

    suspend fun readCurrent(listingId: Long, read: suspend () -> SwipeProfile) {
        entries[listingId] = read()
    }

    suspend fun prefetch(listingId: Long, read: suspend () -> SwipeProfile): SwipeProfile {
        val profile = entries[listingId] ?: read()
        entries[listingId] = profile
        return profile
    }
}
