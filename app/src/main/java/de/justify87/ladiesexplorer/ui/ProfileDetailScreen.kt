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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.justify87.ladiesexplorer.LadiesApplication
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
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
    val raw = state.profileRead
    val owner = raw?.let { it.profile.listingId to it.identity.personId }
    val filters = profileMatchContextForOwner(state.profileMatchContext, raw?.identity?.personId).encode()
    val context = LocalContext.current
    val app = context.applicationContext as LadiesApplication
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val preparation = remember(state.profileSessionId, started) { ProfilePreparationWindow() }
    var heroSize by remember(state.profileSessionId) { mutableStateOf(IntSize.Zero) }
    val latestRaw by rememberUpdatedState(raw)
    val latestFilters by rememberUpdatedState(filters)
    SideEffect { preparation.retain(setOfNotNull(owner)) }
    DisposableEffect(preparation) { onDispose { preparation.clear() } }
    LaunchedEffect(preparation) {
        if (!started) return@LaunchedEffect
        try {
            while (isActive) {
                val model = snapshotFlow {
                    latestRaw?.takeIf { heroSize.width > 0 && heroSize.height > 0 &&
                        !preparation.ready(it, latestFilters, heroSize) }
                }.first { it != null } ?: continue
                val size = heroSize
                val currentFilters = latestFilters
                preparation.step(model, currentFilters, size,
                    { app.coreStore.prepareProfilePanels(model, currentFilters) },
                    { medium, remaining -> prepareProfileMedium(context, medium, size, remaining, preparation) })
                app.galleryVideoFiles.retainPins(preparation, preparation.pinnedVideos())
            }
        } finally {
            withContext(NonCancellable) { app.galleryVideoFiles.retainPins(preparation, emptySet()) }
        }
    }
    LaunchedEffect(preparation, owner) {
        app.galleryVideoFiles.retainPins(preparation, preparation.pinnedVideos())
    }
    val prepared = preparation.displayed(owner)
    val displayed = prepared?.model
    val profile = displayed?.profile
    val renderedToken = state.detailIdentityToken().takeIf { displayed != null && displayed == raw }
    val busy = actionsBusy || state.actionBusy || renderedToken == null
    val detailListState = rememberLazyListState()
    val heroVisible by remember(detailListState) { derivedStateOf { profileHeroVisible(detailListState, "media") } }
    var photoRequest by remember { mutableIntStateOf(0) }
    val identity = displayed?.let { heroIdentity(it.profile, it.identity, it.fromArchive,
        it.media.any { medium -> medium.kind == "VIDEO" } || it.identity.hasVideo,
        it.person.preference, it.person.favorite) }
    var selectedMedia by remember(state.profileSessionId, owner) { mutableStateOf<ProfileMediaItem?>(null) }
    var fullImage by remember(state.profileSessionId, owner) { mutableStateOf<ProfileMediaItem?>(null) }
    var galleryMenu by remember(state.profileSessionId, owner, selectedMedia?.presentationKey()) { mutableStateOf(false) }
    val latestSelected by rememberUpdatedState(selectedMedia)
    fullImage?.takeIf { item -> displayed?.media?.any { it.presentationKey() == item.presentationKey() } == true }
        ?.let { ProfileImageDialog(it) { fullImage = null } }
    var info by remember(state.profileSessionId, owner) { mutableStateOf(false) }
    if (info && identity != null) IdentityInfoDialog(identity) { info = false }
    val notices = buildList {
        if (state.loading || (raw != null && displayed == null)) add(ProfileNotice("Profil wird geladen", "Profil wird vorbereitet …"))
        if (prepared?.degraded == true) add(ProfileNotice("Medienhinweis", "Einige Profilinhalte konnten nicht vorbereitet werden.", warning = true))
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
            Row(Modifier.fillMaxSize().padding(start = ShellMetrics.gap, end = shellContentInset()),
                verticalAlignment = Alignment.CenterVertically) {
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
                        displayed?.identity?.profileUrl?.let { url ->
                            DropdownMenuItem(text = { Text("Originalprofil öffnen") }, enabled = !busy,
                                onClick = { galleryMenu = false; if (renderedToken?.let(isProfileCurrent) == true) openExternal(context, url) })
                        }
                    }
                }
            }
        },
    ) { presentation ->
        ProfileFlow(state = detailListState, model = displayed, filters = filters,
            token = renderedToken, actionsBusy = busy, notes = notes, noteActions = noteActions,
            isProfileCurrent = isProfileCurrent, onConfirmIdentity = onConfirmIdentity,
            modifier = Modifier.fillMaxSize(), paddedDetails = true, photoRequest = photoRequest,
            onGallerySelection = { selectedMedia = it }, encounters = encounters, prices = prices,
            preparedPanels = prepared?.panels,
            afterHeading = {
                if (profile != null && displayed != null) item(key = "actions") {
                    ProfileActionRail(displayed.person, busy, onFavorite = { renderedToken?.let(onFavorite) },
                        onPreference = { _, preference -> renderedToken?.let { onPreference(it, preference) } })
                }
                state.mediaArchive?.takeIf { it.failed > 0u || it.deferred > 0u }?.let { report ->
                    item(key = "archive-report") {
                        Text("Archiv: ${report.failed} fehlgeschlagen · ${report.deferred} zurückgestellt",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }) {
            if (raw != null) {
                item(key = "media") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = shellContentInset())) {
                        ProfileMediaHero(displayed?.media.orEmpty(), height = presentation.heroHeight,
                            modifier = Modifier.onSizeChanged { heroSize = it }, selection = mediaSelection,
                            projectionAvailable = displayed != null,
                            preparedMedia = prepared?.media.orEmpty(), preparing = displayed == null,
                            presentationOwner = owner,
                            videoPlaybackEnabled = started && !state.actionBusy && !actionsBusy,
                            actionToken = renderedToken, actionsEnabled = !busy,
                            isCurrent = { renderedToken?.let(isProfileCurrent) == true },
                            compact = presentation.budget.compactGallery,
                            touchGate = touchGate,
                            playbackVisible = heroVisible)
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
