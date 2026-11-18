package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.Preference

/** The executed run owns membership/filtering; only deck traversal lives here. */
internal data class SwipeStep(
    val listingId: Long,
    val personId: Long,
    val before: Preference? = null,
    val after: Preference? = null,
)

internal class SwipeSession {
    var replayLikes: Boolean = true
    private val history = mutableListOf<SwipeStep>()
    private var restoredListingId: Long? = null
    val last: SwipeStep? get() = history.lastOrNull()
    val excludedPeople: List<Long> get() = history.map { it.personId }.distinct()

    fun visit(step: SwipeStep) {
        history.add(step)
        restoredListingId = null
    }

    fun undo(step: SwipeStep) {
        if (last != step) return
        history.removeAt(history.lastIndex)
        restoredListingId = step.listingId
    }

    fun reset() {
        history.clear()
        restoredListingId = null
    }

    fun queue(cards: List<DiscoveryCard>, active: Boolean): List<DiscoveryCard> {
        val people = history.mapTo(hashSetOf()) { it.personId }
        val listings = history.mapTo(hashSetOf()) { it.listingId }
        // After an identity merge, a visited listing may now have a different owner.
        cards.filter { it.listingId in listings }.forEach { people.add(it.personId) }
        val eligible = cards.filter {
            it.personId !in people && it.listingId !in listings &&
                (if (active) replayLikes || it.preference != Preference.LIKE else it.preference == Preference.NONE)
        }
        // Undo restores the just-visited listing even if a later refresh changed ordering.
        val restored = eligible.firstOrNull { it.listingId == restoredListingId }
        return (listOfNotNull(restored) + eligible).distinctBy { it.personId }
    }
}

internal fun swipePreference(distanceDp: Float): Preference? = when {
    distanceDp >= 96f -> Preference.LIKE
    distanceDp <= -96f -> Preference.DISLIKE
    else -> null
}

internal fun prefetchCards(cards: List<DiscoveryCard>): List<DiscoveryCard> =
    cards.drop(1).take(2)
