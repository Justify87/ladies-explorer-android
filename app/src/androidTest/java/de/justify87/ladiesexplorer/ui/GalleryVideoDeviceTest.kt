package de.justify87.ladiesexplorer.ui

import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Real local native player; fixture is a synthetic 64x64 blue H264 clip.
 * Repro: ffmpeg -f lavfi -i color=c=blue:s=64x64:r=4:d=1 -c:v libx264
 * -pix_fmt yuv420p -movflags +faststart -an fixture.mp4
 * Neither real persons nor source HTTP are involved. */
class GalleryVideoDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun autoplayPauseAndSideTapsUseActualLocalLibmpv() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clip = File.createTempFile("synthetic-video-", ".mp4", context.cacheDir)
        clip.writeBytes(Base64.decode(fixture, Base64.DEFAULT))
        try {
            rule.setContent {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ProfileMediaHero(
                            media = listOf(
                                ProfileMediaItem("IMAGE", "", null, 0u),
                                ProfileMediaItem("VIDEO", "", clip.absolutePath, 1u),
                                ProfileMediaItem("IMAGE", "", null, 2u)),
                            height = 360.dp, actionsEnabled = true, isCurrent = { true },
                            selection = remember { ProfileMediaSelection() }, identity = null,
                            actionToken = null, compact = false, headerOwnsIdentity = false,
                            onIdentityMeasured = {},
                        )
                    }
                }
            }
            rule.onNodeWithContentDescription("Nächstes Medium").performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithContentDescription("Video pausieren").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription("Video pausieren").performClick()
            rule.onNodeWithContentDescription("Video abspielen").assertExists()
            rule.onNodeWithContentDescription("Nächstes Medium").performClick()
            rule.onNodeWithTag("gallery-video-control").assertDoesNotExist()
            rule.onNodeWithContentDescription("Vorheriges Medium").performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithContentDescription("Video pausieren").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription("Vorheriges Medium").performClick()
            rule.onNodeWithTag("gallery-video-control").assertDoesNotExist()
        } finally {
            clip.delete()
        }
    }

    private val fixture = "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAANhbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAox0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAEAAAABAAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAgAAABAAAAAAIEbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABr21pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAW9zdGJsAAAAv3N0c2QAAAAAAAAAAQAAAK9hdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAQABIAAAASAAAAAAAAAABFUxhdmM2MS4xOS4xMDEgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAANWF2Y0MBZAAK/+EAGGdkAAqs2UQmwEQAAAMABAAAAwAgPEiWWAEABmjr48siwP34+AAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAYGAAAAAAAAAAYc3R0cwAAAAAAAAABAAAABAAAEAAAAAAUc3RzcwAAAAAAAAABAAAAAQAAAChjdHRzAAAAAAAAAAMAAAABAAAgAAAAAAEAAEAAAAAAAgAAEAAAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAQAAAABAAAAJHN0c3oAAAAAAAAAAAAAAAQAAALdAAAADgAAAAwAAAAMAAAAFHN0Y28AAAAAAAAAAQAAA5EAAABhdWR0YQAAAFltZXRhAAAAAAAAACFoZGxyAAAAAAAAAABtZGlyYXBwbAAAAAAAAAAAAAAAACxpbHN0AAAAJKl0b28AAAAcZGF0YQAAAAEAAAAATGF2ZjYxLjcuMTAzAAAACGZyZWUAAAMLbWRhdAAAAq0GBf//qdxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjQgcjMxMDggMzFlMTlmOSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjMgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0xIHJlZj0zIGRlYmxvY2s9MTowOjAgYW5hbHlzZT0weDM6MHgxMTMgbWU9aGV4IHN1Ym1lPTcgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MSBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTEgOHg4ZGN0PTEgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0yIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MyBiX3B5cmFtaWQ9MiBiX2FkYXB0PTEgYl9iaWFzPTAgZGlyZWN0PTEgd2VpZ2h0Yj0xIG9wZW5fZ29wPTAgd2VpZ2h0cD0yIGtleWludD0yNTAga2V5aW50X21pbj00IHNjZW5lY3V0PTQwIGludHJhX3JlZnJlc2g9MCByY19sb29rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4wIHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAAAKGWIhAAS//7oyfzLLXnmdRqJlloPlaccwj0dI/65B2ZLyCHBWQeLicEAAAAKQZojbEEP/qpX3gAAAAhBnkF4gj8B1QAAAAgBnmJqQQ8Cxg=="
}
