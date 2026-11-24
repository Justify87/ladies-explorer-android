package de.justify87.ladiesexplorer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
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
    "UNAVAILABLE" -> "Anzeige nicht verfügbar"
    else -> "Status unbekannt"
}

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

@Composable
private fun ProfileImageSurface(
    url: String?, localPath: String?, modifier: Modifier, scale: ContentScale,
    description: String, allowRetry: Boolean,
    imageModifier: Modifier = Modifier,
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
                        modifier = Modifier.fillMaxSize().then(imageModifier))
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
        Text("Kein Bild verfügbar", style = MaterialTheme.typography.bodySmall, maxLines = 2,
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

/** Compact gallery scrolls with the profile instead of squeezing controls into the photo. */
@Composable
internal fun ProfileMediaHero(
    media: List<ProfileMediaItem>,
    height: Dp,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean,
    isCurrent: () -> Boolean,
    selection: ProfileMediaSelection,
    projectionAvailable: Boolean = true,
    identity: HeroIdentity?,
    actionToken: ProfileIdentityToken?,
    compact: Boolean,
    headerOwnsIdentity: Boolean,
    onIdentityMeasured: (Int) -> Unit,
    onSwipe: ((Preference) -> Unit)? = null,
    touchGate: ProfileTouchGate? = null,
    heldDrag: Float? = null,
    onSwipeDrag: (Float) -> Unit = {},
    playbackVisible: Boolean = true,
    galleryNavigationEnabled: Boolean = true,
    onMediaSettled: (Boolean) -> Unit = {},
) {
    val index = selection.resolve(media, projectionAvailable)
    LaunchedEffect(media, index, projectionAvailable) {
        if (projectionAvailable) selection.select(media, selection.resolve(media, true))
    }
    val item = media.getOrNull(index)
    val previous = projectionAvailable && index > 0
    val next = projectionAvailable && index < media.lastIndex
    val gestureOwner = Triple(actionToken, media, index)
    val decision = onSwipe?.takeIf { actionsEnabled && projectionAvailable }
    val galleryTaps = galleryNavigationEnabled && projectionAvailable && media.size > 1
    var drag by remember(gestureOwner) { mutableFloatStateOf(0f) }
    // Gesture hit geometry stays stationary; only the drawn media moves.
    val displayedDrag = heldDrag ?: drag
    val offset by animateFloatAsState(displayedDrag, animationSpec = if (displayedDrag == 0f) spring() else snap(),
        label = "swipe follows finger")
    val imageMotion = Modifier.graphicsLayer {
        translationX = offset
        rotationZ = if (size.width > 0) (offset / size.width * 12f).coerceIn(-12f, 12f) else 0f
    }
    PhotoGestureSurface(owner = Triple(gestureOwner, compact, projectionAvailable),
        modifier = Modifier.fillMaxWidth(), galleryEnabled = galleryTaps, onSwipe = decision,
        onDrag = { drag = it; onSwipeDrag(it) }, touchGate = touchGate) { hit ->
        val photoHeight = maxOf(height, 0.dp)
        val surface = modifier.fillMaxWidth().height(photoHeight).clip(MaterialTheme.shapes.large)
        Box(surface.background(MaterialTheme.colorScheme.surfaceVariant)) {
            when (item?.kind) {
                "IMAGE" -> ProfileImageSurface(item.url, item.localPath, Modifier.fillMaxSize(),
                    ContentScale.Crop, "Profilbild ${index + 1}", true, imageModifier = imageMotion) { feedback ->
                    SideEffect { onMediaSettled(!feedback.loading) }
                    BoxWithConstraints(Modifier.fillMaxSize().then(hit.photo)) {
                        // Reserve the center for retry or video controls.
                        val side = if (galleryTaps) gallerySideFraction(maxWidth.value, feedback.failed) else 0f
                        GalleryTargets(side, hit, gestureOwner, previous, next,
                            { selection.select(media, index - 1) }, { selection.select(media, index + 1) })
                        if (feedback.failed || feedback.loading) Box(Modifier.align(Alignment.Center)
                            .fillMaxWidth(if (side < .5f) 1f - side * 2 else .5f), contentAlignment = Alignment.Center) {
                            ImageFeedbackContent(feedback)
                        }
                    }
                }
                "VIDEO" -> BoxWithConstraints(Modifier.fillMaxSize().then(hit.photo)) {
                    val side = if (galleryTaps) gallerySideFraction(maxWidth.value, true) else 0f
                    SideEffect { onMediaSettled(true) } // playback has its own stable loading surface
                    GalleryVideo(item, active = actionsEnabled && projectionAvailable,
                        mediaVisible = playbackVisible, canInteract = isCurrent,
                        modifier = Modifier.fillMaxSize().then(imageMotion),
                        controlsModifier = Modifier.align(Alignment.Center),
                        onTargets = {
                            GalleryTargets(side, hit, gestureOwner, previous, next,
                                { selection.select(media, index - 1) }, { selection.select(media, index + 1) })
                        })
                }
                else -> Box(Modifier.fillMaxSize().then(hit.photo), contentAlignment = Alignment.Center) {
                    SideEffect { onMediaSettled(true) }
                    Text("Keine Medien verfügbar")
                }
            }
            if (displayedDrag != 0f) Surface(Modifier.align(Alignment.TopCenter).padding(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.medium) {
                Text(if (displayedDrag > 0f) "Gefällt" else "Nein", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface)
            }
            if (identity != null && !compact) {
                val hidden = if (headerOwnsIdentity) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier
                HeroIdentityOverlay(identity, Modifier.align(Alignment.BottomCenter)
                    .onSizeChanged { onIdentityMeasured(it.height) }.then(hidden).then(imageMotion))
            }
        }
    }
}

@Composable
internal fun HeroIdentityOverlay(identity: HeroIdentity, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = Color.Black.copy(alpha = .82f)) {
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

@Composable
private fun GalleryTargets(side: Float, hit: PhotoHitModifiers, owner: Any?,
    previous: Boolean, next: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    if (side <= 0f) return
    Row(Modifier.fillMaxSize()) {
        PhotoTapTarget("Vorheriges Medium", Modifier.weight(side).fillMaxHeight().then(hit.previous), owner,
            if (previous) onPrevious else null)
        if (side < .5f) Spacer(Modifier.weight(1f - side * 2))
        PhotoTapTarget("Nächstes Medium", Modifier.weight(side).fillMaxHeight().then(hit.next), owner,
            if (next) onNext else null)
    }
}

@Composable
internal fun ProfileImageDialog(item: ProfileMediaItem, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Vollbild schließen", tint = Color.White)
                }
            }
            ProfileImage(item.url, item.localPath, Modifier.fillMaxWidth().weight(1f), ContentScale.Fit, allowRetry = true)
        }
    }
}

/** Identity is rendered only in the hero or fixed header. */
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
            (person?.listingCount ?: 0u) > 1u -> "Person bekannt"
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
