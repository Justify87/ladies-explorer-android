package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SavedSearchPaneTest {
    private fun info(id: String = "one", unread: Boolean = false, revision: Long = 1L) =
        SavedSearchInfo(id, id, "Leipzig", 20u, null, null, unread, true, "", null, revision)

    private class Fake(private val item: SavedSearchInfo) : SavedSearchAccess {
        var list = listOf(item)
        var cards = emptyList<SavedSearchCard>()
        var loaded = SearchDraft(city = "Dresden", selectedFilters = mapOf("zeiten" to setOf("jetzt geöffnet")))
        var writeError = false
        var windowError = false
        var readError = false
        var afterWriteReadError = false
        var writes = 0
        var renames = 0
        var acknowledgements = 0
        var acceptAck = true
        var onAck: suspend () -> Unit = {}
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
            // Capture the DB snapshot before delaying delivery to the UI.
            val snapshot = SavedSearchWindow(cards, limit < 400u, list)
            onRead()
            if (windowError) error("window failed")
            return snapshot
        }
        override suspend fun create(name: String, draft: SearchDraft) { write() }
        override suspend fun update(id: String, name: String, draft: SearchDraft) { write(); loaded = draft }
        override suspend fun rename(id: String, name: String) { renames++; write() }
        override suspend fun delete(id: String) { write(); list = list.filterNot { it.searchId == id } }
        override suspend fun acknowledge(id: String, revision: Long): Boolean {
            acknowledgements++
            onAck()
            val accepted = acceptAck && list.any { it.searchId == id && it.revision == revision && it.unreadNewResults }
            if (accepted) list = list.map { if (it.searchId == id) it.copy(unreadNewResults = false) else it }
            return accepted
        }
        var onCheck: suspend () -> Unit = {}
        var cancels = 0
        override fun prepareCheck(id: String) = SavedCheck({ onCheck() }, { cancels++ })
        private suspend fun write() {
            onWrite()
            if (writeError) error("write failed")
            writes++
            if (afterWriteReadError) readError = true
        }
    }

    private fun controller(scope: CoroutineScope, fake: Fake, ordinary: SearchDraft = SearchDraft()) =
        SavedSearchController(scope, fake, { true }, { ordinary }, {}).apply { setVisible(true) }

    private suspend fun idle(c: SavedSearchController) {
        withTimeout(5_000) { c.state.first { !it.busy } }
    }

    private fun ack(c: SavedSearchController): SavedSearchAckAction {
        val s = c.state.value
        val info = s.searches.single { it.searchId == "one" }
        return SavedSearchAckAction(info.searchId, s.result?.revision ?: info.revision,
            s.result, s.ackGeneration)
    }

    @Test(timeout = 15_000) fun ordinaryDraftAndEditorAreIndependentAndCancelDoesNotSerialize() = runBlocking {
        val ordinary = SearchDraft(city = " Berlin ", radiusKm = 30u,
            selectedFilters = mapOf("figur" to setOf("schlank")))
        val fake = Fake(info())
        val c = controller(this, fake, ordinary)
        c.open(); idle(c)
        c.openEditor("one"); idle(c)
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
        c.openEditor("one"); idle(c)
        c.editDraft { it.copy(city = "Köln") }
        c.saveEditor(); idle(c)
        assertEquals("Köln", fake.loaded.city)
        assertEquals(" Berlin ", ordinary.city)
        assertNull(c.state.value.editor)
    }

    @Test(timeout = 15_000) fun refreshCannotRaceWriteAndCommittedWriteIsNotRetriedOnReadFailure() = runBlocking {
        val fake = Fake(info())
        val c = controller(this, fake)
        c.open(); idle(c)
        c.showDialog(SavedDialogKind.Rename, "one")
        c.dialogName("New")
        fake.writeError = true
        c.confirmDialog(); idle(c)
        assertEquals("New", c.state.value.dialog?.name)
        assertEquals(SavedDialogKind.Rename, c.state.value.dialog?.kind)
        fake.writeError = false
        fake.afterWriteReadError = true
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        fake.onWrite = { started.complete(Unit); gate.awaitSignal() }
        c.confirmDialog(); started.awaitSignal()
        c.refresh()
        assertEquals(SavedOperation.Write, c.state.value.operation)
        c.confirmDialog()
        gate.complete(Unit); idle(c)
        assertEquals(1, fake.writes)
        assertEquals(2, fake.renames)
        assertNull(c.state.value.dialog)
        assertFalse(c.state.value.listAvailable)
    }

    @Test(timeout = 15_000) fun staleWindowAfterBackCannotAcknowledgeAndReopeningReadsLoadedPrefix() = runBlocking {
        val fake = Fake(info(unread = true))
        val c = controller(this, fake)
        c.open(); idle(c)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        fake.onRead = { started.complete(Unit); gate.awaitSignal() }
        c.openResults("one"); started.awaitSignal()
        c.back()
        gate.complete(Unit); idle(c)
        assertNull(c.state.value.result)
        assertEquals(0, fake.acknowledgements)
        fake.onRead = {}
        c.openResults("one"); idle(c)
        c.more(); idle(c); c.more(); idle(c)
        c.back()
        c.openResults("one"); idle(c)
        assertEquals(listOf(100u, 100u, 200u, 300u, 300u), fake.windows)
        assertTrue(c.state.value.result!!.ready)
        assertEquals(0, fake.acknowledgements)
        c.acknowledge(ack(c)); idle(c)
        assertEquals(1, fake.acknowledgements)
    }

    @Test(timeout = 15_000) fun cancellationRetainsOperationUntilNativeCallDrains() = runBlocking {
        val fake = Fake(info())
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        fake.onCheck = { started.complete(Unit); gate.awaitSignal() }
        var finished = 0
        val c = SavedSearchController(this, fake, { true }, { SearchDraft() }, { finished++ })
        c.open(); idle(c)
        c.check("one"); started.awaitSignal()
        c.cancelCheck()
        assertEquals(SavedOperation.Cancelling, c.state.value.operation)
        assertEquals(1, fake.cancels)
        c.refresh()
        c.check("one")
        assertEquals(0, finished)
        gate.complete(Unit); idle(c)
        assertFalse(c.state.value.busy)
        assertEquals(1, finished)
        assertEquals(1, fake.cancels)
    }

    @Test(timeout = 15_000) fun hiddenWindowDoesNotAcknowledgeUntilExplicitVisibleAction() = runBlocking {
        val fake = Fake(info(unread = true))
        val c = controller(this, fake)
        c.open(); idle(c)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        fake.onRead = { started.complete(Unit); gate.awaitSignal() }
        c.openResults("one"); started.awaitSignal()
        c.setVisible(false)
        gate.complete(Unit); idle(c)
        assertTrue(c.state.value.result!!.ready)
        assertEquals(0, fake.acknowledgements)
        assertTrue(c.state.value.searches.single().unreadNewResults)
        c.setVisible(true)
        assertEquals(0, fake.acknowledgements)
        fake.onRead = {}
        c.retryResults(); idle(c)
        assertEquals(0, fake.acknowledgements)
        c.acknowledge(ack(c)); idle(c)
        assertEquals(1, fake.acknowledgements)
        assertFalse(c.state.value.searches.single().unreadNewResults)
    }

    private fun fixtureCard() = SavedSearchCard("synthetic", 1L, 2L, "Fixture", "Leipzig",
        null, "2026-01-01T00:00:00Z", null, null)

    @Test(timeout = 15_000) fun lateNativeWriteQueuesCoherentResultRefreshWithoutAcknowledging() = runBlocking {
        val initial = fixtureCard()
        val updated = initial.copy(personId = 3L, localPath = "/fixture/image.jpg")
        val fake = Fake(info()).apply { cards = listOf(initial) }
        val c = controller(this, fake)
        c.open(); idle(c)
        c.openResults("one"); idle(c)
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        fake.onRead = { started.complete(Unit); gate.awaitSignal() }
        c.more(); started.awaitSignal()
        assertEquals(listOf(initial), c.state.value.result!!.cards)
        fake.cards = listOf(updated)
        fake.list = listOf(info(unread = true, revision = 2L))
        c.refreshResultProjection()
        assertEquals(SavedOperation.Results, c.state.value.operation)
        gate.complete(Unit); idle(c)
        assertEquals(listOf(100u, 200u, 200u), fake.windows)
        assertEquals(listOf(updated), c.state.value.result!!.cards)
        assertTrue(c.state.value.result!!.ready)
        assertTrue(c.state.value.searches.single().unreadNewResults)
        assertEquals(0, fake.acknowledgements)
    }

    @Test(timeout = 15_000) fun failedReprojectionInvalidatesActionsAndRetryRetainsWindowCapacity() = runBlocking {
        val card = fixtureCard()
        val fake = Fake(info()).apply { cards = listOf(card) }
        val c = controller(this, fake)
        c.open(); idle(c)
        c.openResults("one"); idle(c)
        c.more(); idle(c)
        assertEquals(listOf(card), c.state.value.result!!.cards)
        fake.windowError = true
        c.refreshResultProjection(); idle(c)
        val failed = c.state.value.result!!
        assertFalse(failed.ready)
        assertFalse(failed.hasMore)
        assertTrue(failed.cards.isEmpty())
        assertNotNull(c.state.value.error)
        fake.windowError = false
        c.retryResults(); idle(c)
        assertTrue(c.state.value.result!!.ready)
        assertEquals(listOf(card), c.state.value.result!!.cards)
        assertNull(c.state.value.error)
        assertEquals(listOf(100u, 200u, 200u, 200u), fake.windows)
    }

    @Test(timeout = 15_000) fun staleAcknowledgementKeepsUnreadAndDoesNotAutomaticallyRetry() = runBlocking {
        val fake = Fake(info(unread = true))
        fake.acceptAck = false
        val c = controller(this, fake)
        c.open(); idle(c)
        c.openResults("one"); idle(c)
        c.acknowledge(ack(c)); idle(c)
        assertEquals(1, fake.acknowledgements)
        assertTrue(c.state.value.searches.single().unreadNewResults)
        assertNotNull(c.state.value.error)
    }
    @Test(timeout = 15_000) fun hiddenInvalidationsCoalesceAndSuccessfulReadClearsOnlyItsError() = runBlocking {
        val fake = Fake(info()).apply { cards = listOf(fixtureCard()) }
        val c = controller(this, fake)
        c.open(); idle(c)
        c.openResults("one"); idle(c)
        fake.windowError = true
        c.refreshResultProjection(); idle(c)
        assertNotNull(c.state.value.windowError)
        c.setVisible(false)
        fake.windowError = false
        repeat(5) { c.refreshResultProjection() }
        assertEquals(listOf(100u, 100u), fake.windows)
        assertTrue(c.state.value.resultDirty)
        assertFalse(c.state.value.busy)
        c.setVisible(true); idle(c)
        assertEquals(listOf(100u, 100u, 100u), fake.windows)
        assertNull(c.state.value.windowError)
        assertTrue(c.state.value.result!!.ready)
        assertFalse(c.state.value.resultDirty)
        assertEquals(0, fake.acknowledgements)
    }

    @Test(timeout = 15_000) fun explicitAckUsesDisplayedRevisionEvenIfVisibilityChangesDuringIo() = runBlocking {
        val fake = Fake(info(unread = true))
        val c = controller(this, fake)
        c.open(); idle(c)
        c.openResults("one"); idle(c)
        val displayed = ack(c)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        fake.onAck = { started.complete(Unit); finish.awaitSignal() }
        c.acknowledge(displayed)
        started.awaitSignal()
        c.setVisible(false)
        fake.list = listOf(info(unread = true, revision = 2L))
        finish.complete(Unit); idle(c)
        assertEquals(1, fake.acknowledgements)
        assertTrue(c.state.value.searches.single().unreadNewResults)
        assertNotNull(c.state.value.actionError)
        assertTrue(c.state.value.resultDirty)
        c.acknowledge(displayed)
        assertEquals(1, fake.acknowledgements)
        c.setVisible(true); idle(c)
        assertEquals(2L, c.state.value.result!!.revision)
        c.acknowledge(displayed) // stale rendered callback must not clear the new revision
        assertEquals(1, fake.acknowledgements)
        c.acknowledge(ack(c)); idle(c)
        assertEquals(2, fake.acknowledgements)
        assertFalse(c.state.value.searches.single().unreadNewResults)
    }

    @Test(timeout = 15_000) fun profileOpenUsesExactlyTheVisibleSavedResultWindow() = runBlocking {
        val card = fixtureCard()
        val fake = Fake(info()).apply { cards = listOf(card) }
        val c = controller(this, fake)
        c.open(); idle(c)
        c.openResults("one"); idle(c)
        val shown = c.state.value.result!!
        val click = SavedSearchProfileOpenAction(shown, card, 12)
        assertTrue(c.admitsProfileOpen(click))
        assertFalse(c.admitsProfileOpen(click.copy(card = card.copy(personId = 99L))))
        c.setVisible(false)
        assertFalse(c.admitsProfileOpen(click))
        c.setVisible(true)
        assertTrue(c.admitsProfileOpen(click))
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        fake.onRead = { started.complete(Unit); gate.awaitSignal() }
        c.more(); started.awaitSignal()
        assertFalse(c.admitsProfileOpen(click))
        gate.complete(Unit); idle(c)
        assertFalse(c.admitsProfileOpen(click))
        val refreshed = SavedSearchProfileOpenAction(c.state.value.result!!, card, 12)
        assertTrue(c.admitsProfileOpen(refreshed))
        fake.cards = listOf(card.copy(personId = 3L))
        fake.onRead = {}
        c.refreshResultProjection()
        assertFalse(c.admitsProfileOpen(refreshed))
        idle(c)
        assertFalse(c.admitsProfileOpen(refreshed))
        val updated = c.state.value.result!!
        assertTrue(c.admitsProfileOpen(SavedSearchProfileOpenAction(updated, updated.cards.single(), 12)))
        c.back()
        assertFalse(c.admitsProfileOpen(SavedSearchProfileOpenAction(updated, updated.cards.single(), 12)))
    }

    @Test(timeout = 15_000) fun successfulAcknowledgementWithFailedListReadIsNotRepeatedByRefresh() = runBlocking {
        val fake = Fake(info(unread = true))
        val c = controller(this, fake)
        c.open(); idle(c)
        c.openResults("one"); idle(c)
        fake.onAck = { fake.readError = true }
        c.acknowledge(ack(c)); idle(c)
        assertEquals(1, fake.acknowledgements)
        assertFalse(fake.list.single().unreadNewResults)
        assertFalse(c.state.value.listAvailable)
        assertNotNull(c.state.value.listError)
        // Re-read after the native acknowledgement; never reissue that operation.
        fake.readError = false
        fake.onAck = {}
        c.refresh(); idle(c)
        assertEquals(1, fake.acknowledgements)
        assertTrue(c.state.value.listAvailable)
        assertFalse(c.state.value.searches.single().unreadNewResults)
    }

    @Test(timeout = 15_000) fun acknowledgementCannotCrossOverviewAndResultPanes() = runBlocking {
        val fake = Fake(info(unread = true))
        val c = controller(this, fake)
        c.open(); idle(c)
        val overview = ack(c)
        c.openResults("one"); idle(c)
        c.acknowledge(overview)
        assertEquals(0, fake.acknowledgements)

        val results = ack(c)
        c.back()
        c.acknowledge(results)
        assertEquals(0, fake.acknowledgements)

        c.acknowledge(ack(c)); idle(c)
        assertEquals(1, fake.acknowledgements)
        assertFalse(c.state.value.searches.single().unreadNewResults)
    }

    @Test(timeout = 15_000) fun acknowledgementRejectsOldReadsVisibilityAndModalCallbacks() = runBlocking {
        val fake = Fake(info(unread = true))
        val c = controller(this, fake)
        c.open(); idle(c)
        c.openResults("one"); idle(c)
        val oldWindow = ack(c)
        c.retryResults(); idle(c)
        c.acknowledge(oldWindow)
        assertEquals(0, fake.acknowledgements)

        val beforeStop = ack(c)
        c.setVisible(false)
        c.setVisible(true)
        c.acknowledge(beforeStop)
        assertEquals(0, fake.acknowledgements)

        c.back()
        val beforeModal = ack(c)
        c.showDialog(SavedDialogKind.Rename, "one")
        c.acknowledge(beforeModal)
        c.dismissDialog()
        c.acknowledge(beforeModal)
        assertEquals(0, fake.acknowledgements)

        c.acknowledge(ack(c)); idle(c)
        assertEquals(1, fake.acknowledgements)
    }
}
