package de.justify87.ladiesexplorer.ui

import org.junit.Assert.*
import org.junit.Test

class GalleryVideoTest {
    @Test fun nativeDiagnosticsRemoveSourcePathsAndUrls() {
        val path = "/data/user/0/synthetic/video.media"
        val message = mpvDiagnosticMessage("ffmpeg/demuxer",
            "decode failed $path with spaces\nhttps://example.invalid/private relative/name token=secret\u0000")
        assertFalse(message.contains(path))
        assertFalse(message.contains("example.invalid"))
        assertFalse(message.contains("/data/another"))
        assertFalse(message.contains('\n'))
        assertFalse(message.contains("secret"))
        assertFalse(message.contains("relative/name"))
        assertFalse(message.contains('\u0000'))
        assertEquals("native subsystem=ffmpeg reason=decode", message)
    }


    @Test fun onlyOrdinaryLocalContainersAreAccepted() {
        assertEquals("mov", videoDemuxer(byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112)))
        assertEquals("matroska", videoDemuxer(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertNull(videoDemuxer("#EXTM3U".toByteArray()))
        assertNull(videoDemuxer("https://example.invalid/video".toByteArray()))
        assertNull(videoDemuxer(byteArrayOf()))
    }
}
