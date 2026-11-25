package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.GlobalResultVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReplayActionTest {
    @Test fun settingsReplayRequiresRenderedSurfaceGenerationDeckAndValue() {
        val discovery = DiscoverUiState(
            loading = false, actionSurface = ProfileActionSurface.SETTINGS,
            actionGeneration = 8L, swipeSessionId = 3L, activeRunId = 4L, replayLikes = true,
        )
        val search = SearchUiState()
        val action = discovery.settingsReplayAction()
        assertTrue(settingsReplayMatches(discovery, search, action))
        listOf(
            discovery.copy(actionSurface = ProfileActionSurface.NONE),
            discovery.copy(actionSurface = ProfileActionSurface.GRID),
            discovery.copy(actionGeneration = 9L),
            discovery.copy(swipeSessionId = 4L),
            discovery.copy(activeRunId = 5L),
            discovery.copy(replayLikes = false),
            discovery.copy(loading = true),
            discovery.copy(busy = true),
        ).forEach { assertFalse(settingsReplayMatches(it, search, action)) }
        assertFalse(settingsReplayMatches(discovery, search.copy(loading = true), action))
        assertFalse(settingsReplayMatches(discovery, search.copy(profileOpen = true), action))
        assertFalse(settingsReplayMatches(discovery, search.copy(actionBusy = true), action))
    }
    @Test fun globalVisibilityRequiresCurrentSettingsAndExpectedPolicy() {
        val discovery = DiscoverUiState(
            loading = false, actionSurface = ProfileActionSurface.SETTINGS,
            actionGeneration = 8L, swipeSessionId = 3L, activeRunId = 4L,
        )
        val search = SearchUiState()
        val action = discovery.settingsVisibilityAction()
        assertTrue(settingsVisibilityMatches(discovery, search, action))
        listOf(
            discovery.copy(actionSurface = ProfileActionSurface.NONE),
            discovery.copy(actionGeneration = 9L),
            discovery.copy(swipeSessionId = 4L),
            discovery.copy(activeRunId = 5L),
            discovery.copy(globalVisibility = GlobalResultVisibility(hideLikes = true)),
            discovery.copy(loading = true),
            discovery.copy(busy = true),
        ).forEach { assertFalse(settingsVisibilityMatches(it, search, action)) }
        listOf(search.copy(loading = true), search.copy(profileOpen = true), search.copy(actionBusy = true))
            .forEach { assertFalse(settingsVisibilityMatches(discovery, it, action)) }
    }

    @Test fun globalAndLocalFiltersCombineWithoutChangingDefinitionOrMediaRules() {
        for (localLikes in listOf(false, true)) for (localDislikes in listOf(false, true))
            for (globalLikes in listOf(false, true)) for (globalDislikes in listOf(false, true)) {
                val local = defaultLocalVisibility().copy(
                    videoOnly = true, photosOnly = true, imageMode = "AI",
                    hideLikes = localLikes, hideDislikes = localDislikes,
                )
                val global = GlobalResultVisibility(globalLikes, globalDislikes)
                assertEquals(local.copy(hideLikes = localLikes || globalLikes,
                    hideDislikes = localDislikes || globalDislikes), global.effective(local))
                assertEquals(localLikes, local.hideLikes)
                assertEquals(localDislikes, local.hideDislikes)
                assertEquals(local, GlobalResultVisibility().effective(local))
            }
    }
}
