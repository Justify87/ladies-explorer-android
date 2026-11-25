package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.Preference
import de.justify87.ladiesexplorer.core.SearchPreview
import de.justify87.ladiesexplorer.core.SearchRequest
import de.justify87.ladiesexplorer.core.SearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import de.justify87.ladiesexplorer.core.DiscoveryCursor
import de.justify87.ladiesexplorer.core.DiscoveryPage
import de.justify87.ladiesexplorer.core.SourceFilterCatalog
import de.justify87.ladiesexplorer.core.FrequentFilter
import kotlinx.coroutines.flow.MutableStateFlow

class SearchRunStateTest {
    @Test fun localFilterSearchFindsRenderedMarkerLabelsAndExistingAliases() {
        listOf("", "Ohne KI-Markierung", "ki-markiert", "Markierung",
            "Bilddarstellung und lokale Filter", "Nur mit Bildern", "Nur mit Video",
            "Dislikes ausblenden", "Likes ausblenden", "Fotos", "Natürlich", "FaceApp").forEach {
            assertTrue("Missing local filters for '$it'", matchesLocalFilterQuery(it))
        }
        assertFalse(matchesLocalFilterQuery("Haarfarbe"))
    }

    private fun response(id: Long, partial: Boolean = false) = SearchResponse(
        query = SearchRequest("Leipzig", 20u, "", 18u, 60u, 140u, 200u, "{}"),
        local = defaultLocalVisibility(),
        searchRunId = id,
        city = "Leipzig",
        requestedRadiusKm = 20u,
        serverCount = 1u,
        totalPages = 1u,
        fetchedPages = 1u,
        partial = partial,
        radiusBoundaryStopped = false,
        radiusVerified = !partial,
        profiles = emptyList(),
    )

    private fun card(id: Long) = fixtureCard(id)

    @Test fun profileMatchContextUsesExecutedRunAndNotDraftOrUnrelatedProfile() {
        val run = response(1).copy(local = defaultLocalVisibility().copy(videoOnly = true))
        val old = SearchUiState().acceptRun(run, listOf(card(1)))
        val changed = old.applyDraftChange(old.copy(city = "Dresden", local = old.local.copy(videoOnly = false)))
        val key = executedProfileMatchContext(changed.response, 7L)
        assertEquals(ProfileMatchContext.Run(1L, 7L, run.local), key)
        assertEquals(key, executedProfileMatchContext(old.response, 7L))
        assertFalse(key == executedProfileMatchContext(response(2), 7L))
        assertFalse(key == executedProfileMatchContext(old.response, 8L))
        assertEquals(null, executedProfileMatchContext(null, 7L))
        assertEquals(null, executedProfileMatchContext(run, null))
        assertEquals(null, profileMatchContextForOwner(null, 7L))
        assertEquals(null, profileMatchContextForOwner(key, null))
        assertEquals(ProfileMatchContext.Run(1L, 8L, run.local), profileMatchContextForOwner(key, 8L))
        val saved = ProfileMatchContext.Saved("fixture", 7L)
        assertEquals(ProfileMatchContext.Saved("fixture", 8L), profileMatchContextForOwner(saved, 8L))
    }

    @Test fun draftChangesAndCancellationKeepExecutedResult() {
        val old = SearchUiState().acceptRun(response(1), listOf(card(1)))
        val changed = old.applyDraftChange(old.copy(city = "Dresden", radiusKm = 50u))
        assertEquals(1L, changed.response?.searchRunId)
        assertEquals("Leipzig", changed.response?.city)
        assertEquals("Dresden", changed.city)
        assertEquals(old.activeCards, changed.activeCards)
        val pending = changed.beginRun()
        assertTrue(pending.loading)
        val cancelled = pending.endRun()
        assertFalse(cancelled.loading)
        assertEquals(old.response, cancelled.response)
        assertEquals(old.activeCards, cancelled.activeCards)
        assertEquals(old.response, pending.endRun("source failure").response)
    }

    @Test fun previewTargetUsesSourceDraftAndCurrentEditorSessionOnly() {
        val search = SearchUiState(city = " Dresden ")
        val saved = SavedSearchUiState()
        val normal = searchPreviewTarget(search, saved)!!
        assertEquals("Dresden", normal.draft.city)
        assertEquals(null, normal.editorSession)
        assertEquals(normal, searchPreviewTarget(search.copy(local = search.local.copy(hideLikes = true)), saved))
        assertFalse(normal == searchPreviewTarget(search.copy(radiusKm = 50u), saved))
        assertEquals(null, searchPreviewTarget(search.beginRun(), saved))
        assertEquals(null, searchPreviewTarget(search.copy(profileOpen = true), saved))
        assertEquals(null, searchPreviewTarget(search, saved.copy(overview = true)))
        val editor = SavedEditor("fixture", 7L, "Test", SearchDraft.from(search))
        val editing = saved.copy(editor = editor)
        val editorTarget = searchPreviewTarget(search, editing)!!
        assertEquals(7L, editorTarget.editorSession)
        assertEquals(normal.draft, editorTarget.draft)
        assertFalse(normal == editorTarget)
        assertFalse(editorTarget == searchPreviewTarget(search, editing.copy(editor = editor.copy(session = 8L))))
        assertEquals(null, searchPreviewTarget(search, editing.copy(operation = SavedOperation.Write)))
    }

    @Test fun sourcePreviewAndLastLocalCountAreNeverConfusedWithAnUnknownNewDraft() {
        val state = SearchUiState().acceptRun(response(1), listOf(card(1)))
        val draft = SearchDraft.from(state)
        val preview = state.copy(preview = SearchPreview(20u, true))
        assertEquals("20 laut Quelle", searchCountLabel(preview, draft, true))
        assertEquals("1 zuletzt lokal", searchCountLabel(preview, draft, false))
        assertEquals("1 zuletzt lokal", searchCountLabel(state, draft, true))
        val changed = draft.copy(city = "Dresden")
        assertEquals("Quellzahl unbekannt", searchCountLabel(preview, changed, false))
        assertEquals("Quellzahl unbekannt", searchCountLabel(state, draft, true, allowLastRun = false))
        val unverified = preview.copy(preview = SearchPreview(0u, false))
        assertEquals("Quellzahl unbekannt", searchCountLabel(unverified, changed, true))
        val loading = state.copy(previewLoading = true)
        assertEquals("Quellzahl wird geprüft", searchCountLabel(loading, changed, true))
        val zero = state.copy(preview = SearchPreview(0u, true))
        assertEquals("0 laut Quelle", searchCountLabel(zero, changed, true))
    }

    @Test fun lastLocalCountRequiresEquivalentSourceAndLocalFilters() {
        val run = response(1).let {
            it.copy(query = it.query.copy(filtersJson = """{"haarfarbe":["Braun","Schwarz"],"figur":["schlank"]}"""))
        }
        val state = SearchUiState().acceptRun(run, listOf(card(1)))
        val draft = SearchDraft.from(state).copy(selectedFilters = mapOf(
            "figur" to setOf("schlank"), "haarfarbe" to setOf("Schwarz", "Braun"),
        ))
        assertEquals("1 zuletzt lokal", searchCountLabel(state, draft, false))
        assertEquals("Quellzahl unbekannt", searchCountLabel(state, draft.toggle("haarfarbe", "Braun"), false))
        assertEquals("Quellzahl unbekannt", searchCountLabel(state, draft.copy(local = draft.local.copy(hideLikes = true)), false))
    }

    @Test fun latePreviewReleaseCannotCancelReplacementWithIdenticalTarget() {
        val target = searchPreviewTarget(SearchUiState(), SavedSearchUiState())!!
        val owner = SearchPreviewOwner()
        val first = SearchPreviewLease(target)
        val replacement = SearchPreviewLease(target)
        owner.replace(first)
        assertTrue(owner.accepts(first))
        owner.replace(replacement)
        assertFalse(owner.accepts(first))
        assertFalse(owner.release(first))
        assertTrue(owner.accepts(replacement))
        assertTrue(owner.release(replacement))
        assertFalse(owner.accepts(replacement))
        owner.replace(first)
        owner.clear()
        assertFalse(owner.accepts(first))
        assertFalse(owner.release(first))
    }

    @Test fun normalDraftEditsRequireCurrentFilterContextButAllowSequentialInput() {
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.SEARCH, actionGeneration = 7)
        val saved = SavedSearchUiState(actionGeneration = 4)
        val search = SearchUiState(city = "Dresden")
        val action = SearchDraftEditAction(7, 4)
        assertTrue(searchDraftEditMatches(discovery, search, saved, action))
        assertTrue(searchDraftEditMatches(discovery, search.copy(city = "Berlin", radiusKm = 50u), saved, action))
        assertFalse(searchDraftEditMatches(discovery.copy(actionSurface = ProfileActionSurface.NONE), search, saved, action))
        assertFalse(searchDraftEditMatches(discovery.copy(actionSurface = ProfileActionSurface.GRID), search, saved, action))
        assertFalse(searchDraftEditMatches(discovery.copy(actionGeneration = 8), search, saved, action))
        assertFalse(searchDraftEditMatches(discovery, search, saved.copy(overview = true), action))
        assertFalse(searchDraftEditMatches(discovery, search, saved.copy(actionGeneration = 5), action))
        assertFalse(searchDraftEditMatches(discovery, search, saved.copy(operation = SavedOperation.Write), action))
        assertFalse(searchDraftEditMatches(discovery, search.beginRun(), saved, action))
        assertFalse(searchDraftEditMatches(discovery, search.copy(profileOpen = true), saved, action))
        assertFalse(searchDraftEditMatches(discovery, search.copy(actionBusy = true), saved, action))
        assertFalse(searchDraftEditMatches(discovery, search.copy(defaultReady = false), saved, action))
        assertFalse(searchDraftEditMatches(discovery, search.copy(defaultSaving = true), saved, action))
    }

    @Test fun resetRequiresRenderedDraftAndRevokesOldEditContextWithoutChangingRun() {
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.SEARCH, actionGeneration = 7)
        val saved = SavedSearchUiState(actionGeneration = 4)
        val search = SearchUiState(city = "Dresden").acceptRun(response(1), listOf(card(1)))
        val action = SearchDraftEditAction(7, 4)
        val rendered = SearchDraft.from(search)
        assertTrue(searchDraftResetMatches(discovery, search, saved, action, rendered))
        assertFalse(searchDraftResetMatches(discovery, search.copy(city = "Berlin"), saved, action, rendered))
        val blank = search.copy(city = "")
        assertTrue(searchDraftResetMatches(discovery, blank, saved, action, SearchDraft.from(blank)))
        val standard = SearchDraft(city = "Halle", radiusKm = 50u, selectedFilters = mapOf("haarfarbe" to setOf("Braun")),
            local = defaultLocalVisibility().copy(hideLikes = true))
        val reset = search.applyDraftChange(standard.applyTo(search))
        val next = discovery.copy(actionGeneration = 8)
        assertEquals(standard, SearchDraft.from(reset))
        assertEquals(search.response, reset.response)
        assertEquals(search.activeCards, reset.activeCards)
        assertFalse(searchDraftEditMatches(next, reset, saved, action))
        assertTrue(searchDraftEditMatches(next, reset, saved, action.copy(generation = 8)))
    }

    @Test fun submitSearchRejectsOldDraftSurfacePaneAndBusyState() {
        val search = SearchUiState(city = "Dresden")
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.SEARCH, actionGeneration = 7)
        val saved = SavedSearchUiState()
        val action = SearchSubmitAction(SearchDraft.from(search), 7)
        assertTrue(searchSubmitMatches(discovery, search, saved, action))
        assertFalse(searchSubmitMatches(discovery, search.copy(city = "Berlin"), saved, action))
        assertFalse(searchSubmitMatches(discovery, search.copy(local = search.local.copy(hideLikes = true)), saved, action))
        assertFalse(searchSubmitMatches(discovery.copy(actionSurface = ProfileActionSurface.GRID), search, saved, action))
        assertFalse(searchSubmitMatches(discovery.copy(actionGeneration = 8), search, saved, action))
        assertFalse(searchSubmitMatches(discovery.copy(busy = true), search, saved, action))
        assertFalse(searchSubmitMatches(discovery, search, saved.copy(overview = true), action))
        assertFalse(searchSubmitMatches(discovery, search, saved.copy(operation = SavedOperation.Write), action))
        assertFalse(searchSubmitMatches(discovery, search.copy(profileOpen = true), saved, action))
        assertFalse(searchSubmitMatches(discovery, search.copy(actionBusy = true), saved, action))
        assertFalse(searchSubmitMatches(discovery, search.beginRun(), saved, action))
        val blank = search.copy(city = " ")
        assertFalse(searchSubmitMatches(discovery, blank, saved, SearchSubmitAction(SearchDraft.from(blank), 7)))
    }

    @Test fun submitSearchAfterFailureOrCancelRequiresNewRenderedGeneration() {
        val search = SearchUiState(city = "Dresden").acceptRun(response(1), listOf(card(1)))
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.SEARCH, actionGeneration = 7)
        val saved = SavedSearchUiState()
        val action = SearchSubmitAction(SearchDraft.from(search), 7)
        val next = discovery.copy(actionGeneration = 8)
        for (state in listOf(search.beginRun().endRun(), search.beginRun().endRun("offline"))) {
            assertEquals(search.response, state.response)
            assertFalse(searchSubmitMatches(next, state, saved, action))
            assertTrue(searchSubmitMatches(next, state, saved, action.copy(generation = 8)))
        }
    }

    @Test fun searchCancelRequiresCurrentRenderedSurfaceAndSwipeSession() {
        val search = SearchUiState().beginRun()
        val saved = SavedSearchUiState()
        val grid = DiscoverUiState(actionSurface = ProfileActionSurface.GRID, actionGeneration = 7)
        val gridAction = SearchCancelAction(ProfileActionSurface.GRID, 7, search.searchAttemptId)
        assertTrue(searchCancelMatches(grid, search, saved, false, gridAction))
        assertFalse(searchCancelMatches(grid, search.endRun(), saved, false, gridAction))
        assertFalse(searchCancelMatches(grid, search.copy(searchCancelling = true), saved, false, gridAction))
        assertFalse(searchCancelMatches(grid, search.copy(profileOpen = true), saved, false, gridAction))
        val filters = grid.copy(actionSurface = ProfileActionSurface.SEARCH, actionGeneration = 8)
        val filterAction = SearchCancelAction(ProfileActionSurface.SEARCH, 8, search.searchAttemptId)
        assertFalse(searchCancelMatches(filters, search, saved, false, gridAction))
        assertTrue(searchCancelMatches(filters, search, saved, false, filterAction))
        assertFalse(searchCancelMatches(filters, search, saved.copy(overview = true), false, filterAction))
        val swipe = grid.copy(actionSurface = ProfileActionSurface.NONE, actionGeneration = 9, swipeSessionId = 12)
        val swipeAction = SearchCancelAction(ProfileActionSurface.NONE, 9, search.searchAttemptId, 12)
        assertTrue(searchCancelMatches(swipe, search, saved, true, swipeAction))
        assertFalse(searchCancelMatches(swipe, search, saved, false, swipeAction))
        assertFalse(searchCancelMatches(swipe.copy(swipeSessionId = 13), search, saved, true, swipeAction))
        assertFalse(searchCancelMatches(swipe, search, saved, true, swipeAction.copy(swipeSession = null)))
        val map = grid.copy(actionSurface = ProfileActionSurface.MAP, actionGeneration = 10)
        val mapAction = SearchCancelAction(ProfileActionSurface.MAP, 10, search.searchAttemptId)
        assertTrue(searchCancelMatches(map, search, saved, false, mapAction))
        assertFalse(searchCancelMatches(grid, search, saved, false, mapAction))
        assertFalse(searchCancelMatches(map, search, saved, false, gridAction))
        assertFalse(searchCancelMatches(map.copy(actionGeneration = 11), search, saved, false, mapAction))
        assertFalse(searchCancelMatches(map, search.copy(searchCancelling = true), saved, false, mapAction))
    }

    @Test fun searchCancelCannotSignalReplacementOwnerAfterFailureOrDrain() {
        val owner = DrainingOperation<String>()
        val saved = SavedSearchUiState()
        val previous = SearchUiState().acceptRun(response(1), listOf(card(1)))
        val first = owner.start("first")!!
        val discovery = DiscoverUiState(actionSurface = ProfileActionSurface.SEARCH, actionGeneration = 7)
        val oldAction = SearchCancelAction(ProfileActionSurface.SEARCH, 7, first)
        assertTrue(searchCancelMatches(discovery, previous.beginRun(first), saved, false, oldAction))
        assertTrue(owner.finish(first))
        val replacement = owner.start("replacement")!!
        val current = discovery.copy(actionGeneration = 8)
        for (state in listOf(previous.beginRun().endRun(), previous.beginRun().endRun("offline"))) {
            val loading = state.beginRun(replacement)
            assertFalse(searchCancelMatches(current, loading, saved, false, oldAction))
            // A new surface snapshot paired with the old search snapshot must not authorize the replacement.
            assertFalse(searchCancelMatches(current, loading, saved, false, oldAction.copy(generation = 8)))
            assertTrue(owner.accepts(replacement))
            assertTrue(searchCancelMatches(current, loading, saved, false, oldAction.copy(generation = 8, attemptId = replacement)))
            assertEquals(previous.response, loading.response)
        }
        var signals = 0
        owner.cancel { signals++ }
        owner.cancel { signals++ }
        assertEquals(1, signals)
        assertTrue(owner.occupied)
        assertFalse(owner.accepts(replacement))
        assertEquals(null, owner.start("too early"))
        assertTrue(owner.finish(replacement))
    }

    @Test fun partialRunReplacesPreviousResultsWithoutInventingRadiusVerification() {
        val old = SearchUiState().acceptRun(response(1), listOf(card(1))).beginRun()
        val partial = response(2, partial = true)
        val state = old.acceptRun(partial, listOf(card(2)))
        assertEquals(partial, state.response)
        assertEquals(listOf(card(2)), state.activeCards)
        assertFalse(state.loading)
        assertFalse(state.searchCancelling)
    }

    @Test fun gridRefreshRequiresCurrentRenderedRunAndIdleVisibleSurface() {
        val grid = DiscoverUiState(actionSurface = ProfileActionSurface.GRID, actionGeneration = 7, loading = false)
        val local = SearchUiState()
        val action = GridRefreshAction(null, 7)
        assertTrue(gridRefreshMatches(grid, local, action))
        assertFalse(gridRefreshMatches(grid.copy(actionSurface = ProfileActionSurface.NONE), local, action))
        assertFalse(gridRefreshMatches(grid.copy(actionSurface = ProfileActionSurface.SAVED), local, action))
        assertFalse(gridRefreshMatches(grid.copy(actionGeneration = 8), local, action))
        assertFalse(gridRefreshMatches(grid.copy(busy = true), local, action))
        assertFalse(gridRefreshMatches(grid.copy(loading = true), local, action))
        assertFalse(gridRefreshMatches(grid, local.copy(profileOpen = true), action))
        assertFalse(gridRefreshMatches(grid, local.copy(actionBusy = true), action))
        assertFalse(gridRefreshMatches(grid, local.beginRun(), action))
        assertTrue(gridRefreshMatches(grid.copy(loadingMore = true), local, action))

        val active = local.acceptRun(response(1), listOf(card(1)))
        val runAction = GridRefreshAction(1, 7)
        assertTrue(gridRefreshMatches(grid, active, runAction))
        assertFalse(gridRefreshMatches(grid, active, action))
        assertFalse(gridRefreshMatches(grid, local, runAction))
        assertFalse(gridRefreshMatches(grid, active.acceptRun(response(2), listOf(card(2))), runAction))
        // The failed projection must remain retryable, but old callbacks must not.
        val failed = grid.copy(actionGeneration = 8, error = "read failed")
        assertFalse(gridRefreshMatches(failed, active, runAction))
        assertTrue(gridRefreshMatches(failed, active, runAction.copy(generation = 8)))
    }

    @Test fun activeGridIsNotTheConsumedSwipeQueue() {
        val rows = listOf(card(1), card(2), card(3).copy(preference = Preference.LIKE))
        val search = SearchUiState().acceptRun(response(1), rows)
        val swipe = SwipeSession().apply {
            visit(SwipeStep(1, 1))
            replayLikes = false
        }
        val discovery = DiscoverUiState(cards = swipe.queue(rows, true), activeRunId = 1)
        assertEquals(listOf(2L), discovery.cards.map { it.listingId })
        assertEquals(rows, gridCards(discovery, search))
        // Neither a draft edit nor a cancelled replacement changes the grid's source.
        assertEquals(rows, gridCards(discovery, search.beginRun().endRun()))
        assertTrue(gridCards(discovery, SearchUiState()).isEmpty())
        val local = discovery.copy(activeRunId = null, localCards = rows)
        assertEquals(rows, gridCards(local, SearchUiState()))
        assertEquals(rows, discoveryMembership(local, SearchUiState()))
    }

    @Test fun gridPreferenceKeepsSharedMembershipAndUndoAdmission() {
        val rows = listOf(card(1), card(2).copy(preference = Preference.DISLIKE, favorite = true))
        val search = SearchUiState().acceptRun(response(1), rows)
        val receipt = GridUndoReceipt(1L, 2L, rows[1].personId, Preference.NONE, Preference.DISLIKE)
        val grid = DiscoverUiState(activeRunId = 1L, actionSurface = ProfileActionSurface.GRID,
            actionGeneration = 7, gridUndo = receipt, loading = false)
        val undo = GridUndoAction(receipt, 7)
        assertEquals(rows, gridCards(grid, search))
        assertEquals(rows, currentMapMembership(grid, search)?.cards)
        assertTrue(gridUndoMatches(grid, search, undo))
        assertFalse(gridUndoMatches(grid.copy(actionGeneration = 8), search, undo))
        assertFalse(gridUndoMatches(grid.copy(gridUndo = null), search, undo))
        assertFalse(gridUndoMatches(grid.copy(busy = true), search, undo))
        assertFalse(gridUndoMatches(grid.copy(actionSurface = ProfileActionSurface.MAP), search, undo))
        assertFalse(gridUndoMatches(grid, search.acceptRun(response(2), rows), undo))
        assertEquals("Unbewertet", gridPreferenceLabel(Preference.NONE))
        assertEquals("Gefällt", gridPreferenceLabel(Preference.LIKE))
        assertEquals("Gefällt nicht", gridPreferenceLabel(Preference.DISLIKE))
    }

    @Test fun gridLikeRejectsReplacedRowRunAndNavigationAndNeverReinterpretsClick() {
        val row = card(2).copy(preference = Preference.DISLIKE)
        val deck = DiscoverUiState(cards = listOf(row), localCards = listOf(row), actionSurface = ProfileActionSurface.GRID,
            actionGeneration = 15, loading = false)
        val local = SearchUiState()
        val action = GridLikeAction(row, null, 15)
        assertTrue(gridActionMatches(deck, local, action))
        assertFalse(gridActionMatches(deck.copy(localCards = listOf(row.copy(preference = Preference.LIKE))), local, action))
        assertFalse(gridActionMatches(deck.copy(localCards = listOf(row.copy(personId = 99L))), local, action))
        assertFalse(gridActionMatches(deck.copy(actionGeneration = 16), local, action))
        assertFalse(gridActionMatches(deck.copy(actionSurface = ProfileActionSurface.SAVED), local, action))
        assertFalse(gridActionMatches(deck.copy(busy = true), local, action))
        assertFalse(gridActionMatches(deck, local.copy(profileOpen = true), action))
        val running = local.acceptRun(response(1), listOf(row))
        val runAction = GridLikeAction(row, 1, 15)
        val activeDeck = deck.copy(activeRunId = 1)
        assertFalse(gridActionMatches(deck, running, runAction))
        assertTrue(gridActionMatches(activeDeck, running, runAction))
        assertFalse(gridActionMatches(activeDeck, running.acceptRun(response(2), listOf(row)), runAction))
        assertFalse(gridActionMatches(deck, running, action))
    }

    @Test fun reprojectKeepsLoadedWindowAndUpdatedCursor() = runBlocking {
        val next = DiscoveryCursor(false, "2026-01-01", 2)
        val end = DiscoveryCursor(false, "2026-01-01", 4)
        val seen = mutableListOf<DiscoveryCursor?>()
        val result = readDiscoveryWindow(4) { cursor ->
            seen += cursor
            if (cursor == null) DiscoveryPage(listOf(card(1), card(2)), next)
            else DiscoveryPage(listOf(card(3), card(4).copy(favorite = true)), end)
        }
        assertEquals(listOf(null, next), seen)
        assertEquals(listOf(1L, 2L, 3L, 4L), result.cards.map { it.listingId })
        assertEquals(end, result.nextCursor)
        assertTrue(result.cards.last().favorite)
    }

    @Test fun gridDecisionAndUndoUseExpectedPreferenceWithoutDeckVisit() = runBlocking {
        val old = card(2).copy(favorite = true)
        var stored = old
        val swipe = SwipeSession().apply { visit(SwipeStep(1, 1)) }
        val step = swipe.last
        var search = SearchUiState().acceptRun(response(1), listOf(card(1), old))
        var discovery = DiscoverUiState(cards = swipe.queue(search.activeCards, true), activeRunId = 1)
        val project: suspend () -> Unit = {
            search = search.copy(activeCards = listOf(card(1), stored))
            discovery = discovery.copy(cards = swipe.queue(search.activeCards, true))
        }
        val write: suspend (Long, Long, Preference?, Preference) -> Preference = { listing, owner, expected, target ->
            check(listing == stored.listingId && owner == stored.personId && expected == stored.preference)
            val before = stored.preference
            stored = stored.copy(preference = target)
            before
        }
        for (target in listOf(Preference.LIKE, Preference.DISLIKE)) {
            val result = writeAndProject(
                write = { write(old.listingId, old.personId, Preference.NONE, target) },
                project = project,
            )
            assertEquals(DiscoveryMutationOutcome.Success, result)
            assertEquals(target, gridCards(discovery, search).last().preference)
            assertTrue(gridCards(discovery, search).last().favorite)
            assertEquals(step, swipe.last)
            val receipt = GridUndoReceipt(1L, old.listingId, old.personId, Preference.NONE, target)
            val undo = writeAndProject(write = {
                undoSwipeStep(receipt.step(), write, { old.personId }, {})
            }, project = project)
            assertEquals(DiscoveryMutationOutcome.Success, undo)
            assertEquals(old, gridCards(discovery, search).last())
            assertEquals(step, swipe.last)
        }
        val failed = writeAndProject(
            write = { write(old.listingId, old.personId + 1, Preference.NONE, Preference.LIKE) },
            project = project,
        )
        assertTrue(failed is DiscoveryMutationOutcome.WriteFailed)
        assertEquals(old, gridCards(discovery, search).last())
        assertEquals(step, swipe.last)
    }

    @Test fun reprojectStopsAtEndEvenWhenRequestedWindowIsLarger() = runBlocking {
        var calls = 0
        val result = readDiscoveryWindow(500) {
            calls++
            DiscoveryPage(listOf(card(1)), null)
        }
        assertEquals(1, calls)
        assertEquals(null, result.nextCursor)
        assertEquals(1, result.cards.size)
    }

    @Test fun paginationDoesNotFireBeforeLayoutOrUntilNearEnd() {
        assertFalse(nearDiscoveryEnd(-1, 0))
        assertFalse(nearDiscoveryEnd(1, 50))
        assertTrue(nearDiscoveryEnd(47, 50))
    }

    @Test fun staticCatalogIsReusedWhileUsageCountsRefreshAndErrorsRecover() = runBlocking {
        val state = MutableStateFlow(SearchUiState())
        val catalog = SourceFilterCatalog(emptyList(), emptyList())
        var catalogReads = 0
        var frequentReads = 0
        var fail = true
        var current = true
        suspend fun refresh() = refreshSearchFilters(state, { current },
            readCatalog = { catalogReads++; catalog },
            readFrequent = {
                frequentReads++
                if (fail) error("usage failed")
                listOf(FrequentFilter("category", "fixture", frequentReads.toUInt()))
            })
        refresh()
        assertEquals(null, state.value.filterCatalog)
        assertEquals("usage failed", state.value.filterError)
        fail = false
        refresh()
        assertEquals(catalog, state.value.filterCatalog)
        assertEquals(null, state.value.filterError)
        refresh()
        assertEquals(2, catalogReads)
        assertEquals(3, frequentReads)
        assertEquals(3u, state.value.frequentFilters.single().count)
        fail = true
        refresh()
        assertEquals(catalog, state.value.filterCatalog)
        assertEquals("usage failed", state.value.filterError)
        fail = false
        current = false
        refresh()
        assertEquals("usage failed", state.value.filterError)
        assertEquals(3u, state.value.frequentFilters.single().count)
        current = true
        refresh()
        assertEquals(2, catalogReads)
        assertEquals(6, frequentReads)
        assertEquals(6u, state.value.frequentFilters.single().count)
        assertEquals(null, state.value.filterError)
    }

    @Test fun generationsForPreviewAndFullSearchAreIndependent() {
        val preview = RequestGeneration()
        val search = RequestGeneration()
        val firstPreview = preview.value
        val firstSearch = search.value
        preview.invalidate()
        assertFalse(preview.isCurrent(firstPreview))
        assertTrue(preview.isCurrent(preview.value))
        assertTrue(search.isCurrent(firstSearch))
        search.invalidate()
        assertFalse(search.isCurrent(firstSearch))
        assertTrue(search.isCurrent(search.value))
    }

    @Test fun cancelledNativeOwnerCannotBeReplacedUntilDrain() {
        val owner = DrainingOperation<String>()
        val first = owner.start("source")!!
        var signalled = 0
        owner.cancel { signalled++ }
        owner.cancel { signalled++ }
        assertEquals(1, signalled)
        assertTrue(owner.occupied)
        assertFalse(owner.accepts(first))
        assertEquals(null, owner.start("replacement search"))
        assertTrue(owner.finish(first))
        val second = owner.start("replacement search")!!
        assertFalse(owner.finish(first))
        assertFalse(owner.accepts(first))
        assertTrue(owner.accepts(second))
        assertTrue(owner.finish(second))
        assertFalse(owner.occupied)
    }
}
