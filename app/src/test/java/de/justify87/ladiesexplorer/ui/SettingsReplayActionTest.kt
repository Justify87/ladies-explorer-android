package de.justify87.ladiesexplorer.ui

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
}
