package de.justify87.ladiesexplorer.ui

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
}
