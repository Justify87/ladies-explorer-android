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
        val ids = (listOf(cards.first()) + prefetchCards(cards)).map { it.listingId }
        cache.readCurrent(1L) { fixtureSwipe(1L) }
        ids.drop(1).forEach { id -> cache.prefetch(id) { fixtureSwipe(id) } }
        assertEquals(setOf(1L, 2L, 3L), entries.keys)
        cache.retain(listOf(2L, 3L, 4L))
        assertEquals(setOf(2L, 3L), entries.keys)
        assertEquals(2L, cache.prefetch(2L) { error("cached profile must not read") }.profile.listingId)
        cache.prefetch(4L) { fixtureSwipe(4L) }
        assertEquals(setOf(2L, 3L, 4L), entries.keys)
        cache.clear()
        assertTrue(entries.isEmpty())
    }

    @Test fun knownGapSameListingOwnerSnapshotAndContactRemainCached() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val old = fixtureSwipe(4L, 1L, 11L)
        cache.prefetch(4L) { old }
        val changed = fixtureCard(4L, 7L)
        cache.retain(listOf(changed.listingId))
        val returned = cache.prefetch(changed.listingId) { fixtureSwipe(4L, 7L, 12L) }
        assertEquals(1L, returned.identity.personId)
        assertEquals(1L, returned.person?.personId)
        assertEquals(11L, returned.profile.snapshotId)
        assertEquals(old.profile.phoneDisplay, returned.profile.phoneDisplay)
        assertEquals(old.media, returned.media)
        // New run uses a different Compose remember instance; the old one is not reused.
        assertNull(SwipeProfileWindow(mutableMapOf())[4L])
    }

    @Test fun knownGapLateCompletionAfterSameWindowResetOrPruneCanRepopulate() = runBlocking {
        val entries = mutableMapOf<Long, SwipeProfile>()
        val cache = SwipeProfileWindow(entries)
        val started = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val late = launch { cache.prefetch(4L) { started.complete(Unit); done.await(); fixtureSwipe(4L) } }
        started.await()
        cache.clear()
        cache.retain(listOf(5L, 6L, 7L))
        done.complete(Unit)
        late.join()
        // Characterizes a delivered result: actual Compose cancellation can prevent delivery,
        // but the cache itself has no admission check for a still-running scope.
        assertNotNull(cache[4L])
        cache.retain(listOf(5L, 6L, 7L))
        assertNull(cache[4L])
    }

    @Test fun cancelledSuspendingReadCannotWriteIntoCache() = runBlocking {
        val cache = SwipeProfileWindow(mutableMapOf())
        val started = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val late = launch { cache.readCurrent(4L) { started.complete(Unit); done.await(); fixtureSwipe(4L) } }
        started.await()
        late.cancelAndJoin()
        done.complete(Unit)
        assertNull(cache[4L])
    }
}
