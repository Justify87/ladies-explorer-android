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
                        PhotoGestureZone(Modifier.fillMaxWidth().height(240.dp).testTag("photo"),
                            owner = "first", cardWidth = 280.dp, label = "Medium",
                            onTap = { taps++ }, onSwipe = { decisions.add(it) }, onDrag = { feedback = it })
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
                Box(Modifier.size(280.dp, 240.dp)) {
                    PhotoGestureZone(Modifier.fillMaxSize().testTag("photo"), owner = enabled,
                        cardWidth = 280.dp, label = "Medium", onTap = { taps++ },
                        onSwipe = if (enabled) ({ decisions++ }) else null)
                    TextButton(onClick = { buttons++ }, modifier = Modifier.align(Alignment.Center).testTag("control")) {
                        Text("Retry")
                    }
                }
            }
        }
        val control = rule.onNodeWithTag("control")
        control.performTouchInput { click(center) }
        control.performTouchInput { down(center); moveTo(center + Offset(400f, 0f)); up() }
        rule.runOnIdle { assertEquals(1, buttons); assertEquals(0, decisions); assertEquals(0, taps); enabled = false }
        val photo = rule.onNodeWithTag("photo")
        photo.performTouchInput { swipe(Offset(width * .2f, height * .2f), Offset(width * .8f, height * .2f)) }
        photo.performTouchInput { click(Offset(width * .2f, height * .2f)) }
        rule.runOnIdle { assertEquals(0, decisions); assertEquals(1, taps) }
    }
}
