package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.Preference

/** Invoked only after rendered-token admission inside the existing ViewModel mutation
 * owner. Native CAS owns the write; the deck owns traversal, not persistence. */
internal suspend fun commitProfilePreference(
    token: ProfileIdentityToken,
    preference: Preference,
    write: suspend (Long, Long, Preference?, Preference) -> Preference,
    visit: (SwipeStep) -> Unit,
) {
    val previous = write(token.listingId, token.ownerId, token.preference, preference)
    if (token is SwipeActionToken && preference != Preference.NONE)
        visit(SwipeStep(token.listingId, token.ownerId, previous, preference))
}
