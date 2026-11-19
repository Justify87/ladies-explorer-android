package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SavedSearchPaneTest {
    private fun info(id: String = "one", unread: Boolean = false, revision: Long = 1L) =
        SavedSearchInfo(id, id, "Leipzig", 20u, null, null, unread, true, "", null, revision)

    private class Fake(private val item: SavedSearchInfo) : SavedSearchAccess {
        var list = listOf(item)
        var loaded = SearchDraft(city = "Dresden", selectedFilters = mapOf("zeiten" to setOf("jetzt geöffnet")))
        var writeError = false
        var readError = false
        var afterWriteReadError = false
        var writes = 0
        var renames = 0
        var acknowledgements = 0
        var acceptAck = true
        var onRead: suspend () -> Unit = {}
        var onWrite: suspend () -> Unit = {}
        val windows = mutableListOf<UInt>()
        override suspend fun list(): List<SavedSearchInfo> {
            if (readError) error("read failed")
            return list
        }
        override suspend fun load(id: String) = loaded
        override suspend fun window(id: String, limit: UInt): SavedSearchWindow {
            windows += limit
            onRead()
            return SavedSearchWindow(emptyList(), limit < 400u, list)
        }
        override suspend fun create(name: String, draft: SearchDraft) { write() }
        override suspend fun update(id: String, name: String, draft: SearchDraft) { write(); loaded = draft }
        override suspend fun rename(id: String, name: String) { renames++; write() }
        override suspend fun delete(id: String) { write(); list = list.filterNot { it.searchId == id } }
        override suspend fun acknowledge(id: String, revision: Long): Boolean {
            acknowledgements++
            if (acceptAck) list = list.map { it.copy(unreadNewResults = false) }
            return acceptAck
        }
        override suspend fun check(id: String, control: RequestControl) { error("not used") }
        private suspend fun write() {
            onWrite()
            if (writeError) error("write failed")
            writes++
            if (afterWriteReadError) readError = true
        }
    }

    private fun controller(scope: CoroutineScope, fake: Fake, ordinary: SearchDraft = SearchDraft()) =
        SavedSearchController(scope, fake, { true }, { ordinary }, {})

    private suspend fun settle() { repeat(5) { yield() } }

    @Test fun ordinaryDraftAndEditorAreIndependentAndCancelDoesNotSerialize() = runBlocking {
        val ordinary = SearchDraft(city = " Berlin ", radiusKm = 30u,
            selectedFilters = mapOf("figur" to setOf("schlank")))
        val fake = Fake(info())
        val c = controller(this, fake, ordinary)
        c.open(); settle()
        c.openEditor("one"); settle()
        assertEquals(SearchPane.SavedEditor, c.state.value.pane)
        c.editDraft { it.copy(city = "Hamburg").toggle("zeiten", "heute geöffnet") }
        assertEquals("Hamburg", c.state.value.editor!!.draft.city)
        assertEquals(AvailabilityMode.Today, availabilityMode(c.state.value.editor!!.draft.selectedFilters))
        assertEquals(AvailabilityMode.All, availabilityMode(ordinary.selectedFilters))
        c.back()
        assertEquals(SearchPane.SavedList, c.state.value.pane)
        assertEquals(" Berlin ", ordinary.applyTo(SearchUiState()).city)
        assertEquals(setOf("schlank"), ordinary.selectedFilters["figur"])
        assertEquals("Dresden", fake.loaded.city)
        assertEquals(0, fake.writes)
        c.openEditor("one"); settle()
        c.editDraft { it.copy(city = "Köln") }
        c.saveEditor(); settle()
        assertEquals("Köln", fake.loaded.city)
        assertEquals(" Berlin ", ordinary.city)
        assertNull(c.state.value.editor)
    }

    @Test fun refreshCannotRaceWriteAndCommittedWriteIsNotRetriedOnReadFailure() = runBlocking {
        val fake = Fake(info())
        val c = controller(this, fake)
        c.open(); settle()
        c.showDialog(SavedDialogKind.Rename, "one")
        c.dialogName("New")
        fake.writeError = true
        c.confirmDialog(); settle()
        assertEquals("New", c.state.value.dialog?.name)
        assertEquals(SavedDialogKind.Rename, c.state.value.dialog?.kind)
        fake.writeError = false
        fake.afterWriteReadError = true
        val gate = CompletableDeferred<Unit>()
        fake.onWrite = { gate.await() }
        c.confirmDialog(); settle()
        c.refresh()
        assertEquals(SavedOperation.Write, c.state.value.operation)
        c.confirmDialog()
        gate.complete(Unit); settle()
        assertEquals(1, fake.writes)
        assertEquals(2, fake.renames)
        assertNull(c.state.value.dialog)
        assertFalse(c.state.value.listAvailable)
    }

    @Test fun staleWindowAfterBackCannotAcknowledgeAndReopeningReadsLoadedPrefix() = runBlocking {
        val fake = Fake(info(unread = true))
        val c = controller(this, fake)
        c.open(); settle()
        val gate = CompletableDeferred<Unit>()
        fake.onRead = { gate.await() }
        c.openResults("one"); settle()
        c.back()
        gate.complete(Unit); settle()
        assertNull(c.state.value.result)
        assertEquals(0, fake.acknowledgements)
        fake.onRead = {}
        c.openResults("one"); settle()
        c.more(); settle(); c.more(); settle()
        c.back()
        c.openResults("one"); settle()
        assertEquals(listOf(100u, 100u, 200u, 300u, 300u), fake.windows)
        assertTrue(c.state.value.result!!.ready)
        assertEquals(1, fake.acknowledgements)
    }

    @Test fun staleAcknowledgementKeepsUnreadAndDoesNotAutomaticallyRetry() = runBlocking {
        val fake = Fake(info(unread = true))
        fake.acceptAck = false
        val c = controller(this, fake)
        c.open(); settle()
        c.openResults("one"); settle()
        assertEquals(1, fake.acknowledgements)
        assertTrue(c.state.value.searches.single().unreadNewResults)
        assertNotNull(c.state.value.error)
    }
}
