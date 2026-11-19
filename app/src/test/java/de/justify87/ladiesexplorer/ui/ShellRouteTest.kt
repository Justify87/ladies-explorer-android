package de.justify87.ladiesexplorer.ui

import org.junit.Assert.*
import org.junit.Test

class ShellRouteTest {
    @Test fun rootsAndDiscoverModesHaveDistinctStateKeys() {
        val start = ShellRoute()
        val routes = RootDestination.entries.map(start::select) + DiscoverMode.entries.filter { it != DiscoverMode.Swipe }.map(start::select)
        assertEquals(4, RootDestination.entries.size)
        assertEquals(6, routes.map { it.stateKey }.toSet().size)
    }

    @Test fun modeSurvivesRootSwitchAndRestoration() {
        val map = ShellRoute().select(DiscoverMode.Map)
        val search = map.select(RootDestination.Search)
        assertFalse(search.mapVisible)
        assertEquals(map, search.back())
        assertEquals(search, ShellRoute.decode(search.encode()))
        assertTrue(search.back().mapVisible)
    }

    @Test fun backFromMapReturnsToSwipeThenSystemBack() {
        val swipe = ShellRoute().select(DiscoverMode.Map).back()
        assertFalse(swipe.mapVisible)
        assertFalse(swipe.canGoBack)
        assertEquals(swipe, swipe.back())
    }

    @Test fun gridHasOwnRestorableRouteAndDoesNotActivateMap() {
        val grid = ShellRoute().select(DiscoverMode.Grid)
        assertFalse(grid.mapVisible)
        assertEquals(grid, grid.select(RootDestination.Search).back())
        assertEquals(grid, ShellRoute.decode(grid.encode()))
        assertEquals(ShellRoute(), grid.back())
    }

    @Test fun backClosesOverlaysBeforeChangingRoot() {
        val search = ShellRoute().select(RootDestination.Search)
        assertEquals(BackAction.CloseProfile, search.backAction(true, true))
        assertEquals(BackAction.CloseSavedResults, search.backAction(false, true))
        assertEquals(BackAction.Navigate, search.backAction(false, false))
        assertEquals(BackAction.System, ShellRoute().backAction(false, false))
    }

    @Test fun unknownSavedRouteHasSafeDefault() {
        assertEquals(ShellRoute(), ShellRoute.decode(listOf("old", "missing")))
        assertEquals(ShellRoute(), ShellRoute.decode(emptyList()))
    }

    @Test fun allProfileEntrypointRoutesCloseProfileBeforeChangingContext() {
        val base = ShellRoute()
        val origins = listOf(
            base.select(DiscoverMode.Grid),
            base.select(DiscoverMode.Map),
            base.select(RootDestination.Saved),
            base.select(RootDestination.Search),
            base,
        )
        origins.forEach { origin ->
            assertEquals(BackAction.CloseProfile, origin.backAction(true, false))
            assertEquals(origin, ShellRoute.decode(origin.encode()))
        }
        val search = origins[3]
        assertEquals(BackAction.CloseProfile, search.backAction(true, true))
        assertEquals(BackAction.CloseSavedResults, search.backAction(false, true))
    }
}
