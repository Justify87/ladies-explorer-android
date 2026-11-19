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
import kotlinx.coroutines.CoroutineScope
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
                                if (compactNotice) ProfileStatusButton(onNotice)
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
        rule.onNodeWithContentDescription("Status und Hinweise").performClick()
        rule.onNodeWithText("Status und Hinweise").assertExists()
        // The long warning is scrollable; a dialog action need not fit its first viewport.
        rule.onNodeWithText("Erneut laden").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, actionCount) }
    }

    @Test fun measuredIdentityHandoffDoesNotResizeHeaderOrRail() {
        lateinit var jump: (Int) -> Unit
        rule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                jump = { index -> scope.launch { list.scrollToItem(index) } }
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
                            Box(Modifier.fillMaxWidth().height(presentation.heroHeight)) {
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(90.dp)
                                    .onSizeChanged { measured(it.height) })
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
        rule.runOnIdle { jump(1) }
        rule.waitForIdle()
        rule.onNodeWithText("B").assertExists()
        assertEquals(header, rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot)
        assertEquals(rail, rule.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { jump(0) }
        rule.waitForIdle()
        rule.onNodeWithText("A").assertExists()
    }

    @Test fun imageTapZoneAndVideoActionAreSeparateNativeTargets() {
        rule.setContent {
            MaterialTheme {
                val selection = remember { ProfileMediaSelection() }
                Box(Modifier.width(360.dp)) {
                    ProfileMediaHero(
                        media = listOf(ProfileMediaItem("IMAGE", "", null, 0u),
                            ProfileMediaItem("VIDEO", "https://example.invalid/video", null, 1u)),
                        height = 420.dp, originalUrl = null, actionsEnabled = false,
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
