package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class ProfileSection(val label: String) {
    Description("Beschreibung"), Services("Alle Dienste und Filterbelege"), Analysis("Analyse / Identität"), Notes("Meine Notiz"), History("Verlauf / verknüpfte Anzeigen")
}

@Stable
internal class ProfileSections(initial: List<String> = emptyList()) {
    var entries by mutableStateOf(initial.distinct())
        private set
    fun restore(values: List<String>) {
        entries = values.distinct().filter { name -> name == "history-description" || ProfileSection.entries.any { it.name == name } }
    }
    fun expanded(section: ProfileSection) = section.name in entries
    fun toggle(section: ProfileSection) {
        entries = if (expanded(section)) entries - section.name else entries + section.name
    }

    // A history filter is presentation state, not part of a replaceable read.
    val showDescriptionChanges get() = "history-description" in entries
    fun setShowDescriptionChanges(value: Boolean) {
        entries = entries - "history-description" + if (value) listOf("history-description") else emptyList()
    }
}

@Composable
internal fun rememberProfileSections(): ProfileSections = rememberSaveable(
    saver = listSaver<ProfileSections, String>(save = { it.entries }, restore = { ProfileSections(it) })
) { ProfileSections() }

/** Shared scroll, section state, read demand and dialogs. The leading slot
 * supplies only context-specific hero/status/navigation; no second profile route. */
@Composable
internal fun ProfileFlow(
    state: LazyListState,
    model: ProfileReadModel?,
    filters: String,
    token: ProfileIdentityToken?,
    actionsBusy: Boolean,
    notes: Map<Long, NoteEditorState>,
    noteActions: NoteEditorActions,
    isProfileCurrent: (ProfileIdentityToken) -> Boolean,
    onConfirmIdentity: (ProfileIdentityToken, Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    paddedDetails: Boolean = false,
    photoRequest: Int = 0,
    swipeRestore: SwipePresentation? = null,
    swipeRunId: Long? = null,
    swipeCapture: SwipePresentationCapture? = null,
    onSwipeRestoreConsumed: (SwipePresentation) -> Unit = {},
    panelReads: ProfilePanelReads? = null,
    leading: ProfileFlowRows.() -> Unit,
) {
    val owner = model?.identity?.personId
    var lastOwner by rememberSaveable { mutableStateOf<Long?>(null) }
    val ownerChanged = owner != null && lastOwner != null && owner != lastOwner
    val presentationOwner = owner ?: lastOwner
    val projection = model?.let {
        ProfileProjectionKey(it.profile.listingId, it.identity.personId, it.profile.snapshotId, it.revision, filters)
    }
    val sections = key(presentationOwner) { rememberProfileSections() }
    val mediaSelection = key(presentationOwner) { rememberProfileMediaSelection() }
    val scroll = key(presentationOwner) { rememberProfileScrollMemory(ownerChanged) }
    val restore = remember(presentationOwner) { ProfileRestoreCoordinator() }
    DisposableEffect(swipeCapture) { onDispose { swipeCapture?.read = { null } } }
    SideEffect {
        swipeCapture?.read = {
            projection?.let { current ->
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
                val anchor = if (scroll.recovering) scroll.anchor else (item?.key as? String)?.let {
                    ProfileScrollAnchor(it, state.firstVisibleItemScrollOffset)
                } ?: scroll.anchor
                SwipePresentation(current.listingId, current.ownerId, current.snapshotId, current.filters,
                    mediaSelection.id, anchor?.key, anchor?.offset ?: 0, sections.entries.toList(), swipeRunId,
                    mediaSelection.index)
            }
        }
    }
    SideEffect {
        scroll.projectionChanged(projection)
        restore.synchronize(swipeRestore, projection, swipeRunId, mediaSelection, sections, scroll)
        if (owner != null) lastOwner = owner
    }
    val panels = rememberProfilePanels(model, filters, panelReads)
    val busy = actionsBusy || token == null
    val boundNotes = noteActions.boundTo(token, isProfileCurrent)
    val scope = rememberCoroutineScope()
    val noteDemand = remember(model?.profile?.listingId, owner, model?.profile?.snapshotId,
        model?.revision, scope) { ProfileNoteDemand(scope) }
    DisposableEffect(noteDemand) { onDispose { noteDemand.close() } }
    val noteExpanded = sections.expanded(ProfileSection.Notes)
    LaunchedEffect(noteDemand, noteExpanded, token, busy) {
        if (noteExpanded && !busy) noteDemand.request(token, false, isProfileCurrent, noteActions.load)
    }
    ComparisonDialog(panels, active = !busy)
    var confirmation by remember(token) { mutableStateOf<Long?>(null) }
    val current = { token?.let(isProfileCurrent) == true }
    val selected = confirmation
    if (selected != null && token != null) {
        IdentityConfirmationDialog(selected, enabled = !busy,
            onConfirm = {
                confirmation = null
                if (!busy && current()) onConfirmIdentity(token, selected)
            },
            onDismiss = { confirmation = null })
    }
    val rows = ProfileFlowRows(mediaSelection).apply {
        leading()
        if (model != null) profileContent(model.profile, model.identity, model.person, model.media,
            panels, sections, model.candidates, model.history, busy,
            owner?.let(notes::get) ?: NoteEditorState(), boundNotes,
            noteDemand = noteDemand,
            onRetryNote = { if (!busy) noteDemand.request(token, true, isProfileCurrent, noteActions.load) },
            isCurrent = current,
            onConfirm = { target -> if (!busy && current()) confirmation = target })
    }.build()
    val keys = rows.map { it.key }
    val available = model != null
    var appliedPhotoRequest by remember { mutableIntStateOf(photoRequest) }
    // Restoring an anchor is itself demand. Avoid restoring an offset into a
    // temporarily short loading row or treating a not-yet-loaded listing as absent.
    val recovering = scroll.recovering
    val phase = restore.phase
    val canRestore = restore.canRestore(swipeRestore)
    val terminal = restore.terminal(swipeRestore)
    val consume by rememberUpdatedState(onSwipeRestoreConsumed)
    LaunchedEffect(swipeRestore, terminal, busy, token) {
        if (swipeRestore != null && terminal && !busy && current()) consume(swipeRestore)
    }
    // The same real row manifest declares both viewport and recovery demand.
    val manifest = rows.map { it.key to it.read }
    val latestRows by rememberUpdatedState(rows)
    LaunchedEffect(panels, state, manifest, projection) {
        if (!available) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }.collect { visible ->
            val reads = visibleProfileReads(latestRows, visible)
            if (ProfileRowRead.Facts in reads) kotlinx.coroutines.coroutineScope {
                launch { panels.loadFacts(checkNotNull(model).profile.snapshotId, filters) }
                if (ProfileRowRead.Detail in reads) launch { panels.loadDetail() }
            }
            else if (ProfileRowRead.Detail in reads) panels.loadDetail()
        }
    }
    val dependency = if (canRestore) scroll.dependency(rows) else null
    LaunchedEffect(panels, projection, dependency) {
        if (available) when (dependency) {
            ProfileRowRead.Facts -> panels.loadFacts(checkNotNull(model).profile.snapshotId, filters)
            ProfileRowRead.Detail -> panels.loadDetail()
            else -> Unit // Notes demand already uses the expanded section and admitted token.
        }
    }
    val noteState = owner?.let(notes::get) ?: NoteEditorState()
    val waitingForAnchor = when (dependency) {
        ProfileRowRead.Facts -> panels.facts == null && panels.factsError == null
        ProfileRowRead.Detail -> panels.detail == null && panels.detailError == null
        ProfileRowRead.Note -> noteDemand.status != ProfileNoteDemand.Status.Settled || noteState.loading
        null -> false
    }
    val readFailed = when (dependency) {
        ProfileRowRead.Facts -> panels.factsError != null
        ProfileRowRead.Detail -> panels.detailError != null
        ProfileRowRead.Note -> noteState.readError
        null -> false
    }
    // A single coroutine processes the actual anchor or explicit photo jump.
    LaunchedEffect(state, scroll, projection, keys, recovering, waitingForAnchor, readFailed,
        photoRequest, swipeRestore, phase, canRestore) {
        if (!available || (!canRestore && !terminal)) return@LaunchedEffect
        if (appliedPhotoRequest != photoRequest && keys.isNotEmpty()) {
            snapshotFlow { state.layoutInfo.totalItemsCount }.first { it == keys.size }
            state.scrollToItem(0)
            scroll.returnToHero(keys.first())
            restore.complete(swipeRestore)
            appliedPhotoRequest = photoRequest
        }
        if (waitingForAnchor) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it == keys.size }
        scroll.target(keys, readFailed)?.let { (index, offset) ->
            state.scrollToItem(index)
            val measured = snapshotFlow {
                state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == keys[index] }?.size
            }.first { it != null } ?: 0
            val safeOffset = restoredRowOffset(offset, measured)
            if (safeOffset > 0) state.scrollToItem(index, safeOffset)
            scroll.restored()
            restore.complete(swipeRestore)
        }
        snapshotFlow {
            val index = state.firstVisibleItemIndex
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            (item?.key as? String)?.let { ProfileScrollAnchor(it, state.firstVisibleItemScrollOffset) }
        }.collect { anchor ->
            if (anchor != null && anchor.key in keys) scroll.observe(anchor.key, anchor.offset)
        }
    }
    LazyColumn(state = state, modifier = modifier, contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows, key = { it.key }) { row ->
            if (paddedDetails && row.key != "hero" && row.key != "media") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { row.content() }
            } else row.content()
        }
    }
}

@Composable
private fun SectionToggle(
    section: ProfileSection, sections: ProfileSections, hint: String? = null, hintError: Boolean = false,
) {
    val expanded = sections.expanded(section)
    TextButton(onClick = { sections.toggle(section) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
            stateDescription = if (expanded) "Ausgeklappt" else "Eingeklappt"
        }) {
        Column(Modifier.weight(1f)) {
            Text(section.label, modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (hintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null)
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
private fun ProfileGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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

/** Promote one existing field; aliases with distinct values remain visible. */
internal fun ProfileFacts.overviewAndTraits(): Pair<List<Pair<String, String>>, List<ProfileField>> {
    val promoted = traits.indexOfFirst {
        it.label.lowercase() in setOf("busen", "oberweite", "brustgröße", "brustgroesse", "brust")
    }
    val first = traits.getOrNull(promoted)
    val overview = listOf(if (first == null) "Busen" to "Unbekannt"
        else first.label to first.values.joinToString(" · ")) +
        important.map { it.label to evidenceLabel(it.status) }
    return overview to traits.filterIndexed { index, _ -> index != promoted }
}

internal data class ProfileServiceRows(
    val source: List<Pair<String, String>>,
    val filters: List<Pair<String, String>>,
) {
    val count get() = source.size + filters.size
    fun preview(expanded: Boolean): ProfileServiceRows =
        if (expanded) this else copy(source = source.take(4), filters = filters.take(4))
}

internal fun ProfileFacts.serviceRows(): ProfileServiceRows = ProfileServiceRows(
    source = services.flatMap { field -> field.values.map { field.label to it } },
    filters = filters.map { "${it.label} (${it.group})" to evidenceLabel(it.status) },
)

@Composable
private fun ProfileDescription(profile: ProfileResponse, sections: ProfileSections) {
    val expanded = sections.expanded(ProfileSection.Description)
    var overflow by remember(profile.snapshotId) { mutableStateOf(false) }
    val text = profile.description.ifBlank { "Keine Beschreibung gespeichert." }
    ProfileGroup("Über das Profil") {
        SelectionContainer {
            Text(text, maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis, onTextLayout = {
                    if (!expanded) overflow = it.hasVisualOverflow
                })
        }
        if (expanded || overflow) {
            TextButton(onClick = { sections.toggle(ProfileSection.Description) }) { Text(if (expanded) "Weniger" else "Mehr anzeigen") }
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
    demand: ProfileNoteDemand,
    onRetry: () -> Unit,
) {
    ProfileGroup("Private Personennotiz") {
        val draft = state.draft
        val loading = demand.status != ProfileNoteDemand.Status.Settled || state.loading
        val editable = owner != null && !busy && !loading && !state.readError
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
                    enabled = !busy && !loading && draft.dirty) { Text("Verwerfen") }
            }
        } else if (owner == null) Text("Person nicht verfügbar.")
        if (loading || busy && draft != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (owner != null && (state.readError || state.error != null || draft == null && !state.loading)) {
            TextButton(onClick = onRetry,
                enabled = !busy && !loading) { Text("Neu laden") }
        }
    }
}

@Composable
private fun ComparisonDialog(panels: ProfilePanels, active: Boolean) {
    LaunchedEffect(panels, active) { if (!active) panels.closeComparison() }
    if (!active) return
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
private fun ProfileFlowRows.profileContent(
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
    noteDemand: ProfileNoteDemand,
    onRetryNote: () -> Unit,
    isCurrent: () -> Boolean,
    onConfirm: (Long) -> Unit,
) {
    item(key = "profile-facts", read = ProfileRowRead.Facts) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileGroup("Übersicht") {
                FactsContent(panels) { facts ->
                    val (overview, traits) = facts.overviewAndTraits()
                    if (facts.openingTimes.isNotEmpty()) ProfileGroup("Öffnungszeiten") { FieldValues(facts.openingTimes) }
                    FieldGrid(overview + listOfNotNull(person?.let { "Anzeigen" to it.listingCount.toString() }))
                    if (traits.isNotEmpty()) ProfileGroup("Merkmale") { FieldValues(traits) }
                }
            }
        }
    }
    if (profile.description.isNotBlank()) item(key = "profile-description") { ProfileDescription(profile, sections) }
    item(key = "profile-services", read = ProfileRowRead.Facts) {
        ProfileGroup("Dienste und Filterbelege") {
            FactsContent(panels) { facts ->
                val all = facts.serviceRows()
                val expanded = sections.expanded(ProfileSection.Services)
                val shown = all.preview(expanded)
                if (shown.source.isNotEmpty()) FieldGrid(shown.source)
                if (shown.filters.isNotEmpty()) {
                    Text("Filterbelege", style = MaterialTheme.typography.labelLarge)
                    FieldGrid(shown.filters)
                }
                if (all.count == 0) Text("Keine Angaben gespeichert.")
                if (expanded || shown.count < all.count) {
                    TextButton(onClick = { sections.toggle(ProfileSection.Services) }) {
                        Text(if (expanded) "Weniger anzeigen" else "Alle ${all.count} Angaben anzeigen")
                    }
                    if (!expanded) Text("${shown.count} von ${all.count} Angaben sichtbar",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (!profile.phoneDisplay.isNullOrBlank()) item(key = "profile-contact") {
        ProfileGroup("Kontakt") {
            ProfileContactActions(profile.phoneDisplay, actionsBusy, isCurrent)
        }
    }
    item(key = "analysis-toggle") { SectionToggle(ProfileSection.Analysis, sections) }
    if (sections.expanded(ProfileSection.Analysis)) {
        item(key = "profile-analysis", read = ProfileRowRead.Detail) {
            ProfileGroup("Gespeicherte Evidenz") {
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
            ProfileGroup("Person #${candidate.personId} · ${candidate.decision}") {
                Text(listOfNotNull("Telefon".takeIf { candidate.phoneMatch },
                    "Quellprofil".takeIf { candidate.sourceProfileMatch },
                    "${candidate.strongImageMatches} starke Bildtreffer".takeIf { candidate.imageMatches > 0u })
                    .joinToString(" · ").ifBlank { "Keine bestätigende Evidenz" })
                OutlinedButton(onClick = { if (isCurrent()) onConfirm(candidate.personId) },
                    enabled = !actionsBusy) { Text("Zusammenführung prüfen") }
            }
        }
    }
    item(key = "notes-toggle") {
        SectionToggle(ProfileSection.Notes, sections, noteSectionHint(note),
            hintError = note.error != null || note.draft?.conflict == true)
    }
    if (sections.expanded(ProfileSection.Notes)) item(key = "profile-notes", read = ProfileRowRead.Note) {
        NotesPanel(profile.listingId, identity?.personId, note, noteActions, actionsBusy, noteDemand, onRetryNote)
    }
    item(key = "history-toggle") { SectionToggle(ProfileSection.History, sections) }
    if (sections.expanded(ProfileSection.History)) {
        val visibleEvents = if (sections.showDescriptionChanges) history
            else history.filterNot { it.fieldKey.equals("description", ignoreCase = true) }
        item(key = "profile-history-head", read = ProfileRowRead.Detail) {
            Text("Profilverlauf", style = MaterialTheme.typography.titleMedium)
            Text("Bis zu 100 zuletzt gespeicherte Änderungen.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(sections.showDescriptionChanges, sections::setShowDescriptionChanges)
                Text("Beschreibungstexte im Verlauf anzeigen")
            }
            if (panels.detail == null) DetailStatus(panels)
            if (visibleEvents.isEmpty()) Text("Keine sichtbaren Änderungen gespeichert.")
        }
        items(visibleEvents, key = { "event/${it.changeEventId}" }, read = ProfileRowRead.Detail) { event ->
            ProfileGroup(historyEventLabel(event.eventType, event.fieldKey)) {
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
        item(key = "profile-listings-head", read = ProfileRowRead.Detail) {
            Text("Verknüpfte Anzeigen", style = MaterialTheme.typography.titleMedium)
            if (panels.detail == null) DetailStatus(panels)
        }
        items(panels.detail?.listings.orEmpty(), key = { "listing/${it.listingId}" }, read = ProfileRowRead.Detail) { listing ->
            val context = LocalContext.current
            ProfileGroup("Anzeige #${listing.externalId}") {
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
