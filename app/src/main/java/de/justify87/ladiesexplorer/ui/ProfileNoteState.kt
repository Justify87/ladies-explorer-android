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
        loading = false, error = null, readError = false,
    )
}

/** Only the ViewModel owns the drafts and mutations; the screens render this contract. */
internal data class NoteEditorActions(
    val load: suspend (Long, Long) -> Unit,
    val edit: (Long, String) -> Unit,
    val save: (Long, Long) -> Unit,
    val useStored: (Long) -> Unit,
    val keepDraft: (Long) -> Unit,
)
