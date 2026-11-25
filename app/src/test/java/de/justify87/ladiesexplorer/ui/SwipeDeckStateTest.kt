package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.Preference
import org.junit.Assert.*
import org.junit.Test

class SwipeDeckStateTest {
    private fun card(listing: Long, person: Long = listing, preference: Preference = Preference.NONE) =
        fixtureCard(listing, person).copy(preference = preference)

    @Test fun activeQueuePreservesRunOrderAndDeduplicatesCanonicalPeople() {
        val rows = listOf(card(4, 1), card(5, 1), card(2, preference = Preference.LIKE),
            card(3, preference = Preference.DISLIKE))
        val session = SwipeSession()
        assertEquals(listOf(4L, 2L, 3L), session.queue(rows, true).map { it.listingId })
        session.replayLikes = false
        assertEquals(listOf(4L, 3L), session.queue(rows, true).map { it.listingId })
        assertEquals(listOf(4L), session.queue(rows, false).map { it.listingId })
    }

    @Test fun decisionsAdvanceEvenIfLikesAreReplayedAndUndoRestoresExactListing() {
        val session = SwipeSession()
        val first = SwipeStep(4, 1, Preference.DISLIKE, Preference.LIKE)
        session.visit(first)
        val rows = listOf(card(2), card(4, 1, Preference.LIKE), card(5, 1))
        assertEquals(listOf(2L), session.queue(rows, true).map { it.listingId })
        assertEquals(Preference.DISLIKE, session.last?.before)
        session.undo(first)
        assertEquals(listOf(4L, 2L), session.queue(rows, true).map { it.listingId })
        assertNull(session.last)
    }

    @Test fun skipAndResetDoNotWritePreferencesAndMergeDoesNotRepeatVisitedListing() {
        val session = SwipeSession()
        session.visit(SwipeStep(4, 1))
        val rows = listOf(card(4, 7), card(5, 7), card(2, preference = Preference.LIKE))
        assertEquals(listOf(2L), session.queue(rows, true).map { it.listingId })
        session.reset()
        assertEquals(listOf(4L, 2L), session.queue(rows, true).map { it.listingId })
        assertEquals(Preference.LIKE, rows.last().preference)
        assertNull(session.last)
    }

    @Test fun explicitEntryUsesExactListingAndOwnerWithoutResettingTraversal() {
        val session = SwipeSession()
        val rows = listOf(card(1), card(2, 8), card(3, 8), card(4))
        session.visit(SwipeStep(1, 1))
        assertFalse(session.focus(rows, 3, 9))
        assertTrue(session.focus(rows, 3, 8))
        assertEquals(listOf(3L, 4L), session.queue(rows, true).map { it.listingId })
        session.visit(SwipeStep(3, 8, Preference.NONE, Preference.LIKE))
        assertEquals(listOf(4L), session.queue(rows, true).map { it.listingId })
        session.undo(requireNotNull(session.last))
        assertEquals(listOf(3L, 4L), session.queue(rows, true).map { it.listingId })
        assertEquals(1L, session.last?.listingId)
    }

    @Test fun explicitEntryCanRevisitButCannotFollowAnUnconfirmedOwnerChange() {
        val session = SwipeSession()
        val rows = listOf(card(1), card(2, preference = Preference.LIKE), card(3))
        session.replayLikes = false
        session.visit(SwipeStep(1, 1))
        assertTrue(session.focus(rows, 1, 1))
        assertEquals(1L, session.queue(rows, true).first().listingId)
        session.visit(SwipeStep(1, 1))
        session.undo(requireNotNull(session.last))
        assertEquals(1L, session.queue(rows, true).first().listingId)
        assertEquals(listOf(3L), session.queue(listOf(card(1, 99), card(3)), true).map { it.listingId })
        assertTrue(session.focus(rows, 2, 2))
        assertEquals(2L, session.queue(rows, true).first().listingId)
        session.reset()
        assertEquals(listOf(1L, 3L), session.queue(rows, true).map { it.listingId })
    }

    @Test fun prefetchNeverExceedsFiveFollowingCards() {
        val rows = (1L..8L).map { card(it) }
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L), swipeReadWindow(rows).drop(1).map { it.listingId })
        assertTrue(swipeReadWindow(emptyList()).drop(1).isEmpty())
    }

    @Test fun explicitNoneDoesNotVisitButSkipHasNoPreviousPreference() {
        val session = SwipeSession()
        val cleared = SwipeStep(4, 1, Preference.LIKE, Preference.NONE)
        val rows = listOf(card(4, 1), card(5, 2))
        session.visit(cleared)
        assertEquals(listOf(4L, 5L), session.queue(rows, true).map { it.listingId })
        assertNull(session.last)
        val skipped = SwipeStep(4, 1)
        session.visit(skipped)
        assertNull(session.last?.before)
        assertNull(session.last?.after)
        session.undo(skipped)
        assertEquals(listOf(4L, 5L), session.queue(rows, true).map { it.listingId })
    }

    @Test fun staleUndoCannotRemoveNewerStepAndPrefetchRemainsBoundedForLargeDeck() {
        val session = SwipeSession()
        val first = SwipeStep(1, 1)
        val second = SwipeStep(2, 2, Preference.NONE, Preference.DISLIKE)
        session.visit(first)
        session.visit(second)
        session.undo(first)
        assertEquals(second, session.last)
        val rows = (1L..250L).map { card(it) }
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), swipeReadWindow(session.queue(rows, true)).drop(1).map { it.listingId })
        session.undo(second)
        assertEquals(first, session.last)
        assertEquals(2L, session.queue(rows, true).first().listingId)
    }

    @Test fun swipeAvailabilityRejectsAnOldMenuBeforeComposeRecomposes() {
        val deck = DiscoverUiState(cards = listOf(card(4)), activeRunId = 3L,
            swipeSessionId = 8L, loading = false)
        val token = deck.deckToken()
        assertTrue(currentSwipeAvailability(deck, false, 3L, token))
        assertFalse(currentSwipeAvailability(deck, false, 4L, token))
        assertFalse(currentSwipeAvailability(deck.copy(swipeSessionId = 9L), false, 3L, token))
        assertFalse(currentSwipeAvailability(deck.copy(activeRunId = 4L), false, 4L, token))
        assertFalse(currentSwipeAvailability(deck, true, 3L, token))
        assertFalse(currentSwipeAvailability(deck.copy(busy = true), false, 3L, token))
        val local = deck.copy(activeRunId = null)
        assertTrue(currentSwipeAvailability(local, false, null, local.deckToken()))
        assertFalse(currentSwipeAvailability(local, false, 3L, local.deckToken()))
    }

    @Test fun renderedAdmissionRejectsChangedOwnerRunSessionPreferenceAndBusyState() {
        val rendered = card(4, 1)
        val state = DiscoverUiState(cards = listOf(rendered, card(5)), activeRunId = 2L,
            swipeSessionId = 8L, loading = false)
        val token = requireNotNull(state.swipeToken(fixtureSwipe(4L, 1L)))
        assertEquals(rendered, currentSwipeActionCard(state, false, token))
        assertNull(currentSwipeActionCard(state.copy(cards = listOf(rendered.copy(personId = 7L))), false, token))
        assertNull(currentSwipeActionCard(state.copy(activeRunId = 3L), false, token))
        assertNull(currentSwipeActionCard(state.copy(swipeSessionId = 9L), false, token))
        assertNull(currentSwipeActionCard(state.copy(cards = listOf(rendered.copy(preference = Preference.LIKE))), false, token))
        assertNull(currentSwipeActionCard(state.copy(busy = true), false, token))
        assertNull(currentSwipeActionCard(state.copy(loading = true), false, token))
        assertNull(currentSwipeActionCard(state, true, token))
        assertNull(state.swipeToken(fixtureSwipe(4L, 7L)))
    }
    @Test fun nativePersonStateOwnsActionTokenAndMismatchBlocksAdmission() {
        val row = card(4, 1)
        val state = DiscoverUiState(cards = listOf(row), loading = false)
        val native = fixtureSwipe()
        val profile = native.copy(local = native.local.copy(
            person = native.person.copy(preference = Preference.LIKE, favorite = true),
        ))
        val token = requireNotNull(state.swipeToken(profile))
        assertEquals(Preference.LIKE, token.preference)
        assertTrue(token.favorite)
        assertNull(currentSwipeActionCard(state, false, token))
        val reconciled = row.copy(preference = Preference.LIKE, favorite = true)
        assertEquals(reconciled, currentSwipeActionCard(state.copy(cards = listOf(reconciled)), false, token))
    }

    @Test fun mergeReconcilesVisitedAliasesWithoutCreatingVisitOrRetargetingUndo() {
        val session = SwipeSession()
        session.visit(SwipeStep(1, 1, Preference.NONE, Preference.LIKE))
        session.reconcileMerge(1, 2, 4)
        assertNull(session.last)
        assertEquals(listOf(2L), session.excludedPeople)
        assertEquals(listOf(4L, 3L), session.queue(listOf(card(3), card(2), card(4)), true).map { it.listingId })
        session.reconcileMerge(2, 3, null)
        assertEquals(listOf(3L), session.excludedPeople)
        assertEquals(listOf(4L), session.queue(listOf(card(3), card(4)), true).map { it.listingId })
        session.reset()
        assertTrue(session.excludedPeople.isEmpty())
    }
    @Test fun undoPresentationIsBoundedAndNeverFollowsChangedIdentity() {
        val session = SwipeSession()
        fun position(id: Long) = SwipePresentation(id, id, 9, "{}", "IMAGE:fixture", "profile-facts", 12, listOf("Services"))
        for (id in 1L..17L) session.visit(SwipeStep(id, id, presentation = position(id)))
        val last = requireNotNull(session.last)
        session.undo(last)
        assertEquals(position(17), session.restoredPresentation(card(17)))
        assertEquals(position(17), session.restoredPresentation(card(17)))
        assertNull(session.restoredPresentation(card(17, 99)))
        assertNull(session.restoredPresentation(card(17)))
        repeat(15) { session.undo(requireNotNull(session.last)) }
        assertNull(session.last?.presentation)
        session.reset()
        assertNull(session.restoredPresentation(card(17)))
    }

    @Test fun restoredReceiptIsOneShotButOnlyForTheMatchingPresentation() {
        val session = SwipeSession()
        val position = SwipePresentation(4, 1, 9, "{}", "IMAGE:fixture", "hero", 12, emptyList())
        session.visit(SwipeStep(4, 1, presentation = position))
        session.undo(requireNotNull(session.last))
        session.consumePresentation(position.copy(anchorOffset = 13))
        assertEquals(position, session.restoredPresentation(card(4, 1)))
        session.consumePresentation(position)
        assertNull(session.restoredPresentation(card(4, 1)))
        assertEquals(4L, session.queue(listOf(card(5), card(4, 1)), true).first().listingId)
        session.visit(SwipeStep(4, 1, presentation = position))
        session.undo(requireNotNull(session.last))
        assertNull(session.restoredPresentation(null)) // successful empty result, no eligible profile
        assertNull(session.restoredPresentation(card(4, 1)))
    }

    @Test fun presentationRequiresSameContextAndIndividuallyValidatesSnapshotAndFilters() {
        val saved = SwipePresentation(4, 1, 9, "{}", "IMAGE:fixture", "hero", 8, emptyList(), runId = 3)
        val projection = ProfileProjectionKey(4, 1, 9, 10, "{}")
        assertTrue(saved.matches(projection, 3))
        assertTrue(saved.matches(projection.copy(revision = 11), 3))
        assertTrue(saved.matches(projection.copy(snapshotId = 10), 3))
        assertEquals(ProfileScrollAnchor("hero", 0), saved.anchorFor(projection.copy(snapshotId = 10)))
        assertFalse(saved.matches(projection.copy(ownerId = 2), 3))
        assertFalse(saved.matches(projection.copy(listingId = 5), 3))
        assertTrue(saved.matches(projection.copy(filters = "different"), 3))
        assertEquals(ProfileScrollAnchor("hero", 0), saved.anchorFor(projection.copy(filters = "different")))
        assertEquals(ProfileScrollAnchor("hero", 8), saved.anchorFor(projection))
        assertFalse(saved.matches(projection, 4))
        assertFalse(saved.matches(null, 3))
    }

    @Test fun traversalWithoutFullProfileStillRejectsReplacedRunSessionAndOwner() {
        val state = DiscoverUiState(cards = listOf(card(4, 1)), activeRunId = 3L,
            swipeSessionId = 8L, loading = false)
        val token = requireNotNull(state.cardToken())
        assertNull(state.swipeToken(null))
        assertEquals(4L, currentSwipeTraversalCard(state, false, token)?.listingId)
        assertNull(currentSwipeTraversalCard(state.copy(swipeSessionId = 9L), false, token))
        assertNull(currentSwipeTraversalCard(state.copy(activeRunId = 4L), false, token))
        assertNull(currentSwipeTraversalCard(state.copy(cards = listOf(card(4, 2))), false, token))
        assertNull(currentSwipeTraversalCard(state.copy(busy = true), false, token))
    }
}
