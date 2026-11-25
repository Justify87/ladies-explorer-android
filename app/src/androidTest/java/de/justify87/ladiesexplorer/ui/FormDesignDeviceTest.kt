package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.CaptureMode
import de.justify87.ladiesexplorer.core.CoreStore
import de.justify87.ladiesexplorer.core.GlobalResultVisibility
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import de.justify87.ladiesexplorer.core.SourceFilterCatalog
import de.justify87.ladiesexplorer.core.SourceFilterGroup
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

    @Test fun progressiveSectionHeaderAndActionsShareBoundsWithoutImplicitAction() {
        var expanded by mutableStateOf(false)
        var dark by mutableStateOf(false)
        var actions = 0
        rule.setContent {
            MaterialTheme(colorScheme = if (dark) ladiesDarkColors else ladiesLightColors) {
                Column(Modifier.width(320.dp).testTag("section-frame")) {
                    ProgressiveSection("Fixture filter", expanded, { expanded = !expanded }) {
                        Text("Fixture option")
                        TextButton(onClick = { actions++ }) { Text("Fixture action") }
                    }
                }
            }
        }
        repeat(2) {
            val header = rule.onNodeWithText("Fixture filter")
            header.assertExists()
            assertTrue(header.fetchSemanticsNode().boundsInRoot.height >= with(rule.density) { 48.dp.toPx() } - 1f)
            rule.onNodeWithText("Fixture option").assertDoesNotExist()
            header.performClick()
            val frame = rule.onNodeWithTag("section-frame").fetchSemanticsNode().boundsInRoot
            val option = rule.onNodeWithText("Fixture option").fetchSemanticsNode().boundsInRoot
            val action = rule.onNodeWithText("Fixture action")
            val actionBounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue(option.top >= header.fetchSemanticsNode().boundsInRoot.bottom)
            assertTrue(actionBounds.top >= option.bottom && actionBounds.bottom <= frame.bottom)
            rule.runOnIdle { assertEquals(it, actions) }
            action.performClick()
            header.performClick()
            rule.onNodeWithText("Fixture action").assertDoesNotExist()
            rule.runOnIdle { dark = true }
        }
        rule.runOnIdle { assertEquals(2, actions) }
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
                    onReset = { true }, onSaveDefault = {}, onPreviewStart = {}, onPreviewStop = {},
                    onSearch = { submitted.add(it) }, onCancelSearch = {},
                    onSave = {}, onName = {}, onCancelEdit = {}, generation = 12L,
                    onProfile = {}, modifier = Modifier.width(360.dp).height(560.dp),
                )
            }
        }
        rule.onNodeWithContentDescription("Ort").performScrollTo().performTextReplacement("Teststadt")
        rule.runOnIdle { assertTrue(submitted.isEmpty()) }
        rule.onNodeWithText("Suchen").performClick()
        rule.runOnIdle {
            assertEquals(1, submitted.size)
            assertEquals("Teststadt", submitted.single().draft.city)
            assertEquals(12L, submitted.single().generation)
        }
    }


    @Test fun standardActionIsExplicitAndDoesNotSubmitSearch() {
        var savedDraft: SearchDraft? = null
        var submitted = 0
        rule.setContent {
            MaterialTheme(colorScheme = ladiesLightColors) {
                var search by remember { mutableStateOf(SearchUiState()) }
                SearchScreen(
                    search = search, saved = SavedSearchUiState(),
                    canStartSearch = true, canEditDraft = true,
                    onDraftChange = { change -> search = change(SearchDraft.from(search)).applyTo(search) },
                    onReset = {
                        search = (savedDraft ?: SearchDraft()).applyTo(search)
                        true
                    },
                    onSaveDefault = { savedDraft = SearchDraft.from(search) },
                    onPreviewStart = {}, onPreviewStop = {}, onSearch = { submitted++ },
                    onCancelSearch = {}, onSave = {}, onName = {}, onCancelEdit = {},
                    generation = 1L, onProfile = {}, modifier = Modifier.width(360.dp).height(560.dp),
                )
            }
        }
        val city = rule.onNodeWithContentDescription("Ort")
        city.performScrollTo().performTextReplacement("Teststadt")
        rule.onNodeWithText("Aktuelle Suche als Standard speichern").performClick()
        city.performTextReplacement("Andere Stadt")
        rule.onNodeWithText("Reset").performClick()
        rule.runOnIdle {
            assertEquals("Teststadt", savedDraft?.city)
            assertEquals(0, submitted)
        }
        city.assertTextContains("Teststadt")
    }

    @Test fun filteringOptionsDoesNotClearHiddenSelectionOrSubmit() {
        var search by mutableStateOf(SearchUiState(filterCatalog = SourceFilterCatalog(
            listOf(""), listOf(SourceFilterGroup("haarfarbe", listOf("Braun", "Schwarz"))))))
        var submitted = 0
        rule.setContent {
            MaterialTheme(colorScheme = ladiesDarkColors) {
                SearchScreen(
                    search = search, saved = SavedSearchUiState(),
                    canStartSearch = true, canEditDraft = true,
                    onDraftChange = { change -> search = change(SearchDraft.from(search)).applyTo(search) },
                    onReset = { true }, onSaveDefault = {}, onPreviewStart = {}, onPreviewStop = {},
                    onSearch = { submitted++ }, onCancelSearch = {},
                    onSave = {}, onName = {}, onCancelEdit = {}, generation = 12L,
                    onProfile = {}, modifier = Modifier.width(360.dp).height(560.dp),
                )
            }
        }
        val query = rule.onNodeWithContentDescription("Filter suchen")
        query.performTextReplacement("Braun")
        rule.onNodeWithText("Braun").performScrollTo().performClick().assertIsSelected()
        query.performScrollTo().performTextReplacement("Schwarz")
        rule.onNodeWithText("Braun").assertDoesNotExist()
        rule.onNodeWithText("Schwarz").performScrollTo().assertIsNotSelected()
        rule.runOnIdle {
            assertEquals(setOf("Braun"), search.selectedFilters["haarfarbe"])
            assertEquals(0, submitted)
        }
        query.performScrollTo().performTextReplacement("fixture-no-match")
        rule.onNodeWithText("Keine passenden Filter").assertExists()
        query.performTextReplacement("")
        rule.runOnIdle { assertEquals(setOf("Braun"), search.selectedFilters["haarfarbe"]) }
    }

    @Test fun normalSectionStateSurvivesTemporaryFilterTextAndTabRemoval() {
        var visible by mutableStateOf(true)
        rule.setContent {
            MaterialTheme(colorScheme = ladiesDarkColors) {
                val holder = rememberSaveableStateHolder()
                if (visible) holder.SaveableStateProvider("Filters") {
                    SearchScreen(
                        search = SearchUiState(), saved = SavedSearchUiState(),
                        canStartSearch = true, canEditDraft = true,
                        onDraftChange = {}, onReset = { true }, onSaveDefault = {}, onPreviewStart = {}, onPreviewStop = {},
                        onSearch = {}, onCancelSearch = {}, onSave = {}, onName = {}, onCancelEdit = {},
                        generation = 1L, onProfile = {}, modifier = Modifier.width(360.dp).height(560.dp),
                    )
                }
            }
        }
        val query = rule.onNodeWithContentDescription("Filter suchen")
        val location = rule.onNodeWithText("Standort")
        val city = rule.onNodeWithContentDescription("Ort")
        city.assertExists()
        location.performClick()
        city.assertDoesNotExist()
        query.performTextReplacement("Ort")
        city.assertExists()
        query.performTextReplacement("")
        city.assertDoesNotExist()
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        rule.runOnIdle { visible = true }
        location.assertExists()
        city.assertDoesNotExist()
        location.performClick()
        city.assertExists()
    }

    @Test fun compactFilterInputAndChoicesKeepTouchTargetsAtLargeFont() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = ladiesLightColors) {
                    Column(Modifier.width(320.dp)) {
                        var text by remember { mutableStateOf("") }
                        CompactFilterField(text, { text = it }, "Filter suchen")
                        ProgressiveSection("Rubrik", true, {}, compact = true) {
                            ScreenChoice(true, {}, true, { Text("Fixture", style = MaterialTheme.typography.bodySmall) }, compact = true)
                        }
                    }
                }
            }
        }
        val min = with(rule.density) { 48.dp.toPx() }
        val field = rule.onNodeWithContentDescription("Filter suchen")
        field.performClick().performTextInput("Test")
        field.assertTextContains("Test")
        val header = rule.onNodeWithText("Rubrik")
        val choice = rule.onNodeWithText("Fixture")
        for (node in listOf(field, header, choice)) {
            assertTrue(node.fetchSemanticsNode().boundsInRoot.height >= min - 1f)
        }
        assertTrue(header.fetchSemanticsNode().boundsInRoot.top >= field.fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(choice.fetchSemanticsNode().boundsInRoot.top >= header.fetchSemanticsNode().boundsInRoot.bottom)
        choice.assertIsSelected()
    }

    @Test fun lightSettingsShowUnknownStatusAndDoNotExportOrCaptureImplicitly() {
        val capture = mutableListOf<CaptureMode>()
        val replay = mutableListOf<Boolean>()
        var exports = 0
        var route by mutableStateOf(ShellRoute().select(RootDestination.Settings))
        var visibility by mutableStateOf(GlobalResultVisibility())
        rule.setContent {
            MaterialTheme(colorScheme = ladiesLightColors) {
                Column {
                    AppHeader(route.settingsPane.title, onBack = if (route.settingsPane == SettingsPane.Main) null
                        else ({ route = route.back() }))
                    androidx.compose.runtime.key(route.settingsPane) {
                        SettingsScreen(
                    pane = route.settingsPane, onOpenPane = { route = route.openSettings(it) },
                    globalVisibility = visibility, visibilityEnabled = true, onGlobalVisibility = { visibility = it },
                    state = HomeUiState(loading = false),
                    replayLikes = false, replayEnabled = true, onReplayLikes = { replay.add(it) },
                    onRefresh = {}, onSaveDiagnostics = { exports++ }, onShareDiagnostics = { exports++ },
                    onDebugLogging = {}, onCaptureMode = { capture.add(it) }, onClearHar = {},
                    onSaveHar = { exports++ }, onShareHar = { exports++ },
                    modifier = Modifier.width(320.dp).height(480.dp),
                        )
                    }
                }
            }
        }
        rule.onNodeWithText("Datenbankstatus noch nicht bekannt.").assertDoesNotExist()
        rule.onNodeWithText("Suche").performClick()
        rule.onNodeWithContentDescription("Likes ausblenden").assertIsOff().performClick().assertIsOn()
        rule.onNodeWithContentDescription("Dislikes ausblenden").assertIsOff().performClick().assertIsOn()
        rule.onNodeWithContentDescription("Zurück").performClick()
        rule.onNodeWithText("Entdecken").performClick()
        rule.onNodeWithContentDescription("Likes erneut zeigen").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Zurück").performClick()
        rule.onNodeWithText("Diagnose und Fehlerbehebung").performClick()
        rule.onNodeWithText("Datenbankstatus noch nicht bekannt.").assertExists()
        rule.onNodeWithText("Debug-Log speichern").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("HAR speichern").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Aus").performScrollTo().assertIsSelected()
        rule.runOnIdle {
            assertEquals(0, exports)
            assertTrue(capture.isEmpty())
            assertEquals(listOf(true), replay)
            assertEquals(GlobalResultVisibility(true, true), visibility)
        }
        rule.onNodeWithText("Metadaten").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(CaptureMode.METADATA), capture) }
    }
    @Test fun globalVisibilityRoundTripsAcrossStoreInstancesWithoutTouchingReplayOrCapture() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val fixture = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("m09_9_fixture_" + name, mode)
        }
        val preferences = fixture.getSharedPreferences("explorer", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val store = CoreStore(fixture)
            assertEquals(GlobalResultVisibility(), store.globalResultVisibility())
            store.setReplayLikes(false)
            for (likes in listOf(false, true)) for (dislikes in listOf(false, true)) {
                val expected = GlobalResultVisibility(likes, dislikes)
                store.setGlobalResultVisibility(expected)
                val recreated = CoreStore(fixture)
                assertEquals(expected, recreated.globalResultVisibility())
                assertTrue(!recreated.replayLikes())
            }
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
