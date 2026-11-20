package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.Preference

/** Native CAS owns persistence; the admitted deck owns traversal. */
internal suspend fun commitProfilePreference(
    token: ProfileIdentityToken,
    preference: Preference,
    write: suspend (Long, Long, Preference?, Preference) -> Preference,
    visit: (SwipeStep) -> Unit,
    presentation: SwipePresentation? = null,
) {
    val previous = write(token.listingId, token.ownerId, token.preference, preference)
    if (token is SwipeActionToken && preference != Preference.NONE)
        visit(SwipeStep(token.listingId, token.ownerId, previous, preference,
            presentation = presentation?.takeIf { it.matches(token) }))
}

/** Change session only after native CAS or skip-owner validation succeeds. */
internal suspend fun undoSwipeStep(
    step: SwipeStep,
    write: suspend (Long, Long, Preference?, Preference) -> Preference,
    owner: suspend (Long) -> Long,
    undo: (SwipeStep) -> Unit,
) {
    check(step.undoable)
    val previous = step.before
    if (previous != null) {
        write(step.listingId, step.personId, checkNotNull(step.after), previous)
    } else {
        check(owner(step.listingId) == step.personId) {
            "Personenzuordnung geändert; Undo nicht möglich. Deck zurücksetzen."
        }
    }
    undo(step)
}
