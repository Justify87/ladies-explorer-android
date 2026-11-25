package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.MapProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.maplibre.geojson.Point
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

    @Test fun selectedListingCannotFollowAChangedOwner() {
        assertEquals(a, mapSelectedProfile(listOf(a, b), a.listingId, a.personId))
        assertNull(mapSelectedProfile(listOf(a.copy(personId = 99)), a.listingId, a.personId))
        assertNull(mapSelectedProfile(listOf(b), a.listingId, a.personId))
    }

    @Test fun featureProjectionPreservesCoordinatesPrecisionAndSamePlaceMembers() {
        val postal = c.copy(precision = "POSTAL")
        val features = requireNotNull(asFeatures(listOf(a, b, postal), a.listingId).features())
        assertEquals(listOf(1L, 2L, 3L), features.map { it.getNumberProperty("listing_id").toLong() })
        assertEquals(listOf("CITY", "CITY", "POSTAL"), features.map { it.getStringProperty("precision") })
        assertEquals(listOf(true, true, false), features.map { it.getBooleanProperty("selected") })
        val point = features.first().geometry() as Point
        assertEquals(a.longitude, point.longitude(), 0.0)
        assertEquals(a.latitude, point.latitude(), 0.0)
        val filtered = requireNotNull(asFeatures(listOf(postal), a.listingId).features())
        assertEquals(3L, filtered.single().getNumberProperty("listing_id").toLong())
        assertEquals(false, filtered.single().getBooleanProperty("selected"))
        assertTrue(requireNotNull(asFeatures(emptyList(), null).features()).isEmpty())
    }
}
