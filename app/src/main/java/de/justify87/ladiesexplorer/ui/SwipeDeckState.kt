package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.Preference

/** The executed run owns membership and filtering; the deck only traverses. */
internal data class SwipeStep(
    val listingId: Long,
    val personId: Long,
    val before: Preference? = null,
    val after: Preference? = null,
    val undoable: Boolean = true,
    val presentation: SwipePresentation? = null,
    val explicitEntry: Boolean = false,
)

internal class SwipeSession {
    var replayLikes: Boolean = true
    private val history = mutableListOf<SwipeStep>()
    private var restoredListingId: Long? = null
    private var explicitStart: Pair<Long, Long>? = null
    private var restoredPosition: SwipePresentation? = null
    private val mergedOwners = mutableMapOf<Long, Long>()
    val last: SwipeStep? get() = history.lastOrNull()?.takeIf { it.undoable }
    private fun canonicalOwner(personId: Long): Long {
        var owner = personId
        while (mergedOwners.containsKey(owner)) owner = mergedOwners.getValue(owner)
        return owner
    }
    val excludedPeople: List<Long> get() = history.map { canonicalOwner(it.personId) }.distinct()

    /** Undo steps bound to an old owner cannot mutate the merged person. */
    fun reconcileMerge(source: Long, target: Long, preferredListing: Long?) {
        val affected = history.indices.filter { canonicalOwner(history[it].personId) in setOf(source, target) }
        affected.forEach { history[it] = history[it].copy(undoable = false, presentation = null) }
        if (restoredPosition?.ownerId in setOf(source, target)) restoredPosition = null
        mergedOwners[source] = target
        explicitStart = explicitStart?.let { (listing, owner) ->
            if (owner == source) (listing to target).takeIf { preferredListing == listing }
            else listing to owner
        }
        if (preferredListing != null) restoredListingId = preferredListing
    }

    fun focus(cards: List<DiscoveryCard>, listingId: Long, ownerId: Long): Boolean {
        if (cards.none { it.listingId == listingId && it.personId == ownerId }) return false
        explicitStart = listingId to ownerId
        restoredListingId = null
        restoredPosition = null
        return true
    }

    fun visit(step: SwipeStep) {
        // Clearing a preference is a local user-state mutation, never deck traversal.
        if (step.after == Preference.NONE) return
        history.add(step.copy(explicitEntry = explicitStart == (step.listingId to step.personId)))
        explicitStart = null
        // Bound presentation history without changing traversal/undo.
        val old = history.lastIndex - 16
        if (old >= 0) history[old] = history[old].copy(presentation = null)
        restoredListingId = null
        restoredPosition = null
    }

    fun undo(step: SwipeStep) {
        if (last != step) return
        history.removeAt(history.lastIndex)
        restoredListingId = step.listingId
        explicitStart = if (step.explicitEntry) step.listingId to step.personId else null
        restoredPosition = step.presentation?.takeIf { it.listingId == step.listingId && it.ownerId == step.personId }
    }

    fun restoredPresentation(card: DiscoveryCard?): SwipePresentation? {
        val position = restoredPosition ?: return null
        // Called only for a successful projection (including an empty result).
        if (card == null || card.listingId != position.listingId || card.personId != position.ownerId) {
            restoredPosition = null
            return null
        }
        return position
    }

    fun consumePresentation(position: SwipePresentation) {
        if (restoredPosition == position) restoredPosition = null
    }

    fun reset() {
        history.clear()
        explicitStart = null
        mergedOwners.clear()
        restoredListingId = null
        restoredPosition = null
    }

    fun queue(cards: List<DiscoveryCard>, active: Boolean): List<DiscoveryCard> {
        val people = excludedPeople.toHashSet()
        val listings = history.mapTo(hashSetOf()) { it.listingId }
        cards.filter { it.listingId in listings }.forEach { people.add(it.personId) }
        val eligible = cards.filter {
            it.personId !in people && it.listingId !in listings &&
                (if (active) replayLikes || it.preference != Preference.LIKE else it.preference == Preference.NONE)
        }
        // Undo restores the just-visited listing even if a later refresh changed ordering.
        val restored = eligible.firstOrNull { it.listingId == restoredListingId }
        // An explicit open is not a visit, and may target a previously traversed card.
        // It is still restricted to the current membership and exact owner.
        val focused = cards.firstOrNull { explicitStart == (it.listingId to it.personId) }
        if (focused == null) explicitStart = null
        return (listOfNotNull(focused, restored) + eligible).distinctBy { it.personId }
    }
}

internal const val SWIPE_WINDOW_SIZE = 6

internal fun swipeReadWindow(cards: List<DiscoveryCard>): List<DiscoveryCard> = cards.take(SWIPE_WINDOW_SIZE)

/** Non-writing traversal remains available without a detail projection. */
internal data class SwipeDeckToken(val runId: Long?, val session: Long)
internal data class SwipeCardToken(val deck: SwipeDeckToken, val listingId: Long, val ownerId: Long)

internal fun DiscoverUiState.deckToken() = SwipeDeckToken(activeRunId, swipeSessionId)
internal fun DiscoverUiState.cardToken(): SwipeCardToken? =
    cards.firstOrNull()?.let { SwipeCardToken(deckToken(), it.listingId, it.personId) }

internal fun currentSwipeDeck(state: DiscoverUiState, searchLoading: Boolean, token: SwipeDeckToken): Boolean =
    !state.busy && !state.loading && !searchLoading && state.deckToken() == token

internal fun currentSwipeAvailability(
    state: DiscoverUiState, searchLoading: Boolean, responseRunId: Long?, token: SwipeDeckToken,
): Boolean = currentSwipeDeck(state, searchLoading, token) && responseRunId == token.runId

internal fun currentSwipeTraversalCard(
    state: DiscoverUiState, searchLoading: Boolean, token: SwipeCardToken,
): DiscoveryCard? = if (!currentSwipeDeck(state, searchLoading, token.deck) || state.cardToken() != token) null
    else state.cards.firstOrNull()

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
        local.profile.snapshotId, local.revision, local.person.preference, local.person.favorite, local.candidates)
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
