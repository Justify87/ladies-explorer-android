package de.justify87.ladiesexplorer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileComponentsTest {
    @Test fun mediaIndexIsSafeForEmptyAndShrinkingGallery() {
        assertEquals(0, mediaIndex(4, 0))
        assertEquals(0, mediaIndex(-1, 1))
        assertEquals(2, mediaIndex(8, 3))
        assertEquals(1, mediaIndex(1, 3))
    }

    @Test fun localMediaPrecedesRemoteFallbackAndMissingMediaIsEmpty() {
        assertEquals(listOf(File("/fixture/image.jpg"), "https://example.invalid/image.jpg"),
            mediaModels("/fixture/image.jpg", "https://example.invalid/image.jpg"))
        assertEquals(listOf("https://example.invalid/image.jpg"),
            mediaModels(null, "https://example.invalid/image.jpg"))
        assertEquals(emptyList<Any>(), mediaModels(null, " "))
    }

    @Test fun unknownStatusNeverBecomesActive() {
        assertEquals("Status unbekannt", statusLabel(null))
        assertEquals("Status unbekannt", statusLabel("TIMEOUT"))
        assertEquals("Anzeige aktiv", statusLabel("ACTIVE"))
        assertEquals("Anzeige nicht verfügbar", statusLabel("UNAVAILABLE"))
    }

    @Test fun externalContactNormalizesInternationalPrefixesAndRejectsShortNumbers() {
        assertEquals("491234567890", phoneForWhatsApp("0123 4567890"))
        assertEquals("441234567890", phoneForWhatsApp("0044 1234567890"))
        assertEquals("491234567890", phoneForWhatsApp("+49 1234567890"))
        assertNull(phoneForWhatsApp("123"))
        assertNull(phoneForWhatsApp(""))
    }
}
