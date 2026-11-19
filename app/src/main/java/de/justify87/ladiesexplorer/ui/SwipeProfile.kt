package de.justify87.ladiesexplorer.ui

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
): ProfileReadModel = readSwipeProfile(
    listingId, refresh, allowNetwork, onSourceRead,
    SwipeProfileReads(::cachedProfile, { loadProfile(it, control) }, ::profileProjection),
)

internal suspend fun readSwipeProfile(
    listingId: Long,
    refresh: Boolean,
    allowNetwork: Boolean,
    onSourceRead: () -> Unit,
    reads: SwipeProfileReads,
): ProfileReadModel {
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
    return ProfileReadModel(local, fromArchive, warning)
}

/** Only first two images; Coil shares the app's cache/HTTP diagnostics and suspending cancellation. */
internal suspend fun warmSwipeImages(context: Context, card: DiscoveryCard, profile: ProfileReadModel?) {
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
internal class SwipeProfileWindow(private val entries: MutableMap<Long, ProfileReadModel>) {
    private data class Slot(val owner: Long, val ticket: Long)
    private val slots = mutableMapOf<Long, Slot>()
    private val inFlight = mutableMapOf<Long, Slot>()
    private var nextTicket = 0L
    private val invalidations = mutableStateMapOf<Long, Long>()
    private val staleProvenance = mutableMapOf<Long, Pair<Boolean, String?>>()
    var closed = false
        private set

    fun invalidation(card: DiscoveryCard?): Long = card?.let { invalidations[it.listingId] } ?: 0L

    fun requiresLocal(card: DiscoveryCard): Boolean = card.listingId in staleProvenance

    operator fun get(card: DiscoveryCard): ProfileReadModel? = entries[card.listingId]?.takeIf {
        it.profile.listingId == card.listingId && it.identity.personId == card.personId &&
            it.person.personId == card.personId
    }

    fun remove(listingId: Long) {
        entries.remove(listingId)
        slots[listingId]?.let { slots[listingId] = it.copy(ticket = ++nextTicket) }
    }

    fun clear() {
        entries.clear()
        slots.clear()
        inFlight.clear()
        invalidations.clear()
        staleProvenance.clear()
    }

    fun close() {
        closed = true
        clear()
    }

    /** Source persistence can change every candidate/person projection, even with
     * the same snapshot ID. Only the read currently returning from that source
     * may publish without a new local read. No extra network refresh is needed. */
    private fun sourceCompleted(listingId: Long, ticket: Slot) {
        for ((id, slot) in slots.toMap()) {
            if (id == listingId && slot == ticket) continue
            entries.remove(id)?.let { staleProvenance[id] = it.fromArchive to it.warning }
            val next = ++nextTicket
            slots[id] = slot.copy(ticket = next)
            // Revocation is immediate, but restarting a running effect here
            // would cancel its native call and recursively invalidate its successor.
            // The rejected read owns the scheduling of local recovery after drain.
            if (id !in inFlight) invalidations[id] = next
        }
    }

    fun retain(cards: List<DiscoveryCard>) {
        if (closed) return
        val desired = cards.associate { it.listingId to it.personId }
        slots.keys.toList().filter { desired[it] != slots[it]?.owner }.forEach {
            slots.remove(it)
            inFlight.remove(it)
            entries.remove(it)
            invalidations.remove(it)
            staleProvenance.remove(it)
        }
        for (card in cards) {
            if (card.listingId !in slots) slots[card.listingId] = Slot(card.personId, ++nextTicket)
        }
    }

    private suspend fun obtain(card: DiscoveryCard, read: suspend (() -> Unit) -> ProfileReadModel): ProfileReadModel? {
        val slot = slots[card.listingId]?.takeIf { it.owner == card.personId } ?: return null
        val listingId = card.listingId
        val ticket = slot.copy(ticket = ++nextTicket)
        slots[listingId] = ticket
        inFlight[listingId] = ticket
        try {
            val profile = read { sourceCompleted(listingId, ticket) }
            currentCoroutineContext().ensureActive()
            if (slots[listingId] != ticket) {
                // A completed read proves local data exists, but its contents may
                // predate another source commit. Keep only provenance for recovery.
                if (inFlight[listingId] == ticket && slots[listingId]?.owner == card.personId &&
                    profile.profile.listingId == listingId && profile.identity.personId == card.personId &&
                    profile.person.personId == card.personId) {
                    staleProvenance.putIfAbsent(listingId, profile.fromArchive to profile.warning)
                }
                return null
            }
            check(profile.profile.listingId == listingId && profile.identity.personId == card.personId &&
                profile.person.personId == card.personId) { "Personenzuordnung geändert; Deck bitte aktualisieren." }
            val provenance = staleProvenance.remove(listingId)
            val published = if (provenance == null) profile.copy(revision = ticket.ticket)
                else profile.copy(revision = ticket.ticket, fromArchive = provenance.first, warning = provenance.second)
            entries[listingId] = published
            return published
        } finally {
            if (inFlight[listingId] == ticket) {
                inFlight.remove(listingId)
                slots[listingId]?.takeIf { it.owner == card.personId && it != ticket }?.let {
                    invalidations[listingId] = it.ticket
                }
            }
        }
    }

    suspend fun readCurrent(card: DiscoveryCard, read: suspend (() -> Unit) -> ProfileReadModel): ProfileReadModel? =
        obtain(card, read)

    suspend fun prefetch(card: DiscoveryCard, read: suspend (() -> Unit) -> ProfileReadModel): ProfileReadModel? =
        get(card) ?: obtain(card, read)
}
