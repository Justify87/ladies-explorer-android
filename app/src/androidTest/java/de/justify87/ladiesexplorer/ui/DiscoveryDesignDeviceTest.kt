package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiscoveryDesignDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun contextHeroAndRailHaveIndependentMeasuredBounds() {
        var pxPerDp = 1f
        rule.setContent {
            MaterialTheme {
                pxPerDp = LocalDensity.current.density
                val list = rememberLazyListState()
                val deck = SwipeDeckToken(null, 1)
                val context = SwipeMenuContext(deck, SwipeCardToken(deck, 4, 1),
                    SwipeActionToken(null, 1, 4, 1, 11, 1, Preference.NONE, false, emptyList()),
                    true, true, true, AvailabilityMode.All, false, true)
                Column(Modifier.fillMaxSize()) {
                    ProfileShell(Modifier.weight(1f), list, "hero", rootHeight = 64.dp,
                        hasRail = true, notices = emptyList(), noticeOwner = context,
                        heroLeadingHeight = discoveryLeadingHeight(LocalDensity.current.fontScale).dp,
                        header = { _, _, _ -> Box(Modifier.fillMaxSize().testTag("header")) { Text("Ladies") } },
                        rail = {
                            Box(Modifier.fillMaxSize().testTag("rail")) {
                                SwipeDecisionControls(context, "Fixture", {}, {}, {})
                            }
                        }) { presentation, measured ->
                        LazyColumn(Modifier.fillMaxSize(), state = list,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item(key = "discover-context") {
                                Box(Modifier.padding(horizontal = 16.dp).testTag("context")) {
                                    DiscoveryChrome("Lokaler Bestand", AvailabilityMode.All, true, deck,
                                        DiscoverMode.Swipe, {}, {})
                                }
                            }
                            item(key = "hero") {
                                Box(Modifier.padding(horizontal = 12.dp).testTag("hero")) {
                                    ProfileMediaHero(emptyList(), presentation.heroHeight,
                                        actionsEnabled = false, isCurrent = { false },
                                        selection = remember { ProfileMediaSelection() },
                                        identity = HeroIdentity("Fixture", place = "Ort"),
                                        actionToken = null, compact = presentation.budget.compactGallery,
                                        headerOwnsIdentity = presentation.identityInHeader, onIdentityMeasured = measured)
                                }
                            }
                            items(20) { Text("Detail $it", Modifier.height(48.dp)) }
                        }
                    }
                    Box(Modifier.fillMaxWidth().testTag("root-nav")) {
                        RootNavigation(RootDestination.Discover, true, false, {})
                    }
                }
            }
        }
        val header = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val context = rule.onNodeWithTag("context").fetchSemanticsNode().boundsInRoot
        val hero = rule.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        val rail = rule.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot
        val nav = rule.onNodeWithTag("root-nav").fetchSemanticsNode().boundsInRoot
        assertTrue(context.top >= header.bottom - 1f)
        assertTrue(hero.top >= context.bottom - 1f)
        assertTrue(hero.height > 0f && hero.bottom <= rail.top + 1f)
        assertTrue(rail.bottom <= nav.top + 1f)
        listOf("Undo", "Nein", "Später", "Gefällt").forEach { label ->
            val bounds = rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48 * pxPerDp - 1f)
            assertTrue(bounds.height >= 48 * pxPerDp - 1f)
        }
    }

    @Test fun modeSelectionAndGridLikeAreExplicitIndependentActions() {
        val actions = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                var active by remember { mutableStateOf(DiscoverMode.Swipe) }
                Column(Modifier.width(320.dp)) {
                    DiscoveryChrome("Lokaler Bestand", AvailabilityMode.All, true, "fixture", active,
                        onMode = { active = it; actions.add(it.name) }, onAvailability = {})
                    ProfileGridCard("Fixture", "30", null, null, false, true,
                        onOpen = { actions.add("open") }, onLike = { actions.add("like") },
                        modifier = Modifier.testTag("card").width(160.dp), place = "Ort")
                }
            }
        }
        rule.onNodeWithText(DiscoverMode.Swipe.title).assertIsSelected()
        rule.onNodeWithText(DiscoverMode.Grid.title).performClick().assertIsSelected()
        rule.onNodeWithContentDescription("Fixture gefällt mir").performClick()
        rule.onNodeWithTag("card").performClick()
        rule.runOnIdle { assertEquals(listOf(DiscoverMode.Grid.name, "like", "open"), actions) }
    }

    @Test fun shortGridScrollsPastChromeAndBackToModeSelection() {
        val actions = mutableListOf<DiscoverMode>()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LadiesTheme(darkTheme = true) {
                    DiscoveryGridScreen(
                        discovery = DiscoverUiState(loading = false),
                        search = SearchUiState(error = "Synthetischer Suchstatus"),
                        onRefresh = {}, onLoadMore = {}, onLike = {}, onProfile = {},
                        chrome = {
                            DiscoveryChrome("Lokaler Bestand", AvailabilityMode.All, true, "fixture",
                                DiscoverMode.Grid, { actions.add(it) }, {})
                        },
                        onCancelSearch = {},
                        modifier = Modifier.width(360.dp).height(150.dp),
                    )
                }
            }
        }
        val grid = rule.onNode(hasScrollToIndexAction())
        grid.performScrollToIndex(1)
        rule.onNodeWithText("Synthetischer Suchstatus").assertIsDisplayed()
        grid.performScrollToIndex(3)
        rule.onNodeWithText("Keine Treffer.").assertIsDisplayed()
        grid.performScrollToIndex(0)
        rule.onNodeWithText(DiscoverMode.Swipe.title).performClick()
        rule.runOnIdle { assertEquals(listOf(DiscoverMode.Swipe), actions) }
    }
}
