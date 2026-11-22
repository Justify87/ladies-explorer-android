package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.SearchRequest

@Composable
internal fun SearchScreen(
    search: SearchUiState,
    saved: SavedSearchUiState,
    canStartSearch: Boolean,
    onDraftChange: ((SearchDraft) -> SearchDraft) -> Unit,
    onReset: () -> Unit,
    onPreviewTarget: (SearchRequest?) -> Unit,
    onSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onSave: (SavedEditorAction) -> Unit,
    onName: (String) -> Unit,
    onCancelEdit: () -> Unit,
    generation: Long,
    onProfile: (SearchResultOpenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = saved.editor
    val editing = editor != null
    val draft = editor?.draft ?: SearchDraft.from(search)
    val state = draft.applyTo(search)
    val enabled = !search.loading && !saved.busy && (!editing || canStartSearch)
    var resultsVisible by rememberSaveable { mutableStateOf(false) }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    var categoryExpanded by rememberSaveable { mutableStateOf(false) }
    val filterListState = rememberLazyListState()
    val resultListState = rememberLazyListState()
    var lastRunId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = if (!editing && resultsVisible && state.response != null) resultListState else filterListState
    LaunchedEffect(state.response?.searchRunId) {
        val runId = state.response?.searchRunId?.toString()
        if (runId != lastRunId && !editing) {
            if (runId != null) {
                resultListState.scrollToItem(0)
                resultsVisible = true
            }
            lastRunId = runId
        }
    }
    val editorVisible = editing || !(resultsVisible && state.response != null)
    val target = if (editorVisible && !search.loading && !saved.blockingNavigation)
        draft.request().copy(maxPages = 1u) else null
    DisposableEffect(target) {
        onPreviewTarget(target)
        onDispose { onPreviewTarget(null) }
    }
    val previewMatches = target != null && state.previewTarget == target
    Column(modifier = modifier.imePadding()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!editing) item {
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
                TextButton(onClick = onCancelSearch, enabled = !state.searchCancelling) { Text(if (state.searchCancelling) "Suche wird beendet …" else "Suche abbrechen") }
            }
        }
        state.filterError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        (if (editing) saved.error else state.error)?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        if (!editing && resultsVisible && state.response != null) {
            val response = state.response
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${state.activeCards.size} sichtbare Treffer", style = MaterialTheme.typography.titleLarge)
                    Text("Quellzahl: ${response.serverCount?.takeIf { response.radiusVerified } ?: "unbekannt"}", style = MaterialTheme.typography.bodyMedium)
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
                SearchResultRow(SearchResultOpenAction(card, response.searchRunId, generation), onProfile)
            }
        } else {
            if (editor != null) item {
                OutlinedTextField(value = editor.name, onValueChange = onName,
                    label = { Text("Name der Suche") }, singleLine = true,
                    enabled = enabled, modifier = Modifier.fillMaxWidth())
            }
            searchFilterEditor(
                state = state,
                filterQuery = filterQuery,
                onFilterQuery = { filterQuery = it },
                categoryExpanded = categoryExpanded,
                onCategoryExpanded = { categoryExpanded = it },
                enabled = enabled,
                onCity = { value -> onDraftChange { it.copy(city = value) } },
                onRadius = { value -> onDraftChange { it.copy(radiusKm = value) } },
                onCategory = { value -> onDraftChange { it.copy(category = value) } },
                onAge = { min, max -> onDraftChange { it.copy(ageMin = min, ageMax = max) } },
                onHeight = { min, max -> onDraftChange { it.copy(heightMin = min, heightMax = max) } },
                onPages = { value -> onDraftChange { it.copy(maxPages = value) } },
                onToggleFilter = { group, value -> onDraftChange { it.toggle(group, value) } },
                onAvailability = { value -> onDraftChange { it.availability(value) } },
                onLocalVisibility = { value -> onDraftChange { it.copy(local = value) } },
            )
            if (editing) item {
                Text("Nicht unterstützte importierte Regeln sind nicht bearbeitbar. Unterstützte Filter bleiben erhalten.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
        if (editorVisible) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when {
                        state.searchCancelling -> "Suche wird beendet …"
                         state.loading -> "Suche läuft …"
                        !previewMatches -> "Quellzahl noch nicht geprüft"
                        state.previewLoading -> "Quellzahl wird geprüft …"
                        state.previewError != null -> "Vorschau nicht verfügbar: ${state.previewError}"
                        state.preview?.serverCount != null -> "${state.preview.serverCount} Treffer laut Quelle"
                        state.preview != null -> "Quellzahl unbekannt (Radius/Metadaten nicht bestätigt)"
                        else -> "Quellzahl noch nicht geprüft"
                    }, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    Text("Lokale Filter werden nach der Suche angewendet.", style = MaterialTheme.typography.labelSmall)
                    if (state.response != null) Text("Der letzte Suchlauf bleibt bis zur neuen Suche erhalten.",
                        style = MaterialTheme.typography.labelSmall)
                    if (editor != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCancelEdit, enabled = !saved.blockingNavigation) { Text("Abbrechen") }
                        Button(onClick = { onSave(SavedEditorAction(editor, saved.actionGeneration)) }, enabled = enabled && editor.name.isNotBlank() && editor.name.length <= 100 && draft.city.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Text(if (saved.operation == SavedOperation.Write) "Speichert …" else "Speichern")
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { filterQuery = ""; onReset() }, enabled = enabled) { Text("Reset") }
                        Button(onClick = if (state.loading) onCancelSearch else onSearch,
                            enabled = !state.searchCancelling && (state.loading || (canStartSearch && enabled && state.city.isNotBlank())), modifier = Modifier.weight(1f)) {
                            Text(if (state.searchCancelling) "Wird beendet …" else if (state.loading) "Abbrechen" else "Suchen")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(action: SearchResultOpenAction, onProfile: (SearchResultOpenAction) -> Unit) {
    val card = action.card
    Card(modifier=Modifier.fillMaxWidth(),onClick={onProfile(action)}) {
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
