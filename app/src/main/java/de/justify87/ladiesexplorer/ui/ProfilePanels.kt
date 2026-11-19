package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.launch

internal enum class ProfileTab(val label: String) {
    Info("Info"), Services("Dienste"), Analysis("Analyse"), Notes("Notizen"), History("Verlauf")
}

/** App-owned route-specific memory survives closing a detail session. */
@Stable
internal class ProfileTabMemory(initial: List<String> = emptyList()) {
    var entries by mutableStateOf(initial)
        private set

    fun selected(personId: Long?): ProfileTab {
        val selected = entries.firstOrNull { it.startsWith("$personId:") }?.substringAfter(':')
        return ProfileTab.entries.firstOrNull { it.name == selected } ?: ProfileTab.Info
    }

    fun select(personId: Long?, tab: ProfileTab) {
        if (personId != null) entries =
            entries.filterNot { it.startsWith("$personId:") }.takeLast(99) + "$personId:${tab.name}"
    }
}

@Composable
internal fun rememberProfileTabMemory(): ProfileTabMemory = rememberSaveable(
    saver = listSaver<ProfileTabMemory, String>(save = { it.entries }, restore = { ProfileTabMemory(it) })
) { ProfileTabMemory() }

@Composable
internal fun rememberProfileNote(
    listingId: Long?,
    owner: Long?,
    actions: NoteEditorActions,
    busy: Boolean,
    visible: Boolean,
) {
    LaunchedEffect(listingId, owner, busy, visible) {
        if (listingId != null && owner != null && !busy && visible) actions.load(listingId, owner)
    }
}

@Composable
private fun PanelCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun FieldGrid(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) {
        Text("Keine Angaben gespeichert.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = when {
            maxWidth < 300.dp || fontScale > 1.5f -> 1
            maxWidth >= 480.dp && fontScale <= 1.2f -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (label, value) ->
                        Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(10.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                                Text(value, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

internal fun evidenceLabel(status: EvidenceStatus): String = when (status) {
    EvidenceStatus.YES -> "Ja"
    EvidenceStatus.NO -> "Nein"
    EvidenceStatus.UNKNOWN -> "Unbekannt"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldChips(rows: List<ProfileField>) {
    if (rows.isEmpty()) {
        Text("Keine Angaben gespeichert.")
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { field ->
            field.values.forEach { value ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text("${field.label}: $value", Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun ProfileFacts.importantRows(): List<Pair<String, String>> {
    val trait = traits.firstOrNull {
        it.label.lowercase() in setOf("busen", "oberweite", "brustgröße", "brustgroesse", "brust")
    }
    return listOf("Busen" to (trait?.values?.joinToString(" · ") ?: "Unbekannt")) +
        important.map { it.label to evidenceLabel(it.status) } +
        filters.map { "${it.label} (${it.group})" to evidenceLabel(it.status) }
}

@Composable
private fun DescriptionCard(profile: ProfileResponse) {
    var expanded by rememberSaveable(profile.snapshotId) { mutableStateOf(false) }
    var overflow by remember(profile.snapshotId) { mutableStateOf(false) }
    val text = profile.description.ifBlank { "Keine Beschreibung gespeichert." }
    PanelCard("Über das Profil") {
        SelectionContainer {
            Text(text, maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis, onTextLayout = {
                    if (!expanded) overflow = it.hasVisualOverflow
                })
        }
        if (expanded || overflow) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Weniger" else "Mehr anzeigen") }
        }
    }
}

@Composable
private fun NotesPanel(
    listingId: Long,
    owner: Long?,
    state: NoteEditorState,
    actions: NoteEditorActions,
    busy: Boolean,
) {
    PanelCard("Private Personennotiz") {
        val draft = state.draft
        val scope = rememberCoroutineScope()
        val editable = owner != null && !busy && !state.loading && !state.readError
        if (draft != null && owner != null) {
            OutlinedTextField(draft.text, { actions.edit(owner, it) },
                modifier = Modifier.fillMaxWidth(), label = { Text("Notiz") },
                minLines = 4, enabled = editable)
            Text("Entwurf bleibt beim Profilwechsel erhalten. Erst Speichern schreibt in die Datenbank.",
                style = MaterialTheme.typography.bodySmall)
            if (draft.conflict) {
                Text("Die gespeicherte Notiz hat sich geändert.", color = MaterialTheme.colorScheme.error)
                SelectionContainer { Text("Gespeichert: ${draft.observed ?: "Keine Notiz"}") }
                TextButton(onClick = { actions.useStored(owner) }, enabled = editable) {
                    Text("Gespeicherten Text übernehmen")
                }
                TextButton(onClick = { actions.keepDraft(owner) }, enabled = editable) {
                    Text("Eigenen Entwurf behalten")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.save(listingId, owner) },
                    enabled = editable && draft.dirty && !draft.conflict) { Text("Speichern") }
                TextButton(onClick = { actions.useStored(owner) },
                    enabled = !busy && !state.loading && draft.dirty) { Text("Verwerfen") }
            }
        } else if (owner == null) Text("Person nicht verfügbar.")
        if (state.loading || busy && draft != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (owner != null && (state.readError || state.error != null || draft == null && !state.loading)) {
            TextButton(onClick = { scope.launch { actions.load(listingId, owner) } },
                enabled = !busy && !state.loading) { Text("Neu laden") }
        }
    }
}

@Composable
internal fun ComparisonDialog(panels: ProfilePanels) {
    if (!panels.comparing && panels.comparison == null && panels.comparisonError == null) return
    AlertDialog(
        onDismissRequest = panels::closeComparison,
        title = { Text("Snapshot-Vergleich") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (panels.comparing) item { CircularProgressIndicator() }
                panels.comparisonError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                panels.comparison?.let { differences ->
                    if (differences.isEmpty()) item { Text("Keine gespeicherten Unterschiede.") }
                    items(differences) { difference ->
                        Column {
                            Text(historyFieldLabel(difference.field), style = MaterialTheme.typography.titleSmall)
                            HistoryValues(difference.before, difference.after)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = panels::closeComparison) { Text("Schließen") } },
    )
}

@Composable
private fun HistoryValues(before: String?, after: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Vorher", style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(before ?: "Nicht gespeichert", style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider()
        Text("Nachher", style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(after ?: "Nicht gespeichert", style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Both routes use the same lazy tab content and local typed projections. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.profileContent(
    profile: ProfileResponse,
    identity: ProfileIdentity?,
    person: PersonSummary?,
    media: List<ProfileMediaItem>,
    panels: ProfilePanels,
    tab: ProfileTab,
    selectTab: (ProfileTab) -> Unit,
    candidates: List<IdentityCandidate>,
    history: List<ChangeRecord>,
    actionsBusy: Boolean,
    note: NoteEditorState,
    noteActions: NoteEditorActions,
    onConfirm: ((Long) -> Unit)?,
    onOpenDetail: (() -> Unit)? = null,
) {
    item(key = "profile-tabs") {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            ProfileTab.entries.forEach { option ->
                Tab(selected = tab == option, onClick = { selectTab(option) },
                    text = { Text(if (option == ProfileTab.Notes && note.draft?.dirty == true)
                        "Notizen •" else option.label, maxLines = 1) },
                    icon = { Icon(when (option) {
                        ProfileTab.Info -> Icons.Default.Info
                        ProfileTab.Services -> Icons.Default.List
                        ProfileTab.Analysis -> Icons.Default.Search
                        ProfileTab.Notes -> Icons.Default.Edit
                        ProfileTab.History -> Icons.Default.DateRange
                    }, contentDescription = null) })
            }
        }
    }
    when (tab) {
        ProfileTab.Info -> {
            item(key = "profile-info") {
                PanelCard("Wichtige Informationen") {
                    FieldGrid(listOf("Alter" to (identity?.age ?: "Unbekannt"),
                        "Ort" to profile.city.ifBlank { "Unbekannt" },
                        "Anzeigen" to (person?.listingCount?.toString() ?: "Unbekannt")) +
                        panels.facts?.importantRows().orEmpty())
                    if (panels.facts == null) FactsContent(panels) { }
                }
            }
            item(key = "profile-traits") {
                PanelCard("Merkmale") { FactsContent(panels) { FieldChips(it.traits) } }
            }
            item(key = "profile-times") {
                PanelCard("Öffnungszeiten") { FactsContent(panels) { FieldChips(it.openingTimes) } }
            }
            item(key = "profile-description") { DescriptionCard(profile) }
        }
        ProfileTab.Services -> item(key = "profile-services") {
            PanelCard("Dienste") { FactsContent(panels) { FieldChips(it.services) } }
        }
        ProfileTab.Analysis -> {
            item(key = "profile-analysis") {
                PanelCard("Gespeicherte Evidenz") {
                    val data = panels.detail
                    FieldGrid(listOf("Bilder im aktuellen Profil" to media.count { it.kind == "IMAGE" }.toString()) +
                        if (data == null) emptyList() else listOf(
                            "Anzeigen" to data.listings.size.toString(), "Bekannte Namen" to data.knownNames.toString(),
                            "Bekannte Orte" to data.knownCities.toString(), "Archivierte Bildreferenzen (Assets)" to data.archivedImages.toString(),
                            "Mit anderen Personen geteilte Assets" to data.sharedImages.toString()))
                    if (data == null) DetailStatus(panels)
                    Text("Nur gespeicherte Fakten; keine KI-Bewertung oder Affinitätswertung.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item(key = "profile-candidate-head") {
                Text("Mögliche Identitätsübereinstimmungen", style = MaterialTheme.typography.titleMedium)
                Text(if (candidates.isEmpty()) "Keine Kandidaten gespeichert." else "Keine automatische Zusammenführung.")
            }
            items(candidates, key = { "candidate/${it.personId}" }) { candidate ->
                PanelCard("Person #${candidate.personId} · ${candidate.decision}") {
                    Text(listOfNotNull("Telefon".takeIf { candidate.phoneMatch },
                        "Quellprofil".takeIf { candidate.sourceProfileMatch },
                        "${candidate.strongImageMatches} starke Bildtreffer".takeIf { candidate.imageMatches > 0u })
                        .joinToString(" · ").ifBlank { "Keine bestätigende Evidenz" })
                    if (onConfirm != null) OutlinedButton(onClick = { onConfirm(candidate.personId) },
                        enabled = !actionsBusy) { Text("Zusammenführung prüfen") }
                    else if (onOpenDetail != null) OutlinedButton(onClick = onOpenDetail,
                        enabled = !actionsBusy) { Text("Im Detail prüfen") }
                }
            }
        }
        ProfileTab.Notes -> item(key = "profile-notes") {
            NotesPanel(profile.listingId, identity?.personId, note, noteActions, actionsBusy)
        }
        ProfileTab.History -> {
            val visibleEvents = if (panels.showDescriptionChanges) history
                else history.filterNot { it.fieldKey.equals("description", ignoreCase = true) }
            item(key = "profile-history-head") {
                Text("Profilverlauf", style = MaterialTheme.typography.titleMedium)
                Text("Bis zu 100 zuletzt gespeicherte Änderungen.", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(panels.showDescriptionChanges, { panels.showDescriptionChanges = it })
                    Text("Beschreibungstexte im Verlauf anzeigen")
                }
                if (visibleEvents.isEmpty()) Text("Keine sichtbaren Änderungen gespeichert.")
            }
            items(visibleEvents, key = { "event/${it.changeEventId}" }) { event ->
                PanelCard(historyEventLabel(event.eventType, event.fieldKey)) {
                    Text(historyDate(event.createdAt), style = MaterialTheme.typography.bodySmall)
                    val listing = panels.detail?.listings?.firstOrNull { it.listingId == event.listingId }
                    listing?.let { Text("Anzeige #${it.externalId}", style = MaterialTheme.typography.labelMedium) }
                    if (event.oldDisplay != null || event.newDisplay != null)
                        HistoryValues(event.oldDisplay, event.newDisplay)
                    val from = event.fromSnapshotId
                    val to = event.toSnapshotId
                    if (from != null && to != null) TextButton(onClick = { panels.compare(from, to) },
                        enabled = !actionsBusy) { Text("Snapshots vergleichen") }
                }
            }
            item(key = "profile-listings-head") {
                Text("Verknüpfte Anzeigen", style = MaterialTheme.typography.titleMedium)
                if (panels.detail == null) DetailStatus(panels)
            }
            items(panels.detail?.listings.orEmpty(), key = { "listing/${it.listingId}" }) { listing ->
                val context = LocalContext.current
                PanelCard("Anzeige #${listing.externalId}") {
                    Text(statusLabel(listing.status))
                    Text("Zuletzt gesehen: ${historyDate(listing.lastSeenAt)}", style = MaterialTheme.typography.bodySmall)
                    listing.url?.let { url -> TextButton(onClick = { openExternal(context, url) }) { Text("Original öffnen") } }
                }
            }
        }
    }
}

@Composable
private fun FactsContent(panels: ProfilePanels, content: @Composable (ProfileFacts) -> Unit) {
    val facts = panels.facts
    if (facts != null) content(facts)
    else {
        panels.factsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ?: CircularProgressIndicator(Modifier.size(24.dp))
        TextButton(onClick = panels::reload) { Text("Neu laden") }
    }
}

@Composable
private fun DetailStatus(panels: ProfilePanels) {
    panels.detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ?: CircularProgressIndicator(Modifier.size(24.dp))
    TextButton(onClick = panels::reload) { Text("Neu laden") }
}
