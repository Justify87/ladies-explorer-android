package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.MapProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPresentationTest {
    private val a = MapProfile(1L, 1L, "Fixture A", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", true, null)
    private val b = a.copy(listingId = 2L, personId = 2L, name = "Fixture B", favorite = false)
    private val c = a.copy(listingId = 3L, personId = 3L, longitude = 12.38)

    @Test fun locationPrecisionIsExplicitlyApproximate() {
        assertEquals("Stadtbereich · Leipzig", mapLocationLabel(a))
        assertEquals("PLZ-Bereich · 04229 Leipzig",
            mapLocationLabel(a.copy(precision = "POSTAL", locationLabel = "04229 Leipzig")))
    }

    @Test fun selectionGroupsOnlyIdenticalCoordinatesInCurrentProjection() {
        assertEquals(listOf(a, b), mapSelection(listOf(a, b, c), 2L))
        assertEquals(listOf(c), mapSelection(listOf(a, b, c), 3L))
        assertEquals(listOf(a), mapSelection(listOf(a, c), 1L))
        assertTrue(mapSelection(listOf(a, c), 2L).isEmpty())
        assertTrue(mapSelection(listOf(a, b, c), null).isEmpty())
        assertTrue(mapSelection(emptyList(), 1L).isEmpty())
    }
}
