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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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

private fun openExternal(context: Context, link: String, dial: Boolean = false) {
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
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(modifier.fillMaxWidth(.25f).fillMaxHeight(.65f)
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Black.copy(alpha = .65f)) {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = if (enabled) 1f else .4f),
                modifier = Modifier.padding(10.dp).size(28.dp))
        }
    }
}

/** Shared media hero; deck navigation is added by M05.4, not by this component. */
@Composable
internal fun ProfileMediaHero(
    media: List<ProfileMediaItem>,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    originalUrl: String? = null,
) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val index = mediaIndex(selected, media.size)
    LaunchedEffect(index, selected) { if (selected != index) selected = index }
    val item = media.getOrNull(index)
    var more by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(item?.kind) { if (item?.kind != "IMAGE") fullscreen = false }
    if (fullscreen && item?.kind == "IMAGE") {
        Dialog(onDismissRequest = { fullscreen = false }) {
            ProfileImage(item.url, item.localPath, Modifier.fillMaxWidth().heightIn(min = 240.dp)
                .aspectRatio(.72f).clickable { fullscreen = false }, ContentScale.Fit)
        }
    }
    Box(modifier.clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)) {
        when {
            item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Keine Medien verfügbar")
            }
            item.kind == "VIDEO" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                FilledTonalButton(onClick = { openExternal(context, item.url) }) {
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
            MediaTapZone("Vorheriges Medium", Icons.Default.ChevronLeft, index > 0,
                Modifier.align(Alignment.CenterStart)) { selected = index - 1 }
            MediaTapZone("Nächstes Medium", Icons.Default.ChevronRight, index < media.lastIndex,
                Modifier.align(Alignment.CenterEnd)) { selected = index + 1 }
        }
        onBack?.let {
            HeroButton(it, "Zurück", Icons.Default.ArrowBack,
                Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 30.dp))
        }
        if (item?.kind == "IMAGE" || originalUrl != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 12.dp)) {
                HeroButton({ more = true }, "Weitere Aktionen", Icons.Default.MoreVert)
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (item?.kind == "IMAGE") DropdownMenuItem(text = { Text("Bild groß anzeigen") },
                        onClick = { more = false; fullscreen = true })
                    if (originalUrl != null) DropdownMenuItem(text = { Text("Originalprofil öffnen") },
                        onClick = { more = false; openExternal(context, originalUrl) })
                }
            }
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp), shape = MaterialTheme.shapes.small,
            color = Color.Black.copy(alpha = .7f)) {
            Text(if (media.isEmpty()) "–" else "${index + 1} / ${media.size}",
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White)
        }
    }
}

@Composable
internal fun ProfileIdentityHeader(
    name: String,
    place: String,
    age: String? = null,
    identity: ProfileIdentity? = null,
    person: PersonSummary? = null,
    hasVideo: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(listOfNotNull(name.ifBlank { "Unbekannt" }, age).joinToString(", "),
            style = MaterialTheme.typography.headlineMedium)
        Text(place.ifBlank { "Ort unbekannt" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val badges = listOfNotNull(
            statusLabel(identity?.status).takeIf { identity != null },
            when {
                (person?.listingCount ?: 0u) > 1u -> "Person bekannt · ${person?.listingCount} Anzeigen"
                identity?.personNew == true -> "Person neu erkannt"
                else -> null
            },
            "Anzeige neu".takeIf { identity?.listingNew == true },
            "Video".takeIf { hasVideo || identity?.hasVideo == true },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            badges.forEach { label ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
internal fun ProfileActionRail(
    person: PersonSummary?,
    phone: String?,
    busy: Boolean,
    onFavorite: (Long) -> Unit,
    onPreference: (Long, Preference) -> Unit,
) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (person != null) {
            FilterChip(selected = person.preference == Preference.LIKE, enabled = !busy,
                onClick = { onPreference(person.personId, if (person.preference == Preference.LIKE) Preference.NONE else Preference.LIKE) },
                label = { Text("Like") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
            FilterChip(selected = person.favorite, enabled = !busy,
                onClick = { onFavorite(person.personId) }, label = { Text("Favorit") },
                leadingIcon = { Icon(Icons.Default.Star, null) })
            FilterChip(selected = person.preference == Preference.DISLIKE, enabled = !busy,
                onClick = { onPreference(person.personId, if (person.preference == Preference.DISLIKE) Preference.NONE else Preference.DISLIKE) },
                label = { Text("Nein") })
        }
        val normalized = phone?.let(::phoneForWhatsApp)
        OutlinedButton(enabled = normalized != null, onClick = {
            normalized?.let { openExternal(context, "https://wa.me/$it") }
        }) { Text("WhatsApp") }
        OutlinedButton(enabled = !phone.isNullOrBlank(), onClick = {
            phone?.let { openExternal(context, "tel:${Uri.encode(it)}", dial = true) }
        }) { Text("Kontakt") }
    }
}
