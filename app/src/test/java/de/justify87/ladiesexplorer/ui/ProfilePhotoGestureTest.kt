package de.justify87.ladiesexplorer.ui

import androidx.compose.ui.unit.IntSize
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

    private fun resolver(swipe: Boolean = true) = PhotoGestureResolver(8f, 400f, 72f, 500, swipe)

    @Test fun oneDownHasExclusiveTapOrHorizontalRelease() {
        val tap = resolver()
        assertEquals(PhotoGestureResult.Pending, tap.step(2f, 1f, true, 20, false))
        assertEquals(PhotoGestureResult.Tap, tap.step(2f, 1f, false, 80, false))
        val swipe = resolver()
        assertEquals(PhotoGestureResult.Drag(12f), swipe.step(12f, 1f, true, 25, false))
        assertEquals(PhotoGestureResult.Drag(110f), swipe.step(110f, 80f, true, 80, false))
        assertEquals(PhotoGestureResult.Release(Preference.LIKE), swipe.step(100f, 80f, false, 100, false))
    }

    @Test fun cancelAndReleaseSequencesNeverTurnIntoTapOrVelocityCommit() {
        assertEquals(PhotoGestureResult.Cancel, resolver().step(8f, 9f, true, 20, false))
        assertEquals(PhotoGestureResult.Cancel, resolver().step(1f, -15f, true, 20, false))
        assertEquals(PhotoGestureResult.Cancel, resolver().step(0f, 0f, false, 600, false))
        assertEquals(PhotoGestureResult.Cancel, resolver(false).step(120f, 0f, true, 20, false))
        assertEquals(PhotoGestureResult.Cancel, resolver().step(120f, 0f, false, 20, false))
        val reversed = resolver()
        assertEquals(PhotoGestureResult.Drag(110f), reversed.step(110f, 0f, true, 20, false))
        assertEquals(PhotoGestureResult.Release(null), reversed.step(4f, 0f, false, 40, false))
        val blocked = resolver()
        assertEquals(PhotoGestureResult.Drag(-120f), blocked.step(-120f, 0f, true, 20, false))
        assertEquals(PhotoGestureResult.Cancel, blocked.step(-120f, 0f, false, 50, true))
    }

    @Test fun multiTouchRevokesBothNativeAndPhotoUntilLastUp() {
        val gate = ProfileTouchGate()
        assertFalse(gate.observe(0, 1, true))
        val original = gate.capture()
        assertTrue(gate.current(original))
        assertTrue(gate.observe(1, 2, true))
        assertNull(gate.capture())
        assertFalse(gate.current(original))
        assertTrue(gate.observe(2, 1, true))
        assertTrue(gate.observe(1, 0, true))
        assertFalse(gate.active)
        assertFalse(gate.observe(0, 1, true))
        assertTrue(gate.current(gate.capture()))
        assertFalse(gate.current(original))
        assertFalse(gate.observe(1, 0, true))
    }

    @Test fun backFocusAndObserverReplacementCannotReadmitOldRelease() {
        val gate = ProfileTouchGate()
        gate.observe(0, 1, true)
        val original = gate.capture()
        gate.invalidate()
        assertTrue(gate.observe(1, 0, true))
        assertFalse(gate.current(original))
        assertFalse(gate.observe(0, 1, true))
        val resumed = gate.capture()
        assertTrue(gate.observe(1, 0, false))
        assertFalse(gate.current(resumed))
        assertTrue(gate.observe(1, 1, true)) // no fresh down
        assertTrue(gate.observe(1, 0, true))
        assertFalse(gate.observe(0, 1, true))
        val next = gate.capture()
        assertFalse(gate.observe(1, 0, true))
        assertTrue(gate.current(next)) // normal release stays admitted
        assertFalse(gate.observe(0, 1, true))
        assertFalse(gate.current(next)) // even an ordinary old stream is not a new one
        assertTrue(gate.current(gate.capture()))
    }

    @Test fun actualMeasuredGalleryAndRootOriginArePartOfTheDownGeometry() {
        val photo = androidx.compose.ui.geometry.Rect(0f, 48f, 400f, 300f)
        val left = androidx.compose.ui.geometry.Rect(0f, 48f, 96f, 300f)
        val geometry = PhotoHitGeometry(photo, androidx.compose.ui.geometry.Offset.Zero, listOf(left))
        assertTrue(geometry.galleryAt(androidx.compose.ui.geometry.Offset(40f, 100f)))
        assertFalse(geometry.galleryAt(androidx.compose.ui.geometry.Offset(200f, 100f)))
        assertNotEquals(geometry, geometry.copy(rootOrigin = androidx.compose.ui.geometry.Offset(0f, 1f)))
        assertNotEquals(geometry, geometry.copy(gallery = emptyList()))
    }

    @Test fun bookmarkExcludesOnlyItsActualRectangleFromGallery() {
        val photo = androidx.compose.ui.geometry.Rect(0f, 0f, 320f, 360f)
        val right = androidx.compose.ui.geometry.Rect(160f, 0f, 320f, 360f)
        val bookmark = androidx.compose.ui.geometry.Rect(266f, 126f, 314f, 174f)
        val geometry = PhotoHitGeometry(photo, androidx.compose.ui.geometry.Offset.Zero, listOf(right), bookmark)
        for ((x, y) in listOf(290f to 30f, 220f to 150f, 290f to 200f, 319f to 150f)) {
            assertTrue(geometry.galleryAt(androidx.compose.ui.geometry.Offset(x, y)))
        }
        assertFalse(geometry.galleryAt(androidx.compose.ui.geometry.Offset(290f, 150f)))
        assertFalse(geometry.galleryAt(androidx.compose.ui.geometry.Offset(100f, 150f)))
    }

    @Test fun flightEndsOutsideTheMeasuredHeroInBothDirections() {
        val flight = SwipeFlight(4L to 1L, 5, 1f, 125f, IntSize(800, 1200), 0, null)
        assertTrue(flight.destination > 800f)
        assertTrue(flight.copy(direction = -1f, offset = -125f).destination < -800f)
    }
}
