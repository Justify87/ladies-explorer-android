package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SavedEditor(val id: String, val session: Long, val name: String, val draft: SearchDraft)
internal enum class SavedOperation { Refresh, Editor, Results, Write, Check, Cancelling }
internal enum class SavedDialogKind { Create, Rename, Delete }
internal data class SavedDialog(val kind: SavedDialogKind, val id: String?, val name: String, val draft: SearchDraft? = null)
internal enum class SearchPane { Filters, SavedList, SavedEditor, SavedResults }
internal data class SavedSearchProfileOpenAction(
    val window: SavedResultWindow,
    val card: SavedSearchCard,
    val generation: Long,
)
internal data class SavedSearchAckAction(
    val id: String,
    val revision: Long,
    val window: SavedResultWindow?,
    val generation: Long,
)
internal data class SavedSearchRowAction(
    val search: SavedSearchInfo,
    val generation: Long,
)
internal data class SavedDialogAction(val dialog: SavedDialog, val generation: Long)
internal data class SavedEditorSaveAction(val session: Long, val generation: Long)

internal data class SavedResultWindow(
    val id: String,
    val cards: List<SavedSearchCard> = emptyList(),
    val hasMore: Boolean = false,
    val ready: Boolean = false,
    val revision: Long? = null,
    // Distinct result reads must publish even if their cards/revision are structurally equal.
    val readGeneration: Long = 0L,
)
internal data class SavedSearchUiState(
    val overview: Boolean = false,
    val searches: List<SavedSearchInfo> = emptyList(),
    val listAvailable: Boolean = false,
    val editor: SavedEditor? = null,
    val result: SavedResultWindow? = null,
    val dialog: SavedDialog? = null,
    val operation: SavedOperation? = null,
    val actionError: String? = null,
    val listError: String? = null,
    val windowError: String? = null,
    val resultDirty: Boolean = false,
    val actionGeneration: Long = 0L,
) {
    val error get() = actionError ?: if (result != null) windowError ?: listError else listError
    val busy get() = operation != null
    val blockingNavigation get() = operation == SavedOperation.Write || operation == SavedOperation.Check || operation == SavedOperation.Cancelling
    val pane get() = when {
        result != null -> SearchPane.SavedResults
        editor != null -> SearchPane.SavedEditor
        overview -> SearchPane.SavedList
        else -> SearchPane.Filters
    }
}

internal class SavedCheck(val run: suspend () -> Unit, val cancel: () -> Unit)

internal interface SavedSearchAccess {
    suspend fun list(): List<SavedSearchInfo>
    suspend fun load(id: String): SearchDraft
    suspend fun window(id: String, limit: UInt): SavedSearchWindow
    suspend fun create(name: String, draft: SearchDraft)
    suspend fun update(id: String, name: String, draft: SearchDraft)
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun acknowledge(id: String, revision: Long): Boolean
    fun prepareCheck(id: String): SavedCheck
}

internal class SavedSearchStore(private val core: CoreStore) : SavedSearchAccess {
    override suspend fun list() = core.savedSearches()
    override suspend fun load(id: String): SearchDraft {
        val def = core.loadSavedSearch(id)
        return SearchDraft.from(def.query, def.local)
    }
    override suspend fun window(id: String, limit: UInt) = core.savedSearchWindow(id, limit)
    override suspend fun create(name: String, draft: SearchDraft) { core.saveSearch(name, draft.request(), draft.local) }
    override suspend fun update(id: String, name: String, draft: SearchDraft) { core.updateSavedSearch(id, name, draft.request(), draft.local) }
    override suspend fun rename(id: String, name: String) { core.renameSavedSearch(id, name) }
    override suspend fun delete(id: String) { core.deleteSavedSearch(id) }
    override suspend fun acknowledge(id: String, revision: Long) = core.acknowledgeSavedSearch(id, revision)
    override fun prepareCheck(id: String): SavedCheck {
        val control = RequestControl()
        return SavedCheck({ core.runSavedSearch(id, control) }, control::cancel)
    }
}

/** Cancellation signals Rust; operation ownership lasts until drain. */
internal class SavedSearchController(
    private val scope: CoroutineScope,
    private val store: SavedSearchAccess,
    private val canOperate: () -> Boolean,
    private val ordinaryDraft: () -> SearchDraft,
    private val onSourceFinished: () -> Unit,
) {
    private val mutable = MutableStateFlow(SavedSearchUiState())
    val state: StateFlow<SavedSearchUiState> = mutable
    private var refreshPending = false
    private var readTicket = 0L
    // Fail closed until the foreground route explicitly reports visibility.
    private var visible = false
    private var session = 0L
    private var control: SavedCheck? = null
    private val limits = mutableMapOf<String, UInt>()

    private fun operation(kind: SavedOperation, clearError: Boolean = true, work: suspend () -> Unit) {
        kotlin.check(!state.value.busy)
        mutable.update { it.copy(operation = kind, actionError = if (clearError) null else it.actionError,
            actionGeneration = it.actionGeneration + 1) }
        scope.launch {
            try {
                work()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                mutable.update { it.copy(actionError = failure.message ?: "Gespeicherte Suche fehlgeschlagen") }
            } finally {
                mutable.update { it.copy(operation = null) }
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                } else refreshDirtyWindow()
            }
        }
    }

    private suspend fun reload(committed: Boolean = false) {
        try {
            val list = store.list()
            mutable.update { it.copy(searches = list, listAvailable = true, listError = null) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            mutable.update { it.copy(searches = emptyList(), listAvailable = false,
                listError = if (committed) "Änderung gespeichert; Übersicht konnte nicht geladen werden. Bitte aktualisieren."
                else failure.message ?: "Übersicht konnte nicht geladen werden") }
        }
    }

    /** Refresh badge without materializing a hidden grid. */
    fun refresh() {
        if (state.value.result != null) markResultDirty()
        if (state.value.busy) { refreshPending = true; return }
        val result = state.value.result
        if (result != null && visible) refreshDirtyWindow()
        else operation(SavedOperation.Refresh, clearError = false) { reload() }
    }

    private fun markResultDirty() {
        mutable.update { s ->
            if (s.result == null) s else s.copy(resultDirty = true)
        }
    }

    /** Coalesce invalidations until results are visible. */
    fun refreshResultProjection() {
        markResultDirty()
        refreshDirtyWindow()
    }

    private fun refreshDirtyWindow() {
        val s = state.value
        val result = s.result ?: return
        if (visible && s.resultDirty && !s.busy) {
            readWindow(result.id, limits[result.id] ?: 100u)
        }
    }

    fun setVisible(value: Boolean) {
        if (visible != value) {
            visible = value
            mutable.update { it.copy(actionGeneration = it.actionGeneration + 1) }
        }
        if (value) refreshDirtyWindow()
    }

    /** A delayed grid click must still refer to the currently rendered result window. */
    fun admitsProfileOpen(action: SavedSearchProfileOpenAction): Boolean {
        val current = state.value
        return visible && canOperate() && !current.busy && !current.resultDirty &&
            current.pane == SearchPane.SavedResults &&
            current.result === action.window && action.window.ready &&
            action.card.listingId != null && action.card.personId != null &&
            action.window.cards.any { it == action.card }
    }

    fun open() {
        if (!canOperate() || state.value.blockingNavigation) return
        mutable.update { it.copy(overview = true, actionError = null) }
        refresh()
    }

    fun back() {
        val s = state.value
        when {
            s.operation == SavedOperation.Write -> Unit
            s.operation == SavedOperation.Check || s.operation == SavedOperation.Cancelling -> cancelCheck()
            s.dialog != null -> dismissDialog()
            s.result != null -> { readTicket++; mutable.update { it.copy(result = null, resultDirty = false,
                windowError = null, actionError = null, actionGeneration = it.actionGeneration + 1) } }
            s.editor != null -> mutable.update { it.copy(editor = null, actionError = null,
                actionGeneration = it.actionGeneration + 1) }
            else -> { session++; mutable.update { it.copy(overview = false, actionError = null,
                actionGeneration = it.actionGeneration + 1) } }
        }
    }

    private fun admittedRow(action: SavedSearchRowAction): SavedSearchInfo? {
        val s = state.value
        return action.search.takeIf { row ->
            visible && canOperate() && !s.busy && s.listAvailable &&
                s.pane == SearchPane.SavedList && s.dialog == null &&
                s.actionGeneration == action.generation &&
                s.searches.any { it === row }
        }
    }

    fun openEditor(action: SavedSearchRowAction) {
        val info = admittedRow(action)?.takeIf { it.definitionSupported } ?: return
        val id = info.searchId
        val ticket = ++session
        val generation = action.generation + 1
        val stillCurrent = {
            ticket == session && visible && canOperate() &&
                state.value.pane == SearchPane.SavedList && state.value.actionGeneration == generation
        }
        operation(SavedOperation.Editor) {
            val draft = try {
                store.load(id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (stillCurrent()) mutable.update { it.copy(actionError = failure.message ?: "Vorlage konnte nicht geladen werden") }
                return@operation
            }
            if (stillCurrent()) {
                mutable.update { it.copy(editor = SavedEditor(id, ticket, info.name, draft)) }
            }
        }
    }

    fun editDraft(change: (SearchDraft) -> SearchDraft) {
        if (state.value.busy) return
        mutable.update { s -> s.copy(editor = s.editor?.let { it.copy(draft = change(it.draft)) }) }
    }

    fun editName(name: String) {
        if (state.value.busy) return
        mutable.update { s -> s.copy(editor = s.editor?.copy(name = name)) }
    }

    fun saveEditor(action: SavedEditorSaveAction) {
        val s = state.value
        val editor = s.editor ?: return
        if (!visible || s.busy || !canOperate() || s.pane != SearchPane.SavedEditor ||
            editor.session != action.session || s.actionGeneration != action.generation) return
        operation(SavedOperation.Write) {
            store.update(editor.id, editor.name, editor.draft)
            mutable.update { it.copy(editor = null) }
            reload(committed = true)
        }
    }

    fun showDialog(kind: SavedDialogKind, action: SavedSearchRowAction? = null) {
        val s = state.value
        if (!visible || s.busy || !s.listAvailable || !canOperate() ||
            s.pane != SearchPane.SavedList || s.dialog != null) return
        val info = if (kind == SavedDialogKind.Create) {
            if (action != null) return
            null
        } else admittedRow(action ?: return) ?: return
        val draft = if (kind == SavedDialogKind.Create) ordinaryDraft() else null
        val name = if (draft != null) "${draft.city} · ${draft.radiusKm} km" else info!!.name
        mutable.update { it.copy(dialog = SavedDialog(kind, info?.searchId, name, draft), actionError = null,
            actionGeneration = it.actionGeneration + 1) }
    }

    fun dialogName(name: String) {
        if (state.value.busy) return
        mutable.update { it.copy(dialog = it.dialog?.copy(name = name)) }
    }

    fun dismissDialog() {
        if (state.value.operation == SavedOperation.Write || state.value.dialog == null) return
        mutable.update { it.copy(dialog = null, actionError = null,
            actionGeneration = it.actionGeneration + 1) }
    }

    fun confirmDialog(action: SavedDialogAction) {
        val s = state.value
        val dialog = s.dialog ?: return
        if (!visible || s.busy || !canOperate() || s.pane != SearchPane.SavedList ||
            s.actionGeneration != action.generation || dialog !== action.dialog) return
        operation(SavedOperation.Write) {
            when (dialog.kind) {
                SavedDialogKind.Create -> store.create(dialog.name, requireNotNull(dialog.draft))
                SavedDialogKind.Rename -> store.rename(requireNotNull(dialog.id), dialog.name)
                SavedDialogKind.Delete -> {
                    val id = requireNotNull(dialog.id)
                    store.delete(id)
                    limits.remove(id)
                }
            }
            mutable.update { it.copy(dialog = null) }
            reload(committed = true)
        }
    }

    fun acknowledge(action: SavedSearchAckAction) {
        val s = state.value
        if (s.busy || !canOperate() || !visible || s.dialog != null ||
            s.actionGeneration != action.generation) return
        if (action.window == null) {
            if (s.pane != SearchPane.SavedList) return
        } else if (s.pane != SearchPane.SavedResults || s.result !== action.window ||
            action.window.id != action.id || !action.window.ready || s.resultDirty ||
            action.window.revision != action.revision) return

        val info = s.searches.firstOrNull { it.searchId == action.id && it.unreadNewResults } ?: return
        // Acknowledge only the explicitly rendered pane/window and revision.
        val revision = action.window?.revision ?: info.revision
        if (action.revision != revision) {
            mutable.update { it.copy(actionError = "Ergebnisse haben sich geändert. Bitte erneut als gelesen markieren.") }
            return
        }
        operation(SavedOperation.Write) {
            val accepted = store.acknowledge(action.id, action.revision)
            reload(committed = accepted)
            if (!accepted) {
                markResultDirty()
                mutable.update { it.copy(actionError = "Ergebnisse haben sich geändert. Bitte erneut als gelesen markieren.") }
            }
        }
    }

    fun openResults(action: SavedSearchRowAction) {
        val id = admittedRow(action)?.searchId ?: return
        mutable.update { it.copy(result = SavedResultWindow(id), resultDirty = false, windowError = null, actionError = null) }
        readWindow(id, limits[id] ?: 100u)
    }

    fun more() {
        val result = state.value.result ?: return
        if (state.value.busy || state.value.resultDirty || !result.hasMore || !canOperate()) return
        val next = (limits[result.id] ?: 100u).coerceAtMost(UInt.MAX_VALUE - 100u) + 100u
        readWindow(result.id, next)
    }

    fun retryResults() {
        val result = state.value.result ?: return
        if (state.value.busy || !canOperate()) return
        readWindow(result.id, limits[result.id] ?: 100u)
    }

    private fun readWindow(id: String, limit: UInt) {
        val ticket = ++readTicket
        mutable.update { it.copy(resultDirty = false, windowError = null) }
        operation(SavedOperation.Results, clearError = false) {
            val window = try {
                store.window(id, limit)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (ticket == readTicket && state.value.result?.id == id) mutable.update {
                    it.copy(result = SavedResultWindow(id), windowError = failure.message ?: "Ergebnisse konnten nicht geladen werden")
                }
                return@operation
            }
            if (ticket != readTicket || state.value.result?.id != id) return@operation
            limits[id] = limit
            mutable.update { it.copy(result = SavedResultWindow(id, window.cards, window.hasMore, true,
                    window.searches.firstOrNull { info -> info.searchId == id }?.revision, ticket),
                searches = window.searches, listAvailable = true, windowError = null, listError = null) }
        }
    }

    fun check(action: SavedSearchRowAction) {
        val id = admittedRow(action)?.takeIf { it.definitionSupported }?.searchId ?: return
        val request = store.prepareCheck(id)
        control = request
        operation(SavedOperation.Check) {
            try {
                request.run()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (state.value.operation != SavedOperation.Cancelling) mutable.update {
                    it.copy(actionError = failure.message ?: "Prüfung fehlgeschlagen")
                }
            } finally {
                control = null
                reload()
                onSourceFinished()
            }
        }
    }

    fun cancelCheck() {
        if (state.value.operation != SavedOperation.Check) return
        mutable.update { it.copy(operation = SavedOperation.Cancelling) }
        control?.cancel?.invoke()
    }
}
