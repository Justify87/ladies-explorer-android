package de.justify87.ladiesexplorer.ui

/** Immutable editor state. A refresh never silently rebases a dirty draft. */
internal data class NoteDraft(
    val expected: String?,
    val text: String,
    val observed: String? = expected,
) {
    val dirty: Boolean get() = text != expected.orEmpty()
    val conflict: Boolean get() = dirty && expected != observed

    fun reconcile(saved: String?): NoteDraft =
        if (!dirty || text == saved.orEmpty()) from(saved) else copy(observed = saved)

    fun edit(value: String): NoteDraft = copy(text = value).reconcile(observed)

    fun useStored(): NoteDraft = from(observed)
    fun keepDraft(): NoteDraft = copy(expected = observed)

    companion object {
        fun from(saved: String?) = NoteDraft(saved, saved.orEmpty())
    }
}

internal data class NoteEditorState(
    val draft: NoteDraft? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val readError: Boolean = false,
) {
    fun loaded(saved: String?) = copy(
        draft = draft?.reconcile(saved) ?: NoteDraft.from(saved),
        loading = false, error = if (readError) null else error, readError = false,
    )

    fun committed(note: String?) = NoteEditorState(draft = NoteDraft.from(note))
}

internal fun Map<Long, NoteEditorState>.updateNote(
    owner: Long,
    change: (NoteEditorState) -> NoteEditorState,
): Map<Long, NoteEditorState> = this + (owner to change(this[owner] ?: NoteEditorState()))

internal fun Map<Long, NoteEditorState>.dirtyOwners(vararg owners: Long): List<Long> =
    owners.distinct().filter { this[it]?.draft?.dirty == true }

/** Only the ViewModel owns the drafts and mutations; the screens render this contract. */
internal data class NoteEditorActions(
    val load: suspend (Long, Long) -> Unit,
    val edit: (Long, String) -> Unit,
    val save: (Long, Long) -> Unit,
    val useStored: (Long) -> Unit,
    val keepDraft: (Long) -> Unit,
)

/** Every UI callback carries the owner and exact rendered context, not merely the current tab. */
internal fun NoteEditorActions.boundTo(
    token: ProfileIdentityToken?,
    isCurrent: (ProfileIdentityToken) -> Boolean,
): NoteEditorActions {
    fun admitted(owner: Long, listing: Long? = null) = token != null &&
        token.ownerId == owner && (listing == null || token.listingId == listing) && isCurrent(token)
    return NoteEditorActions(
        load = { listing, owner -> if (admitted(owner, listing)) load(listing, owner) },
        edit = { owner, value -> if (admitted(owner)) edit(owner, value) },
        save = { listing, owner -> if (admitted(owner, listing)) save(listing, owner) },
        useStored = { owner -> if (admitted(owner)) useStored(owner) },
        keepDraft = { owner -> if (admitted(owner)) keepDraft(owner) },
    )
}

/** Existing per-owner note-read tickets, shared by the editor and merge completion. */
internal class NoteReadTickets {
    private var next = 0L
    private val tickets = mutableMapOf<Long, Long>()

    fun invalidate(owner: Long): Long = (++next).also { tickets[owner] = it }
    fun isCurrent(owner: Long, ticket: Long): Boolean = tickets[owner] == ticket
    fun clear() = tickets.clear()
}
