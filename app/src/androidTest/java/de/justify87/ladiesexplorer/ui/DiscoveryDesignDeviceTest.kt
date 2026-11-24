package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.DiscoveryCard
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
                val context = SwipeControlsContext(deck, SwipeCardToken(deck, 4, 1),
                    SwipeActionToken(null, 1, 4, 1, 11, 1, Preference.NONE, false, emptyList()),
                    true, true, AvailabilityMode.All, false, true)
                Column(Modifier.fillMaxSize()) {
                    ProfileShell(Modifier.weight(1f), list, "hero", rootHeight = 64.dp,
                        hasRail = true, notices = emptyList(), noticeOwner = context,
                        heroLeadingHeight = discoveryLeadingHeight(LocalDensity.current.fontScale).dp,
                        header = { _, _, _ -> Row(Modifier.fillMaxSize().testTag("header")) {
                            Text("Entdecken", Modifier.weight(1f))
                            AvailabilityQuickChoice(AvailabilityMode.All, true, deck, {})
                        } },
                        rail = {
                            Box(Modifier.fillMaxSize().testTag("rail")) {
                                SwipeDecisionControls(context, "Fixture", {}, {}, {})
                            }
                        }) { presentation ->
                        LazyColumn(Modifier.fillMaxSize(), state = list,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item(key = "discover-context") {
                                Box(Modifier.padding(horizontal = 16.dp).testTag("context")) {
                                    DiscoverModeSwitcher(DiscoverMode.Swipe, {}, Modifier)
                                }
                            }
                            item(key = "hero") {
                                Box(Modifier.padding(horizontal = 12.dp).testTag("hero")) {
                                    ProfileMediaHero(emptyList(), presentation.heroHeight,
                                        actionsEnabled = false, isCurrent = { false },
                                        selection = remember { ProfileMediaSelection() },
                                        favorite = false, onFavorite = {},
                                        actionToken = null, compact = presentation.budget.compactGallery)
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
        val favorite = rule.onNodeWithContentDescription("Als Favorit speichern").fetchSemanticsNode().boundsInRoot
        assertTrue(favorite.width >= 48 * pxPerDp - 1f && favorite.height >= 48 * pxPerDp - 1f)
        assertTrue(favorite.top >= hero.top - 1f && favorite.right <= hero.right + 1f)
        rule.onNodeWithText("Fixture").assertDoesNotExist()
        listOf("Undo", "Nein", "Später", "Gefällt").forEach { label ->
            val bounds = rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48 * pxPerDp - 1f)
            assertTrue(bounds.height >= 48 * pxPerDp - 1f)
        }
    }

    @Test fun gridHorizontalDecisionsTapAndVerticalScrollAreExclusive() {
        val actions = mutableListOf<String>()
        lateinit var scroll: androidx.compose.foundation.lazy.LazyListState
        var preference by mutableStateOf(Preference.NONE)
        var owner by mutableIntStateOf(1)
        rule.setContent {
            MaterialTheme {
                scroll = rememberLazyListState()
                LazyColumn(Modifier.width(320.dp).height(320.dp), state = scroll) {
                    item {
                        ProfileGridCard("Fixture", "30", null, null, true,
                            onOpen = { actions.add("open") },
                            modifier = Modifier.testTag("card").width(240.dp), place = "Ort",
                            preference = preference, decisionOwner = owner,
                            onDecision = { actions.add(it.name); preference = it })
                    }
                    items(20) { Text("Detail", Modifier.height(60.dp)) }
                }
            }
        }
        val card = rule.onNodeWithTag("card")
        rule.onNodeWithText("Fixture").assertDoesNotExist()
        rule.onNodeWithText("Ort").assertDoesNotExist()
        rule.onNodeWithText("30").assertExists()
        card.performTouchInput { click(center) }
        card.performTouchInput { swipe(Offset(width * .15f, center.y), Offset(width * .85f, center.y)) }
        card.performTouchInput { swipe(Offset(width * .85f, center.y), Offset(width * .15f, center.y)) }
        card.performTouchInput { down(center); moveTo(center + Offset(width * .1f, 0f)); up() }
        card.performTouchInput { down(center); moveTo(center + Offset(width * .4f, 0f)); cancel() }
        card.performTouchInput { down(Offset(width * .15f, center.y)); moveTo(Offset(width * .85f, center.y)) }
        rule.runOnIdle { owner++ }
        card.performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("open", "LIKE", "DISLIKE"), actions) }
        rule.onNodeWithContentDescription("Fixture", substring = true).assertExists()
        card.performTouchInput { swipe(Offset(center.x, height * .8f), Offset(center.x, height * .1f)) }
        rule.runOnIdle {
            assertEquals(listOf("open", "LIKE", "DISLIKE"), actions)
            assertTrue(scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 0)
        }
    }

    @Test fun gridUndoUsesCurrentReceiptAndRemainsAvailableWhenLocalCardDisappears() {
        val receipt = GridUndoReceipt(null, 11L, 12L, Preference.NONE, Preference.LIKE)
        var state by mutableStateOf(DiscoverUiState(loading = false, actionSurface = ProfileActionSurface.GRID,
            actionGeneration = 5, gridUndo = receipt))
        val undos = mutableListOf<GridUndoAction>()
        rule.setContent {
            LadiesTheme {
                DiscoveryGridScreen(state, SearchUiState(), onLoadMore = {},
                    onDecision = { _, _ -> }, onUndo = { undos.add(it); state = state.copy(gridUndo = null) },
                    onProfile = {}, chrome = {}, onCancelSearch = {},
                    modifier = Modifier.width(360.dp).height(480.dp))
            }
        }
        val undo = rule.onNodeWithText("Letzte Entscheidung rückgängig")
        rule.onNodeWithText("Keine Treffer.").assertExists()
        rule.runOnIdle { state = state.copy(busy = true) }
        undo.assertIsNotEnabled()
        rule.runOnIdle { state = state.copy(busy = false, actionGeneration = 6) }
        undo.performClick()
        rule.runOnIdle { assertEquals(listOf(GridUndoAction(receipt, 6)), undos) }
        undo.assertDoesNotExist()
    }

    @Test fun quickChoiceKeepsRunOwnerAndAllOptionsAcrossModesAndThemes() {
        var owner by mutableIntStateOf(1)
        var dark by mutableStateOf(true)
        var mode by mutableStateOf(DiscoverMode.Swipe)
        var availability by mutableStateOf(AvailabilityMode.All)
        val choices = mutableListOf<AvailabilityMode>()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                LadiesTheme(darkTheme = dark) {
                    Column(Modifier.width(360.dp)) {
                        Row {
                            Text("Entdecken", Modifier.weight(1f))
                            AvailabilityQuickChoice(availability, true, owner, {
                                choices.add(it)
                                availability = it
                            })
                        }
                        DiscoverModeSwitcher(mode, { mode = it }, Modifier)
                    }
                }
            }
        }
        rule.onNodeWithText("Alle ▾").performClick()
        rule.onNodeWithText("Heute").performClick()
        rule.onNodeWithText("Heute ▾").assertExists()
        rule.onNodeWithText("Heute ▾").performClick()
        rule.runOnIdle { owner++ }
        rule.onNodeWithText("Jetzt").assertDoesNotExist()
        rule.runOnIdle { dark = false }
        rule.onNodeWithText("Grid").performClick().assertIsSelected()
        rule.onNodeWithText("Karte").performClick().assertIsSelected()
        rule.onNodeWithText("Heute ▾").performClick()
        rule.onNodeWithText("Jetzt").performClick()
        rule.runOnIdle { assertEquals(listOf(AvailabilityMode.Today, AvailabilityMode.Now), choices) }
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
                        onLoadMore = {}, onDecision = { _, _ -> }, onUndo = {}, onProfile = {},
                        chrome = {
                            DiscoverModeSwitcher(DiscoverMode.Grid, { actions.add(it) }, Modifier)
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
        grid.performScrollToIndex(2)
        rule.onNodeWithText("Keine Treffer.").assertIsDisplayed()
        grid.performScrollToIndex(0)
        rule.onNodeWithText(DiscoverMode.Swipe.title).performClick()
        rule.runOnIdle { assertEquals(listOf(DiscoverMode.Swipe), actions) }
    }

    @Test fun gridReturnKeepsVisibleListingWhenEarlierMembersDisappear() {
        var route by mutableStateOf(ShellRoute().select(DiscoverMode.Grid))
        var rows by mutableStateOf((1L..24L).map { id ->
            DiscoveryCard(id, id, "Fixture $id", "Ort", null, null, null, Preference.NONE,
                false, false, false, false, null)
        })
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            LadiesTheme {
                holder.SaveableStateProvider(route.stateKey) {
                    if (route.discovery == DiscoverMode.Grid) {
                        DiscoveryGridScreen(
                            discovery = DiscoverUiState(localCards = rows, loading = false),
                            search = SearchUiState(), onLoadMore = {}, onDecision = { _, _ -> }, onUndo = {},
                            onProfile = { route = route.openSwipe() },
                            chrome = {}, onCancelSearch = {},
                            modifier = Modifier.width(360.dp).height(480.dp),
                        )
                    } else Text("Swipe")
                }
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(7)
        val profile = rule.onNodeWithContentDescription("Fixture 8")
        profile.assertIsDisplayed()
        val top = profile.fetchSemanticsNode().boundsInRoot.top
        profile.performClick()
        rule.onNodeWithText("Swipe").assertIsDisplayed()
        rule.runOnIdle {
            rows = rows.drop(2)
            route = route.back()
        }
        profile.assertIsDisplayed()
        assertEquals(top, profile.fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test fun compactGridStatusAndAccessibleActionsAcrossThemesAndFontScale() {
        val name = "Sehr langer synthetischer Profilname"
        var font by mutableFloatStateOf(1.3f)
        var dark by mutableStateOf(true)
        var preference by mutableStateOf(Preference.NONE)
        val decisions = mutableListOf<Preference>()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                LadiesTheme(darkTheme = dark) {
                    ProfileGridCard(name, "30", null, null, true, {},
                        modifier = Modifier.width(160.dp).testTag("compact-card"),
                        preference = preference, decisionOwner = preference,
                        onDecision = { decisions.add(it); preference = it })
                }
            }
        }
        for (scale in listOf(1.3f, 2f)) {
            rule.runOnIdle { font = scale; dark = scale == 1.3f }
            val card = rule.onNodeWithContentDescription(name)
            val bounds = card.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= bounds.width / 1.03f - 1f)
            rule.onNodeWithText(name).assertDoesNotExist()
            rule.onNodeWithText("30").assertExists()
            for ((label, value) in listOf("Gefällt" to Preference.LIKE, "Gefällt nicht" to Preference.DISLIKE,
                "Bewertung entfernen" to Preference.NONE)) {
                val action = card.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
                rule.runOnIdle { assertTrue(action.action()) }
                card.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, gridPreferenceLabel(value)))
            }
        }
        rule.runOnIdle { assertEquals(listOf(Preference.LIKE, Preference.DISLIKE, Preference.NONE,
            Preference.LIKE, Preference.DISLIKE, Preference.NONE), decisions) }
    }
}
