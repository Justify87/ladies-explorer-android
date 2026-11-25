package de.justify87.ladiesexplorer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import coil3.request.ImageRequest
import coil3.size.Scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
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

internal data class HeroVisual(val media: ProfileMediaItem, val geometry: HeroMediaGeometry, val painter: Painter)

private data class ImageFeedback(val loading: Boolean, val failed: Boolean, val painter: Painter?, val retry: (() -> Unit)?)

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
        var loaded by remember(retry, attempt) { mutableStateOf(false) }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (attempt < models.size) {
                val requested = attempt
                key(retry, requested) {
                    AsyncImage(model = models[requested], contentDescription = description, contentScale = scale,
                        onSuccess = { if (attempt == requested) loaded = true },
                        onError = { if (attempt == requested) attempt = requested + 1 },
                        modifier = Modifier.fillMaxSize())
                }
            }
            content(ImageFeedback(loading = attempt < models.size && !loaded, failed = attempt >= models.size,
                painter = null,
                retry = if (allowRetry && models.isNotEmpty()) ({ retry++ }) else null))
        }
    }
}

@Composable
private fun HeroPhotoSurface(
    item: ProfileMediaItem, viewport: IntSize, description: String,
    imageMotion: Modifier, prepared: PreparedMedium?,
    content: @Composable BoxScope.(ImageFeedback, HeroMediaGeometry) -> Unit,
) {
    // Archiving the same URL must not replace an already decoded painter. Retry can use the new local path.
    key(item.kind, item.url, item.localPath.takeIf { item.url.isBlank() }, viewport) {
        var retry by remember { mutableIntStateOf(0) }
        val models = remember(retry) { mediaModels(item.localPath, item.url) }
        var attempt by remember(retry) { mutableIntStateOf(0) }
        val context = LocalContext.current
        val retryAction: (() -> Unit)? = if (models.isNotEmpty()) ({ retry++ }) else null
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bitmap = (prepared as? PreparedMedium.Photo)?.bitmap?.takeIf { retry == 0 }
            val (feedback, geometry) = if (bitmap != null) {
                val painter = remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) }
                val geometry = heroMediaGeometry(viewport, IntSize(bitmap.width, bitmap.height))
                Image(painter, description, Modifier.fillMaxSize().then(imageMotion), contentScale = geometry.scale)
                ImageFeedback(false, false, painter, retryAction) to geometry
            } else if (prepared == PreparedMedium.Unavailable && retry == 0) {
                ImageFeedback(false, true, null, retryAction) to heroMediaGeometry(viewport, IntSize.Zero)
            } else if (attempt < models.size && viewport.width > 0 && viewport.height > 0) {
                val requested = attempt
                key(retry, requested) {
                    val request = remember(context, models, requested, viewport) {
                        heroImageRequest(context, models[requested], viewport)
                    }
                    val painter = rememberAsyncImagePainter(model = request, contentScale = ContentScale.Fit)
                    val state by painter.state.collectAsState()
                    val success = state as? AsyncImagePainter.State.Success
                    val decoded = success?.result?.image?.let { IntSize(it.width, it.height) }
                    val settled = settledHeroPhotoGeometry(viewport, decoded, true)
                    val geometry = settled ?: heroMediaGeometry(viewport, IntSize.Zero)
                    // Never draw the loading painter at provisional geometry. The successful
                    // request supplies the pixels and dimensions in the same composition.
                    if (success != null && settled != null) Image(painter = success.painter, contentDescription = description,
                        contentScale = geometry.scale, modifier = Modifier.fillMaxSize().then(imageMotion))
                    if (state is AsyncImagePainter.State.Error) LaunchedEffect(Unit) {
                        attempt = requested + 1
                    }
                    ImageFeedback(settled == null, false, success?.painter?.takeIf { settled != null }, retryAction) to geometry
                }
            } else {
                val failed = attempt >= models.size
                ImageFeedback(!failed, failed, null, retryAction) to heroMediaGeometry(viewport, IntSize.Zero)
            }
            content(feedback, geometry)
        }
    }
}

@Composable
private fun ImageFeedbackContent(feedback: ImageFeedback) {
    if (feedback.failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Kein Bild verfügbar", style = MaterialTheme.typography.bodySmall, maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        feedback.retry?.let { retry -> TextButton(onClick = retry, modifier = Modifier.heightIn(min = ShellMetrics.touch)) {
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
    actionToken: ProfileIdentityToken?,
    compact: Boolean,
    onSwipe: ((Preference) -> Unit)? = null,
    touchGate: ProfileTouchGate? = null,
    heldDrag: Float? = null,
    onSwipeDrag: (Float) -> Unit = {},
    playbackVisible: Boolean = true,
    galleryNavigationEnabled: Boolean = true,
    favorite: Boolean? = null,
    onFavorite: (() -> Unit)? = null,
    onVisual: (HeroVisual?) -> Unit = {},
    preparedMedia: Map<String, PreparedMedium> = emptyMap(),
    preparing: Boolean = false,
    videoPlaybackEnabled: Boolean = actionsEnabled,
    presentationOwner: Any? = actionToken?.let { it.listingId to it.ownerId },
) {
    val index = selection.resolve(media, projectionAvailable)
    LaunchedEffect(media, index, projectionAvailable) {
        if (projectionAvailable) selection.select(media, selection.resolve(media, true))
    }
    val item = media.getOrNull(index)
    val mediaKey = item?.presentationKey()
    var videoSize by remember(mediaKey) { mutableStateOf(IntSize.Zero) }
    val previous = projectionAvailable && index > 0
    val next = projectionAvailable && index < media.lastIndex
    val gestureOwner = Triple(presentationOwner, media.map { it.presentationKey() }, index)
    val decision = onSwipe?.takeIf { actionsEnabled && projectionAvailable }
    val galleryTaps = galleryNavigationEnabled && projectionAvailable && media.size > 1
    var drag by remember(gestureOwner) { mutableFloatStateOf(0f) }
    // Gesture hit geometry stays stationary; only the drawn media moves.
    val displayedDrag = heldDrag ?: drag
    // A different medium must not inherit the previous photo's spring return.
    val offset by key(mediaKey) {
        animateFloatAsState(displayedDrag, animationSpec = if (displayedDrag == 0f) spring() else snap(),
            label = "swipe follows finger")
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
            val imageMotion = Modifier.graphicsLayer {
                translationX = offset
                rotationZ = if (viewport.width > 0) (offset / viewport.width * 12f).coerceIn(-12f, 12f) else 0f
            }
            val favoriteControl: @Composable BoxScope.(HeroMediaGeometry) -> Unit = { geometry ->
                val favoriteOrigin = with(density) {
                    geometry.favoriteOrigin(ShellMetrics.touch.roundToPx(), 6.dp.roundToPx())
                }
                // Last sibling owns the exact bookmark hit rectangle; the parent gesture resolver
                // excludes the same measured child, not an entire strip of the gallery.
                if (favorite != null) key(actionToken) {
                    IconButton(onClick = { if (actionsEnabled && isCurrent()) onFavorite?.invoke() },
                        enabled = actionsEnabled && onFavorite != null,
                        modifier = Modifier.align(Alignment.TopStart).absoluteOffset { favoriteOrigin }
                            .size(ShellMetrics.touch).then(hit.exclusive).shellFocus()) {
                        Box(Modifier.size(34.dp).background(Color.Black.copy(alpha = .78f), MaterialTheme.shapes.small),
                            contentAlignment = Alignment.Center) {
                            Icon(if (favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (favorite) "Favorit entfernen" else "Als Favorit speichern",
                                tint = Color.White)
                        }
                    }
                }
            }
            when (item?.kind) {
                "IMAGE" -> HeroPhotoSurface(item, viewport,
                    "Profilbild ${index + 1}", imageMotion, preparedMedia[item.presentationKey()]) { feedback, geometry ->
                    SideEffect {
                        // A resized request is not the settled visual until its own decode succeeds.
                        onVisual(feedback.painter?.takeIf { projectionAvailable && !feedback.failed && geometry.imageBounds != null }
                            ?.let { HeroVisual(item, geometry, it) })
                    }
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
                    favoriteControl(geometry)
                }
                "VIDEO" -> BoxWithConstraints(Modifier.fillMaxSize().then(hit.photo)) {
                    val geometry = heroMediaGeometry(viewport, videoSize, allowCrop = false)
                    val side = if (galleryTaps) gallerySideFraction(maxWidth.value, true) else 0f
                    SideEffect { onVisual(null) }
                    // Once the native aspect is known, the texture occupies only the fitted media
                    // area; the remaining hero is the same neutral surface as a fitted photo.
                    val videoSurface = geometry.imageBounds?.takeIf { it.width > 0f && it.height > 0f }?.let { bounds ->
                        with(density) {
                            Modifier.absoluteOffset { IntOffset(bounds.left.toInt(), bounds.top.toInt()) }
                                .size(bounds.width.toDp(), bounds.height.toDp())
                        }
                    } ?: Modifier.fillMaxSize()
                    GalleryVideo(item, active = videoPlaybackEnabled && projectionAvailable,
                        mediaVisible = playbackVisible, canInteract = { videoPlaybackEnabled && projectionAvailable },
                        prepared = preparedMedia[item.presentationKey()],
                        onVideoSize = { videoSize = it },
                        modifier = videoSurface.then(imageMotion),
                        controlsModifier = Modifier.align(Alignment.Center),
                        onTargets = {
                            GalleryTargets(side, hit, gestureOwner, previous, next,
                                { selection.select(media, index - 1) }, { selection.select(media, index + 1) })
                        })
                    favoriteControl(geometry)
                }
                else -> Box(Modifier.fillMaxSize().then(hit.photo), contentAlignment = Alignment.Center) {
                    SideEffect { onVisual(null) }
                    if (preparing) CircularProgressIndicator(Modifier.size(28.dp))
                    else Text("Keine Medien verfügbar")
                    favoriteControl(heroMediaGeometry(viewport, IntSize.Zero))
                }
            }
            if (displayedDrag != 0f) Surface(Modifier.align(Alignment.TopCenter).padding(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.medium) {
                Text(if (displayedDrag > 0f) "Gefällt" else "Nein", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

@Composable
private fun GalleryTargets(side: Float, hit: PhotoHitModifiers, owner: Any?,
    previous: Boolean, next: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    if (side <= 0f) return
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(side).fillMaxHeight()) {
            PhotoTapTarget("Vorheriges Medium", Modifier.fillMaxSize().then(hit.previous), owner,
                if (previous) onPrevious else null)
        }
        if (side < .5f) Spacer(Modifier.weight(1f - side * 2))
        Box(Modifier.weight(side).fillMaxHeight()) {
            PhotoTapTarget("Nächstes Medium", Modifier.fillMaxSize().then(hit.next), owner,
                if (next) onNext else null)
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ShellMetrics.small), verticalArrangement = Arrangement.spacedBy(ShellMetrics.gap)) {
        if (person != null) {
            FilterChip(selected = person.preference == Preference.LIKE, enabled = !busy,
                modifier = Modifier.heightIn(min = ShellMetrics.touch),
                onClick = { onPreference(person.personId, toggledProfilePreference(person.preference, Preference.LIKE)) },
                label = { Text("Gefällt") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
            FilterChip(selected = person.favorite, enabled = !busy,
                modifier = Modifier.heightIn(min = ShellMetrics.touch),
                onClick = { onFavorite(person.personId) }, label = { Text("Favorit") },
                leadingIcon = { Icon(if (person.favorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null) })
            FilterChip(selected = person.preference == Preference.DISLIKE, enabled = !busy,
                modifier = Modifier.heightIn(min = ShellMetrics.touch),
                onClick = { onPreference(person.personId, toggledProfilePreference(person.preference, Preference.DISLIKE)) },
                label = { Text("Nein") })
            FilterChip(selected = person.preference == Preference.NONE, enabled = !busy && person.preference != Preference.NONE,
                modifier = Modifier.heightIn(min = ShellMetrics.touch),
                onClick = { onPreference(person.personId, Preference.NONE) }, label = { Text("Neutral") })
        }
    }
}

@Composable
internal fun ProfileContactActions(phone: String?, busy: Boolean, isCurrent: () -> Boolean) {
    if (phone?.let(::phoneForWhatsApp) == null) return
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(ShellMetrics.small), verticalArrangement = Arrangement.spacedBy(ShellMetrics.gap)) {
        OutlinedButton(enabled = !busy, modifier = Modifier.heightIn(min = ShellMetrics.touch).shellFocus(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = ShellMetrics.inset, vertical = ShellMetrics.small), onClick = {
            profileContactTarget(phone, false, busy, isCurrent)?.let { openExternal(context, it, dial = true) }
        }) {
            Icon(Icons.Default.Phone, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Telefon")
        }
        OutlinedButton(enabled = !busy, modifier = Modifier.heightIn(min = ShellMetrics.touch).shellFocus(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = ShellMetrics.inset, vertical = ShellMetrics.small),
            onClick = {
                profileContactTarget(phone, true, busy, isCurrent)?.let { openExternal(context, it) }
            }) {
            Icon(Icons.Default.Email, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("WhatsApp")
        }
    }
}
