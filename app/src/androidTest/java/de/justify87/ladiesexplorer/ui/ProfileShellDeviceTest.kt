package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import de.justify87.ladiesexplorer.core.Preference
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic native UI probes; no source access or database writes. */
class ProfileShellDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun readingChromeGrowsBodyWithoutResizingHeroAndReturnsToRoot() {
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        var heroViewport = androidx.compose.ui.unit.IntSize.Zero
        lateinit var scrollTo: (Int, Int) -> Unit
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                scrollTo = { index, offset -> scope.launch { list.scrollToItem(index, offset) } }
                ProfileShell(Modifier.size(360.dp, 600.dp), list, "hero",
                    hasRail = true, notices = emptyList(), noticeOwner = "fixture",
                    header = { presentation, _, _ -> Text(if (presentation.reading) "Lesen" else "Hero") },
                    rail = { Box(Modifier.fillMaxSize().testTag("reading-rail")) },
                    rootNavigation = { Box(Modifier.fillMaxWidth().height(64.dp).testTag("reading-root")) },
                    heroLeadingMeasure = { Box(Modifier.height(48.dp)) },
                ) { presentation ->
                    SideEffect { heroViewport = presentation.heroViewport }
                    LazyColumn(Modifier.fillMaxSize().testTag("reading-body"), state = list,
                        contentPadding = PaddingValues(bottom = presentation.readingExtraSpace)) {
                        item("discover-context") { Box(Modifier.height(48.dp)) }
                        item("hero") { Box(Modifier.fillMaxWidth().height(presentation.heroHeight).testTag("reading-hero")) }
                        item("profile-heading") { Text("Profil", Modifier.height(64.dp)) }
                        items(20, key = { "detail/$it" }) { Text("Detail $it", Modifier.height(60.dp)) }
                    }
                }
            }
        }
        val body = rule.onNodeWithTag("reading-body")
        val hero = rule.onNodeWithTag("reading-hero")
        val initialBody = body.fetchSemanticsNode().boundsInRoot
        val initialHero = hero.fetchSemanticsNode().boundsInRoot
        val size = rule.runOnIdle { heroViewport }
        rule.onNodeWithTag("reading-root").assertIsDisplayed()
        body.performScrollToKey("detail/4")
        rule.onNodeWithText("Lesen").assertIsDisplayed()
        rule.onNodeWithTag("reading-root").assertIsNotDisplayed()
        assertTrue(body.fetchSemanticsNode().boundsInRoot.height > initialBody.height)
        rule.runOnIdle {
            assertEquals(size, heroViewport)
            assertEquals("detail/4", list.layoutInfo.visibleItemsInfo.first().key)
        }
        rule.runOnIdle { scrollTo(7, 5) }
        rule.waitForIdle()
        val anchor = rule.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Lesen").assertIsDisplayed()
        rule.onNodeWithTag("reading-root").assertIsNotDisplayed()
        rule.runOnIdle {
            assertEquals(anchor, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
            assertEquals(size, heroViewport)
        }
        body.performScrollToKey("discover-context")
        rule.onNodeWithTag("reading-root").assertIsDisplayed()
        rule.onNodeWithText("Hero").assertIsDisplayed()
        assertEquals(initialBody, body.fetchSemanticsNode().boundsInRoot)
        assertEquals(initialHero, hero.fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { assertEquals(size, heroViewport) }
    }

    @Test fun standaloneNeutralAndFavoriteRemainIndependentAndBusyBlocksWrites() {
        var preference by mutableStateOf(Preference.LIKE)
        var favorite by mutableStateOf(false)
        var busy by mutableStateOf(false)
        val decisions = mutableListOf<Preference>()
        rule.setContent {
            MaterialTheme {
                ProfileActionRail(
                    de.justify87.ladiesexplorer.core.PersonSummary(1, "Fixture", preference, favorite, 1u, ""),
                    busy, onFavorite = { favorite = !favorite },
                    onPreference = { _, value -> decisions.add(value); preference = value },
                )
            }
        }
        rule.onNodeWithText("Favorit").performClick()
        rule.onNodeWithText("Neutral").performClick()
        rule.runOnIdle {
            assertTrue(favorite)
            assertEquals(Preference.NONE, preference)
            assertEquals(listOf(Preference.NONE), decisions)
            busy = true
        }
        rule.onNodeWithText("Gefällt").assertIsNotEnabled()
        rule.onNodeWithText("Nein").assertIsNotEnabled()
        rule.onNodeWithText("Favorit").assertIsNotEnabled()
    }

    @Test fun fixedHeaderAndRailLeaveARealViewportWithLongWarningsAndLargeType() {
        var actionCount = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    ProfileShell(Modifier.size(300.dp, 230.dp), rememberLazyListState(), "hero",
                        hasRail = true,
                        notices = listOf(ProfileNotice("Fehler", "Langer synthetischer Fehler ".repeat(80),
                            "Erneut laden", invoke = { actionCount++ }, warning = true)),
                        noticeOwner = "fixture",
                        header = { _, compactNotice, onNotice ->
                            Row(Modifier.fillMaxSize().testTag("header"), verticalAlignment = Alignment.CenterVertically) {
                                Text("Profil", Modifier.weight(1f))
                                if (compactNotice != null) ProfileStatusButton(compactNotice, onNotice)
                            }
                        }, rail = { Box(Modifier.fillMaxSize().testTag("rail")) },
                    ) { presentation ->
                        LazyColumn(Modifier.fillMaxSize().testTag("body")) {
                            item(key = "inline-rail") {
                                check(presentation.budget.railInContent)
                                TextButton(onClick = { actionCount++ }, Modifier.heightIn(min = 48.dp)) {
                                    Text("Inline-Aktion")
                                }
                            }
                            item(key = "hero") {
                                ProfileMediaHero(emptyList(), presentation.heroHeight,
                                    actionsEnabled = false, isCurrent = { false },
                                    selection = remember { ProfileMediaSelection() }, actionToken = null, compact = true)
                            }
                            item(key = "detail") { BasicText("Profilinhalt", Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }
        val header = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val body = rule.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        assertTrue(body.height > 0f)
        assertTrue(body.top >= header.bottom - 1f)
        rule.onNodeWithTag("rail").assertDoesNotExist()
        rule.onNodeWithText("Inline-Aktion").assertIsDisplayed()
        rule.onNodeWithTag("body").performScrollToKey("detail")
        rule.onNodeWithText("Profilinhalt").assertIsDisplayed()
        rule.onNodeWithTag("body").performScrollToKey("inline-rail")
        rule.onNodeWithText("Inline-Aktion").performClick()
        rule.onNodeWithContentDescription("Status und Hinweise: Fehler").performClick()
        rule.onNodeWithText("Status und Hinweise").assertExists()
        // The long warning is scrollable; a dialog action need not fit its first viewport.
        rule.onNodeWithText("Erneut laden").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(2, actionCount) }
    }

    @Test fun statusChangesPreserveHeroViewportAndAnchorAndBindActionsToCurrentNotice() {
        var notices by mutableStateOf(emptyList<ProfileNotice>())
        var owner by mutableIntStateOf(1)
        var standalone by mutableStateOf(true)
        var retryCount = 0
        var stopCount = 0
        var measurements = 0
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        val warning = ProfileNotice("Profilfehler", "Lokaler Fehler", "Erneut laden",
            enabled = false, invoke = { retryCount++ }, warning = true)
        val archive = ProfileNotice("Archiv", "Archiv läuft", "Stoppen", invoke = { stopCount++ })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (standalone) 1f else 2f)) {
                LadiesTheme(darkTheme = !standalone) {
                    list = rememberLazyListState()
                    ProfileShell(Modifier.size(360.dp, if (standalone) 600.dp else 230.dp), list, "hero",
                        hasRail = !standalone, notices = notices, noticeOwner = owner,
                        header = { _, indicator, onNotice ->
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Profil", Modifier.weight(1f))
                                if (indicator != null) ProfileStatusButton(indicator, onNotice)
                            }
                        },
                        rail = { Box(Modifier.fillMaxSize()) },
                    ) { presentation ->
                        LazyColumn(Modifier.fillMaxSize().testTag("status-body"), state = list) {
                            item("hero") {
                                Box(Modifier.fillMaxWidth().height(presentation.heroHeight)
                                    .testTag("status-hero").onSizeChanged { measurements++ })
                            }
                            items(20, key = { "detail/$it" }) { Text("Detail $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        repeat(2) {
            val body = rule.onNodeWithTag("status-body")
            body.performScrollToKey("hero")
            val hero = rule.onNodeWithTag("status-hero")
            val bounds = hero.fetchSemanticsNode().boundsInRoot
            val viewport = body.fetchSemanticsNode().boundsInRoot
            val beforeMeasurements = rule.runOnIdle { measurements }
            rule.runOnIdle { notices = listOf(archive, warning) }
            rule.onNodeWithContentDescription("Status und Hinweise: Profilfehler").assertExists()
            assertEquals(bounds, hero.fetchSemanticsNode().boundsInRoot)
            assertEquals(viewport, body.fetchSemanticsNode().boundsInRoot)
            rule.runOnIdle { assertEquals(beforeMeasurements, measurements) }
            body.performScrollToKey("detail/4")
            val anchor = rule.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            rule.onNodeWithContentDescription("Status und Hinweise: Profilfehler").performClick()
            rule.onNodeWithText("Erneut laden").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText("Stoppen").performScrollTo().assertIsEnabled()
            rule.runOnIdle { owner++ }
            rule.onNodeWithText("Status und Hinweise").assertDoesNotExist()
            rule.runOnIdle { assertEquals(0, retryCount); assertEquals(it, stopCount) }
            rule.onNodeWithContentDescription("Status und Hinweise: Profilfehler").performClick()
            rule.onNodeWithText("Stoppen").performScrollTo().performClick()
            rule.runOnIdle {
                assertEquals(it + 1, stopCount)
                assertEquals(0, retryCount)
                notices = emptyList()
            }
            rule.runOnIdle {
                assertEquals(anchor, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
            }
            assertEquals(viewport, body.fetchSemanticsNode().boundsInRoot)
            body.performScrollToKey("hero")
            assertEquals(bounds, hero.fetchSemanticsNode().boundsInRoot)
            rule.runOnIdle { standalone = false }
        }
    }

    @Test fun inlineIdentityHandoffDoesNotResizeHeaderOrRail() {
        lateinit var jump: (Int, Int) -> Unit
         rule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                jump = { index, offset -> scope.launch { list.scrollToItem(index, offset) } }
                ProfileShell(Modifier.size(360.dp, 600.dp), list, "hero",
                    hasRail = true, notices = emptyList(), noticeOwner = "fixture",
                    header = { presentation, _, _ ->
                        Box(Modifier.fillMaxSize().testTag("header")) {
                            Text(if (presentation.identityInHeader) "B" else "A")
                        }
                    }, rail = { Box(Modifier.fillMaxSize().testTag("rail")) },
                ) { presentation ->
                    LazyColumn(Modifier.fillMaxSize().testTag("body"), state = list) {
                        item(key = "hero") {
                            Box(Modifier.fillMaxWidth().height(presentation.heroHeight))
                        }
                        item(key = "profile-heading") { Text("Fixture", Modifier.height(90.dp)) }
                        items(20) { Text("Detail $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        rule.onNodeWithText("A").assertExists()
        val header = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val rail = rule.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { jump(1, 0) }
        rule.waitForIdle()
        rule.onNodeWithText("Fixture").assertIsDisplayed()
        rule.onNodeWithText("A").assertExists()
        rule.runOnIdle { jump(2, 0) }
        rule.waitForIdle()
        rule.onNodeWithText("B").assertExists()
        assertEquals(header, rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot)
        assertEquals(rail, rule.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { jump(0, 0) }
        rule.waitForIdle()
        rule.onNodeWithText("A").assertExists()
    }

    @Test fun actualDecisionRailUsesSameControlsBeforeAndAfterScrollAndAllowsUndoWhenEmpty() {
        lateinit var jump: () -> Unit
        lateinit var clear: () -> Unit
        val actions = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                var empty by remember { mutableStateOf(false) }
                clear = { empty = true }
                jump = { scope.launch { list.scrollToItem(1) } }
                val deck = SwipeDeckToken(null, 1)
                val context = SwipeControlsContext(deck, if (empty) null else SwipeCardToken(deck, 4, 1),
                    if (empty) null else SwipeActionToken(null, 1, 4, 1, 11, 1,
                        Preference.NONE, false, emptyList()),
                    true, true, AvailabilityMode.All, true, true)
                ProfileShell(Modifier.size(360.dp, 600.dp), list, "hero", hasRail = true,
                    notices = emptyList(), noticeOwner = context,
                    header = { presentation, _, _ -> Text(if (presentation.identityInHeader) "B" else "A") },
                    rail = { SwipeDecisionControls(context, if (empty) null else "Fixture",
                        { actions.add("undo") }, { actions.add("skip") }, { actions.add(it.name) }) },
                ) { presentation ->
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        item(key = "hero") {
                            Box(Modifier.fillMaxWidth().height(presentation.heroHeight))
                        }
                        items(20) { Text("Detail $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        rule.onNodeWithText("A").assertExists()
        rule.onNode(hasText("Gefällt") or hasContentDescription("Gefällt")).performClick()
        rule.runOnIdle { jump() }
        rule.onNodeWithText("B").assertExists()
        rule.onNode(hasText("Nein") or hasContentDescription("Nein")).performClick()
        rule.onNode(hasText("Später") or hasContentDescription("Später")).performClick()
        rule.runOnIdle { clear() }
        rule.onNode(hasText("Nein") or hasContentDescription("Nein")).assertIsNotEnabled()
        rule.onNode(hasText("Später") or hasContentDescription("Später")).assertIsNotEnabled()
        rule.onNode(hasText("Gefällt") or hasContentDescription("Gefällt")).assertIsNotEnabled()
        rule.onNode(hasText("Rückgängig") or hasContentDescription("Rückgängig")).performClick()
        rule.runOnIdle { assertEquals(listOf("LIKE", "DISLIKE", "skip", "undo"), actions) }
    }

    @Test fun imageTapZoneAndVideoActionAreSeparateNativeTargets() {
        rule.setContent {
            MaterialTheme {
                val selection = remember { ProfileMediaSelection() }
                Box(Modifier.width(360.dp)) {
                    ProfileMediaHero(
                        media = listOf(ProfileMediaItem("IMAGE", "", null, 0u),
                            ProfileMediaItem("VIDEO", "https://example.invalid/video", null, 1u)),
                        height = 420.dp, actionsEnabled = false,
                        isCurrent = { false }, selection = selection,
                        actionToken = null, compact = false,
                    )
                }
            }
        }
        val next = rule.onNodeWithContentDescription("Nächstes Medium").fetchSemanticsNode().boundsInRoot
        val feedback = rule.onNodeWithText("Kein Bild verfügbar").fetchSemanticsNode().boundsInRoot
        assertTrue(next.left >= feedback.right - 1f)
        rule.onNodeWithContentDescription("Nächstes Medium").performClick()
        rule.onNodeWithContentDescription("Video abspielen").assertExists().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Vorheriges Medium").performClick()
        rule.onNodeWithText("Kein Bild verfügbar").assertExists()
    }
}
