package de.justify87.ladiesexplorer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.MapProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.UUID

internal const val MAP_FALLBACK_IMAGE = "ladies-marker-fallback"
internal const val MAP_COUNT_PREFIX = "ladies-marker-count-"
internal const val MAP_THUMBNAIL_LIMIT = 64
private const val PHOTO_PIXELS = 80
private const val IMAGE_DENSITY = 320
private const val MAP_DECODE_LIMIT = 4
// Cancelled generations or replaced styles may still drain a decode. Share the budget until it returns.
private val mapDecodePermits = Semaphore(MAP_DECODE_LIMIT)

internal fun mapThumbnailPaths(points: List<MapProfile>, cards: List<DiscoveryCard>): Map<Long, String> {
    val byListing = cards.associateBy { it.listingId }
    return points.mapNotNull { profile ->
        val card = byListing[profile.listingId] ?: return@mapNotNull null
        val path = card.localPath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (card.personId != profile.personId || profile.thumbnailUrl.isNullOrBlank() ||
            card.thumbnailUrl != profile.thumbnailUrl) return@mapNotNull null
        profile.listingId to path
    }.toMap()
}

internal fun mapThumbnailId(profile: MapProfile, localPath: String? = null): String {
    val url = profile.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return MAP_FALLBACK_IMAGE
    // A changed media revision must never display the preceding owner's bitmap.
    val revision = UUID.nameUUIDFromBytes(("${url.length}:$url:${localPath.orEmpty()}").toByteArray(Charsets.UTF_8))
    return "ladies-marker-photo-${profile.listingId}-${profile.personId}-$revision"
}

private fun markerBitmap(size: Int): Bitmap =
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { density = IMAGE_DENSITY }

internal fun mapCountBitmap(count: Int, ink: Int): Bitmap {
    val bitmap = markerBitmap(96)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 34f
    }
    val label = count.toString()
    val width = paint.measureText(label)
    if (width > 84f) paint.textSize *= 84f / width
    Canvas(bitmap).drawText(label, 48f, 48f - (paint.ascent() + paint.descent()) / 2f, paint)
    return bitmap
}

internal fun mapFallbackBitmap(): Bitmap {
    val bitmap = markerBitmap(PHOTO_PIXELS)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(49, 54, 63) }
    canvas.drawCircle(40f, 40f, 39f, paint)
    paint.color = Color.WHITE
    canvas.drawCircle(40f, 28f, 11f, paint)
    canvas.drawRoundRect(20f, 43f, 60f, 64f, 11f, 11f, paint)
    return bitmap
}

internal fun mapPhotoBitmap(source: Bitmap): Bitmap {
    val bitmap = markerBitmap(PHOTO_PIXELS)
    val canvas = Canvas(bitmap)
    canvas.clipPath(Path().apply { addCircle(40f, 40f, 39f, Path.Direction.CW) })
    val scale = maxOf(PHOTO_PIXELS.toFloat() / source.width, PHOTO_PIXELS.toFloat() / source.height)
    val w = source.width * scale
    val h = source.height * scale
    canvas.drawBitmap(source, null, RectF((PHOTO_PIXELS - w) / 2, (PHOTO_PIXELS - h) / 2,
        (PHOTO_PIXELS + w) / 2, (PHOTO_PIXELS + h) / 2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return bitmap
}

internal fun mapCountInk(background: Int): Int =
    if (ColorUtils.calculateLuminance(background) > .179) Color.BLACK else Color.WHITE

internal fun mapVisibleThumbnailIds(rendered: List<String>, available: Set<String>): List<String> =
    rendered.asSequence().filter { it in available }.distinct().sorted().take(MAP_THUMBNAIL_LIMIT).toList()

// One controller belongs to one loaded native style. Coil remains the only image loader;
// the controller retains only the bounded style sprites for currently visible single markers.
private data class MapMarkerThumbnail(val url: String, val localPath: String?)

internal class MapMarkerImages(
    private val view: LifecycleMapView,
    private val map: MapLibreMap,
    private val style: Style,
    private val lifecycle: Lifecycle,
    private val decode: suspend (Context, String, String?) -> Bitmap? = ::loadMapThumbnail,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var profiles = emptyMap<String, MapMarkerThumbnail>()
    private var desired = emptyList<String>()
    private val installed = mutableSetOf<String>()
    private val attempted = mutableSetOf<String>()
    private var pending: Job? = null
    private var moving = false
    private var started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private var closed = false
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                started = true
                refresh()
            }
            Lifecycle.Event.ON_STOP -> {
                started = false
                moving = false
                clearVisible()
            }
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    init {
        lifecycle.addObserver(observer)
    }

    fun update(points: List<MapProfile>, thumbnailPaths: Map<Long, String> = emptyMap()) {
        if (closed) return
        val next = points.groupBy { it.latitude to it.longitude }.values.asSequence()
            .filter { it.size == 1 }.map { it.single() }
            .mapNotNull { profile ->
                val url = profile.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val path = thumbnailPaths[profile.listingId]
                mapThumbnailId(profile, path) to MapMarkerThumbnail(url, path)
            }.toMap()
        if (next.keys != profiles.keys) {
            profiles = next
            reconcile(desired.filter { it in next })
        } else profiles = next
    }

    fun cameraMoving() {
        moving = true
        pending?.cancel()
        pending = null
    }

    fun cameraIdle() {
        moving = false
        refresh()
    }

    fun refresh() {
        if (closed || view.released || !started || moving || view.width <= 0 || view.height <= 0) return
        val rendered = map.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), MAP_SINGLE)
            .mapNotNull { feature -> feature.getStringProperty("photo_id") }
        reconcile(mapVisibleThumbnailIds(rendered, profiles.keys))
    }

    private fun reconcile(next: List<String>) {
        if (closed || view.released) return
        if (next != desired) {
            pending?.cancel()
            pending = null
            desired = next
            (installed - next.toSet()).forEach {
                style.removeImage(it)
                installed.remove(it)
            }
            attempted.retainAll(next.toSet())
        }
        if (!started || moving || pending?.isActive == true) return
        val missing = desired.filterNot { it in attempted }
        if (missing.isEmpty()) return
        pending = scope.launch {
            for (batch in missing.chunked(MAP_DECODE_LIMIT)) coroutineScope {
                batch.map { id ->
                    async {
                        val thumbnail = profiles[id] ?: return@async
                        val bitmap = try {
                            mapDecodePermits.withPermit {
                                ensureActive()
                                decode(view.context, thumbnail.url, thumbnail.localPath)
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }
                        ensureActive()
                        if (!closed && !view.released && started && !moving &&
                            id in desired && profiles[id] == thumbnail) {
                            if (bitmap != null) {
                                style.addImage(id, bitmap)
                                installed.add(id)
                            }
                            attempted.add(id)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun clearVisible() {
        pending?.cancel()
        pending = null
        desired = emptyList()
        if (!view.released) installed.forEach { style.removeImage(it) }
        installed.clear()
        attempted.clear()
    }

    fun close() {
        if (closed) return
        closed = true
        lifecycle.removeObserver(observer)
        clearVisible()
        scope.cancel()
    }
}

internal suspend fun loadMapThumbnail(context: Context, url: String, localPath: String? = null): Bitmap? {
    for (model in mediaModels(localPath, url)) {
        val request = ImageRequest.Builder(context).data(model)
            .size(PHOTO_PIXELS, PHOTO_PIXELS).scale(Scale.FILL).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: continue
        return mapPhotoBitmap(result.image.toBitmap())
    }
    return null
}
