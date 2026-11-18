package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.LocalVisibility
import de.justify87.ladiesexplorer.core.SourceFilterGroup
import kotlin.math.roundToInt

private val categoryLabels = mapOf(
    "" to "Alle Rubriken",
    "huren-hostessen" to "Huren & Hostessen",
    "bordelle-laufhaeuser" to "Bordelle & Laufhäuser",
    "escort" to "Escort",
    "transen-shemale" to "Trans",
    "erotische-massage" to "Erotische Massage",
    "domina-sm" to "Domina & SM",
    "fkk-saunaclubs" to "FKK & Saunaclubs",
    "swinger" to "Swinger",
    "telefonsex" to "Telefonsex",
    "onlineservice" to "Online-Service",
)

private val groupLabels = mapOf(
    "figur" to "Figur", "typ" to "Typ", "geschlecht" to "Geschlecht",
    "busen" to "Busen", "intimbereich" to "Intimbereich", "hautton" to "Hautton",
    "haarfarbe" to "Haarfarbe", "haarlaenge" to "Haarlänge",
    "augenfarbe" to "Augenfarbe", "koerperschmuck" to "Körperschmuck",
    "sonstiges" to "Sonstiges", "service" to "Services",
    "treff" to "Treffpunkt", "location" to "Location", "zeiten" to "Zeiten",
)

@Composable
fun SearchScreen(
    state: SearchUiState,
    onCity: (String) -> Unit,
    onRadius: (UInt) -> Unit,
    onCategory: (String) -> Unit,
    onAge: (UInt,UInt) -> Unit,
    onHeight: (UInt,UInt) -> Unit,
    onPages: (UInt) -> Unit,
    onToggleFilter: (String,String) -> Unit,
    onAvailability: (AvailabilityMode) -> Unit,
    onLocalVisibility: (LocalVisibility) -> Unit,
    onReset: () -> Unit,
    onPreviewVisible: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onSave: (String) -> Unit,
    onNewPreset: () -> Unit,
    onLoadSaved: (String) -> Unit,
    onRunSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onAcknowledge: (String) -> Unit,
    onShowSavedResults: (String) -> Unit,
    onLoadMoreSavedResults: () -> Unit,
    onProfile: (Long,Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var resultsVisible by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    var categoryExpanded by rememberSaveable { mutableStateOf(false) }
    val filterListState = rememberLazyListState()
    val resultListState = rememberLazyListState()
    val savedListState = key(state.showingSavedSearchId) { rememberLazyListState() }
    var lastEditingId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastRunId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = when {
        state.showingSavedSearchId != null -> savedListState
        resultsVisible && state.response != null -> resultListState
        else -> filterListState
    }
    LaunchedEffect(state.showingSavedSearchId, state.savedResultCards.size, state.savedResultCursor) {
        if (state.showingSavedSearchId != null && state.savedResultCursor != null) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (last >= state.savedResultCards.size - 3) onLoadMoreSavedResults()
                }
        }
    }
    LaunchedEffect(state.editingSavedSearchId) {
        if (lastEditingId != state.editingSavedSearchId) {
            presetName = state.savedSearches.firstOrNull { it.searchId == state.editingSavedSearchId }?.name.orEmpty()
            lastEditingId = state.editingSavedSearchId
        }
    }
    LaunchedEffect(state.response?.searchRunId) {
        val runId = state.response?.searchRunId?.toString()
        if (runId != lastRunId) {
            if (runId != null) {
                resultListState.scrollToItem(0)
                resultsVisible = true
            }
            lastRunId = runId
        }
    }
    val editorVisible = state.showingSavedSearchId == null && !(resultsVisible && state.response != null)
    DisposableEffect(editorVisible) {
        onPreviewVisible(editorVisible)
        onDispose { onPreviewVisible(false) }
    }
    Column(modifier = modifier.imePadding()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.showingSavedSearchId == null) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (resultsVisible && state.response != null) "Ergebnisse" else "Filter",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.response != null) {
                    TextButton(onClick = { resultsVisible = !resultsVisible }) {
                        Text(if (resultsVisible) "Filter ändern" else "Ergebnisse")
                    }
                }
            }
        }
        if (state.loading) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                TextButton(onClick = onCancelSearch) { Text("Suche abbrechen") }
            }
        }
        state.error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        if (state.showingSavedSearchId != null) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${state.savedResultCards.size} bekannte Treffer",style=MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.savedResultCards.isEmpty()) {
                item { Text("Für diese Suche sind noch keine vollständigen Ergebnisse gespeichert.") }
            }
            items(state.savedResultCards,key={it.resultKey}) { card ->
                Card(modifier=Modifier.fillMaxWidth()) {
                    Row(
                        modifier=Modifier.padding(12.dp),
                        horizontalArrangement=Arrangement.spacedBy(12.dp),
                        verticalAlignment=Alignment.CenterVertically,
                    ) {
                        ProfileThumbnail(
                            url=card.thumbnailUrl,
                            modifier=Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(card.name,style=MaterialTheme.typography.titleMedium)
                            Text(card.city,style=MaterialTheme.typography.bodySmall)
                            Text("Erstmals: ${card.firstSeenAt.take(16)}",style=MaterialTheme.typography.labelSmall)
                            val listing = card.listingId
                            val person = card.personId
                            if (listing != null && person != null) {
                                TextButton(onClick={onProfile(listing,person)}) { Text("Profil öffnen") }
                            } else {
                                Text("Profil lokal noch nicht verfügbar",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (state.savedResultsLoadingMore) item { CircularProgressIndicator() }
        } else if (resultsVisible && state.response != null) {
            val response = state.response
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${state.activeCards.size} sichtbare Treffer", style = MaterialTheme.typography.titleLarge)
                    Text("Quellzahl: ${response.serverCount ?: "unbekannt"}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${response.fetchedPages} von ${response.totalPages} Seiten · ${response.city} · ${response.requestedRadiusKm} km",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (response.partial || !response.radiusVerified) {
                        Text(
                            "Suchlauf unvollständig oder Radius nicht bestätigt. Sichtbar: ${state.activeCards.size}.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (response.radiusBoundaryStopped) {
                        Text("Weitere Ergebnisse außerhalb des gewählten Radius wurden nicht übernommen.")
                    }
                    OutlinedButton(onClick = { resultsVisible = false }) { Text("Filter anzeigen") }
                }
            }
            items(state.activeCards, key = { it.listingId }) { card ->
                SearchResultRow(card, onProfile)
            }
        } else {
            item {
                OutlinedTextField(value = filterQuery, onValueChange = { filterQuery = it },
                    label = { Text("Filter suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                FilterCard("Standort") {
                    OutlinedTextField(value = state.city, onValueChange = onCity,
                        label = { Text("Ort") }, singleLine = true,
                        enabled = !state.loading, modifier = Modifier.fillMaxWidth())
                    Text("Umkreis", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        for (radius in listOf(5u,10u,20u,30u,50u,70u,100u)) {
                            FilterChip(selected = state.radiusKm == radius,
                                onClick = { onRadius(radius) }, enabled = !state.loading, label = { Text("$radius km") })
                        }
                    }
                }
            }
            if (state.frequentFilters.isNotEmpty() && filterQuery.isBlank()) item {
                FilterCard("Häufige Filter") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        state.frequentFilters.forEach { filter ->
                            FilterChip(selected = filter.value in state.selectedFilters[filter.group].orEmpty(),
                                onClick = { onToggleFilter(filter.group, filter.value) }, enabled = !state.loading,
                                label = { Text(filter.value) })
                        }
                    }
                }
            }
            item {
                FilterCard("Verfügbarkeit") {
                    AvailabilitySelector(availabilityMode(state.selectedFilters), !state.loading, onAvailability)
                }
            }
            item {
                FilterCard("Alter") {
                    Text("${state.ageMin}–${state.ageMax} Jahre", style = MaterialTheme.typography.titleSmall)
                    RangeSlider(value = state.ageMin.toFloat()..state.ageMax.toFloat(),
                        onValueChange = { onAge(it.start.roundToInt().toUInt().coerceIn(18u,60u),
                            it.endInclusive.roundToInt().toUInt().coerceIn(18u,60u)) },
                        valueRange = 18f..60f, enabled = !state.loading)
                }
            }
            state.filterCatalog?.groups?.firstOrNull { it.key == "treff" }?.let { group ->
                if (matchesFilterGroup(group, filterQuery)) item(key = "filter/treff") {
                    FilterSection(group, state.selectedFilters[group.key].orEmpty(), !state.loading,
                        filterQuery, true, { onToggleFilter(group.key, it) })
                }
            }
            item {
                FilterCard("Rubrik") {
                    OutlinedButton(onClick = { categoryExpanded = !categoryExpanded }, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(categoryLabels[state.category] ?: state.category)
                    }
                    if (categoryExpanded || filterQuery.isNotBlank()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            state.filterCatalog?.categories.orEmpty()
                                .filter { (categoryLabels[it] ?: it).contains(filterQuery, ignoreCase = true) }
                                .forEach { category ->
                                    FilterChip(selected = state.category == category, enabled = !state.loading,
                                        onClick = { onCategory(category) }, label = { Text(categoryLabels[category] ?: category) })
                                }
                        }
                    }
                }
            }
            item {
                FilterCard("Größe") {
                    Text("${state.heightMin}–${state.heightMax} cm", style = MaterialTheme.typography.titleSmall)
                    RangeSlider(value = state.heightMin.toFloat()..state.heightMax.toFloat(),
                        onValueChange = { onHeight(it.start.roundToInt().toUInt().coerceIn(140u,200u),
                            it.endInclusive.roundToInt().toUInt().coerceIn(140u,200u)) },
                        valueRange = 140f..200f, enabled = !state.loading)
                }
            }
            val groupOrder = listOf(
                "Eigenschaften" to setOf("figur", "typ", "geschlecht", "busen", "intimbereich", "hautton",
                    "haarfarbe", "haarlaenge", "augenfarbe", "koerperschmuck"),
                "Services" to setOf("service"),
                "Weitere Optionen" to setOf("sonstiges", "location", "zeiten"),
            )
            groupOrder.forEach { (title, keys) ->
                val groups = state.filterCatalog?.groups.orEmpty().filter { group ->
                    group.key in keys && matchesFilterGroup(group, filterQuery)
                }
                if (groups.isNotEmpty()) item(key = "section/$title") {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                groups.forEach { group ->
                    item(key = "filter/${group.key}") {
                        FilterSection(group, state.selectedFilters[group.key].orEmpty(), !state.loading,
                            filterQuery, false, { onToggleFilter(group.key, it) })
                    }
                }
            }
            item {
                FilterCard("Bilddarstellung und lokale Filter") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("ALL" to "Alle", "NATURAL" to "Natürlich", "AI" to "KI-markiert").forEach { (mode, label) ->
                            FilterChip(selected = state.local.imageMode.ifBlank { "ALL" } == mode, enabled = !state.loading,
                                onClick = { onLocalVisibility(state.local.copy(imageMode = mode)) }, label = { Text(label) })
                        }
                    }
                    LocalFilterRow("Nur mit Bildern", state.local.photosOnly, !state.loading) {
                        onLocalVisibility(state.local.copy(photosOnly = it))
                    }
                    LocalFilterRow("Nur mit Video", state.local.videoOnly, !state.loading) {
                        onLocalVisibility(state.local.copy(videoOnly = it))
                    }
                    LocalFilterRow("Dislikes ausblenden", state.local.hideDislikes, !state.loading) {
                        onLocalVisibility(state.local.copy(hideDislikes = it))
                    }
                    LocalFilterRow("Likes ausblenden", state.local.hideLikes, !state.loading) {
                        onLocalVisibility(state.local.copy(hideLikes = it))
                    }
                    Text("Studio, FaceApp und Betriebsprofile: keine zuverlässigen nativen Klassifikationsdaten.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                FilterCard("Seitenlimit") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        for (pages in listOf(1u,5u,10u,25u,50u)) {
                            FilterChip(selected = state.maxPages == pages, onClick = { onPages(pages) },
                                enabled = !state.loading, label = { Text("$pages") })
                        }
                    }
                }
            }
            item { HorizontalDivider() }
            item {
                Text("Gespeicherte Suchen",style = MaterialTheme.typography.titleLarge)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(
                        value=presetName,onValueChange={presetName=it},
                        label={Text("Name der Suche")},singleLine=true,
                        modifier=Modifier.weight(1f),
                    )
                    Button(
                        onClick={
                            onSave(presetName)
                        },
                        enabled=!state.saving && presetName.isNotBlank(),
                    ) { Text(if (state.editingSavedSearchId == null) "Speichern" else "Aktualisieren") }
                }
            }
            if (state.editingSavedSearchId != null) {
                val imported = state.savedSearches.firstOrNull { it.searchId == state.editingSavedSearchId }
                if (imported?.localFilterSummary?.isNotBlank() == true) {
                    item {
                        Text(
                            "Importierte lokale Filter bleiben beim Bearbeiten erhalten: ${imported.localFilterSummary}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                item {
                    TextButton(onClick = {
                        onNewPreset()
                        presetName = ""
                    }) { Text("Als neue Suche speichern") }
                }
            }
            if (state.savedSearches.isEmpty()) {
                item { Text("Noch keine Suche gespeichert.") }
            }
            items(state.savedSearches, key = { it.searchId }) { saved ->
                Card(modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(saved.name,style=MaterialTheme.typography.titleMedium)
                                Text(
                                    if (saved.definitionSupported) "${saved.city} · ${saved.radiusKm} km" else
                                        "Importierte Suchdefinition · bitte neu erstellen",
                                    style=MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (saved.unreadNewResults) {
                                Text("Neu",color=MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (saved.localFilterSummary.isNotBlank()) {
                            Text("Lokale Filter: ${saved.localFilterSummary}",style=MaterialTheme.typography.bodySmall)
                        }
                        saved.lastError?.let {
                            Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled=saved.definitionSupported && !state.loading,
                                onClick={ onLoadSaved(saved.searchId) },
                            ) { Text("Bearbeiten") }
                            TextButton(onClick={onShowSavedResults(saved.searchId)}) {
                                Text("Ergebnisse")
                            }
                            TextButton(
                                enabled=saved.definitionSupported && !state.loading,
                                onClick={ onRunSaved(saved.searchId) },
                            ) { Text("Ausführen") }
                            if (saved.unreadNewResults) {
                                TextButton(onClick={ onAcknowledge(saved.searchId) }) { Text("Gelesen") }
                            }
                            TextButton(onClick={ onDeleteSaved(saved.searchId) }) { Text("Löschen") }
                        }
                    }
                }
            }
        }
    }
        if (editorVisible) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when {
                        state.loading -> "Suche läuft …"
                        state.previewLoading -> "Quellzahl wird geprüft …"
                        state.previewError != null -> "Vorschau nicht verfügbar: ${state.previewError}"
                        state.preview?.serverCount != null -> "${state.preview.serverCount} Treffer laut Quelle"
                        state.preview != null -> "Quellzahl unbekannt (Radius/Metadaten nicht bestätigt)"
                        else -> "Quellzahl noch nicht geprüft"
                    }, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Text("Lokale Filter werden nach der Suche angewendet.", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { filterQuery = ""; onReset() }, enabled = !state.loading) { Text("Reset") }
                        Button(onClick = if (state.loading) onCancelSearch else onSearch,
                            enabled = state.loading || state.city.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Text(if (state.loading) "Abbrechen" else "Suchen")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AvailabilitySelector(active: AvailabilityMode, enabled: Boolean, onSelect: (AvailabilityMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        AvailabilityMode.entries.forEach { mode ->
            FilterChip(modifier = Modifier.weight(1f), selected = mode == active, enabled = enabled,
                onClick = { onSelect(mode) }, label = { Text(mode.label) })
        }
    }
}

@Composable
private fun FilterCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LocalFilterRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun matchesFilterGroup(group: SourceFilterGroup, query: String): Boolean =
    query.isBlank() || (groupLabels[group.key] ?: group.key).contains(query, ignoreCase = true) ||
        group.options.any { it.contains(query, ignoreCase = true) }

@Composable
private fun FilterSection(
    group: SourceFilterGroup,
    selected: Set<String>,
    enabled: Boolean,
    query: String,
    initiallyExpanded: Boolean,
    onToggle: (String) -> Unit,
) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(initiallyExpanded) }
    FilterCard((groupLabels[group.key] ?: group.key) + if (selected.isEmpty()) "" else " (${selected.size})") {
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Weniger anzeigen" else "Optionen anzeigen")
        }
        if (expanded || query.isNotBlank()) {
            val all = (groupLabels[group.key] ?: group.key).contains(query, ignoreCase = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                group.options.filter { all || it.contains(query, ignoreCase = true) || it in selected }.forEach { option ->
                    FilterChip(selected = option in selected, onClick = { onToggle(option) }, enabled = enabled,
                        label = { Text(option) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(card: DiscoveryCard, onProfile: (Long,Long) -> Unit) {
    Card(modifier=Modifier.fillMaxWidth(),onClick={onProfile(card.listingId,card.personId)}) {
        Row(
            modifier=Modifier.padding(12.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp),
            verticalAlignment=Alignment.CenterVertically,
        ) {
            ProfileThumbnail(
                url=card.thumbnailUrl,
                localPath=card.localPath,
                modifier=Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)),
            )
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(card.name.ifBlank { "Inserat ${card.listingId}" },style=MaterialTheme.typography.titleMedium)
                Text(card.city,style=MaterialTheme.typography.bodySmall)
                if (card.hasVideo || card.hasAiContent) {
                    Text(
                        listOfNotNull(
                            "Video".takeIf { card.hasVideo },
                            "KI".takeIf { card.hasAiContent },
                        ).joinToString(" · "),
                        style=MaterialTheme.typography.labelSmall,
                    )
                }
                Text("Profil öffnen",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
