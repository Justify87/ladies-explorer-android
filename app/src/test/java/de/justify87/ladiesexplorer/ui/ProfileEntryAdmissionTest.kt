package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.MapOverview
import de.justify87.ladiesexplorer.core.MapProfile
import de.justify87.ladiesexplorer.core.SearchRequest
import de.justify87.ladiesexplorer.core.SearchResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEntryAdmissionTest {
    private fun response(id: Long) = SearchResponse(
        query = SearchRequest("Leipzig", 20u, "", 18u, 60u, 140u, 200u, "{}", 10u),
        local = defaultLocalVisibility(), searchRunId = id, city = "Leipzig",
        requestedRadiusKm = 20u, serverCount = 1u, totalPages = 1u, fetchedPages = 1u,
        partial = false, radiusBoundaryStopped = false, radiusVerified = true,
        profiles = emptyList(),
    )

    @Test fun searchOpenRequiresRenderedRunRowAndRouteGeneration() {
        val card = fixtureCard(1)
        val search = SearchUiState().acceptRun(response(7), listOf(card))
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.SEARCH, actionGeneration = 4, loading = false)
        val click = SearchResultOpenAction(card, 7, 4)
        assertTrue(searchResultOpenMatches(search, discovery, click))
        assertFalse(searchResultOpenMatches(search.copy(activeCards = listOf(card.copy(personId = 2L))), discovery, click))
        assertFalse(searchResultOpenMatches(search.acceptRun(response(8), listOf(card)), discovery, click))
        assertFalse(searchResultOpenMatches(search.copy(loading = true), discovery, click))
        assertFalse(searchResultOpenMatches(search.copy(profileOpen = true), discovery, click))
        assertFalse(searchResultOpenMatches(search, discovery.copy(actionGeneration = 5), click))
        assertFalse(searchResultOpenMatches(search, discovery.copy(actionSurface = ProfileActionSurface.SAVED_RESULTS), click))
    }

    @Test fun mapOpenRequiresCurrentOverviewFilterAndRouteGeneration() {
        val profile = MapProfile(1L, 2L, "Fixture", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", true, null)
        val overview = MapOverview(listOf(profile), 1u, 0u, 0u)
        val map = MapUiState(overview = overview, favoritesOnly = true)
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.MAP, actionGeneration = 9, loading = false)
        val click = MapProfileOpenAction(overview, profile, true, 9)
        assertTrue(mapProfileOpenMatches(map, discovery, click))
        assertFalse(mapProfileOpenMatches(map.copy(favoritesOnly = false), discovery, click))
        assertFalse(mapProfileOpenMatches(map.copy(loading = true), discovery, click))
        assertFalse(mapProfileOpenMatches(map.copy(overview = overview.copy(profiles = listOf(profile))), discovery, click))
        assertFalse(mapProfileOpenMatches(map.copy(overview = overview.copy(profiles = listOf(profile.copy(personId = 3L)))), discovery, click))
        assertFalse(mapProfileOpenMatches(map, discovery.copy(actionGeneration = 10), click))
        assertFalse(mapProfileOpenMatches(map, discovery.copy(actionSurface = ProfileActionSurface.GRID), click))
        assertFalse(mapProfileOpenMatches(map, discovery.copy(busy = true), click))
    }
}
