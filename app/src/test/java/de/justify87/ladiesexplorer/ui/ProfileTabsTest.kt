package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.EvidenceStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ProfileTabsTest {
    @Test fun routeAndPersonSelectionsDoNotLeak() {
        val swipe = ProfileTabMemory()
        val detail = ProfileTabMemory()
        swipe.select(10L, ProfileTab.History)
        swipe.select(11L, ProfileTab.Services)
        detail.select(10L, ProfileTab.Notes)
        assertEquals(ProfileTab.Info, swipe.selected(99L))
        assertEquals(ProfileTab.History, swipe.selected(10L))
        assertEquals(ProfileTab.Services, swipe.selected(11L))
        assertEquals(ProfileTab.Notes, detail.selected(10L))
        assertEquals(ProfileTab.History, ProfileTabMemory(swipe.entries).selected(10L))
    }

    @Test fun dirtyDraftSurvivesRefreshUntilExplicitConflictResolution() {
        val first = NoteDraft.from("original").copy(text = "mine")
        val changed = first.reconcile("external")
        assertEquals("original", changed.expected)
        assertEquals("mine", changed.text)
        assertEquals(true, changed.conflict)
        assertEquals("external", changed.useStored().text)
        assertEquals(false, changed.keepDraft().conflict)
        assertEquals("mine", changed.keepDraft().text)
        assertEquals(false, changed.reconcile("mine").dirty)
    }

    @Test fun cleanDraftFollowsPersistedValueAndEmptyTextIsARealEdit() {
        val original = NoteEditorState().loaded("original")
        assertEquals("external", original.loaded("external").draft?.text)
        val cleared = original.draft!!.copy(text = "")
        assertEquals(true, cleared.dirty)
        assertEquals(false, cleared.reconcile(null).dirty)
    }

    @Test fun switchingProfilesDoesNotReplaceOtherDraftsAndMergeChecksDirtyOwners() {
        val original = NoteEditorState().loaded("old")
        val drafts = mapOf(1L to original.copy(draft = original.draft!!.copy(text = "unsaved")),
            2L to NoteEditorState().loaded("other"))
        val reopened = drafts.getValue(1L).loaded("old")
        assertEquals("unsaved", reopened.draft?.text)
        assertEquals(true, reopened.draft?.dirty)
        assertEquals(false, drafts.getValue(2L).draft?.dirty)
        assertEquals(listOf(1L), drafts.dirtyOwners(1L, 2L))
    }

    @Test fun navigationWhileWriteAwaitsDoesNotRedirectCommittedNote() = runBlocking {
        var notes = emptyMap<Long, NoteEditorState>()
            .updateNote(1L) { it.loaded("old") }
            .updateNote(1L) { it.copy(draft = it.draft!!.edit("saved")) }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            val outcome = writeAndProject(
                write = { started.complete(Unit); finish.await() },
                afterWrite = { notes = notes.updateNote(1L) { it.committed("saved") } },
                project = {},
            )
            assertEquals(DiscoveryMutationOutcome.Success, outcome)
        }
        started.await()
        notes = notes.updateNote(2L) { it.loaded("other") }
        finish.complete(Unit)
        job.join()
        assertEquals("saved", notes.getValue(1L).draft?.text)
        assertEquals(false, notes.getValue(1L).draft?.dirty)
        assertEquals("other", notes.getValue(2L).draft?.text)
    }

    @Test fun failedWriteMessageSurvivesSuccessfulReadAndBlankCommitClearsDraft() {
        val failed = NoteEditorState().loaded("a").copy(error = "write failed")
        assertEquals("write failed", failed.loaded("a").error)
        val dirty = failed.copy(draft = failed.draft!!.edit(" "))
        assertEquals(false, dirty.committed(null).draft?.dirty)
        assertEquals(null, dirty.committed(null).error)
    }

    @Test fun historyLabelsDoNotExposeStorageKeys() {
        assertEquals("Merkmale geändert", historyEventLabel("FIELD_CHANGED", "traits_json"))
        assertEquals("Anzeige entdeckt", historyEventLabel("PROFILE_CREATED", null))
        assertEquals("Medien geändert", historyEventLabel("MEDIA_CHANGED", "media"))
        assertEquals("Weitere Angabe", historyFieldLabel("unknown_internal_key"))
    }

    @Test fun missingEvidenceIsNotNegativeEvidence() {
        assertEquals("Ja", evidenceLabel(EvidenceStatus.YES))
        assertEquals("Nein", evidenceLabel(EvidenceStatus.NO))
        assertEquals("Unbekannt", evidenceLabel(EvidenceStatus.UNKNOWN))
    }

    @Test fun bothMergeOwnersWithDirtyDraftsRemainIndependentAfterConflictingRead() {
        val notes = mapOf(
            1L to NoteEditorState().loaded("a"),
            2L to NoteEditorState().loaded("b"),
        ).updateNote(1L) { it.copy(draft = it.draft!!.edit("draft a")) }
            .updateNote(2L) { it.copy(draft = it.draft!!.edit("draft b")) }
            .updateNote(1L) { it.loaded("external a") }
        assertEquals(listOf(1L, 2L), notes.dirtyOwners(1L, 2L, 1L))
        assertEquals(true, notes.getValue(1L).draft?.conflict)
        assertEquals("draft a", notes.getValue(1L).draft?.text)
        assertEquals("draft b", notes.getValue(2L).draft?.text)
        val resolved = notes.updateNote(1L) { it.copy(draft = it.draft!!.useStored()) }
        assertEquals(listOf(2L), resolved.dirtyOwners(1L, 2L))
    }
}
