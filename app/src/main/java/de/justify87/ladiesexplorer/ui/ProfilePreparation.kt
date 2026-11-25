package de.justify87.ladiesexplorer.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun ProfileMediaItem.presentationKey(): String = "$kind:${url.ifBlank { localPath.orEmpty() }}"

internal sealed interface PreparedMedium {
    data class Photo(val bitmap: Bitmap) : PreparedMedium
    data object Video : PreparedMedium
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
            prepared.model.media.filter { it.kind == "VIDEO" }.map { it.url }
        }
    }.filter { it.isNotBlank() }.toSet()

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
                media = reusable?.media?.filterKeys { it in keys }.orEmpty(),
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
    suspend fun <T> read(block: suspend () -> T): Pair<T?, String?> = try {
        block() to null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        null to (failure.message ?: "Profildaten nicht verfügbar")
    }
    val (facts, factsError) = read { profileFacts(model.profile.snapshotId, filters) }
    val (detail, detailError) = read { personDetail(model.identity.personId) }
    return PreparedPanelData(facts, detail, factsError, detailError)
}

internal suspend fun prepareProfileMedium(
    context: Context, item: ProfileMediaItem, size: IntSize, remaining: Long, pinOwner: Any,
): PreparedMedium {
    return try {
        when (item.kind) {
            "IMAGE" -> {
                if (remaining <= 0 || size.width <= 0 || size.height <= 0) return PreparedMedium.Unavailable
                val loader = SingletonImageLoader.get(context)
                for (source in mediaModels(item.localPath, item.url)) {
                    currentCoroutineContext().ensureActive()
                    val result = loader.execute(heroImageRequest(context, source, size))
                    val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: continue
                    if (bitmap.allocationByteCount.toLong() <= remaining) return PreparedMedium.Photo(bitmap)
                }
                PreparedMedium.Unavailable
            }
            "VIDEO" -> {
                val files = (context.applicationContext as LadiesApplication).galleryVideoFiles
                if (files.warm(item, pinOwner)) PreparedMedium.Video else PreparedMedium.Unavailable
            }
            else -> PreparedMedium.Unavailable
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        PreparedMedium.Unavailable
    }
}
