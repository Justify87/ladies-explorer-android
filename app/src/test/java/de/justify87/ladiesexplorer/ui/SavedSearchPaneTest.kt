package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.SearchRequest
import org.junit.Assert.*
import org.junit.Test

class SavedSearchPaneTest {
    @Test fun panePriorityAndBackToOverview() {
        val filters = SearchUiState()
        assertEquals(SearchPane.Filters, filters.searchPane())
        val overview = filters.copy(savedOverviewOpen = true)
        assertEquals(SearchPane.SavedList, overview.searchPane())
        val editing = overview.copy(editingSavedSearchId = "one")
        assertEquals(SearchPane.SavedEditor, editing.searchPane())
        assertEquals(SearchPane.SavedList, editing.copy(editingSavedSearchId = null).searchPane())
        assertEquals(SearchPane.SavedResults, overview.beginSavedResults("one").searchPane())
    }
    @Test fun ordinaryDraftRestorationDoesNotRollBackCurrentScreenState() {
        val ordinary = SearchDraft(SearchRequest(
            city = " Berlin ", radiusKm = 30u, category = "", ageMin = 22u, ageMax = 52u,
            heightMin = 150u, heightMax = 190u, filtersJson = "{}", maxPages = 5u,
        ), defaultLocalVisibility())
        val edited = SearchUiState(city = "Leipzig", editingSavedSearchId = "saved",
            savedOverviewOpen = true, gridHiddenLikes = setOf(9L))
        val restored = ordinary.restore(edited)
        assertEquals(" Berlin ", restored.city)
        assertEquals(30u, restored.radiusKm)
        assertEquals(setOf(9L), restored.gridHiddenLikes)
        assertTrue(restored.savedOverviewOpen)
        assertEquals("saved", restored.editingSavedSearchId)
    }
}
