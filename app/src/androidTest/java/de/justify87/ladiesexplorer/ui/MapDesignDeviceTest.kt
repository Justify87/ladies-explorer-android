package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import de.justify87.ladiesexplorer.core.MapOverview
import de.justify87.ladiesexplorer.core.MapProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MapDesignDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun localSelectionAndCancelRemainAvailableWithoutMapTiles() {
        val profile = MapProfile(1L, 1L, "Fixture", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", true, null)
        val second = profile.copy(listingId = 2L, personId = 2L, name = "Fixture B")
        val actions = mutableListOf<String>()
        val resolving = true
        var cancelling by mutableStateOf(false)
        rule.setContent {
            LadiesTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MapDetails(
                        state = MapUiState(overview = MapOverview(listOf(profile, second), 3u, 1u, 1u),
                            resolving = resolving, cancelling = cancelling),
                        tileState = MapTileState.Failed, active = profile, selectionCount = 2,
                        profileEnabled = true,
                        onCancel = { actions.add("cancel"); cancelling = true },
                        onRetryTiles = { actions.add("tiles") }, onPickAll = { actions.add("all") },
                        onPickLocation = { actions.add("location") }, onClear = { actions.add("clear") },
                        onOpen = { actions.add(it.name) },
                    )
                }
            }
        }
        rule.onNodeWithText("Karte erneut laden").performScrollTo().performClick()
        rule.onNodeWithText("Standorte auflösen").assertDoesNotExist()
        rule.onNodeWithText("Stadtbereich · Leipzig").assertExists()
        rule.onNodeWithText("Aktualisieren").assertDoesNotExist()
        rule.onNodeWithText("2 von 3 Profilen kartiert · 1 ohne Position").assertDoesNotExist()
        rule.onNodeWithText("Abbrechen").performScrollTo().performClick()
        rule.onNodeWithText("Wird abgebrochen …").assertIsNotEnabled()
        rule.onNodeWithText("2 Profile am ungefähren Ort").performScrollTo().performClick()
        rule.onNodeWithText("Profil öffnen").performScrollTo().performClick()
        rule.onNodeWithText("Kartierte Profile auswählen").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Auswahl lösen").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("tiles", "cancel", "location", "Fixture", "all", "clear"), actions) }
    }

    @Test fun pickerExposesSelectedStateAndChoosesExactRenderedProfile() {
        val a = MapProfile(1L, 1L, "Fixture A", "Leipzig", "Leipzig", 51.34, 12.37, "CITY", true, null)
        val b = a.copy(listingId = 2L, personId = 2L, name = "Fixture B")
        var chosen: MapProfile? = null
        rule.setContent {
            LadiesTheme {
                MapProfilePicker(listOf(a, b), a.listingId, "Profile am ungefähren Ort",
                    onDismiss = {}, onSelect = { chosen = it })
            }
        }
        rule.onNodeWithText("✓ Fixture A").assertIsSelected()
        rule.onNodeWithText("Fixture B").assertIsNotSelected().performClick()
        rule.runOnIdle { assertEquals(b, chosen) }
    }
}
