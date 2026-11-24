package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.SavedSearchInfo
import kotlinx.coroutines.flow.first

internal fun savedDeltaText(info: SavedSearchInfo): String {
    val delta = info.delta ?: return if (info.lastRunAt == null) "Noch nicht ausgeführt" else "Delta nicht verfügbar"
    if (delta.initial == true) return "Vergleichsbasis · ${delta.currentCount} Treffer"
    val diff = listOfNotNull(
        "+${delta.newCount} neu".takeIf { delta.newCount > 0u },
        "${delta.removedCount} nicht mehr dabei".takeIf { delta.removedCount > 0u },
    ).joinToString(" · ")
    return (if (diff.isNotBlank()) diff else if (delta.initial == null) "Vergleichsstatus unbekannt" else "Keine Änderungen") +
        " · ${delta.currentCount} Treffer"
}

@Composable
internal fun SavedSearchOverview(
    state: SavedSearchUiState,
    enabled: Boolean,
    controller: SavedSearchController,
    modifier: Modifier = Modifier,
) {
    val actions = enabled && !state.busy && state.listAvailable
    state.dialog?.let { dialog ->
        val deleting = dialog.kind == SavedDialogKind.Delete
        AlertDialog(
            onDismissRequest = { controller.dismissDialog(SavedDialogAction(dialog, state.actionGeneration)) },
            title = { Text(when (dialog.kind) {
                SavedDialogKind.Create -> "Aktuelle Filter speichern"
                SavedDialogKind.Rename -> "Suche umbenennen"
                SavedDialogKind.Delete -> "Suche löschen?"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deleting) Text(dialog.name)
                    else OutlinedTextField(dialog.name, { name -> controller.dialogName(SavedDialogAction(dialog, state.actionGeneration), name) }, label = { Text("Name") },
                        singleLine = true, enabled = !state.busy)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.operation == SavedOperation.Write) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { controller.confirmDialog(SavedDialogAction(dialog, state.actionGeneration)) },
                enabled = actions && (deleting || dialog.name.isNotBlank() && dialog.name.length <= 100)) {
                Text(if (deleting) "Löschen" else "Speichern")
            } },
            dismissButton = { TextButton(onClick = { controller.dismissDialog(SavedDialogAction(dialog, state.actionGeneration)) }, enabled = !state.blockingNavigation) { Text("Abbrechen") } },
        )
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.showCreateDialog(SavedSearchPaneAction(state.actionGeneration)) }, enabled = actions) { Text("Filter speichern") }
                TextButton(onClick = controller::refresh, enabled = !state.busy) { Text("Aktualisieren") }
            }
        }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            when (state.operation) {
                SavedOperation.Check -> TextButton(onClick = controller::cancelCheck) { Text("Prüfung abbrechen") }
                SavedOperation.Cancelling -> Text("Prüfung wird beendet …")
                SavedOperation.Write -> Text("Änderung wird gespeichert …")
                else -> Text("Lädt …")
            }
        }
        if (state.dialog == null) state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.listAvailable && state.searches.isEmpty() && !state.busy) item { Text("Noch keine gespeicherte Suche.") }
        items(state.searches, key = { it.searchId }) { saved ->
            val rowAction = SavedSearchRowAction(saved, state.actionGeneration)
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
                    saved.lastError?.let { Text("Letzte Prüfung unvollständig/fehlgeschlagen: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    FlowRow {
                        TextButton(onClick = { controller.openResults(rowAction) }, enabled = actions) { Text("Öffnen") }
                        TextButton(onClick = { controller.check(rowAction) }, enabled = actions && saved.definitionSupported) { Text("Prüfen") }
                        TextButton(onClick = { controller.openEditor(rowAction) }, enabled = actions && saved.definitionSupported) { Text("Ändern") }
                        TextButton(onClick = { controller.showDialog(SavedDialogKind.Rename, rowAction) }, enabled = actions) { Text("Umbenennen") }
                        TextButton(onClick = { controller.showDialog(SavedDialogKind.Delete, rowAction) }, enabled = actions) { Text("Löschen") }
                        if (saved.unreadNewResults) TextButton(onClick = { controller.acknowledge(SavedSearchAckAction(saved.searchId, saved.revision, null, state.actionGeneration)) }, enabled = actions) { Text("Gelesen") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavedSearchResultsScreen(
    state: SavedSearchUiState,
    enabled: Boolean,
    onMore: (SavedSearchResultAction) -> Unit,
    onRetry: (SavedSearchResultAction) -> Unit,
    onAcknowledge: (SavedSearchAckAction) -> Unit,
    generation: Long,
    onOpen: (SavedSearchProfileOpenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val result = state.result ?: return
    val resultAction = SavedSearchResultAction(result, state.actionGeneration)
    // Restore the saved scroll position against loaded results, not an empty grid.
    val grid = rememberLazyGridState()
    if (!result.ready || state.resultDirty) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy || state.resultDirty) CircularProgressIndicator()
            else {
                Text(state.error ?: "Ergebnisse konnten nicht geladen werden", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onRetry(resultAction) }, enabled = enabled) { Text("Erneut versuchen") }
            }
        }
        return
    }
    val canPage = enabled && !state.busy && result.hasMore
    LaunchedEffect(grid, resultAction, canPage, state.windowError) {
        if (canPage && state.windowError == null) {
            snapshotFlow {
                val layout = grid.layoutInfo
                nearDiscoveryEnd(layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount)
            }.first { it }
            onMore(resultAction)
        }
    }
    val info = state.searches.firstOrNull { it.searchId == result.id }
    LazyVerticalGrid(columns = profileGridCells(), state = grid, modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(info?.let(::savedDeltaText) ?: "${result.cards.size} geladene Treffer")
                Text("${result.cards.size} geladen · aktuelle lokale Profildaten", style = MaterialTheme.typography.bodySmall)
                info?.lastError?.let { Text("Letzte Prüfung fehlgeschlagen: $it", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { onRetry(resultAction) }, enabled = enabled && !state.busy) { Text("Aktualisieren") }
                if (info?.unreadNewResults == true && result.revision != null) TextButton(onClick = { onAcknowledge(SavedSearchAckAction(result.id, result.revision, result, state.actionGeneration)) },
                    enabled = enabled && !state.busy) { Text("Als gelesen markieren") }
            }
        }
        state.error?.let { error ->
            item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Column { Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onRetry(resultAction) }, enabled = enabled && !state.busy) { Text("Erneut versuchen") } }
            }
        }
        if (result.cards.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Text("Keine gespeicherten Treffer. Suche in der Übersicht prüfen.")
        }
        items(result.cards, key = { "result/${it.resultKey}" }) { card ->
            val listing = card.listingId
            val person = card.personId
            Column {
                ProfileGridCard(card.name, card.age, card.thumbnailUrl, card.localPath, false,
                    enabled && !state.busy && listing != null && person != null,
                    onOpen = {
                        if (listing != null && person != null) {
                            onOpen(SavedSearchProfileOpenAction(result, card, generation))
                        }
                    }, onLike = null, place = card.city)
                if (listing == null || person == null) Text("Kein lokales Profil",
                    style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
        if (state.busy || result.hasMore) item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
            if (state.busy) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else TextButton(onClick = { onMore(resultAction) }, enabled = canPage) { Text("Weitere Treffer") }
        }
    }
}
