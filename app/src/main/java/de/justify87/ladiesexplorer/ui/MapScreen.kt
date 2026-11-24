package de.justify87.ladiesexplorer.ui

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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

internal enum class MapTileState { Loading, Ready, Failed }
private enum class MapPickerScope { All, Location }

internal fun mapSelection(points: List<MapProfile>, selectedListingId: Long?): List<MapProfile> {
    val active = points.firstOrNull { it.listingId == selectedListingId } ?: return emptyList()
    return points.filter { it.latitude == active.latitude && it.longitude == active.longitude }
}

internal fun mapSelectedProfile(points: List<MapProfile>, listingId: Long?, ownerId: Long?): MapProfile? =
    points.firstOrNull { it.listingId == listingId && it.personId == ownerId }

@Composable
internal fun MapScreen(
    state: MapUiState,
    onRefresh: (MapControlAction) -> Unit,
    onResolve: (MapControlAction) -> Unit,
    onCancel: (MapControlAction) -> Unit,
    membershipCurrent: Boolean,
    generation: Long,
    onProfile: (MapProfileOpenAction) -> Unit,
    onMode: (DiscoverMode) -> Unit,
    profileEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var selectedListingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedOwnerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var camera by rememberSaveable { mutableStateOf<CameraPosition?>(null) }
    var cameraReader by remember { mutableStateOf<(() -> CameraPosition?)?>(null) }
    var mapAttempt by remember { mutableIntStateOf(0) }
    var tileState by remember { mutableStateOf(MapTileState.Loading) }
    var picker by remember { mutableStateOf<MapPickerScope?>(null) }
    var focusRequest by remember { mutableStateOf<Long?>(null) }
    val controls = MapControlAction(state, generation)
    val overview = state.overview.takeIf { membershipCurrent }
    val points = overview?.profiles.orEmpty()
    val active = mapSelectedProfile(points, selectedListingId, selectedOwnerId)
    val selection = remember(points, active?.listingId) { mapSelection(points, active?.listingId) }
    val latestPoints by rememberUpdatedState(points)
    val detailsScroll = rememberScrollState()
    LaunchedEffect(selectedListingId, state.resolving, tileState == MapTileState.Failed) {
        detailsScroll.scrollTo(0)
    }
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val marker = (if (dark) scheme.primaryContainer else scheme.primary).toArgb()
    val markerInk = (if (dark) scheme.onPrimaryContainer else scheme.onPrimary).toArgb()
    val selectionRing = (if (dark) Color.White else Color.Black).toArgb()

    LaunchedEffect(overview) {
        // A temporary null overview during reload is not proof that the selection disappeared.
        if (overview != null && active == null) {
            selectedListingId = null
            selectedOwnerId = null
            focusRequest = null
            if (picker == MapPickerScope.Location) picker = null
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val availableHeight = maxHeight
        val compact = availableHeight < 440.dp ||
            (LocalDensity.current.fontScale >= 1.5f && availableHeight < 600.dp)
        Column(
            Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DiscoverModeSwitcher(DiscoverMode.Map, onMode, Modifier)
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .then(if (compact) Modifier.height(280.dp) else Modifier.weight(1f)),
                shape = MaterialTheme.shapes.large,
                color = scheme.surfaceContainerHigh,
            ) {
                key(mapAttempt, marker, markerInk, selectionRing) {
                    NativeProfileMap(
                        points = points,
                        selectedListingId = active?.listingId,
                        savedCamera = camera,
                        onCameraChanged = { camera = it },
                        onCameraReader = { cameraReader = it },
                        onSelect = { id ->
                            latestPoints.firstOrNull { it.listingId == id }?.let { profile ->
                                selectedListingId = profile.listingId
                                selectedOwnerId = profile.personId
                                focusRequest = null
                            }
                        },
                        focusRequest = focusRequest,
                        onFocusConsumed = { focusRequest = null },
                        marker = marker,
                        markerInk = markerInk,
                        selectionRing = selectionRing,
                        onTileState = { tileState = it },
                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.large),
                    )
                }
            }
            MapDetails(
                state = if (membershipCurrent) state else state.copy(overview = null, loading = true),
                tileState = tileState,
                active = active,
                selectionCount = selection.size,
                profileEnabled = membershipCurrent && profileEnabled && !state.loading,
                onRefresh = { onRefresh(controls) },
                onResolve = { onResolve(controls) },
                onCancel = { onCancel(controls) },
                onRetryTiles = { tileState = MapTileState.Loading; mapAttempt++ },
                onPickAll = { picker = MapPickerScope.All },
                onPickLocation = { picker = MapPickerScope.Location },
                onClear = { selectedListingId = null; selectedOwnerId = null; focusRequest = null },
                onOpen = { person ->
                    if (overview != null) {
                        cameraReader?.invoke()?.let { camera = it }
                        onProfile(MapProfileOpenAction(overview, person, generation))
                    }
                },
                modifier = Modifier.fillMaxWidth().then(
                    if (compact) Modifier else Modifier.heightIn(max = availableHeight * .48f)
                        .verticalScroll(detailsScroll)
                ),
            )
        }
    }

    picker?.let { scope ->
        val choices = if (scope == MapPickerScope.All) points else selection
        MapProfilePicker(
            profiles = choices,
            selectedListingId = selectedListingId,
            title = if (scope == MapPickerScope.All) "Kartierte Profile" else "Profile am ungefähren Ort",
            onDismiss = { picker = null },
            onSelect = { person ->
                if (latestPoints.any { it == person }) {
                    selectedListingId = person.listingId
                    selectedOwnerId = person.personId
                    focusRequest = person.listingId
                }
                picker = null
            },
        )
    }
}

@Composable
internal fun MapDetails(
    state: MapUiState,
    tileState: MapTileState,
    active: MapProfile?,
    selectionCount: Int,
    profileEnabled: Boolean,
    onRefresh: () -> Unit,
    onResolve: () -> Unit,
    onCancel: () -> Unit,
    onRetryTiles: () -> Unit,
    onPickAll: () -> Unit,
    onPickLocation: () -> Unit,
    onClear: () -> Unit,
    onOpen: (MapProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = state.overview
    val scheme = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ungefähre Ortslagen · gemeinsame Ergebnismenge",
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        if (overview != null) {
            Text("${overview.profiles.size} von ${overview.totalProfiles} Profilen kartiert · ${overview.unlocatedProfiles} ohne Position",
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.resolving || state.loading) {
            Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(when {
                    state.cancelling -> "Laufende Anfrage wird beendet …"
                    state.resolving -> "Standorte werden ermittelt …"
                    else -> "Kartenbestand wird geladen …"
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = scheme.error)
        }
        if (state.failedLookups > 0u) {
            Text("Einige Standortabfragen sind fehlgeschlagen. Gespeicherte Teilergebnisse bleiben verfügbar.",
                color = scheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (tileState == MapTileState.Failed) {
            Text("Karte konnte nicht geladen werden. Gespeicherte Profildaten bleiben verfügbar.",
                color = scheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onRetryTiles, modifier = Modifier.heightIn(min = 48.dp).shellFocus()) {
                Text("Karte erneut laden")
            }
        }
        if (state.resolving) {
            MapActionRow(state, onRefresh, onResolve, onCancel)
        }
        if (overview != null && overview.profiles.isEmpty() && !state.loading) {
            Text(
                if (overview.pendingLocations > 0u) "Noch keine Koordinaten gespeichert. Standorte ausdrücklich auflösen."
                else "Keine kartierbaren Profile in der aktuellen Ergebnismenge.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (active != null) {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Only the selected profile uses the existing image adapter, never every marker.
                        ProfileImage(active.thumbnailUrl, null,
                            Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                            description = "Profilbild von ${active.name}")
                        Column(Modifier.weight(1f)) {
                            Text(active.name.ifBlank { "Profil" }, style = MaterialTheme.typography.titleMedium)
                            Text(active.locationLabel, style = MaterialTheme.typography.bodySmall)
                        }
                        ShellIconButton(onClick = onClear) {
                            Icon(Icons.Default.Close, contentDescription = "Auswahl lösen")
                        }
                    }
                    if (selectionCount > 1) {
                        TextButton(onClick = onPickLocation, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).shellFocus()) {
                            Text("$selectionCount Profile am ungefähren Ort")
                        }
                    }
                    Button(onClick = { onOpen(active) }, enabled = profileEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).shellFocus()) { Text("Profil öffnen") }
                }
            }
        }
        if (!overview?.profiles.isNullOrEmpty()) {
            TextButton(onClick = onPickAll, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).shellFocus()) {
                Text("Kartierte Profile auswählen")
            }
        }
        if (!state.resolving) {
            MapActionRow(state, onRefresh, onResolve, onCancel)
        }
        Text("Nur PLZ-/Stadtzentren, keine exakten Adressen. Kartenkacheln sind cacheabhängig; Standortauflösung benötigt Internet.",
            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun MapActionRow(
    state: MapUiState,
    onRefresh: () -> Unit,
    onResolve: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.resolving,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).shellFocus()) { Text("Aktualisieren") }
        if (state.resolving) {
            OutlinedButton(onClick = onCancel, enabled = !state.cancelling,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).shellFocus()) {
                Text(if (state.cancelling) "Wird abgebrochen …" else "Abbrechen")
            }
        } else if ((state.overview?.pendingLocations ?: 0u) > 0u) {
            Button(onClick = onResolve, enabled = !state.loading,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).shellFocus()) { Text("Standorte auflösen") }
        }
    }
}

@Composable
internal fun MapProfilePicker(
    profiles: List<MapProfile>,
    selectedListingId: Long?,
    title: String,
    onDismiss: () -> Unit,
    onSelect: (MapProfile) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (profiles.isEmpty()) Text("Keine kartierbaren Profile verfügbar.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).selectableGroup()) {
                items(profiles, key = { it.listingId }) { profile ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).shellFocus()
                        .selectable(selected = profile.listingId == selectedListingId, role = Role.RadioButton,
                            onClick = { onSelect(profile) })
                        .padding(8.dp)) {
                        Text(if (profile.listingId == selectedListingId) "✓ ${profile.name}" else profile.name,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(profile.locationLabel, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).shellFocus()) { Text("Schließen") } },
    )
}

private fun asFeatures(points: List<MapProfile>, selectedListingId: Long?): FeatureCollection {
    val selected = mapSelection(points, selectedListingId).mapTo(HashSet()) { it.listingId }
    val features = points.map { item ->
        val properties = JsonObject().apply {
            addProperty("listing_id", item.listingId)
            addProperty("selected", item.listingId in selected)
        }
        Feature.fromGeometry(Point.fromLngLat(item.longitude, item.latitude), properties)
    }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

@Composable
private fun NativeProfileMap(
    points: List<MapProfile>,
    selectedListingId: Long?,
    savedCamera: CameraPosition?,
    onCameraChanged: (CameraPosition) -> Unit,
    onCameraReader: (() -> CameraPosition?) -> Unit,
    onSelect: (Long) -> Unit,
    focusRequest: Long?,
    onFocusConsumed: () -> Unit,
    marker: Int,
    markerInk: Int,
    selectionRing: Int,
    onTileState: (MapTileState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val owner = LocalLifecycleOwner.current
    val currentPoints by rememberUpdatedState(points)
    val currentSelection by rememberUpdatedState(selectedListingId)
    val currentCamera by rememberUpdatedState(savedCamera)
    val saveCamera by rememberUpdatedState(onCameraChanged)
    val publishCameraReader by rememberUpdatedState(onCameraReader)
    val currentSelect by rememberUpdatedState(onSelect)
    val publishTileState by rememberUpdatedState(onTileState)
    val consumeFocus by rememberUpdatedState(onFocusConsumed)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadError by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                MapLibre.getInstance(context)
                val mapView = LifecycleMapView(context, owner.lifecycle)
                publishTileState(MapTileState.Loading)
                mapView.addOnDidFailLoadingMapListener {
                    if (!mapView.released) {
                        loadError = true
                        publishTileState(MapTileState.Failed)
                    }
                }
                mapView.getMapAsync { ready ->
                    if (!mapView.released) {
                        ready.setStyle(MAP_STYLE) { style ->
                            if (!mapView.released) {
                                val source = GeoJsonSource(
                                    MAP_SOURCE,
                                    asFeatures(currentPoints, currentSelection),
                                    GeoJsonOptions().withCluster(true).withClusterRadius(48).withClusterMaxZoom(15),
                                )
                                style.addSource(source)
                                style.addLayer(
                                    CircleLayer(MAP_CLUSTERS, MAP_SOURCE)
                                        .withFilter(Expression.has("point_count"))
                                        .withProperties(circleColor(marker), circleRadius(24f),
                                            circleStrokeWidth(2f), circleStrokeColor(markerInk)),
                                )
                                style.addLayer(
                                    SymbolLayer("ladies-cluster-count", MAP_SOURCE)
                                        .withFilter(Expression.has("point_count"))
                                        .withProperties(
                                            textField(Expression.get("point_count_abbreviated")),
                                            textColor(markerInk), textSize(14f),
                                        ),
                                )
                                val selected = Expression.eq(Expression.get("selected"), Expression.literal(true))
                                style.addLayer(
                                    CircleLayer(MAP_SINGLE, MAP_SOURCE)
                                        .withFilter(Expression.not(Expression.has("point_count")))
                                        .withProperties(
                                            circleColor(marker), circleRadius(24f),
                                            circleStrokeWidth(Expression.switchCase(selected,
                                                Expression.literal(5f), Expression.literal(2f))),
                                            circleStrokeColor(Expression.switchCase(selected,
                                                Expression.color(selectionRing), Expression.color(markerInk))),
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
                                saveCamera(ready.cameraPosition)
                                publishCameraReader { if (mapView.released) null else ready.cameraPosition }
                                ready.addOnCameraIdleListener {
                                    if (!mapView.released) saveCamera(ready.cameraPosition)
                                }
                                ready.addOnMapClickListener { location ->
                                    if (mapView.released) false else {
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
                                }
                                loadError = false
                                map = ready
                                publishTileState(MapTileState.Ready)
                            }
                        }
                    }
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                if (!view.released) map?.cameraPosition?.let(saveCamera)
                view.releaseMap()
            },
        )
        when {
            loadError -> Surface(modifier = Modifier.align(Alignment.Center).padding(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Text("Karte nicht verfügbar", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            }
            map == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }

    LaunchedEffect(points, selectedListingId, map) {
        map?.getStyle { style ->
            (style.getSource(MAP_SOURCE) as? GeoJsonSource)?.setGeoJson(asFeatures(points, selectedListingId))
        }
    }
    LaunchedEffect(map, focusRequest, points) {
        val ready = map ?: return@LaunchedEffect
        val id = focusRequest ?: return@LaunchedEffect
        points.firstOrNull { it.listingId == id }?.let {
            ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude),
                maxOf(16.0, ready.cameraPosition.zoom)))
        }
        consumeFocus()
    }
}

private class LifecycleMapView(
    context: Context,
    private val lifecycle: Lifecycle,
) : MapView(context) {
    var released = false
        private set
    private var started = false
    private var resumed = false
    private val memoryCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() {
            if (!released) this@LifecycleMapView.onLowMemory()
        }
    }
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (!started) {
                onStart()
                started = true
            }
            Lifecycle.Event.ON_RESUME -> if (!resumed) {
                onResume()
                resumed = true
            }
            Lifecycle.Event.ON_PAUSE -> pauseMap()
            Lifecycle.Event.ON_STOP -> stopMap()
            Lifecycle.Event.ON_DESTROY -> releaseMap()
            else -> Unit
        }
    }

    init {
        onCreate(null)
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        // addObserver synchronizes to the current lifecycle state.
        lifecycle.addObserver(observer)
    }

    private fun pauseMap() {
        if (resumed) {
            onPause()
            resumed = false
        }
    }

    private fun stopMap() {
        pauseMap()
        if (started) {
            onStop()
            started = false
        }
    }

    fun releaseMap() {
        if (released) return
        released = true
        lifecycle.removeObserver(observer)
        context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        stopMap()
        onDestroy()
    }
}
