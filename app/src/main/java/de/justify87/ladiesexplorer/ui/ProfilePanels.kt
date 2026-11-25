package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class ProfileSection(val label: String, val defaultExpanded: Boolean = false) {
    Match("Übereinstimmung mit deiner Suche", defaultExpanded = true),
    Description("Über das Profil"), Analysis("Analyse / Identität"), Notes("Meine Notiz"), Encounters("Meine Begegnungen"), Prices("Manuelle Angebotspreise"), History("Verlauf / verknüpfte Anzeigen")
}

@Stable
internal class ProfileSections(initial: List<String> = emptyList()) {
    var entries by mutableStateOf(validEntries(initial))
        private set
    fun restore(values: List<String>) {
        entries = validEntries(values)
    }
    private fun validEntries(values: List<String>) = values.distinct().filter { name ->
        name == "history-description" || ProfileSection.entries.any { it.name == name }
    }
    // Entries store deviations from each section's default, including a collapsed Match.
    fun expanded(section: ProfileSection) = section.defaultExpanded != (section.name in entries)
    fun toggle(section: ProfileSection) {
        entries = if (section.name in entries) entries - section.name else entries + section.name
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

@Composable
private fun <R, V, D, S> recordPanel(
    editor: ProfileRecordEditor<R, V, D, S>?,
    token: ProfileIdentityToken?,
    expanded: Boolean,
    busy: Boolean,
): ProfileRecordPanel<S>? {
    val demanded = expanded || editor?.matchesContext(token) == true
    DisposableEffect(editor, token, demanded, busy) {
        onDispose { editor?.detach(token) }
    }
    LaunchedEffect(editor, token, demanded, busy, editor?.readRevision) {
        if (demanded && !busy && token != null) editor?.load(token)
    }
    return editor?.panel?.takeIf { it.token == token }
}

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
    onGallerySelection: (ProfileMediaItem?) -> Unit = {},
    userScrollEnabled: Boolean = true,
    encounters: EncounterEditor? = null,
    prices: PriceEditor? = null,
    fallbackIdentity: HeroIdentity? = null,
    fallbackMedia: List<ProfileMediaItem> = emptyList(),
    onDiscoveryContextHeight: ((Int) -> Unit)? = null,
    preparedPanels: PreparedPanelData? = null,
    afterHeading: ProfileFlowRows.() -> Unit = {},
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
    val selectedMedium = model?.media?.ifEmpty { fallbackMedia }?.let { it.getOrNull(mediaSelection.resolve(it, true)) }
    SideEffect { onGallerySelection(selectedMedium) }
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
    val panels = rememberProfilePanels(model, filters, panelReads, preparedPanels)
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
    val encounterPanel = recordPanel(encounters, token, sections.expanded(ProfileSection.Encounters), busy)
    val pricePanel = recordPanel(prices, token, sections.expanded(ProfileSection.Prices), busy)
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
        val phone = model?.profile?.phoneDisplay
        if (phone?.let(::phoneForWhatsApp) != null) item(key = "profile-contact") {
            ProfileContactActions(phone, busy, current)
        }
        val heading = model?.let { heroIdentity(it.profile, it.identity, it.fromArchive) } ?: fallbackIdentity
        if (heading != null) item(key = "profile-heading") {
            CompactProfileHeading(heading, model?.person?.listingCount, model?.profile?.publishedAt)
        }
        afterHeading()
        if (model != null) profileContent(model.profile, model.identity, model.media,
            panels, sections, model.candidates, model.history, busy,
            owner?.let(notes::get) ?: NoteEditorState(), boundNotes,
            noteDemand = noteDemand,
            onRetryNote = { if (!busy) noteDemand.request(token, true, isProfileCurrent, noteActions.load) },
            encounters = encounters, encounterPanel = encounterPanel,
            onRetryEncounters = { panel -> scope.launch { encounters?.retry(panel) } },
            prices = prices, pricePanel = pricePanel,
            onRetryPrices = { panel -> scope.launch { prices?.retry(panel) } },
            isCurrent = current,
            onConfirm = { target -> if (!busy && current()) confirmation = target })
    }.build()
    val keys = rows.map { it.key }
    val available = model != null
    var appliedPhotoRequest by remember { mutableIntStateOf(photoRequest) }
    // Anchor restoration triggers demand; wait for data before applying the offset.
    val recovering = scroll.recovering
    val phase = restore.phase
    val canRestore = restore.canRestore(swipeRestore)
    val terminal = restore.terminal(swipeRestore)
    val consume by rememberUpdatedState(onSwipeRestoreConsumed)
    LaunchedEffect(swipeRestore, terminal, busy, token) {
        if (swipeRestore != null && terminal && !busy && current()) consume(swipeRestore)
    }
    val dependency = if (canRestore) scroll.dependency(rows) else null
    val latestRows by rememberUpdatedState(rows)
    val anchorRead by rememberUpdatedState(dependency)
    LaunchedEffect(panels, state) {
        if (model == null) return@LaunchedEffect
        snapshotFlow {
            val visible = state.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
            visibleProfileReads(latestRows, visible) + listOfNotNull(anchorRead)
        }.collect { reads ->
            // Demand survives scrolling; projection replacement owns cancellation.
            if (ProfileRowRead.Facts in reads) launch { panels.loadFacts(model.profile.snapshotId, filters) }
            if (ProfileRowRead.Detail in reads) launch { panels.loadDetail() }
        }
    }
    val noteState = owner?.let(notes::get) ?: NoteEditorState()
    val waitingForAnchor = when (dependency) {
        ProfileRowRead.Facts -> panels.facts == null && panels.factsError == null
        ProfileRowRead.Detail -> panels.detail == null && panels.detailError == null
        ProfileRowRead.Note -> noteDemand.status != ProfileNoteDemand.Status.Settled || noteState.loading
        ProfileRowRead.Encounters -> encounterPanel?.snapshot == null && encounterPanel?.error == null
        ProfileRowRead.Prices -> pricePanel?.snapshot == null && pricePanel?.error == null
        null -> false
    }
    val readFailed = when (dependency) {
        ProfileRowRead.Facts -> panels.factsError != null
        ProfileRowRead.Detail -> panels.detailError != null
        ProfileRowRead.Note -> noteState.readError
        ProfileRowRead.Encounters -> encounterPanel?.error != null
        ProfileRowRead.Prices -> pricePanel?.error != null
        null -> false
    }
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
            state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }?.let { item ->
                (item.key as? String)?.let { scroll.observe(it, state.firstVisibleItemScrollOffset) }
            }
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
    val contentInset = shellContentInset()
    LazyColumn(state = state, modifier = modifier, contentPadding = contentPadding,
        userScrollEnabled = userScrollEnabled) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val section = row.section
            val first = section != null && rows.getOrNull(index - 1)?.section != section
            val last = section != null && rows.getOrNull(index + 1)?.section != section
            val gap = if (index == rows.lastIndex || section != null && !last) 0.dp else ShellMetrics.inset
            val horizontal = if (paddedDetails && row.key != "hero" && row.key != "media") contentInset else 0.dp
            val measure = if (row.key == "discover-context" && onDiscoveryContextHeight != null)
                Modifier.onSizeChanged { onDiscoveryContextHeight(it.height) } else Modifier
            Column(Modifier.fillMaxWidth().then(measure).padding(horizontal = horizontal).padding(bottom = gap)) {
                if (section != null) {
                    if (first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.fillMaxWidth().padding(bottom = if (first) 0.dp else ShellMetrics.small),
                        verticalArrangement = Arrangement.spacedBy(ShellMetrics.small)) { row.content() }
                } else row.content()
            }
        }
    }
}

internal fun profilePublicationLabel(raw: String?): String? {
    val source = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val date = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN)
    val time = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN)
    val offsetTime = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm xxx", Locale.GERMAN)
    return runCatching { OffsetDateTime.parse(source).format(offsetTime) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(source).format(time) }.getOrNull()
        ?: runCatching { LocalDate.parse(source).format(date) }.getOrNull()
}

internal fun compactProfileMetadata(identity: HeroIdentity, listingCount: UInt?, publishedAt: String?): List<String> =
    listOf(
        listOfNotNull(identity.place.takeIf { it.isNotBlank() }, identity.status).joinToString(" · "),
        listOfNotNull(listingCount?.let { "Anzeigen: $it" },
            profilePublicationLabel(publishedAt)?.let { "Veröffentlicht: $it" }).joinToString(" · "),
    ).filter { it.isNotBlank() }

@Composable
private fun CompactProfileHeading(identity: HeroIdentity, listingCount: UInt?, publishedAt: String?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(identity.title, modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        compactProfileMetadata(identity, listingCount, publishedAt).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProfileDisclosure(
    title: String, expanded: Boolean, onToggle: () -> Unit,
    hint: String? = null, hintError: Boolean = false, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ProgressiveSectionHeader(title, expanded, onToggle, hint = hint, hintError = hintError)
        if (expanded) Column(Modifier.fillMaxWidth().padding(bottom = ShellMetrics.small),
            verticalArrangement = Arrangement.spacedBy(ShellMetrics.small), content = content)
    }
}

@Composable
private fun SectionToggle(
    section: ProfileSection, sections: ProfileSections, hint: String? = null, hintError: Boolean = false,
) {
    ProgressiveSectionHeader(section.label, sections.expanded(section), { sections.toggle(section) },
        hint = hint, hintError = hintError)
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
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun FieldGrid(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        val columns = if (maxWidth < 340.dp || fontScale >= 1.5f) 1 else 2
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(ShellMetrics.inset), verticalArrangement = Arrangement.spacedBy(ShellMetrics.small)) {
                rows.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ShellMetrics.inset)) {
                        row.forEach { (label, value) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
}

internal fun evidenceLabel(status: EvidenceStatus): String = when (status) {
    EvidenceStatus.YES -> "Ja"
    EvidenceStatus.NO -> "Nein"
    EvidenceStatus.UNKNOWN -> "Unbekannt"
}

private val primaryTraitLabels = listOf(
    setOf("größe", "grösse", "groesse", "körpergröße", "koerpergroesse"),
    setOf("busen", "oberweite", "brustgröße", "brustgroesse", "brust"),
    setOf("konfektion", "konfektionsgröße", "konfektionsgroesse", "kleidergröße"),
    setOf("sprachen", "sprache"),
)

private fun traitOrder(field: ProfileField): Int {
    val label = field.label.trim().lowercase(Locale.ROOT)
    val primary = primaryTraitLabels.indexOfFirst { label in it }
    if (primary >= 0) return primary
    return when (label) {
        "gewicht", "figur", "körperbau" -> 4
        "haarfarbe", "haare", "augenfarbe" -> 5
        "nationalität", "herkunft" -> 6
        else -> 7
    }
}

internal fun ProfileFacts.overviewAndTraits(age: String? = null): Pair<List<Pair<String, String>>, List<ProfileField>> {
    val ordered = traits.filter { field ->
        field.values.isNotEmpty() && !(field.label.equals("Alter", ignoreCase = true) &&
            age != null && field.values.all { it.trim().removeSuffix(" Jahre").trim() == age.trim() })
    }.sortedBy(::traitOrder)
    val (primary, remaining) = ordered.partition { traitOrder(it) < primaryTraitLabels.size }
    val rawServices = services.flatMap { it.values }
    val overview = primary.map { it.label to it.values.joinToString(" · ") } +
        important.filterNot { evidence ->
            evidence.status == EvidenceStatus.YES && rawServices.any { it.equals(evidence.label, ignoreCase = true) }
        }.map { it.label to evidenceLabel(it.status) }
    return overview.distinct() to remaining.distinct()
}

private val weekdays = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")

private fun weekdayIndex(label: String) = weekdays.indexOfFirst { it.equals(label.trim(), ignoreCase = true) }

internal fun compactOpeningTimes(fields: List<ProfileField>): List<ProfileField> {
    val populated = fields.filter { it.values.isNotEmpty() }
    val (days, other) = populated.partition { weekdayIndex(it.label) >= 0 }
    val ordered = days.sortedBy { weekdayIndex(it.label) }
    val result = mutableListOf<ProfileField>()
    var index = 0
    while (index < ordered.size) {
        val first = ordered[index]
        var end = index
        while (end + 1 < ordered.size &&
            weekdayIndex(ordered[end + 1].label) == weekdayIndex(ordered[end].label) + 1 &&
            ordered[end + 1].values == first.values) end++
        val label = if (end == index) first.label else "${first.label} – ${ordered[end].label}"
        result.add(ProfileField(label, first.values))
        index = end + 1
    }
    return result + other.sortedBy { !it.label.contains("feiertag", ignoreCase = true) }
}

@Composable
private fun CompactFieldRows(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 280.dp && LocalDensity.current.fontScale < 1.5f) 2 else 1
        val groups = buildList<List<Pair<String, String>>> {
            var pending = mutableListOf<Pair<String, String>>()
            rows.forEach { field ->
                if (field.first.length > 20 || field.second.length > 30) {
                    if (pending.isNotEmpty()) add(pending.toList())
                    pending = mutableListOf()
                    add(listOf(field))
                } else {
                    pending.add(field)
                    if (pending.size == columns) {
                        add(pending.toList())
                        pending = mutableListOf()
                    }
                }
            }
            if (pending.isNotEmpty()) add(pending.toList())
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ShellMetrics.inset)) {
                    group.forEach { (label, value) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactFactsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ProfileGroup(title, content)
}

@Composable
private fun CompactServiceValues(values: List<String>) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { value ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(value, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal fun ProfileFacts.serviceRows(): List<ProfileField> = services.filter { it.values.isNotEmpty() }

internal fun matchSummary(groups: List<FilterMatchGroup>): String {
    val yes = groups.count { it.status == EvidenceStatus.YES }
    val no = groups.count { it.status == EvidenceStatus.NO }
    val unknown = groups.count { it.status == EvidenceStatus.UNKNOWN }
    return "$yes von ${groups.size} belegt · $no nicht erfüllt · $unknown unbekannt"
}

internal fun splitMatchGroups(groups: List<FilterMatchGroup>) = groups.partition { !it.group.startsWith("local_") }

private fun matchGroupLabel(group: String): String = when (group) {
    "radius" -> "Suchumkreis"
    "category" -> "Rubrik"
    "age" -> "Alter"
    "height" -> "Größe"
    "local_video", "local_photo", "local_likes", "local_dislikes", "local_image" -> "Lokaler Filter"
    else -> groupLabels[group] ?: group
}

@Composable
private fun MatchEvidenceText(label: String, status: EvidenceStatus) {
    val color = when (status) {
        EvidenceStatus.YES -> MaterialTheme.colorScheme.primary
        EvidenceStatus.NO -> MaterialTheme.colorScheme.error
        EvidenceStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text("$label · ${evidenceLabel(status)}", color = color,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun ProfileMatchSection(groups: List<FilterMatchGroup>, sections: ProfileSections) {
    if (groups.isEmpty()) return
    val section = ProfileSection.Match
    val (source, local) = splitMatchGroups(groups)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Personenbezogene Suche" to source, "Lokale Sichtbarkeitsregeln" to local)
            .filter { it.second.isNotEmpty() }.forEach { (label, entries) ->
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(matchSummary(entries), style = MaterialTheme.typography.bodyMedium)
            }
        ProfileDisclosure(section.label, sections.expanded(section), { sections.toggle(section) }) {
            listOf("Suchkriterien" to source, "Lokale Regeln" to local)
                .filter { it.second.isNotEmpty() }.forEach { (label, entries) ->
                    ProfileGroup(label) {
                        entries.forEach { group ->
                            val title = matchGroupLabel(group.group)
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (group.options.size != 1 || group.options.single().status != group.status) {
                                    MatchEvidenceText(if (group.options.size > 1) "$title (Alternativen)" else title, group.status)
                                } else {
                                    Text(title, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                group.options.forEach { option ->
                                    val optionLabel = if (group.group == "category") categoryLabels[option.label] ?: option.label
                                        else option.label
                                    MatchEvidenceText(optionLabel, option.status)
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun ProfileDescription(profile: ProfileResponse, sections: ProfileSections) {
    val expanded = sections.expanded(ProfileSection.Description)
    ProfileDisclosure(ProfileSection.Description.label, expanded, { sections.toggle(ProfileSection.Description) },
        hint = if (expanded) "Weniger anzeigen" else "Mehr anzeigen") {
        SelectionContainer { Text(profile.description) }
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
        val editable = owner != null && !busy && !loading && state.canEdit()
        if (draft != null && owner != null) {
            OutlinedTextField(draft.text, { actions.edit(owner, it) },
                modifier = Modifier.fillMaxWidth(), label = { Text("Notiz") },
                minLines = 4, enabled = editable, shape = MaterialTheme.shapes.small)
            Text("Entwurf bleibt beim Profilwechsel erhalten. Erst Speichern schreibt in die Datenbank.",
                style = MaterialTheme.typography.bodySmall)
            if (draft.conflict) {
                Text("Die gespeicherte Notiz hat sich geändert.", color = MaterialTheme.colorScheme.error)
                SelectionContainer { Text("Gespeichert: ${draft.observed ?: "Keine Notiz"}") }
                TextButton(onClick = { actions.useStored(owner, draft) }, enabled = editable) {
                    Text("Gespeicherten Text übernehmen")
                }
                TextButton(onClick = { actions.keepDraft(owner, draft) }, enabled = editable) {
                    Text("Eigenen Entwurf behalten")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ShellMetrics.small), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(modifier = Modifier.heightIn(min = 48.dp), onClick = { actions.save(listingId, owner, draft) },
                    enabled = editable && state.canSave(draft)) { Text("Speichern") }
                TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { actions.useStored(owner, draft) },
                    enabled = !busy && !loading && state.canDiscard(draft)) { Text("Verwerfen") }
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
    val generation = panels.comparisonGeneration
    AlertDialog(
        onDismissRequest = { panels.dismissComparison(generation) },
        title = { Text("Snapshot-Vergleich") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(ShellMetrics.inset)) {
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
        confirmButton = { TextButton(onClick = { panels.dismissComparison(generation) }) { Text("Schließen") } },
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

internal fun profileAnalysisFields(imageCount: Int, data: PersonDetail?): List<Pair<String, String>> =
    buildList {
        add("Bilder im aktuellen Profil" to imageCount.toString())
        if (data != null) {
            add("Bekannte Namen" to data.knownNames.toString())
            add("Bekannte Orte" to data.knownCities.toString())
            add("Archivierte Bildreferenzen (Assets)" to data.archivedImages.toString())
            add("Mit anderen Personen geteilte Assets" to data.sharedImages.toString())
        }
    }

private fun ProfileFlowRows.profileContent(
    profile: ProfileResponse,
    identity: ProfileIdentity?,
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
    encounters: EncounterEditor?,
    encounterPanel: EncounterPanel?,
    onRetryEncounters: (EncounterPanel) -> Unit,
    prices: PriceEditor?,
    pricePanel: PricePanel?,
    onRetryPrices: (PricePanel) -> Unit,
    isCurrent: () -> Boolean,
    onConfirm: (Long) -> Unit,
) {
    val facts = panels.facts
    val (overview, traits) = facts?.overviewAndTraits(identity?.age) ?: (emptyList<Pair<String, String>>() to emptyList())
    if (facts == null || overview.isNotEmpty()) item(key = "profile-facts", read = ProfileRowRead.Facts) {
        Column(verticalArrangement = Arrangement.spacedBy(ShellMetrics.small)) {
            FactsContent(panels, actionsBusy, isCurrent) {
                CompactFactsGroup("Wichtige Merkmale") { CompactFieldRows(overview) }
            }
        }
    }
    val serviceRows = facts?.serviceRows().orEmpty()
    if (serviceRows.isNotEmpty()) item(key = "profile-services", read = ProfileRowRead.Facts) {
        CompactFactsGroup("Dienste") {
            serviceRows.forEach { field ->
                ProfileGroup(field.label) { CompactServiceValues(field.values) }
            }
        }
    }
    if (prices != null) withSection(ProfileSection.Prices) {
        item(key = "prices-toggle", read = ProfileRowRead.Prices.takeIf { sections.expanded(ProfileSection.Prices) }) {
            SectionToggle(ProfileSection.Prices, sections, pricePanel?.error, hintError = pricePanel?.error != null)
        }
        if (sections.expanded(ProfileSection.Prices)) {
            item(key = "profile-prices-head", read = ProfileRowRead.Prices) {
                val data = pricePanel?.snapshot
                if (pricePanel?.error != null) {
                    TextButton(enabled = !actionsBusy, onClick = { onRetryPrices(checkNotNull(pricePanel)) }) { Text("Neu laden") }
                } else if (data == null) {
                    if (!actionsBusy) CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    data.lastPaid?.let { paid ->
                        Text("Zuletzt bekannter bezahlter Gesamtbetrag (Begegnungen): ${euro(paid.amountCents)} € · ${displayTime(paid.occurredAt, java.time.ZoneId.systemDefault())}")
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !actionsBusy && isCurrent(),
                        onClick = { prices.begin(checkNotNull(pricePanel)) }) { Text("Preisliste hinzufügen") }
                }
            }
            val overview = pricePanel?.snapshot
            if (overview != null) items(overview.lists, key = { "price/${it.priceListId}" }, read = ProfileRowRead.Prices) { row ->
                PriceListRow(row, row == overview.lists.first(), !actionsBusy && isCurrent(),
                    onEdit = { prices.begin(checkNotNull(pricePanel), row) },
                    onDelete = { prices.begin(checkNotNull(pricePanel), row, deleting = true) })
            }
        }
    }
    if (traits.isNotEmpty()) item(key = "profile-traits", read = ProfileRowRead.Facts) {
        CompactFactsGroup("Weitere Merkmale") {
            CompactFieldRows(traits.map { it.label to it.values.joinToString(" · ") })
        }
    }
    withSection(ProfileSection.Analysis) {
        item(key = "analysis-toggle") { SectionToggle(ProfileSection.Analysis, sections, panels.detailError, panels.detailError != null) }
        if (sections.expanded(ProfileSection.Analysis)) {
            item(key = "profile-analysis", read = ProfileRowRead.Detail) {
                ProfileGroup("Gespeicherte Evidenz") {
                    val data = panels.detail
                    FieldGrid(profileAnalysisFields(media.count { it.kind == "IMAGE" }, data))
                    if (data == null) DetailStatus(panels, actionsBusy, isCurrent)
                    Text("Nur gespeicherte Fakten; keine KI-Bewertung oder Affinitätswertung.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (candidates.isNotEmpty()) item(key = "profile-candidate-head") {
                Text("Mögliche Identitätsübereinstimmungen", style = MaterialTheme.typography.titleSmall)
                Text("Keine automatische Zusammenführung.", style = MaterialTheme.typography.bodySmall)
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
    }
    withSection(ProfileSection.Notes) {
        item(key = "notes-toggle", read = ProfileRowRead.Note.takeIf { sections.expanded(ProfileSection.Notes) }) {
            SectionToggle(ProfileSection.Notes, sections, noteSectionHint(note),
                hintError = note.error != null || note.draft?.conflict == true)
        }
        if (sections.expanded(ProfileSection.Notes)) item(key = "profile-notes", read = ProfileRowRead.Note) {
            NotesPanel(profile.listingId, identity?.personId, note, noteActions, actionsBusy, noteDemand, onRetryNote)
        }
    }
    if (encounters != null) withSection(ProfileSection.Encounters) {
        item(key = "encounters-toggle", read = ProfileRowRead.Encounters.takeIf { sections.expanded(ProfileSection.Encounters) }) {
            SectionToggle(ProfileSection.Encounters, sections, encounterPanel?.error, hintError = encounterPanel?.error != null)
        }
        if (sections.expanded(ProfileSection.Encounters)) {
            item(key = "profile-encounters-head", read = ProfileRowRead.Encounters) {
                val rows = encounterPanel?.snapshot
                if (encounterPanel?.error != null) {
                    TextButton(enabled = !actionsBusy, onClick = { onRetryEncounters(checkNotNull(encounterPanel)) }) { Text("Neu laden") }
                } else if (rows == null) {
                    if (!actionsBusy) CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    OutlinedButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !actionsBusy && isCurrent(),
                        onClick = { encounters.begin(checkNotNull(encounterPanel)) }) { Text("Begegnung hinzufügen") }
                }
            }
            if (encounterPanel?.snapshot != null) items(encounterPanel.snapshot, key = { "encounter/${it.encounterId}" }, read = ProfileRowRead.Encounters) { row ->
                EncounterRow(row, !actionsBusy && isCurrent(),
                    onEdit = { encounters.begin(encounterPanel, row) },
                    onDelete = { encounters.begin(encounterPanel, row, deleting = true) })
            }
        }
    }
    if (profile.description.isNotBlank()) item(key = "profile-description") { ProfileDescription(profile, sections) }
    if (!facts?.filters.isNullOrEmpty()) item(key = "profile-match", read = ProfileRowRead.Facts) {
        ProfileMatchSection(checkNotNull(facts).filters, sections)
    }
    val times = compactOpeningTimes(facts?.openingTimes.orEmpty())
    if (times.isNotEmpty()) item(key = "profile-opening-times", read = ProfileRowRead.Facts) {
        CompactFactsGroup("Öffnungszeiten") {
            CompactFieldRows(times.map { it.label to it.values.joinToString(" · ") })
        }
    }
    withSection(ProfileSection.History) {
        item(key = "history-toggle") { SectionToggle(ProfileSection.History, sections, panels.detailError, panels.detailError != null) }
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
                if (panels.detail == null) DetailStatus(panels, actionsBusy, isCurrent)
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
            if (panels.detail == null || panels.detail?.listings.orEmpty().isNotEmpty()) item(key = "profile-listings-head", read = ProfileRowRead.Detail) {
                Text("Verknüpfte Anzeigen", style = MaterialTheme.typography.titleMedium)
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
}

@Composable
private fun FactsContent(panels: ProfilePanels, busy: Boolean, isCurrent: () -> Boolean,
    content: @Composable (ProfileFacts) -> Unit) {
    val facts = panels.facts
    if (facts != null) content(facts)
    else {
        val error = panels.factsError
        if (error == null) CircularProgressIndicator(Modifier.size(24.dp))
        else {
            Text(error, color = MaterialTheme.colorScheme.error)
            val generation = panels.factsGeneration
            TextButton(enabled = !busy, onClick = { if (isCurrent()) panels.retryFacts(generation) }) { Text("Neu laden") }
        }
    }
}

@Composable
private fun DetailStatus(panels: ProfilePanels, busy: Boolean, isCurrent: () -> Boolean) {
    val error = panels.detailError
    if (error == null) CircularProgressIndicator(Modifier.size(24.dp))
    else {
        Text(error, color = MaterialTheme.colorScheme.error)
        val generation = panels.detailGeneration
        TextButton(enabled = !busy, onClick = { if (isCurrent()) panels.retryDetail(generation) }) { Text("Neu laden") }
    }
}
