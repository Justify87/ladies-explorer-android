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
        query = SearchRequest("Leipzig", 20u, "", 18u, 60u, 140u, 200u, "{}"),
        local = defaultLocalVisibility(), searchRunId = id, city = "Leipzig",
        requestedRadiusKm = 20u, serverCount = 1u, totalPages = 1u, fetchedPages = 1u,
        partial = false, radiusBoundaryStopped = false, radiusVerified = true,
        profiles = emptyList(),
    )

    @Test fun gridAvailabilityRequiresRenderedRouteRunAndDraft() {
        val discovery = DiscoverUiState(loading = false, actionSurface = ProfileActionSurface.GRID, actionGeneration = 4L)
        val search = SearchUiState(city = "Leipzig")
        val action = search.discoveryAvailabilityAction(4L)
        assertTrue(discoveryAvailabilityMatches(discovery, search, action))
        val map = discovery.copy(actionSurface = ProfileActionSurface.MAP)
        val mapAction = search.discoveryAvailabilityAction(4L, ProfileActionSurface.MAP)
        assertTrue(discoveryAvailabilityMatches(map, search, mapAction))
        assertFalse(discoveryAvailabilityMatches(discovery, search, mapAction))
        assertFalse(discoveryAvailabilityMatches(map, search, action))
        assertFalse(discoveryAvailabilityMatches(map.copy(actionGeneration = 5L), search, mapAction))
        assertFalse(discoveryAvailabilityMatches(discovery, search.copy(city = "Berlin"), action))
        assertFalse(discoveryAvailabilityMatches(discovery, search.copy(local = search.local.copy(videoOnly = true)), action))
        for (state in listOf(
            discovery.copy(actionSurface = ProfileActionSurface.NONE),
            discovery.copy(actionSurface = ProfileActionSurface.MAP),
            discovery.copy(actionGeneration = 5L),
            discovery.copy(activeRunId = 7L),
            discovery.copy(loading = true),
            discovery.copy(busy = true),
        )) assertFalse(discoveryAvailabilityMatches(state, search, action))
        for (state in listOf(search.copy(loading = true), search.copy(actionBusy = true), search.copy(profileOpen = true))) {
            assertFalse(discoveryAvailabilityMatches(discovery, state, action))
        }

        val active = search.acceptRun(response(7), emptyList())
        val activeDiscovery = discovery.copy(activeRunId = 7L, actionGeneration = 5L)
        val activeAction = active.discoveryAvailabilityAction(5L)
        assertTrue(discoveryAvailabilityMatches(activeDiscovery, active, activeAction))
        // Header quick selection uses the executed query, not the unexecuted draft.
        assertTrue(discoveryAvailabilityMatches(activeDiscovery, active.copy(city = "Berlin"), activeAction))
        assertFalse(discoveryAvailabilityMatches(activeDiscovery, active, action))
        assertFalse(discoveryAvailabilityMatches(activeDiscovery.copy(activeRunId = 8L),
            active.acceptRun(response(8), emptyList()), activeAction))
    }

    @Test fun gridAvailabilityRejectsOldCallbackAfterFailedOrCancelledRun() {
        val search = SearchUiState()
        val discovery = DiscoverUiState(loading = false, actionSurface = ProfileActionSurface.GRID, actionGeneration = 4L)
        val action = search.discoveryAvailabilityAction(4L)
        val revoked = discovery.copy(actionGeneration = 5L)
        val loading = search.beginRun()
        assertFalse(discoveryAvailabilityMatches(revoked, loading, action))
        for (ended in listOf(loading.endRun("failed"), loading.endRun())) {
            assertFalse(discoveryAvailabilityMatches(revoked, ended, action))
            assertTrue(discoveryAvailabilityMatches(revoked, ended, ended.discoveryAvailabilityAction(5L)))
        }
    }

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

    @Test fun mapControlsRequireRenderedStateAndVisibleMembership() {
        val cards = listOf(fixtureCard(1), fixtureCard(2))
        val membership = MapMembership(null, cards)
        val map = MapUiState(overview = MapOverview(emptyList(), 2u, 2u, 2u),
            membership = membership, actionGeneration = 3L)
        val discovery = DiscoverUiState(localCards = cards, loading = false,
            actionSurface = ProfileActionSurface.MAP, actionGeneration = 9L)
        val search = SearchUiState()
        val action = MapControlAction(map, 9L)
        for (control in listOf(MapControl.REFRESH, MapControl.RESOLVE)) {
            assertTrue(mapControlMatches(map, discovery, search, action, control))
            assertFalse(mapControlMatches(map.copy(actionGeneration = 4L), discovery, search, action, control))
            assertFalse(mapControlMatches(map.copy(membership = MapMembership(7, cards)), discovery, search, action, control))
            assertFalse(mapControlMatches(map.copy(overview = null), discovery, search, action, control))
            assertFalse(mapControlMatches(map, discovery.copy(actionSurface = ProfileActionSurface.NONE), search, action, control))
            assertFalse(mapControlMatches(map, discovery.copy(actionGeneration = 10L), search, action, control))
            assertFalse(mapControlMatches(map, discovery, search.copy(profileOpen = true), action, control))
            assertFalse(mapControlMatches(map, discovery, search.copy(loading = true), action, control))
            assertFalse(mapControlMatches(map, discovery.copy(busy = true), search, action, control))
        }
        assertFalse(mapControlMatches(map, discovery, search, action, MapControl.CANCEL))
        assertFalse(mapControlMatches(map, discovery.copy(localCards = cards.drop(1)), search, action, MapControl.RESOLVE))
        val noPending = map.copy(overview = MapOverview(emptyList(), 2u, 2u, 0u))
        assertFalse(mapControlMatches(noPending, discovery, search, MapControlAction(noPending, 9L), MapControl.RESOLVE))
        val failedRead = map.copy(overview = null, error = "read failed", actionGeneration = 4L)
        assertTrue(mapControlMatches(failedRead, discovery, search, MapControlAction(failedRead, 9L), MapControl.REFRESH))
    }

    @Test fun mapControlsDistinguishLoadingResolvingAndCancellation() {
        val cards = listOf(fixtureCard(1), fixtureCard(2))
        val map = MapUiState(overview = MapOverview(emptyList(), 2u, 2u, 2u),
            membership = MapMembership(null, cards), actionGeneration = 3L)
        val discovery = DiscoverUiState(localCards = cards, loading = false,
            actionSurface = ProfileActionSurface.MAP, actionGeneration = 9L)
        val search = SearchUiState()
        for (state in listOf(map.copy(loading = true), map.copy(resolving = true), map.copy(resolving = true, cancelling = true))) {
            val action = MapControlAction(state, 9L)
            assertFalse(mapControlMatches(state, discovery, search, action, MapControl.REFRESH))
            assertFalse(mapControlMatches(state, discovery, search, action, MapControl.RESOLVE))
        }
        val resolving = map.copy(resolving = true, actionGeneration = 4L)
        val cancel = MapControlAction(resolving, 9L)
        assertTrue(mapControlMatches(resolving, discovery, search, cancel, MapControl.CANCEL))
        assertFalse(mapControlMatches(resolving.copy(cancelling = true), discovery, search, cancel, MapControl.CANCEL))
        assertFalse(mapControlMatches(resolving.copy(actionGeneration = 5L), discovery, search, cancel, MapControl.CANCEL))
        assertFalse(mapControlMatches(resolving, discovery.copy(actionGeneration = 10L), search, cancel, MapControl.CANCEL))
    }

    @Test fun mapOpenRequiresCurrentRunListingOwnerPreferenceAndRouteGeneration() {
        val row = fixtureCard(1, 2).copy(favorite = true)
        val search = SearchUiState().acceptRun(response(7), listOf(row))
        val profile = MapProfile(1L, 2L, "Fixture", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", true, null)
        val overview = MapOverview(listOf(profile), 1u, 0u, 0u)
        val map = MapUiState(overview = overview, membership = MapMembership(7, listOf(row)))
        val discovery = DiscoverUiState(activeRunId = 7, actionSurface = ProfileActionSurface.MAP, actionGeneration = 9, loading = false)
        val click = MapProfileOpenAction(overview, profile, 9)
        assertTrue(mapProfileOpenMatches(map, discovery, search, click))
        assertFalse(mapProfileOpenMatches(map.copy(loading = true), discovery, search, click))
        assertFalse(mapProfileOpenMatches(map.copy(overview = overview.copy(profiles = listOf(profile))), discovery, search, click))
        assertFalse(mapProfileOpenMatches(map.copy(overview = overview.copy(profiles = listOf(profile.copy(personId = 3L)))), discovery, search, click))
        assertFalse(mapProfileOpenMatches(map, discovery.copy(actionGeneration = 10), search, click))
        assertFalse(mapProfileOpenMatches(map, discovery.copy(actionSurface = ProfileActionSurface.GRID), search, click))
        assertFalse(mapProfileOpenMatches(map, discovery.copy(busy = true), search, click))
        assertFalse(mapProfileOpenMatches(map, discovery, search.copy(activeCards = listOf(row.copy(personId = 3))), click))
        assertFalse(mapProfileOpenMatches(map, discovery, search.copy(activeCards = listOf(row.copy(preference = de.justify87.ladiesexplorer.core.Preference.LIKE))), click))
        assertFalse(mapProfileOpenMatches(map, discovery, search.acceptRun(response(8), listOf(row)), click))
        assertTrue(requireNotNull(map.membership).matches(overview))
        assertFalse(requireNotNull(map.membership).matches(overview.copy(profiles = listOf(profile.copy(personId = 3)))))
        assertFalse(requireNotNull(map.membership).matches(overview.copy(profiles = listOf(profile.copy(listingId = 9)))))
        assertFalse(requireNotNull(map.membership).matches(overview.copy(profiles = listOf(profile.copy(favorite = false)))))
    }

    @Test fun sharedMembershipAndReturnOriginSurviveDecisionsUndoAndDraftChanges() {
        val rows = (1L..4L).map { fixtureCard(it) }
        val search = SearchUiState().acceptRun(response(7), rows)
        val discovery = DiscoverUiState(activeRunId = 7, loading = false)
        val scope = requireNotNull(currentMapMembership(discovery, search))
        assertTrue(scope.cards == gridCards(discovery, search))
        assertTrue(scope == currentMapMembership(discovery, search.copy(city = "Berlin")))
        for (mode in listOf(DiscoverMode.Grid, DiscoverMode.Map)) {
            val origin = ShellRoute().select(mode)
            val session = SwipeSession()
            assertTrue(session.focus(scope.cards, 3, 3))
            val swipe = origin.openSwipe()
            assertTrue(session.queue(scope.cards, true).first().listingId == 3L)
            session.visit(SwipeStep(3, 3, presentation = null))
            session.visit(SwipeStep(1, 1))
            assertTrue(session.queue(scope.cards, true).first().listingId == 2L)
            session.undo(requireNotNull(session.last))
            assertTrue(session.queue(scope.cards, true).first().listingId == 1L)
            assertTrue(swipe.back() == origin)
            assertTrue(scope.cards == discoveryMembership(discovery, search))
        }
    }

    @Test fun gridAnchorRestoresExactKeyOffsetAndClampsRemovedAnchor() {
        val keys = listOf("summary", "listing/4", "listing/5")
        assertTrue(gridRestorePosition(keys, "listing/4", 7, 29) == (1 to 29))
        assertTrue(gridRestorePosition(keys, "listing/3", 7, 29) == (2 to 0))
        assertTrue(gridRestorePosition(emptyList(), "listing/3", 7, 29) == (0 to 0))
    }
}
