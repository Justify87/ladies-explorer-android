package de.justify87.ladiesexplorer.ui

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import androidx.compose.ui.unit.IntSize
import de.justify87.ladiesexplorer.core.*
import de.justify87.ladiesexplorer.LadiesApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A committed or cached profile exists; recovery must not repeat the source request. */
internal class LocalProfileReadFailure(cause: Exception) : IllegalStateException(cause.message, cause)

internal class SwipeProfileReads(
    val cached: suspend (Long) -> ProfileResponse?,
    val source: suspend (Long) -> ProfileResponse,
    val projection: suspend (Long) -> NativeProfileProjection?,
)

/** Swipe reads never archive media. */
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

internal fun sameSwipeCard(a: DiscoveryCard?, b: DiscoveryCard?): Boolean =
    a != null && b != null && a.listingId == b.listingId && a.personId == b.personId

/** Current failure requires retry or demotion/promotion; speculative failure may retry once on promotion. */
internal fun swipeFailureNeedsDemotion(startedCurrent: Boolean, stillCurrent: Boolean): Boolean =
    startedCurrent && stillCurrent

internal data class SwipeReadPolicy(val refresh: Boolean, val allowNetwork: Boolean)

/** Active-run freshness is resolved before promotion; strict recovery is local-only. */
internal fun swipeReadPolicy(
    current: Boolean, runId: Long?, listingId: Long,
    localOnly: Boolean, preferLocal: Boolean,
): SwipeReadPolicy {
    require(listingId > 0)
    return SwipeReadPolicy(
        refresh = runId != null && !localOnly && !preferLocal,
        allowNetwork = !localOnly && (current || runId != null),
    )
}

internal suspend fun warmSwipeMedia(context: Context, profile: ProfileReadModel, size: IntSize) {
    if (size.width <= 0 || size.height <= 0) return
    val media = profile.media.filter { it.kind == "IMAGE" }
        .map { mediaModels(it.localPath, it.url) }.filter { it.isNotEmpty() }
    val loader = SingletonImageLoader.get(context)
    for (models in media) for (model in models) {
        currentCoroutineContext().ensureActive()
        val result = loader.execute(heroImageRequest(context, model, size))
        if (result is SuccessResult) break
    }
    val videos = (context.applicationContext as LadiesApplication).galleryVideoFiles
    for (item in profile.media.filter { it.kind == "VIDEO" }) {
        currentCoroutineContext().ensureActive()
        try {
            videos.warm(item)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // An unavailable video does not prevent warming the remaining gallery.
        }
    }
}

/** Per-entry tickets reject late delivery after prune, reset or owner replacement. */
internal class SwipeProfileWindow(
    private val entries: MutableMap<Long, ProfileReadModel>,
    initialExternalRevision: Long = 0L,
) {
    private data class Slot(val owner: Long, val ticket: Long)
    private val slots = mutableMapOf<Long, Slot>()
    private val inFlight = mutableMapOf<Long, Slot>()
    // Presentation survives internal reprojection; action admission still requires a valid entry.
    private val presentation = mutableStateMapOf<Long, ProfileReadModel>()
    private var nextTicket = 0L
    private val invalidations = mutableStateMapOf<Long, Long>()
    private val staleProvenance = mutableMapOf<Long, Pair<Boolean, String?>>()
    private val externalRevision = mutableLongStateOf(initialExternalRevision)
    var closed = false
        private set

    fun invalidation(card: DiscoveryCard?): Long = card?.let { invalidations[it.listingId] } ?: 0L

    fun requiresLocal(card: DiscoveryCard): Boolean = card.listingId in staleProvenance

    fun isCurrent(revision: Long): Boolean = externalRevision.longValue >= revision

    /** A disposed window's late commit revokes replacement data, not its native execution. */
    fun applyExternalRevision(revision: Long) {
        if (isCurrent(revision) || closed) return
        presentation.clear()
        invalidateSlots()
        externalRevision.longValue = revision
    }

    operator fun get(card: DiscoveryCard): ProfileReadModel? = entries[card.listingId]?.takeIf {
        it.profile.listingId == card.listingId && it.identity.personId == card.personId &&
            it.person.personId == card.personId
    }

    fun displayed(card: DiscoveryCard): ProfileReadModel? = presentation[card.listingId]?.takeIf {
        it.identity.personId == card.personId && it.person.personId == card.personId && it.profile.listingId == card.listingId
    }

    fun remove(listingId: Long) {
        entries.remove(listingId)
        presentation.remove(listingId)
        slots[listingId]?.let { slots[listingId] = it.copy(ticket = ++nextTicket) }
    }

    fun clear() {
        entries.clear()
        presentation.clear()
        slots.clear()
        inFlight.clear()
        invalidations.clear()
        staleProvenance.clear()
    }

    fun close() {
        closed = true
        clear()
    }

    /** Source persistence can invalidate all projections even with unchanged snapshot IDs; only its current read may publish. */
    private fun invalidateSlots(listingId: Long? = null, ticket: Slot? = null) {
        for ((id, slot) in slots.toMap()) {
            if (id == listingId && slot == ticket) continue
            entries.remove(id)?.let { staleProvenance[id] = it.fromArchive to it.warning }
            val next = ++nextTicket
            slots[id] = slot.copy(ticket = next)
            // Revoke now; let the rejected read schedule recovery after drain to avoid recursive cancellation.
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
            presentation.remove(it)
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
            val profile = read { invalidateSlots(listingId, ticket) }
            currentCoroutineContext().ensureActive()
            if (slots[listingId] != ticket) {
                // Preserve proof of local existence, not data that may predate another commit.
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
            presentation[listingId] = published
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
