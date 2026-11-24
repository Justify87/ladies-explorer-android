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
        val actions = mutableListOf<String>()
        var resolving by mutableStateOf(false)
        var cancelling by mutableStateOf(false)
        rule.setContent {
            LadiesTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MapDetails(
                        state = MapUiState(overview = MapOverview(listOf(profile), 2u, 1u, 1u),
                            resolving = resolving, cancelling = cancelling),
                        tileState = MapTileState.Failed, active = profile, selectionCount = 2,
                        profileEnabled = true, onRefresh = { actions.add("refresh") },
                        onResolve = { actions.add("resolve"); resolving = true },
                        onCancel = { actions.add("cancel"); cancelling = true },
                        onRetryTiles = { actions.add("tiles") }, onPickAll = { actions.add("all") },
                        onPickLocation = { actions.add("location") }, onClear = { actions.add("clear") },
                        onOpen = { actions.add(it.name) },
                    )
                }
            }
        }
        rule.onNodeWithText("Karte erneut laden").performScrollTo().performClick()
        rule.onNodeWithText("Standorte auflösen").performScrollTo().performClick()
        rule.onNodeWithText("Aktualisieren").assertIsNotEnabled()
        rule.onNodeWithText("Abbrechen").performScrollTo().performClick()
        rule.onNodeWithText("Wird abgebrochen …").assertIsNotEnabled()
        rule.onNodeWithText("2 Profile am ungefähren Ort").performScrollTo().performClick()
        rule.onNodeWithText("Profil öffnen").performScrollTo().performClick()
        rule.onNodeWithText("Kartierte Profile auswählen").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("tiles", "resolve", "cancel", "location", "Fixture", "all"), actions) }
    }
}
