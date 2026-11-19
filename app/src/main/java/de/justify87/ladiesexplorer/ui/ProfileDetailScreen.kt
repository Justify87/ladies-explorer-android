package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
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
    notes: Map<Long, NoteEditorState>,
    noteActions: NoteEditorActions,
    onFavorite: (ProfileIdentityToken) -> Unit,
    onPreference: (ProfileIdentityToken, Preference) -> Unit,
    isProfileCurrent: (ProfileIdentityToken) -> Boolean,
    onConfirmIdentity: (ProfileIdentityToken, Long) -> Unit,
    onCancelArchive: () -> Unit,
    onRetryProfile: () -> Unit,
    onBack: () -> Unit,
    actionsBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    val owner = state.profileIdentity?.personId
    val filters = state.response?.takeIf { state.activeCards.any { card -> card.listingId == profile?.listingId } }?.query?.filtersJson ?: "{}"
    val renderedToken = state.detailIdentityToken()
    val busy = actionsBusy || state.actionBusy || renderedToken == null
    val boundNotes = noteActions.boundTo(renderedToken, isProfileCurrent)
    val detailListState = rememberLazyListState()
    val sections = key(owner) { rememberProfileSections() }
    val panels = rememberProfilePanels(state.profileRead, filters, detailListState)
    rememberProfileNote(renderedToken, boundNotes, busy, sections.expanded(ProfileSection.Notes))
    ComparisonDialog(panels)
    var confirmTarget by remember(renderedToken, state.identityCandidates) { mutableStateOf<Pair<DetailIdentityToken, Long>?>(null) }

    confirmTarget?.let { (token, target) ->
        IdentityConfirmationDialog(target, enabled = !actionsBusy && !state.actionBusy,
            onConfirm = { confirmTarget = null; onConfirmIdentity(token, target) },
            onDismiss = { confirmTarget = null })
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val heroHeight = (maxHeight * .84f).coerceIn(220.dp, 720.dp)
        ProfileFlow(
            state = detailListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (profile == null) item {
                TextButton(onClick = onBack) { Text("Zurück") }
            }
            if (state.loading) item { CircularProgressIndicator() }
            state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            state.actionError?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
            if (state.retryProfileListingId != null) item {
                TextButton(onClick = onRetryProfile, enabled = !state.actionBusy && !actionsBusy) {
                    Text(if (state.profileSourceRetryAllowed) "Profil online abrufen" else "Profil erneut laden")
                }
            }
            if (profile != null) {
                item(key = "media") {
                    ProfileMediaHero(state.profileMedia, Modifier.fillMaxWidth().height(heroHeight),
                        onBack = onBack, originalUrl = state.profileIdentity?.profileUrl,
                        actionsEnabled = !busy, isCurrent = { renderedToken?.let(isProfileCurrent) == true })
                }
                item(key = "identity") {
                    ProfileHeader(profile, state.profileIdentity, state.selectedPerson,
                        state.profileMedia, state.profileFromArchive)
                }
                item(key = "actions") {
                    ProfileActionRail(state.selectedPerson, profile.phoneDisplay,
                        busy,
                        onFavorite = { renderedToken?.let(onFavorite) },
                        onPreference = { _, preference -> renderedToken?.let { onPreference(it, preference) } },
                        isCurrent = { renderedToken?.let(isProfileCurrent) == true })
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
                    panels, sections, state.identityCandidates, state.changeEvents,
                    busy, owner?.let(notes::get) ?: NoteEditorState(), boundNotes,
                    isCurrent = { renderedToken?.let(isProfileCurrent) == true },
                    onConfirm = { target -> renderedToken?.takeIf(isProfileCurrent)?.let { confirmTarget = it to target } })
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

/** The same explicit destructive confirmation in both profile contexts. */
@Composable
internal fun IdentityConfirmationDialog(
    target: Long,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Personen zusammenführen?") },
        text = {
            Text("Das Inserat wird mit Person #$target zusammengeführt. Die Entscheidung verändert Zuordnungen und die gemeinsame Historie. Bitte nur bei sicherer Identität bestätigen.")
        },
        confirmButton = { Button(enabled = enabled, onClick = onConfirm) { Text("Zusammenführen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
