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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        if (state.loading && !editorVisible) item {
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
            searchFilterEditor(
                state = state,
                filterQuery = filterQuery,
                onFilterQuery = { filterQuery = it },
                categoryExpanded = categoryExpanded,
                onCategoryExpanded = { categoryExpanded = it },
                onCity = onCity,
                onRadius = onRadius,
                onCategory = onCategory,
                onAge = onAge,
                onHeight = onHeight,
                onPages = onPages,
                onToggleFilter = onToggleFilter,
                onAvailability = onAvailability,
                onLocalVisibility = onLocalVisibility,
            )
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
                        enabled=!state.saving && !state.loading && presetName.isNotBlank(),
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
                            TextButton(onClick={onShowSavedResults(saved.searchId)}, enabled = !state.loading) {
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
                if (card.hasVideo || card.isPremium || card.hasAiContent) {
                    Text(
                        listOfNotNull(
                            "Video".takeIf { card.hasVideo },
                            "Premium".takeIf { card.isPremium },
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
