package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
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
}
