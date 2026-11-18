package de.justify87.ladiesexplorer.ui

import org.junit.Assert.*
import org.junit.Test

class ShellRouteTest {
    @Test fun rootsAndDiscoverModesHaveDistinctStateKeys() {
        val start = ShellRoute()
        val routes = RootDestination.entries.map(start::select) + start.select(DiscoverMode.Map)
        assertEquals(4, RootDestination.entries.size)
        assertEquals(5, routes.map { it.stateKey }.toSet().size)
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

    @Test fun unknownSavedRouteHasSafeDefault() {
        assertEquals(ShellRoute(), ShellRoute.decode(listOf("old", "missing")))
        assertEquals(ShellRoute(), ShellRoute.decode(emptyList()))
    }
}
