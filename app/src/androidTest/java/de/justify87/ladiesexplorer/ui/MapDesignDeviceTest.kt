package de.justify87.ladiesexplorer.ui

import android.graphics.Bitmap
import android.graphics.Color as BitmapColor
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import de.justify87.ladiesexplorer.core.MapOverview
import de.justify87.ladiesexplorer.core.MapProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MapDesignDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun localSelectionAndCancelRemainAvailableWithoutMapTiles() {
        val profile = MapProfile(1L, 1L, "Fixture", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", true, null)
        val second = profile.copy(listingId = 2L, personId = 2L, name = "Fixture B")
        val actions = mutableListOf<String>()
        val resolving = true
        var cancelling by mutableStateOf(false)
        rule.setContent {
            LadiesTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MapDetails(
                        state = MapUiState(overview = MapOverview(listOf(profile, second), 3u, 1u, 1u),
                            resolving = resolving, cancelling = cancelling),
                        tileState = MapTileState.Failed, active = profile, selectionCount = 2,
                        profileEnabled = true,
                        onCancel = { actions.add("cancel"); cancelling = true },
                        onRetryTiles = { actions.add("tiles") }, onPickAll = { actions.add("all") },
                        onPickLocation = { actions.add("location") }, onClear = { actions.add("clear") },
                        onOpen = { actions.add(it.name) },
                    )
                }
            }
        }
        rule.onNodeWithText("Karte erneut laden").performScrollTo().performClick()
        rule.onNodeWithText("Standorte auflösen").assertDoesNotExist()
        rule.onNodeWithText("Stadtbereich · Leipzig").assertExists()
        rule.onNodeWithText("Aktualisieren").assertDoesNotExist()
        rule.onNodeWithText("2 von 3 Profilen kartiert · 1 ohne Position").assertDoesNotExist()
        rule.onNodeWithText("Standortaufbereitung abbrechen").performScrollTo().performClick()
        rule.onNodeWithText("Wird abgebrochen …").assertIsNotEnabled()
        rule.onNodeWithText("2 Profile am ungefähren Ort").performScrollTo().performClick()
        rule.onNodeWithText("Profil öffnen").performScrollTo().performClick()
        rule.onNodeWithText("Kartierte Profile auswählen").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Auswahl lösen").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("tiles", "cancel", "location", "Fixture", "all", "clear"), actions) }
    }

    @Test fun pickerExposesSelectedStateAndChoosesExactRenderedProfile() {
        val a = MapProfile(1L, 1L, "Fixture A", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", true, null)
        val b = a.copy(listingId = 2L, personId = 2L, name = "Fixture B")
        var chosen: MapProfile? = null
        rule.setContent {
            LadiesTheme {
                MapProfilePicker(listOf(a, b), a.listingId, "Profile am ungefähren Ort",
                    onDismiss = {}, onSelect = { chosen = it })
            }
        }
        rule.onNodeWithText("✓ Fixture A").assertIsSelected()
        rule.onNodeWithText("Fixture B").assertIsNotSelected().performClick()
        rule.runOnIdle { assertEquals(b, chosen) }
    }

    @Test fun nativeStyleCountsSplitAndRegroupWithoutProviderGlyphsAndRejectStalePhotos() {
        val a = MapProfile(1L, 1L, "Fixture A", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", false, null)
        val b = a.copy(listingId = 2L, personId = 2L, precision = "POSTAL")
        val c = a.copy(listingId = 3L, personId = 3L, longitude = a.longitude + .015)
        val d = a.copy(listingId = 4L, personId = 4L, longitude = a.longitude + .09,
            thumbnailUrl = "fixture-visible")
        val points = listOf(a, b, c, d)
        val center = LatLng(a.latitude, a.longitude)
        var map: MapLibreMap? = null
        var source: GeoJsonSource? = null
        var style: Style? = null
        var view: LifecycleMapView? = null
        var images: MapMarkerImages? = null
        var testLifecycle: androidx.lifecycle.Lifecycle? = null
        val decoded = mutableListOf<String>()
        val delayed = CompletableDeferred<Unit>()
        val oldDecodes = mutableListOf<Job>()
        val old = b.copy(thumbnailUrl = "fixture-delayed")
        val fresh = old.copy(personId = 22L, thumbnailUrl = "fixture-fresh")
        fun rendered(layer: String) = map?.let { ready ->
            val native = requireNotNull(view)
            ready.queryRenderedFeatures(RectF(0f, 0f, native.width.toFloat(), native.height.toFloat()), layer)
        }.orEmpty()
        fun photo() = mapPhotoBitmap(Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(BitmapColor.GREEN)
        })
        rule.setContent {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            testLifecycle = lifecycle
            AndroidView(
                factory = { context ->
                    MapLibre.getInstance(context)
                    LifecycleMapView(context, lifecycle).also { native ->
                        view = native
                        native.getMapAsync { ready ->
                            if (!native.released) ready.setStyle(
                                Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#222222"}}]}""")
                            ) { loaded ->
                                if (!native.released) {
                                    installMapProfileLayers(native, loaded, asFeatures(points, null),
                                        BitmapColor.RED, BitmapColor.WHITE, BitmapColor.BLACK, BitmapColor.BLUE)
                                    style = loaded
                                    source = loaded.getSource(MAP_SOURCE) as GeoJsonSource
                                    val controller = MapMarkerImages(native, ready, loaded, lifecycle) { _, url, _ ->
                                        decoded.add(url)
                                        if (url == old.thumbnailUrl) {
                                            oldDecodes.add(requireNotNull(currentCoroutineContext()[Job]))
                                            withContext(NonCancellable) { delayed.await() }
                                        }
                                        photo()
                                    }
                                    images = controller
                                    controller.update(points)
                                    native.addOnDidBecomeIdleListener { controller.refresh() }
                                    ready.addOnCameraMoveStartedListener { controller.cameraMoving() }
                                    ready.addOnCameraIdleListener { controller.cameraIdle() }
                                    ready.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 4.0))
                                    map = ready
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { images?.close(); it.releaseMap() },
            )
        }
        try {
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.runOnIdle {
                    rendered(MAP_CLUSTER_COUNTS).any { it.getNumberProperty("point_count").toInt() == 4 }
                }
            }
            rule.runOnIdle {
                assertTrue(decoded.isEmpty())
                val cluster = rendered(MAP_CLUSTERS).first { it.getNumberProperty("point_count").toInt() == 4 }
                val ready = requireNotNull(map)
                val zoom = requireNotNull(mapClusterZoom(requireNotNull(source), cluster, 4.0, ready.maxZoomLevel))
                assertTrue(zoom > 4.0 && zoom <= 16.0)
                assertNull(mapClusterZoom(requireNotNull(source), cluster, zoom, ready.maxZoomLevel))
                val point = cluster.geometry() as org.maplibre.geojson.Point
                ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude(), point.longitude()), zoom))
            }
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.runOnIdle {
                    rendered(MAP_CLUSTER_COUNTS).any { it.getNumberProperty("point_count").toInt() == 3 } &&
                        rendered(MAP_PHOTOS).any { it.getNumberProperty("listing_id").toLong() == d.listingId } &&
                        style?.getImage(mapThumbnailId(d)) != null
                }
            }
            rule.runOnIdle {
                assertEquals(listOf("fixture-visible"), decoded)
                requireNotNull(map).moveCamera(CameraUpdateFactory.newLatLngZoom(center, 16.0))
            }
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.runOnIdle {
                    val group = rendered(MAP_GROUP_COUNTS).singleOrNull()
                    group != null && group.getNumberProperty("group_count").toInt() == 2 &&
                        mapRenderedSelection(points, group) == a &&
                        style?.getImage(mapThumbnailId(d)) == null
                }
            }
            rule.runOnIdle {
                requireNotNull(map).moveCamera(CameraUpdateFactory.newLatLngZoom(center, 4.0))
            }
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.runOnIdle {
                    rendered(MAP_CLUSTER_COUNTS).any { it.getNumberProperty("point_count").toInt() == 4 }
                }
            }
            rule.runOnIdle {
                images?.update(listOf(old))
                requireNotNull(source).setGeoJson(asFeatures(listOf(old), old.listingId))
                requireNotNull(map).moveCamera(CameraUpdateFactory.newLatLngZoom(center, 16.0))
            }
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.runOnIdle {
                    decoded.contains(old.thumbnailUrl) && rendered(MAP_PHOTOS).isNotEmpty() &&
                        style?.getImage(mapThumbnailId(old)) == null
                }
            }
            // Force a new camera generation while the previous decode is still draining.
            rule.runOnIdle {
                if (oldDecodes.size == 1) {
                    requireNotNull(images).cameraMoving()
                    requireNotNull(images).cameraIdle()
                }
            }
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.runOnIdle { oldDecodes.size >= 2 && oldDecodes.none { it.isCompleted } }
            }
            rule.runOnIdle {
                images?.update(listOf(fresh))
                requireNotNull(source).setGeoJson(asFeatures(listOf(fresh), fresh.listingId))
            }
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.runOnIdle {
                    style?.getImage(mapThumbnailId(fresh)) != null &&
                        rendered(MAP_PHOTOS).any { it.getNumberProperty("owner_id").toLong() == fresh.personId }
                }
            }
            delayed.complete(Unit)
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.runOnIdle { oldDecodes.size >= 2 && oldDecodes.all { it.isCompleted } }
            }
            rule.runOnIdle {
                assertNull(style?.getImage(mapThumbnailId(old)))
                val visible = rendered(MAP_SINGLE).single()
                assertEquals(fresh, mapRenderedSelection(listOf(fresh), visible))
                assertEquals("POSTAL", visible.getStringProperty("group_precision"))
                assertTrue(visible.getBooleanProperty("selected"))
                images?.update(emptyList())
                requireNotNull(source).setGeoJson(asFeatures(emptyList(), null))
                assertNull(style?.getImage(mapThumbnailId(fresh)))
            }
            rule.waitUntil(timeoutMillis = 20_000) {
                rule.runOnIdle { rendered(MAP_SINGLE).isEmpty() && rendered(MAP_CLUSTERS).isEmpty() }
            }
            // Replacing controllers and cancelling camera generations must not free a
            // permit while a non-cooperative decode is still draining.
            val drain = CompletableDeferred<Unit>()
            var activeDecodes = 0
            var peakDecodes = 0
            var startedDecodes = 0
            val controllers = mutableListOf<MapMarkerImages>()
            try {
                rule.runOnIdle {
                    images?.close()
                    requireNotNull(source).setGeoJson(asFeatures(listOf(fresh), null))
                }
                rule.waitUntil(timeoutMillis = 20_000) {
                    rule.runOnIdle {
                        rendered(MAP_SINGLE).any {
                            it.getNumberProperty("owner_id").toLong() == fresh.personId
                        }
                    }
                }
                repeat(4) { index ->
                    rule.runOnIdle {
                        val native = requireNotNull(view)
                        val controller = MapMarkerImages(native, requireNotNull(map),
                            requireNotNull(style),
                            requireNotNull(testLifecycle)) { _, _, _ ->
                            startedDecodes++
                            activeDecodes++
                            peakDecodes = maxOf(peakDecodes, activeDecodes)
                            try {
                                withContext(NonCancellable) { drain.await() }
                                null
                            } finally {
                                activeDecodes--
                            }
                        }
                        controllers.add(controller)
                        controller.update(listOf(fresh))
                        controller.refresh()
                    }
                    rule.waitUntil(timeoutMillis = 10_000) {
                        rule.runOnIdle { startedDecodes == index + 1 }
                    }
                    rule.runOnIdle {
                        if (index < 3) controllers.last().close()
                        else controllers.last().cameraMoving()
                    }
                }
                rule.runOnIdle {
                    assertEquals(4, activeDecodes)
                    controllers.last().cameraIdle()
                }
                rule.waitForIdle()
                rule.runOnIdle {
                    assertEquals(4, startedDecodes)
                    assertEquals(4, activeDecodes)
                }
                drain.complete(Unit)
                rule.waitUntil(timeoutMillis = 10_000) {
                    rule.runOnIdle { startedDecodes == 5 && activeDecodes == 0 }
                }
                rule.runOnIdle { assertEquals(4, peakDecodes) }
            } finally {
                drain.complete(Unit)
                rule.runOnIdle { controllers.forEach { it.close() } }
            }
        } finally {
            delayed.complete(Unit)
        }
    }

    @Test fun thumbnailDecodeUsesExistingLoaderForSyntheticLocalFileAndFallsBackOnMissingFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("map-thumbnail-fixture", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888).apply {
                eraseColor(BitmapColor.GREEN)
            }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fun decode(url: String, localPath: String? = null) = runBlocking {
                withTimeout(10_000) { loadMapThumbnail(context, url, localPath) }
            }
            val missing = file.path + ".missing"
            val missingUrl = Uri.fromFile(File(missing)).toString()
            val decoded = requireNotNull(decode(missingUrl, file.path))
            assertEquals(80, decoded.width)
            assertEquals(80, decoded.height)
            assertEquals(BitmapColor.GREEN, decoded.getPixel(40, 40))
            assertEquals(BitmapColor.TRANSPARENT, decoded.getPixel(0, 0))
            assertNotNull(decode(Uri.fromFile(file).toString(), missing))
            assertNull(decode(missingUrl))
            assertEquals(BitmapColor.TRANSPARENT, mapFallbackBitmap().getPixel(0, 0))
        } finally {
            file.delete()
        }
    }

    @Test fun dockTracksMeasuredAttributionInsteadOfFixedTouchHeight() {
        var attributionHeight by mutableStateOf(20.dp)
        var pixelsPerDp = 1f
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val outer = LocalDensity.current
                val fitted = minOf(outer.density, maxWidth.value * outer.density / 320f,
                    maxHeight.value * outer.density / 560f)
                CompositionLocalProvider(LocalDensity provides Density(fitted, 2f)) {
                    pixelsPerDp = fitted
                    var measured by remember { mutableIntStateOf(0) }
                    LadiesTheme {
                        Box(Modifier.size(320.dp, 560.dp)) {
                            DiscoveryMapLayout(hasPanel = true, statusLabel = "Profile", warning = false,
                                attributionInset = with(LocalDensity.current) { measured.toDp() },
                                modeSwitcher = { DiscoverModeSwitcher(DiscoverMode.Map, {}, Modifier) },
                                searchStatus = {},
                                mapContent = {
                                    Box(Modifier.fillMaxSize().testTag("viewport"))
                                    Box(Modifier.align(Alignment.BottomStart).size(160.dp, attributionHeight)
                                        .onSizeChanged { measured = it.height }.testTag("attribution"))
                                },
                                details = { modifier, _ -> Box(modifier.height(48.dp).testTag("dock")) })
                        }
                    }
                }
            }
        }
        fun checkBounds() {
            val dock = rule.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
            val attribution = rule.onNodeWithTag("attribution").fetchSemanticsNode().boundsInRoot
            val viewport = rule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val gap = attribution.top - dock.bottom
            assertTrue(gap >= 0f && gap <= rule.runOnIdle { (ShellMetrics.small.value + 1f) * pixelsPerDp })
            assertTrue(attribution.bottom <= viewport.bottom + 1f)
        }
        checkBounds()
        rule.runOnIdle { attributionHeight = 36.dp }
        checkBounds()
    }

    @Test fun shortMapKeepsViewportAndAllStatusActionsReachable() {
        val profile = MapProfile(1L, 1L, "Fixture", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", true, null)
        val actions = mutableListOf<String>()
        var short by mutableStateOf(true)
        var dark by mutableStateOf(true)
        var pixelsPerDp = 1f
        rule.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val outer = LocalDensity.current
                val fitted = minOf(outer.density, maxWidth.value * outer.density / 320f,
                    maxHeight.value * outer.density / 560f)
                CompositionLocalProvider(LocalDensity provides Density(fitted, 2f)) {
                    pixelsPerDp = fitted
                    LadiesTheme(darkTheme = dark) {
                        Box(Modifier.size(320.dp, if (short) 260.dp else 560.dp)) {
                            DiscoveryMapLayout(hasPanel = true, statusLabel = "Suchfehler", warning = true,
                                modeSwitcher = { DiscoverModeSwitcher(DiscoverMode.Map, {}, Modifier) },
                                searchStatus = {
                                    DiscoverySearchRunStatus(
                                        SearchUiState(loading = true, error = "Lange synthetische Suchmeldung ".repeat(35)),
                                        onCancel = { actions.add("search") }
                                    )
                                },
                                mapContent = { Box(Modifier.fillMaxSize().testTag("map-viewport")) },
                                details = { modifier, close ->
                                    MapDetails(MapUiState(overview = MapOverview(listOf(profile), 1u, 0u, 0u), resolving = true),
                                        MapTileState.Failed, profile, 1, true,
                                        onCancel = { actions.add("cancel") },
                                        onRetryTiles = { actions.add("tiles") },
                                        onPickAll = { actions.add("all"); close() },
                                        onPickLocation = {}, onClear = {},
                                        onOpen = { actions.add("profile"); close() }, modifier = modifier)
                                })
                        }
                    }
                }
            }
        }
        val viewport = rule.onNodeWithTag("map-viewport")
        val openPanel = rule.onNodeWithText("Suchfehler")
        openPanel.assertIsDisplayed()
        assertTrue(viewport.fetchSemanticsNode().boundsInRoot.height >= rule.runOnIdle { 48f * pixelsPerDp - 1f })
        rule.onNodeWithText("Karte").assertIsSelected()
        rule.onNodeWithText("Profil öffnen").assertDoesNotExist()
        openPanel.performClick()
        rule.onNodeWithText("Abbrechen").performScrollTo().performClick()
        rule.onNodeWithText("Karte erneut laden").performScrollTo().performClick()
        rule.onNodeWithText("Standortaufbereitung abbrechen").performScrollTo().performClick()
        rule.onNodeWithText("Kartierte Profile auswählen").performScrollTo().performClick()
        rule.onNodeWithText("Kartenstatus und Profile").assertDoesNotExist()
        openPanel.performClick()
        rule.onNodeWithText("Profil öffnen").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("search", "tiles", "cancel", "all", "profile"), actions) }
        rule.runOnIdle { short = false; dark = false }
        openPanel.assertDoesNotExist()
        assertTrue(viewport.fetchSemanticsNode().boundsInRoot.height >= rule.runOnIdle { 160f * pixelsPerDp - 1f })
        rule.onNodeWithText("Kartierte Profile auswählen").performScrollTo().assertIsDisplayed()
    }
}
