package de.justify87.ladiesexplorer.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.JsonObject
import de.justify87.ladiesexplorer.core.MapProfile
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val MAP_SOURCE = "ladies-coarse-profiles"
private const val MAP_CLUSTERS = "ladies-clusters"
private const val MAP_SINGLE = "ladies-single"

@Composable
fun MapScreen(
    state: MapUiState,
    onRefresh: () -> Unit,
    onResolve: () -> Unit,
    onCancel: () -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onProfile: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedListingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selection = state.overview?.profiles?.filter {
        it.listingId == selectedListingId ||
            (selectedListingId != null && state.overview.profiles.firstOrNull {
                profile -> profile.listingId == selectedListingId
            }?.let { active ->
                active.latitude == it.latitude && active.longitude == it.longitude
            } == true)
    }.orEmpty()
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Karte", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Text("Nur Favoriten", style = MaterialTheme.typography.labelMedium)
            Switch(checked = state.favoritesOnly, onCheckedChange = onFavoritesOnly)
        }
        val overview = state.overview
        if (overview != null) {
            Text(
                "${overview.profiles.size} von ${overview.totalProfiles} Profilen kartiert · ${overview.unlocatedProfiles} ohne Position",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = !state.resolving && !state.loading) {
                Text("Aktualisieren")
            }
            if (state.resolving) {
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            } else if ((overview?.pendingLocations ?: 0u) > 0u) {
                Button(onClick = onResolve, enabled = !state.loading) {
                    Text("Standorte auflösen")
                }
            }
        }
        if (state.resolving || state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(if (state.resolving) "Standorte werden ermittelt …" else "Karte wird geladen …")
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if ((overview?.failedLookups ?: 0u) > 0u) {
            Text("Einige Standortabfragen sind fehlgeschlagen. Später erneut versuchen.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (overview != null && overview.profiles.isEmpty() && !state.loading) {
            Text(
                if (overview.pendingLocations > 0u) "Noch keine Koordinaten gespeichert. Tippe auf „Standorte auflösen“."
                else "Keine kartierbaren Profile. Suche zuerst nach Profilen oder deaktiviere den Favoritenfilter.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            NativeProfileMap(
                points = overview?.profiles.orEmpty(),
                onSelect = { selectedListingId = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (selection.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp).height(100.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Ungefähre Lage · ${selection.size} Profile", style = MaterialTheme.typography.labelMedium)
                    selection.forEach { person ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${person.name} · ${person.locationLabel}", modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { onProfile(person.listingId, person.personId) }) {
                                Text("Profil")
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Nur PLZ-/Stadtzentren, keine exakten Adressen. Kartenkacheln und Standortauflösung benötigen Internet.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun asFeatures(points: List<MapProfile>): FeatureCollection {
    val features = points.map { item ->
        val properties = JsonObject().apply {
            addProperty("listing_id", item.listingId)
        }
        Feature.fromGeometry(Point.fromLngLat(item.longitude, item.latitude), properties)
    }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

@Composable
private fun NativeProfileMap(
    points: List<MapProfile>,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentPoints by rememberUpdatedState(points)
    val currentSelect by rememberUpdatedState(onSelect)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var centered by remember { mutableStateOf(false) }
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(owner, mapView) {
        val lifecycle = owner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.getMapAsync { ready ->
                ready.setStyle(MAP_STYLE) { style ->
                    val source = GeoJsonSource(
                        MAP_SOURCE,
                        asFeatures(currentPoints),
                        GeoJsonOptions().withCluster(true).withClusterRadius(45).withClusterMaxZoom(15),
                    )
                    style.addSource(source)
                    style.addLayer(
                        CircleLayer(MAP_CLUSTERS, MAP_SOURCE)
                            .withFilter(Expression.has("point_count"))
                            .withProperties(circleColor("#4268B5"), circleRadius(21f)),
                    )
                    style.addLayer(
                        SymbolLayer("ladies-cluster-count", MAP_SOURCE)
                            .withFilter(Expression.has("point_count"))
                            .withProperties(
                                textField(Expression.get("point_count_abbreviated")),
                                textColor("#FFFFFF"), textSize(14f),
                            ),
                    )
                    style.addLayer(
                        CircleLayer(MAP_SINGLE, MAP_SOURCE)
                            .withFilter(Expression.not(Expression.has("point_count")))
                            .withProperties(
                                circleColor("#D62F77"), circleRadius(9f),
                                circleStrokeWidth(2f), circleStrokeColor("#FFFFFF"),
                            ),
                    )
                    ready.addOnMapClickListener { location ->
                        val screen = ready.projection.toScreenLocation(location)
                        val single = ready.queryRenderedFeatures(screen, MAP_SINGLE).firstOrNull()
                        if (single != null && single.hasProperty("listing_id")) {
                            currentSelect(single.getNumberProperty("listing_id").toLong())
                            true
                        } else if (ready.queryRenderedFeatures(screen, MAP_CLUSTERS).isNotEmpty()) {
                            ready.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                location, (ready.cameraPosition.zoom + 2.0).coerceAtMost(16.0)))
                            true
                        } else false
                    }
                    map = ready
                }
            }
            mapView
        },
        modifier = modifier,
    )
    LaunchedEffect(points, map) {
        map?.getStyle { style ->
            (style.getSource(MAP_SOURCE) as? GeoJsonSource)?.setGeoJson(asFeatures(points))
        }
        if (points.isNotEmpty() && !centered) {
            val first = points.first()
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(first.latitude, first.longitude), 10.0))
            if (map != null) centered = true
        }
    }
}
