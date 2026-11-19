package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*

@Composable
internal fun ProfileDetailScreen(
    state: SearchUiState,
    tabs: ProfileTabMemory,
    notes: Map<Long, NoteEditorState>,
    noteActions: NoteEditorActions,
    onFavorite: (Long) -> Unit,
    onPreference: (Long, Preference) -> Unit,
    onConfirmIdentity: (DetailIdentityToken, Long) -> Unit,
    onCancelArchive: () -> Unit,
    onBack: () -> Unit,
    actionsBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    val owner = state.profileIdentity?.personId
    val filters = state.response?.takeIf { state.activeCards.any { card -> card.listingId == profile?.listingId } }?.query?.filtersJson ?: "{}"
    val tab = tabs.selected(owner)
    val panels = rememberProfilePanels(profile, owner, filters, state.profileMedia, tab)
    rememberProfileNote(profile?.listingId, owner, noteActions, actionsBusy || state.actionBusy, tab == ProfileTab.Notes)
    val selectTab: (ProfileTab) -> Unit = { tabs.select(owner, it) }
    ComparisonDialog(panels)
    val detailListState = rememberLazyListState()
    val renderedToken = state.detailIdentityToken()
    var confirmTarget by remember(renderedToken, state.identityCandidates) { mutableStateOf<Pair<DetailIdentityToken, Long>?>(null) }

    confirmTarget?.let { (token, target) ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("Personen zusammenführen?") },
            text = {
                Text("Das Inserat wird mit Person #$target zusammengeführt. Die Entscheidung verändert Zuordnungen und die gemeinsame Historie. Bitte nur bei sicherer Identität bestätigen.")
            },
            confirmButton = {
                Button(enabled = !actionsBusy && !state.actionBusy, onClick = {
                    confirmTarget = null
                    onConfirmIdentity(token, target)
                }) { Text("Zusammenführen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("Abbrechen") }
            },
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * .84f).coerceIn(220.dp, 720.dp)
        LazyColumn(
            state = detailListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (profile == null) item {
                TextButton(onClick = onBack) { Text("Zurück") }
            }
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            state.actionError?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            if (profile != null) {
                item(key = "media") {
                    ProfileMediaHero(state.profileMedia, Modifier.fillMaxWidth().height(heroHeight),
                        onBack = onBack, originalUrl = state.profileIdentity?.profileUrl)
                }
                item(key = "identity") {
                    ProfileHeader(profile, state.profileIdentity, state.selectedPerson,
                        state.profileMedia, state.profileFromArchive)
                }
                if (state.profileFromArchive) {
                    state.mediaWarning?.let { item(key = "archive-warning") {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    } }
                }
                item(key = "actions") {
                    ProfileActionRail(state.selectedPerson, profile.phoneDisplay,
                        actionsBusy || state.actionBusy, onFavorite, onPreference)
                }
                if (state.archiving) {
                    item {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier=Modifier.width(28.dp).height(28.dp))
                            Text("  Medien werden lokal archiviert",modifier=Modifier.weight(1f))
                            TextButton(onClick=onCancelArchive) { Text("Stoppen") }
                        }
                    }
                }
                state.mediaArchive?.let { report ->
                    item {
                        Text(
                            "Archiv: ${report.downloaded} geladen · ${report.reused} wiederverwendet · ${report.failed} fehlgeschlagen · ${report.deferred} zurückgestellt",
                            style=MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                state.mediaWarning?.let {
                    item { Text(it,color=MaterialTheme.colorScheme.error) }
                }
                profileContent(profile, state.profileIdentity, state.selectedPerson, state.profileMedia,
                    panels, tab, selectTab, state.identityCandidates, state.changeEvents,
                    actionsBusy || state.actionBusy, owner?.let(notes::get) ?: NoteEditorState(), noteActions,
                    onConfirm = { target -> renderedToken?.let { confirmTarget = it to target } })
            }
        }
    }
}

@Composable
internal fun ProfileHeader(
    profile: ProfileResponse,
    identity: ProfileIdentity?,
    person: PersonSummary?,
    media: List<ProfileMediaItem>,
    fromArchive: Boolean,
) {
    ProfileIdentityHeader(
        name = profile.name,
        place = listOfNotNull(profile.city, profile.postalCode).filter { it.isNotBlank() }.joinToString(" · "),
        age = identity?.age, identity = identity, person = person,
        hasVideo = media.any { it.kind == "VIDEO" }, fromArchive = fromArchive,
    )
    profile.hiddenLocation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
