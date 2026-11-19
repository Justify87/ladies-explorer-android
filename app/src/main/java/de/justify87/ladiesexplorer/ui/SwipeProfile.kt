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

/** A committed or cached profile exists; recovery must not repeat the source request. */
internal class LocalProfileReadFailure(cause: Exception) : IllegalStateException(cause.message, cause)

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
    val local = try {
        checkNotNull(reads.projection(listingId)) { "Profil nicht mehr verfügbar" }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        throw LocalProfileReadFailure(failure)
    }
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

/** Bounded composition-owned cache. Every in-flight read owns a per-entry ticket.
 * Prune/reset/owner replacement makes a late delivery ineligible even without cancellation. */
internal class SwipeProfileWindow(private val entries: MutableMap<Long, SwipeProfile>) {
    private data class Slot(val owner: Long, val ticket: Long)
    private val slots = mutableMapOf<Long, Slot>()
    private var nextTicket = 0L

    operator fun get(card: DiscoveryCard): SwipeProfile? = entries[card.listingId]?.takeIf {
        it.profile.listingId == card.listingId && it.identity.personId == card.personId &&
            it.person?.personId == card.personId
    }

    fun remove(listingId: Long) {
        entries.remove(listingId)
        slots[listingId]?.let { slots[listingId] = it.copy(ticket = ++nextTicket) }
    }

    fun clear() {
        entries.clear()
        slots.clear()
    }

    fun retain(cards: List<DiscoveryCard>) {
        val desired = cards.associate { it.listingId to it.personId }
        slots.keys.toList().filter { desired[it] != slots[it]?.owner }.forEach {
            slots.remove(it)
            entries.remove(it)
        }
        for (card in cards) {
            if (card.listingId !in slots) slots[card.listingId] = Slot(card.personId, ++nextTicket)
        }
    }

    private suspend fun obtain(card: DiscoveryCard, read: suspend () -> SwipeProfile): SwipeProfile? {
        val slot = slots[card.listingId]?.takeIf { it.owner == card.personId } ?: return null
        val ticket = slot.copy(ticket = ++nextTicket)
        slots[card.listingId] = ticket
        val profile = read()
        currentCoroutineContext().ensureActive()
        if (slots[card.listingId] != ticket) return null
        check(profile.profile.listingId == card.listingId && profile.identity.personId == card.personId &&
            profile.person?.personId == card.personId) { "Personenzuordnung geändert; Deck bitte aktualisieren." }
        entries[card.listingId] = profile
        return profile
    }

    suspend fun readCurrent(card: DiscoveryCard, read: suspend () -> SwipeProfile): SwipeProfile? =
        obtain(card, read)

    suspend fun prefetch(card: DiscoveryCard, read: suspend () -> SwipeProfile): SwipeProfile? =
        get(card) ?: obtain(card, read)
}
