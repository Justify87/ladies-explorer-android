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

    @Test fun secondFingerOutsideHeroRevokesExistingAndNewGesturesUntilAllSeparate() {
        val fence = PhotoPointerFence()
        fence.observe(1)
        val original = fence.capture()
        assertTrue(fence.current(original))
        fence.observe(2)
        assertFalse(fence.current(original))
        assertNull(fence.capture())
        fence.observe(1)
        assertFalse(fence.current(original))
        val next = fence.capture()
        assertTrue(fence.current(next))
        fence.invalidate()
        assertFalse(fence.current(next))
    }
}
