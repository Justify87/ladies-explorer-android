package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.EvidenceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSectionsTest {
    @Test fun sectionsExpandIndependentlyAndStayContextLocal() {
        val swipe = ProfileSections()
        val detail = ProfileSections()
        swipe.toggle(ProfileSection.History)
        swipe.toggle(ProfileSection.Notes)
        detail.toggle(ProfileSection.Analysis)
        assertEquals(true, swipe.expanded(ProfileSection.History))
        assertEquals(true, swipe.expanded(ProfileSection.Notes))
        assertEquals(false, swipe.expanded(ProfileSection.Analysis))
        assertEquals(false, detail.expanded(ProfileSection.Notes))
        assertEquals(true, detail.expanded(ProfileSection.Analysis))
        swipe.toggle(ProfileSection.Notes)
        assertEquals(false, swipe.expanded(ProfileSection.Notes))
        assertEquals(true, swipe.expanded(ProfileSection.History))
        swipe.setShowDescriptionChanges(true)
        assertEquals(false, detail.showDescriptionChanges)
        val restored = ProfileSections(swipe.entries)
        assertEquals(true, restored.expanded(ProfileSection.History))
        assertEquals(false, restored.expanded(ProfileSection.Notes))
        assertEquals(true, restored.showDescriptionChanges)
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
}
