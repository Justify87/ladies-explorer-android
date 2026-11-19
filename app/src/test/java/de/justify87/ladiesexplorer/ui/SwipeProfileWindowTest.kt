package de.justify87.ladiesexplorer.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SwipeProfileWindowTest {
    @Test fun actualWindowPrunesAndReusesCurrentAndTwoFollowingProfiles() = runBlocking {
        val entries = mutableMapOf<Long, SwipeProfile>()
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

    @Test fun sameListingOwnerChangeInvalidatesOldSnapshotAndContact() = runBlocking {
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

    @Test fun lateCompletionAfterResetAndSameListingReuseCannotRepopulate() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val card = fixtureCard(4L, 1L)
        cache.retain(listOf(card))
        val started = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val late = launch {
            assertNull(cache.prefetch(card) { started.complete(Unit); done.await(); fixtureSwipe(4L, 1L, 11L) })
        }
        started.await()
        cache.clear()
        cache.retain(listOf(card))
        cache.readCurrent(card) { fixtureSwipe(4L, 1L, 12L) }
        done.complete(Unit)
        late.join()
        assertEquals(12L, cache[card]?.profile?.snapshotId)
    }

    @Test fun cancelledSuspendingReadCannotWriteIntoCache() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val card = fixtureCard(4L, 1L)
        cache.retain(listOf(card))
        val started = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val late = launch { cache.readCurrent(card) { started.complete(Unit); done.await(); fixtureSwipe(4L) } }
        started.await()
        late.cancelAndJoin()
        done.complete(Unit)
        assertNull(cache[card])
    }
}
