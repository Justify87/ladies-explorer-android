package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.SavedSearchInfo
import kotlinx.coroutines.flow.first

internal fun savedDeltaText(info: SavedSearchInfo): String {
    val delta = info.delta ?: return if (info.lastRunAt == null) "Noch nicht ausgeführt" else "Delta nicht verfügbar"
    if (delta.initial) return "Vergleichsbasis · ${delta.currentCount} Treffer"
    return listOfNotNull(
        "+${delta.newCount} neu".takeIf { delta.newCount > 0u },
        "${delta.removedCount} nicht mehr dabei".takeIf { delta.removedCount > 0u },
    ).joinToString(" · ").ifBlank { "Keine neuen Treffer" } + " · ${delta.currentCount} Treffer"
}

@Composable
internal fun SavedSearchOverview(
    state: SearchUiState,
    canRun: Boolean,
    onRefresh: () -> Unit,
    onSave: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRun: (String) -> Unit,
    onCancel: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onAcknowledge: (String) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.loading && !state.saving && !state.savedEditorLoading
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.savedSearches.firstOrNull { it.searchId == dialog }
    if (dialog != null) AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text(if (selected != null) "Suche umbenennen" else "Aktuelle Filter speichern") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val id = selected?.searchId
            if (id == null) onSave(name) else onRename(id, name)
            dialog = null
        }, enabled = enabled && name.isNotBlank() && name.length <= 100) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = { dialog = null }) { Text("Abbrechen") } },
    )
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Suche löschen?") },
            text = { Text(state.savedSearches.firstOrNull { it.searchId == id }?.name.orEmpty()) },
            confirmButton = { TextButton(onClick = { onDelete(id); deleting = null }, enabled = enabled) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Abbrechen") } })
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { name = "${state.city} · ${state.radiusKm} km"; dialog = "" }, enabled = enabled) {
                    Text("Filter speichern")
                }
                TextButton(onClick = onRefresh, enabled = enabled && !state.savedListLoading) { Text("Aktualisieren") }
            }
        }
        if (state.loading || state.saving || state.savedEditorLoading || state.savedListLoading) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.loading) TextButton(onClick = onCancel) { Text("Prüfung abbrechen") }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.savedSearches.isEmpty() && !state.savedListLoading && state.error == null) item {
            Text("Noch keine gespeicherte Suche.")
        }
        items(state.savedSearches, key = { it.searchId }) { saved ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(saved.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (saved.unreadNewResults) Badge { Text("Neu") }
                    }
                    Text(if (saved.definitionSupported) "${saved.city} · ${saved.radiusKm} km"
                         else "Importierte Definition nicht ausführbar", style = MaterialTheme.typography.bodySmall)
                    Text(savedDeltaText(saved), color = MaterialTheme.colorScheme.primary)
                    saved.lastRunAt?.let { Text("Letzter vollständiger Lauf: ${it.take(16)}", style = MaterialTheme.typography.labelSmall) }
                    if (saved.localFilterSummary.isNotBlank()) Text(saved.localFilterSummary, style = MaterialTheme.typography.bodySmall)
                    saved.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onOpen(saved.searchId) }, enabled = enabled) { Text("Öffnen") }
                        TextButton(onClick = { onRun(saved.searchId) }, enabled = enabled && canRun && saved.definitionSupported) { Text("Prüfen") }
                        TextButton(onClick = { onEdit(saved.searchId) }, enabled = enabled && saved.definitionSupported) { Text("Ändern") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { name = saved.name; dialog = saved.searchId }, enabled = enabled) { Text("Umbenennen") }
                        TextButton(onClick = { deleting = saved.searchId }, enabled = enabled) { Text("Löschen") }
                        if (saved.unreadNewResults) TextButton(onClick = { onAcknowledge(saved.searchId) }, enabled = enabled) { Text("Gelesen") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavedSearchResultsScreen(
    state: SearchUiState,
    enabled: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = rememberLazyGridState()
    val canPage = enabled && !state.loading && !state.savedResultsLoadingMore && state.savedResultCursor != null
    LaunchedEffect(grid, state.savedResultCursor, canPage, state.error) {
        if (canPage && state.error == null) {
            snapshotFlow {
                val layout = grid.layoutInfo
                nearDiscoveryEnd(layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount)
            }.first { it }
            onLoadMore()
        }
    }
    val info = state.savedSearches.firstOrNull { it.searchId == state.showingSavedSearchId }
    LazyVerticalGrid(columns = GridCells.Fixed(2), state = grid, modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(info?.let(::savedDeltaText) ?: "${state.savedResultCards.size} geladene Treffer")
                Text("${state.savedResultCards.size} geladen", style = MaterialTheme.typography.bodySmall)
                info?.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        state.error?.let { error ->
            item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Column { Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = if (state.savedResultCursor != null) onLoadMore else onRetry, enabled = enabled && !state.savedResultsLoadingMore) { Text("Erneut versuchen") } }
            }
        }
        if (state.savedResultCards.isEmpty() && !state.savedResultsLoadingMore && state.error == null) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { Text("Keine gespeicherten Treffer. Suche in der Übersicht prüfen.") }
        }
        items(state.savedResultCards, key = { "result/${it.resultKey}" }) { card ->
            val listing = card.listingId
            val person = card.personId
            Column {
                ProfileGridCard(card.name, card.age, card.thumbnailUrl, card.localPath, false,
                    enabled && !state.loading && listing != null && person != null,
                    onOpen = { if (listing != null && person != null) onOpen(listing, person) }, onLike = null)
                Text(if (listing == null || person == null) "Kein lokales Profil" else card.city,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
        if (state.savedResultsLoadingMore || state.savedResultCursor != null) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                if (state.savedResultsLoadingMore) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else TextButton(onClick = onLoadMore, enabled = canPage) { Text("Weitere Treffer") }
            }
        }
    }
}
