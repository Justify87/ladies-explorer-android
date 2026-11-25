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

    @Test fun explicitSwipeEntryReturnsToActualOriginAndSurvivesRestoration() {
        for (mode in listOf(DiscoverMode.Grid, DiscoverMode.Map)) {
            val origin = ShellRoute().select(mode)
            val swipe = origin.openSwipe()
            assertEquals(DiscoverMode.Swipe, swipe.discovery)
            assertEquals(mode, swipe.swipeOrigin)
            assertEquals("discover/Swipe", swipe.stateKey)
            assertEquals(BackAction.Navigate, swipe.backAction(false, false))
            assertEquals(origin, ShellRoute.decode(swipe.encode()).back())
            assertNull(swipe.select(DiscoverMode.Grid).swipeOrigin)
            assertNull(swipe.select(RootDestination.Search).swipeOrigin)
        }
        assertEquals(ShellRoute(), ShellRoute().openSwipe())
        assertNull(ShellRoute.decode(listOf("Discover", "Swipe", "Swipe")).swipeOrigin)
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
    @Test fun settingsSubmenusHaveOwnRestorableRouteAndBackReturnsToSettingsFirst() {
        val settings = ShellRoute().select(DiscoverMode.Map).select(RootDestination.Settings)
        val children = SettingsPane.entries.filter { it != SettingsPane.Main }.map(settings::openSettings)
        assertEquals(4, (children + settings).map { it.stateKey }.toSet().size)
        for (child in children) {
            assertEquals(child, ShellRoute.decode(child.encode()))
            assertEquals(BackAction.Navigate, child.backAction(false, false))
            assertEquals(settings, child.back())
            assertEquals(DiscoverMode.Map, child.back().back().discovery)
            assertEquals(SettingsPane.Main, child.select(RootDestination.Search).settingsPane)
        }
        assertEquals(settings, ShellRoute.decode(listOf("Settings", "Map", "")))
        assertEquals(SettingsPane.Main, ShellRoute.decode(listOf("Discover", "Map", "", "Diagnostics")).settingsPane)
    }
}
