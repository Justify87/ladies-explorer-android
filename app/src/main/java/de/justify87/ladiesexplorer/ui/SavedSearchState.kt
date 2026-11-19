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
)
internal data class SavedSearchUiState(
    val overview: Boolean = false,
    val searches: List<SavedSearchInfo> = emptyList(),
    val listAvailable: Boolean = false,
    val editor: SavedEditor? = null,
    val result: SavedResultWindow? = null,
    val dialog: SavedDialog? = null,
    val operation: SavedOperation? = null,
    val error: String? = null,
) {
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
    private var visible = true
    private var session = 0L
    private var control: SavedCheck? = null
    private val limits = mutableMapOf<String, UInt>()

    private fun operation(kind: SavedOperation, clearError: Boolean = true, work: suspend () -> Unit) {
        kotlin.check(!state.value.busy)
        mutable.update { it.copy(operation = kind, error = if (clearError) null else it.error) }
        scope.launch {
            try {
                work()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                mutable.update { it.copy(error = failure.message ?: "Gespeicherte Suche fehlgeschlagen") }
            } finally {
                mutable.update { it.copy(operation = null) }
                if (refreshPending) {
                    refreshPending = false
                    refresh(clearError = false)
                }
            }
        }
    }

    private suspend fun reload(committed: Boolean = false) {
        try {
            val list = store.list()
            mutable.update { it.copy(searches = list, listAvailable = true) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            mutable.update { it.copy(searches = emptyList(), listAvailable = false,
                error = if (committed) "Änderung gespeichert; Übersicht konnte nicht geladen werden. Bitte aktualisieren."
                else it.error ?: failure.message ?: "Übersicht konnte nicht geladen werden") }
        }
    }

    fun refresh() = refresh(clearError = true)

    private fun refresh(clearError: Boolean) {
        if (state.value.busy) { refreshPending = true; return }
        val result = state.value.result
        if (result != null) {
            readWindow(result.id, limits[result.id] ?: 100u, acknowledge = false, clearError = clearError)
        } else operation(SavedOperation.Refresh, clearError) { reload() }
    }

    fun setVisible(value: Boolean) {
        visible = value
    }

    fun open() {
        if (!canOperate() || state.value.blockingNavigation) return
        mutable.update { it.copy(overview = true, error = null) }
        refresh()
    }

    fun back() {
        val s = state.value
        when {
            s.operation == SavedOperation.Check || s.operation == SavedOperation.Cancelling -> cancelCheck()
            s.operation == SavedOperation.Write -> Unit
            s.dialog != null -> dismissDialog()
            s.result != null -> { readTicket++; mutable.update { it.copy(result = null, error = null) } }
            s.editor != null -> mutable.update { it.copy(editor = null, error = null) }
            else -> { session++; mutable.update { it.copy(overview = false, error = null) } }
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
                if (ticket == session) mutable.update { it.copy(error = failure.message ?: "Vorlage konnte nicht geladen werden") }
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
        mutable.update { it.copy(dialog = SavedDialog(kind, id, name, draft), error = null) }
    }

    fun dialogName(name: String) {
        if (state.value.busy) return
        mutable.update { it.copy(dialog = it.dialog?.copy(name = name)) }
    }

    fun dismissDialog() {
        if (state.value.operation == SavedOperation.Write) return
        mutable.update { it.copy(dialog = null, error = null) }
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
        if (state.value.busy || !canOperate()) return
        operation(SavedOperation.Write) {
            val accepted = store.acknowledge(id, info.revision)
            reload(committed = accepted)
            if (!accepted && state.value.listAvailable) mutable.update {
                it.copy(error = "Ergebnisse haben sich geändert. Bitte erneut als gelesen markieren.")
            }
        }
    }

    fun openResults(id: String) {
        if (state.value.busy || !canOperate() || state.value.pane != SearchPane.SavedList) return
        mutable.update { it.copy(result = SavedResultWindow(id), error = null) }
        readWindow(id, limits[id] ?: 100u, acknowledge = true)
    }

    fun more() {
        val result = state.value.result ?: return
        if (state.value.busy || !result.hasMore || !canOperate()) return
        val next = (limits[result.id] ?: 100u).coerceAtMost(UInt.MAX_VALUE - 100u) + 100u
        readWindow(result.id, next, acknowledge = false)
    }

    fun retryResults() {
        val result = state.value.result ?: return
        if (state.value.busy || !canOperate()) return
        readWindow(result.id, limits[result.id] ?: 100u, acknowledge = true)
    }

    private fun readWindow(id: String, limit: UInt, acknowledge: Boolean, clearError: Boolean = true) {
        val ticket = ++readTicket
        operation(SavedOperation.Results, clearError) {
            val window = try {
                store.window(id, limit)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (ticket == readTicket && state.value.result?.id == id) mutable.update {
                    it.copy(error = failure.message ?: "Ergebnisse konnten nicht geladen werden")
                }
                return@operation
            }
            if (ticket != readTicket || state.value.result?.id != id) return@operation
            limits[id] = limit
            mutable.update { it.copy(result = SavedResultWindow(id, window.cards, window.hasMore, true),
                searches = window.searches, listAvailable = true) }
            val info = window.searches.firstOrNull { it.searchId == id && it.unreadNewResults }
            if (acknowledge && visible && info != null && ticket == readTicket && state.value.result?.id == id) {
                val accepted = store.acknowledge(id, info.revision)
                reload(committed = accepted)
                // A newer baseline must remain unread; do not retry it automatically.
                if (!accepted && state.value.result?.id == id && state.value.listAvailable) mutable.update {
                    it.copy(error = "Neue Ergebnisse verfügbar. Bitte Ergebnisliste aktualisieren.")
                }
            }
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
                    it.copy(error = failure.message ?: "Prüfung fehlgeschlagen")
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
        session++
        readTicket++
        mutable.update { SavedSearchUiState(operation = it.operation) }
    }
}
