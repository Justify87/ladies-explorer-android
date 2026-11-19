package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.Preference
import org.junit.Assert.*
import org.junit.Test

class SwipeDeckStateTest {
    private fun card(listing: Long, person: Long = listing, preference: Preference = Preference.NONE) =
        DiscoveryCard(listingId = listing, personId = person, name = "Fixture", city = "Leipzig",
            thumbnailUrl = null, localPath = null, description = null, preference = preference,
            favorite = false, hasVideo = false, hasAiContent = false, isPremium = false, age = null)

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

    @Test fun prefetchNeverExceedsTwoFollowingCardsAndShortDragsDoNotDecide() {
        val rows = (1L..8L).map { card(it) }
        assertEquals(listOf(2L, 3L), prefetchCards(rows).map { it.listingId })
        assertTrue(prefetchCards(emptyList()).isEmpty())
        assertNull(swipePreference(95f))
        assertNull(swipePreference(-95f))
        assertEquals(Preference.LIKE, swipePreference(96f))
        assertEquals(Preference.DISLIKE, swipePreference(-96f))
    }

    @Test fun explicitNoneIsCurrentlyAVisitButSkipHasNoPreviousPreference() {
        val session = SwipeSession()
        val cleared = SwipeStep(4, 1, Preference.LIKE, Preference.NONE)
        val rows = listOf(card(4, 1), card(5, 2))
        session.visit(cleared)
        assertEquals(listOf(5L), session.queue(rows, true).map { it.listingId })
        assertEquals(Preference.NONE, session.last?.after)
        session.undo(cleared)
        assertEquals(listOf(4L, 5L), session.queue(rows, true).map { it.listingId })
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
        assertEquals(listOf(4L, 5L), prefetchCards(session.queue(rows, true)).map { it.listingId })
        session.undo(second)
        assertEquals(first, session.last)
        assertEquals(2L, session.queue(rows, true).first().listingId)
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
}
