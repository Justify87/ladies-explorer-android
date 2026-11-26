package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileProjectionTest {
    @Test(timeout = 15_000) fun rejectedConditionalWriteRecoversDetailAndFeedIndependentlyWithoutReplay() = runBlocking {
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
        assertEquals(listOf("saved", "saved", "saved", "discovery", "map", "saved", "discovery"), calls)
    }

    @Test(timeout = 15_000) fun actualDiscoveryPublisherKeepsSuccessfulSiblingAndRejectsOutdatedEpoch() = runBlocking {
        val search = MutableStateFlow(SearchUiState(activeCards = listOf(fixtureCard(1L))))
        val state = MutableStateFlow(DiscoverUiState(cards = listOf(fixtureCard(1L))))
        val page = SavedPersonPage(emptyList(), 0u, 0u, 1L, false)
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
        assertTrue(search.value.activeCards.isEmpty())
        failDiscovery = false
        failSaved = true
        assertFalse(publisher.project(1L))
        assertEquals(listOf(7L), state.value.cards.map { it.listingId })
        assertEquals(listOf(7L), state.value.localCards.map { it.listingId })
        assertEquals(1, window)
        assertNull(state.value.error)
        assertNull(state.value.savedPage)
        assertEquals("saved read failed", state.value.savedError)

        val run = SearchResponse(
            SearchRequest("Leipzig", 20u, "", 18u, 60u, 140u, 200u, "{}"),
            defaultLocalVisibility(), 3L, "Leipzig", 20u, 1u, 1u, 1u, false, false, true,
        )
        search.value = search.value.acceptRun(run, listOf(fixtureCard(1L)))
        val activePublisher = DiscoveryProjectionPublisher(search, state, SwipeSession(), { epoch },
            { error("active run must not alter local window") },
            { DiscoveryProjection(3L, listOf(fixtureCard(7L)), DiscoveryPage(listOf(fixtureCard(7L)), null)) },
            { error("saved read failed") },
        )
        assertFalse(activePublisher.project(1L))
        assertEquals(run, search.value.response)
        assertEquals(listOf(fixtureCard(7L)), search.value.activeCards)
        assertEquals(listOf(7L), state.value.cards.map { it.listingId })
        search.value = SearchUiState()

        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        beforeRead = { started.complete(Unit); finish.awaitSignal() }
        val job = launch { publisher.project(1L) }
        started.awaitSignal()
        epoch = 2L
        state.value = state.value.copy(cards = listOf(fixtureCard(9L)), savedError = "new")
        finish.complete(Unit)
        job.join()
        assertEquals(9L, state.value.cards.single().listingId)
        assertEquals("new", state.value.savedError)
    }

    @Test(timeout = 15_000) fun speculativeProjectionKeepsDeckGenerationUntilMembershipActuallyChanges() = runBlocking {
        val search = MutableStateFlow(SearchUiState())
        val first = fixtureCard(1L)
        val state = MutableStateFlow(DiscoverUiState(
            cards = listOf(first), localCards = listOf(first), swipeSessionId = 7L, loading = false,
        ))
        val page = SavedPersonPage(emptyList(), 0u, 0u, 1L, false)
        var rows = listOf(first)
        val publisher = DiscoveryProjectionPublisher(search, state, SwipeSession(), { 1L }, {},
            {
                assertFalse("Background refresh must not show foreground loading", state.value.loading)
                DiscoveryProjection(null, emptyList(), DiscoveryPage(rows, null))
            },
            { SavedPeopleWindow(page, null) })
        assertTrue(publisher.project(1L, background = true))
        assertEquals(listOf(first), state.value.cards)
        assertEquals(7L, state.value.swipeSessionId)
        assertFalse(state.value.loading)
        rows = listOf(first, fixtureCard(2L))
        assertTrue(publisher.project(1L, background = true))
        assertEquals(rows, state.value.cards)
        assertEquals(8L, state.value.swipeSessionId)
        assertFalse(state.value.loading)
    }

    @Test(timeout = 15_000) fun mapReadFailureInvalidatesOldOverviewAndLateSuccessCannotReplaceNewEpoch() = runBlocking {
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
            projectMapOverview(state, { epoch == 1 }, { started.complete(Unit); finish.awaitSignal(); old }) { s, value -> s.copy(overview = value) }
        }
        started.awaitSignal()
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

    @Test(timeout = 15_000) fun productionPreferenceCommitUsesCasThenTraversesOnlyForExplicitDeckDecision() = runBlocking {
        val state = DiscoverUiState(cards = listOf(fixtureCard(4L, 1L)), loading = false)
        val token = requireNotNull(state.swipeToken(fixtureSwipe())).copy(preference = Preference.LIKE)
        val deck = SwipeSession()
        val writes = mutableListOf<Preference>()
        val write: suspend (Long, Long, Preference?, Preference) -> Preference = { listing, owner, expected, target ->
            assertEquals(4L, listing)
            assertEquals(1L, owner)
            assertEquals(Preference.LIKE, expected)
            writes += target
            Preference.LIKE
        }
        commitProfilePreference(token, Preference.NONE, write, deck::visit)
        assertNull(deck.last)
        assertEquals(listOf(1L), deck.queue(state.cards, active = true).map { it.personId })
        val detail = DetailIdentityToken(2L, token.listingId, token.ownerId, token.snapshotId,
            token.projectionRevision, token.preference, token.favorite, token.candidates)
        commitProfilePreference(detail, Preference.DISLIKE, write, deck::visit)
        assertNull(deck.last)
        try {
            commitProfilePreference(token, Preference.DISLIKE, { _, _, _, _ -> error("CAS failed") }, deck::visit)
            fail("Expected CAS failure")
        } catch (_: IllegalStateException) { }
        assertNull(deck.last)
        commitProfilePreference(token, Preference.DISLIKE, write, deck::visit)
        assertEquals(SwipeStep(4L, 1L, Preference.LIKE, Preference.DISLIKE), deck.last)
        assertTrue(deck.queue(state.cards, active = true).isEmpty())
        assertEquals(listOf(Preference.NONE, Preference.DISLIKE, Preference.DISLIKE), writes)
    }

    @Test(timeout = 15_000) fun committedNeutralResetReprojectsWithoutVisitEvenIfFeedReadFails() = runBlocking {
        val state = DiscoverUiState(cards = listOf(fixtureCard(4L, 1L)), loading = false)
        val token = requireNotNull(state.swipeToken(fixtureSwipe())).copy(preference = Preference.LIKE)
        val deck = SwipeSession()
        var native = Preference.LIKE
        var writes = 0
        val outcome = writeAndProject(
            write = {
                commitProfilePreference(token, Preference.NONE, { listing, owner, expected, target ->
                    assertEquals(4L, listing)
                    assertEquals(1L, owner)
                    assertEquals(native, expected)
                    val previous = native
                    native = target
                    writes++
                    previous
                }, deck::visit)
            },
            project = { error("feed unavailable") },
        )
        assertTrue(outcome is DiscoveryMutationOutcome.ProjectionFailed)
        assertEquals(Preference.NONE, native)
        assertEquals(1, writes)
        assertNull(deck.last)
        // Read-only recovery uses the native state and the unchanged traversal owner.
        val projected = state.cards.map { it.copy(preference = native) }
        assertEquals(projected, deck.queue(projected, active = false))
        assertEquals(1, writes)
    }

    @Test(timeout = 15_000) fun mutationFailureRestoresGridAndReprojectsWithoutRepeatingWrite() = runBlocking {
        val events = mutableListOf<String>()
        val result = writeAndProject(
            write = { events += "write"; error("conflict") },
            project = { events += "projection" },
            onWriteFailure = { events += "restore" },
        )
        assertTrue(result is DiscoveryMutationOutcome.WriteFailed)
        assertEquals(listOf("write", "restore", "projection"), events)
    }

    @Test(timeout = 15_000) fun committedMutationWithFailedProjectionNeverRollsBackGridOrRetriesWrite() = runBlocking {
        val events = mutableListOf<String>()
        val result = writeAndProject(
            write = { events += "write" },
            project = { events += "projection"; error("read failed") },
            onWriteFailure = { events += "restore" },
        )
        assertEquals(DiscoveryMutationOutcome.ProjectionFailed(true), result)
        assertEquals(listOf("write", "projection"), events)
        assertEquals(DiscoveryMutationOutcome.ProjectionFailed(false), writeAndProject(
            write = {}, afterWrite = { error("detail read failed") }, project = {},
        ))
        assertEquals(DiscoveryMutationOutcome.ProjectionFailed(true, true), writeAndProject(
            write = {}, afterWrite = { error("detail read failed") }, project = { error("feed read failed") },
        ))
    }

    @Test(timeout = 15_000) fun committedWriteCanBeFollowedByDetailFailureAndIndependentFeedRecovery() = runBlocking {
        val calls = mutableListOf<String>()
        val result = writeAndProject(
            write = { calls += "commit" },
            afterWrite = { calls += "detail"; error("stale owner") },
            project = {
                check(readIndependentProjections(
                    first = { listOf(fixtureCard(7)) },
                    second = { listOf(fixtureCard(8)) },
                    publishFirst = { calls += "grid:${it.getOrThrow().single().listingId}" },
                    publishSecond = { calls += "saved:${it.getOrThrow().single().listingId}" },
                ))
            },
            onWriteFailure = { calls += "write rollback" },
        )
        assertEquals(DiscoveryMutationOutcome.ProjectionFailed(false, true), result)
        assertEquals(listOf("commit", "detail", "grid:7", "saved:8"), calls)
    }

    @Test(timeout = 15_000) fun cancellationIsNotConvertedIntoWriteFailureOrReadRetry() = runBlocking {
        val calls = mutableListOf<String>()
        val cancellation = kotlinx.coroutines.CancellationException("cancelled")
        val failure = runCatching {
            writeAndProject(
                write = { calls += "write"; throw cancellation },
                project = { calls += "projection" },
                onWriteFailure = { calls += "write failure" },
            )
        }.exceptionOrNull()
        assertTrue(failure === cancellation)
        assertEquals(listOf("write"), calls)
    }
}
