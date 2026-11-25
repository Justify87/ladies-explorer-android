package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    @Test fun nativeStyleClustersThenShowsIndividualAndUpdatedMembers() {
        val a = MapProfile(1L, 1L, "Fixture A", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", false, null)
        val b = a.copy(listingId = 2L, personId = 2L, precision = "POSTAL")
        val center = LatLng(a.latitude, a.longitude)
        var map: MapLibreMap? = null
        var source: GeoJsonSource? = null
        rule.setContent {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            AndroidView(
                factory = { context ->
                    MapLibre.getInstance(context)
                    LifecycleMapView(context, lifecycle).also { view ->
                        view.getMapAsync { ready ->
                            if (!view.released) ready.setStyle(
                                Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#222222"}}]}""")
                            ) { style ->
                                if (!view.released) {
                                    installMapProfileLayers(style, asFeatures(listOf(a, b), null),
                                        android.graphics.Color.RED, android.graphics.Color.WHITE,
                                        android.graphics.Color.BLACK, android.graphics.Color.BLUE)
                                    source = style.getSource(MAP_SOURCE) as GeoJsonSource
                                    ready.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 5.0))
                                    map = ready
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.releaseMap() },
            )
        }
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.runOnIdle {
                map?.let { ready ->
                    ready.queryRenderedFeatures(ready.projection.toScreenLocation(center), MAP_CLUSTERS)
                        .any { it.getNumberProperty("point_count").toInt() == 2 }
                } == true
            }
        }
        rule.runOnIdle { requireNotNull(map).moveCamera(CameraUpdateFactory.newLatLngZoom(center, 16.0)) }
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.runOnIdle {
                map?.let { ready ->
                    ready.queryRenderedFeatures(ready.projection.toScreenLocation(center), MAP_SINGLE)
                        .map { it.getNumberProperty("listing_id").toLong() }.toSet() == setOf(1L, 2L)
                } == true
            }
        }
        rule.runOnIdle { requireNotNull(source).setGeoJson(asFeatures(listOf(b), b.listingId)) }
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.runOnIdle {
                map?.let { ready ->
                    val visible = ready.queryRenderedFeatures(ready.projection.toScreenLocation(center), MAP_SINGLE)
                    visible.size == 1 && visible.first().getNumberProperty("listing_id").toLong() == 2L &&
                        visible.first().getStringProperty("precision") == "POSTAL" &&
                        visible.first().getBooleanProperty("selected") == true
                } == true
            }
        }
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
                                    Column {
                                        TextButton(onClick = { actions.add("search") }) { Text("Suche abbrechen") }
                                        Text("Lange synthetische Suchmeldung ".repeat(35))
                                    }
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
        rule.onNodeWithText("Suche abbrechen").performScrollTo().performClick()
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
