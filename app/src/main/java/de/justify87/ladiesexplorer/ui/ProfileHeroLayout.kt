package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.lazy.LazyListState
import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.ProfileIdentity
import de.justify87.ladiesexplorer.core.ProfileResponse

/** Reserve non-overlapping chrome; use inline decisions in constrained windows. */
internal data class ProfileShellBudget(
    val header: Float,
    val notice: Float,
    val rail: Float,
    val body: Float,
    val shortHeader: Boolean,
    val narrowHeader: Boolean,
    val compactGallery: Boolean,
) {
    val railInContent get() = rail == 0f
    val noticeInHeader get() = notice == 0f
}

// The discovery context remains a separate stable lazy row. Short windows prioritize a
// complete scrollable hero over squeezing it into the space remaining above the inline rail.
internal fun profileHeroHeight(width: Float, usableHeight: Float, viewportHeight: Float, leadingHeight: Float? = null): Float {
    val h = usableHeight.coerceAtLeast(0f)
    val available = viewportHeight.coerceIn(0f, h)
    if (leadingHeight != null) {
        val afterContext = (available - leadingHeight.coerceAtLeast(0f)).coerceAtLeast(0f)
        return if (afterContext >= 250f) afterContext else available
    }
    return minOf(h * .63f, width.coerceAtLeast(0f) * 1.55f, available)
}

internal fun profileShellBudget(
    width: Float, availableHeight: Float, rootHeight: Float, fontScale: Float,
    hasNotice: Boolean, hasRail: Boolean,
): ProfileShellBudget {
    val available = availableHeight.coerceAtLeast(0f)
    // A real 48dp control and a scrollable body take priority over decorative chrome.
    val short = available < 240f
    val preferredHeader = if (short) ShellMetrics.touch.value else ShellMetrics.header.value * maxOf(1f, fontScale)
    val header = preferredHeader.coerceAtMost(available)
    val minBody = minOf(96f, (available - header).coerceAtLeast(0f))
    var remainder = (available - header - minBody).coerceAtLeast(0f)
    val rail = if (hasRail && remainder >= ShellMetrics.touch.value && !short && width >= ShellMetrics.touch.value * 4) {
        if (remainder >= ShellMetrics.decisionRail.value && width >= 320f && fontScale < 1.3f)
            ShellMetrics.decisionRail.value else ShellMetrics.touch.value
    } else 0f
    remainder -= rail
    val notice = if (hasNotice && remainder >= 48f && !short) 48f else 0f
    val body = (available - header - rail - notice).coerceAtLeast(0f)
    val compact = width < 320f || fontScale >= 1.5f ||
        profileHeroHeight(width, available + rootHeight, body) < 320f
    return ProfileShellBudget(header, notice, rail, body, short, width < 300f, compact)
}

/** Never repeat the inline identity while any part of its row is visible. */
internal fun LazyListState.headerOwnsIdentity(heroKey: String): Boolean {
    val layout = layoutInfo
    return layout.visibleItemsInfo.isNotEmpty() &&
        layout.visibleItemsInfo.none { it.key == heroKey || it.key == "profile-heading" }
}

/** Reserve a non-clickable center for image feedback. */
internal fun gallerySideFraction(width: Float, video: Boolean = false): Float =
    if (width <= 0f) 0f else if (video) .33f else .5f

/** Rendered deck controls retain their original deck and profile tokens. */
internal data class SwipeControlsContext(
    val deck: SwipeDeckToken, val card: SwipeCardToken?, val profile: SwipeActionToken?,
    val deckEnabled: Boolean, val availabilityEnabled: Boolean,
    val availability: AvailabilityMode, val replay: Boolean, val canUndo: Boolean,
)

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
    val shortDecision get() = when (preference) {
        Preference.LIKE -> "Gefällt"
        Preference.DISLIKE -> "Nein"
        Preference.NONE -> "Neutral"
        null -> null
    }
    val decisions get() = listOfNotNull(
        shortDecision,
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
