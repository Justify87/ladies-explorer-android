package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.Preference

/** The executed run owns membership/filtering; only deck traversal lives here. */
internal data class SwipeStep(
    val listingId: Long,
    val personId: Long,
    val before: Preference? = null,
    val after: Preference? = null,
    val undoable: Boolean = true,
)

internal class SwipeSession {
    var replayLikes: Boolean = true
    private val history = mutableListOf<SwipeStep>()
    private var restoredListingId: Long? = null
    private val mergedOwners = mutableMapOf<Long, Long>()
    val last: SwipeStep? get() = history.lastOrNull()?.takeIf { it.undoable }
    private fun canonicalOwner(personId: Long): Long {
        var owner = personId
        while (mergedOwners.containsKey(owner)) owner = mergedOwners.getValue(owner)
        return owner
    }
    val excludedPeople: List<Long> get() = history.map { canonicalOwner(it.personId) }.distinct()

    /** Merge is not a Visit. Old owner-bound undo steps may not mutate the canonical person. */
    fun reconcileMerge(source: Long, target: Long, preferredListing: Long?) {
        val affected = history.indices.filter { canonicalOwner(history[it].personId) in setOf(source, target) }
        affected.forEach { history[it] = history[it].copy(undoable = false) }
        mergedOwners[source] = target
        if (preferredListing != null) restoredListingId = preferredListing
    }

    fun visit(step: SwipeStep) {
        // Clearing a preference is a local user-state mutation, never deck traversal.
        if (step.after == Preference.NONE) return
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
        mergedOwners.clear()
        restoredListingId = null
    }

    fun queue(cards: List<DiscoveryCard>, active: Boolean): List<DiscoveryCard> {
        val people = excludedPeople.toHashSet()
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

/** Non-writing deck operations remain possible when a detailed profile cannot be loaded. */
internal data class SwipeDeckToken(val runId: Long?, val session: Long)
internal data class SwipeCardToken(val deck: SwipeDeckToken, val listingId: Long, val ownerId: Long)

internal fun DiscoverUiState.deckToken() = SwipeDeckToken(activeRunId, swipeSessionId)
internal fun DiscoverUiState.cardToken(): SwipeCardToken? =
    cards.firstOrNull()?.let { SwipeCardToken(deckToken(), it.listingId, it.personId) }

internal fun currentSwipeDeck(state: DiscoverUiState, searchLoading: Boolean, token: SwipeDeckToken): Boolean =
    !state.busy && !state.loading && !searchLoading && state.deckToken() == token

internal fun currentSwipeTraversalCard(
    state: DiscoverUiState, searchLoading: Boolean, token: SwipeCardToken,
): DiscoveryCard? = if (!currentSwipeDeck(state, searchLoading, token.deck) || state.cardToken() != token) null
    else state.cards.firstOrNull()

/** A rendered action is bound to its deck context and coherent local profile. */
internal data class SwipeActionToken(
    val runId: Long?,
    val session: Long,
    override val listingId: Long,
    override val ownerId: Long,
    override val snapshotId: Long,
    override val projectionRevision: Long,
    override val preference: Preference,
    override val favorite: Boolean,
    override val candidates: List<de.justify87.ladiesexplorer.core.IdentityCandidate>,
) : ProfileIdentityToken

internal fun DiscoverUiState.swipeToken(profile: ProfileReadModel?): SwipeActionToken? {
    val card = cards.firstOrNull() ?: return null
    val local = profile ?: return null
    if (local.profile.listingId != card.listingId || local.identity.personId != card.personId ||
        local.person.personId != card.personId) return null
    return SwipeActionToken(activeRunId, swipeSessionId, card.listingId, card.personId,
        local.profile.snapshotId, local.revision, card.preference, card.favorite, local.candidates)
}

internal fun currentSwipeActionCard(
    state: DiscoverUiState,
    searchLoading: Boolean,
    token: SwipeActionToken,
): DiscoveryCard? {
    if (state.busy || state.loading || searchLoading || token.runId != state.activeRunId ||
        token.session != state.swipeSessionId) return null
    return state.cards.firstOrNull()?.takeIf {
        it.listingId == token.listingId && it.personId == token.ownerId &&
            it.preference == token.preference && it.favorite == token.favorite
    }
}
