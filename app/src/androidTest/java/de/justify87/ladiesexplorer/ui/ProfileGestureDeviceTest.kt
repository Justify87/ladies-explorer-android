package de.justify87.ladiesexplorer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose events; no source access or database writes. */
class ProfileGestureDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun nativeGalleryClickHorizontalVerticalDiagonalAndCancelAreExclusive() {
        val decisions = mutableListOf<Preference>()
        var taps = 0
        lateinit var list: LazyListState
        var feedback = 0f
        rule.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                val gate = remember { ProfileTouchGate() }
                LazyColumn(Modifier.size(280.dp, 320.dp).admitProfileTouches(gate) { true }, state = list) {
                    item {
                        PhotoGestureSurface(owner = "first", modifier = Modifier.fillMaxWidth().height(240.dp).testTag("photo"),
                            galleryEnabled = true, onSwipe = { decisions.add(it) }, onDrag = { feedback = it }, touchGate = gate) { hit ->
                            Box(Modifier.fillMaxSize().then(hit.photo)) {
                                PhotoTapTarget("Medium", Modifier.fillMaxSize().then(hit.next)) { taps++ }
                            }
                        }
                    }
                    items(20) { Text("Detail", Modifier.height(60.dp)) }
                }
            }
        }
        val photo = rule.onNodeWithTag("photo")
        rule.onNodeWithContentDescription("Medium").performTouchInput { click(center) }
        photo.performTouchInput { swipe(Offset(width * .2f, center.y), Offset(width * .8f, center.y)) }
        photo.performTouchInput { swipe(Offset(width * .8f, center.y), Offset(width * .2f, center.y)) }
        photo.performTouchInput { down(center); moveTo(center + Offset(width * .18f, 0f)); up() }
        photo.performTouchInput { down(center); moveTo(center + Offset(width * .15f, width * .15f)); moveTo(center + Offset(width * .4f, width * .15f)); up() }
        photo.performTouchInput { down(center); advanceEventTime(1500); up() }
        photo.performTouchInput { down(center); moveTo(center + Offset(width * .35f, 0f)); cancel() }
        photo.performTouchInput { down(0, center); down(1, center + Offset(10f, 10f)); moveTo(0, center + Offset(width * .4f, 0f)); up(1); up(0) }
        rule.runOnIdle {
            assertEquals(listOf(Preference.LIKE, Preference.DISLIKE), decisions)
            assertEquals(1, taps)
            assertEquals(0f, feedback, 0f)
        }
        photo.performTouchInput { swipe(Offset(center.x, height * .8f), Offset(center.x, height * .15f)) }
        rule.runOnIdle {
            assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
            assertEquals(2, decisions.size)
            assertEquals(1, taps)
        }
    }

    @Test fun nativeControlWinsAndStandaloneHorizontalDoesNotClickGallery() {
        var decisions = 0
        var buttons = 0
        var taps = 0
        var enabled by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                val gate = remember { ProfileTouchGate() }
                PhotoGestureSurface(owner = enabled, modifier = Modifier.size(280.dp, 240.dp)
                    .testTag("photo").admitProfileTouches(gate) { true },
                    galleryEnabled = true, onSwipe = if (enabled) ({ decisions++ }) else null,
                    touchGate = gate) { hit ->
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("chrome"))
                        Row(Modifier.fillMaxWidth().weight(1f).then(hit.photo), verticalAlignment = Alignment.CenterVertically) {
                            PhotoTapTarget("Medium", Modifier.weight(.3f).fillMaxHeight().then(hit.previous), owner = enabled) { taps++ }
                            Box(Modifier.weight(.7f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = { buttons++ }, modifier = Modifier.testTag("control")) { Text("Retry") }
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("chrome").performTouchInput { swipe(Offset(width * .1f, center.y), Offset(width * .9f, center.y)) }
        val control = rule.onNodeWithTag("control")
        control.performTouchInput { click(center) }
        control.performTouchInput { down(center); moveTo(center + Offset(400f, 0f)); up() }
        val photo = rule.onNodeWithTag("photo")
        photo.performTouchInput { down(Offset(width * .1f, height * .7f)); moveTo(Offset(width * .8f, height * .7f)) }
        rule.runOnIdle { enabled = false }
        photo.performTouchInput { up() }
        rule.runOnIdle { assertEquals(1, buttons); assertEquals(0, decisions); assertEquals(0, taps) }
        photo.performTouchInput { swipe(Offset(width * .1f, height * .7f), Offset(width * .8f, height * .7f)) }
        rule.onNodeWithContentDescription("Medium").performTouchInput { click(center) }
        rule.onNodeWithContentDescription("Medium").performClick() // semantic action, not touch
        rule.runOnIdle { assertEquals(0, decisions); assertEquals(2, taps) }
    }

    @Test fun actualHeroSideTapAndSwipeDoNotInvokeEachOther() {
        val decisions = mutableListOf<Preference>()
        var favorites by mutableIntStateOf(0)
        var current by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                // Use the real geometry policy; requires at least 320dp usable width.
                Box(Modifier.requiredWidth(320.dp)) {
                    ProfileMediaHero(
                        media = listOf(ProfileMediaItem("IMAGE", "", null, 0u),
                            ProfileMediaItem("VIDEO", "", null, 1u)),
                        height = 360.dp, modifier = Modifier.testTag("hero"),
                        actionsEnabled = true, isCurrent = { current }, selection = remember { ProfileMediaSelection() },
                        identity = null, actionToken = null, compact = false, headerOwnsIdentity = false,
                        favorite = favorites % 2 == 1, onFavorite = { favorites++ },
                        onIdentityMeasured = {}, onSwipe = { decisions.add(it) })
                }
            }
        }
        val hero = rule.onNodeWithTag("hero")
        val favorite = rule.onNodeWithContentDescription("Als Favorit speichern")
        favorite.performTouchInput { click(center) }
        rule.onNodeWithContentDescription("Favorit entfernen").assertExists()
        rule.runOnIdle { assertEquals(1, favorites); assertTrue(decisions.isEmpty()); current = false }
        rule.onNodeWithContentDescription("Favorit entfernen").performClick()
        rule.runOnIdle { assertEquals(1, favorites); current = true }
        rule.onNodeWithTag("gallery-video-control").assertDoesNotExist()
        hero.performTouchInput { swipe(Offset(width * .85f, height * .6f), Offset(width * .15f, height * .6f)) }
        rule.onNodeWithTag("gallery-video-control").assertDoesNotExist()
        rule.onNodeWithText("1 / 2").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(Preference.DISLIKE), decisions) }
        rule.onNodeWithContentDescription("Nächstes Medium").performTouchInput { click(center) }
        rule.onNodeWithText("2 / 2").assertDoesNotExist()
        rule.onNodeWithTag("gallery-video-control").assertExists()
        rule.runOnIdle { assertEquals(1, decisions.size) }
    }

    @Test fun realRailTargetCannotCommitFromSecondFingerInEitherOrder() {
        var decisions = 0
        var railClicks = 0
        rule.setContent {
            MaterialTheme {
                val gate = remember { ProfileTouchGate() }
                Column(Modifier.size(280.dp, 320.dp).testTag("root").admitProfileTouches(gate) { true }) {
                    PhotoGestureSurface(owner = "fixture", modifier = Modifier.fillMaxWidth().height(240.dp).testTag("photo"),
                        onSwipe = { decisions++ }, touchGate = gate) { hit ->
                        Box(Modifier.fillMaxSize().then(hit.photo))
                    }
                    TextButton(onClick = { railClicks++ }, modifier = Modifier.height(64.dp).testTag("rail")) { Text("Like") }
                }
            }
        }
        val root = rule.onNodeWithTag("root")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val button = rule.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot.center - origin
        val photo = rule.onNodeWithTag("photo").fetchSemanticsNode().boundsInRoot
        val first = photo.topLeft - origin + Offset(photo.width * .15f, photo.height * .5f)
        val last = first + Offset(photo.width * .6f, 0f)
        root.performTouchInput { down(0, first); moveTo(0, last); down(1, button); up(0); up(1) }
        root.performTouchInput { down(0, button); down(1, first); moveTo(1, last); up(1); up(0) }
        rule.runOnIdle { assertEquals(0, railClicks); assertEquals(0, decisions) }
        rule.onNodeWithTag("rail").performTouchInput { click(center) }
        rule.onNodeWithTag("rail").performClick()
        rule.runOnIdle { assertEquals(2, railClicks); assertEquals(0, decisions) }
    }

    @Test fun backFocusAdmissionAndChangedGeometryRevokePendingRelease() {
        var decisions = 0
        var width by mutableStateOf(280.dp)
        var eligible by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                val gate = remember { ProfileTouchGate() }
                BackHandler(enabled = gate.active) { gate.invalidate() }
                Box(Modifier.size(300.dp, 260.dp).testTag("root").admitProfileTouches(gate) { eligible }) {
                    PhotoGestureSurface(owner = "fixture", modifier = Modifier.width(width).height(240.dp),
                        onSwipe = { decisions++ }, touchGate = gate) { hit ->
                        Box(Modifier.fillMaxSize().then(hit.photo))
                    }
                }
            }
        }
        val root = rule.onNodeWithTag("root")
        fun drag() = root.performTouchInput { down(Offset(this.width * .15f, center.y)); moveTo(Offset(this.width * .7f, center.y)) }
        drag()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        root.performTouchInput { up() }
        drag()
        rule.runOnIdle { eligible = false }
        root.performTouchInput { up() }
        rule.runOnIdle { eligible = true }
        drag()
        rule.runOnIdle { width = 240.dp }
        rule.waitForIdle()
        root.performTouchInput { up() }
        rule.runOnIdle { assertEquals(0, decisions) }
        drag()
        root.performTouchInput { up() }
        rule.runOnIdle { assertEquals(1, decisions) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun nativeGalleryPressDoesNotRebindAndKeyboardRemainsNative() {
        var owner by mutableStateOf(1)
        val taps = mutableListOf<Int>()
        rule.setContent {
            MaterialTheme {
                val rendered = owner
                PhotoGestureSurface(owner = rendered, modifier = Modifier.size(280.dp, 240.dp).testTag("photo"),
                    galleryEnabled = true) { hit ->
                    Box(Modifier.fillMaxSize().then(hit.photo)) {
                        PhotoTapTarget("Medium", Modifier.fillMaxSize().then(hit.next), owner = rendered) {
                            taps.add(rendered)
                        }
                    }
                }
            }
        }
        val photo = rule.onNodeWithTag("photo")
        photo.performTouchInput { down(center) }
        rule.runOnIdle { owner = 2 }
        photo.performTouchInput { up() }
        rule.runOnIdle { assertTrue(taps.isEmpty()) }
        val target = rule.onNodeWithContentDescription("Medium")
        target.performSemanticsAction(SemanticsActions.RequestFocus)
        target.assertIsFocused()
        target.performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(listOf(2), taps) }
    }
}
