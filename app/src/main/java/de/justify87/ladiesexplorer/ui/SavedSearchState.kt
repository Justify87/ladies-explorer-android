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
internal data class SavedResultWindow(
    val id: String,
    val cards: List<SavedSearchCard> = emptyList(),
    val hasMore: Boolean = false,
    val ready: Boolean = false,
    val revision: Long? = null,
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

// Testable IO seam; CoreStore remains the only Kotlin bridge to Rust.
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

/** One main-thread operation owner for saved definitions, reads, acknowledgement and checks.
 *  Cancellation signals Rust but keeps ownership until the call has drained. */
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
        mutable.update { it.copy(operation = kind, actionError = if (clearError) null else it.actionError) }
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

    /** Definition refresh also updates the badge; never materialize a hidden grid. */
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

    /** Native completions invalidate the projection even when another root is visible.
     * Coalesce while reading and consume only when the result pane is visible. */
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
        visible = value
        if (value) refreshDirtyWindow()
    }

    fun open() {
        if (!canOperate() || state.value.blockingNavigation) return
        mutable.update { it.copy(overview = true, actionError = null) }
        refresh()
    }

    fun back() {
        val s = state.value
        when {
            s.operation == SavedOperation.Check || s.operation == SavedOperation.Cancelling -> cancelCheck()
            s.operation == SavedOperation.Write -> Unit
            s.dialog != null -> dismissDialog()
            s.result != null -> { readTicket++; mutable.update { it.copy(result = null, resultDirty = false, windowError = null, actionError = null) } }
            s.editor != null -> mutable.update { it.copy(editor = null, actionError = null) }
            else -> { session++; mutable.update { it.copy(overview = false, actionError = null) } }
        }
    }

    fun openEditor(id: String) {
        val s = state.value
        val info = s.searches.firstOrNull { it.searchId == id && it.definitionSupported } ?: return
        if (s.busy || !canOperate() || s.pane != SearchPane.SavedList) return
        val ticket = ++session
        operation(SavedOperation.Editor) {
            val draft = try {
                store.load(id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (ticket == session) mutable.update { it.copy(actionError = failure.message ?: "Vorlage konnte nicht geladen werden") }
                return@operation
            }
            if (ticket == session && state.value.pane == SearchPane.SavedList) {
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

    fun saveEditor() {
        val editor = state.value.editor ?: return
        if (state.value.busy || !canOperate()) return
        operation(SavedOperation.Write) {
            store.update(editor.id, editor.name, editor.draft)
            mutable.update { it.copy(editor = null) }
            reload(committed = true)
        }
    }

    fun showDialog(kind: SavedDialogKind, id: String? = null) {
        val s = state.value
        if (s.busy || !canOperate() || s.pane != SearchPane.SavedList) return
        val draft = if (kind == SavedDialogKind.Create) ordinaryDraft() else null
        val name = if (draft != null) "${draft.city} · ${draft.radiusKm} km"
            else s.searches.firstOrNull { it.searchId == id }?.name ?: return
        mutable.update { it.copy(dialog = SavedDialog(kind, id, name, draft), actionError = null) }
    }

    fun dialogName(name: String) {
        if (state.value.busy) return
        mutable.update { it.copy(dialog = it.dialog?.copy(name = name)) }
    }

    fun dismissDialog() {
        if (state.value.operation == SavedOperation.Write) return
        mutable.update { it.copy(dialog = null, actionError = null) }
    }

    fun confirmDialog() {
        val dialog = state.value.dialog ?: return
        if (state.value.busy || !canOperate()) return
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

    fun acknowledge(id: String) {
        val info = state.value.searches.firstOrNull { it.searchId == id && it.unreadNewResults } ?: return
        val s = state.value
        if (s.busy || !canOperate() || !visible) return
        if (s.result != null && (s.result.id != id || !s.result.ready || s.resultDirty)) return
        // Explicit user intent is the linearization point for presentation.
        // The native revision CAS still prevents acknowledging a newer baseline.
        val revision = if (s.result != null) s.result.revision ?: return else info.revision
        operation(SavedOperation.Write) {
            val accepted = store.acknowledge(id, revision)
            reload(committed = accepted)
            if (!accepted) {
                markResultDirty()
                mutable.update { it.copy(actionError = "Ergebnisse haben sich geändert. Bitte erneut als gelesen markieren.") }
            }
        }
    }

    fun openResults(id: String) {
        if (state.value.busy || !canOperate() || state.value.pane != SearchPane.SavedList) return
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
                    window.searches.firstOrNull { info -> info.searchId == id }?.revision),
                searches = window.searches, listAvailable = true, windowError = null, listError = null) }
        }
    }

    fun check(id: String) {
        val s = state.value
        if (s.busy || !canOperate() || s.searches.none { it.searchId == id && it.definitionSupported }) return
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

    fun resetAfterImport() {
        limits.clear()
        refreshPending = false
        session++
        readTicket++
        mutable.update { SavedSearchUiState(operation = it.operation) }
    }
}
