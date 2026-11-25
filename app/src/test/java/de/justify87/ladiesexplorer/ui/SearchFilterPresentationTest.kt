package de.justify87.ladiesexplorer.ui

import org.junit.Assert.*
import org.junit.Test

class SearchFilterPresentationTest {
    @Test fun parentAndPartialValueMatchesExcludeUnrelatedOptions() {
        val options = listOf("Schwarz", "Braun", "Blond")
        val parents = listOf("Eigenschaften", "Haarfarbe")
        fun visible(query: String) = matchingFilterOptions(query, parents, options) { it }
        assertEquals(options, visible("  haarF  "))
        assertEquals(options, visible("Eigenschaften"))
        assertEquals(listOf("Braun"), visible("rau"))
        assertEquals(emptyList<String>(), visible("Treffpunkt"))
        assertEquals(options, visible(""))
    }

    @Test fun hiddenSelectionIsNotMutatedAndNormalExpansionReturns() {
        val draft = SearchDraft.from(SearchUiState()).toggle("haarfarbe", "Braun")
        val options = listOf("Schwarz", "Braun")
        val visible = matchingFilterOptions("schwarz", listOf("Haarfarbe"), options) { it }
        assertEquals(listOf("Schwarz"), visible)
        assertTrue("Braun" in draft.selectedFilters["haarfarbe"].orEmpty())
        val flipped = listOf("catalog/haarfarbe", "location-input")
        assertTrue(filterSectionExpanded("catalog/haarfarbe", false, flipped, ""))
        assertFalse(filterSectionExpanded("location-input", true, flipped, ""))
        assertTrue(filterSectionExpanded("location-input", true, flipped, "Ort"))
        assertTrue(filterSectionExpanded("category", false, flipped, "Rubrik"))
        assertFalse(filterSectionExpanded("category", false, flipped, ""))
        assertFalse(filterSectionExpanded("location-input", true, flipped, ""))
        assertTrue(filterSectionExpanded("catalog/haarfarbe", false, flipped, ""))
    }

    @Test fun fixedOptionsAreFilteredByGroupOrDisplayedValue() {
        val modes = AvailabilityMode.entries
        assertEquals(listOf(AvailabilityMode.Now), matchingFilterOptions("jet", listOf("Verfügbarkeit"), modes) { it.label })
        assertEquals(modes, matchingFilterOptions("verfüg", listOf("Verfügbarkeit"), modes) { it.label })
        assertEquals(listOf(20u), matchingFilterOptions("20 km", listOf("Umkreis"), listOf(5u, 20u, 50u)) { "$it km" })
        assertTrue(matchingFilterOptions("Rubrik", listOf("Seitenlimit"), listOf(1u, 5u)) { it.toString() }.isEmpty())
    }
}
