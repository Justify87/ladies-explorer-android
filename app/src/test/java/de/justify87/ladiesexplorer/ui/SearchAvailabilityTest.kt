package de.justify87.ladiesexplorer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchAvailabilityTest {
    @Test fun quickModesPreserveOtherTimesAndGroups() {
        val filters = mapOf("zeiten" to setOf("heute-offen", "vor 10 Uhr"), "figur" to setOf("schlank"))
        val now = withAvailability(filters, AvailabilityMode.Now)
        assertEquals(AvailabilityMode.Now, availabilityMode(now))
        assertEquals(setOf("jetzt geöffnet", "vor 10 Uhr"), now["zeiten"])
        assertEquals(filters["figur"], now["figur"])
        assertEquals(setOf("vor 10 Uhr"), withAvailability(now, AvailabilityMode.All)["zeiten"])
    }

    @Test fun nowWinsAmbiguousLegacySelectionAndClearingRemovesOnlyQuickTimes() {
        val filters = mapOf("zeiten" to setOf("jetzt-offen", "heute geöffnet"))
        assertEquals(AvailabilityMode.Now, availabilityMode(filters))
        assertEquals(emptyMap<String, Set<String>>(), withAvailability(filters, AvailabilityMode.All))
    }
}
