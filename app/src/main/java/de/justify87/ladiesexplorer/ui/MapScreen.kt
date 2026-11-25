package de.justify87.ladiesexplorer.ui

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.widget.ImageView
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.JsonObject
import de.justify87.ladiesexplorer.core.MapProfile
import de.justify87.ladiesexplorer.core.MapOverview
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
internal const val MAP_SOURCE = "ladies-coarse-profiles"
internal const val MAP_CLUSTERS = "ladies-clusters"
internal const val MAP_SINGLE = "ladies-single"
internal const val MAP_PHOTOS = "ladies-marker-photos"
internal const val MAP_CLUSTER_COUNTS = "ladies-cluster-count"
internal const val MAP_GROUP_COUNTS = "ladies-group-count"

internal enum class MapTileState { Loading, Ready, Failed }
private enum class MapPickerScope { All, Location }

internal fun mapSelection(points: List<MapProfile>, selectedListingId: Long?): List<MapProfile> {
    val active = points.firstOrNull { it.listingId == selectedListingId } ?: return emptyList()
    return points.filter { it.latitude == active.latitude && it.longitude == active.longitude }
}

internal fun mapSelectedProfile(points: List<MapProfile>, listingId: Long?, ownerId: Long?): MapProfile? =
    points.firstOrNull { it.listingId == listingId && it.personId == ownerId }

internal fun mapLocationLabel(profile: MapProfile): String =
    when (profile.precision) {
        "POSTAL" -> "PLZ-Bereich · ${profile.locationLabel}"
        "CITY" -> "Stadtbereich · ${profile.locationLabel}"
        else -> "Ungefähre Ortslage · ${profile.locationLabel}"
    }

internal fun mapInitialFitReady(overview: MapOverview?, resolving: Boolean): Boolean =
    overview != null && overview.profiles.isNotEmpty() && (overview.pendingLocations == 0u || !resolving)

@Composable
internal fun MapScreen(
    state: MapUiState,
    search: SearchUiState,
    onCancelSearch: () -> Unit,
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
    var picker by rememberSaveable { mutableStateOf<MapPickerScope?>(null) }
    var focusRequest by remember { mutableStateOf<Long?>(null) }
    var nativeAttribution by remember { mutableStateOf(IntSize.Zero) }
    var geoNamesHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val attributionInset = with(density) { maxOf(nativeAttribution.height, geoNamesHeight).toDp() }
    val attributionEnd = with(density) { nativeAttribution.width.toDp() }
    val controls = MapControlAction(state, generation)
    val overview = state.overview.takeIf { membershipCurrent }
    val points = overview?.profiles.orEmpty()
    val active = mapSelectedProfile(points, selectedListingId, selectedOwnerId)
    val selection = remember(points, active?.listingId) { mapSelection(points, active?.listingId) }
    val latestPoints by rememberUpdatedState(points)
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val marker = (if (dark) scheme.primaryContainer else scheme.primary).toArgb()
    val markerInk = (if (dark) scheme.onPrimaryContainer else scheme.onPrimary).toArgb()
    val selectionRing = (if (dark) Color.White else Color.Black).toArgb()
    val cityMarker = scheme.tertiary.toArgb()

    LaunchedEffect(overview) {
        // A temporary null overview during reload is not proof that the selection disappeared.
        if (overview != null && overview.pendingLocations == 0u && active == null) {
            selectedListingId = null
            selectedOwnerId = null
            focusRequest = null
            if (picker == MapPickerScope.Location) picker = null
        }
    }

    val visibleState = if (membershipCurrent) state else state.copy(overview = null, loading = state.error == null)
    val searchWarning = search.error != null || search.response?.let { it.partial || !it.radiusVerified } == true
    val warning = searchWarning || visibleState.error != null || tileState == MapTileState.Failed
    val summary = when {
        search.error != null -> "Suchfehler"
        visibleState.error != null || tileState == MapTileState.Failed -> "Kartenfehler"
        search.response?.let { it.partial || !it.radiusVerified } == true -> "Teilergebnis / Radius"
        search.loading -> "Suche läuft"
        visibleState.resolving -> "Standortaufbereitung"
        visibleState.loading -> "Kartenbestand wird geladen"
        else -> "Profile und Status"
    }
    DiscoveryMapLayout(
        modifier = modifier,
        hasPanel = hasDiscoverySearchStatus(search) || hasMapDetails(visibleState, tileState, active),
        statusLabel = summary,
        warning = warning,
        attributionInset = attributionInset,
        modeSwitcher = {
            DiscoverModeSwitcher(DiscoverMode.Map, { mode ->
                cameraReader?.invoke()?.let { camera = it }
                onMode(mode)
            }, Modifier)
        },
        searchStatus = {
            if (hasDiscoverySearchStatus(search)) DiscoverySearchRunStatus(search, onCancelSearch)
        },
        mapContent = {
            key(mapAttempt, marker, markerInk, selectionRing, cityMarker) {
                NativeProfileMap(
                    points = points,
                    fitReady = mapInitialFitReady(overview, state.resolving),
                    selectedListingId = active?.listingId,
                    savedCamera = camera,
                    onCameraChanged = { camera = it },
                    onCameraReader = { cameraReader = it },
                    onSelect = { id, ownerId ->
                        mapSelectedProfile(latestPoints, id, ownerId)?.let { profile ->
                            selectedListingId = profile.listingId
                            selectedOwnerId = profile.personId
                            focusRequest = null
                            if (mapSelection(latestPoints, id).size > 1) picker = MapPickerScope.Location
                        }
                    },
                    focusRequest = focusRequest?.takeIf { it == active?.listingId },
                    onFocusConsumed = { focusRequest = null },
                    marker = marker,
                    markerInk = markerInk,
                    selectionRing = selectionRing,
                    cityMarker = cityMarker,
                    onTileState = { tileState = it },
                    onAttributionInset = { nativeAttribution = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart)
                    .onSizeChanged { geoNamesHeight = it.height }
                    .padding(start = 4.dp, bottom = 4.dp, end = attributionEnd + 4.dp),
                color = scheme.surface,
                shape = MaterialTheme.shapes.small,
            ) {
                Text("GeoNames · CC BY 4.0", Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        },
        details = { panelModifier, closePanel ->
            MapDetails(
                state = visibleState,
                tileState = tileState,
                active = active,
                selectionCount = selection.size,
                profileEnabled = membershipCurrent && profileEnabled && !state.loading,
                onCancel = { onCancel(controls) },
                onRetryTiles = { tileState = MapTileState.Loading; mapAttempt++ },
                onPickAll = { closePanel(); picker = MapPickerScope.All },
                onPickLocation = { closePanel(); picker = MapPickerScope.Location },
                onClear = { selectedListingId = null; selectedOwnerId = null; focusRequest = null },
                onOpen = { person ->
                    if (overview != null) {
                        cameraReader?.invoke()?.let { camera = it }
                        onProfile(MapProfileOpenAction(overview, person, generation))
                    }
                },
                modifier = panelModifier,
            )
        },
    )

    picker?.takeIf { it == MapPickerScope.All || selection.isNotEmpty() }?.let { scope ->
        val choices = if (scope == MapPickerScope.All) points else selection
        MapProfilePicker(
            profiles = choices,
            selectedListingId = active?.listingId,
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

// Long status messages must not consume the map's viewport. In a short window,
// the same controls remain reachable in a scrollable dialog without covering the map.
@Composable
internal fun DiscoveryMapLayout(
    modifier: Modifier = Modifier,
    hasPanel: Boolean,
    statusLabel: String,
    warning: Boolean,
    attributionInset: Dp = 0.dp,
    modeSwitcher: @Composable () -> Unit,
    searchStatus: @Composable () -> Unit,
    mapContent: @Composable BoxScope.() -> Unit,
    details: @Composable (Modifier, () -> Unit) -> Unit,
) {
    var panelOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 360.dp || (maxHeight < 460.dp && LocalDensity.current.fontScale > 1.3f)
        val statusLimit = maxHeight * .25f
        val inset = shellContentInset()
        LaunchedEffect(compact, hasPanel) {
            if (!compact || !hasPanel) panelOpen = false
        }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = inset)) { modeSwitcher() }
            if (compact) {
                if (hasPanel) TextButton(onClick = { panelOpen = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch).shellFocus()) {
                    Text(statusLabel, color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            } else {
                Box(Modifier.fillMaxWidth().padding(horizontal = inset).heightIn(max = statusLimit)
                    .verticalScroll(rememberScrollState())) { searchStatus() }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                mapContent()
                val dockBottom = attributionInset + ShellMetrics.small
                if (!compact) details(
                    Modifier.align(Alignment.BottomCenter).padding(start = ShellMetrics.small,
                        end = ShellMetrics.small, bottom = dockBottom)
                        .widthIn(max = 420.dp).fillMaxWidth()
                        .heightIn(max = minOf(maxHeight * .65f, (maxHeight - dockBottom).coerceAtLeast(0.dp)))
                        .verticalScroll(rememberScrollState()),
                    {},
                )
            }
        }
        if (compact && hasPanel && panelOpen) {
            AlertDialog(onDismissRequest = { panelOpen = false },
                title = { Text("Kartenstatus und Profile") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(ShellMetrics.small)) {
                        searchStatus()
                        details(Modifier.fillMaxWidth()) { panelOpen = false }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { panelOpen = false }, modifier = Modifier.heightIn(min = ShellMetrics.touch)) {
                        Text("Schließen")
                    }
                })
        }
    }
}

private fun hasMapDetails(state: MapUiState, tileState: MapTileState, active: MapProfile?) =
    active != null || state.error != null || state.resolving || state.loading ||
        tileState == MapTileState.Failed || state.overview != null

@Composable
internal fun MapDetails(
    state: MapUiState,
    tileState: MapTileState,
    active: MapProfile?,
    selectionCount: Int,
    profileEnabled: Boolean,
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
    if (!hasMapDetails(state, tileState, active)) return
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = ShellMetrics.small, vertical = ShellMetrics.gap),
            verticalArrangement = Arrangement.spacedBy(ShellMetrics.gap)) {
            if (state.resolving || state.loading) {
                Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(when {
                        state.cancelling -> "Standortaufbereitung wird beendet …"
                        state.resolving -> "Ortslagen werden lokal aufbereitet …"
                        else -> "Kartenbestand wird geladen …"
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.error?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = scheme.error)
            }
            if (tileState == MapTileState.Failed) {
                Text("Karte konnte nicht geladen werden. Gespeicherte Profildaten bleiben verfügbar.",
                    color = scheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRetryTiles, modifier = Modifier.heightIn(min = ShellMetrics.touch).shellFocus()) {
                    Text("Karte erneut laden")
                }
            }
            if (state.resolving) {
                TextButton(onClick = onCancel, enabled = !state.cancelling,
                    modifier = Modifier.heightIn(min = ShellMetrics.touch).shellFocus()) {
                    Text(if (state.cancelling) "Wird abgebrochen …" else "Standortaufbereitung abbrechen")
                }
            }
            if (overview != null && overview.profiles.isEmpty() && !state.loading) {
                Text(
                    if (overview.pendingLocations > 0u) "Standortaufbereitung noch nicht vollständig."
                    else "Keine verlässlich zuordenbaren Ortslagen in der aktuellen Ergebnismenge.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (active != null) {
                Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(ShellMetrics.inset), verticalArrangement = Arrangement.spacedBy(ShellMetrics.gap)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileImage(active.thumbnailUrl, null,
                                Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                                description = "Profilbild von ${active.name}")
                            Column(Modifier.weight(1f)) {
                                Text(active.name.ifBlank { "Profil" }, style = MaterialTheme.typography.titleMedium)
                                Text(mapLocationLabel(active), style = MaterialTheme.typography.bodySmall)
                            }
                            ShellIconButton(onClick = onClear) {
                                Icon(Icons.Default.Close, contentDescription = "Auswahl lösen")
                            }
                        }
                        if (selectionCount > 1) {
                            TextButton(onClick = onPickLocation, modifier = Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch).shellFocus()) {
                                Text("$selectionCount Profile am ungefähren Ort")
                            }
                        }
                        Button(onClick = { onOpen(active) }, enabled = profileEnabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch).shellFocus()) { Text("Profil öffnen") }
                    }
                }
            }
            if (!overview?.profiles.isNullOrEmpty()) {
                TextButton(onClick = onPickAll, modifier = Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch).shellFocus()) {
                    Text("Kartierte Profile auswählen")
                }
            }
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
                    Column(Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch).shellFocus()
                        .selectable(selected = profile.listingId == selectedListingId, role = Role.RadioButton,
                            onClick = { onSelect(profile) })
                        .padding(8.dp)) {
                        Text(if (profile.listingId == selectedListingId) "✓ ${profile.name}" else profile.name,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(mapLocationLabel(profile), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ShellMetrics.touch).shellFocus()) { Text("Schließen") } },
    )
}

internal fun asFeatures(points: List<MapProfile>, selectedListingId: Long?): FeatureCollection {
    val groups = points.groupBy { it.latitude to it.longitude }
    val features = points.map { item ->
        val group = groups.getValue(item.latitude to item.longitude)
        val representative = group.minBy { it.listingId }
        val city = group.any { it.precision != "POSTAL" }
        val properties = JsonObject().apply {
            addProperty("listing_id", item.listingId)
            addProperty("owner_id", item.personId)
            addProperty("selected", group.any { it.listingId == selectedListingId })
            addProperty("precision", item.precision)
            addProperty("representative", item.listingId == representative.listingId)
            addProperty("group_precision", if (city) "CITY" else "POSTAL")
            addProperty("group_count", group.size)
            addProperty("group_image", MAP_COUNT_PREFIX + group.size + if (city) "-city" else "")
            addProperty("photo_id", if (group.size == 1) mapThumbnailId(item) else MAP_FALLBACK_IMAGE)
        }
        // Keep one native source point per listing so native point_count stays exact.
        Feature.fromGeometry(Point.fromLngLat(item.longitude, item.latitude), properties)
    }
    return FeatureCollection.fromFeatures(features.toTypedArray())
}

internal fun mapRenderedSelection(points: List<MapProfile>, feature: Feature): MapProfile? {
    val center = feature.geometry() as? Point ?: return null
    val profile = mapSelectedProfile(points, feature.getNumberProperty("listing_id").toLong(),
        feature.getNumberProperty("owner_id").toLong()) ?: return null
    return profile.takeIf {
        it.latitude == center.latitude() && it.longitude == center.longitude() &&
            mapSelection(points, it.listingId).size == feature.getNumberProperty("group_count").toInt()
    }
}

internal fun installMapProfileLayers(
    view: LifecycleMapView,
    style: Style,
    features: FeatureCollection,
    marker: Int,
    markerInk: Int,
    selectionRing: Int,
    cityMarker: Int,
) {
    val source = GeoJsonSource(
        MAP_SOURCE,
        features,
        GeoJsonOptions().withCluster(true).withClusterRadius(48).withClusterMaxZoom(15),
    )
    style.addSource(source)
    style.addImage(MAP_FALLBACK_IMAGE, mapFallbackBitmap())
    // The count is a local sprite, not a provider glyph or a collision-prone label.
    view.addOnStyleImageMissingListener { id ->
        if (!view.released && id.startsWith(MAP_COUNT_PREFIX)) {
            val key = id.removePrefix(MAP_COUNT_PREFIX)
            val count = key.removeSuffix("-city").toIntOrNull()
            if (count != null && count > 1) {
                style.addImage(id, mapCountBitmap(count,
                    if (key.endsWith("-city")) mapCountInk(cityMarker) else markerInk))
            }
        }
    }
    view.addOnCanRemoveUnusedStyleImageListener { true }

    val singles = Expression.all(Expression.not(Expression.has("point_count")),
        Expression.eq(Expression.get("representative"), Expression.literal(true)))
    val selected = Expression.eq(Expression.get("selected"), Expression.literal(true))
    val postal = Expression.eq(Expression.get("group_precision"), Expression.literal("POSTAL"))
    val one = Expression.eq(Expression.get("group_count"), Expression.literal(1))
    style.addLayer(
        CircleLayer(MAP_SINGLE, MAP_SOURCE)
            .withFilter(singles)
            .withProperties(
                circleColor(Expression.switchCase(postal,
                    Expression.color(marker), Expression.color(cityMarker))),
                circleRadius(Expression.switchCase(postal,
                    Expression.literal(24f), Expression.literal(28f))),
                circleStrokeWidth(Expression.switchCase(selected,
                    Expression.literal(5f), Expression.literal(2f))),
                circleStrokeColor(Expression.switchCase(selected,
                    Expression.color(selectionRing), Expression.color(markerInk))),
            ),
    )
    style.addLayer(
        SymbolLayer(MAP_PHOTOS, MAP_SOURCE)
            .withFilter(Expression.all(singles, one))
            .withProperties(iconImage(Expression.coalesce(
                Expression.image(Expression.get("photo_id")),
                Expression.image(Expression.literal(MAP_FALLBACK_IMAGE)))),
                iconAllowOverlap(true), iconIgnorePlacement(true)),
    )
    style.addLayer(
        SymbolLayer(MAP_GROUP_COUNTS, MAP_SOURCE)
            .withFilter(Expression.all(singles, Expression.not(one)))
            .withProperties(iconImage(Expression.get("group_image")),
                iconAllowOverlap(true), iconIgnorePlacement(true)),
    )
    // Put clusters above single markers so a picture cannot conceal the group.
    style.addLayer(
        CircleLayer(MAP_CLUSTERS, MAP_SOURCE)
            .withFilter(Expression.has("point_count"))
            .withProperties(circleColor(marker), circleRadius(24f),
                circleStrokeWidth(2f), circleStrokeColor(markerInk)),
    )
    style.addLayer(
        SymbolLayer(MAP_CLUSTER_COUNTS, MAP_SOURCE)
            .withFilter(Expression.has("point_count"))
            .withProperties(iconImage(Expression.concat(Expression.literal(MAP_COUNT_PREFIX),
                Expression.toString(Expression.get("point_count")))),
                iconAllowOverlap(true), iconIgnorePlacement(true)),
    )
}

@Composable
private fun NativeProfileMap(
    points: List<MapProfile>,
    fitReady: Boolean,
    selectedListingId: Long?,
    savedCamera: CameraPosition?,
    onCameraChanged: (CameraPosition) -> Unit,
    onCameraReader: (() -> CameraPosition?) -> Unit,
    onSelect: (Long, Long) -> Unit,
    focusRequest: Long?,
    onFocusConsumed: () -> Unit,
    marker: Int,
    markerInk: Int,
    selectionRing: Int,
    cityMarker: Int,
    onTileState: (MapTileState) -> Unit,
    onAttributionInset: (IntSize) -> Unit,
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
    val publishAttribution by rememberUpdatedState(onAttributionInset)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var nativeView by remember { mutableStateOf<LifecycleMapView?>(null) }
    var markerImages by remember { mutableStateOf<MapMarkerImages?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var positioned by remember { mutableStateOf(savedCamera != null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                MapLibre.getInstance(context)
                val mapView = LifecycleMapView(context, owner.lifecycle)
                nativeView = mapView
                mapView.attributionInsetChanged = { publishAttribution(it) }
                publishTileState(MapTileState.Loading)
                mapView.addOnDidFailLoadingMapListener {
                    if (!mapView.released) {
                        loadError = true
                        publishTileState(MapTileState.Failed)
                    }
                }
                mapView.getMapAsync { ready ->
                    if (!mapView.released) {
                        ready.uiSettings.isLogoEnabled = false
                        ready.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.END)
                        ready.setStyle(MAP_STYLE) { style ->
                            if (!mapView.released) {
                                installMapProfileLayers(mapView, style, asFeatures(currentPoints, currentSelection),
                                    marker, markerInk, selectionRing, cityMarker)
                                val images = MapMarkerImages(mapView, ready, style, owner.lifecycle)
                                markerImages = images
                                images.update(currentPoints)
                                mapView.addOnDidBecomeIdleListener { images.refresh() }
                                val initial = currentCamera
                                if (initial != null) {
                                    positioned = true
                                    ready.moveCamera(CameraUpdateFactory.newCameraPosition(initial))
                                } else {
                                    ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(51.0, 10.0), 5.0))
                                }
                                publishCameraReader { if (mapView.released || !positioned) null else ready.cameraPosition }
                                ready.addOnCameraMoveStartedListener { reason ->
                                    if (!mapView.released) images.cameraMoving()
                                    if (!mapView.released && reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                                        positioned = true
                                    }
                                }
                                ready.addOnCameraIdleListener {
                                    if (!mapView.released) {
                                        images.cameraIdle()
                                        if (positioned) saveCamera(ready.cameraPosition)
                                    }
                                }
                                ready.addOnMapClickListener { location ->
                                    if (mapView.released) false else {
                                        val screen = ready.projection.toScreenLocation(location)
                                        val cluster = ready.queryRenderedFeatures(screen, MAP_CLUSTERS).firstOrNull()
                                        val center = cluster?.geometry() as? Point
                                        if (cluster != null && center != null) {
                                            val source = style.getSource(MAP_SOURCE) as? GeoJsonSource
                                            val zoom = source?.let { runCatching { it.getClusterExpansionZoom(cluster) }.getOrNull() }
                                                ?.toDouble()?.coerceAtMost(ready.maxZoomLevel)
                                            if (zoom != null && zoom > ready.cameraPosition.zoom) {
                                                positioned = true
                                                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                                    LatLng(center.latitude(), center.longitude()), zoom))
                                                true
                                            } else false
                                        } else {
                                            val single = ready.queryRenderedFeatures(screen, MAP_SINGLE).firstOrNull()
                                            val profile = single?.let { mapRenderedSelection(currentPoints, it) }
                                            if (profile != null) {
                                                positioned = true
                                                currentSelect(profile.listingId, profile.personId)
                                                true
                                            } else false
                                        }
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
            modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it },
            onRelease = { view ->
                if (!view.released && positioned) map?.cameraPosition?.let(saveCamera)
                publishCameraReader { null }
                markerImages?.close()
                markerImages = null
                publishAttribution(IntSize.Zero)
                map = null
                nativeView = null
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
        val ready = map ?: return@LaunchedEffect
        ready.getStyle { style ->
            if (map === ready && nativeView?.released == false) {
                markerImages?.update(currentPoints)
                (style.getSource(MAP_SOURCE) as? GeoJsonSource)?.setGeoJson(asFeatures(currentPoints, currentSelection))
            }
        }
    }
    LaunchedEffect(map, fitReady, points, viewport) {
        val ready = map?.takeIf { nativeView?.released == false } ?: return@LaunchedEffect
        // Partial projections are visible immediately, but the initial fit waits for the final
        // local projection and real layout dimensions. Explicit camera interaction takes priority.
        if (positioned || !fitReady || points.isEmpty() || viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val locations = points.map { LatLng(it.latitude, it.longitude) }.distinct()
        val padding = minOf(64, minOf(viewport.width, viewport.height) / 4)
        val update = if (locations.size == 1) CameraUpdateFactory.newLatLngZoom(locations.first(), 10.0)
        else CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(locations).build(), padding)
        positioned = true
        ready.moveCamera(update)
        saveCamera(ready.cameraPosition)
    }
    LaunchedEffect(map, focusRequest, points) {
        val ready = map?.takeIf { nativeView?.released == false } ?: return@LaunchedEffect
        val id = focusRequest ?: return@LaunchedEffect
        points.firstOrNull { it.listingId == id }?.let {
            positioned = true
            ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude),
                maxOf(16.0, ready.cameraPosition.zoom)))
        }
        consumeFocus()
    }
}

internal class LifecycleMapView(
    context: Context,
    private val lifecycle: Lifecycle,
) : MapView(context) {
    var released = false
        private set
    private var started = false
    private var resumed = false
    private var attributionControl: ImageView? = null
    var attributionInsetChanged: (IntSize) -> Unit = {}

    override fun initialiseAttributionView(): ImageView = super.initialiseAttributionView().also {
        attributionControl = it
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val control = attributionControl?.takeIf { it.visibility == View.VISIBLE }
        val inset = control?.let {
            val end = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) it.right else width - it.left
            IntSize(end.coerceAtLeast(0), (height - it.top).coerceAtLeast(0))
        } ?: IntSize.Zero
        attributionInsetChanged(inset)
    }

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
