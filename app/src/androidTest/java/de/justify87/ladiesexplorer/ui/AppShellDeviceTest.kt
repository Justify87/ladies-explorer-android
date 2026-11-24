package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppShellDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rootNavigationKeepsSelectionBadgeAndAdmission() {
        val selections = mutableListOf<RootDestination>()
        lateinit var lock: () -> Unit
        rule.setContent {
            MaterialTheme(colorScheme = ladiesDarkColors) {
                var active by remember { mutableStateOf(RootDestination.Discover) }
                var enabled by remember { mutableStateOf(true) }
                lock = { enabled = false }
                RootNavigation(active, enabled, unreadSearch = true) {
                    selections.add(it)
                    active = it
                }
            }
        }
        rule.onNodeWithText("Entdecken").assertIsSelected()
        rule.onNodeWithContentDescription("Neue gespeicherte Suchergebnisse").assertExists()
        rule.onNodeWithText("Suche").performClick().assertIsSelected()
        rule.onNodeWithText("Entdecken").assertIsNotSelected()
        rule.runOnIdle { lock() }
        rule.onNodeWithText("Gespeichert").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(listOf(RootDestination.Search), selections) }
    }

    @Test fun largeTypeKeepsHeaderAndFourEqualHeightTargets() {
        var back = 0
        var search = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = ladiesLightColors) {
                    Column(Modifier.width(360.dp)) {
                        AppHeader("Gespeicherte Suchergebnisse", onBack = { back++ }) {
                            ShellIconButton(onClick = { search++ }) {
                                Icon(Icons.Default.Search, contentDescription = "Suche öffnen")
                            }
                        }
                        RootNavigation(RootDestination.Search, true, false) {}
                    }
                }
            }
        }
        val minTouch = with(rule.density) { 48.dp.toPx() }
        val minNav = with(rule.density) { 64.dp.toPx() }
        val backNode = rule.onNodeWithContentDescription("Zurück")
        val searchNode = rule.onNodeWithContentDescription("Suche öffnen")
        val backBounds = backNode.fetchSemanticsNode().boundsInRoot
        val searchBounds = searchNode.fetchSemanticsNode().boundsInRoot
        assertTrue(backBounds.width >= minTouch - 1f && backBounds.height >= minTouch - 1f)
        assertTrue(searchBounds.width >= minTouch - 1f && searchBounds.height >= minTouch - 1f)
        assertTrue(backBounds.right <= searchBounds.left + 1f)
        val bounds = RootDestination.entries.map { rule.onNodeWithText(it.title).fetchSemanticsNode().boundsInRoot }
        bounds.forEach {
            assertTrue(it.height >= minNav - 1f && it.width >= minTouch - 1f)
            assertEquals(bounds.first().top, it.top, 1f)
            assertEquals(bounds.first().bottom, it.bottom, 1f)
        }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left + 1f) }
        searchNode.performSemanticsAction(SemanticsActions.RequestFocus)
        searchNode.assertIsFocused()
        backNode.performClick()
        searchNode.performClick()
        rule.runOnIdle { assertEquals(1, back); assertEquals(1, search) }
    }

    @Test fun shellAndModeTargetsAcrossResponsiveMatrix() {
        val widths = listOf(360, 390, 430, 600)
        val heights = listOf(740, 844, 932, 900)
        var index by mutableIntStateOf(0)
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(true)
        var pixelsPerDp = 1f
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val outer = LocalDensity.current
                // Fit the requested logical viewport on the test device without clipping it.
                val fittedDensity = minOf(outer.density,
                    maxWidth.value * outer.density / widths[index],
                    maxHeight.value * outer.density / heights[index])
                CompositionLocalProvider(LocalDensity provides Density(fittedDensity, scale)) {
                    LadiesTheme(darkTheme = dark) {
                        pixelsPerDp = fittedDensity
                        Column(Modifier.size(widths[index].dp, heights[index].dp)) {
                            AppHeader("Gespeicherte Suchergebnisse", onBack = {}) {
                                ShellIconButton(onClick = {}) {
                                    Icon(Icons.Default.Search, contentDescription = "Suche öffnen")
                                }
                            }
                            DiscoverModeSwitcher(DiscoverMode.Grid, {}, Modifier)
                            Spacer(Modifier.weight(1f))
                            RootNavigation(RootDestination.Discover, true, false, {})
                        }
                    }
                }
            }
        }
        for (i in widths.indices) for (font in listOf(1f, 1.3f, 2f)) for (theme in listOf(true, false)) {
            rule.runOnIdle { index = i; scale = font; dark = theme }
            val min = rule.runOnIdle { 48f * pixelsPerDp - 1f }
            val back = rule.onNodeWithContentDescription("Zurück").fetchSemanticsNode().boundsInRoot
            val search = rule.onNodeWithContentDescription("Suche öffnen").fetchSemanticsNode().boundsInRoot
            assertTrue(back.width >= min && back.height >= min && search.width >= min && search.height >= min)
            assertTrue(back.right <= search.left + 1f)
            rule.onNodeWithText(DiscoverMode.Grid.title).assertIsSelected()
            rule.onNodeWithText(RootDestination.Discover.title).assertIsSelected()
            val modes = DiscoverMode.entries.map { rule.onNodeWithText(it.title).fetchSemanticsNode().boundsInRoot }
            val destinations = RootDestination.entries.map { rule.onNodeWithText(it.title).fetchSemanticsNode().boundsInRoot }
            for (bounds in listOf(modes, destinations)) {
                bounds.forEach {
                    assertTrue("width=${widths[i]}, scale=$font, dark=$theme", it.width >= min && it.height >= min)
                    assertEquals(bounds.first().top, it.top, 1f)
                    assertEquals(bounds.first().bottom, it.bottom, 1f)
                }
                bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left + 1f) }
            }
            assertTrue(back.bottom <= modes.first().top + 1f)
            assertTrue(modes.first().bottom <= destinations.first().top + 1f)
        }
    }
}
