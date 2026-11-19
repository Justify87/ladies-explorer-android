package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Info
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
    val filters = state.response?.takeIf { state.activeCards.any { card -> card.listingId == profile?.listingId } }?.query?.filtersJson ?: "{}"
    val renderedToken = state.detailIdentityToken()
    val busy = actionsBusy || state.actionBusy || renderedToken == null
    val detailListState = rememberLazyListState()
    var photoRequest by remember { mutableIntStateOf(0) }
    val identity = profile?.let { heroIdentity(it, state.profileIdentity, state.profileFromArchive,
        state.profileMedia.any { medium -> medium.kind == "VIDEO" } || state.profileIdentity?.hasVideo == true,
        state.selectedPerson?.preference, state.selectedPerson?.favorite) }
    var info by remember(renderedToken, identity) { mutableStateOf(false) }
    if (info && identity != null) IdentityInfoDialog(identity) { info = false }
    val notices = buildList {
        if (state.loading) add(ProfileNotice("Profil wird geladen", "Profil wird geladen …"))
        state.error?.let { add(ProfileNotice("Profilfehler", it, warning = true)) }
        state.actionError?.let { add(ProfileNotice("Aktionsfehler", it, warning = true)) }
        if (state.retryProfileListingId != null) add(ProfileNotice("Profil erneut laden", "Profil konnte nicht vollständig geladen werden.",
            if (state.profileSourceRetryAllowed) "Online abrufen" else "Erneut laden",
            !state.actionBusy && !actionsBusy, onRetryProfile, warning = true))
        if (state.archiving) add(ProfileNotice("Medienarchiv", "Medien werden lokal archiviert", "Stoppen", true, onCancelArchive))
        state.mediaWarning?.let { add(ProfileNotice("Archivhinweis", it, warning = true)) }
        if (actionsBusy || state.actionBusy) add(ProfileNotice("Wird verarbeitet", "Profilaktion wird verarbeitet."))
    }
    ProfileShell(modifier, detailListState, "media", hasRail = false, notices = notices,
        noticeOwner = Triple(state.profileSessionId, renderedToken, state.retryProfileListingId),
        header = { presentation, compactNotice, onNotice ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurück") }
                val shown = identity.takeIf { presentation.identityInHeader }
                ProfileShellTitle(shown?.title ?: "Profil", shown?.compactSubtitle ?: "Profilansicht",
                    shown?.decisions.orEmpty(), presentation.budget.shortHeader, Modifier.weight(1f),
                    onClick = if (identity == null) null else ({ info = true }))
                if (compactNotice != null) ProfileStatusButton(compactNotice, onNotice)
                if (presentation.photoShortcut && profile != null) IconButton(onClick = { photoRequest++ }) {
                    Icon(Icons.Default.KeyboardArrowUp, "Zum Foto")
                } else if (identity != null) IconButton(onClick = { info = true }) {
                    Icon(Icons.Default.Info, "Vollständige Profilinformationen")
                }
            }
        },
    ) { presentation, onIdentityMeasured ->
        ProfileFlow(state = detailListState, model = state.profileRead, filters = filters,
            token = renderedToken, actionsBusy = busy, notes = notes, noteActions = noteActions,
            isProfileCurrent = isProfileCurrent, onConfirmIdentity = onConfirmIdentity,
            modifier = Modifier.fillMaxSize(), paddedDetails = true, photoRequest = photoRequest) {
            if (profile != null) {
                item(key = "media") {
                    ProfileMediaHero(state.profileMedia, height = presentation.heroHeight,
                        originalUrl = state.profileIdentity?.profileUrl, selection = mediaSelection,
                        identity = identity, actionToken = renderedToken, actionsEnabled = !busy,
                        isCurrent = { renderedToken?.let(isProfileCurrent) == true },
                        compact = presentation.budget.compactGallery,
                        headerOwnsIdentity = presentation.identityInHeader,
                        onIdentityMeasured = onIdentityMeasured)
                }
                item(key = "identity") {
                    Text("Profilinfos", style = MaterialTheme.typography.labelMedium)
                    ProfileMetadata(profile, state.profileIdentity, state.selectedPerson, state.profileMedia, state.profileFromArchive)
                }
                item(key = "actions") {
                    ProfileActionRail(state.selectedPerson, busy, onFavorite = { renderedToken?.let(onFavorite) },
                        onPreference = { _, preference -> renderedToken?.let { onPreference(it, preference) } })
                }
                state.mediaArchive?.let { report ->
                    item(key = "archive-report") {
                        Text("Archiv: ${report.downloaded} geladen · ${report.reused} wiederverwendet · ${report.failed} fehlgeschlagen · ${report.deferred} zurückgestellt",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
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
