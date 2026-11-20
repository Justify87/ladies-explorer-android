package de.justify87.ladiesexplorer.ui

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class GalleryVideoTest {
    @Test fun onlyOrdinaryLocalContainersAreAccepted() {
        assertEquals("mov", videoDemuxer(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)))
        assertEquals("matroska", videoDemuxer(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertNull(videoDemuxer("#EXTM3U".toByteArray()))
        assertNull(videoDemuxer("https://example.invalid/video".toByteArray()))
        assertNull(videoDemuxer(byteArrayOf()))
    }

    @Test fun flightEndsOutsideTheMeasuredHeroInBothDirections() {
        val flight = SwipeFlight(4L to 1L, 5, 1f, 125f, IntSize(800, 1200), 0, null, null)
        assertTrue(flight.destination > 800f)
        assertTrue(flight.copy(direction = -1f, offset = -125f).destination < -800f)
    }
}
