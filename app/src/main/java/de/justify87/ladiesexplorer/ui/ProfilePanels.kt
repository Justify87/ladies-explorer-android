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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class ProfileTab(val label: String) {
    Info("Info"), Services("Dienste"), Analysis("Analyse"), Notes("Notizen"), History("Verlauf")
}

/** Persist selection per canonical person and route, not across unrelated people. */
@Composable
internal fun rememberProfileTab(personId: Long?): Pair<ProfileTab, (ProfileTab) -> Unit> {
    var selections by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val prefix = "$personId:"
    val selected = selections.firstOrNull { it.startsWith(prefix) }?.substringAfter(':')
    val tab = ProfileTab.entries.firstOrNull { it.name == selected } ?: ProfileTab.Info
    return tab to { next ->
        if (personId != null) selections = ArrayList(
            selections.filterNot { it.startsWith(prefix) }.takeLast(99) + "$personId:${next.name}"
        )
    }
}

internal class ProfilePanels(
    private val core: CoreStore,
    private val scope: CoroutineScope,
    private val listingId: Long?,
    private val personId: Long?,
) {
    var facts by mutableStateOf<ProfileFacts?>(null)
        private set
    var detail by mutableStateOf<PersonDetail?>(null)
        private set
    var factsError by mutableStateOf<String?>(null)
        private set
    var detailError by mutableStateOf<String?>(null)
        private set
    var noteError by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var comparison by mutableStateOf<List<SnapshotDifference>?>(null)
        private set
    var comparisonError by mutableStateOf<String?>(null)
        private set
    var comparing by mutableStateOf(false)
        private set
    private var comparisonGeneration = 0
    var revision by mutableIntStateOf(0)
        private set

    fun reload() {
        if (!saving) {
            facts = null
            detail = null
            noteError = null
            revision++
        }
    }

    suspend fun load(snapshot: Long?, filters: String) {
        if (snapshot == null || personId == null) return
        factsError = null
        detailError = null
        try {
            facts = core.profileFacts(snapshot, filters)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { factsError = failure.message ?: "Profilfelder nicht verfügbar" }
        try {
            detail = core.personDetail(personId)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { detailError = failure.message ?: "Personendaten nicht verfügbar" }
    }

    fun saveNote(expected: String?, text: String) {
        val owner = personId ?: return
        val listing = listingId ?: return
        if (saving || detail == null) return
        saving = true
        noteError = null
        scope.launch {
            try {
                val note = text.takeUnless { it.isBlank() }
                core.savePersonNote(listing, owner, expected, note)
                detail = detail?.copy(privateNote = note)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { noteError = failure.message ?: "Notiz konnte nicht gespeichert werden" }
            finally { saving = false }
        }
    }

    fun compare(from: Long, to: Long) {
        val owner = personId ?: return
        val generation = ++comparisonGeneration
        comparing = true
        comparison = null
        comparisonError = null
        scope.launch {
            try {
                val result = core.compareSnapshots(owner, from, to)
                if (generation == comparisonGeneration) comparison = result
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == comparisonGeneration) comparisonError = failure.message ?: "Snapshots nicht verfügbar"
            } finally {
                if (generation == comparisonGeneration) comparing = false
            }
        }
    }

    fun closeComparison() {
        comparisonGeneration++
        comparing = false
        comparison = null
        comparisonError = null
    }
}

@Composable
internal fun rememberProfilePanels(profile: ProfileResponse?, personId: Long?, filters: String): ProfilePanels {
    val core = (LocalContext.current.applicationContext as LadiesApplication).coreStore
    // Owner/snapshot keyed child scope: late results can only update the old holder.
    val scope = rememberCoroutineScope()
    val panels = remember(core, scope, profile?.listingId, profile?.snapshotId, personId) {
        ProfilePanels(core, scope, profile?.listingId, personId)
    }
    LaunchedEffect(panels, filters, panels.revision) { panels.load(profile?.snapshotId, filters) }
    return panels
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
        val columns = if (maxWidth >= 480.dp) 3 else 2
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

private fun List<ProfileField>.rows() = map { it.label to it.values.joinToString(" · ") }

@Composable
private fun DescriptionCard(profile: ProfileResponse) {
    var expanded by rememberSaveable(profile.snapshotId) { mutableStateOf(false) }
    val text = profile.description.ifBlank { "Keine Beschreibung gespeichert." }
    PanelCard("Über das Profil") {
        SelectionContainer {
            Text(text, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
        }
        if (text.length > 180 || text.count { it == '\n' } >= 6) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Weniger" else "Mehr anzeigen") }
        }
    }
}

@Composable
private fun NotesPanel(panels: ProfilePanels, owner: Long?, busy: Boolean) {
    val detail = panels.detail
    PanelCard("Private Personennotiz") {
        if (detail == null || owner == null) {
            if (panels.detailError == null && owner != null) CircularProgressIndicator(Modifier.size(24.dp))
            else Text(panels.detailError ?: "Person nicht verfügbar.")
            TextButton(onClick = panels::reload, enabled = !busy) { Text("Neu laden") }
        } else {
            val saved = detail.privateNote
            var draft by rememberSaveable(owner, saved) { mutableStateOf(saved.orEmpty()) }
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Notiz") }, minLines = 4, enabled = !busy && !panels.saving)
            Text("Lokal gespeichert, unabhängig von einzelnen Anzeigen.", style = MaterialTheme.typography.bodySmall)
            panels.noteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { panels.saveNote(saved, draft) },
                    enabled = !busy && !panels.saving && draft != saved.orEmpty()) { Text("Speichern") }
                TextButton(onClick = { draft = saved.orEmpty(); panels.reload() },
                    enabled = !busy && !panels.saving) { Text("Verwerfen / neu laden") }
            }
            if (panels.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ComparisonDialog(panels: ProfilePanels) {
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
                            Text(difference.field, style = MaterialTheme.typography.titleSmall)
                            SelectionContainer { Text("Vorher: ${difference.before ?: "Nicht gespeichert"}") }
                            SelectionContainer { Text("Nachher: ${difference.after ?: "Nicht gespeichert"}") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = panels::closeComparison) { Text("Schließen") } },
    )
}

/** Both routes use the same lazy tab content and local typed projections. */
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
    onConfirm: ((Long) -> Unit)?,
    onOpenDetail: (() -> Unit)? = null,
) {
    item(key = "profile-tabs") {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            ProfileTab.entries.forEach { option ->
                Tab(selected = tab == option, onClick = { selectTab(option) },
                    text = { Text(option.label, maxLines = 1) },
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
                        panels.facts.orEmptyFilters())
                }
            }
            item(key = "profile-traits") {
                PanelCard("Merkmale") { FactsContent(panels) { FieldGrid(it.traits.rows()) } }
            }
            item(key = "profile-times") {
                PanelCard("Öffnungszeiten") { FactsContent(panels) { FieldGrid(it.openingTimes.rows()) } }
            }
            item(key = "profile-description") { DescriptionCard(profile) }
        }
        ProfileTab.Services -> item(key = "profile-services") {
            PanelCard("Dienste") { FactsContent(panels) { FieldGrid(it.services.rows()) } }
        }
        ProfileTab.Analysis -> {
            item(key = "profile-analysis") {
                PanelCard("Gespeicherte Evidenz") {
                    val data = panels.detail
                    FieldGrid(listOf("Bilder im aktuellen Profil" to media.count { it.kind == "IMAGE" }.toString()) +
                        if (data == null) emptyList() else listOf(
                            "Anzeigen" to data.listings.size.toString(), "Namen" to data.knownNames.toString(),
                            "Orte" to data.knownCities.toString(), "Archivierte Bildreferenzen (Assets)" to data.archivedImages.toString(),
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
        ProfileTab.Notes -> item(key = "profile-notes") { NotesPanel(panels, identity?.personId, actionsBusy) }
        ProfileTab.History -> {
            item(key = "profile-history-head") {
                Text("Profilverlauf", style = MaterialTheme.typography.titleMedium)
                if (history.isEmpty()) Text("Keine Änderungen gespeichert.")
                ComparisonDialog(panels)
            }
            items(history, key = { "event/${it.changeEventId}" }) { event ->
                PanelCard(event.fieldKey ?: event.eventType) {
                    Text(event.createdAt, style = MaterialTheme.typography.bodySmall)
                    event.oldJson?.let { SelectionContainer { Text("Vorher: $it") } }
                    event.newJson?.let { SelectionContainer { Text("Nachher: $it") } }
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
                    Text("Zuletzt gesehen: ${listing.lastSeenAt}", style = MaterialTheme.typography.bodySmall)
                    listing.url?.let { url -> TextButton(onClick = { openExternal(context, url) }) { Text("Original öffnen") } }
                }
            }
        }
    }
}

private fun ProfileFacts?.orEmptyFilters(): List<Pair<String, String>> =
    this?.filters.orEmpty().map { it.label to evidenceLabel(it.status) }

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
