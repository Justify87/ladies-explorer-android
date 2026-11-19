package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SwipeProfileReadTest {
    private fun profile(snapshot: Long = 11L) = ProfileResponse(
        listingId = 4L, snapshotId = snapshot, newSnapshot = false, advertId = "fixture",
        name = "Fixture", city = "Fixture city", description = "", postalCode = null,
        hiddenLocation = null, sourceProfileId = null, canonicalId = null,
        phoneDisplay = null, publishedAt = null, traitsJson = "{}", servicesJson = "{}",
        openingTimesJson = "{}", media = emptyList(),
    )

    private fun identity(owner: Long) = ProfileIdentity(
        personId = owner, status = "ACTIVE", profileUrl = null, personNew = false,
        listingNew = false, hasVideo = false, age = null,
    )

    private class Fixture(val cachedProfile: ProfileResponse?) {
        val calls = mutableListOf<String>()
        var owner = 1L
        var online: suspend () -> ProfileResponse = { requireNotNull(cachedProfile).copy(snapshotId = 12L) }
        var onPerson: suspend () -> Unit = {}
        val reads = SwipeProfileReads(
            cached = { calls += "cache:$it"; cachedProfile },
            source = { calls += "source:$it"; online() },
            identity = { calls += "identity:$it"; ProfileIdentity(owner, "ACTIVE", null, false, false, false, null) },
            person = { calls += "person:$it"; onPerson(); PersonSummary(it, "Fixture", Preference.NONE, false, 1u, "") },
            media = { calls += "media:$it"; listOf(ProfileMediaItem("IMAGE", "", null, 0u)) },
            history = { calls += "history:$it"; emptyList() },
            candidates = {
                calls += "candidates:$it:owner:$owner"
                listOf(IdentityCandidate(owner + 10, "CANDIDATE", false, true, 0u, 0u, null))
            },
        )
    }

    @Test fun cachedPrefetchDoesNotAccessSourceAndReadsBySnapshotAndOwner() = runBlocking {
        val f = Fixture(profile())
        val result = readSwipeProfile(4L, false, false, { f.calls += "completion" }, f.reads)
        assertTrue(result.fromArchive)
        assertEquals(11L, result.profile.snapshotId)
        assertEquals(identity(1L), result.identity)
        assertEquals(1L, result.person?.personId)
        assertEquals(listOf("cache:4", "identity:4", "person:1", "media:11", "history:1", "candidates:4:owner:1"), f.calls)
    }

    @Test fun failedRefreshFallsBackLocallyAndReportsNativeCompletionExactlyOnce() = runBlocking {
        val f = Fixture(profile())
        f.online = { error("source failed") }
        val result = readSwipeProfile(4L, true, true, { f.calls += "completion" }, f.reads)
        assertTrue(result.fromArchive)
        assertNotNull(result.warning)
        assertEquals(11L, result.profile.snapshotId)
        assertEquals(listOf("cache:4", "source:4", "completion", "identity:4", "person:1", "media:11", "history:1", "candidates:4:owner:1"), f.calls)
    }

    @Test fun successfulRefreshUsesReturnedSnapshotWithoutExtraSourceRead() = runBlocking {
        val f = Fixture(profile())
        val result = readSwipeProfile(4L, true, true, { f.calls += "completion" }, f.reads)
        assertFalse(result.fromArchive)
        assertNull(result.warning)
        assertEquals(12L, result.profile.snapshotId)
        assertTrue("media:12" in f.calls)
        assertFalse("media:11" in f.calls)
        assertEquals(1, f.calls.count { it == "source:4" })
        assertEquals(1, f.calls.count { it == "completion" })
    }

    @Test fun cancelledSourceDoesNotFallBackOrStartLocalProjectionButSignalsCompletion() = runBlocking {
        val f = Fixture(profile())
        val stopped = CancellationException("cancelled")
        f.online = { throw stopped }
        val failure = runCatching {
            readSwipeProfile(4L, true, true, { f.calls += "completion" }, f.reads)
        }.exceptionOrNull()
        assertSame(stopped, failure)
        assertEquals(listOf("cache:4", "source:4", "completion"), f.calls)
    }

    @Test fun knownGapOwnerChangeDuringMultiReadReturnsMixedProjection() = runBlocking {
        val f = Fixture(profile())
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.onPerson = { started.complete(Unit); finish.await() }
        var projected: SwipeProfile? = null
        val job = launch {
            projected = readSwipeProfile(4L, false, false, {}, f.reads)
        }
        started.await()
        f.owner = 2L
        finish.complete(Unit)
        job.join()
        // Existing read sequence does not validate the owner again after the split reads.
        // T10.2 must replace this characterization with rejection/recovery of stale data.
        val mixed = requireNotNull(projected)
        assertEquals(1L, mixed.identity.personId)
        assertEquals(12L, mixed.candidates.single().personId)
        assertTrue("history:1" in f.calls)
        assertEquals(1, f.calls.count { it == "identity:4" })
    }


}
