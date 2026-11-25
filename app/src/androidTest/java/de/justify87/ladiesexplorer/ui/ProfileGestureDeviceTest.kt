package de.justify87.ladiesexplorer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import java.io.File
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

    @Test fun preparedPhotoTapAndArchivePathChangeKeepDecodedVisual() {
        val first = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }
        val second = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        val a = ProfileMediaItem("IMAGE", "https://example.invalid/a.jpg", null, 0u)
        val b = ProfileMediaItem("IMAGE", "https://example.invalid/b.jpg", null, 1u)
        var media by mutableStateOf(listOf(a, b))
        var visual by mutableStateOf<HeroVisual?>(null)
        var actions by mutableStateOf(true)
        val prepared = mapOf(a.presentationKey() to PreparedMedium.Photo(first),
            b.presentationKey() to PreparedMedium.Photo(second))
        rule.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(320.dp)) {
                    ProfileMediaHero(media, 360.dp, modifier = Modifier.testTag("prepared-hero"),
                        actionsEnabled = actions, isCurrent = { actions },
                        selection = remember { ProfileMediaSelection() }, actionToken = null,
                        compact = false, preparedMedia = prepared, presentationOwner = "same-owner",
                        onVisual = { visual = it })
                }
            }
        }
        rule.waitUntil(5_000) { visual?.media == a }
        val before = rule.runOnIdle { requireNotNull(visual) }
        rule.runOnIdle {
            media = listOf(a.copy(localPath = "/fixture/archived.jpg"), b)
            actions = false
        }
        rule.waitUntil(5_000) { visual?.media?.localPath == "/fixture/archived.jpg" }
        rule.runOnIdle {
            assertSame(before.painter, visual?.painter)
            assertEquals(before.geometry, visual?.geometry)
        }
        rule.onNodeWithContentDescription("Nächstes Medium").performClick()
        rule.waitUntil(5_000) { visual?.media == b }
        rule.onNodeWithContentDescription("Profilbild 2").assertExists()
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
                        actionToken = null, compact = false,
                        favorite = favorites % 2 == 1, onFavorite = { favorites++ },
                        onSwipe = { decisions.add(it) })
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

    @OptIn(ExperimentalTestApi::class)
    @Test fun realFitImagesKeepSourceBorderAndStableHeroAcrossGallery() {
        val files = (0..1).map { i ->
            File.createTempFile("synthetic-hero-", ".png", rule.activity.cacheDir).also { file ->
                val bitmap = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE)
                if (i == 1) Canvas(bitmap).drawRect(0f, 0f, 360f, 48f,
                    Paint().apply { color = android.graphics.Color.BLACK })
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            val media = files.mapIndexed { i, f -> ProfileMediaItem("IMAGE", "", f.absolutePath, i.toUInt()) }
            var visual by mutableStateOf<HeroVisual?>(null)
            rule.setContent {
                MaterialTheme {
                    Box(Modifier.requiredWidth(320.dp)) {
                        ProfileMediaHero(media = media, height = 360.dp, modifier = Modifier.testTag("hero"),
                            actionsEnabled = true, isCurrent = { true }, selection = remember { ProfileMediaSelection() },
                            actionToken = null, compact = false, onVisual = { visual = it })
                    }
                }
            }
            val hero = rule.onNodeWithTag("hero")
            fun verify(index: Int, expected: androidx.compose.ui.graphics.Color) {
                rule.waitUntil(10_000) { visual?.media == media[index] }
                rule.waitForIdle()
                val geometry = rule.runOnIdle { requireNotNull(visual).geometry }
                assertFalse(geometry.cropped)
                val pixels = hero.captureToImage().toPixelMap()
                val rect = requireNotNull(geometry.imageBounds)
                val actual = pixels[rect.center.x.toInt(), (rect.top + rect.height * .025f).toInt()]
                assertEquals(expected.red, actual.red, .05f)
                assertEquals(expected.green, actual.green, .05f)
                assertEquals(expected.blue, actual.blue, .05f)
            }
            verify(0, androidx.compose.ui.graphics.Color.Blue)
            val initialBounds = hero.fetchSemanticsNode().boundsInRoot
            rule.onNodeWithContentDescription("Nächstes Medium").performClick()
            verify(1, androidx.compose.ui.graphics.Color.Black)
            assertEquals(initialBounds, hero.fetchSemanticsNode().boundsInRoot)
            rule.onNodeWithContentDescription("Vorheriges Medium").performClick()
            verify(0, androidx.compose.ui.graphics.Color.Blue)
            assertEquals(initialBounds, hero.fetchSemanticsNode().boundsInRoot)
        } finally {
            files.forEach { it.delete() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun cropAndFitSwitchPublishOnlyTheirFinalGeometry() {
        val files = listOf(300, 160).mapIndexed { index, width ->
            File.createTempFile("synthetic-geometry-", ".png", rule.activity.cacheDir).also { file ->
                val bitmap = Bitmap.createBitmap(width, 360, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (index == 0) android.graphics.Color.BLUE else android.graphics.Color.GREEN)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        try {
            val media = files.mapIndexed { i, file -> ProfileMediaItem("IMAGE", "", file.absolutePath, i.toUInt()) }
            var visual by mutableStateOf<HeroVisual?>(null)
            var projected by mutableStateOf(false)
            var heroWidth by mutableIntStateOf(320)
            rule.setContent {
                MaterialTheme {
                    Box(Modifier.requiredWidth(heroWidth.dp)) {
                        ProfileMediaHero(media, 360.dp, Modifier.testTag("hero"),
                            actionsEnabled = true, isCurrent = { true }, selection = remember { ProfileMediaSelection() },
                            actionToken = null, compact = false, projectionAvailable = projected, onVisual = {
                                if (it != null) assertEquals(it.media == media[0] && heroWidth == 320, it.geometry.cropped)
                                visual = it
                            })
                    }
                }
            }
            val hero = rule.onNodeWithTag("hero")
            fun verify(index: Int) {
                rule.waitUntil(10_000) { visual?.media == media[index] }
                val pixels = hero.captureToImage().toPixelMap()
                val edge = pixels[(pixels.width * .03f).toInt(), pixels.height / 2]
                val center = pixels[pixels.width / 2, pixels.height / 2]
                if (index == 0) {
                    assertEquals(1f, edge.blue, .05f)
                    assertEquals(0f, edge.green, .05f)
                } else {
                    assertEquals(1f, center.green, .05f)
                    assertTrue(edge != center)
                }
            }
            rule.waitUntil(10_000) {
                val pixels = hero.captureToImage().toPixelMap()
                val edge = pixels[(pixels.width * .03f).toInt(), pixels.height / 2]
                edge.blue > .95f && edge.green < .05f
            }
            rule.runOnIdle { assertNull(visual); projected = true }
            verify(0)
            rule.runOnIdle { heroWidth = 200 }
            rule.waitUntil(10_000) { visual?.geometry?.cropped == false }
            rule.runOnIdle { heroWidth = 320 }
            verify(0)
            val bounds = hero.fetchSemanticsNode().boundsInRoot
            repeat(3) {
                rule.onNodeWithContentDescription("Nächstes Medium").performClick()
                verify(1)
                rule.onNodeWithContentDescription("Vorheriges Medium").performClick()
                verify(0)
                assertEquals(bounds, hero.fetchSemanticsNode().boundsInRoot)
            }
        } finally {
            files.forEach { it.delete() }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun fittedPhotoKeepsSideTapsAndFlightReusesTheSuccessfulFallback() {
        val file = File.createTempFile("synthetic-wide-", ".png", rule.activity.cacheDir)
        val missing = File(file.parentFile, file.name + ".missing")
        val bitmap = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            // The alternate model is a local file URI: no real network or test server.
            val first = ProfileMediaItem("IMAGE", android.net.Uri.fromFile(file).toString(), missing.absolutePath, 0u)
            val second = ProfileMediaItem("IMAGE", "", file.absolutePath, 1u)
            var visual by mutableStateOf<HeroVisual?>(null)
            var outgoing by mutableStateOf<HeroVisual?>(null)
            var favorites by mutableIntStateOf(0)
            val decisions = mutableListOf<Preference>()
            rule.setContent {
                LadiesTheme {
                    Box(Modifier.requiredSize(320.dp, 360.dp)) {
                        val flight = outgoing
                        if (flight != null) SwipeFlightImage(flight.painter, flight.geometry.cropped,
                            Modifier.fillMaxSize().testTag("outgoing"))
                        else ProfileMediaHero(listOf(first, second), 360.dp, Modifier.testTag("hero"),
                            actionsEnabled = true, isCurrent = { true }, selection = remember { ProfileMediaSelection() },
                            actionToken = null, compact = false, favorite = favorites % 2 == 1,
                            onFavorite = { favorites++ }, onSwipe = { decisions.add(it) }, onVisual = { visual = it })
                    }
                }
            }
            rule.waitUntil(10_000) { visual?.media == first }
            val hero = rule.onNodeWithTag("hero")
            assertFalse(rule.runOnIdle { requireNotNull(visual).geometry.cropped })
            // The old full-width reserved strip swallowed this tap above the fitted image.
            hero.performTouchInput { click(Offset(width * .8f, height * .1f)) }
            rule.waitUntil(10_000) { visual?.media == second }
            hero.performTouchInput { click(Offset(width * .2f, height * .1f)) }
            rule.waitUntil(10_000) { visual?.media == first }
            rule.onNodeWithContentDescription("Als Favorit speichern").performTouchInput { click(center) }
            rule.runOnIdle {
                assertEquals(first, visual?.media)
                assertEquals(1, favorites)
                assertTrue(decisions.isEmpty())
                assertTrue(file.delete())
                outgoing = requireNotNull(visual)
            }
            val pixels = rule.onNodeWithTag("outgoing").captureToImage().toPixelMap()
            val center = pixels[pixels.width / 2, pixels.height / 2]
            assertEquals(1f, center.blue, .05f)
            assertEquals(0f, center.red, .05f)
            assertEquals(0f, center.green, .05f)
        } finally {
            file.delete()
        }
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
