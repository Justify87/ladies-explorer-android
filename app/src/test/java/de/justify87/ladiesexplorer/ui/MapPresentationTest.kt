package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.MapProfile
import de.justify87.ladiesexplorer.core.MapOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test fun initialCameraFitWaitsForResolvedOrStoppedProjection() {
        val partial = MapOverview(listOf(a), 3u, 2u, 2u)
        val complete = MapOverview(listOf(a, b, c), 3u, 0u, 0u)
        assertFalse(mapInitialFitReady(null, true))
        assertFalse(mapInitialFitReady(partial.copy(profiles = emptyList()), false))
        assertFalse(mapInitialFitReady(partial, true))
        assertTrue(mapInitialFitReady(partial, false))
        assertTrue(mapInitialFitReady(complete, true))
    }

    @Test fun featureProjectionPreservesCoordinatesPrecisionAndSamePlaceMembers() {
        val postal = c.copy(precision = "POSTAL")
        val features = requireNotNull(asFeatures(listOf(a, b, postal), a.listingId).features())
        assertEquals(listOf(1L, 2L, 3L), features.map { it.getNumberProperty("listing_id").toLong() })
        assertEquals(listOf(1L, 2L, 3L), features.map { it.getNumberProperty("owner_id").toLong() })
        assertEquals(listOf("CITY", "CITY", "POSTAL"), features.map { it.getStringProperty("precision") })
        assertEquals(listOf(true, true, false), features.map { it.getBooleanProperty("selected") })
        assertEquals(listOf(true, false, true), features.map { it.getBooleanProperty("representative") })
        assertEquals(listOf(2, 2, 1), features.map { it.getNumberProperty("group_count").toInt() })
        assertEquals(listOf("CITY", "CITY", "POSTAL"), features.map { it.getStringProperty("group_precision") })
        val point = features.first().geometry() as Point
        assertEquals(a.longitude, point.longitude(), 0.0)
        assertEquals(a.latitude, point.latitude(), 0.0)
        val filtered = requireNotNull(asFeatures(listOf(postal), a.listingId).features())
        assertEquals(3L, filtered.single().getNumberProperty("listing_id").toLong())
        assertEquals(false, filtered.single().getBooleanProperty("selected"))
        assertTrue(requireNotNull(asFeatures(emptyList(), null).features()).isEmpty())
    }

    @Test fun groupRepresentativeIsStableAndTapRejectsObsoleteOwnerOrLocation() {
        val mixed = b.copy(precision = "POSTAL")
        val points = listOf(mixed, a, c)
        val features = requireNotNull(asFeatures(points, mixed.listingId).features())
        val representative = features.single {
            it.getBooleanProperty("representative") && it.getNumberProperty("group_count").toInt() == 2
        }
        assertEquals(a.listingId, representative.getNumberProperty("listing_id").toLong())
        assertEquals("CITY", representative.getStringProperty("group_precision"))
        assertEquals(MAP_COUNT_PREFIX + "2-city", representative.getStringProperty("group_image"))
        assertEquals(a, mapRenderedSelection(points, representative))
        assertNull(mapRenderedSelection(listOf(a.copy(personId = 99), mixed, c), representative))
        assertNull(mapRenderedSelection(listOf(a.copy(latitude = 52.0), mixed, c), representative))
        assertNull(mapRenderedSelection(listOf(a, c), representative))
        assertNull(mapRenderedSelection(points, features.first()))
    }

    @Test fun thumbnailsAreScopedToListingOwnerAndRevisionAndViewportBudget() {
        val photo = a.copy(thumbnailUrl = "fixture-a")
        val id = mapThumbnailId(photo)
        assertEquals(MAP_FALLBACK_IMAGE, mapThumbnailId(a))
        assertEquals(id, mapThumbnailId(photo.copy(name = "Changed")))
        assertFalse(id == mapThumbnailId(photo.copy(personId = 99)))
        assertFalse(id == mapThumbnailId(photo.copy(thumbnailUrl = "fixture-b")))
        val grouped = requireNotNull(asFeatures(listOf(photo, b), null).features())
        assertTrue(grouped.all { it.getStringProperty("photo_id") == MAP_FALLBACK_IMAGE })
        val available = (1..100).map { "photo-$it" }.toSet()
        val rendered = available.toList() + available.toList() + "obsolete"
        val visible = mapVisibleThumbnailIds(rendered, available)
        assertEquals(MAP_THUMBNAIL_LIMIT, visible.size)
        assertEquals(visible, mapVisibleThumbnailIds(rendered.reversed(), available))
        assertTrue(visible.all { it in available })
        assertEquals(emptyList<String>(), mapVisibleThumbnailIds(rendered, emptySet()))
    }
}
