package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.lazy.LazyListState
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.ProfileIdentity
import de.justify87.ladiesexplorer.core.ProfileResponse

/** The body viewport is measured after these bounded, non-overlapping chrome slots.
 * In constrained windows secondary controls move into an explicit menu, not over the body. */
internal data class ProfileShellBudget(
    val header: Float,
    val notice: Float,
    val rail: Float,
    val body: Float,
    val shortHeader: Boolean,
    val compactGallery: Boolean,
) {
    val railInMenu get() = rail == 0f
    val noticeInHeader get() = notice == 0f
}

internal fun profileHeroHeight(width: Float, usableHeight: Float, viewportHeight: Float): Float {
    val h = usableHeight.coerceAtLeast(0f)
    val available = viewportHeight.coerceIn(0f, h)
    val desired = h * if (width >= 390f) .70f else .65f
    val minimum = h * if (width >= 390f) .65f else .60f
    val peek = if (available - 24f >= minimum) 24f else 0f
    return minOf(desired, (available - peek).coerceAtLeast(0f))
}

internal fun profileShellBudget(
    width: Float, availableHeight: Float, rootHeight: Float, fontScale: Float,
    hasNotice: Boolean, hasRail: Boolean,
): ProfileShellBudget {
    val available = availableHeight.coerceAtLeast(0f)
    // A real 48dp control and a scrollable body take priority over decorative chrome.
    val short = available < 240f
    val preferredHeader = if (short) 48f else maxOf(56f, 56f * fontScale)
    val header = preferredHeader.coerceAtMost(available)
    val minBody = minOf(96f, (available - header).coerceAtLeast(0f))
    var remainder = (available - header - minBody).coerceAtLeast(0f)
    val rail = if (hasRail && remainder >= 48f && !short) 48f else 0f
    remainder -= rail
    val notice = if (hasNotice && remainder >= 48f && !short) 48f else 0f
    val body = (available - header - rail - notice).coerceAtLeast(0f)
    val compact = width < 320f || fontScale >= 1.5f ||
        profileHeroHeight(width, available + rootHeight, body) < 320f
    return ProfileShellBudget(header, notice, rail, body, short, compact)
}

/** In normal A the whole bottom identity overlay must remain visible. As soon as
 * it crosses either viewport edge B owns the identity; returning reverses this.
 * Size is measured even while the overlay is visually hidden. */
internal fun headerOwnsIdentity(
    firstIndex: Int, heroStart: Int?, heroEnd: Int?, identityHeight: Int,
    viewportStart: Int, viewportEnd: Int,
): Boolean {
    if (heroStart == null || heroEnd == null) return firstIndex > 0
    if (identityHeight <= 0) return heroEnd <= viewportStart
    val top = maxOf(heroStart, heroEnd - identityHeight)
    return top < viewportStart || heroEnd > viewportEnd
}

internal fun LazyListState.headerOwnsIdentity(key: String, identityHeight: Int): Boolean {
    val layout = layoutInfo
    val hero = layout.visibleItemsInfo.firstOrNull { it.key == key }
    return headerOwnsIdentity(firstVisibleItemIndex, hero?.offset, hero?.let { it.offset + it.size },
        identityHeight, layout.viewportStartOffset, layout.viewportEndOffset)
}

/** Row fractions reserve a non-clickable center for image feedback. Pixels are
 * computed by Compose weights from this same policy; video uses dedicated buttons. */
internal fun gallerySideFraction(width: Float): Float = if (width >= 320f) .24f else 0f

/** An open menu is an intention in a specific presentation context, not a live
 * subscription that may silently rebind actions to another profile/run. */
internal data class SwipeMenuContext(
    val deck: SwipeDeckToken, val card: SwipeCardToken?, val profile: SwipeActionToken?,
    val deckEnabled: Boolean, val availabilityEnabled: Boolean, val matchingRun: Boolean,
    val availability: AvailabilityMode, val replay: Boolean, val canUndo: Boolean,
)

internal fun swipeMenuCurrent(opened: SwipeMenuContext?, current: SwipeMenuContext): Boolean =
    opened != null && opened == current

internal fun swipeContextLabel(summary: String, executed: Boolean, matchingRun: Boolean, availability: AvailabilityMode): String =
    when {
        !matchingRun -> "$summary · Swipe · Suchlauf wird übernommen"
        executed -> "$summary · Swipe · ${availability.label}"
        else -> "$summary · Swipe"
    }

/** Compact run label puts the availability ahead of the optional long city. */
internal fun swipeContextShort(hasRun: Boolean, matchingRun: Boolean, availability: AvailabilityMode): String =
    when {
        !matchingRun -> "Swipe · Suchwechsel"
        hasRun -> "Suche · ${availability.label} · Swipe"
        else -> "Lokal · Swipe"
    }

internal data class HeroIdentity(
    val name: String,
    val age: String? = null,
    val place: String,
    val status: String? = null,
    val hasVideo: Boolean = false,
    val preference: Preference? = null,
    val favorite: Boolean? = null,
) {
    val title get() = listOfNotNull(name.ifBlank { "Unbekannt" }, age?.takeIf { it.isNotBlank() }).joinToString(", ")
    val subtitle get() = listOfNotNull(place.ifBlank { "Ort unbekannt" }, status).joinToString(" · ")
    val compactSubtitle get() = listOfNotNull(status, place.ifBlank { "Ort unbekannt" }).joinToString(" · ")
    val decisions get() = listOfNotNull(
        when (preference) {
            Preference.LIKE -> "Gefällt"
            Preference.DISLIKE -> "Nein"
            Preference.NONE -> "Neutral"
            null -> null
        },
        favorite?.let { if (it) "Favorit" else "Kein Favorit" },
        "Video".takeIf { hasVideo },
    ).joinToString(" · ")
}

internal fun heroIdentity(profile: ProfileResponse, identity: ProfileIdentity?, archived: Boolean,
    hasVideo: Boolean = identity?.hasVideo == true, preference: Preference? = null, favorite: Boolean? = null) =
    HeroIdentity(profile.name, identity?.age,
        listOfNotNull(profile.city, profile.postalCode).filter { it.isNotBlank() }.joinToString(" · "),
        identity?.let { if (archived) "Zuletzt bekannt: ${statusLabel(it.status)}" else statusLabel(it.status) },
        hasVideo, preference, favorite)
