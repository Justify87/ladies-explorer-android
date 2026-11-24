package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FormDesignDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun darkChoicesWrapAtLargeFontScaleAndKeepExplicitSelection() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = ladiesDarkColors) {
                    val selected = remember { mutableStateOf(false) }
                    Column(Modifier.width(320.dp)) {
                        ScreenGroup("Filter") {
                            FlowRow {
                                ScreenChoice(selected.value, { selected.value = true }, true, { androidx.compose.material3.Text("Metadaten") })
                                ScreenChoice(!selected.value, { selected.value = false }, true, { androidx.compose.material3.Text("Aus") })
                            }
                        }
                    }
                }
            }
        }
        val min = with(rule.density) { 48.dp.toPx() }
        val metadata = rule.onNodeWithText("Metadaten")
        val off = rule.onNodeWithText("Aus")
        assertTrue(metadata.fetchSemanticsNode().boundsInRoot.height >= min - 1f)
        assertTrue(off.fetchSemanticsNode().boundsInRoot.height >= min - 1f)
        off.assertIsSelected()
        metadata.performClick().assertIsSelected()
        off.assertIsNotSelected()
    }

    @Test fun editingSearchDraftDoesNotSubmitUntilExplicitSearch() {
        val submitted = mutableListOf<SearchSubmitAction>()
        rule.setContent {
            MaterialTheme(colorScheme = ladiesDarkColors) {
                var search by remember { mutableStateOf(SearchUiState()) }
                SearchScreen(
                    search = search, saved = SavedSearchUiState(),
                    canStartSearch = true, canEditDraft = true,
                    onDraftChange = { change -> search = change(SearchDraft.from(search)).applyTo(search) },
                    onReset = { true }, onPreviewStart = {}, onPreviewStop = {},
                    onSearch = { submitted.add(it) }, onCancelSearch = {},
                    onSave = {}, onName = {}, onCancelEdit = {}, generation = 12L,
                    onProfile = {}, modifier = Modifier.width(360.dp).height(560.dp),
                )
            }
        }
        rule.onNodeWithText("Ort").performScrollTo().performTextReplacement("Teststadt")
        rule.runOnIdle { assertTrue(submitted.isEmpty()) }
        rule.onNodeWithText("Suchen").performClick()
        rule.runOnIdle {
            assertEquals(1, submitted.size)
            assertEquals("Teststadt", submitted.single().draft.city)
            assertEquals(12L, submitted.single().generation)
        }
    }

    @Test fun lightSettingsShowUnknownStatusAndDoNotExportOrCaptureImplicitly() {
        val capture = mutableListOf<CaptureMode>()
        val replay = mutableListOf<Boolean>()
        var exports = 0
        rule.setContent {
            MaterialTheme(colorScheme = ladiesLightColors) {
                SettingsScreen(
                    state = HomeUiState(loading = false),
                    replayLikes = false, replayEnabled = true, onReplayLikes = { replay.add(it) },
                    onRefresh = {}, onSaveDiagnostics = { exports++ }, onShareDiagnostics = { exports++ },
                    onDebugLogging = {}, onCaptureMode = { capture.add(it) }, onClearHar = {},
                    onSaveHar = { exports++ }, onShareHar = { exports++ },
                )
            }
        }
        rule.onNodeWithText("Datenbankstatus noch nicht bekannt.").assertExists()
        rule.onNodeWithContentDescription("Likes erneut zeigen").performScrollTo().performClick()
        rule.onNodeWithText("Debug-Log speichern").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("HAR speichern").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Aus").performScrollTo().assertIsSelected()
        rule.runOnIdle {
            assertEquals(0, exports)
            assertTrue(capture.isEmpty())
            assertEquals(listOf(true), replay)
        }
        rule.onNodeWithText("Metadaten").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(CaptureMode.METADATA), capture) }
    }
}
