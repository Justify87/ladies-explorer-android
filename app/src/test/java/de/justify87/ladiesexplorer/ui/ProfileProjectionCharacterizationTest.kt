package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileProjectionCharacterizationTest {
    @Test fun rejectedConditionalWriteRecoversDetailAndFeedIndependentlyWithoutReplay() = runBlocking {
        val calls = mutableListOf<String>()
        val original = IllegalStateException("CAS rejected")
        val result = writeAndProject(
            write = { calls += "write"; throw original },
            recoverWriteFailure = { calls += "detail"; error("local read failed") },
            project = { calls += "feed" },
            afterWrite = { calls += "unexpected success" },
        ) as DiscoveryMutationOutcome.WriteFailed
        assertSame(original, result.failure)
        assertNotNull(result.projectionFailure)
        assertEquals(listOf("write", "detail", "feed"), calls)
    }

    @Test fun hiddenSwipeCompletionsCoalesceDiscoveryButAlwaysInvalidateSavedSearch() {
        val calls = mutableListOf<String>()
        lateinit var policy: SwipeProjectionInvalidation
        policy = SwipeProjectionInvalidation({ calls += "saved" }, { calls += "discovery"; check(policy.beginDiscovery(false)) }, { calls += "map" })
        repeat(3) { policy.sourceCompleted() }
        assertEquals(listOf("saved", "saved", "saved"), calls)
        policy.setVisibility(true, false)
        policy.setVisibility(true, false)
        assertEquals(listOf("saved", "saved", "saved", "discovery"), calls)
        policy.setVisibility(false, true)
        policy.sourceCompleted()
        assertEquals(listOf("saved", "saved", "saved", "discovery", "map", "saved", "map"), calls)
    }

    @Test fun actualDiscoveryPublisherKeepsSuccessfulSiblingAndRejectsOutdatedEpoch() = runBlocking {
        val search = MutableStateFlow(SearchUiState(activeCards = listOf(fixtureCard(1L))))
        val state = MutableStateFlow(DiscoverUiState(cards = listOf(fixtureCard(1L))))
        val page = SavedPersonPage(emptyList(), 0u, 0u, 1L, null)
        var epoch = 1L
        var failDiscovery = true
        var failSaved = false
        var window = 0
        var beforeRead: suspend () -> Unit = {}
        val publisher = DiscoveryProjectionPublisher(search, state, SwipeSession(), { epoch }, { window = it },
            {
                beforeRead()
                if (failDiscovery) error("grid read failed")
                DiscoveryProjection(null, emptyList(), DiscoveryPage(listOf(fixtureCard(7L)), null))
            },
            { if (failSaved) error("saved read failed"); SavedPeopleWindow(page, null) },
        )
        assertFalse(publisher.project(1L))
        assertTrue(state.value.cards.isEmpty())
        assertEquals("grid read failed", state.value.error)
        assertEquals(page, state.value.savedPage)
        failDiscovery = false
        failSaved = true
        assertFalse(publisher.project(1L))
        assertEquals(listOf(7L), state.value.cards.map { it.listingId })
        assertEquals(1, window)
        assertNull(state.value.error)
        assertNull(state.value.savedPage)
        assertEquals("saved read failed", state.value.savedError)

        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        beforeRead = { started.complete(Unit); finish.await() }
        val job = launch { publisher.project(1L) }
        started.await()
        epoch = 2L
        state.value = state.value.copy(cards = listOf(fixtureCard(9L)), savedError = "new")
        finish.complete(Unit)
        job.join()
        assertEquals(9L, state.value.cards.single().listingId)
        assertEquals("new", state.value.savedError)
    }

    @Test fun mapReadFailureInvalidatesOldOverviewAndLateSuccessCannotReplaceNewEpoch() = runBlocking {
        val old = MapOverview(emptyList(), 1u, 1u, 0u)
        val new = MapOverview(emptyList(), 2u, 2u, 0u)
        val state = MutableStateFlow(MapUiState(overview = old, loading = true))
        projectMapOverview(state, { true }, { error("map failed") }) { s, value -> s.copy(overview = value) }
        assertNull(state.value.overview)
        assertEquals("map failed", state.value.error)
        assertFalse(state.value.loading)
        var epoch = 1
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            projectMapOverview(state, { epoch == 1 }, { started.complete(Unit); finish.await(); old }) { s, value -> s.copy(overview = value) }
        }
        started.await()
        epoch = 2
        state.value = MapUiState(overview = new)
        finish.complete(Unit)
        job.join()
        assertEquals(new, state.value.overview)
    }

    @Test fun busyRefreshRetainsPendingAndDirtyUntilTheActualReadStarts() {
        val calls = mutableListOf<String>()
        var busy = true
        lateinit var policy: SwipeProjectionInvalidation
        policy = SwipeProjectionInvalidation(
            { calls += "saved" },
            { if (policy.beginDiscovery(busy)) calls += "read" },
            { calls += "map" },
        )
        policy.setVisibility(true, false)
        repeat(3) { policy.sourceCompleted() }
        assertTrue(policy.pending)
        assertEquals(listOf("saved", "saved", "saved"), calls)
        busy = false
        policy.setVisibility(true, false)
        assertFalse(policy.pending)
        assertEquals(listOf("saved", "saved", "saved", "read"), calls)
        policy.setVisibility(true, false)
        assertEquals(1, calls.count { it == "read" })
    }
}
