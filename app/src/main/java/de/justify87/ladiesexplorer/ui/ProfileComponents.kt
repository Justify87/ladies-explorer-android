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

@Composable
internal fun ProfileImage(
    url: String?,
    localPath: String?,
    modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop,
    description: String = "Profilbild",
    allowRetry: Boolean = false,
) {
    // A new media identity owns separate state. Callbacks from disposed requests
    // cannot advance another image's local/remote fallback.
    key(localPath, url) {
        val models = remember { mediaModels(localPath, url) }
        var retry by remember { mutableIntStateOf(0) }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            key(retry) {
                var attempt by remember { mutableIntStateOf(0) }
                if (attempt >= models.size) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Kein Bild verfügbar", style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (allowRetry && models.isNotEmpty()) {
                            TextButton(onClick = { retry++ }) { Text("Erneut laden") }
                        }
                    }
                } else {
                    val requestedAttempt = attempt
                    key(requestedAttempt) {
                        var loading by remember { mutableStateOf(true) }
                        AsyncImage(
                            model = models[requestedAttempt],
                            contentDescription = description,
                            contentScale = scale,
                            onSuccess = { loading = false },
                            onError = {
                                if (attempt == requestedAttempt) attempt = requestedAttempt + 1
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (loading) CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroButton(
    onClick: () -> Unit,
    description: String,
    image: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.extraLarge,
        color = Color.Black.copy(alpha = .65f)) {
        IconButton(onClick = onClick) {
            Icon(image, contentDescription = description, tint = Color.White)
        }
    }
}

@Composable
private fun MediaTapZone(
    label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).fillMaxWidth(.30f).fillMaxHeight(.65f)
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick))
}

/** Shared presentation only; the swipe surface owns horizontal decisions. */
@Composable
internal fun ProfileMediaHero(
    media: List<ProfileMediaItem>,
    modifier: Modifier = Modifier,
    originalUrl: String?,
    actionsEnabled: Boolean,
    isCurrent: () -> Boolean,
    selection: ProfileMediaSelection,
    projectionAvailable: Boolean = true,
    identity: HeroIdentity?,
    actionToken: ProfileIdentityToken?,
) {
    val context = LocalContext.current
    val index = selection.resolve(media, projectionAvailable)
    LaunchedEffect(media, index, projectionAvailable) {
        if (projectionAvailable) selection.select(media, index)
    }
    val item = media.getOrNull(index)
    var more by remember(actionToken, item?.url, item?.localPath, originalUrl) { mutableStateOf(false) }
    var fullscreen by remember(actionToken, item?.url, item?.localPath) { mutableStateOf(false) }
    LaunchedEffect(item?.kind, actionsEnabled) {
        if (item?.kind != "IMAGE" || !actionsEnabled) fullscreen = false
        if (!actionsEnabled) more = false
    }
    if (fullscreen && actionsEnabled && item?.kind == "IMAGE") {
        Dialog(onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                ProfileImage(item.url, item.localPath, Modifier.fillMaxSize(), ContentScale.Fit, allowRetry = true)
                HeroButton({ fullscreen = false }, "Vollbild schließen", Icons.Default.Close,
                    Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)) {
        when {
            item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Keine Medien verfügbar")
            }
            item.kind == "VIDEO" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FilledTonalButton(enabled = actionsEnabled, onClick = { if (isCurrent()) openExternal(context, item.url) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Video extern öffnen")
                }
            }
            else -> ProfileImage(item.url, item.localPath, Modifier.fillMaxSize(),
                description = "Profilbild ${index + 1}", allowRetry = true)
        }
        if (media.size > 1) {
            val start = (index / 10) * 10
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in start until minOf(start + 10, media.size)) {
                    Box(Modifier.weight(1f).height(4.dp).clip(MaterialTheme.shapes.small)
                        .background(Color.Black.copy(alpha = .55f))) {
                        if (i == index) Box(Modifier.fillMaxSize().padding(.5.dp).background(Color.White))
                    }
                }
            }
            MediaTapZone("Vorheriges Medium", projectionAvailable && index > 0,
                Modifier.align(Alignment.CenterStart)) { if (projectionAvailable) selection.select(media, index - 1) }
            MediaTapZone("Nächstes Medium", projectionAvailable && index < media.lastIndex,
                Modifier.align(Alignment.CenterEnd)) { if (projectionAvailable) selection.select(media, index + 1) }
        }
        if (actionsEnabled && (item?.kind == "IMAGE" || originalUrl != null)) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 12.dp)) {
                HeroButton({ if (isCurrent()) more = true }, "Weitere Aktionen", Icons.Default.MoreVert)
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (item?.kind == "IMAGE") DropdownMenuItem(text = { Text("Bild groß anzeigen") },
                        onClick = { more = false; if (isCurrent()) fullscreen = true })
                    if (originalUrl != null) DropdownMenuItem(text = { Text("Originalprofil öffnen") },
                        onClick = { more = false; if (isCurrent()) openExternal(context, originalUrl) })
                }
            }
        }
        Surface(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 26.dp), shape = MaterialTheme.shapes.small,
            color = Color.Black.copy(alpha = .7f)) {
            Text(if (media.isEmpty()) "–" else "${index + 1} / ${media.size}",
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White)
        }
        if (identity != null) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = Color.Black.copy(alpha = .82f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(identity.title, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(identity.subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
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
