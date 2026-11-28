package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedPeopleStateTest {
    private fun row(person: Long = 1L, preference: Preference = Preference.NONE, favorite: Boolean = false) =
        SavedPersonRow(person, "Fixture", preference, favorite, null, 0u,
            "2026-11-22", "2026-11-22", "UNKNOWN", null, null, null, false)

    private fun displayed(row: SavedPersonRow) = DiscoverUiState(
        loading = false, actionSurface = ProfileActionSurface.SAVED, actionGeneration = 42,
        savedPage = SavedPersonPage(listOf(row), 1u, 1u, 123L, false),
    )

    @Test fun activityLabelsDistinguishSeenChangedAndStatusCheckWithoutRepeatingDates() {
        val seen = row().copy(lastSeenAt = "2026-11-22T08:00:00Z")
        assertEquals(listOf("Zuletzt gesehen: 2026-11-22"), savedPersonActivityLabels(seen))
        val recent = seen.copy(recent = true, latestChangeAt = "2026-11-22T09:00:00Z")
        assertEquals(listOf("Gesehen und geändert: 2026-11-22"), savedPersonActivityLabels(recent))
        assertEquals(listOf("Zuletzt gesehen: 2026-11-22", "Geändert: 2026-11-21",
            "Letzte Statusbeobachtung: 2026-11-24 10:15 UTC"),
            savedPersonActivityLabels(recent.copy(latestChangeAt = "2026-11-21T09:00:00Z",
                statusCheckedAt = "2026-11-24T10:15:00Z")))
        assertEquals(savedPersonActivityLabels(seen), savedPersonActivityLabels(recent.copy(latestChangeAt = null)))
        assertEquals(savedPersonActivityLabels(seen), savedPersonActivityLabels(recent.copy(recent = false)))
    }

    @Test fun savedControlsRequireVisibleCurrentSelectionAndIdleReadContext() {
        val state = displayed(row())
        val search = SearchUiState()
        val action = state.savedPeopleControlAction()
        assertTrue(savedPeopleControlMatches(state, search, action))
        assertFalse(savedPeopleControlMatches(state.copy(savedKind = SavedPersonKind.LIKE), search, action))
        assertFalse(savedPeopleControlMatches(state.copy(savedSort = SavedPersonSort.NAME), search, action))
        assertFalse(savedPeopleControlMatches(state.copy(actionSurface = ProfileActionSurface.NONE), search, action))
        assertFalse(savedPeopleControlMatches(state.copy(actionSurface = ProfileActionSurface.GRID), search, action))
        assertFalse(savedPeopleControlMatches(state.copy(loading = true), search, action))
        assertFalse(savedPeopleControlMatches(state.copy(busy = true), search, action))
        assertFalse(savedPeopleControlMatches(state, search.copy(profileOpen = true), action))
        assertFalse(savedPeopleControlMatches(state, search.copy(loading = true), action))
        assertFalse(savedPeopleControlMatches(state, search.copy(actionBusy = true), action))

        // An explicit selection or reload may supersede local pagination.
        assertTrue(savedPeopleControlMatches(state.copy(savedLoadingMore = true), search, action))
        assertTrue(savedPeopleControlMatches(state.copy(savedRecentLoadingMore = true), search, action))

        // Reload revokes old controls even when the replacement read fails.
        val failed = state.copy(savedPage = null, savedError = "read failed", actionGeneration = 43L)
        assertFalse(savedPeopleControlMatches(failed, search, action))
        assertTrue(savedPeopleControlMatches(failed, search, failed.savedPeopleControlAction()))
    }

    @Test fun savedActionsRequireRenderedRowTabAndWindowGeneration() {
        val original = row()
        val state = displayed(original)
        val action = SavedPersonAction(original, state.savedKind, state.savedSort, state.actionGeneration)
        assertTrue(savedActionMatches(state, SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(savedPage = null), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(savedPage = state.savedPage!!.copy(
            rows = listOf(row(person = 2L)))), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(savedPage = state.savedPage.copy(
            rows = listOf(row(preference = Preference.LIKE)))), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(savedPage = state.savedPage.copy(
            rows = listOf(row(favorite = true)))), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(savedKind = SavedPersonKind.LIKE), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(savedSort = SavedPersonSort.NAME), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(actionGeneration = 43), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(actionSurface = ProfileActionSurface.GRID), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(loading = true), SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(busy = true), SearchUiState(), action))
    }

    @Test fun savedRowAdmissionRejectsOpenProfileAndCompetingWorkWithOrWithoutListing() {
        for (person in listOf(row(), row().copy(card = fixtureCard(8L, 1L)))) {
            val state = displayed(person)
            val action = SavedPersonAction(person, state.savedKind, state.savedSort, state.actionGeneration)
            val search = SearchUiState()
            assertTrue(savedActionMatches(state, search, action))
            assertFalse(savedActionMatches(state.copy(busy = true), search, action))
            assertFalse(savedActionMatches(state, search.copy(profileOpen = true), action))
            assertFalse(savedActionMatches(state, search.copy(loading = true), action))
            assertFalse(savedActionMatches(state, search.copy(actionBusy = true), action))
        }
    }

    @Test fun recentSavedActionsUseTheSameStrictContract() {
        val recent = row().copy(recent = true)
        val state = displayed(recent).copy(savedPage = SavedPersonPage(emptyList(), 1u, 0u, 123L, false),
            savedRecent = SavedPersonPage(listOf(recent), 1u, 1u, 123L, false))
        val action = SavedPersonAction(recent, state.savedKind, state.savedSort, state.actionGeneration)
        assertTrue(savedActionMatches(state, SearchUiState(), action))
        assertFalse(savedActionMatches(state.copy(savedRecent = null), SearchUiState(), action))
    }

    @Test fun statusBatchRequiresExactRenderedSavedSelectionAndWindow() {
        val state = displayed(row())
        val action = SavedStatusBatchAction(state.savedPage!!, state.savedKind, state.savedSort,
            state.actionGeneration)
        assertTrue(savedStatusBatchMatches(state, action))
        assertFalse(savedStatusBatchMatches(state.copy(savedPage = null), action))
        assertFalse(savedStatusBatchMatches(state.copy(savedPage = state.savedPage.copy()), action))
        assertFalse(savedStatusBatchMatches(state.copy(savedKind = SavedPersonKind.LIKE), action))
        assertFalse(savedStatusBatchMatches(state.copy(savedSort = SavedPersonSort.NAME), action))
        assertFalse(savedStatusBatchMatches(state.copy(actionGeneration = 43), action))
        assertFalse(savedStatusBatchMatches(state.copy(actionSurface = ProfileActionSurface.SEARCH), action))
        assertFalse(savedStatusBatchMatches(state.copy(loading = true), action))
        assertFalse(savedStatusBatchMatches(state.copy(busy = true), action))
    }

    @Test fun statusCancelRequiresVisibleContextAndCurrentRunningAttempt() {
        val state = displayed(row()).copy(busy = true,
            savedStatusProgress = SavedStatusProgress(attemptId = 7L))
        val search = SearchUiState()
        val action = SavedStatusCancelAction(7L, 42L)
        val progress = state.savedStatusProgress!!
        assertTrue(savedStatusCancelMatches(state, search, action))
        assertFalse(savedStatusCancelMatches(state.copy(savedStatusProgress = null), search, action))
        assertFalse(savedStatusCancelMatches(state.copy(savedStatusProgress = progress.copy(running = false)), search, action))
        assertFalse(savedStatusCancelMatches(state.copy(savedStatusProgress = progress.copy(cancelled = true)), search, action))
        assertFalse(savedStatusCancelMatches(state.copy(actionSurface = ProfileActionSurface.NONE), search, action))
        assertFalse(savedStatusCancelMatches(state.copy(actionSurface = ProfileActionSurface.GRID), search, action))
        assertFalse(savedStatusCancelMatches(state.copy(actionGeneration = 43L), search, action))
        assertFalse(savedStatusCancelMatches(state, search.copy(profileOpen = true), action))
        // Progress publication within the same attempt must not invalidate its cancel button.
        assertTrue(savedStatusCancelMatches(state.copy(savedStatusProgress = progress.copy(done = 1)), search, action))
        // A new attempt in the same visible context must reject the earlier callback.
        val next = state.copy(savedStatusProgress = SavedStatusProgress(attemptId = 8L))
        assertFalse(savedStatusCancelMatches(next, search, action))
        assertTrue(savedStatusCancelMatches(next, search, action.copy(attemptId = 8L)))
    }

    @Test fun paginationRequiresExactRenderedWindowAndAvailableOperation() {
        val page = displayed(row()).savedPage!!.copy(hasMore = true)
        val recent = page.copy()
        val state = displayed(row()).copy(savedPage = page, savedRecent = recent)
        val search = SearchUiState()
        val regularAction = SavedPeoplePageAction(page, state.savedKind, state.savedSort, 42L, false)
        val recentAction = SavedPeoplePageAction(recent, state.savedKind, state.savedSort, 42L, true)
        for (action in listOf(regularAction, recentAction)) {
            assertTrue(savedPeoplePageMatches(state, search, action))
            assertFalse(savedPeoplePageMatches(state.copy(savedKind = SavedPersonKind.LIKE), search, action))
            assertFalse(savedPeoplePageMatches(state.copy(savedSort = SavedPersonSort.NAME), search, action))
            assertFalse(savedPeoplePageMatches(state.copy(actionSurface = ProfileActionSurface.NONE), search, action))
            assertFalse(savedPeoplePageMatches(state.copy(actionGeneration = 43L), search, action))
            assertFalse(savedPeoplePageMatches(state.copy(loading = true), search, action))
            assertFalse(savedPeoplePageMatches(state.copy(busy = true), search, action))
            assertFalse(savedPeoplePageMatches(state.copy(savedLoadingMore = true), search, action))
            assertFalse(savedPeoplePageMatches(state.copy(savedRecentLoadingMore = true), search, action))
            assertFalse(savedPeoplePageMatches(state, search.copy(profileOpen = true), action))
            assertFalse(savedPeoplePageMatches(state, search.copy(loading = true), action))
            assertFalse(savedPeoplePageMatches(state, search.copy(actionBusy = true), action))
            assertFalse(savedPeoplePageMatches(state, search, action.copy(watchlist = !action.watchlist)))
        }
        assertFalse(savedPeoplePageMatches(state.copy(savedPage = page.copy()), search, regularAction))
        assertFalse(savedPeoplePageMatches(state.copy(savedRecent = recent.copy()), search, recentAction))
        val exhausted = page.copy(hasMore = false)
        assertFalse(savedPeoplePageMatches(state.copy(savedPage = exhausted), search, regularAction.copy(page = exhausted)))
        assertTrue(savedPeoplePageMatches(state.copy(savedError = "read failed"), search, regularAction))
    }

    @Test fun singleStatusTargetsOnlyTheCurrentRenderedListing() {
        val person = row().copy(card = fixtureCard(8L, 1L))
        val state = displayed(person)
        val search = SearchUiState()
        val action = SavedPersonAction(person, state.savedKind, state.savedSort, state.actionGeneration)
        assertEquals(8L, savedStatusTarget(state, search, action))
        assertNull(savedStatusTarget(state.copy(savedPage = state.savedPage!!.copy(
            rows = listOf(person.copy(card = fixtureCard(9L, 1L))))), search, action))
        assertNull(savedStatusTarget(state.copy(actionSurface = ProfileActionSurface.NONE), search, action))
        assertNull(savedStatusTarget(state.copy(actionGeneration = 43), search, action))
        assertNull(savedStatusTarget(state, search.copy(profileOpen = true), action))
        val cardless = row()
        val cardlessState = displayed(cardless)
        val cardlessAction = action.copy(row = cardless)
        assertTrue(savedActionMatches(cardlessState, search, cardlessAction))
        assertNull(savedStatusTarget(cardlessState, search, cardlessAction))
    }

    @Test fun independentWindowSizesSurviveTabAndRetry() {
        val memory = SavedWindowMemory()
        val favorite = SavedPersonKind.FAVORITE
        val like = SavedPersonKind.LIKE
        val date = SavedPersonSort.DATE
        memory.requestMore(favorite, date, false, 50)
        memory.requestMore(favorite, date, true, 50)
        memory.requestMore(favorite, date, false, 50)
        assertEquals(100u to 100u, memory.limits(favorite, date))
        assertEquals(50u to 100u, memory.limits(like, date))
        assertEquals(50u to 100u, memory.limits(favorite, SavedPersonSort.NAME))
    }

    @Test fun partialStatusBatchProjectsCommittedWritesAfterCancelAndReadFailure() = runBlocking {
        val visited = mutableListOf<Long>()
        val committed = mutableListOf<Long>()
        var cancelled = false
        var projection = 0
        var savedSearchProjection = 0
        var progress = Triple(0, 0, 0)
        val outcome = writeAndProject(
            write = {
                runSavedStatusBatch(
                    targets = listOf(1L, 2L, 3L, 4L),
                    cancelled = { cancelled },
                    check = {
                        visited += it
                        if (it == 2L) error("timeout")
                        committed += it
                        if (it == 3L) cancelled = true
                    },
                    publish = { total, done, failed, _ -> progress = Triple(total, done, failed) },
                    pause = {},
                )
            },
            afterWrite = { savedSearchProjection++ },
            recoverWriteFailure = { savedSearchProjection++ },
            project = { projection++; error("read failed") },
        )
        assertEquals(listOf(1L, 2L, 3L), visited)
        assertEquals(listOf(1L, 3L), committed)
        assertEquals(Triple(4, 3, 1), progress)
        assertEquals(1, projection)
        assertEquals(1, savedSearchProjection)
        assertEquals(DiscoveryMutationOutcome.ProjectionFailed(true), outcome)
    }

    @Test fun failedStatusTargetReadReconcilesOnceWithoutRepeatingTheRead() = runBlocking {
        val calls = mutableListOf<String>()
        val outcome = writeAndProject(
            write = { calls += "targets"; error("target read failed") },
            afterWrite = { calls += "unexpected success" },
            recoverWriteFailure = { calls += "saved search"; calls += "map" },
            project = { calls += "feed" },
        )
        assertTrue(outcome is DiscoveryMutationOutcome.WriteFailed)
        assertEquals(listOf("targets", "saved search", "map", "feed"), calls)
    }
}
