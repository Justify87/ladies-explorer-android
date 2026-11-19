package de.justify87.ladiesexplorer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import de.justify87.ladiesexplorer.core.*
import java.io.File

internal fun mediaIndex(index: Int, count: Int): Int =
    index.coerceIn(0, (count - 1).coerceAtLeast(0))

internal fun mediaModels(localPath: String?, url: String?): List<Any> =
    listOfNotNull(localPath?.takeIf { it.isNotBlank() }?.let(::File), url?.takeIf { it.isNotBlank() })

internal fun statusLabel(status: String?): String = when (status) {
    "ACTIVE" -> "Anzeige aktiv"
    "INACTIVE" -> "Anzeige inaktiv"
    "UNAVAILABLE" -> "Anzeige nicht verfügbar"
    else -> "Status unbekannt"
}

/** The standalone preference chip toggles the same selected preference back to neutral. */
internal fun toggledProfilePreference(current: Preference, action: Preference): Preference =
    if (current == action) Preference.NONE else action

internal fun phoneForWhatsApp(phone: String): String? {
    val digits = phone.filter { it in '0'..'9' }
    val normalized = when {
        phone.trim().startsWith("+") -> digits
        digits.startsWith("00") -> digits.drop(2)
        digits.startsWith("0") -> "49${digits.drop(1)}"
        else -> digits
    }
    return normalized.takeIf { it.length in 9..15 }
}

internal fun openExternal(context: Context, link: String, dial: Boolean = false) {
    val uri = Uri.parse(link)
    val allowed = if (dial) uri.scheme == "tel" else uri.scheme == "https" &&
        uri.host != null && uri.userInfo == null
    if (!allowed) {
        Toast.makeText(context, "Link nicht verfügbar", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        context.startActivity(Intent(if (dial) Intent.ACTION_DIAL else Intent.ACTION_VIEW, uri))
    }.onFailure {
        Toast.makeText(context, "Keine passende App verfügbar", Toast.LENGTH_SHORT).show()
    }
}

private data class ImageFeedback(val loading: Boolean = false, val failed: Boolean = false, val retry: (() -> Unit)? = null)

/** One image request / local fallback owner, independent of the visual chrome. */
@Composable
private fun ProfileImageSurface(
    url: String?, localPath: String?, modifier: Modifier, scale: ContentScale,
    description: String, allowRetry: Boolean,
    content: @Composable BoxScope.(ImageFeedback) -> Unit,
) {
    key(localPath, url) {
        val models = remember { mediaModels(localPath, url) }
        var retry by remember { mutableIntStateOf(0) }
        var attempt by remember(retry) { mutableIntStateOf(0) }
        var loading by remember(retry, attempt) { mutableStateOf(true) }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (attempt < models.size) {
                val requested = attempt
                key(retry, requested) {
                    AsyncImage(model = models[requested], contentDescription = description, contentScale = scale,
                        onSuccess = { loading = false },
                        onError = { if (attempt == requested) attempt = requested + 1 },
                        modifier = Modifier.fillMaxSize())
                }
            }
            content(ImageFeedback(loading = attempt < models.size && loading, failed = attempt >= models.size,
                retry = if (allowRetry && models.isNotEmpty()) ({ retry++ }) else null))
        }
    }
}

@Composable
private fun ImageFeedbackContent(feedback: ImageFeedback) {
    if (feedback.failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Kein Bild verfügbar", style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        feedback.retry?.let { retry -> TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Erneut laden")
        } }
    } else if (feedback.loading) CircularProgressIndicator(Modifier.size(28.dp))
}

@Composable
internal fun ProfileImage(
    url: String?, localPath: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop,
    description: String = "Profilbild", allowRetry: Boolean = false,
) {
    ProfileImageSurface(url, localPath, modifier, scale, description, allowRetry) { feedback ->
        ImageFeedbackContent(feedback)
    }
}

@Composable
private fun GalleryTapZone(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick))
}

/** Normal photo uses a single image surface and a disjoint chrome layout.
 * Compact gallery scrolls with ProfileFlow and does not squeeze controls into a tiny photo. */
@Composable
internal fun ProfileMediaHero(
    media: List<ProfileMediaItem>,
    height: Dp,
    modifier: Modifier = Modifier,
    originalUrl: String?,
    actionsEnabled: Boolean,
    isCurrent: () -> Boolean,
    selection: ProfileMediaSelection,
    projectionAvailable: Boolean = true,
    identity: HeroIdentity?,
    actionToken: ProfileIdentityToken?,
    compact: Boolean,
    headerOwnsIdentity: Boolean,
    onIdentityMeasured: (Int) -> Unit,
) {
    val context = LocalContext.current
    val index = selection.resolve(media, projectionAvailable)
    LaunchedEffect(media, index, projectionAvailable) {
        if (projectionAvailable) selection.select(media, index)
    }
    val item = media.getOrNull(index)
    var more by remember(actionToken, item?.url, item?.localPath, originalUrl, actionsEnabled) { mutableStateOf(false) }
    var fullscreen by remember(actionToken, item?.url, item?.localPath, actionsEnabled) { mutableStateOf(false) }
    val previous = projectionAvailable && index > 0
    val next = projectionAvailable && index < media.lastIndex
    val canMore = actionsEnabled && (item?.kind == "IMAGE" || originalUrl != null)

    if (fullscreen && actionsEnabled && item?.kind == "IMAGE") {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { fullscreen = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Vollbild schließen", tint = Color.White)
                    }
                }
                ProfileImage(item.url, item.localPath, Modifier.fillMaxWidth().weight(1f), ContentScale.Fit, allowRetry = true)
            }
        }
    }
    val controls: @Composable (Boolean) -> Unit = { arrows ->
        Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .70f))) {
            if (media.size > 1) {
                val start = (index / 10) * 10
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in start until minOf(start + 10, media.size)) {
                        Box(Modifier.weight(1f).height(4.dp).clip(MaterialTheme.shapes.small)
                            .background(if (i == index) Color.White else Color.Gray))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (arrows && media.size > 1) IconButton(onClick = { selection.select(media, index - 1) }, enabled = previous) {
                    Icon(Icons.Default.ChevronLeft, "Vorheriges Medium", tint = Color.White.copy(alpha = if (previous) 1f else .4f))
                }
                Text(if (media.isEmpty()) "–" else "${index + 1} / ${media.size}",
                    color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (arrows && media.size > 1) IconButton(onClick = { selection.select(media, index + 1) }, enabled = next) {
                    Icon(Icons.Default.ChevronRight, "Nächstes Medium", tint = Color.White.copy(alpha = if (next) 1f else .4f))
                }
                if (canMore) Box {
                    IconButton(onClick = { if (isCurrent()) more = true }) {
                        Icon(Icons.Default.MoreVert, "Weitere Medienaktionen", tint = Color.White)
                    }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if (item?.kind == "IMAGE") DropdownMenuItem(text = { Text("Bild groß anzeigen") },
                            onClick = { more = false; if (isCurrent()) fullscreen = true })
                        if (originalUrl != null) DropdownMenuItem(text = { Text("Originalprofil öffnen") },
                            onClick = { more = false; if (isCurrent()) openExternal(context, originalUrl) })
                    }
                }
            }
        }
    }
    val center: @Composable () -> Unit = {
        if (item?.kind == "VIDEO") FilledTonalButton(enabled = actionsEnabled,
            onClick = { if (isCurrent()) openExternal(context, item.url) },
            modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text("Video extern öffnen")
        } else if (item == null) Text("Keine Medien verfügbar")
    }
    if (compact) {
        // Controls and feedback are in distinct scrollable rows, even if only a
        // small part of this item fits above an IME. No overlap or squeezed target.
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)) {
            controls(true)
            if (item?.kind == "IMAGE") {
                ProfileImageSurface(item.url, item.localPath,
                    modifier.fillMaxWidth().height(maxOf(height, 160.dp)),
                    ContentScale.Crop, "Profilbild ${index + 1}", true) { feedback -> ImageFeedbackContent(feedback) }
            } else Box(modifier.fillMaxWidth().heightIn(min = maxOf(height, 96.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { center() }
        }
    } else {
        val chrome: @Composable (ImageFeedback?) -> Unit = { feedback ->
            Column(Modifier.fillMaxSize()) {
                controls(item?.kind != "IMAGE")
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val side = gallerySideFraction(maxWidth.value)
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        if (item?.kind == "IMAGE" && media.size > 1 && side > 0f) {
                            GalleryTapZone("Vorheriges Medium", previous, Modifier.weight(side).fillMaxHeight()) {
                                selection.select(media, index - 1)
                            }
                            Box(Modifier.weight(1f - side * 2).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                feedback?.let { ImageFeedbackContent(it) }
                            }
                            GalleryTapZone("Nächstes Medium", next, Modifier.weight(side).fillMaxHeight()) {
                                selection.select(media, index + 1)
                            }
                        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (feedback != null) ImageFeedbackContent(feedback) else center()
                        }
                    }
                }
                if (identity != null) {
                    val hidden = if (headerOwnsIdentity) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier
                    Surface(Modifier.fillMaxWidth().onSizeChanged { onIdentityMeasured(it.height) }.then(hidden),
                        color = Color.Black.copy(alpha = .82f)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(identity.title, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(identity.compactSubtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (identity.decisions.isNotBlank()) Text(identity.decisions, color = Color.White,
                                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        val surface = Modifier.fillMaxWidth().height(height).clip(MaterialTheme.shapes.large)
        if (item?.kind == "IMAGE") {
            ProfileImageSurface(item.url, item.localPath, surface.then(modifier), ContentScale.Crop,
                "Profilbild ${index + 1}", true) { feedback -> chrome(feedback) }
        } else Box(surface.then(modifier).background(MaterialTheme.colorScheme.surfaceVariant)) { chrome(null) }
    }
}

/** Supplementary facts only. The name/age/place/status have exactly one presentation
 * owner (the hero or the fixed compact header). */
@Composable
internal fun ProfileMetadata(
    profile: ProfileResponse,
    identity: ProfileIdentity?,
    person: PersonSummary?,
    media: List<ProfileMediaItem>,
    fromArchive: Boolean,
) {
    val badges = listOfNotNull(
        "Archivierte Daten".takeIf { fromArchive },
        when {
            (person?.listingCount ?: 0u) > 1u -> "Person bekannt · ${person?.listingCount} Anzeigen"
            identity?.personNew == true -> "Person neu erkannt"
            else -> null
        },
        "Anzeige neu".takeIf { identity?.listingNew == true },
        "Video".takeIf { media.any { it.kind == "VIDEO" } || identity?.hasVideo == true },
    )
    if (badges.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        badges.forEach { label ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    profile.hiddenLocation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
internal fun ProfileActionRail(
    person: PersonSummary?,
    busy: Boolean,
    onFavorite: (Long) -> Unit,
    onPreference: (Long, Preference) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (person != null) {
            FilterChip(selected = person.preference == Preference.LIKE, enabled = !busy,
                onClick = { onPreference(person.personId, toggledProfilePreference(person.preference, Preference.LIKE)) },
                label = { Text("Like") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
            FilterChip(selected = person.favorite, enabled = !busy,
                onClick = { onFavorite(person.personId) }, label = { Text("Favorit") },
                leadingIcon = { Icon(Icons.Default.Star, null) })
            FilterChip(selected = person.preference == Preference.DISLIKE, enabled = !busy,
                onClick = { onPreference(person.personId, toggledProfilePreference(person.preference, Preference.DISLIKE)) },
                label = { Text("Nein") })
        }
    }
}

@Composable
internal fun ProfileContactActions(phone: String?, busy: Boolean, isCurrent: () -> Boolean) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val normalized = phone?.let(::phoneForWhatsApp)
        OutlinedButton(enabled = !busy && normalized != null, onClick = {
            if (isCurrent()) normalized?.let { openExternal(context, "https://wa.me/$it") }
        }) { Text("WhatsApp") }
        OutlinedButton(enabled = !busy && !phone.isNullOrBlank(), onClick = {
            if (isCurrent()) phone?.let { openExternal(context, "tel:${Uri.encode(it)}", dial = true) }
        }) { Text("Kontakt") }
    }
}
