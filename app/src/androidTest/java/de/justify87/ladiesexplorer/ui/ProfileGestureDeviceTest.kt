package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual Compose hit paths, no source traffic or database writes. */
class ProfileGestureDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tapHorizontalVerticalDiagonalAndCancelledGesturesAreExclusive() {
        val decisions = mutableListOf<Preference>()
        var taps = 0
        var scroll = 0
        var feedback = 0f
        rule.setContent {
            MaterialTheme {
                val list = rememberLazyListState()
                SideEffect { scroll = list.firstVisibleItemScrollOffset + list.firstVisibleItemIndex * 10000 }
                LazyColumn(Modifier.size(280.dp, 320.dp), state = list) {
                    item {
                        PhotoGestureSurface(owner = "first", modifier = Modifier.fillMaxWidth().height(240.dp).testTag("photo"),
                            onTap = { _, _ -> taps++; true }, onSwipe = { decisions.add(it) }, onDrag = { feedback = it }) { region ->
                            Box(Modifier.fillMaxSize().then(region))
                        }
                    }
                    items(20) { Text("Detail", Modifier.height(60.dp)) }
                }
            }
        }
        val photo = rule.onNodeWithTag("photo")
        photo.performTouchInput { click(center) }
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
            assertTrue(scroll > 0)
            assertEquals(2, decisions.size)
            assertEquals(1, taps)
        }
    }

    @Test fun nativeChildButtonWinsHitTestingAndStandaloneNeverDecides() {
        var decisions = 0
        var buttons = 0
        var taps = 0
        var enabled by mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                PhotoGestureSurface(owner = enabled, modifier = Modifier.size(280.dp, 240.dp).testTag("photo"),
                    onTap = { _, _ -> taps++; true }, onSwipe = if (enabled) ({ decisions++ }) else null) { region ->
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("chrome"))
                        Box(Modifier.fillMaxWidth().weight(1f).then(region), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { buttons++ }, modifier = Modifier.testTag("control")) { Text("Retry") }
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
        // Second finger on chrome (a different child hit path) cancels the drag.
        photo.performTouchInput {
            down(0, Offset(width * .2f, height * .7f))
            moveTo(0, Offset(width * .55f, height * .7f))
            down(1, Offset(width * .8f, 10f))
            up(1); up(0)
        }
        rule.runOnIdle { assertEquals(1, buttons); assertEquals(0, decisions); assertEquals(0, taps) }
        // Owner/enable change while a native pointer is still down.
        photo.performTouchInput { down(Offset(width * .2f, height * .7f)); moveTo(Offset(width * .7f, height * .7f)) }
        rule.runOnIdle { enabled = false }
        photo.performTouchInput { up() }
        rule.runOnIdle { assertEquals(0, decisions) }
        photo.performTouchInput { swipe(Offset(width * .2f, height * .7f), Offset(width * .8f, height * .7f)) }
        photo.performTouchInput { click(Offset(width * .2f, height * .7f)) }
        rule.runOnIdle { assertEquals(0, decisions); assertEquals(1, taps) }
    }
    @Test fun actualHeroSideTapAndSwipeDoNotInvokeEachOther() {
        val decisions = mutableListOf<Preference>()
        rule.setContent {
            MaterialTheme {
                ProfileMediaHero(
                    media = listOf(de.justify87.ladiesexplorer.core.ProfileMediaItem("IMAGE", "", null, 0u),
                        de.justify87.ladiesexplorer.core.ProfileMediaItem("IMAGE", "", null, 1u)),
                    height = 400.dp, modifier = Modifier.testTag("hero"), originalUrl = null,
                    actionsEnabled = true, isCurrent = { true }, selection = remember { ProfileMediaSelection() },
                    identity = null, actionToken = null, compact = false, headerOwnsIdentity = false,
                    onIdentityMeasured = {}, onSwipe = { decisions.add(it) })
            }
        }
        val hero = rule.onNodeWithTag("hero")
        hero.performTouchInput { swipe(Offset(width * .85f, height * .6f), Offset(width * .15f, height * .6f)) }
        rule.onNodeWithText("1 / 2").assertExists()
        rule.runOnIdle { assertEquals(listOf(Preference.DISLIKE), decisions) }
        rule.onNodeWithContentDescription("Nächstes Medium").performTouchInput { click(center) }
        rule.onNodeWithText("2 / 2").assertExists()
        rule.runOnIdle { assertEquals(1, decisions.size) }
    }
}
