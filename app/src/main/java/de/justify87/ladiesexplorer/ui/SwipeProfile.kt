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

/** Reuse the existing source/persistence contract, but never archive media for a swipe read. */
internal suspend fun CoreStore.readSwipeProfile(
    listingId: Long,
    control: RequestControl,
    refresh: Boolean,
    allowNetwork: Boolean = true,
    onSourceRead: () -> Unit = {},
): SwipeProfile {
    var cached = cachedProfile(listingId)
    var fromArchive = cached != null && !refresh
    var warning: String? = null
    if (allowNetwork && (refresh || cached == null)) {
        // Mark before I/O: cancellation or a failed post-commit read must not lose
        // the fact that this operation may have changed the persisted snapshot.
        onSourceRead()
        try {
            cached = loadSwipeProfile(listingId, control)
            fromArchive = false
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (cached == null) throw failure
            fromArchive = true
            warning = "Online-Aktualisierung fehlgeschlagen; archivierte Daten."
        }
    }
    val profile = checkNotNull(cached) { "Kein archiviertes Profil vorhanden" }
    currentCoroutineContext().ensureActive()
    val identity = profileIdentity(listingId)
    return SwipeProfile(profile, identity, person(identity.personId),
        profileMedia(profile.snapshotId), changeEvents(identity.personId),
        identityCandidates(listingId), fromArchive, warning)
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
