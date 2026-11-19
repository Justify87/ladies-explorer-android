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
}
