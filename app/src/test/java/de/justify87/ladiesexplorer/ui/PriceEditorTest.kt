package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PriceEditorTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val token = SwipeActionToken(null, 1, 4, 1, 11, 1, Preference.NONE, false, emptyList())
    private val values = PriceListValues(null, "2026-11-24T00:00:00.000Z",
        listOf(PriceItem(null, null, 0), PriceItem("Fixture", 30, 1234)))
    private val row = PriceList(5, 1, values, listOf(6, 7))
    private val data = PriceOverview(listOf(row), PaidEncounter("2026-11-23T12:00:00.000Z", 42))

    @Test fun priceDraftRoundTripAndStablePositionIdentity() {
        val draft = PriceListDraft.from(row, zone)
        assertEquals(values, draft.values(row))
        val changed = draft.removeItem(0).addItem()
            .changeItem(0) { it.copy(amount = "999") }
            .changeItem(2) { it.copy(amount = "0", label = "  New  ") }
            .copy(dateText = "25.11.2026", listingId = 4)
        assertEquals(PriceListValues(4, "2026-11-25T00:00:00.000Z",
            listOf(PriceItem("Fixture", 30, 1234), PriceItem("New", null, 0))), changed.values(row))
    }

    @Test fun invalidDateAmountsAndDurationsAreNotWriteValues() {
        val initial = PriceListDraft.from(null, zone, LocalDate.of(2026, 11, 24))
        val draft = initial.changeItem(0) { it.copy(amount = "0") }
        assertEquals(0L, draft.values(null).items.single().amountCents)
        for (invalid in listOf(
            initial, draft.copy(dateText = "30.02.2026"), draft.copy(dateText = "01.01.0000"),
            draft.removeItem(0), draft.changeItem(0) { it.copy(amount = "-1") },
            draft.changeItem(0) { it.copy(amount = "1,234") },
            draft.changeItem(0) { it.copy(amount = "99999999999999999999999999") },
            draft.changeItem(0) { it.copy(duration = "0") },
        )) assertTrue(runCatching { invalid.values(null) }.isFailure)
    }

    @Test(timeout = 15000) fun encounterCommitRevokesPriceOverviewEvenWithoutTokenOrBusyChange() = runBlocking {
        val detail = DetailIdentityToken(1, 4, 1, 11, 1, Preference.NONE, false, emptyList())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val updated = data.copy(lastPaid = PaidEncounter("2026-11-24T12:00:00.000Z", 0))
        var reads = 0
        val editor = PriceEditor({ it == detail }, { _, _ ->
            when (++reads) {
                1 -> data
                2 -> {
                    started.complete(Unit)
                    release.awaitSignal()
                    data
                }
                else -> updated
            }
        }, { _, _ -> error("No price write") })
        editor.load(detail)
        val rendered = checkNotNull(editor.panel)
        assertEquals(data, rendered.snapshot)
        val old = launch { editor.load(detail) }
        started.awaitSignal()
        editor.invalidateAfterCommit(detail.ownerId)
        assertNull(editor.panel)
        assertEquals(1L, editor.readRevision)
        editor.begin(rendered, row)
        assertNull(editor.edit)
        editor.load(detail)
        release.complete(Unit)
        old.join()
        assertEquals(updated, editor.panel?.snapshot)
        assertEquals(3, reads)
    }

    @Test(timeout = 15000) fun sharedAdmissionAndCommitReadFailure() = runBlocking {
        var current: ProfileIdentityToken = token
        var blocked = true
        var failRead = false
        val writes = mutableListOf<Pair<PriceEdit, PriceListValues?>>()
        val editor = PriceEditor({ it == current }, { _, _ ->
            if (failRead) error("read failed")
            data
        }, { edit, v -> writes += edit to v }, { !blocked })
        editor.load(token)
        val panel = checkNotNull(editor.panel)
        editor.begin(panel, row)
        assertNull(editor.edit)
        blocked = false
        editor.begin(panel, row)
        val initial = checkNotNull(editor.edit)
        editor.change(initial) { it.changeItem(0) { item -> item.copy(amount = "12,34") } }
        val changed = checkNotNull(editor.edit)
        current = token.copy(session = 2, ownerId = 2)
        editor.detach(token)
        editor.load(current)
        editor.save(changed)
        assertTrue(writes.isEmpty())
        assertFalse(editor.canResume(changed))
        current = token.copy(session = 3)
        editor.load(current)
        editor.resume(changed)
        val resumed = checkNotNull(editor.edit)
        assertEquals(row, resumed.expected)
        editor.save(resumed)
        editor.save(resumed)
        assertEquals(1, writes.size)
        assertEquals(1234L, writes.single().second?.items?.first()?.amountCents)
        editor.committed(writes.single().first)
        failRead = true
        editor.load(current)
        assertTrue(checkNotNull(editor.panel?.error).startsWith("Änderung gespeichert"))
        failRead = false
        editor.retry(checkNotNull(editor.panel))
        assertEquals(data, editor.panel?.snapshot)
        assertEquals(1, writes.size)
    }
}
