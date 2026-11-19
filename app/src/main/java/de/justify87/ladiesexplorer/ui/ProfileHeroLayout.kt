package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.lazy.LazyListState
import de.justify87.ladiesexplorer.core.ProfileIdentity
import de.justify87.ladiesexplorer.core.ProfileResponse

/**
 * All dimensions are usable dp. The actual weighted viewport already excludes
 * the measured header, rail, status and root bar. H includes those app bars,
 * but not system insets. No arbitrary minimum can overflow a small window.
 */
internal fun profileHeroHeight(width: Float, usableHeight: Float, viewportHeight: Float): Float {
    val h = usableHeight.coerceAtLeast(0f)
    val available = viewportHeight.coerceIn(0f, h)
    val fraction = if (width >= 390f) .70f else .65f
    val desired = h * fraction
    // Leave a hint only if the normal minimum still fits.
    val minimum = h * if (width >= 390f) .65f else .60f
    val peek = if (available - 24f >= minimum) 24f else 0f
    return minOf(desired, (available - peek).coerceAtLeast(0f))
}

/** Large type and very short/narrow windows keep identity outside the image.
 * This is a presentation fallback, not a second scroll or media store. */
internal fun useHeaderIdentity(width: Float, height: Float, fontScale: Float): Boolean =
    width < 320f || height < 540f || fontScale >= 1.5f

/** Never label local cards by an unexecuted draft or a different run. */
internal fun swipeContextLabel(summary: String, executed: Boolean, matchingRun: Boolean, availability: AvailabilityMode): String =
    when {
        !matchingRun -> "$summary · Swipe · Suchlauf wird übernommen"
        executed -> "$summary · Swipe · ${availability.label}"
        else -> "$summary · Swipe"
    }

internal fun heroHasLeftViewport(firstIndex: Int, heroIndex: Int, heroEnd: Int?, viewportStart: Int): Boolean =
    if (heroEnd != null) heroEnd <= viewportStart else firstIndex > heroIndex

internal fun LazyListState.heroHasLeft(key: String): Boolean {
    val layout = layoutInfo
    val hero = layout.visibleItemsInfo.firstOrNull { it.key == key }
    // In both contexts the hero is the first row; the status header is not another scroller.
    return heroHasLeftViewport(firstVisibleItemIndex, 0, hero?.let { it.offset + it.size }, layout.viewportStartOffset)
}

internal data class HeroIdentity(
    val name: String,
    val age: String? = null,
    val place: String,
    val status: String? = null,
) {
    val title get() = listOfNotNull(name.ifBlank { "Unbekannt" }, age?.takeIf { it.isNotBlank() }).joinToString(", ")
    val subtitle get() = listOfNotNull(place.ifBlank { "Ort unbekannt" }, status).joinToString(" · ")
    // Keep source status ahead of a long place name in the single-line header.
    val compactSubtitle get() = listOfNotNull(status, place.ifBlank { "Ort unbekannt" }).joinToString(" · ")
}

internal fun heroIdentity(profile: ProfileResponse, identity: ProfileIdentity?, archived: Boolean) =
    HeroIdentity(profile.name, identity?.age,
        listOfNotNull(profile.city, profile.postalCode).filter { it.isNotBlank() }.joinToString(" · "),
        identity?.let { if (archived) "Zuletzt bekannt: ${statusLabel(it.status)}" else statusLabel(it.status) })
