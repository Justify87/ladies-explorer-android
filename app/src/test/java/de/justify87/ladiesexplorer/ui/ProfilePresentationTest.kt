package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfilePresentationTest {
    @Test fun gapCannotReplaceDetailAnchorWithFallbackLayout() {
        val memory = ProfileScrollMemory()
        memory.observe("event/20", 36)
        memory.gap()
        memory.observe("hero", 0)
        val rows = ProfileFlowRows(ProfileMediaSelection()).apply {
            item("hero") {}
            item("profile-facts") {}
            item("profile-history-head") {}
            item("event/20") {}
        }.build()
        assertEquals(3 to 36, memory.target(rows.map { it.key }))
        memory.restored()
        memory.observe("profile-facts", 8)
        assertEquals(ProfileScrollAnchor("profile-facts", 8), memory.anchor)
    }

    @Test fun directRevisionChangeFreezesAnchorJustLikeAGap() {
        val key = ProfileProjectionKey(4L, 1L, 11L, 2L, "{}")
        val memory = ProfileScrollMemory()
        memory.projectionChanged(key)
        memory.observe("listing/9", 22)
        memory.projectionChanged(key.copy(revision = 3L))
        assertTrue(memory.recovering)
        assertEquals(ProfileRowRead.Detail, memory.dependency(ProfileFlowRows(ProfileMediaSelection()).apply {
            item("hero") {}
            item("profile-listings-head", ProfileRowRead.Detail) {}
        }.build()))
        memory.observe("profile-facts", 0)
        assertEquals(2 to 22, memory.target(listOf("hero", "profile-listings-head", "listing/9")))
        memory.restored()
        memory.projectionChanged(key.copy(revision = 3L))
        assertFalse(memory.recovering)
        val newOwner = ProfileScrollMemory(initialRecovery = true)
        assertEquals(0 to 0, newOwner.target(listOf("hero", "profile-facts")))
    }

    @Test fun factsErrorParksLogicalAnchorUntilRetryOrDeliberateScroll() {
        val original = ProfileScrollAnchor("profile-services/group/7:Fixture/0", 12)
        val memory = ProfileScrollMemory(original, initialRecovery = true)
        val placeholder = ProfileFlowRows(ProfileMediaSelection()).apply {
            item("hero") {}
            item("profile-facts", ProfileRowRead.Facts) {}
        }.build()
        assertEquals(ProfileRowRead.Facts, memory.dependency(placeholder))
        assertEquals(1 to 0, memory.target(placeholder.map { it.key }, readFailed = true))
        memory.parkFactsFailure()
        memory.observe("profile-facts", 0)
        assertTrue(memory.parkedOnFactsError)
        assertEquals(original, memory.anchor)
        assertNull(memory.target(placeholder.map { it.key }))
        memory.resumeFactsRetry()
        assertEquals(ProfileRowRead.Facts, memory.dependency(placeholder))
        assertEquals(2 to 12, memory.target(listOf("hero", "profile-services", original.key)))
        assertEquals(1 to 0, memory.target(listOf("hero", "profile-services")))
        assertEquals(1 to 0, memory.target(listOf("hero", "profile-heading")))
        memory.parkFactsFailure()
        memory.observe("hero", 5)
        memory.resumeFactsRetry()
        assertFalse(memory.recovering)
        assertFalse(memory.parkedOnFactsError)
        assertEquals(ProfileScrollAnchor("hero", 5), memory.anchor)
    }

    @Test fun sameOwnerGapWaitsForFactsRatherThanRestoringOffsetIntoSpinner() {
        val memory = ProfileScrollMemory()
        memory.projectionChanged(ProfileProjectionKey(4L, 1L, 11L, 2L, "{}"))
        memory.observe("profile-services", 60)
        memory.projectionChanged(null)
        assertEquals(ProfileRowRead.Facts, memory.dependency(ProfileFlowRows(ProfileMediaSelection()).apply {
            item("profile-services", ProfileRowRead.Facts) {}
        }.build()))
        assertEquals(1 to 60, memory.target(listOf("hero", "profile-services")))
        assertEquals(1 to 0, memory.target(listOf("hero", "profile-services"), readFailed = true))
        assertEquals(19, restoredRowOffset(60, 20))
        assertEquals(0, restoredRowOffset(60, 0))
        assertEquals(8, restoredRowOffset(8, 100))
    }

    @Test fun missingAnchorFallsBackToItsSectionAndListingRequestsDetail() {
        val memory = ProfileScrollMemory()
        memory.observe("listing/9", 22)
        memory.gap()
        val rows = ProfileFlowRows(ProfileMediaSelection()).apply {
            item("hero") {}
            item("profile-listings-head", ProfileRowRead.Detail) {}
        }.build()
        assertEquals(ProfileRowRead.Detail, memory.dependency(rows))
        assertEquals(1 to 0, memory.target(listOf("hero", "profile-listings-head")))
        assertEquals(2 to 22, memory.target(listOf("hero", "profile-listings-head", "listing/9")))
        memory.restored()
        assertNull(memory.dependency(rows))
    }

    @Test fun mediaSelectionSurvivesMissingProjectionAndReordering() {
        val a = ProfileMediaItem("IMAGE", "a", null, 0u)
        val b = ProfileMediaItem("IMAGE", "b", null, 1u)
        val selection = ProfileMediaSelection()
        selection.select(listOf(a, b), 1)
        assertEquals(0, selection.resolve(listOf(a), false))
        assertEquals(1, selection.index)
        assertEquals(0, selection.resolve(listOf(b, a), true))
        assertEquals(0, selection.resolve(listOf(a), true))
    }

    @Test fun undoReferenceRestoresStableMediaAndRowWithSafeFallbacks() {
        val position = SwipePresentation(4, 1, 11, "{}", "IMAGE:b", "profile-services", 60,
            listOf("Services"), mediaIndex = 7)
        val a = ProfileMediaItem("IMAGE", "a", null, 0u)
        val b = ProfileMediaItem("IMAGE", "b", null, 1u)
        val selection = ProfileMediaSelection(position.mediaIndex, position.mediaId)
        assertEquals(0, selection.resolve(listOf(b, a), true))
        assertEquals(0, selection.resolve(listOf(a), true))
        assertEquals(0, selection.resolve(emptyList(), true))
        assertTrue(ProfileSections(position.sections).entries.isEmpty())
        val memory = ProfileScrollMemory(ProfileScrollAnchor(requireNotNull(position.anchorKey), position.anchorOffset),
            initialRecovery = true)
        assertEquals(ProfileRowRead.Facts, memory.dependency(ProfileFlowRows(ProfileMediaSelection()).apply {
            item("profile-services", ProfileRowRead.Facts) {}
        }.build()))
        assertEquals(1 to 60, memory.target(listOf("hero", "profile-services")))
        assertEquals(19, restoredRowOffset(position.anchorOffset, 20))
        assertEquals(1 to 0, memory.target(listOf("hero", "profile-services"), readFailed = true))
    }

    @Test fun userReturnDuringPendingRestoreKeepsMediaButSupersedesOldScrollAnchor() {
        val saved = SwipePresentation(4, 1, 11, "{}", "IMAGE:b", "profile-notes", 23,
            listOf("Notes"), mediaIndex = 1)
        val coordinator = ProfileRestoreCoordinator()
        val media = ProfileMediaSelection()
        val sections = ProfileSections()
        val scroll = ProfileScrollMemory()
        coordinator.synchronize(saved, null, null, media, sections, scroll)
        coordinator.returnToHero(saved)
        assertFalse(coordinator.terminal(saved))
        coordinator.synchronize(saved, ProfileProjectionKey(4, 1, 11, 1, "{}"), null, media, sections, scroll)
        assertEquals("IMAGE:b", media.id)
        assertTrue(sections.expanded(ProfileSection.Notes))
        assertEquals(0 to 0, scroll.target(listOf("hero", "profile-notes")))
    }

    @Test fun explicitReceiptWaitsForProjectionAndDoesNotConsumeBeforeActualScroll() {
        val key = ProfileProjectionKey(4, 1, 11, 1, "{}")
        val saved = SwipePresentation(4, 1, 11, "{}", "IMAGE:b", "profile-notes", 23,
            listOf("Notes"), runId = 3, mediaIndex = 2)
        val media = ProfileMediaSelection()
        val sections = ProfileSections()
        val scroll = ProfileScrollMemory()
        val coordinator = ProfileRestoreCoordinator()
        coordinator.synchronize(saved, null, 3, media, sections, scroll)
        assertEquals(ProfileRestoreCoordinator.Phase.Waiting, coordinator.phase)
        assertFalse(coordinator.terminal(saved))
        assertFalse(coordinator.canRestore(saved))
        coordinator.synchronize(saved, key, 3, media, sections, scroll)
        assertEquals(ProfileRestoreCoordinator.Phase.Restoring, coordinator.phase)
        assertEquals("IMAGE:b", media.id)
        assertTrue(sections.expanded(ProfileSection.Notes))
        assertEquals(ProfileScrollAnchor("profile-notes", 23), scroll.anchor)
        assertTrue(scroll.recovering)
        assertFalse(coordinator.terminal(saved))
        val rows = ProfileFlowRows(media).apply {
            item("hero") {}
            item("profile-notes", ProfileRowRead.Note) {}
        }.build()
        assertEquals(ProfileRowRead.Note, scroll.dependency(rows))
        // A projection gap must not consume the receipt or overwrite its anchor.
        coordinator.synchronize(saved, null, 3, media, sections, scroll)
        assertEquals(ProfileRestoreCoordinator.Phase.Waiting, coordinator.phase)
        assertFalse(coordinator.terminal(saved))
        coordinator.synchronize(saved, key.copy(revision = 2), 3, media, sections, scroll)
        assertEquals(ProfileScrollAnchor("profile-notes", 23), scroll.anchor)
        scroll.restored()
        coordinator.complete(saved)
        assertTrue(coordinator.terminal(saved))
        media.restore(1, "IMAGE:c")
        coordinator.synchronize(saved, key, 3, media, sections, scroll)
        assertEquals("IMAGE:c", media.id)
        coordinator.synchronize(null, key, 3, media, sections, scroll)
        assertEquals(ProfileRestoreCoordinator.Phase.Idle, coordinator.phase)
    }

    @Test fun changedSnapshotKeepsStableKeysButWrongOwnerCannotRestoreAnything() {
        val saved = SwipePresentation(4, 1, 11, "{}", "IMAGE:b", "event/5", 60,
            listOf("History", "history-description"), runId = 3, mediaIndex = 2)
        val projection = ProfileProjectionKey(4, 1, 12, 1, "{}")
        val media = ProfileMediaSelection()
        val sections = ProfileSections()
        val scroll = ProfileScrollMemory()
        val coordinator = ProfileRestoreCoordinator()
        coordinator.synchronize(saved, projection, 3, media, sections, scroll)
        assertEquals(ProfileScrollAnchor("event/5", 0), scroll.anchor)
        assertEquals("IMAGE:b", media.id)
        assertTrue(sections.showDescriptionChanges)
        val rows = ProfileFlowRows(media).apply {
            item("hero") {}
            item("profile-history-head", ProfileRowRead.Detail) {}
        }.build()
        assertEquals(ProfileRowRead.Detail, scroll.dependency(rows))
        assertEquals(1 to 0, scroll.target(rows.map { it.key }))
        coordinator.synchronize(saved, projection.copy(ownerId = 5), 3, media, sections, scroll)
        assertEquals(ProfileRestoreCoordinator.Phase.Invalid, coordinator.phase)
        assertNull(media.id)
        assertTrue(sections.entries.isEmpty())
        assertEquals(0 to 0, scroll.target(rows.map { it.key }))
    }

    @Test fun repeatedSkipAndPreferenceDuringRestoreRetainOriginalPosition() = runBlocking {
        val state = DiscoverUiState(cards = listOf(fixtureCard(4, 1)), activeRunId = 3, loading = false)
        val card = requireNotNull(state.cardToken())
        val token = requireNotNull(state.swipeToken(fixtureSwipe(snapshot = 12)))
        val saved = SwipePresentation(4, 1, 11, "{}", "IMAGE:b", "profile-notes", 20,
            listOf("Notes"), runId = 3)
        val transient = saved.copy(mediaId = null, anchorKey = "hero", anchorOffset = 0)
        assertEquals(saved, presentationForDecision(saved, transient, card))
        assertEquals(saved, presentationForDecision(saved, null, card))
        assertEquals(transient, presentationForDecision(saved.copy(runId = 7), transient, card))
        val deck = SwipeSession()
        val skip = SwipeStep(4, 1, presentation = presentationForDecision(saved, null, card))
        deck.visit(skip)
        undoSwipeStep(skip, { _, _, _, _ -> error("skip must not write") }, { 1 }, deck::undo)
        assertEquals(saved, deck.restoredPresentation(state.cards.first()))
        commitProfilePreference(token, Preference.LIKE, { _, _, _, _ -> Preference.NONE },
            deck::visit, presentationForDecision(saved, transient, card))
        assertEquals(saved, deck.last?.presentation)
        val detail = DetailIdentityToken(2, 4, 1, 12, 1, Preference.NONE, false, emptyList())
        commitProfilePreference(detail, Preference.DISLIKE, { _, _, _, _ -> Preference.NONE },
            { error("standalone must never visit") }, saved)
    }

    @Test fun committedDecisionRetainsPresentationOnlyForMatchingSwipeAndUndoCasSuccess() = runBlocking {
        val state = DiscoverUiState(cards = listOf(fixtureCard(4, 1)), activeRunId = 3, loading = false)
        val token = requireNotNull(state.swipeToken(fixtureSwipe()))
        val position = SwipePresentation(4, 1, token.snapshotId, "{}", "IMAGE:fixture",
            "hero", 12, emptyList(), runId = 3)
        val deck = SwipeSession()
        var native = Preference.NONE
        val write: suspend (Long, Long, Preference?, Preference) -> Preference = { listing, owner, expected, target ->
            assertEquals(4L, listing)
            assertEquals(1L, owner)
            check(expected == native)
            val previous = native
            native = target
            previous
        }
        val outcome = writeAndProject(write = {
            commitProfilePreference(token, Preference.LIKE, write, deck::visit, position)
        }, project = { error("read failed after commit") })
        assertTrue(outcome is DiscoveryMutationOutcome.ProjectionFailed)
        val step = requireNotNull(deck.last)
        assertEquals(position, step.presentation)
        native = Preference.DISLIKE
        try {
            undoSwipeStep(step, write, { 1 }, deck::undo)
            fail("Expected CAS rejection")
        } catch (_: IllegalStateException) { }
        assertEquals(step, deck.last)
        assertNull(deck.restoredPresentation(state.cards.first()))
        native = Preference.LIKE
        undoSwipeStep(step, write, { 1 }, deck::undo)
        assertEquals(Preference.NONE, native)
        assertNull(deck.last)
        assertEquals(position, deck.restoredPresentation(state.cards.first()))
        commitProfilePreference(token, Preference.LIKE, write, deck::visit, position.copy(runId = 4))
        assertNull(deck.last?.presentation)
    }

    @Test fun skipUndoChecksOwnerWithoutWritingAndRestoresOnlyAfterSuccess() = runBlocking {
        val deck = SwipeSession()
        val position = SwipePresentation(4, 1, 11, "{}", "IMAGE:fixture", "hero", 12, emptyList())
        val step = SwipeStep(4, 1, presentation = position)
        deck.visit(step)
        val noWrite: suspend (Long, Long, Preference?, Preference) -> Preference = { _, _, _, _ ->
            error("Skip must never write")
        }
        try {
            undoSwipeStep(step, noWrite, { 2 }, deck::undo)
            fail("Expected owner conflict")
        } catch (_: IllegalStateException) { }
        assertEquals(step, deck.last)
        undoSwipeStep(step, noWrite, { 1 }, deck::undo)
        assertNull(deck.last)
        assertEquals(position, deck.restoredPresentation(fixtureCard(4, 1)))
    }

    @Test fun photoRequestSupersedesPendingAnchorRecovery() {
        val memory = ProfileScrollMemory(ProfileScrollAnchor("profile-facts", 35), initialRecovery = true)
        memory.returnToHero("hero")
        assertFalse(memory.recovering)
        assertEquals(ProfileScrollAnchor("hero", 0), memory.anchor)
        assertNull(memory.target(listOf("hero", "profile-facts")))
    }
}
