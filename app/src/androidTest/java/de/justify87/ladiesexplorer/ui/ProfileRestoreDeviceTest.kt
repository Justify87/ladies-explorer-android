package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real ProfileFlow with synthetic local readers, no source access or actual note/DB writes. */
class ProfileRestoreDeviceTest {
    @get:Rule val rule = createComposeRule()

    private fun profile(snapshot: Long = 11) = ProfileReadModel(
        NativeProfileProjection(
            ProfileResponse(listingId = 4, snapshotId = snapshot, newSnapshot = false, advertId = "fixture",
                name = "Fixture", city = "Fixture", description = "Fixture ".repeat(120), postalCode = null,
                hiddenLocation = null, sourceProfileId = null, canonicalId = null,
                phoneDisplay = null, publishedAt = null, traitsJson = "{}", servicesJson = "{}",
                openingTimesJson = "{}", media = emptyList()),
            ProfileIdentity(1, "ACTIVE", null, false, false, false, null),
            PersonSummary(1, "Fixture", Preference.NONE, false, 1u, ""),
            listOf(ProfileMediaItem("IMAGE", "", null, 0u),
                ProfileMediaItem("VIDEO", "https://example.invalid/fixture", null, 1u)),
            emptyList(), emptyList(),
        ), true,
    )

    private fun token(model: ProfileReadModel) = SwipeActionToken(
        3, 1, 4, 1, model.profile.snapshotId, model.revision, Preference.NONE, false, emptyList(),
    )

    private fun facts() = ProfileFacts(emptyList(),
        listOf(ProfileField("Fixture", (1..25).map { "Value $it" })), emptyList(), emptyList(), emptyList())

    private fun reads(factsRead: suspend () -> ProfileFacts) = ProfilePanelReads(
        { _, _ -> factsRead() }, { PersonDetail(emptyList(), 0u, 0u, 0u, 0u) }, { _, _, _ -> emptyList() })

    private val notes = NoteEditorActions({ _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {})

    @Test fun lateUndoReceiptWaitsForRealRowsAndSurvivesSavedStateRecreation() {
        val tester = StateRestorationTester(rule)
        val ready = CompletableDeferred<ProfileFacts>()
        val readers = reads { ready.await() }
        val original = profile()
        var model by mutableStateOf<ProfileReadModel?>(null)
        var receipt by mutableStateOf<SwipePresentation?>(null)
        var consumed = 0
        lateinit var list: LazyListState
        lateinit var capture: SwipePresentationCapture
        lateinit var jump: () -> Unit
        tester.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                capture = remember { SwipePresentationCapture() }
                val scope = rememberCoroutineScope()
                jump = { scope.launch { list.scrollToItem(0) } }
                ProfileFlow(list, model, "{}", model?.let(::token), model == null, emptyMap(), notes,
                    isProfileCurrent = { true }, onConfirmIdentity = { _, _ -> },
                    modifier = Modifier.size(300.dp, 300.dp), swipeRestore = receipt, swipeRunId = 3,
                    swipeCapture = capture, onSwipeRestoreConsumed = { consumed++; receipt = null },
                    panelReads = readers) {
                    item("hero") {
                        Column(Modifier.height(400.dp)) {
                            val index = mediaSelection.resolve(original.media, model != null)
                            Text("Medium $index")
                            TextButton(onClick = { mediaSelection.select(original.media, 0) }) { Text("Erstes Medium") }
                        }
                    }
                }
            }
        }
        val position = SwipePresentation(4, 1, 11, "{}", "VIDEO:https://example.invalid/fixture",
            "profile-services", 12, listOf("Services"), runId = 3, mediaIndex = 1)
        rule.runOnIdle { receipt = position }
        rule.runOnIdle { assertEquals(0, consumed); assertNull(capture.read()) }
        rule.runOnIdle { model = original }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, consumed); assertNotNull(receipt) }
        // Actual saveable recreation before the data dependency becomes available.
        tester.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertEquals(0, consumed); ready.complete(facts()) }
        rule.waitUntil(5_000) { consumed == 1 }
        rule.runOnIdle {
            val actual = requireNotNull(capture.read())
            assertEquals("profile-services", actual.anchorKey)
            assertEquals(12, actual.anchorOffset)
            assertEquals(1, actual.mediaIndex)
            assertTrue("Services" in actual.sections)
        }
        rule.runOnIdle { jump() }
        rule.onNodeWithText("Medium 1").assertExists()
        rule.onNodeWithText("Erstes Medium").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Medium 0").assertExists()
        rule.runOnIdle { assertEquals(1, consumed) }
    }

    @Test fun changedSnapshotRestoresIndependentKeysAndFailedPanelReadUsesSafeRowStart() {
        val original = profile(12)
        val readers = reads { error("Fixture read failure") }
        var receipt by mutableStateOf<SwipePresentation?>(SwipePresentation(4, 1, 11, "{}",
            "VIDEO:https://example.invalid/fixture", "profile-services", 900, listOf("Services"), 3, 1))
        lateinit var capture: SwipePresentationCapture
        rule.setContent {
            MaterialTheme {
                capture = remember { SwipePresentationCapture() }
                ProfileFlow(rememberLazyListState(), original, "{}", token(original), false,
                    emptyMap(), notes, { true }, { _, _ -> }, Modifier.size(300.dp, 180.dp),
                    swipeRestore = receipt, swipeRunId = 3, swipeCapture = capture,
                    onSwipeRestoreConsumed = { receipt = null }, panelReads = readers) {
                    item("hero") { Text("Fixture", Modifier.height(400.dp)) }
                }
            }
        }
        rule.waitUntil(5_000) { receipt == null }
        rule.runOnIdle {
            val actual = requireNotNull(capture.read())
            assertEquals("profile-services", actual.anchorKey)
            assertEquals(0, actual.anchorOffset)
            assertEquals("VIDEO:https://example.invalid/fixture", actual.mediaId)
        }
    }
}
