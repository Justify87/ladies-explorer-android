package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfilePresentationTest {
    @Test fun gapCannotReplaceDetailAnchorWithFallbackLayout() {
        val memory = ProfileScrollMemory()
        memory.observe("event/20", 36)
        memory.gap()
        memory.observe("hero", 0)
        val rows = ProfileFlowRows(ProfileMediaSelection()).apply {
            item("hero") {}
            item("profile-facts") {}
            item("profile-history-head") {}
            item("event/20") {}
        }.build()
        assertEquals(3 to 36, memory.target(rows.map { it.key }))
        memory.restored()
        memory.observe("profile-facts", 8)
        assertEquals(ProfileScrollAnchor("profile-facts", 8), memory.anchor)
    }

    @Test fun directRevisionChangeFreezesAnchorJustLikeAGap() {
        val key = ProfileProjectionKey(4L, 1L, 11L, 2L, "{}")
        val memory = ProfileScrollMemory()
        memory.projectionChanged(key)
        memory.observe("listing/9", 22)
        memory.projectionChanged(key.copy(revision = 3L))
        assertTrue(memory.recovering)
        assertTrue(memory.needsDetail())
        memory.observe("profile-facts", 0)
        assertEquals(2 to 22, memory.target(listOf("hero", "profile-listings-head", "listing/9")))
        memory.restored()
        memory.projectionChanged(key.copy(revision = 3L))
        assertFalse(memory.recovering)
        val newOwner = ProfileScrollMemory(initialRecovery = true)
        assertEquals(0 to 0, newOwner.target(listOf("hero", "profile-facts")))
    }

    @Test fun sameOwnerGapWaitsForFactsRatherThanRestoringOffsetIntoSpinner() {
        val memory = ProfileScrollMemory()
        memory.projectionChanged(ProfileProjectionKey(4L, 1L, 11L, 2L, "{}"))
        memory.observe("profile-services", 60)
        memory.projectionChanged(null)
        assertTrue(memory.needsFacts())
        assertFalse(memory.needsDetail())
        assertEquals(1 to 60, memory.target(listOf("hero", "profile-services")))
        assertEquals(1 to 0, memory.target(listOf("hero", "profile-services"), readFailed = true))
        assertEquals(19, restoredRowOffset(60, 20))
        assertEquals(0, restoredRowOffset(60, 0))
        assertEquals(8, restoredRowOffset(8, 100))
    }

    @Test fun missingAnchorFallsBackToItsSectionAndListingRequestsDetail() {
        val memory = ProfileScrollMemory()
        memory.observe("listing/9", 22)
        memory.gap()
        assertTrue(memory.needsListings())
        assertEquals(1 to 0, memory.target(listOf("hero", "profile-listings-head")))
        assertEquals(2 to 22, memory.target(listOf("hero", "profile-listings-head", "listing/9")))
        memory.restored()
        assertFalse(memory.needsListings())
    }

    @Test fun mediaSelectionSurvivesMissingProjectionAndReordering() {
        val a = ProfileMediaItem("IMAGE", "a", null, 0u)
        val b = ProfileMediaItem("IMAGE", "b", null, 1u)
        val selection = ProfileMediaSelection()
        selection.select(listOf(a, b), 1)
        assertEquals(0, selection.resolve(listOf(a), false))
        assertEquals(1, selection.index)
        assertEquals(0, selection.resolve(listOf(b, a), true))
        assertEquals(0, selection.resolve(listOf(a), true))
    }

    @Test fun openedNoteCoalescesDemandAndRetryDoesNotDependOnLazyItem() = runBlocking {
        val state = DiscoverUiState(cards = listOf(fixtureCard(4L, 1L)), loading = false)
        val token = requireNotNull(state.swipeToken(fixtureSwipe()))
        var current = true
        var reads = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val demand = ProfileNoteDemand(this)
        val load: suspend (Long, Long) -> Unit = { listing, owner ->
            assertEquals(4L, listing)
            assertEquals(1L, owner)
            reads++
            if (reads == 2) { started.complete(Unit); finish.await() }
        }
        demand.request(token, false, { current }, load)
        demand.request(token, false, { current }, load)
        assertEquals(1, reads)
        demand.request(token, true, { current }, load)
        started.await()
        demand.request(token, false, { current }, load)
        demand.request(token, true, { current }, load)
        assertEquals(ProfileNoteDemand.Status.Loading, demand.status)
        finish.complete(Unit)
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(ProfileNoteDemand.Status.Settled, demand.status)
        current = false
        demand.request(token, true, { current }, load)
        assertEquals(2, reads)
        demand.close()
    }

    @Test fun productionPreferenceCommitUsesCasThenTraversesOnlyForExplicitDeckDecision() = runBlocking {
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

    @Test fun committedNeutralResetReprojectsWithoutVisitEvenIfFeedReadFails() = runBlocking {
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
}
