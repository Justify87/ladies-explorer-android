package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCursor
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

    @Test fun gridPaginationRequiresRenderedCursorGenerationAndVisibleLocalContext() {
        val cursor = DiscoveryCursor(false, "2026-11-24", 7L)
        val discovery = DiscoverUiState(loading = false, nextCursor = cursor,
            actionSurface = ProfileActionSurface.GRID, actionGeneration = 4L)
        val search = SearchUiState()
        val action = GridPageAction(cursor, 4L)
        assertTrue(gridPageMatches(discovery, search, action))
        for (state in listOf(
            discovery.copy(actionSurface = ProfileActionSurface.NONE),
            discovery.copy(actionSurface = ProfileActionSurface.MAP),
            discovery.copy(actionGeneration = 5L),
            discovery.copy(nextCursor = cursor.copy(listingId = 8L)),
            discovery.copy(nextCursor = null),
            discovery.copy(activeRunId = 7L),
            discovery.copy(loading = true),
            discovery.copy(loadingMore = true),
            discovery.copy(busy = true),
        )) assertFalse(gridPageMatches(state, search, action))
        assertFalse(gridPageMatches(discovery, search.acceptRun(response(7), emptyList()), action))
        assertFalse(gridPageMatches(discovery, search.copy(profileOpen = true), action))
        assertFalse(gridPageMatches(discovery, search.copy(loading = true), action))
        assertFalse(gridPageMatches(discovery, search.copy(actionBusy = true), action))
    }

    @Test fun gridPaginationRejectsOldCallbackAfterFailureButAllowsExplicitRetry() {
        val cursor = DiscoveryCursor(false, "2026-11-24", 7L)
        val failed = DiscoverUiState(loading = false, nextCursor = cursor,
            actionSurface = ProfileActionSurface.GRID, actionGeneration = 5L, error = "read failed")
        val search = SearchUiState()
        assertFalse(gridPageMatches(failed, search, GridPageAction(cursor, 4L)))
        assertTrue(gridPageMatches(failed, search, GridPageAction(cursor, 5L)))
        val swipe = failed.copy(actionSurface = ProfileActionSurface.NONE, error = null)
        assertTrue(localDiscoveryPageMatches(swipe, search, cursor))
        assertFalse(gridPageMatches(swipe, search, GridPageAction(cursor, 5L)))
        assertFalse(localDiscoveryPageMatches(swipe.copy(loadingMore = true), search, cursor))
        assertFalse(localDiscoveryPageMatches(swipe, search.acceptRun(response(7), emptyList()), cursor))
    }

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

    @Test fun mapControlsRequireRenderedStateAndVisibleRoute() {
        val map = MapUiState(overview = MapOverview(emptyList(), 2u, 2u, 2u),
            actionGeneration = 3L)
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.MAP, actionGeneration = 9L)
        val search = SearchUiState()
        val action = MapControlAction(map, 9L)
        for (control in listOf(MapControl.REFRESH, MapControl.RESOLVE, MapControl.FILTER)) {
            assertTrue(mapControlMatches(map, discovery, search, action, control))
            assertFalse(mapControlMatches(map.copy(actionGeneration = 4L), discovery, search, action, control))
            assertFalse(mapControlMatches(map.copy(favoritesOnly = true), discovery, search, action, control))
            assertFalse(mapControlMatches(map.copy(overview = null), discovery, search, action, control))
            assertFalse(mapControlMatches(map, discovery.copy(actionSurface = ProfileActionSurface.NONE), search, action, control))
            assertFalse(mapControlMatches(map, discovery.copy(actionGeneration = 10L), search, action, control))
            assertFalse(mapControlMatches(map, discovery, search.copy(profileOpen = true), action, control))
        }
        assertFalse(mapControlMatches(map, discovery, search, action, MapControl.CANCEL))
        val noPending = map.copy(overview = MapOverview(emptyList(), 0u, 0u, 0u))
        assertFalse(mapControlMatches(noPending, discovery, search, MapControlAction(noPending, 9L), MapControl.RESOLVE))
        val failedRead = map.copy(overview = null, error = "read failed", actionGeneration = 4L)
        assertTrue(mapControlMatches(failedRead, discovery, search, MapControlAction(failedRead, 9L), MapControl.REFRESH))
    }

    @Test fun mapControlsDistinguishLoadingResolvingAndCancellation() {
        val map = MapUiState(overview = MapOverview(emptyList(), 2u, 2u, 2u), actionGeneration = 3L)
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.MAP, actionGeneration = 9L)
        val search = SearchUiState()
        for (state in listOf(map.copy(loading = true), map.copy(resolving = true), map.copy(resolving = true, cancelling = true))) {
            val action = MapControlAction(state, 9L)
            assertFalse(mapControlMatches(state, discovery, search, action, MapControl.REFRESH))
            assertFalse(mapControlMatches(state, discovery, search, action, MapControl.RESOLVE))
            assertTrue(mapControlMatches(state, discovery, search, action, MapControl.FILTER))
        }
        val resolving = map.copy(resolving = true, actionGeneration = 4L)
        val cancel = MapControlAction(resolving, 9L)
        assertTrue(mapControlMatches(resolving, discovery, search, cancel, MapControl.CANCEL))
        assertFalse(mapControlMatches(resolving.copy(cancelling = true), discovery, search, cancel, MapControl.CANCEL))
        assertFalse(mapControlMatches(resolving.copy(actionGeneration = 5L), discovery, search, cancel, MapControl.CANCEL))
        assertFalse(mapControlMatches(resolving, discovery.copy(actionGeneration = 10L), search, cancel, MapControl.CANCEL))
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
