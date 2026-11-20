package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.SearchRequest
import de.justify87.ladiesexplorer.core.SearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import de.justify87.ladiesexplorer.core.DiscoveryCursor
import de.justify87.ladiesexplorer.core.DiscoveryPage

class SearchRunStateTest {
    private fun response(id: Long, partial: Boolean = false) = SearchResponse(
        query = SearchRequest("Leipzig", 20u, "", 18u, 60u, 140u, 200u, "{}", 10u),
        local = defaultLocalVisibility(),
        searchRunId = id,
        city = "Leipzig",
        requestedRadiusKm = 20u,
        serverCount = 1u,
        totalPages = 1u,
        fetchedPages = 1u,
        partial = partial,
        radiusBoundaryStopped = false,
        radiusVerified = !partial,
        profiles = emptyList(),
    )

    private fun card(id: Long) = fixtureCard(id)

    @Test fun draftChangesAndCancellationKeepExecutedResult() {
        val old = SearchUiState().acceptRun(response(1), listOf(card(1)))
        val changed = old.applyDraftChange(old.copy(city = "Dresden", radiusKm = 50u))
        assertEquals(1L, changed.response?.searchRunId)
        assertEquals("Leipzig", changed.response?.city)
        assertEquals("Dresden", changed.city)
        assertEquals(old.activeCards, changed.activeCards)
        val pending = changed.beginRun()
        assertTrue(pending.loading)
        val cancelled = pending.endRun()
        assertFalse(cancelled.loading)
        assertEquals(old.response, cancelled.response)
        assertEquals(old.activeCards, cancelled.activeCards)
        assertEquals(old.response, pending.endRun("source failure").response)
    }

    @Test fun partialRunReplacesPreviousResultsWithoutInventingRadiusVerification() {
        val old = SearchUiState().acceptRun(response(1), listOf(card(1))).beginRun()
        val partial = response(2, partial = true)
        val state = old.acceptRun(partial, listOf(card(2)))
        assertEquals(partial, state.response)
        assertEquals(listOf(card(2)), state.activeCards)
        assertFalse(state.loading)
        assertFalse(state.searchCancelling)
    }

    @Test fun activeGridIsNotTheConsumedSwipeQueue() {
        val rows = listOf(card(1), card(2), card(3).copy(preference = Preference.LIKE))
        val search = SearchUiState().acceptRun(response(1), rows)
        val swipe = SwipeSession().apply {
            visit(SwipeStep(1, 1))
            replayLikes = false
        }
        val discovery = DiscoverUiState(cards = swipe.queue(rows, true), activeRunId = 1)
        assertEquals(listOf(2L), discovery.cards.map { it.listingId })
        assertEquals(rows, gridCards(discovery, search))
        // Neither a draft edit nor a cancelled replacement changes the grid's source.
        assertEquals(rows, gridCards(discovery, search.beginRun().endRun()))
        assertEquals(discovery.cards, gridCards(discovery, SearchUiState()))
    }

    @Test fun gridLikeHidesOnlyGridUntilSuccessfulNewRunAndRestoresOnFailure() {
        val rows = listOf(card(1), card(2).copy(favorite = true))
        val initial = SearchUiState().acceptRun(response(1), rows)
        val swipe = SwipeSession().apply { visit(SwipeStep(1, 1)) }
        val last = swipe.last
        val deck = DiscoverUiState(cards = swipe.queue(rows, true))
        val pending = initial.hideGridLike(2)
        assertEquals(listOf(1L), gridCards(deck, pending).map { it.listingId })
        assertEquals(rows, pending.activeCards)
        assertEquals(last, swipe.last)
        assertEquals(rows, gridCards(deck, pending.restoreGridLike(2)))
        assertEquals(pending.gridHiddenLikes, pending.beginRun().endRun().gridHiddenLikes)
        assertEquals(pending.gridHiddenLikes, pending.applyDraftChange(SearchUiState()).gridHiddenLikes)
        assertTrue(pending.acceptRun(response(2), rows).gridHiddenLikes.isEmpty())
        assertEquals(Preference.LIKE, gridLikeTarget(Preference.NONE))
        assertEquals(Preference.LIKE, gridLikeTarget(Preference.DISLIKE))
        assertEquals(Preference.NONE, gridLikeTarget(Preference.LIKE))
    }

    @Test fun reprojectKeepsLoadedWindowAndUpdatedCursor() = runBlocking {
        val next = DiscoveryCursor(false, "2026-01-01", 2)
        val end = DiscoveryCursor(false, "2026-01-01", 4)
        val seen = mutableListOf<DiscoveryCursor?>()
        val result = readDiscoveryWindow(4) { cursor ->
            seen += cursor
            if (cursor == null) DiscoveryPage(listOf(card(1), card(2)), next)
            else DiscoveryPage(listOf(card(3), card(4).copy(favorite = true)), end)
        }
        assertEquals(listOf(null, next), seen)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.cards.map { it.listingId })
        assertEquals(end, result.nextCursor)
        assertTrue(result.cards.last().favorite)
    }

    @Test fun successfulGridWriteKeepsFavoriteAndSwipeHistoryInSharedProjection() = runBlocking {
        val old = card(2).copy(favorite = true)
        var stored = old
        val swipe = SwipeSession().apply { visit(SwipeStep(1, 1)) }
        val step = swipe.last
        var search = SearchUiState().acceptRun(response(1), listOf(card(1), old)).hideGridLike(2)
        var discovery = DiscoverUiState(cards = swipe.queue(search.activeCards, true))
        val result = writeAndProject(
            write = { stored = stored.copy(preference = gridLikeTarget(stored.preference)) },
            project = {
                search = search.copy(activeCards = listOf(card(1), stored))
                discovery = discovery.copy(cards = swipe.queue(search.activeCards, true))
            },
            onWriteFailure = { search = search.restoreGridLike(2) },
        )
        assertEquals(DiscoveryMutationOutcome.Success, result)
        assertEquals(Preference.LIKE, discovery.cards.single().preference)
        assertTrue(discovery.cards.single().favorite)
        assertEquals(step, swipe.last)
        assertEquals(listOf(1L), gridCards(discovery, search).map { it.listingId })
    }

    @Test fun reprojectStopsAtEndEvenWhenRequestedWindowIsLarger() = runBlocking {
        var calls = 0
        val result = readDiscoveryWindow(500) {
            calls++
            DiscoveryPage(listOf(card(1)), null)
        }
        assertEquals(1, calls)
        assertEquals(null, result.nextCursor)
        assertEquals(1, result.cards.size)
    }

    @Test fun paginationDoesNotFireBeforeLayoutOrUntilNearEnd() {
        assertFalse(nearDiscoveryEnd(-1, 0))
        assertFalse(nearDiscoveryEnd(1, 50))
        assertTrue(nearDiscoveryEnd(47, 50))
    }

    @Test fun generationsForPreviewAndFullSearchAreIndependent() {
        val preview = RequestGeneration()
        val search = RequestGeneration()
        val firstPreview = preview.value
        val firstSearch = search.value
        preview.invalidate()
        assertFalse(preview.isCurrent(firstPreview))
        assertTrue(preview.isCurrent(preview.value))
        assertTrue(search.isCurrent(firstSearch))
        search.invalidate()
        assertFalse(search.isCurrent(firstSearch))
        assertTrue(search.isCurrent(search.value))
    }

    @Test fun cancelledNativeOwnerCannotBeReplacedUntilDrain() {
        val owner = DrainingOperation<String>()
        val first = owner.start("source")!!
        var signalled = 0
        owner.cancel { signalled++ }
        owner.cancel { signalled++ }
        assertEquals(1, signalled)
        assertTrue(owner.occupied)
        assertFalse(owner.accepts(first))
        val home = DrainingOperation<String>()
        assertFalse(canAcquireImport(owner, home, false))
        assertEquals(null, owner.start("replacement search"))
        assertTrue(owner.finish(first))
        assertTrue(canAcquireImport(owner, home, false))
        val importing = home.start("import")!!
        assertFalse(canAcquireImport(owner, home, false))
        assertEquals(null, home.start("status"))
        assertTrue(home.accepts(importing))
        assertTrue(home.finish(importing))
        val second = owner.start("replacement search")!!
        assertFalse(owner.finish(first))
        assertFalse(owner.accepts(first))
        assertTrue(owner.accepts(second))
        assertTrue(owner.finish(second))
        assertFalse(owner.occupied)
    }
}
