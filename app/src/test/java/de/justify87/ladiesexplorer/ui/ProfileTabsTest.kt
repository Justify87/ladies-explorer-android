package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.EvidenceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

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

    @Test fun missingEvidenceIsNotNegativeEvidence() {
        assertEquals("Ja", evidenceLabel(EvidenceStatus.YES))
        assertEquals("Nein", evidenceLabel(EvidenceStatus.NO))
        assertEquals("Unbekannt", evidenceLabel(EvidenceStatus.UNKNOWN))
    }
}
