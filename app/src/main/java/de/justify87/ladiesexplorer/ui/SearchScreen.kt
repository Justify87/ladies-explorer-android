package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LifecycleStartEffect

@Composable
internal fun SearchScreen(
    search: SearchUiState,
    saved: SavedSearchUiState,
    canStartSearch: Boolean,
    canEditDraft: Boolean,
    onDraftChange: ((SearchDraft) -> SearchDraft) -> Unit,
    onReset: () -> Boolean,
    onSaveDefault: () -> Unit,
    onPreviewStart: (SearchPreviewLease) -> Unit,
    onPreviewStop: (SearchPreviewLease) -> Unit,
    onSearch: (SearchSubmitAction) -> Unit,
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
    val enabled = search.defaultReady && !search.defaultSaving && !search.loading && !saved.busy && (if (editing) canStartSearch else canEditDraft)
    var resultsVisible by rememberSaveable { mutableStateOf(false) }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    var flippedSections by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
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
    val filterContext = remember(editorVisible) { Any() }
    val currentFilterContext by rememberUpdatedState(filterContext)
    val changeDraft: ((SearchDraft) -> SearchDraft) -> Unit = { change ->
        if (editorVisible && currentFilterContext === filterContext) onDraftChange(change)
    }
    val target = if (editorVisible) searchPreviewTarget(search, saved) else null
    if (target != null) LifecycleStartEffect(target) {
        val lease = SearchPreviewLease(target)
        onPreviewStart(lease)
        onStopOrDispose { onPreviewStop(lease) }
    }
    val previewMatches = target != null && state.previewTarget == target.draft.request() &&
        state.previewEditorSession == target.editorSession
    BoxWithConstraints(modifier = modifier) {
        val inlineFooter = maxHeight < 360.dp || (maxHeight < 460.dp && LocalDensity.current.fontScale > 1.3f)
        val footer: @Composable () -> Unit = {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = if (inlineFooter) 0.dp else shellContentInset(),
                    vertical = ShellMetrics.gap),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (editor != null) {
                        Text(searchCountLabel(state, draft, previewMatches, allowLastRun = false),
                            style = MaterialTheme.typography.labelSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = onCancelEdit, enabled = !saved.blockingNavigation, modifier = Modifier.heightIn(min = 48.dp)) { Text("Abbrechen") }
                            Button(onClick = { onSave(SavedEditorAction(editor, saved.actionGeneration)) }, enabled = enabled && editor.name.isNotBlank() && editor.name.length <= 100 && draft.city.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (saved.operation == SavedOperation.Write) "Speichert …" else "Speichern")
                            }
                        }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                            OutlinedButton(onClick = {
                                if (currentFilterContext === filterContext && onReset()) filterQuery = ""
                            }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
                            Button(onClick = if (state.loading) onCancelSearch else ({
                                if (currentFilterContext === filterContext) onSearch(SearchSubmitAction(draft, generation))
                            }), enabled = !state.searchCancelling && (state.loading || (canStartSearch && enabled && state.city.isNotBlank())), modifier = Modifier.heightIn(min = 48.dp)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(if (state.searchCancelling) "Wird beendet …" else if (state.loading) "Abbrechen" else "Suchen")
                                    if (!state.loading) Text(searchCountLabel(state, draft, previewMatches),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                if (currentFilterContext === filterContext) onSaveDefault()
                            }, enabled = enabled && draft.city.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (state.defaultSaving) "Standard wird gespeichert …" else "Aktuelle Suche als Standard speichern",
                                    style = MaterialTheme.typography.labelMedium)
                            }
                            if (state.response != null) TextButton(onClick = { resultsVisible = true },
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (state.activeProjectionPending || state.activeProjectionError != null) "Letzte Ergebnisse"
                                    else "Letzte Ergebnisse (${state.activeCards.size} lokal)", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (!state.defaultReady) Text("Suchstandard wird geladen …", style = MaterialTheme.typography.labelSmall)
                        state.defaultStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = shellContentInset(), vertical = ShellMetrics.small),
                verticalArrangement = Arrangement.spacedBy(if (editorVisible) ShellMetrics.gap else ShellMetrics.inset),
            ) {
                if (!editorVisible) item {
                    TextButton(onClick = { resultsVisible = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Filter ändern")
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
                if (previewMatches && editorVisible) state.previewError?.let { message ->
                    item { Text("Vorschau nicht verfügbar: $message", color = MaterialTheme.colorScheme.error) }
                }
                if (!editing && resultsVisible && state.response != null) {
                    val response = state.response
                    item {
                        val title = when {
                            state.activeProjectionPending -> "Lokale Treffer werden aktualisiert"
                            state.activeProjectionError != null -> "Lokale Treffer unbekannt"
                            else -> "${state.activeCards.size} lokal sichtbare Treffer"
                        }
                        ScreenGroup(title) {
                            state.activeProjectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Text("Quellzahl: ${response.serverCount?.takeIf { response.radiusVerified } ?: "unbekannt"}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                (if (response.partial || !response.radiusVerified) "${response.fetchedPages} Seiten geladen" else "${response.fetchedPages} von ${response.totalPages} Seiten") +
                                    " · ${response.city} · ${response.requestedRadiusKm} km",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (response.partial || !response.radiusVerified) {
                                Text(
                                    "Suchlauf unvollständig oder Radius nicht bestätigt.",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (response.radiusBoundaryStopped) {
                                Text("Weitere Ergebnisse außerhalb des gewählten Radius wurden nicht übernommen.")
                            }
                        }
                    }
                    items(state.activeCards.takeUnless { state.activeProjectionPending || state.activeProjectionError != null }.orEmpty(), key = { it.listingId }) { card ->
                        SearchResultRow(SearchResultOpenAction(card, response.searchRunId, generation), onProfile)
                    }
                } else {
                    if (editor != null) item {
                        CompactFilterField(editor.name, onName, "Name der Suche", enabled)
                    }
                    searchFilterEditor(
                        state = state,
                        filterQuery = filterQuery,
                        onFilterQuery = { filterQuery = it },
                        flippedSections = flippedSections,
                        onToggleSection = { key ->
                            if (filterQuery.isBlank()) flippedSections = ArrayList(if (key in flippedSections) flippedSections - key else flippedSections + key)
                        },
                        enabled = enabled,
                        onCity = { value -> changeDraft { it.copy(city = value) } },
                        onRadius = { value -> changeDraft { it.copy(radiusKm = value) } },
                        onCategory = { value -> changeDraft { it.copy(category = value) } },
                        onAge = { min, max -> changeDraft { it.copy(ageMin = min, ageMax = max) } },
                        onHeight = { min, max -> changeDraft { it.copy(heightMin = min, heightMax = max) } },
                        onToggleFilter = { group, value -> changeDraft { it.toggle(group, value) } },
                        onAvailability = { value -> changeDraft { it.availability(value) } },
                        onLocalVisibility = { value -> changeDraft { it.copy(local = value) } },
                    )
                    if (editing) item {
                        Text("Nicht unterstützte importierte Regeln sind nicht bearbeitbar. Unterstützte Filter bleiben erhalten.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (editorVisible && inlineFooter) item(key = "search-footer") { footer() }
            }
            if (editorVisible && !inlineFooter) footer()
        }
    }
}

@Composable
private fun SearchResultRow(action: SearchResultOpenAction, onProfile: (SearchResultOpenAction) -> Unit) {
    val card = action.card
    Card(modifier = Modifier.fillMaxWidth(), onClick = { onProfile(action) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
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
                Text(listOfNotNull(card.name.ifBlank { "Inserat ${card.listingId}" }, card.age).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium)
                Text(card.city.ifBlank { "Ort unbekannt" }, style = MaterialTheme.typography.bodySmall)
                if (card.hasVideo || card.isPremium || card.hasAiContent) {
                    Text(
                        listOfNotNull(
                            "Video".takeIf { card.hasVideo },
                            "Premium".takeIf { card.isPremium },
                            "KI-markiert".takeIf { card.hasAiContent },
                        ).joinToString(" · "),
                        style=MaterialTheme.typography.labelSmall,
                    )
                }
                Text("Profil öffnen",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
