package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.launch

internal enum class ProfileSection(val label: String) {
    Analysis("Analyse / Identität"), Notes("Meine Notiz"), History("Verlauf / verknüpfte Anzeigen")
}

@Stable
internal class ProfileSections(initial: List<String> = emptyList()) {
    var entries by mutableStateOf(initial.distinct())
        private set
    fun expanded(section: ProfileSection) = section.name in entries
    fun toggle(section: ProfileSection) {
        entries = if (expanded(section)) entries - section.name else entries + section.name
    }
}

@Composable
internal fun rememberProfileSections(): ProfileSections = rememberSaveable(
    saver = listSaver<ProfileSections, String>(save = { it.entries }, restore = { ProfileSections(it) })
) { ProfileSections() }

@Composable
internal fun rememberProfileNote(
    token: ProfileIdentityToken?,
    actions: NoteEditorActions,
    busy: Boolean,
    expanded: Boolean,
) {
    var requested by remember(token) { mutableStateOf(false) }
    LaunchedEffect(token, busy, expanded) {
        if (token != null && !busy && expanded && !requested) {
            actions.load(token.listingId, token.ownerId)
            requested = true
        }
    }
}

/** The only profile scroll container; context-specific navigation and hero are slots. */
@Composable
internal fun ProfileFlow(
    state: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(state = state, modifier = modifier, contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun SectionToggle(section: ProfileSection, sections: ProfileSections, hint: String? = null) {
    val expanded = sections.expanded(section)
    TextButton(onClick = { sections.toggle(section) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Column(Modifier.weight(1f)) {
            Text(section.label, modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "Einklappen" else "Ausklappen")
    }
}

internal fun noteSectionHint(note: NoteEditorState): String? = when {
    note.draft?.conflict == true -> "Notizkonflikt"
    note.error != null -> note.error
    note.loading -> "Notiz wird geladen"
    note.draft?.dirty == true -> "Ungespeicherter Entwurf"
    else -> null
}

@Composable
private fun PanelCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        content()
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
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun FieldValues(rows: List<ProfileField>) {
    FieldGrid(rows.map { it.label to it.values.joinToString(" · ") })
}

private fun ProfileFacts.importantRows(): List<Pair<String, String>> {
    val trait = traits.firstOrNull {
        it.label.lowercase() in setOf("busen", "oberweite", "brustgröße", "brustgroesse", "brust")
    }
    return listOf("Busen" to (trait?.values?.joinToString(" · ") ?: "Unbekannt")) +
        important.map { it.label to evidenceLabel(it.status) }
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

/** Both routes render identical inline sections in a single lazy list. */
internal fun LazyListScope.profileContent(
    profile: ProfileResponse,
    identity: ProfileIdentity?,
    person: PersonSummary?,
    media: List<ProfileMediaItem>,
    panels: ProfilePanels,
    sections: ProfileSections,
    candidates: List<IdentityCandidate>,
    history: List<ChangeRecord>,
    actionsBusy: Boolean,
    note: NoteEditorState,
    noteActions: NoteEditorActions,
    isCurrent: () -> Boolean,
    onConfirm: (Long) -> Unit,
) {
    item(key = "profile-facts") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelCard("Übersicht") {
                if (person != null) FieldGrid(listOf("Anzeigen" to person.listingCount.toString()))
                FactsContent(panels) { facts ->
                    FieldGrid(facts.importantRows())
                    if (facts.openingTimes.isNotEmpty()) PanelCard("Öffnungszeiten") { FieldValues(facts.openingTimes) }
                    val traits = facts.traits.filterNot {
                        it.label.lowercase() in setOf("busen", "oberweite", "brustgröße", "brustgroesse", "brust")
                    }
                    if (traits.isNotEmpty()) PanelCard("Merkmale") { FieldValues(traits) }
                }
            }
        }
    }
    if (profile.description.isNotBlank()) item(key = "profile-description") { DescriptionCard(profile) }
    item(key = "profile-services") {
        PanelCard("Dienste und Filterbelege") {
            FactsContent(panels) { facts ->
                if (facts.services.isNotEmpty()) FieldValues(facts.services)
                if (facts.filters.isNotEmpty()) FieldGrid(facts.filters.map {
                    "${it.label} (${it.group})" to evidenceLabel(it.status)
                })
                if (facts.services.isEmpty() && facts.filters.isEmpty()) Text("Keine Angaben gespeichert.")
            }
        }
    }
    item(key = "analysis-toggle") { SectionToggle(ProfileSection.Analysis, sections) }
    if (sections.expanded(ProfileSection.Analysis)) {

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
                    OutlinedButton(onClick = { if (isCurrent()) onConfirm(candidate.personId) },
                        enabled = !actionsBusy) { Text("Zusammenführung prüfen") }
                }
            }
        }
    item(key = "notes-toggle") { SectionToggle(ProfileSection.Notes, sections, noteSectionHint(note)) }
    if (sections.expanded(ProfileSection.Notes)) item(key = "profile-notes") {
        NotesPanel(profile.listingId, identity?.personId, note, noteActions, actionsBusy)
    }
    item(key = "history-toggle") { SectionToggle(ProfileSection.History, sections) }
    if (sections.expanded(ProfileSection.History)) {
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
                    if (from != null && to != null) TextButton(onClick = { if (isCurrent()) panels.compare(from, to) },
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
                    listing.url?.let { url -> TextButton(enabled = !actionsBusy, onClick = { if (isCurrent()) openExternal(context, url) }) { Text("Original öffnen") } }
                }
            }
    }
}

@Composable
private fun FactsContent(panels: ProfilePanels, content: @Composable (ProfileFacts) -> Unit) {
    val facts = panels.facts
    if (facts != null) content(facts)
    else {
        val error = panels.factsError
        if (error == null) CircularProgressIndicator(Modifier.size(24.dp))
        else {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = panels::retryFacts) { Text("Neu laden") }
        }
    }
}

@Composable
private fun DetailStatus(panels: ProfilePanels) {
    val error = panels.detailError
    if (error == null) CircularProgressIndicator(Modifier.size(24.dp))
    else {
        Text(error, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = panels::retryDetail) { Text("Neu laden") }
    }
}
