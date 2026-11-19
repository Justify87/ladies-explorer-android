package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.Preference
import org.junit.Assert.*
import org.junit.Test

class ProfilePhotoGestureTest {
    @Test fun slopAndDominanceUseBothAxes() {
        assertEquals(PhotoAxis.Pending, photoAxis(3f, 4f, 8f))
        assertEquals(PhotoAxis.Pending, photoAxis(8f, 0f, 8f))
        assertEquals(PhotoAxis.Horizontal, photoAxis(12f, 4f, 8f))
        assertEquals(PhotoAxis.Horizontal, photoAxis(-12f, -4f, 8f))
        assertEquals(PhotoAxis.Vertical, photoAxis(4f, -12f, 8f))
        assertEquals(PhotoAxis.Ambiguous, photoAxis(10f, 9f, 8f))
        assertEquals(PhotoAxis.Ambiguous, photoAxis(6f, 6f, 8f))
    }

    @Test fun thresholdUsesFullCardWidthAndMinimumNotVelocityOrZoneWidth() {
        assertNull(photoSwipePreference(71f, 240f, 72f))
        assertEquals(Preference.LIKE, photoSwipePreference(72f, 240f, 72f))
        assertNull(photoSwipePreference(-99f, 400f, 72f))
        assertEquals(Preference.DISLIKE, photoSwipePreference(-100f, 400f, 72f))
        assertNull(photoSwipePreference(120f, 600f, 72f))
        assertEquals(Preference.LIKE, photoSwipePreference(150f, 600f, 72f))
        assertNull(photoSwipePreference(0f, 400f, 72f))
        assertNull(photoSwipePreference(100f, 0f, 72f))
    }
}
