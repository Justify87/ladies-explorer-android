package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.SavedPersonKind
import de.justify87.ladiesexplorer.core.SavedPersonRow
import de.justify87.ladiesexplorer.core.SavedPersonSort
import kotlinx.coroutines.flow.first

private val savedKinds = listOf(
    SavedPersonKind.FAVORITE to "Favoriten",
    SavedPersonKind.LIKE to "Likes",
    SavedPersonKind.DISLIKE to "Dislikes",
)

@Composable
internal fun SavedPeopleScreen(
    state: DiscoverUiState,
    onKind: (SavedPersonKind) -> Unit,
    onSort: (SavedPersonSort) -> Unit,
    onRefresh: () -> Unit,
    onProfile: (SavedPersonAction) -> Unit,
    onFavorite: (SavedPersonAction) -> Unit,
    onPreference: (SavedPersonAction, Preference) -> Unit,
    onLoadMore: (SavedPeoplePageAction) -> Unit,
    onCheckAll: (SavedStatusBatchAction) -> Unit,
    onCheckSingle: (SavedPersonAction) -> Unit,
    onCancelCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollMemory = rememberSaveableStateHolder()
    val enabled = !state.loading && !state.busy
    val page = state.savedPage
    val recent = state.savedRecent
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            savedKinds.forEach { (kind, label) ->
                FilterChip(
                    selected = state.savedKind == kind,
                    onClick = { onKind(kind) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val label = savedKinds.first { it.first == state.savedKind }.second
            Text(page?.let {
                val singular = when (state.savedKind) {
                    SavedPersonKind.FAVORITE -> "Favorit"
                    SavedPersonKind.LIKE -> "Like"
                    SavedPersonKind.DISLIKE -> "Dislike"
                }
                "${it.total} ${if (it.total == 1u) singular else label}"
            } ?: label,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortMenu = true }, enabled = enabled) {
                    Text(if (state.savedSort == SavedPersonSort.DATE) "Datum" else "Name")
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf(SavedPersonSort.DATE to "Datum", SavedPersonSort.NAME to "Name").forEach { (sort, text) ->
                        DropdownMenuItem(text = { Text(text) }, onClick = { sortMenu = false; onSort(sort) })
                    }
                }
            }
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text("Laden") }
        }
        val batchAction = page?.let { SavedStatusBatchAction(it, state.savedKind, state.savedSort, state.actionGeneration) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { batchAction?.let(onCheckAll) }, enabled = enabled && batchAction != null) {
                Text("Alle Status prüfen")
            }
            if (state.savedStatusProgress?.running == true) {
                TextButton(onClick = onCancelCheck, enabled = !state.savedStatusProgress.cancelled) {
                    Text(if (state.savedStatusProgress.cancelled) "Abbruch …" else "Abbrechen")
                }
            }
        }
        state.savedStatusProgress?.let { progress ->
            if (progress.running) {
                if (progress.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("${progress.done}/${progress.total} geprüft · ${progress.failed} fehlgeschlagen" +
                if (progress.cancelled) " · Abgebrochen" else "",
                style = MaterialTheme.typography.bodySmall)
            progress.lastError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        state.mutationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.savedError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val selection = "${state.savedKind.name}-${state.savedSort.name}"
        if (page == null) {
            if (state.loading) CircularProgressIndicator()
            return@Column
        }
        scrollMemory.SaveableStateProvider(selection) {
            val listState = rememberLazyListState()
            var expanded by rememberSaveable { mutableStateOf(false) }
            val pageAction = SavedPeoplePageAction(page, state.savedKind, state.savedSort, state.actionGeneration, false)
            val recentAction = recent?.let {
                SavedPeoplePageAction(it, state.savedKind, state.savedSort, state.actionGeneration, true)
            }
            val canPage = page.nextCursor != null && enabled && !state.savedLoadingMore && !state.savedRecentLoadingMore
            LaunchedEffect(listState, pageAction, canPage, state.savedError) {
                if (canPage && state.savedError == null) {
                    snapshotFlow {
                        val layout = listState.layoutInfo
                        nearDiscoveryEnd(layout.visibleItemsInfo.lastOrNull()?.index ?: -1, layout.totalItemsCount)
                    }.first { it }
                    onLoadMore(pageAction)
                }
            }
            // Keep the list mounted during refresh to preserve root/tab scroll state.
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!state.loading && page.total == 0u) item {
                    Text("Noch nichts gespeichert.", modifier = Modifier.padding(16.dp))
                }
                if (recent != null && recent.rows.isNotEmpty()) {
                    item(key = "recent-header") {
                        Text("Neu & geändert", style = MaterialTheme.typography.titleMedium)
                    }
                    items(if (expanded) recent.rows else recent.rows.take(3), key = { it.personId }) { row ->
                        SavedPersonCard(SavedPersonAction(row, state.savedKind, state.savedSort, state.actionGeneration),
                            enabled, onProfile, onFavorite, onPreference, onCheckSingle, preferChangeTime = true)
                    }
                    if (recent.sectionTotal > 3u) item(key = "recent-toggle") {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Weniger anzeigen" else "Alle anzeigen (${recent.sectionTotal})")
                        }
                    }
                    if (expanded && recent.nextCursor != null) item(key = "recent-more") {
                        TextButton(onClick = { recentAction?.let(onLoadMore) },
                            enabled = enabled && !state.savedRecentLoadingMore && !state.savedLoadingMore) {
                            Text(if (state.savedRecentLoadingMore) "Lädt …" else "Weitere Änderungen")
                        }
                    }
                    if (page.rows.isNotEmpty()) item(key = "regular-header") {
                        Text("Weitere Favoriten", style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(page.rows, key = { it.personId }) { row ->
                    SavedPersonCard(SavedPersonAction(row, state.savedKind, state.savedSort, state.actionGeneration),
                        enabled, onProfile, onFavorite, onPreference, onCheckSingle)
                }
                if (state.savedLoadingMore) item { CircularProgressIndicator() }
                else if (page.nextCursor != null) item {
                    TextButton(onClick = { onLoadMore(pageAction) }, enabled = canPage) { Text("Weitere Profile") }
                }
            }
        }
    }
}

@Composable
private fun SavedPersonCard(
    action: SavedPersonAction,
    enabled: Boolean,
    onProfile: (SavedPersonAction) -> Unit,
    onFavorite: (SavedPersonAction) -> Unit,
    onPreference: (SavedPersonAction, Preference) -> Unit,
    onCheckSingle: (SavedPersonAction) -> Unit,
    preferChangeTime: Boolean = false,
) {
    val row = action.row
    val card = row.card
    // An open menu must not silently rebind to a newer person window or owner.
    var menu by remember(action) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.weight(1f).clickable(enabled = enabled && card != null) {
                    if (card != null) onProfile(action)
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileThumbnail(card?.thumbnailUrl, Modifier.size(76.dp, 94.dp).clip(MaterialTheme.shapes.medium),
                    card?.localPath)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(listOfNotNull(row.name, card?.age).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(card?.city?.takeIf { it.isNotBlank() } ?: "Ort unbekannt",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when (row.status) {
                        "ACTIVE" -> "Aktiv"
                        "UNAVAILABLE", "INACTIVE", "REMOVED" -> "Nicht verfügbar"
                        else -> "Status unbekannt"
                    } + if (row.listingCount > 1u) " · +${row.listingCount - 1u}" else "",
                        style = MaterialTheme.typography.labelSmall)
                    if (row.recent) Text(when (row.latestChangeType) {
                        "NEW_LISTING_FOR_PERSON" -> "Neue Anzeige"
                        "PROFILE_CREATED", "LISTING_FIRST_SNAPSHOT" ->
                            if (row.listingCount == 1u) "Neu" else "Geändert"
                        else -> "Geändert"
                    }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    Text("Zuletzt: ${row.lastSeenAt.take(10)}", style = MaterialTheme.typography.labelSmall)
                    row.statusCheckedAt?.let {
                        Text("Geprüft: ${it.take(16).replace('T', ' ')} UTC", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text((if (preferChangeTime) row.latestChangeAt ?: row.lastSeenAt else row.lastSeenAt).take(10),
                    style = MaterialTheme.typography.labelSmall)
                Row {
                    IconButton(onClick = { onFavorite(action) }, enabled = enabled) {
                        Icon(if (row.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (row.favorite) "Favorit entfernen" else "Favorit hinzufügen")
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Weitere Aktionen")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (card != null) DropdownMenuItem(
                                text = { Text("Status prüfen") },
                                onClick = { menu = false; onCheckSingle(action) },
                            )
                            listOf(Preference.LIKE to "Like", Preference.DISLIKE to "Dislike",
                                Preference.NONE to "Entscheidung entfernen").forEach { (target, title) ->
                                DropdownMenuItem(text = { Text(title) }, enabled = row.preference != target,
                                    onClick = { menu = false; onPreference(action, target) })
                            }
                        }
                    }
                }
            }
        }
    }
}
