package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.IdentityCandidate
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SwipeProfileWindowTest {
    @Test(timeout = 15_000) fun actualWindowPrunesAndReusesCurrentAndTwoFollowingProfiles() = runBlocking {
        val entries = mutableMapOf<Long, ProfileReadModel>()
        val cache = SwipeProfileWindow(entries)
        val cards = (1L..250L).map { fixtureCard(it) }
        val window = listOf(cards.first()) + prefetchCards(cards)
        cache.retain(window)
        cache.readCurrent(window.first()) { fixtureSwipe(1L, 1L) }
        window.drop(1).forEach { card -> cache.prefetch(card) { fixtureSwipe(card.listingId, card.personId) } }
        assertEquals(setOf(1L, 2L, 3L), entries.keys)
        cache.retain(cards.drop(1).take(3))
        assertEquals(setOf(2L, 3L), entries.keys)
        assertEquals(2L, cache.prefetch(cards[1]) { error("cached profile must not read") }?.profile?.listingId)
        cache.prefetch(cards[3]) { fixtureSwipe(4L, 4L) }
        assertEquals(setOf(2L, 3L, 4L), entries.keys)
        cache.clear()
        assertTrue(entries.isEmpty())
    }

    @Test(timeout = 15_000) fun unfinishedPrefetchSurvivesPromotionOfTheSameOwnerSlot() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val a = fixtureCard(1L)
        val b = fixtureCard(2L)
        val c = fixtureCard(3L)
        val next = fixtureCard(4L)
        cache.retain(listOf(a, b, c))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var reads = 0
        val pending = launch {
            cache.prefetch(b) {
                reads++
                started.complete(Unit)
                finish.awaitSignal()
                fixtureSwipe(b.listingId, b.personId)
            }
        }
        started.awaitSignal()
        cache.retain(listOf(b, c, next))
        finish.complete(Unit)
        pending.join()
        assertEquals(b.listingId, cache[b]?.profile?.listingId)
        assertEquals(b.personId, cache[b]?.identity?.personId)
        assertEquals(b.personId, cache[b]?.person?.personId)
        cache.prefetch(b) { error("promoted projection must be reused") }
        assertEquals(1, reads)
    }

    @Test(timeout = 15_000) fun promotedSlotRejectsProjectionForDifferentOwner() = runBlocking {
        val entries = mutableMapOf<Long, ProfileReadModel>()
        val cache = SwipeProfileWindow(entries)
        val a = fixtureCard(1L)
        val b = fixtureCard(2L)
        cache.retain(listOf(a, b))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var rejected = false
        val pending = launch {
            try {
                cache.prefetch(b) {
                    started.complete(Unit)
                    finish.awaitSignal()
                    fixtureSwipe(b.listingId, a.personId)
                }
            } catch (_: IllegalStateException) {
                rejected = true
            }
        }
        started.awaitSignal()
        cache.retain(listOf(b))
        finish.complete(Unit)
        pending.join()
        assertTrue(rejected)
        assertNull(cache[b])
        assertFalse(entries.containsKey(b.listingId))
        assertEquals(b.personId, cache.readCurrent(b) {
            fixtureSwipe(b.listingId, b.personId)
        }?.identity?.personId)
    }

    @Test(timeout = 15_000) fun sameListingOwnerChangeInvalidatesOldSnapshotAndContact() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val oldCard = fixtureCard(4L, 1L)
        cache.retain(listOf(oldCard))
        cache.prefetch(oldCard) { fixtureSwipe(4L, 1L, 11L) }
        val changed = fixtureCard(4L, 7L)
        cache.retain(listOf(changed))
        assertNull(cache[changed])
        val returned = requireNotNull(cache.prefetch(changed) { fixtureSwipe(4L, 7L, 12L) })
        assertEquals(7L, returned.identity.personId)
        assertEquals(12L, returned.profile.snapshotId)
        assertNull(cache[oldCard])
    }

    @Test(timeout = 15_000) fun lateCompletionAfterResetAndSameListingReuseCannotRepopulate() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val card = fixtureCard(4L, 1L)
        cache.retain(listOf(card))
        val started = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val late = launch {
            assertNull(cache.prefetch(card) { started.complete(Unit); done.awaitSignal(); fixtureSwipe(4L, 1L, 11L) })
        }
        started.awaitSignal()
        cache.clear()
        cache.retain(listOf(card))
        cache.readCurrent(card) { fixtureSwipe(4L, 1L, 12L) }
        done.complete(Unit)
        late.join()
        assertEquals(12L, cache[card]?.profile?.snapshotId)
    }

    @Test(timeout = 15_000) fun sourceCompletionRevokesOtherProfilesEvenWithSameOwnerAndSnapshot() = runBlocking {
        val entries = mutableMapOf<Long, ProfileReadModel>()
        val cache = SwipeProfileWindow(entries)
        val current = fixtureCard(1L)
        val next = fixtureCard(2L)
        cache.retain(listOf(current, next))
        val initial = requireNotNull(cache.readCurrent(current) {
            fixtureSwipe(1L, 1L, 11L).copy(fromArchive = false)
        })
        val oldInvalidation = cache.invalidation(current)
        cache.prefetch(next) { sourceCompleted ->
            sourceCompleted()
            assertNull(cache[current])
            assertTrue(cache.requiresLocal(current))
            fixtureSwipe(2L, 2L, 12L)
        }
        assertTrue(cache.invalidation(current) > oldInvalidation)
        assertNotNull(cache[next])
        val refreshed = requireNotNull(cache.readCurrent(current) {
            val native = fixtureSwipe(1L, 1L, 11L)
            native.copy(local = native.local.copy(
                media = listOf(ProfileMediaItem("IMAGE", "https://example.invalid/changed.jpg", "/fixture/local.jpg", 0u)),
                candidates = listOf(IdentityCandidate(9L, "CANDIDATE", false, true, 0u, 0u, null)),
            ))
        })
        assertEquals(initial.profile.snapshotId, refreshed.profile.snapshotId)
        assertNotEquals(initial.revision, refreshed.revision)
        assertEquals("/fixture/local.jpg", refreshed.media.single().localPath)
        assertEquals(9L, refreshed.candidates.single().personId)
        assertFalse(refreshed.fromArchive)
        assertFalse(cache.requiresLocal(current))
    }

    @Test(timeout = 15_000) fun sourceCompletionRejectsAnotherInFlightLocalRead() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val a = fixtureCard(1L)
        val b = fixtureCard(2L)
        cache.retain(listOf(a, b))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val late = launch {
            assertNull(cache.readCurrent(a) { started.complete(Unit); release.awaitSignal(); fixtureSwipe(1L) })
        }
        started.awaitSignal()
        val before = cache.invalidation(a)
        cache.prefetch(b) { sourceCompleted -> sourceCompleted(); fixtureSwipe(2L, 2L) }
        // Reject the pending read, but do not cancel/restart a native operation
        // because of another source completion.
        assertEquals(before, cache.invalidation(a))
        assertTrue(late.isActive)
        release.complete(Unit)
        late.join()
        assertNull(cache[a])
        assertTrue(cache.invalidation(a) > before)
        assertTrue(cache.requiresLocal(a))
        assertNotNull(cache[b])
    }

    @Test(timeout = 15_000) fun laterSourceCompletionFromReplacedSlotRevokesItsNewProjection() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val card = fixtureCard(4L, 1L)
        cache.retain(listOf(card))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = launch {
            assertNull(cache.readCurrent(card) { sourceCompleted ->
                started.complete(Unit)
                release.awaitSignal()
                sourceCompleted()
                fixtureSwipe(4L)
            })
        }
        started.awaitSignal()
        cache.remove(card.listingId)
        cache.readCurrent(card) { fixtureSwipe(4L) }
        assertNotNull(cache[card])
        release.complete(Unit)
        old.join()
        assertNull(cache[card])
        assertTrue(cache.requiresLocal(card))
    }

    @Test(timeout = 15_000) fun externalCompletionRevokesReplacementWithoutRestartingItsRunningRead() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf(), initialExternalRevision = 5L)
        val card = fixtureCard(4L, 1L)
        cache.retain(listOf(card))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val current = launch {
            assertNull(cache.readCurrent(card) { started.complete(Unit); release.awaitSignal(); fixtureSwipe(4L) })
        }
        started.awaitSignal()
        val before = cache.invalidation(card)
        assertFalse(cache.isCurrent(6L))
        cache.applyExternalRevision(6L)
        assertTrue(cache.isCurrent(6L))
        assertEquals(before, cache.invalidation(card))
        assertTrue(current.isActive)
        release.complete(Unit)
        current.join()
        assertNull(cache[card])
        assertTrue(cache.requiresLocal(card))
        val after = cache.invalidation(card)
        assertTrue(after > before)
        cache.readCurrent(card) { fixtureSwipe(4L) }
        cache.applyExternalRevision(6L)
        assertEquals(after, cache.invalidation(card))
        assertNotNull(cache[card])
    }

    @Test(timeout = 15_000) fun cancelledSuspendingReadCannotWriteIntoCache() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val card = fixtureCard(4L, 1L)
        cache.retain(listOf(card))
        val started = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val late = launch { cache.readCurrent(card) { started.complete(Unit); done.awaitSignal(); fixtureSwipe(4L) } }
        started.awaitSignal()
        late.cancelAndJoin()
        done.complete(Unit)
        assertNull(cache[card])
    }
}
