package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SwipeProfileReadTest {
    private class Fixture(var cachedProfile: ProfileResponse?) {
        val calls = mutableListOf<String>()
        var owner = 1L
        var online: suspend () -> ProfileResponse = { requireNotNull(cachedProfile).copy(snapshotId = 12L) }
        var beforeProjection: suspend () -> Unit = {}
        val reads = SwipeProfileReads(
            cached = { calls += "cache:$it"; cachedProfile },
            source = { calls += "source:$it"; online().also { cachedProfile = it } },
            projection = {
                calls += "profile:$it"
                beforeProjection()
                cachedProfile?.let { profile ->
                    NativeProfileProjection(profile, fixtureIdentity(owner), fixturePerson(owner),
                        listOf(ProfileMediaItem("IMAGE", "", null, 0u)), emptyList(),
                        listOf(IdentityCandidate(owner + 10, "CANDIDATE", false, true, 0u, 0u, null)))
                }
            },
        )
    }

    @Test fun slotRolePreservesActiveFreshnessAndLocalFirstRecovery() {
        assertEquals(SwipeReadPolicy(true, true), swipeReadPolicy(true, 3, 4, false, false))
        assertEquals(SwipeReadPolicy(false, true), swipeReadPolicy(false, 3, 4, false, false))
        assertEquals(SwipeReadPolicy(false, true), swipeReadPolicy(true, null, 4, false, false))
        assertEquals(SwipeReadPolicy(false, false), swipeReadPolicy(false, null, 4, false, false))
        assertEquals(SwipeReadPolicy(false, false), swipeReadPolicy(true, 3, 4, true, false))
        assertEquals(SwipeReadPolicy(false, true), swipeReadPolicy(true, 3, 4, false, true))
    }

    @Test fun cachedPrefetchDoesNotAccessSourceAndReadsBySnapshotAndOwner() = runBlocking {
        val f = Fixture(fixtureProfile())
        val result = readSwipeProfile(4L, false, false, { f.calls += "completion" }, f.reads)
        assertTrue(result.fromArchive)
        assertEquals(11L, result.profile.snapshotId)
        assertEquals(fixtureIdentity(1L), result.identity)
        assertEquals(1L, result.person?.personId)
        assertEquals(listOf("cache:4", "profile:4"), f.calls)
    }

    @Test fun failedRefreshFallsBackLocallyAndReportsNativeCompletionExactlyOnce() = runBlocking {
        val f = Fixture(fixtureProfile())
        f.online = { error("source failed") }
        val result = readSwipeProfile(4L, true, true, { f.calls += "completion" }, f.reads)
        assertTrue(result.fromArchive)
        assertNotNull(result.warning)
        assertEquals(11L, result.profile.snapshotId)
        assertEquals(listOf("cache:4", "source:4", "completion", "profile:4"), f.calls)
    }

    @Test fun successfulRefreshUsesReturnedSnapshotWithoutExtraSourceRead() = runBlocking {
        val f = Fixture(fixtureProfile())
        val result = readSwipeProfile(4L, true, true, { f.calls += "completion" }, f.reads)
        assertFalse(result.fromArchive)
        assertNull(result.warning)
        assertEquals(12L, result.profile.snapshotId)
        assertEquals(1, f.calls.count { it == "profile:4" })
        assertEquals(1, f.calls.count { it == "source:4" })
        assertEquals(1, f.calls.count { it == "completion" })
    }

    @Test fun cancelledSourceDoesNotFallBackOrStartLocalProjectionButSignalsCompletion() = runBlocking {
        val f = Fixture(fixtureProfile())
        val stopped = CancellationException("cancelled")
        f.online = { throw stopped }
        val failure = runCatching {
            readSwipeProfile(4L, true, true, { f.calls += "completion" }, f.reads)
        }.exceptionOrNull()
        assertSame(stopped, failure)
        assertEquals(listOf("cache:4", "source:4", "completion"), f.calls)
    }

    @Test fun projectionUsesOneAuthoritativeLocalReadAfterOwnerAndSnapshotChange() = runBlocking {
        val f = Fixture(fixtureProfile())
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.beforeProjection = { started.complete(Unit); finish.await() }
        var projected: ProfileReadModel? = null
        val job = launch {
            projected = readSwipeProfile(4L, false, false, {}, f.reads)
        }
        started.await()
        f.owner = 2L
        f.cachedProfile = fixtureProfile(12L)
        finish.complete(Unit)
        job.join()
        val local = requireNotNull(projected)
        assertEquals(2L, local.identity.personId)
        assertEquals(2L, local.person?.personId)
        assertEquals(12L, local.profile.snapshotId)
        assertEquals(12L, local.candidates.single().personId)
        assertEquals(listOf("cache:4", "profile:4"), f.calls)
    }

    @Test fun offlineWithoutCacheFailsWithoutSourceOrCompletion() = runBlocking {
        val f = Fixture(null)
        val error = runCatching { readSwipeProfile(4L, false, false, { f.calls += "completion" }, f.reads) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(listOf("cache:4"), f.calls)
    }

    @Test fun firstSourceFailureWithoutCacheSignalsCompletionAndPreservesFailure() = runBlocking {
        val f = Fixture(null)
        val expected = IllegalStateException("no cache")
        f.online = { throw expected }
        val error = runCatching { readSwipeProfile(4L, true, true, { f.calls += "completion" }, f.reads) }.exceptionOrNull()
        assertSame(expected, error)
        assertEquals(listOf("cache:4", "source:4", "completion"), f.calls)
    }

    @Test fun sourceSuccessThenProjectionFailureDoesNotRetrySource() = runBlocking {
        val f = Fixture(fixtureProfile())
        val failure = IllegalStateException("person projection failed")
        f.beforeProjection = { throw failure }
        val error = runCatching { readSwipeProfile(4L, true, true, { f.calls += "completion" }, f.reads) }.exceptionOrNull()
        assertTrue(error is LocalProfileReadFailure)
        assertSame(failure, error?.cause)
        assertEquals(listOf("cache:4", "source:4", "completion", "profile:4"), f.calls)
        f.beforeProjection = {}
        val recovered = readSwipeProfile(4L, false, false, { f.calls += "completion" }, f.reads)
        assertEquals(12L, recovered.profile.snapshotId)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
    }

    @Test fun cachedActiveRunPrefetchStillAvoidsSource() = runBlocking {
        val f = Fixture(fixtureProfile())
        val result = readSwipeProfile(4L, false, true, { f.calls += "completion" }, f.reads)
        assertTrue(result.fromArchive)
        assertEquals(1L, result.person?.personId)
        assertFalse(f.calls.any { it.startsWith("source:") || it == "completion" })
    }
}
