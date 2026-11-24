package de.justify87.ladiesexplorer.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileNoteStateTest {
    @Test fun collapsedNoteShowsConflictBeforeDraft() {
        val dirty = NoteDraft.from("original").copy(text = "mine")
        assertEquals("Ungespeicherter Entwurf", noteSectionHint(NoteEditorState(draft = dirty)))
        assertEquals("Notizkonflikt", noteSectionHint(NoteEditorState(draft = dirty.reconcile("external"))))
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

    @Test fun saveAndDiscardRejectUnseenDraftChangesAndKeepRequiresCurrentConflict() {
        val rendered = NoteDraft.from("old").edit("mine")
        val ready = NoteEditorState(draft = rendered)
        assertTrue(ready.canEdit())
        assertTrue(ready.canSave(rendered))
        assertTrue(ready.canDiscard(rendered))
        assertFalse(ready.canKeep(rendered))
        val typed = ready.copy(draft = rendered.edit("newer"))
        assertFalse(typed.canSave(rendered))
        assertFalse(typed.canDiscard(rendered))
        assertTrue(typed.canSave(typed.draft!!))
        val conflict = ready.loaded("external")
        assertFalse(conflict.canDiscard(rendered))
        assertFalse(conflict.canSave(rendered))
        val seenConflict = conflict.draft!!
        assertTrue(conflict.canKeep(seenConflict))
        assertTrue(conflict.canDiscard(seenConflict))
        assertFalse(conflict.canSave(seenConflict))
        val changedConflict = conflict.loaded("new external")
        assertFalse(changedConflict.canKeep(seenConflict))
        assertFalse(changedConflict.canDiscard(seenConflict))
        assertTrue(changedConflict.canKeep(changedConflict.draft!!))
        val loading = conflict.copy(loading = true)
        assertFalse(loading.canEdit())
        assertFalse(loading.canDiscard(seenConflict))
        assertFalse(loading.canKeep(seenConflict))
        val failed = conflict.copy(readError = true)
        assertFalse(failed.canEdit())
        assertFalse(failed.canKeep(seenConflict))
        assertFalse(ready.copy(readError = true).canSave(rendered))
        assertTrue(failed.canDiscard(seenConflict))
        assertFalse(NoteEditorState().canEdit())
        assertFalse(NoteEditorState().canDiscard(rendered))
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

    @Test(timeout = 15_000) fun navigationWhileWriteAwaitsDoesNotRedirectCommittedNote() = runBlocking {
        var notes = emptyMap<Long, NoteEditorState>()
            .updateNote(1L) { it.loaded("old") }
            .updateNote(1L) { it.copy(draft = it.draft!!.edit("saved")) }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            val outcome = writeAndProject(
                write = { started.complete(Unit); finish.awaitSignal() },
                afterWrite = { notes = notes.updateNote(1L) { it.committed("saved") } },
                project = {},
            )
            assertEquals(DiscoveryMutationOutcome.Success, outcome)
        }
        started.awaitSignal()
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

    @Test(timeout = 15_000) fun noteCallbacksCannotRetargetAfterRenderedContextChanges() = runBlocking {
        val state = DiscoverUiState(cards = listOf(fixtureCard(4L, 1L)), loading = false)
        val token = requireNotNull(state.swipeToken(fixtureSwipe()))
        var active: ProfileIdentityToken? = token
        val calls = mutableListOf<String>()
        val raw = NoteEditorActions(
            load = { listing, owner -> calls += "load:$listing:$owner" },
            edit = { owner, _ -> calls += "edit:$owner" },
            save = { listing, owner, draft -> calls += "save:$listing:$owner:${draft.text}" },
            useStored = { owner, draft -> calls += "discard:$owner:${draft.text}" },
            keepDraft = { owner, draft -> calls += "keep:$owner:${draft.text}" },
        )
        val bound = raw.boundTo(token) { it == active }
        bound.load(4L, 1L)
        bound.edit(1L, "draft")
        val draft = NoteDraft.from("old").edit("rendered")
        bound.save(99L, 1L, draft)
        bound.edit(2L, "wrong owner")
        bound.save(4L, 1L, draft)
        bound.useStored(1L, draft)
        bound.keepDraft(1L, draft)
        active = token.copy(projectionRevision = token.projectionRevision + 1)
        bound.save(4L, 1L, draft)
        bound.useStored(1L, draft)
        bound.keepDraft(1L, draft)
        assertEquals(listOf("load:4:1", "edit:1", "save:4:1:rendered",
            "discard:1:rendered", "keep:1:rendered"), calls)
    }

    @Test(timeout = 15_000) fun openedNoteCoalescesDemandAndRetryDoesNotDependOnLazyItem() = runBlocking {
        val state = DiscoverUiState(cards = listOf(fixtureCard(4L, 1L)), loading = false)
        val token = requireNotNull(state.swipeToken(fixtureSwipe()))
        var current = true
        var reads = 0
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val demand = ProfileNoteDemand(this)
        val load: suspend (Long, Long) -> Unit = { listing, owner ->
            assertEquals(4L, listing)
            assertEquals(1L, owner)
            reads++
            if (reads == 2) { started.complete(Unit); finish.awaitSignal() }
        }
        demand.request(token, false, { current }, load)
        demand.request(token, false, { current }, load)
        assertEquals(1, reads)
        demand.request(token, true, { current }, load)
        started.awaitSignal()
        demand.request(token, false, { current }, load)
        demand.request(token, true, { current }, load)
        assertEquals(ProfileNoteDemand.Status.Loading, demand.status)
        finish.complete(Unit)
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(ProfileNoteDemand.Status.Settled, demand.status)
        current = false
        demand.request(token, true, { current }, load)
        assertEquals(2, reads)
        demand.close()
    }
}
