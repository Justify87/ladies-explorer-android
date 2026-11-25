package de.justify87.ladiesexplorer.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun ProfileMediaItem.presentationKey(): String = "$kind:${url.ifBlank { localPath.orEmpty() }}"

internal sealed interface PreparedMedium {
    data class Photo(val bitmap: Bitmap) : PreparedMedium
    data object Video : PreparedMedium
    data object Deferred : PreparedMedium
    data object Unavailable : PreparedMedium
}

internal data class PreparedPanelData(
    val facts: ProfileFacts?,
    val detail: PersonDetail?,
    val factsError: String?,
    val detailError: String?,
)

internal data class PreparedProfile(
    val model: ProfileReadModel,
    val filters: String,
    val viewport: IntSize,
    val media: Map<String, PreparedMedium> = emptyMap(),
    val panels: PreparedPanelData? = null,
) {
    // Settled work is not the same as decoded/available media.
    val complete: Boolean get() = panels != null && model.media.all { it.presentationKey() in media }
    val hasMediaFailures: Boolean get() = media.values.any { it == PreparedMedium.Unavailable }

    // Keep static details and media visible, but never attribute old match evidence to
    // a replaced projection or a different executed filter context.
    fun panelsFor(currentFilters: String, projectionCurrent: Boolean): PreparedPanelData? {
        val data = panels ?: return null
        if (projectionCurrent && filters == currentFilters) return data
        return data.copy(facts = data.facts?.copy(filters = emptyList()))
    }

    fun matches(model: ProfileReadModel, filters: String, viewport: IntSize): Boolean =
        this.model == model && this.filters == filters && this.viewport == viewport
}

internal fun <T> nextProfileWork(window: List<T>, needsWork: (T) -> Boolean): T? =
    window.firstOrNull(needsWork)

/** One bounded presentation window. A completed old revision stays visible while its replacement is prepared. */
internal class ProfilePreparationWindow(private val imageBudget: Long = 96L * 1024 * 1024) {
    private data class Slot(val working: PreparedProfile? = null, val published: PreparedProfile? = null)
    private val slots = mutableStateMapOf<Pair<Long, Long>, Slot>()

    private fun ProfileReadModel.key() = profile.listingId to identity.personId
    fun retain(keys: Set<Pair<Long, Long>>) {
        slots.keys.toList().filterNot { it in keys }.forEach(slots::remove)
        keys.forEach { if (it !in slots) slots[it] = Slot() }
    }

    fun remove(key: Pair<Long, Long>) { if (key in slots) slots[key] = Slot() }
    fun clear() { slots.clear() }
    fun displayed(key: Pair<Long, Long>?): PreparedProfile? = key?.let { slots[it]?.published }

    fun ready(model: ProfileReadModel, filters: String, viewport: IntSize): Boolean =
        slots[model.key()]?.published?.matches(model, filters, viewport) == true

    fun pinnedVideos(): Set<String> = slots.values.flatMap { slot ->
        listOfNotNull(slot.working, slot.published).flatMap { prepared ->
            prepared.model.media.filter {
                it.kind == "VIDEO" && prepared.media[it.presentationKey()] == PreparedMedium.Video
            }.map { it.url }
        }
    }.filter { it.isNotBlank() }.toSet()

    // Resolve a failed prefetch without replacing the live painter or profile.
    fun recovered(expected: PreparedProfile, mediaKey: String) {
        val key = expected.model.key()
        val slot = slots[key] ?: return
        val published = slot.published ?: return
        if (!published.matches(expected.model, expected.filters, expected.viewport) ||
            published.media[mediaKey] != PreparedMedium.Unavailable) return
        slots[key] = slot.copy(published = published.copy(
            media = published.media + (mediaKey to PreparedMedium.Deferred)))
    }

    private fun remainingImageBytes(): Long {
        val bitmaps = slots.values.flatMap { slot ->
            listOfNotNull(slot.working, slot.published).flatMap { it.media.values }
        }.filterIsInstance<PreparedMedium.Photo>().map { it.bitmap }.distinct()
        return (imageBudget - bitmaps.sumOf { it.allocationByteCount.toLong() }).coerceAtLeast(0)
    }

    suspend fun step(
        model: ProfileReadModel, filters: String, viewport: IntSize,
        readPanels: suspend () -> PreparedPanelData,
        readMedium: suspend (ProfileMediaItem, Long) -> PreparedMedium,
        accept: () -> Boolean = { true },
    ) {
        val key = model.key()
        val slot = slots[key] ?: return
        if (slot.published?.matches(model, filters, viewport) == true) return
        val previous = slot.working ?: slot.published
        val working = if (previous?.matches(model, filters, viewport) == true) previous else {
            val reusable = previous?.takeIf {
                it.model.profile.snapshotId == model.profile.snapshotId && it.viewport == viewport
            }
            val keys = model.media.map { it.presentationKey() }.toSet()
            PreparedProfile(model, filters, viewport,
                media = reusable?.media?.filter { (key, value) -> key in keys && value != PreparedMedium.Unavailable }.orEmpty(),
                panels = previous?.takeIf { it.model == model && it.filters == filters }?.panels)
        }
        val ticket = if (slot.working === working) slot else slot.copy(working = working)
        slots[key] = ticket
        val next = if (working.panels == null) working.copy(panels = readPanels()) else {
            val item = model.media.firstOrNull { it.presentationKey() !in working.media }
            if (item == null) working else {
                val result = readMedium(item, remainingImageBytes())
                working.copy(media = working.media + (item.presentationKey() to result))
            }
        }
        currentCoroutineContext().ensureActive()
        if (slots[key] !== ticket || !accept()) return
        slots[key] = if (next.complete) Slot(published = next) else ticket.copy(working = next)
    }
}

internal suspend fun CoreStore.prepareProfilePanels(model: ProfileReadModel, filters: String): PreparedPanelData {
    suspend fun <T> read(section: String, block: suspend () -> T): Pair<T?, String?> = try {
        block() to null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        diagnostic("PROFILE", DiagnosticLevel.WARN, "panel_failed listing=${model.profile.listingId} section=$section", failure)
        null to (failure.message ?: "Profildaten nicht verfügbar")
    }
    val (facts, factsError) = read("facts") { profileFacts(model.profile.snapshotId, filters) }
    val (detail, detailError) = read("detail") { personDetail(model.identity.personId) }
    return PreparedPanelData(facts, detail, factsError, detailError)
}

internal suspend fun prepareProfileMedium(
    context: Context, item: ProfileMediaItem, size: IntSize, remaining: Long, pinOwner: Any,
    model: ProfileReadModel, role: String,
): PreparedMedium {
    val app = context.applicationContext as LadiesApplication
    val core = app.coreStore
    val key = mediaDiagnosticKey(item.url.ifBlank { item.localPath.orEmpty() })
    val label = "listing=${model.profile.listingId} snapshot=${model.profile.snapshotId} role=$role media=$key kind=${item.kind}"
    val started = android.os.SystemClock.elapsedRealtime()
    fun record(level: DiagnosticLevel, event: String, failure: Throwable? = null) {
        core.diagnostic("PREPARE", level, "$event $label elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}", failure)
    }
    record(DiagnosticLevel.DEBUG, "start viewport=${size.width}x${size.height} remaining_bytes=$remaining")
    return try {
        when (item.kind) {
            "IMAGE" -> {
                if (size.width <= 0 || size.height <= 0) {
                    record(DiagnosticLevel.DEBUG, "deferred reason=viewport")
                    return PreparedMedium.Deferred
                }
                if (remaining <= 0) {
                    record(DiagnosticLevel.DEBUG, "deferred reason=image_budget remaining_bytes=$remaining")
                    return PreparedMedium.Deferred
                }
                val loader = SingletonImageLoader.get(context)
                for (source in mediaModels(item.localPath, item.url)) {
                    currentCoroutineContext().ensureActive()
                    val result = loader.execute(heroImageRequest(context, source, size))
                    if (result is ErrorResult) {
                        record(DiagnosticLevel.WARN, "source_failed source=${if (source is java.io.File) "archive" else "url"}", result.throwable)
                        continue
                    }
                    val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap
                    if (bitmap == null) {
                        record(DiagnosticLevel.DEBUG, "deferred reason=non_bitmap")
                        return PreparedMedium.Deferred
                    }
                    val bytes = bitmap.allocationByteCount.toLong()
                    if (bytes > remaining) {
                        record(DiagnosticLevel.DEBUG, "deferred reason=image_budget required_bytes=$bytes remaining_bytes=$remaining")
                        return PreparedMedium.Deferred
                    }
                    record(DiagnosticLevel.DEBUG, "prepared bytes=$bytes width=${bitmap.width} height=${bitmap.height}")
                    return PreparedMedium.Photo(bitmap)
                }
                record(DiagnosticLevel.WARN, "failed reason=no_usable_image")
                PreparedMedium.Unavailable
            }
            "VIDEO" -> {
                if (app.galleryVideoFiles.warm(item, pinOwner)) {
                    record(DiagnosticLevel.DEBUG, "prepared")
                    PreparedMedium.Video
                } else {
                    record(DiagnosticLevel.WARN, "failed reason=video_warm")
                    PreparedMedium.Unavailable
                }
            }
            else -> {
                record(DiagnosticLevel.WARN, "failed reason=unsupported_kind")
                PreparedMedium.Unavailable
            }
        }
    } catch (cancel: CancellationException) {
        record(DiagnosticLevel.DEBUG, "cancelled")
        throw cancel
    } catch (deferred: VideoPreparationDeferred) {
        record(DiagnosticLevel.DEBUG, "deferred reason=video_budget")
        PreparedMedium.Deferred
    } catch (failure: Exception) {
        record(DiagnosticLevel.WARN, "failed reason=exception", failure)
        PreparedMedium.Unavailable
    }
}
