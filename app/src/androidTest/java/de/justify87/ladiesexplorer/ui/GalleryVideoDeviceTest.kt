package de.justify87.ladiesexplorer.ui

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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clip = File.createTempFile("synthetic-video-", ".mp4", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("fixture.mp4").use { input ->
                clip.outputStream().use { output -> input.copyTo(output) }
            }
            rule.setContent {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ProfileMediaHero(
                            media = listOf(
                                ProfileMediaItem("IMAGE", "", null, 0u),
                                ProfileMediaItem("VIDEO", "", clip.absolutePath, 1u),
                                ProfileMediaItem("IMAGE", "", null, 2u)),
                            height = 360.dp, actionsEnabled = true, isCurrent = { true },
                            selection = remember { ProfileMediaSelection() },
                            actionToken = null, compact = false,
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
}
