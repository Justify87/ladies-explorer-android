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
        var profileChanges = 0
        var feedOk = true
        var renderedSwipe: SwipeActionToken? = null
        var source: suspend (Long) -> ProfileResponse = { error("offline") }
        var cached: ProfileResponse? = fixtureProfile()
        var identityError: Exception? = null
        var archivedMedia: List<ProfileMediaItem>? = null
        var preference = Preference.NONE
        var afterProjection: suspend (NativeProfileProjection?) -> NativeProfileProjection? = { it }
        var commit: suspend (Long, Long) -> Unit = { _, target -> owner = target }
        var archive: suspend (Long, Control) -> MediaArchiveReport = { snapshot, _ ->
            MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u)
        }
        val controls = mutableListOf<Control>()
        val sourceControls = mutableListOf<Control>()
        val operations = StandaloneProfileOperations(
            scope, search, discovery, notes, tickets,
            StandaloneProfileIo(
                loadProfile = { listing, _ -> calls += "source:$listing"; source(listing).also { cached = it } },
                cachedProfile = { calls += "cache:$it"; cached },
                profileProjection = {
                    calls += "profile:$it"
                    identityError?.let { e -> throw e }
                    afterProjection(cached?.let { profile ->
                        NativeProfileProjection(profile, fixtureIdentity(owner), fixturePerson(owner).copy(preference = preference),
                            archivedMedia ?: fixtureSwipe(snapshot = profile.snapshotId).media, emptyList(),
                            listOf(IdentityCandidate(2L, "CANDIDATE", false, true, 0u, 0u, null)))
                    })
                },
                archiveProfileMedia = { snapshot, control -> calls += "archive:$snapshot"; archive(snapshot, control) },
                newArchiveControl = { Control().also(controls::add) },
                cancelArchive = { it.cancelled++; calls += "cancel" },
                confirmIdentityMatch = { listing, expectedOwner, expectedSnapshot, target ->
                    check(owner == expectedOwner)
                    check(cached?.snapshotId == expectedSnapshot)
                    calls += "confirm:$listing:$target"; commit(listing, target)
                },
                newSourceControl = { Control().also(sourceControls::add) },
                cancelSource = { it.cancelled++ },
            ),
            ProfileProjectionCallbacks(
                epoch = { epoch }, nextEpoch = { ++epoch }, pending = { pending },
                projectDiscovery = { calls += "project:$it"; feedOk },
                discovery = { calls += "discovery"; pending = false },
                savedSearch = { calls += "saved" }, map = { calls += "map" }, home = { calls += "home" },
                admitSwipe = { it == renderedSwipe && currentSwipeActionCard(discovery.value, false, it) != null },
                profileChanged = { profileChanges++ },
            ),
        )
        suspend fun idle() {
            withTimeout(10_000) {
                while (true) {
                    val children = scope.coroutineContext[Job]!!.children.toList()
                    if (children.isEmpty()) break
                    children.joinAll()
                }
            }
        }
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
        assertEquals(listOf("confirm:4:2", "profile:4",
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
        assertEquals(listOf("confirm:4:2", "project:1",
            "discovery", "saved", "map", "home"), f.calls)
    }

    @Test fun committedMergeReadFailureInvalidatesCapabilitiesAndRetryNeverRepeatsWrite() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val a = f.tickets.invalidate(1L)
        f.commit = { _, target -> f.owner = target; f.identityError = IllegalStateException("read failed") }
        f.confirmCurrent()
        f.idle()
        assertEquals(2L, f.owner)
        assertNull(f.search.value.profileIdentity)
        assertNull(f.search.value.selectedPersonId)
        assertNull(f.search.value.profile)
        assertTrue(f.search.value.profileMedia.isEmpty())
        assertEquals(4L, f.search.value.retryProfileListingId)
        assertFalse(f.tickets.isCurrent(1L, a))
        assertEquals(setOf(3L), f.notes.value.keys)
        assertTrue(f.search.value.actionError!!.startsWith("Zusammenführung gespeichert"))
        assertEquals(listOf("confirm:4:2", "profile:4", "project:1", "saved", "map", "home"), f.calls)
        f.confirmCurrent()
        f.identityError = null
        f.operations.retryProfileProjection()
        f.idle()
        assertEquals(2L, f.search.value.profileIdentity?.personId)
        assertNull(f.search.value.retryProfileListingId)
        assertEquals(1, f.calls.count { it.startsWith("confirm:") })
        assertFalse(f.calls.any { it.startsWith("source:") })
        assertFalse(f.discovery.value.busy)
    }

    @Test fun failedMergeWriteReprojectsButPreservesNotesAndDoesNotReplayWrite() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val ticket = f.tickets.invalidate(1L)
        f.commit = { _, _ -> error("write failed") }
        f.confirmCurrent()
        f.idle()
        assertEquals(listOf("confirm:4:2", "profile:4", "project:1", "saved", "map"), f.calls)
        assertEquals("write failed", f.search.value.actionError)
        assertEquals(1L, f.search.value.profileIdentity?.personId)
        assertTrue(f.tickets.isCurrent(1L, ticket))
        assertEquals(setOf(1L, 2L, 3L), f.notes.value.keys)
        assertFalse(f.discovery.value.busy)
    }

    @Test fun rejectedMergeWithChangedOwnerUsesNewLocalProjectionWithoutTouchingNotes() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val old = requireNotNull(f.search.value.detailIdentityToken())
        val ticket = f.tickets.invalidate(1L)
        f.owner = 7L
        f.cached = fixtureProfile(12L)
        f.operations.confirmIdentity(old, 2L)
        f.idle()
        assertEquals(7L, f.search.value.profileIdentity?.personId)
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertNotEquals(old, f.search.value.detailIdentityToken())
        assertTrue(f.tickets.isCurrent(1L, ticket))
        assertEquals(setOf(1L, 2L, 3L), f.notes.value.keys)
        assertEquals(listOf("profile:4", "project:1", "saved", "map"), f.calls)
        assertFalse(f.discovery.value.busy)
        assertNotNull(f.search.value.actionError)
    }

    @Test fun lateDetailSuccessAndFailureCannotReplaceNewerProjection() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val session = f.search.value.profileSessionId
        for (failLate in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.afterProjection = { old ->
                started.complete(Unit)
                release.await()
                if (failLate) error("late read failed")
                old
            }
            val late = launch { f.operations.refreshLocalProfile(session, 4L) }
            started.await()
            f.preference = if (failLate) Preference.DISLIKE else Preference.LIKE
            f.afterProjection = { it }
            f.operations.refreshLocalProfile(session, 4L)
            val current = requireNotNull(f.search.value.detailIdentityToken())
            assertEquals(f.preference, current.preference)
            release.complete(Unit)
            late.join()
            assertEquals(current, f.search.value.detailIdentityToken())
            assertNull(f.search.value.retryProfileListingId)
        }
    }

    @Test fun stopBeforeFirstCommitOffersExplicitSourceRetryOnlyAfterLocalAbsence() = runBlocking {
        val f = Fixture(this)
        f.cached = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.source = { started.complete(Unit); release.await(); error("cancelled before commit") }
        f.operations.loadProfile(4L, 1L) {}
        started.await()
        f.operations.setPresentationVisible(false)
        f.operations.setPresentationVisible(true)
        release.complete(Unit)
        f.idle()
        assertTrue(f.search.value.profileSourceRetryAllowed)
        assertNull(f.search.value.profile)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        f.source = { fixtureProfile(12L) }
        f.operations.retryProfileProjection()
        f.idle()
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertFalse(f.search.value.profileSourceRetryAllowed)
        assertEquals(2, f.calls.count { it.startsWith("source:") })
    }

    @Test fun knownLocalProfileCannotTriggerOnlineRetryEvenWhenProjectionMissing() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val session = f.search.value.profileSessionId
        f.cached = null
        try { f.operations.refreshLocalProfile(session, 4L); fail("expected missing projection") }
        catch (_: IllegalStateException) { }
        f.operations.retryProfileProjection()
        f.idle()
        assertFalse(f.search.value.profileSourceRetryAllowed)
        assertNull(f.search.value.detailIdentityToken())
        assertFalse(f.calls.any { it.startsWith("source:") })
    }

    @Test fun reopenedListingWaitsForOldSourceDrainAndBothCompletionsRefreshMap() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { started.complete(Unit); finish.await(); error("late native response rejected") }
        f.operations.loadProfile(4L, 1L) {}
        started.await()
        val oldSession = f.search.value.profileSessionId
        f.operations.closeProfile()
        f.owner = 7L
        f.cached = fixtureProfile(12L)
        f.source = { error("offline") }
        f.operations.loadProfile(4L, 7L) {}
        yield()
        assertNotEquals(oldSession, f.search.value.profileSessionId)
        assertNull(f.search.value.profile)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        assertEquals(1, f.sourceControls.single().cancelled)
        finish.complete(Unit)
        f.idle()
        assertEquals(7L, f.search.value.profileIdentity?.personId)
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertEquals(2, f.calls.count { it == "map" })
        assertEquals(2, f.profileChanges)
        assertEquals(2, f.calls.count { it.startsWith("source:") })
        assertTrue(f.controls.isEmpty())
    }

    @Test fun stopRevokesDetailDeliveryAndResumeReadsOnlyAfterSourceDrain() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { started.complete(Unit); finish.await(); fixtureProfile(12L) }
        f.operations.loadProfile(4L, 1L) {}
        started.await()
        val oldSession = f.search.value.profileSessionId
        f.operations.setPresentationVisible(false)
        assertNotEquals(oldSession, f.search.value.profileSessionId)
        assertNull(f.search.value.profile)
        assertEquals(1, f.sourceControls.single().cancelled)
        f.operations.setPresentationVisible(true)
        yield()
        assertNull(f.search.value.profile)
        finish.complete(Unit)
        f.idle()
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        assertEquals(1, f.calls.count { it == "map" })
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
        assertEquals(listOf("source:4", "saved", "discovery", "map"), f.calls)
        assertEquals(1, f.profileChanges)
        assertNull(f.search.value.profile)
        assertFalse(f.search.value.loading)
        assertTrue(f.controls.isEmpty())
    }

    @Test fun archiveUiRetainsOwnershipUntilNativeDrainAndSignalsCancelOnlyOnce() = runBlocking {
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
        assertTrue(f.search.value.archiving)
        assertTrue(coroutineContext[Job]!!.children.single().isActive)
        f.operations.closeProfile()
        assertEquals(1, f.controls.single().cancelled)
        f.calls.clear()
        finish.complete(Unit)
        f.idle()
        assertEquals(listOf("saved", "discovery"), f.calls)
        assertEquals(2, f.profileChanges)
        assertNull(f.search.value.mediaArchive)
        assertFalse(f.search.value.profileOpen)
    }

    @Test fun partialArchiveFailureStillReprojectsCommittedMediaAndRevokesOldToken() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.source = { fixtureProfile() }
        f.archive = { _, _ ->
            started.complete(Unit)
            release.await()
            f.archivedMedia = listOf(ProfileMediaItem("IMAGE", "https://example.invalid/changed.jpg", "/fixture/partial.jpg", 0u))
            error("partial archive failure")
        }
        f.operations.loadProfile(4L, 1L) {}
        started.await()
        val old = requireNotNull(f.search.value.detailIdentityToken())
        release.complete(Unit)
        f.idle()
        assertEquals("/fixture/partial.jpg", f.search.value.profileRead?.media?.single()?.localPath)
        assertEquals("partial archive failure", f.search.value.mediaWarning)
        assertNotEquals(old, f.search.value.detailIdentityToken())
        assertFalse(f.search.value.archiving)
        assertNull(f.search.value.mediaArchive)
        assertEquals(1, f.calls.count { it.startsWith("archive:") })
    }

    @Test fun failedPostArchiveProjectionRevokesActionsAndRetryReadsOnlyLocalState() = runBlocking {
        val f = Fixture(this)
        f.source = { fixtureProfile() }
        f.archive = { snapshot, _ ->
            f.identityError = IllegalStateException("projection unavailable")
            MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u)
        }
        f.operations.loadProfile(4L, 1L) {}
        f.idle()
        assertNull(f.search.value.detailIdentityToken())
        assertNull(f.search.value.profileRead)
        assertEquals(4L, f.search.value.retryProfileListingId)
        assertFalse(f.search.value.archiving)
        f.identityError = null
        f.calls.clear()
        f.operations.retryProfileProjection()
        f.idle()
        assertNotNull(f.search.value.detailIdentityToken())
        assertNull(f.search.value.retryProfileListingId)
        assertEquals(listOf("profile:4", "discovery"), f.calls)
    }

    @Test fun mergeWaitsForCancelledArchiveToActuallyDrainBeforeWrite() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { fixtureProfile() }
        f.archive = { snapshot, _ -> started.complete(Unit); finish.await(); MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u) }
        f.operations.loadProfile(4L, 1L) {}
        started.await()
        f.calls.clear()
        f.confirmCurrent()
        yield()
        assertTrue(f.discovery.value.busy)
        assertEquals(listOf("cancel"), f.calls)
        assertEquals(1L, f.owner)
        finish.complete(Unit)
        f.idle()
        assertEquals(2L, f.owner)
        assertEquals(1, f.calls.count { it.startsWith("confirm:") })
        assertFalse(f.search.value.archiving)
        assertFalse(f.discovery.value.busy)
    }

    @Test fun nativeMergeCommitWithCancelledDeliveryStillInvalidatesNotesAndProjections() = runBlocking {
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
        assertEquals(listOf("confirm:4:2", "discovery", "saved", "map", "home"), f.calls)
        assertFalse(f.tickets.isCurrent(1L, ticket))
        assertNull(f.search.value.profileIdentity)
        assertNull(f.search.value.profile)
        assertEquals(4L, f.search.value.retryProfileListingId)
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
        assertEquals(listOf("confirm:4:2", "profile:4",
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

    @Test fun swipeMergeUsesSameNativeWriteWithoutOpeningDetailAndRejectsStaleToken() = runBlocking {
        val f = Fixture(this)
        val candidate = IdentityCandidate(2L, "CANDIDATE", false, true, 0u, 0u, null)
        f.discovery.value = DiscoverUiState(cards = listOf(fixtureCard(4L, 1L)), loading = false)
        val token = requireNotNull(f.discovery.value.swipeToken(fixtureSwipe().let { it.copy(local = it.local.copy(candidates = listOf(candidate))) }))
        f.renderedSwipe = token
        f.oldNotes()
        f.notes.value = f.notes.value.updateNote(2L) { it.copy(draft = it.draft!!.edit("unsaved")) }
        f.operations.confirmIdentity(token, 2L)
        assertTrue(f.discovery.value.mutationError!!.contains("2"))
        assertTrue(f.calls.isEmpty())
        f.oldNotes()
        f.operations.confirmIdentity(token.copy(snapshotId = 99L), 2L)
        assertTrue(f.calls.isEmpty())
        f.operations.confirmIdentity(token, 2L)
        f.idle()
        assertEquals(2L, f.owner)
        assertEquals(listOf("confirm:4:2", "project:1", "saved", "map", "home"), f.calls)
        assertFalse(f.search.value.profileOpen)
        assertNull(f.search.value.profile)
        assertEquals(setOf(3L), f.notes.value.keys)
        assertFalse(f.discovery.value.busy)
    }
    @Test fun initialSourceCommitThenLocalReadFailureRetriesOnlyLocalProjection() = runBlocking {
        val f = Fixture(this)
        f.source = { fixtureProfile(12L) }
        f.identityError = IllegalStateException("local read failed")
        f.operations.loadProfile(4L, 1L) {}
        f.idle()
        assertEquals(4L, f.search.value.retryProfileListingId)
        assertNull(f.search.value.profile)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        f.identityError = null
        f.operations.retryProfileProjection()
        f.idle()
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        assertTrue(f.controls.isEmpty())
    }
}
