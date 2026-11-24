package de.justify87.ladiesexplorer.ui

/** A refresh never silently rebases a dirty draft. */
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

    fun canEdit() = draft != null && !loading && !readError

    fun canDiscard(rendered: NoteDraft) =
        !loading && draft == rendered && rendered.dirty

    fun canKeep(rendered: NoteDraft) =
        canDiscard(rendered) && !readError && rendered.conflict

    fun canSave(rendered: NoteDraft) =
        canDiscard(rendered) && !readError && !rendered.conflict
}

internal fun Map<Long, NoteEditorState>.updateNote(
    owner: Long,
    change: (NoteEditorState) -> NoteEditorState,
): Map<Long, NoteEditorState> = this + (owner to change(this[owner] ?: NoteEditorState()))

internal fun Map<Long, NoteEditorState>.dirtyOwners(vararg owners: Long): List<Long> =
    owners.distinct().filter { this[it]?.draft?.dirty == true }

internal data class NoteEditorActions(
    val load: suspend (Long, Long) -> Unit,
    val edit: (Long, String) -> Unit,
    val save: (Long, Long, NoteDraft) -> Unit,
    val useStored: (Long, NoteDraft) -> Unit,
    val keepDraft: (Long, NoteDraft) -> Unit,
)

/** Callbacks carry the rendered owner and context. */
internal fun NoteEditorActions.boundTo(
    token: ProfileIdentityToken?,
    isCurrent: (ProfileIdentityToken) -> Boolean,
): NoteEditorActions {
    fun admitted(owner: Long, listing: Long? = null) = token != null &&
        token.ownerId == owner && (listing == null || token.listingId == listing) && isCurrent(token)
    return NoteEditorActions(
        load = { listing, owner -> if (admitted(owner, listing)) load(listing, owner) },
        edit = { owner, value -> if (admitted(owner)) edit(owner, value) },
        save = { listing, owner, draft -> if (admitted(owner, listing)) save(listing, owner, draft) },
        useStored = { owner, draft -> if (admitted(owner)) useStored(owner, draft) },
        keepDraft = { owner, draft -> if (admitted(owner)) keepDraft(owner, draft) },
    )
}

internal class NoteReadTickets {
    private var next = 0L
    private val tickets = mutableMapOf<Long, Long>()

    fun invalidate(owner: Long): Long = (++next).also { tickets[owner] = it }
    fun isCurrent(owner: Long, ticket: Long): Boolean = tickets[owner] == ticket
    fun clear() = tickets.clear()
}
