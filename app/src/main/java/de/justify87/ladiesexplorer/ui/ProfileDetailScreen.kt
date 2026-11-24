package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onCancelArchive: (Long) -> Unit,
    onRetryProfile: () -> Unit,
    onBack: () -> Unit,
    actionsBusy: Boolean,
    touchGate: ProfileTouchGate? = null,
    modifier: Modifier = Modifier,
    encounters: EncounterEditor? = null,
    prices: PriceEditor? = null,
) {
    val profile = state.profile
    val filters = state.response?.takeIf { state.activeCards.any { card -> card.listingId == profile?.listingId } }?.query?.filtersJson ?: "{}"
    val renderedToken = state.detailIdentityToken()
    val busy = actionsBusy || state.actionBusy || renderedToken == null
    val detailListState = rememberLazyListState()
    val heroVisible by remember(detailListState) { derivedStateOf { profileHeroVisible(detailListState, "media") } }
    var photoRequest by remember { mutableIntStateOf(0) }
    val identity = profile?.let { heroIdentity(it, state.profileIdentity, state.profileFromArchive,
        state.profileMedia.any { medium -> medium.kind == "VIDEO" } || state.profileIdentity?.hasVideo == true,
        state.selectedPerson?.preference, state.selectedPerson?.favorite) }
    val context = LocalContext.current
    var selectedMedia by remember(state.profileSessionId) { mutableStateOf<ProfileMediaItem?>(null) }
    var fullImage by remember(renderedToken) { mutableStateOf<ProfileMediaItem?>(null) }
    var galleryMenu by remember(renderedToken, selectedMedia) { mutableStateOf(false) }
    val latestSelected by rememberUpdatedState(selectedMedia)
    fullImage?.takeIf { !busy }?.let { ProfileImageDialog(it) { fullImage = null } }
    var info by remember(renderedToken, identity) { mutableStateOf(false) }
    if (info && identity != null) IdentityInfoDialog(identity) { info = false }
    val notices = buildList {
        if (state.loading) add(ProfileNotice("Profil wird geladen", "Profil wird geladen …"))
        state.error?.let { add(ProfileNotice("Profilfehler", it, warning = true)) }
        state.actionError?.let { add(ProfileNotice("Aktionsfehler", it, warning = true)) }
        if (state.retryProfileListingId != null) add(ProfileNotice("Profil erneut laden", "Profil konnte nicht vollständig geladen werden.",
            if (state.profileSourceRetryAllowed) "Online abrufen" else "Erneut laden",
            !state.actionBusy && !actionsBusy, onRetryProfile, warning = true))
        if (state.archiving) add(ProfileNotice("Medienarchiv", "Medien werden lokal archiviert", "Stoppen", true,
            { onCancelArchive(state.profileSessionId) }))
        state.mediaWarning?.let { add(ProfileNotice("Archivhinweis", it, warning = true)) }
        if (actionsBusy || state.actionBusy) add(ProfileNotice("Wird verarbeitet", "Profilaktion wird verarbeitet."))
    }
    ProfileShell(modifier, detailListState, "media", hasRail = false, notices = notices,
        noticeOwner = Triple(state.profileSessionId, renderedToken, state.retryProfileListingId),
        header = { presentation, compactNotice, onNotice ->
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                ShellIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurück") }
                val shown = identity.takeIf { presentation.identityInHeader }
                ProfileShellTitle(shown?.title ?: "Profil", shown?.compactSubtitle ?: "Profilansicht",
                    shown?.decisions.orEmpty(), presentation.budget.shortHeader, Modifier.weight(1f),
                    onClick = if (identity == null) null else ({ info = true }))
                if (compactNotice != null) ProfileStatusButton(compactNotice, onNotice)
                if (presentation.photoShortcut && profile != null) ShellIconButton(onClick = { photoRequest++ }) {
                    Icon(Icons.Default.KeyboardArrowUp, "Zum Foto")
                }
                if (identity != null) Box {
                    ShellIconButton(onClick = { galleryMenu = true }) { Icon(Icons.Default.MoreVert, "Profilaktionen") }
                    DropdownMenu(expanded = galleryMenu, onDismissRequest = { galleryMenu = false }) {
                        DropdownMenuItem(text = { Text("Vollständige Profilinformationen") },
                            onClick = { galleryMenu = false; info = true })
                        selectedMedia?.takeIf { it.kind == "IMAGE" }?.let { item ->
                            DropdownMenuItem(text = { Text("Bild groß anzeigen") }, enabled = !busy,
                                onClick = { galleryMenu = false; if (latestSelected == item && renderedToken?.let(isProfileCurrent) == true) fullImage = item })
                        }
                        state.profileIdentity?.profileUrl?.let { url ->
                            DropdownMenuItem(text = { Text("Originalprofil öffnen") }, enabled = !busy,
                                onClick = { galleryMenu = false; if (renderedToken?.let(isProfileCurrent) == true) openExternal(context, url) })
                        }
                    }
                }
            }
        },
    ) { presentation, onIdentityMeasured ->
        ProfileFlow(state = detailListState, model = state.profileRead, filters = filters,
            token = renderedToken, actionsBusy = busy, notes = notes, noteActions = noteActions,
            isProfileCurrent = isProfileCurrent, onConfirmIdentity = onConfirmIdentity,
            modifier = Modifier.fillMaxSize(), paddedDetails = true, photoRequest = photoRequest,
            onGallerySelection = { selectedMedia = it }, encounters = encounters, prices = prices) {
            if (profile != null) {
                item(key = "media") {
                    ProfileMediaHero(state.profileMedia, height = presentation.heroHeight,
                        selection = mediaSelection,
                        identity = identity, actionToken = renderedToken, actionsEnabled = !busy,
                        isCurrent = { renderedToken?.let(isProfileCurrent) == true },
                        compact = presentation.budget.compactGallery,
                        headerOwnsIdentity = presentation.identityInHeader,
                        onIdentityMeasured = onIdentityMeasured, touchGate = touchGate,
                        playbackVisible = heroVisible)
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
