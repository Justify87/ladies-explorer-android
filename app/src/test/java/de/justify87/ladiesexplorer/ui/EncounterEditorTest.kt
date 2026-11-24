package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class EncounterEditorTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val token = SwipeActionToken(null, 1, 4, 1, 11, 1, Preference.NONE, false, emptyList())
    private val values = EncounterValues(null, "2026-11-24T12:00:00.123Z", null, null, null, null, null, null, emptyList())
    private val row = Encounter(5, 1, values, values.occurredAt, values.occurredAt)

    @Test fun draftPreservesUnknownZeroAndOriginalTimestamp() {
        val original = EncounterDraft.from(row, zone)
        assertEquals(values, original.values(row, zone))
        val edited = original.copy(amount = "0", duration = "15", overall = "5", sympathy = "1",
            note = "privat", listingId = 4).values(row, zone)
        assertEquals(values.occurredAt, edited.occurredAt)
        assertEquals(0L, edited.totalPriceCents)
        assertEquals(15L, edited.durationMinutes)
        assertEquals(5L, edited.overallRating)
        assertEquals(1L, edited.sympathyRating)
        assertEquals(4L, edited.listingId)
        assertEquals("privat", edited.note)
        assertEquals(1234L, original.copy(amount = "12,34").values(row, zone).totalPriceCents)
        assertEquals("2026-11-24T12:01:00.000Z", original.copy(whenText = "24.11.2026 13:01").values(row, zone).occurredAt)
    }

    @Test fun serviceDraftPreservesUnknownZeroAndIndependentTotal() {
        val draft = EncounterDraft.from(row, zone).copy(baseAmount = "0").addService().addService()
            .changeService(0) { it.copy(name = "  Fixture A  ") }
            .changeService(1) { it.copy(name = "Fixture B", amount = "12,34") }
        val v = draft.values(row, zone)
        assertEquals(0L, v.basePriceCents)
        assertNull(v.totalPriceCents)
        assertEquals(listOf(EncounterService("Fixture A", null), EncounterService("Fixture B", 1234)), v.services)
        assertEquals(v, EncounterDraft.from(row.copy(values = v), zone).values(row.copy(values = v), zone))
        val changed = draft.removeService(0).addService()
            .changeService(0) { it.copy(name = "stale callback") }
            .changeService(2) { it.copy(name = "Fixture C", amount = "0") }
        assertEquals(listOf(EncounterService("Fixture B", 1234), EncounterService("Fixture C", 0)), changed.values(row, zone).services)
        assertTrue(changed.removeService(1).removeService(2).values(row, zone).services.isEmpty())
    }

    @Test fun invalidAndAmbiguousInputsNeverBecomeWriteValues() {
        val draft = EncounterDraft("24.11.2026 13:00")
        for (invalid in listOf(
            draft.copy(duration = "0"), draft.copy(amount = "-1"), draft.copy(amount = "1,234"),
            draft.copy(amount = "99999999999999999999999"), draft.copy(overall = "6"),
            draft.copy(baseAmount = "-1"), draft.addService(),
            draft.addService().changeService(0) { it.copy(name = "Fixture", amount = "-1") },
            draft.addService().changeService(0) { it.copy(name = "Fixture", amount = "1,234") },
            draft.copy(sympathy = "0"), draft.copy(whenText = "30.02.2026 13:00"),
            draft.copy(whenText = "29.03.2026 02:30"), draft.copy(whenText = "25.10.2026 02:30"),
        )) {
            assertTrue("Invalid draft accepted: $invalid", runCatching { invalid.values(null, zone) }.isFailure)
        }
    }

    @Test(timeout = 15000) fun staleReadAndForeignPanelCannotOpenAnEditor() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val other = token.copy(session = 2, listingId = 6, ownerId = 2)
        var current: ProfileIdentityToken = token
        val editor = EncounterEditor({ it == current }, { _, owner ->
            if (owner == 1L) {
                oldStarted.complete(Unit)
                release.awaitSignal()
                listOf(row)
            } else emptyList()
        }, { _, _ -> error("No write admitted") })
        val old = launch { editor.load(token) }
        oldStarted.awaitSignal()
        val stale = checkNotNull(editor.panel)
        current = other
        editor.detach(token)
        editor.load(other)
        release.complete(Unit)
        old.join()
        assertEquals(other, editor.panel?.token)
        assertEquals(emptyList<Encounter>(), editor.panel?.snapshot)
        editor.begin(stale)
        editor.begin(checkNotNull(editor.panel), row)
        assertNull(editor.edit)
    }

    @Test(timeout = 15000) fun lifecycleRequiresExplicitSameOwnerResumeAndDiscard() = runBlocking {
        var current: ProfileIdentityToken = token
        val editor = EncounterEditor({ it == current }, { _, _ -> listOf(row) }, { _, _ -> error("No write") })
        editor.load(token)
        editor.begin(checkNotNull(editor.panel), row)
        editor.change(checkNotNull(editor.edit)) {
            it.copy(note = "retained").addService().changeService(0) { line -> line.copy(name = "Fixture") }
        }
        val draft = checkNotNull(editor.edit)
        val foreign = token.copy(session = 2, ownerId = 2)
        current = foreign
        editor.detach(token)
        editor.load(foreign)
        assertFalse(editor.canResume(draft))
        editor.save(draft)
        assertSame(draft, editor.edit)
        current = token.copy(session = 3)
        editor.load(current)
        assertTrue(editor.canResume(draft))
        editor.resume(draft)
        val resumed = checkNotNull(editor.edit)
        assertEquals(current, resumed.token)
        assertEquals(row, resumed.expected)
        assertEquals("retained", resumed.draft.note)
        assertEquals("Fixture", resumed.draft.services.single().name)
        editor.requestDismiss(resumed)
        val confirmation = checkNotNull(editor.edit)
        editor.keep(confirmation)
        assertEquals("retained", editor.edit?.draft?.note)
        editor.requestDismiss(checkNotNull(editor.edit))
        editor.discard(checkNotNull(editor.edit))
        assertNull(editor.edit)
    }

    @Test(timeout = 15000) fun writeFailureKeepsDraftAndCommittedReadFailureNeverReplaysWrite() = runBlocking {
        val writes = mutableListOf<Pair<EncounterEdit, EncounterValues?>>()
        var failRead = false
        val detail = DetailIdentityToken(1, 4, 1, 11, 1, Preference.NONE, false, emptyList())
        val editor = EncounterEditor({ it == detail }, { _, _ ->
            if (failRead) error("read failed")
            listOf(row)
        }, { edit, v -> writes += edit to v })
        editor.load(detail)
        editor.begin(checkNotNull(editor.panel), row)
        editor.change(checkNotNull(editor.edit)) { it.copy(note = "changed") }
        val rendered = checkNotNull(editor.edit)
        editor.save(rendered)
        editor.save(rendered)
        editor.requestDismiss(checkNotNull(editor.edit))
        assertEquals(1, writes.size)
        assertTrue(checkNotNull(editor.edit).writing)
        editor.failed(writes.single().first, "conflict")
        assertEquals("changed", editor.edit?.draft?.note)
        assertEquals("conflict", editor.edit?.error)
        editor.save(checkNotNull(editor.edit))
        assertEquals(2, writes.size)
        editor.committed(writes.last().first)
        assertNull(editor.edit)
        assertNull(editor.panel)
        assertEquals(1L, editor.readRevision)
        failRead = true
        editor.load(detail)
        assertTrue(checkNotNull(editor.panel?.error).startsWith("Änderung gespeichert"))
        failRead = false
        editor.retry(checkNotNull(editor.panel))
        assertEquals(listOf(row), editor.panel?.snapshot)
        assertEquals(2, writes.size)
    }

    @Test(timeout = 15000) fun deleteNeedsExplicitConfirmationAndOnlySubmitsOnce() = runBlocking {
        val writes = mutableListOf<EncounterEdit>()
        val editor = EncounterEditor({ it == token }, { _, _ -> listOf(row) }, { edit, v ->
            assertNull(v)
            writes += edit
        })
        editor.load(token)
        editor.begin(checkNotNull(editor.panel), row, deleting = true)
        editor.requestDismiss(checkNotNull(editor.edit))
        assertNull(editor.edit)
        assertTrue(writes.isEmpty())
        editor.begin(checkNotNull(editor.panel), row, deleting = true)
        val rendered = checkNotNull(editor.edit)
        editor.save(rendered)
        editor.save(rendered)
        assertEquals(1, writes.size)
        assertEquals(row, writes.single().expected)
        assertTrue(writes.single().deleting)
    }
}
