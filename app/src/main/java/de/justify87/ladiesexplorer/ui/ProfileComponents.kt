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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import coil3.request.ImageRequest
import coil3.size.Scale
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
    val value = phone.trim().removePrefix("tel:").removePrefix("sms:")
        .removePrefix("https://wa.me/").removePrefix("wa.me/").trim()
    if (!value.matches(Regex("""\+?[0-9 ()\-./]+"""))) return null
    val digits = value.filter { it in '0'..'9' }
    val normalized = when {
        value.startsWith("+") -> digits
        digits.startsWith("00") -> digits.drop(2)
        digits.startsWith("0") -> "49${digits.drop(1)}"
        else -> digits
    }
    return normalized.takeIf { it.length in 9..15 && it.first() != '0' }
}

/** Bind both contact actions to the same validated snapshot number and current owner. */
internal fun profileContactTarget(phone: String?, whatsapp: Boolean, busy: Boolean, isCurrent: () -> Boolean): String? {
    if (busy || !isCurrent()) return null
    val normalized = phone?.let(::phoneForWhatsApp) ?: return null
    return if (whatsapp) "https://wa.me/$normalized" else "tel:+$normalized"
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

internal fun heroImageRequest(context: Context, model: Any, size: IntSize): ImageRequest =
    ImageRequest.Builder(context).data(model).size(size.width, size.height).scale(Scale.FIT).build()

internal data class HeroVisual(val media: ProfileMediaItem, val geometry: HeroMediaGeometry)

private data class ImageFeedback(val loading: Boolean = false, val failed: Boolean = false, val retry: (() -> Unit)? = null)

@Composable
private fun ProfileImageSurface(
    url: String?, localPath: String?, modifier: Modifier, scale: ContentScale,
    description: String, allowRetry: Boolean,
    imageModifier: Modifier = Modifier,
    decodeSize: IntSize? = null,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    onDecodedSize: (IntSize) -> Unit = {},
    content: @Composable BoxScope.(ImageFeedback) -> Unit,
) {
    key(localPath, url) {
        val models = remember { mediaModels(localPath, url) }
        var retry by remember { mutableIntStateOf(0) }
        var attempt by remember(retry) { mutableIntStateOf(0) }
        var loading by remember(retry, attempt) { mutableStateOf(true) }
        val context = LocalContext.current
        Box(modifier.background(background), contentAlignment = Alignment.Center) {
            if (attempt < models.size) {
                val requested = attempt
                key(retry, requested) {
                    val model = remember(models, requested, decodeSize, context) {
                        val source = models[requested]
                        if (decodeSize != null && decodeSize.width > 0 && decodeSize.height > 0)
                            heroImageRequest(context, source, decodeSize) else source
                    }
                    AsyncImage(model = model, contentDescription = description, contentScale = scale,
                        onSuccess = {
                            if (attempt == requested) {
                                loading = false
                                onDecodedSize(IntSize(it.result.image.width, it.result.image.height))
                            }
                        },
                        onError = {
                            if (attempt == requested) {
                                onDecodedSize(IntSize.Zero)
                                attempt = requested + 1
                            }
                        },
                        modifier = Modifier.fillMaxSize().then(imageModifier))
                }
            }
            content(ImageFeedback(loading = attempt < models.size && loading, failed = attempt >= models.size,
                retry = if (allowRetry && models.isNotEmpty()) ({ onDecodedSize(IntSize.Zero); retry++ }) else null))
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
    decodeSize: IntSize? = null,
) {
    ProfileImageSurface(url, localPath, modifier, scale, description, allowRetry,
        decodeSize = decodeSize, background = if (decodeSize == null) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface) { feedback ->
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
    actionToken: ProfileIdentityToken?,
    compact: Boolean,
    onSwipe: ((Preference) -> Unit)? = null,
    touchGate: ProfileTouchGate? = null,
    heldDrag: Float? = null,
    onSwipeDrag: (Float) -> Unit = {},
    playbackVisible: Boolean = true,
    galleryNavigationEnabled: Boolean = true,
    onMediaSettled: (Boolean) -> Unit = {},
    favorite: Boolean? = null,
    onFavorite: (() -> Unit)? = null,
    onVisual: (HeroVisual?) -> Unit = {},
) {
    val index = selection.resolve(media, projectionAvailable)
    LaunchedEffect(media, index, projectionAvailable) {
        if (projectionAvailable) selection.select(media, selection.resolve(media, true))
    }
    val item = media.getOrNull(index)
    var decodedSize by remember(item) { mutableStateOf(IntSize.Zero) }
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
        val surface = modifier.fillMaxWidth().height(photoHeight).clip(MaterialTheme.shapes.medium)
        BoxWithConstraints(surface.background(MaterialTheme.colorScheme.surface)
            .semantics { if (media.size > 1) stateDescription = "Medium ${index + 1} von ${media.size}" }) {
            val density = LocalDensity.current
            val viewport = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
            val geometry = heroMediaGeometry(viewport, decodedSize, allowCrop = item?.kind == "IMAGE")
            val favoriteOrigin = with(density) {
                geometry.favoriteOrigin(48.dp.roundToPx(), 6.dp.roundToPx())
            }
            val favoriteBounds = if (favorite != null) with(density) {
                androidx.compose.ui.geometry.Rect(favoriteOrigin.x.toFloat(), favoriteOrigin.y.toFloat(),
                    (favoriteOrigin.x + 48.dp.roundToPx()).toFloat(), (favoriteOrigin.y + 48.dp.roundToPx()).toFloat())
            } else null
            SideEffect {
                onVisual(item?.takeIf { it.kind == "IMAGE" && geometry.imageBounds != null }
                    ?.let { HeroVisual(it, geometry) })
            }
            when (item?.kind) {
                "IMAGE" -> ProfileImageSurface(item.url, item.localPath, Modifier.fillMaxSize(),
                    geometry.scale, "Profilbild ${index + 1}", true, imageModifier = imageMotion,
                    decodeSize = viewport, background = MaterialTheme.colorScheme.surface,
                    onDecodedSize = { decodedSize = it }) { feedback ->
                    SideEffect { onMediaSettled(!feedback.loading) }
                    BoxWithConstraints(Modifier.fillMaxSize().then(hit.photo)) {
                        // Reserve the center for retry or video controls.
                        val side = if (galleryTaps) gallerySideFraction(maxWidth.value, feedback.failed || feedback.loading) else 0f
                        GalleryTargets(side, hit, gestureOwner, previous, next,
                            { selection.select(media, index - 1) }, { selection.select(media, index + 1) },
                            favoriteBounds = favoriteBounds)
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
                        onVideoSize = { decodedSize = it },
                        modifier = Modifier.fillMaxSize().then(imageMotion),
                        controlsModifier = Modifier.align(Alignment.Center),
                        onTargets = {
                            GalleryTargets(side, hit, gestureOwner, previous, next,
                                { selection.select(media, index - 1) }, { selection.select(media, index + 1) },
                            favoriteBounds = favoriteBounds)
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
            if (favorite != null) key(actionToken) {
                IconButton(onClick = { if (actionsEnabled && isCurrent()) onFavorite?.invoke() },
                    enabled = actionsEnabled && onFavorite != null,
                    modifier = Modifier.align(Alignment.TopStart).absoluteOffset { favoriteOrigin }
                        .size(48.dp).then(hit.exclusive).shellFocus()) {
                    Box(Modifier.size(34.dp).background(Color.Black.copy(alpha = .78f), MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center) {
                        Icon(if (favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (favorite) "Favorit entfernen" else "Als Favorit speichern",
                            tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTargets(side: Float, hit: PhotoHitModifiers, owner: Any?,
    previous: Boolean, next: Boolean, onPrevious: () -> Unit, onNext: () -> Unit,
    favoriteBounds: androidx.compose.ui.geometry.Rect? = null) {
    if (side <= 0f) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        fun reservedTop(left: Float, right: Float): Dp = with(density) {
            (favoriteBounds?.takeIf { it.left < right && it.right > left }
                ?.bottom?.coerceIn(0f, heightPx) ?: 0f).toDp()
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(side).fillMaxHeight().padding(top = reservedTop(0f, widthPx * side))) {
                PhotoTapTarget("Vorheriges Medium", Modifier.fillMaxSize().then(hit.previous), owner,
                    if (previous) onPrevious else null)
            }
            if (side < .5f) Spacer(Modifier.weight(1f - side * 2))
            Box(Modifier.weight(side).fillMaxHeight().padding(top = reservedTop(widthPx * (1f - side), widthPx))) {
                PhotoTapTarget("Nächstes Medium", Modifier.fillMaxSize().then(hit.next), owner,
                    if (next) onNext else null)
            }
        }
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
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onPreference(person.personId, toggledProfilePreference(person.preference, Preference.LIKE)) },
                label = { Text("Gefällt") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
            FilterChip(selected = person.favorite, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onFavorite(person.personId) }, label = { Text("Favorit") },
                leadingIcon = { Icon(if (person.favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null) })
            FilterChip(selected = person.preference == Preference.DISLIKE, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onPreference(person.personId, toggledProfilePreference(person.preference, Preference.DISLIKE)) },
                label = { Text("Nein") })
        }
    }
}

@Composable
internal fun ProfileContactActions(phone: String?, busy: Boolean, isCurrent: () -> Boolean) {
    if (phone?.let(::phoneForWhatsApp) == null) return
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).shellFocus(), onClick = {
            profileContactTarget(phone, false, busy, isCurrent)?.let { openExternal(context, it, dial = true) }
        }) {
            Icon(Icons.Default.Phone, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Telefon")
        }
        Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).shellFocus(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary),
            onClick = {
                profileContactTarget(phone, true, busy, isCurrent)?.let { openExternal(context, it) }
            }) {
            Icon(Icons.Default.Email, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("WhatsApp")
        }
    }
}
