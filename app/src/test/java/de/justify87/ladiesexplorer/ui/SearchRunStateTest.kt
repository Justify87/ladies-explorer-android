package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.SearchRequest
import de.justify87.ladiesexplorer.core.SearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun card(id: Long) = DiscoveryCard(
        listingId = id, personId = id, name = "Fixture", city = "Leipzig",
        thumbnailUrl = null, localPath = null, description = null,
        preference = Preference.NONE, favorite = false, hasVideo = false,
        hasAiContent = false, isPremium = false,
    )

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

    @Test fun lateResponseCannotReplaceNewRunAndPartialSuccessIsRetained() {
        val generation = RequestGeneration()
        val first = generation.value
        var state = SearchUiState().acceptRun(response(1), listOf(card(1))).beginRun()
        generation.invalidate() // cancel
        val second = generation.value
        val newRun = response(2, partial = true)
        if (generation.isCurrent(second)) state = state.acceptRun(newRun, listOf(card(2)))
        if (generation.isCurrent(first)) state = state.acceptRun(response(99), listOf(card(99)))
        assertEquals(2L, state.response?.searchRunId)
        assertEquals(2L, state.activeCards.single().listingId)
        assertTrue(state.response!!.partial)
        assertFalse(state.response!!.radiusVerified)
        assertFalse(state.loading)
    }

    @Test fun generationsForPreviewAndFullSearchAreIndependent() {
        val preview = RequestGeneration()
        val search = RequestGeneration()
        val firstPreview = preview.value
        val firstSearch = search.value
        preview.invalidate()
        assertFalse(preview.isCurrent(firstPreview))
        assertTrue(search.isCurrent(firstSearch))
        search.invalidate()
        assertFalse(search.isCurrent(firstSearch))
    }
}
