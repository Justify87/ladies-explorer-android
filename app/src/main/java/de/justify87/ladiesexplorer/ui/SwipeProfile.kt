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

/** Bound native operations, injected only to characterize the real swipe read sequence.
 * No state, cache or replacement transport lives here. */
internal class SwipeProfileReads(
    val cached: suspend (Long) -> ProfileResponse?,
    val source: suspend (Long) -> ProfileResponse,
    val identity: suspend (Long) -> ProfileIdentity,
    val person: suspend (Long) -> PersonSummary?,
    val media: suspend (Long) -> List<ProfileMediaItem>,
    val history: suspend (Long) -> List<ChangeRecord>,
    val candidates: suspend (Long) -> List<IdentityCandidate>,
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
    SwipeProfileReads(::cachedProfile, { loadSwipeProfile(it, control) }, ::profileIdentity,
        ::person, ::profileMedia, ::changeEvents, ::identityCandidates),
)

internal suspend fun readSwipeProfile(
    listingId: Long,
    refresh: Boolean,
    allowNetwork: Boolean,
    onSourceRead: () -> Unit,
    reads: SwipeProfileReads,
): SwipeProfile {
    var cached = reads.cached(listingId)
    var fromArchive = cached != null && !refresh
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
    val profile = checkNotNull(cached) { "Kein archiviertes Profil vorhanden" }
    currentCoroutineContext().ensureActive()
    val identity = reads.identity(listingId)
    return SwipeProfile(profile, identity, reads.person(identity.personId),
        reads.media(profile.snapshotId), reads.history(identity.personId),
        reads.candidates(listingId), fromArchive, warning)
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
