package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import org.maplibre.android.camera.CameraPosition
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
    var camera by rememberSaveable { mutableStateOf<CameraPosition?>(null) }
    var mapAttempt by remember { mutableIntStateOf(0) }
    val points = state.overview?.profiles.orEmpty()
    val active = points.firstOrNull { it.listingId == selectedListingId }
    val selection = if (active == null) emptyList() else points.filter {
        it.latitude == active.latitude && it.longitude == active.longitude
    }
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
                OutlinedButton(onClick = onCancel, enabled = !state.cancelling) { Text(if (state.cancelling) "Wird abgebrochen …" else "Abbrechen") }
            } else if ((overview?.pendingLocations ?: 0u) > 0u) {
                Button(onClick = onResolve, enabled = !state.loading) {
                    Text("Standorte auflösen")
                }
            }
        }
        if (state.resolving || state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(when {
                    state.cancelling -> "Laufende Anfrage wird beendet …"
                    state.resolving -> "Standorte werden ermittelt …"
                    else -> "Karte wird geladen …"
                })
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
            key(mapAttempt) {
                NativeProfileMap(
                    points = points,
                    savedCamera = camera,
                    onCameraChanged = { camera = it },
                    onSelect = { selectedListingId = it },
                    onRetry = { mapAttempt++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
    savedCamera: CameraPosition?,
    onCameraChanged: (CameraPosition) -> Unit,
    onSelect: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentPoints by rememberUpdatedState(points)
    val currentCamera by rememberUpdatedState(savedCamera)
    val saveCamera by rememberUpdatedState(onCameraChanged)
    val currentSelect by rememberUpdatedState(onSelect)
    val mapView = remember(context, owner) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var loadError by remember(mapView) { mutableStateOf<String?>(null) }
    var disposed by remember(mapView) { mutableStateOf(false) }

    DisposableEffect(owner, mapView) {
        val lifecycle = owner.lifecycle
        // addObserver synchronizes to the current state; never start/resume twice.
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
        onDispose {
            disposed = true
            map?.cameraPosition?.let(saveCamera)
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                mapView.addOnDidFailLoadingMapListener { message ->
                    if (!disposed) loadError = message.ifBlank { "Kartenstil konnte nicht geladen werden." }
                }
                mapView.getMapAsync { ready ->
                    if (!disposed) {
                        ready.setStyle(MAP_STYLE) { style ->
                            if (!disposed) {
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
                                val initial = currentCamera
                                if (initial != null) {
                                    ready.moveCamera(CameraUpdateFactory.newCameraPosition(initial))
                                } else {
                                    val first = currentPoints.firstOrNull()
                                    ready.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                        if (first == null) LatLng(51.0, 10.0)
                                        else LatLng(first.latitude, first.longitude),
                                        if (first == null) 5.0 else 10.0,
                                    ))
                                }
                                ready.addOnCameraIdleListener {
                                    if (!disposed) saveCamera(ready.cameraPosition)
                                }
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
                                loadError = null
                                map = ready
                            }
                        }
                    }
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
        )
        when {
            loadError != null -> Card(modifier = Modifier.align(Alignment.Center).padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Karte konnte nicht geladen werden.", color = MaterialTheme.colorScheme.error)
                    Text("Gespeicherte Profildaten bleiben verfügbar.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onRetry) { Text("Karte erneut laden") }
                }
            }
            map == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }

    LaunchedEffect(points, map) {
        map?.getStyle { style ->
            (style.getSource(MAP_SOURCE) as? GeoJsonSource)?.setGeoJson(asFeatures(points))
        }
    }
}
