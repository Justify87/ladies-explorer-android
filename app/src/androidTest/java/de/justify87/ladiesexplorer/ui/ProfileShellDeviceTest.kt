package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import de.justify87.ladiesexplorer.core.Preference
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic native probes. No source request, real profile, database mutation or intent. */
class ProfileShellDeviceTest {
    @get:Rule val rule = createComposeRule()

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
                    ) { _, _ ->
                        Box(Modifier.fillMaxSize().testTag("body")) { BasicText("Profilinhalt") }
                    }
                }
            }
        }
        val header = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val body = rule.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        assertTrue(body.height > 0f)
        assertTrue(body.top >= header.bottom - 1f)
        rule.onNodeWithTag("rail").assertDoesNotExist()
        rule.onNodeWithContentDescription("Status und Hinweise: Fehler").performClick()
        rule.onNodeWithText("Status und Hinweise").assertExists()
        // The long warning is scrollable; a dialog action need not fit its first viewport.
        rule.onNodeWithText("Erneut laden").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, actionCount) }
    }

    @Test fun measuredIdentityHandoffDoesNotResizeHeaderOrRail() {
        lateinit var jump: (Int, Int) -> Unit
        var heroPx = 0
        var identityPx = 0
        rule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                jump = { index, offset -> scope.launch { list.scrollToItem(index, offset) } }
                ProfileShell(Modifier.size(360.dp, 600.dp), list, "hero", rootHeight = 80.dp,
                    hasRail = true, notices = emptyList(), noticeOwner = "fixture",
                    header = { presentation, _, _ ->
                        Box(Modifier.fillMaxSize().testTag("header")) {
                            Text(if (presentation.identityInHeader) "B" else "A")
                        }
                    }, rail = { Box(Modifier.fillMaxSize().testTag("rail")) },
                ) { presentation, measured ->
                    LazyColumn(Modifier.fillMaxSize().testTag("body"), state = list) {
                        item(key = "hero") {
                            Box(Modifier.fillMaxWidth().height(presentation.heroHeight).onSizeChanged { heroPx = it.height }) {
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp)
                                    .onSizeChanged { identityPx = it.height; measured(it.height) })
                            }
                        }
                        items(20) { Text("Detail $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        rule.onNodeWithText("A").assertExists()
        val header = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val rail = rule.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot
        // Cross the measured overlay edge before the entire hero disappears.
        rule.runOnIdle {
            assertTrue(heroPx > identityPx && identityPx > 0)
            jump(0, heroPx - identityPx + 1)
        }
        rule.waitForIdle()
        rule.onNodeWithText("B").assertExists()
        rule.runOnIdle { jump(1, 0) }
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
                val context = SwipeMenuContext(deck, if (empty) null else SwipeCardToken(deck, 4, 1),
                    if (empty) null else SwipeActionToken(null, 1, 4, 1, 11, 1,
                        Preference.NONE, false, emptyList()),
                    true, true, true, AvailabilityMode.All, true, true)
                ProfileShell(Modifier.size(360.dp, 600.dp), list, "hero", hasRail = true,
                    notices = emptyList(), noticeOwner = context,
                    header = { presentation, _, _ -> Text(if (presentation.identityInHeader) "B" else "A") },
                    rail = { SwipeDecisionControls(context, if (empty) null else "Fixture",
                        { actions.add("undo") }, { actions.add("skip") }, { actions.add(it.name) }) },
                ) { presentation, measured ->
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        item(key = "hero") {
                            Box(Modifier.fillMaxWidth().height(presentation.heroHeight)) {
                                Box(Modifier.align(Alignment.BottomCenter).height(80.dp).fillMaxWidth()
                                    .onSizeChanged { measured(it.height) })
                            }
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
        rule.onNode(hasText("Undo") or hasContentDescription("Undo")).performClick()
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
                        isCurrent = { false }, selection = selection, identity = HeroIdentity("Fixture", place = "Ort"),
                        actionToken = null, compact = false, headerOwnsIdentity = false, onIdentityMeasured = {},
                    )
                }
            }
        }
        val next = rule.onNodeWithContentDescription("Nächstes Medium").fetchSemanticsNode().boundsInRoot
        val feedback = rule.onNodeWithText("Kein Bild verfügbar").fetchSemanticsNode().boundsInRoot
        assertTrue(next.left >= feedback.right - 1f)
        rule.onNodeWithContentDescription("Nächstes Medium").performClick()
        rule.onNodeWithText("Video extern öffnen").assertExists().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Vorheriges Medium").performClick()
        rule.onNodeWithText("Kein Bild verfügbar").assertExists()
    }
}
