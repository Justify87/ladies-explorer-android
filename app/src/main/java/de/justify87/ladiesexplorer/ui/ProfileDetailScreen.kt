package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val identity = profile?.let { heroIdentity(it, state.profileIdentity, state.profileFromArchive) }
    val details by remember(detailListState) { derivedStateOf { detailListState.heroHasLeft("media") } }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val usableHeight = maxHeight
        val windowWidth = maxWidth
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Zurück") }
                Column(Modifier.weight(1f)) {
                    Text(if (details) identity?.title ?: "Profil" else "Profil", style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Reserve the same two-line layout in A and B.
                    Text(if (details) identity?.subtitle.orEmpty() else "",
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (details && profile != null) IconButton(onClick = { scope.launch { detailListState.scrollToItem(0) } }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Zum Foto")
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.retryProfileListingId != null) {
                    TextButton(onClick = onRetryProfile, enabled = !state.actionBusy && !actionsBusy) {
                        Text(if (state.profileSourceRetryAllowed) "Profil online abrufen" else "Profil erneut laden")
                    }
                }
                if (state.archiving) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text("  Medien werden lokal archiviert", modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelArchive) { Text("Stoppen") }
                }
                state.mediaWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val heroHeight = profileHeroHeight(windowWidth.value, usableHeight.value, maxHeight.value).dp
                ProfileFlow(
                    state = detailListState, model = state.profileRead, filters = filters,
                    token = renderedToken, actionsBusy = busy, notes = notes, noteActions = noteActions,
                    isProfileCurrent = isProfileCurrent, onConfirmIdentity = onConfirmIdentity,
                    modifier = Modifier.fillMaxSize(), paddedDetails = true,
                ) {
                    if (profile != null) {
                        item(key = "media") {
                            ProfileMediaHero(state.profileMedia, Modifier.fillMaxWidth().height(heroHeight),
                                originalUrl = state.profileIdentity?.profileUrl, selection = mediaSelection,
                                identity = identity, actionToken = renderedToken,
                                actionsEnabled = !busy, isCurrent = { renderedToken?.let(isProfileCurrent) == true })
                        }
                        item(key = "identity") {
                            Text("Profilinfos", style = MaterialTheme.typography.labelMedium)
                            ProfileHeader(profile, state.profileIdentity, state.selectedPerson,
                                state.profileMedia, state.profileFromArchive, showIdentity = false)
                        }
                        item(key = "actions") {
                            ProfileActionRail(state.selectedPerson, busy,
                                onFavorite = { renderedToken?.let(onFavorite) },
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
    }
}

@Composable
internal fun ProfileHeader(
    profile: ProfileResponse,
    identity: ProfileIdentity?,
    person: PersonSummary?,
    media: List<ProfileMediaItem>,
    fromArchive: Boolean,
    showIdentity: Boolean = true,
) {
    ProfileIdentityHeader(
        name = profile.name,
        place = listOfNotNull(profile.city, profile.postalCode).filter { it.isNotBlank() }.joinToString(" · "),
        age = identity?.age, identity = identity, person = person,
        hasVideo = media.any { it.kind == "VIDEO" }, fromArchive = fromArchive, showIdentity = showIdentity,
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
