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

/** A rendered action is bound to its deck context and coherent local profile. */
internal data class SwipeActionToken(
    val runId: Long?,
    val session: Long,
    val listingId: Long,
    val ownerId: Long,
    val snapshotId: Long,
    val preference: Preference,
    val candidateIds: Set<Long>,
)

internal fun DiscoverUiState.swipeToken(profile: SwipeProfile?): SwipeActionToken? {
    val card = cards.firstOrNull() ?: return null
    val local = profile ?: return null
    if (local.profile.listingId != card.listingId || local.identity.personId != card.personId ||
        local.person?.personId != card.personId) return null
    return SwipeActionToken(activeRunId, swipeSessionId, card.listingId, card.personId,
        local.profile.snapshotId, card.preference, local.candidates.map { it.personId }.toSet())
}

internal fun currentSwipeActionCard(
    state: DiscoverUiState,
    searchLoading: Boolean,
    token: SwipeActionToken,
): DiscoveryCard? {
    if (state.busy || state.loading || searchLoading || token.runId != state.activeRunId ||
        token.session != state.swipeSessionId) return null
    return state.cards.firstOrNull()?.takeIf {
        it.listingId == token.listingId && it.personId == token.ownerId && it.preference == token.preference
    }
}
