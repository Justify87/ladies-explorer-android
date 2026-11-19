package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.resume

class StandaloneProfileOperationsTest {
    private class Control { var cancelled = 0 }
    private class Fixture(private val scope: CoroutineScope) {
        val search = MutableStateFlow(SearchUiState())
        val discovery = MutableStateFlow(DiscoverUiState(loading = false))
        val notes = MutableStateFlow<Map<Long, NoteEditorState>>(emptyMap())
        val tickets = NoteReadTickets()
        val calls = mutableListOf<String>()
        var owner = 1L
        var epoch = 0L
        var pending = false
        var feedOk = true
        var source: suspend (Long) -> ProfileResponse = { error("offline") }
        var cached: ProfileResponse? = fixtureProfile()
        var identityError: Exception? = null
        var commit: suspend (Long, Long) -> Unit = { _, target -> owner = target }
        var archive: suspend (Long, Control) -> MediaArchiveReport = { snapshot, _ ->
            MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u)
        }
        val controls = mutableListOf<Control>()
        val operations = StandaloneProfileOperations(
            scope, search, discovery, notes, tickets,
            StandaloneProfileIo(
                loadProfile = { calls += "source:$it"; source(it) },
                cachedProfile = { calls += "cache:$it"; cached },
                profileMedia = { calls += "media:$it"; fixtureSwipe(snapshot = it).media },
                profileIdentity = { calls += "identity:$it"; identityError?.let { e -> throw e }; fixtureIdentity(owner) },
                person = { calls += "person:$it"; fixturePerson(it) },
                changeEvents = { calls += "history:$it"; emptyList() },
                identityCandidates = { calls += "candidates:$it"; listOf(IdentityCandidate(2L, "CANDIDATE", false, true, 0u, 0u, null)) },
                archiveProfileMedia = { snapshot, control -> calls += "archive:$snapshot"; archive(snapshot, control) },
                newArchiveControl = { Control().also(controls::add) },
                cancelArchive = { it.cancelled++; calls += "cancel" },
                confirmIdentityMatch = { listing, expectedOwner, target ->
                    check(owner == expectedOwner)
                    calls += "confirm:$listing:$target"; commit(listing, target)
                },
            ),
            ProfileProjectionCallbacks(
                epoch = { epoch }, nextEpoch = { ++epoch }, pending = { pending },
                projectDiscovery = { calls += "project:$it"; feedOk },
                discovery = { calls += "discovery"; pending = false },
                savedSearch = { calls += "saved" }, map = { calls += "map" }, home = { calls += "home" },
            ),
        )
        suspend fun idle() { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        suspend fun openOffline() {
            operations.loadProfile(4L, owner) { calls += "open" }
            idle()
            assertTrue(search.value.profileFromArchive)
            assertEquals(owner, search.value.profileIdentity?.personId)
            assertTrue(controls.isEmpty())
            calls.clear()
        }
        fun confirmCurrent() {
            search.value.detailIdentityToken()?.let { operations.confirmIdentity(it, 2L) }
        }
        fun oldNotes() {
            notes.value = (1L..3L).associateWith { NoteEditorState().loaded("note $it") }
        }
    }

    @Test fun dirtyNotesForEitherOwnerBlockRealMergeAdmission() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        for (owner in listOf(1L, 2L)) {
            f.oldNotes()
            f.notes.value = f.notes.value.updateNote(owner) { it.copy(draft = it.draft!!.edit("draft")) }
            f.confirmCurrent()
            assertTrue(f.search.value.actionError!!.contains(owner.toString()))
            assertFalse(f.discovery.value.busy)
            assertTrue(f.calls.isEmpty())
        }
        f.operations.closeProfile()
        f.confirmCurrent()
        assertTrue(f.calls.isEmpty())
    }

    @Test fun mergeCommitsOnceAndInvalidatesBothTicketsBeforeReloadAndAllCompletionCallbacks() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val a = f.tickets.invalidate(1L)
        val b = f.tickets.invalidate(2L)
        val other = f.tickets.invalidate(3L)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.commit = { _, target -> started.complete(Unit); finish.await(); f.owner = target }
        f.confirmCurrent()
        started.await()
        assertTrue(f.discovery.value.busy)
        f.confirmCurrent()
        assertTrue(f.tickets.isCurrent(1L, a))
        finish.complete(Unit)
        f.idle()
        assertEquals(listOf("confirm:4:2", "identity:4", "history:2", "candidates:4", "person:2",
            "project:1", "saved", "map", "home"), f.calls)
        assertEquals(2L, f.search.value.profileIdentity?.personId)
        assertEquals(2L, f.search.value.selectedPerson?.personId)
        assertFalse(f.tickets.isCurrent(1L, a))
        assertFalse(f.tickets.isCurrent(2L, b))
        assertTrue(f.tickets.isCurrent(3L, other))
        assertEquals(setOf(3L), f.notes.value.keys)
        assertFalse(f.discovery.value.busy)
        assertFalse(f.search.value.actionBusy)
    }

    @Test fun backDuringMergeKeepsWriteGuardUntilDrainWithoutReopeningProfile() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.commit = { _, target -> started.complete(Unit); finish.await(); f.owner = target }
        f.feedOk = false
        f.confirmCurrent()
        started.await()
        f.operations.closeProfile()
        assertFalse(f.search.value.profileOpen)
        assertTrue(f.discovery.value.busy)
        f.confirmCurrent()
        finish.complete(Unit)
        f.idle()
        assertNull(f.search.value.profile)
        assertFalse(f.search.value.profileOpen)
        assertFalse(f.discovery.value.busy)
        assertEquals(listOf("confirm:4:2", "identity:4", "history:2", "candidates:4", "person:2", "project:1",
            "discovery", "saved", "map", "home"), f.calls)
    }

    @Test fun knownGapCommittedMergeReadFailureRetainsOldProfileButNeverRepeatsWrite() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val old = f.search.value
        val a = f.tickets.invalidate(1L)
        f.commit = { _, target -> f.owner = target; f.identityError = IllegalStateException("read failed") }
        f.confirmCurrent()
        f.idle()
        assertEquals(2L, f.owner)
        assertNull(f.search.value.profileIdentity)
        assertNull(f.search.value.selectedPersonId)
        assertEquals(old.profile, f.search.value.profile)
        assertEquals(old.profileMedia, f.search.value.profileMedia)
        assertFalse(f.tickets.isCurrent(1L, a))
        assertEquals(setOf(3L), f.notes.value.keys)
        assertTrue(f.search.value.actionError!!.startsWith("Zusammenführung gespeichert"))
        assertEquals(listOf("confirm:4:2", "identity:4", "discovery", "saved", "map", "home"), f.calls)
        f.confirmCurrent()
        assertEquals(1, f.calls.count { it.startsWith("confirm:") })
        assertFalse(f.discovery.value.busy)
    }

    @Test fun failedMergeWriteLeavesAllNotesAndProjectionUntouched() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val ticket = f.tickets.invalidate(1L)
        f.commit = { _, _ -> error("write failed") }
        f.confirmCurrent()
        f.idle()
        assertEquals(listOf("confirm:4:2"), f.calls)
        assertEquals("write failed", f.search.value.actionError)
        assertEquals(1L, f.search.value.profileIdentity?.personId)
        assertTrue(f.tickets.isCurrent(1L, ticket))
        assertEquals(setOf(1L, 2L, 3L), f.notes.value.keys)
        assertFalse(f.discovery.value.busy)
    }

    @Test fun knownGapSameListingReopenedBeforeOldSourceCompletionDoesNotRefreshMapForLateCommit() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { started.complete(Unit); finish.await(); fixtureProfile(11L) }
        f.operations.loadProfile(4L, 1L) {}
        started.await()
        val oldSession = f.search.value.profileSessionId
        val oldJob = coroutineContext[Job]!!.children.single()
        f.operations.closeProfile()
        f.owner = 7L
        f.cached = fixtureProfile(12L)
        f.source = { error("offline") }
        f.operations.loadProfile(4L, 7L) {}
        // Wait for the new local projection, not the old blocked source.
        coroutineContext[Job]!!.children.single { it != oldJob }.join()
        assertNotEquals(oldSession, f.search.value.profileSessionId)
        assertEquals(7L, f.search.value.profileIdentity?.personId)
        assertEquals(12L, f.search.value.profile?.snapshotId)
        f.calls.clear()
        finish.complete(Unit)
        f.idle()
        assertEquals(listOf("saved", "discovery"), f.calls)
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertTrue(f.controls.isEmpty())
    }

    @Test fun cancellationAfterNativeSourceCommitBeforeDeliveryStillCallsCompletion() = runBlocking {
        val f = Fixture(this)
        val continuation = CompletableDeferred<CancellableContinuation<ProfileResponse>>()
        f.source = { suspendCancellableCoroutine { continuation.complete(it) } }
        f.operations.loadProfile(4L, 1L) {}
        val reply = continuation.await()
        // Simulate a native commit that returns to a suspended dispatcher; cancel before delivery.
        f.cached = fixtureProfile(12L)
        reply.resume(f.cached!!)
        val child = coroutineContext[Job]!!.children.single()
        child.cancelAndJoin()
        assertEquals(listOf("source:4", "saved", "discovery"), f.calls)
        assertNull(f.search.value.profile)
        assertFalse(f.search.value.loading)
        assertTrue(f.controls.isEmpty())
    }

    @Test fun knownGapArchiveUiReleasesBeforeNativeDrainButLateCompletionStillInvalidatesFeeds() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { fixtureProfile() }
        f.archive = { snapshot, _ -> started.complete(Unit); finish.await(); MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u) }
        f.operations.loadProfile(4L, 1L) {}
        started.await()
        assertTrue(f.search.value.archiving)
        f.operations.cancelMediaArchive()
        assertEquals(1, f.controls.single().cancelled)
        assertFalse(f.search.value.archiving)
        assertTrue(coroutineContext[Job]!!.children.single().isActive)
        f.operations.closeProfile()
        assertEquals(2, f.controls.single().cancelled)
        f.calls.clear()
        finish.complete(Unit)
        f.idle()
        assertEquals(listOf("media:11", "saved", "discovery"), f.calls)
        assertNull(f.search.value.mediaArchive)
        assertFalse(f.search.value.profileOpen)
    }

    @Test fun knownGapNativeMergeCommitWithCancelledDeliveryDoesNotInvalidateNotesOrProjections() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val reply = CompletableDeferred<CancellableContinuation<Unit>>()
        val ticket = f.tickets.invalidate(1L)
        f.commit = { _, target -> suspendCancellableCoroutine { c -> f.owner = target; reply.complete(c) } }
        f.confirmCurrent()
        reply.await().resume(Unit)
        coroutineContext[Job]!!.children.single().cancelAndJoin()
        assertEquals(2L, f.owner)
        assertEquals(listOf("confirm:4:2"), f.calls)
        assertTrue(f.tickets.isCurrent(1L, ticket))
        assertEquals(1L, f.search.value.profileIdentity?.personId)
        assertFalse(f.discovery.value.busy)
    }

    @Test fun mergeWithPendingReadReconcilesAfterDrainWithoutWritingAgain() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.commit = { _, target -> started.complete(Unit); finish.await(); f.owner = target }
        f.confirmCurrent()
        started.await()
        f.pending = true
        finish.complete(Unit)
        f.idle()
        assertEquals(listOf("confirm:4:2", "identity:4", "history:2", "candidates:4", "person:2",
            "project:1", "discovery", "saved", "map", "home"), f.calls)
        assertFalse(f.pending)
        assertFalse(f.discovery.value.busy)
    }
    @Test fun delayedConfirmCallbackCannotUseCurrentOwnerOfSameListingInNewSession() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val oldSession = f.search.value.profileSessionId
        val oldToken = requireNotNull(f.search.value.detailIdentityToken())
        val oldTicket = f.tickets.invalidate(1L)
        f.operations.closeProfile()
        f.owner = 7L
        f.openOffline()
        assertNotEquals(oldSession, f.search.value.profileSessionId)
        val currentTicket = f.tickets.invalidate(7L)
        val current = requireNotNull(f.search.value.detailIdentityToken())
        f.operations.confirmIdentity(current.copy(listingId = 5L), 2L)
        f.operations.confirmIdentity(current.copy(snapshotId = current.snapshotId + 1L), 2L)
        f.operations.confirmIdentity(current, 99L)
        f.operations.confirmIdentity(oldToken, 2L)
        f.idle()
        assertTrue(f.calls.isEmpty())
        assertEquals(7L, f.search.value.profileIdentity?.personId)
        assertTrue(f.tickets.isCurrent(1L, oldTicket))
        assertTrue(f.tickets.isCurrent(7L, currentTicket))
        f.confirmCurrent()
        f.idle()
        assertEquals(1, f.calls.count { it == "confirm:4:2" })
        assertEquals(2L, f.search.value.profileIdentity?.personId)
        assertFalse(f.tickets.isCurrent(7L, currentTicket))
    }

}
