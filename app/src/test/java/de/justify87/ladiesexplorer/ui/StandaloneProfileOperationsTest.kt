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
                    calls += "confirm:$listing:$target"
                    check(owner == expectedOwner) { "fixture owner conflict" }
                    check(cached?.snapshotId == expectedSnapshot) { "fixture snapshot conflict" }
                    commit(listing, target)
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
                val waiter = currentCoroutineContext().job
                while (true) {
                    val children = scope.coroutineContext.job.children.filter { it !== waiter }.toList()
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
        fun closeCurrent() = operations.closeProfile(search.value.profileSessionId)

        fun retryCurrent() = operations.retryProfileProjection(requireNotNull(search.value.detailRetryAction()))

        fun confirmCurrent() {
            operations.confirmIdentity(requireNotNull(search.value.detailIdentityToken()), 2L)
        }
        fun oldNotes() {
            notes.value = (1L..3L).associateWith { NoteEditorState().loaded("note $it") }
        }
    }

    @Test(timeout = 15_000) fun explicitRefreshRetainsSessionAndRejectsDuplicateOrStaleToken() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val old = requireNotNull(f.search.value.detailIdentityToken())
        val original = f.search.value.profileRead
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        f.source = { started.complete(Unit); release.awaitSignal(); fixtureProfile(12L) }
        f.operations.refreshProfile(old)
        started.awaitSignal()
        assertTrue(f.search.value.loading)
        assertEquals(old.sessionId, f.search.value.profileSessionId)
        assertEquals(original, f.search.value.profileRead)
        f.operations.refreshProfile(old)
        release.complete(Unit)
        f.idle()
        val current = requireNotNull(f.search.value.detailIdentityToken())
        assertEquals(old.sessionId, current.sessionId)
        assertEquals(12L, current.snapshotId)
        f.operations.refreshProfile(old)
        f.idle()
        assertEquals(1, f.calls.count { it.startsWith("source:") })
    }

    @Test(timeout = 15_000) fun dirtyNotesForEitherOwnerBlockRealMergeAdmission() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val token = requireNotNull(f.search.value.detailIdentityToken())
        for (owner in listOf(1L, 2L)) {
            f.oldNotes()
            f.notes.value = f.notes.value.updateNote(owner) { it.copy(draft = it.draft!!.edit("draft")) }
            f.confirmCurrent()
            assertTrue(f.search.value.actionError!!.contains(owner.toString()))
            assertFalse(f.discovery.value.busy)
            assertTrue(f.calls.isEmpty())
        }
        f.oldNotes()
        f.closeCurrent()
        f.operations.confirmIdentity(token, 2L)
        f.idle()
        assertTrue(f.calls.isEmpty())
        assertEquals(1L, f.owner)
    }

    @Test(timeout = 15_000) fun mergeCommitsOnceAndInvalidatesBothTicketsBeforeReloadAndAllCompletionCallbacks() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val a = f.tickets.invalidate(1L)
        val b = f.tickets.invalidate(2L)
        val other = f.tickets.invalidate(3L)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.commit = { _, target -> started.complete(Unit); finish.awaitSignal(); f.owner = target }
        f.afterProjection = {
            assertEquals(2L, f.owner)
            assertFalse(f.tickets.isCurrent(1L, a))
            assertFalse(f.tickets.isCurrent(2L, b))
            assertEquals(setOf(3L), f.notes.value.keys)
            it
        }
        val token = requireNotNull(f.search.value.detailIdentityToken())
        f.operations.confirmIdentity(token, 2L)
        started.awaitSignal()
        assertTrue(f.discovery.value.busy)
        f.operations.confirmIdentity(token, 2L)
        assertTrue(f.tickets.isCurrent(1L, a))
        finish.complete(Unit)
        f.idle()
        assertEquals("confirm:4:2", f.calls.first())
        assertEquals(listOf("home", "map", "profile:4", "project:1", "saved"), f.calls.drop(1).sorted())
        assertEquals(2L, f.search.value.profileIdentity?.personId)
        assertEquals(2L, f.search.value.selectedPerson?.personId)
        assertFalse(f.tickets.isCurrent(1L, a))
        assertFalse(f.tickets.isCurrent(2L, b))
        assertTrue(f.tickets.isCurrent(3L, other))
        assertEquals(setOf(3L), f.notes.value.keys)
        assertFalse(f.discovery.value.busy)
        assertFalse(f.search.value.actionBusy)
    }

    @Test(timeout = 15_000) fun backDuringMergeKeepsWriteGuardUntilDrainWithoutReopeningProfile() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.commit = { _, target -> started.complete(Unit); finish.awaitSignal(); f.owner = target }
        f.feedOk = false
        val token = requireNotNull(f.search.value.detailIdentityToken())
        f.operations.confirmIdentity(token, 2L)
        started.awaitSignal()
        f.closeCurrent()
        assertFalse(f.search.value.profileOpen)
        assertTrue(f.discovery.value.busy)
        f.operations.confirmIdentity(token, 2L)
        yield()
        assertEquals(listOf("confirm:4:2"), f.calls)
        finish.complete(Unit)
        f.idle()
        assertNull(f.search.value.profile)
        assertFalse(f.search.value.profileOpen)
        assertFalse(f.discovery.value.busy)
        f.operations.confirmIdentity(token, 2L)
        f.idle()
        assertEquals(listOf("confirm:4:2", "project:1"), f.calls.take(2))
        assertEquals(listOf("discovery", "home", "map", "saved"), f.calls.drop(2).sorted())
        assertEquals(2L, f.owner)
    }

    @Test(timeout = 15_000) fun stopDuringConfirmedMergeRebindsStillOpenListingOnLocalResume() = runBlocking {
        for (resumeBeforeCommit in listOf(false, true)) {
            val f = Fixture(this)
            f.openOffline()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            f.commit = { _, target -> started.complete(Unit); finish.awaitSignal(); f.owner = target }
            val old = requireNotNull(f.search.value.detailIdentityToken())
            f.confirmCurrent()
            started.awaitSignal()
            f.operations.setPresentationVisible(false)
            assertTrue(f.search.value.profileOpen)
            assertNull(f.search.value.detailIdentityToken())
            assertTrue(f.discovery.value.busy)
            if (resumeBeforeCommit) {
                f.operations.setPresentationVisible(true)
                yield()
                assertNull(f.search.value.profile)
            }
            finish.complete(Unit)
            f.idle()
            if (!resumeBeforeCommit) {
                assertNull(f.search.value.profile)
                f.operations.setPresentationVisible(true)
                f.idle()
            }
            assertTrue(f.search.value.profileOpen)
            assertEquals(4L, f.search.value.profile?.listingId)
            assertEquals(2L, f.search.value.profileIdentity?.personId)
            assertNull(f.search.value.retryProfileListingId)
            assertNull(f.search.value.actionError)
            assertNotEquals(old.sessionId, f.search.value.profileSessionId)
            f.operations.confirmIdentity(old, 2L)
            f.idle()
            assertEquals(1, f.calls.count { it.startsWith("confirm:") })
            assertEquals(1, f.calls.count { it == "profile:4" })
            assertFalse(f.calls.any { it.startsWith("source:") })
            assertFalse(f.discovery.value.busy)
        }
    }

    @Test(timeout = 15_000) fun committedMergeReadFailureInvalidatesCapabilitiesAndRetryNeverRepeatsWrite() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val a = f.tickets.invalidate(1L)
        f.commit = { _, target -> f.owner = target; f.identityError = IllegalStateException("read failed") }
        val token = requireNotNull(f.search.value.detailIdentityToken())
        f.operations.confirmIdentity(token, 2L)
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
        assertEquals("confirm:4:2", f.calls.first())
        assertEquals(listOf("home", "map", "profile:4", "project:1", "saved"), f.calls.drop(1).sorted())
        f.operations.confirmIdentity(token, 2L)
        f.identityError = null
        f.retryCurrent()
        f.idle()
        assertEquals(2L, f.search.value.profileIdentity?.personId)
        assertNull(f.search.value.retryProfileListingId)
        assertEquals(1, f.calls.count { it.startsWith("confirm:") })
        assertFalse(f.calls.any { it.startsWith("source:") })
        assertFalse(f.discovery.value.busy)
    }

    @Test(timeout = 15_000) fun failedMergeWriteReprojectsButPreservesNotesAndDoesNotReplayWrite() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val ticket = f.tickets.invalidate(1L)
        f.commit = { _, _ -> error("write failed") }
        f.confirmCurrent()
        f.idle()
        assertEquals("confirm:4:2", f.calls.first())
        assertEquals(listOf("map", "profile:4", "project:1", "saved"), f.calls.drop(1).sorted())
        assertEquals("write failed", f.search.value.actionError)
        assertEquals(1L, f.search.value.profileIdentity?.personId)
        assertTrue(f.tickets.isCurrent(1L, ticket))
        assertEquals(setOf(1L, 2L, 3L), f.notes.value.keys)
        assertFalse(f.discovery.value.busy)
    }

    @Test(timeout = 15_000) fun rejectedMergeWithChangedOwnerRevokesDetailInsteadOfRebindingSilently() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val old = requireNotNull(f.search.value.detailIdentityToken())
        val ticket = f.tickets.invalidate(1L)
        f.owner = 7L
        f.cached = fixtureProfile(12L)
        f.operations.confirmIdentity(old, 2L)
        f.idle()
        assertNull(f.search.value.profileIdentity)
        assertNull(f.search.value.profile)
        assertNull(f.search.value.detailIdentityToken())
        assertEquals(4L, f.search.value.retryProfileListingId)
        assertTrue(f.tickets.isCurrent(1L, ticket))
        assertEquals(setOf(1L, 2L, 3L), f.notes.value.keys)
        assertEquals("confirm:4:2", f.calls.first())
        assertEquals(listOf("map", "profile:4", "project:1", "saved"), f.calls.drop(1).sorted())
        assertFalse(f.discovery.value.busy)
        assertEquals("fixture owner conflict", f.search.value.actionError)
    }

    @Test(timeout = 15_000) fun changedOwnerAtOpenCannotBePublishedOrRecoveredByLocalRetry() = runBlocking {
        val f = Fixture(this)
        f.operations.loadProfile(4L, 1L) {}
        f.owner = 7L
        f.idle()
        assertNull(f.search.value.profileIdentity)
        assertNull(f.search.value.profile)
        assertEquals(4L, f.search.value.retryProfileListingId)
        assertTrue(f.search.value.error!!.contains("Profilzuordnung"))
        assertTrue(f.controls.isEmpty())
        f.calls.clear()
        f.retryCurrent()
        f.idle()
        assertNull(f.search.value.profile)
        assertFalse(f.calls.any { it.startsWith("source:") })
        f.closeCurrent()
        f.operations.loadProfile(4L, 7L) {}
        f.idle()
        assertEquals(7L, f.search.value.profileIdentity?.personId)
    }

    @Test(timeout = 15_000) fun lateDetailSuccessAndFailureCannotReplaceNewerProjection() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val session = f.search.value.profileSessionId
        for (failLate in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.afterProjection = { old ->
                started.complete(Unit)
                release.awaitSignal()
                if (failLate) error("late read failed")
                old
            }
            val late = launch { f.operations.refreshLocalProfile(session, 4L) }
            started.awaitSignal()
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

    @Test(timeout = 15_000) fun stopBeforeFirstCommitOffersExplicitSourceRetryOnlyAfterLocalAbsence() = runBlocking {
        val f = Fixture(this)
        f.cached = null
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.source = { started.complete(Unit); release.awaitSignal(); error("cancelled before commit") }
        f.operations.loadProfile(4L, 1L) {}
        started.awaitSignal()
        f.operations.setPresentationVisible(false)
        f.operations.setPresentationVisible(true)
        release.complete(Unit)
        f.idle()
        assertTrue(f.search.value.profileSourceRetryAllowed)
        assertNull(f.search.value.profile)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        f.source = { fixtureProfile(12L) }
        f.retryCurrent()
        f.idle()
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertFalse(f.search.value.profileSourceRetryAllowed)
        assertEquals(2, f.calls.count { it.startsWith("source:") })
    }

    @Test(timeout = 15_000) fun knownLocalProfileCannotTriggerOnlineRetryEvenWhenProjectionMissing() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val session = f.search.value.profileSessionId
        f.cached = null
        try { f.operations.refreshLocalProfile(session, 4L); fail("expected missing projection") }
        catch (_: IllegalStateException) { }
        f.retryCurrent()
        f.idle()
        assertFalse(f.search.value.profileSourceRetryAllowed)
        assertNull(f.search.value.detailIdentityToken())
        assertFalse(f.calls.any { it.startsWith("source:") })
    }

    @Test(timeout = 15_000) fun reopenedListingWaitsForOldSourceDrainAndBothCompletionsRefreshMap() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { started.complete(Unit); finish.awaitSignal(); error("late native response rejected") }
        f.operations.loadProfile(4L, 1L) {}
        started.awaitSignal()
        val oldSession = f.search.value.profileSessionId
        f.closeCurrent()
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

    @Test(timeout = 15_000) fun stopRevokesDetailDeliveryAndResumeReadsOnlyAfterSourceDrain() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { started.complete(Unit); finish.awaitSignal(); fixtureProfile(12L) }
        f.operations.loadProfile(4L, 1L) {}
        started.awaitSignal()
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

    @Test(timeout = 15_000) fun cancellationAfterNativeSourceCommitBeforeDeliveryStillCallsCompletion() = runBlocking {
        val f = Fixture(this)
        val continuation = CompletableDeferred<CancellableContinuation<ProfileResponse>>()
        f.source = { suspendCancellableCoroutine { continuation.complete(it) } }
        f.operations.loadProfile(4L, 1L) {}
        val reply = continuation.awaitSignal()
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

    @Test(timeout = 15_000) fun archiveUiRetainsOwnershipUntilNativeDrainAndSignalsCancelOnlyOnce() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { fixtureProfile() }
        f.archive = { snapshot, _ -> started.complete(Unit); finish.awaitSignal(); MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u) }
        f.operations.loadProfile(4L, 1L) {}
        started.awaitSignal()
        assertTrue(f.search.value.archiving)
        val session = f.search.value.profileSessionId
        f.operations.cancelMediaArchive(session, f.search.value.profileArchiveAttempt)
        f.operations.cancelMediaArchive(session, f.search.value.profileArchiveAttempt)
        assertEquals(1, f.controls.single().cancelled)
        assertTrue(f.search.value.archiving)
        assertTrue(coroutineContext[Job]!!.children.single().isActive)
        f.closeCurrent()
        assertEquals(1, f.controls.single().cancelled)
        f.calls.clear()
        finish.complete(Unit)
        f.idle()
        assertEquals(listOf("saved", "discovery"), f.calls)
        assertEquals(2, f.profileChanges)
        assertNull(f.search.value.mediaArchive)
        assertFalse(f.search.value.profileOpen)
    }

    @Test(timeout = 15_000) fun staleArchiveStopCannotCancelRefreshWithinSameSession() = runBlocking {
        val f = Fixture(this)
        f.source = { fixtureProfile() }
        f.operations.loadProfile(4L, 1L) {}
        f.idle()
        val token = requireNotNull(f.search.value.detailIdentityToken())
        val oldAttempt = f.search.value.profileArchiveAttempt
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.archive = { snapshot, _ ->
            started.complete(Unit)
            release.awaitSignal()
            MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u)
        }
        try {
            f.operations.refreshProfile(token)
            started.awaitSignal()
            assertEquals(token.sessionId, f.search.value.profileSessionId)
            val currentAttempt = f.search.value.profileArchiveAttempt
            assertNotEquals(oldAttempt, currentAttempt)
            f.operations.cancelMediaArchive(token.sessionId, oldAttempt)
            assertEquals(0, f.controls.last().cancelled)
            f.operations.cancelMediaArchive(token.sessionId, currentAttempt)
            assertEquals(1, f.controls.last().cancelled)
        } finally {
            release.complete(Unit)
        }
        f.idle()
    }

    @Test(timeout = 15_000) fun staleArchiveStopCannotCancelReplacementOfSameProfile() = runBlocking {
        val f = Fixture(this)
        val firstStarted = CompletableDeferred<Unit>()
        val firstFinish = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondFinish = CompletableDeferred<Unit>()
        f.source = { fixtureProfile() }
        f.archive = { snapshot, control ->
            if (control === f.controls.first()) {
                firstStarted.complete(Unit)
                firstFinish.awaitSignal()
            } else {
                secondStarted.complete(Unit)
                secondFinish.awaitSignal()
            }
            MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u)
        }
        try {
            f.operations.loadProfile(4L, 1L) {}
            firstStarted.awaitSignal()
            val old = f.search.value.profileSessionId
            f.closeCurrent()
            assertEquals(1, f.controls.single().cancelled)
            f.operations.loadProfile(4L, 1L) {}
            val current = f.search.value.profileSessionId
            assertNotEquals(old, current)
            f.operations.cancelMediaArchive(current, f.search.value.profileArchiveAttempt)
            assertEquals(1, f.controls.single().cancelled)
            firstFinish.complete(Unit)
            secondStarted.awaitSignal()
            assertTrue(f.search.value.archiving)
            f.operations.cancelMediaArchive(old, f.search.value.profileArchiveAttempt)
            assertEquals(0, f.controls.last().cancelled)
            f.operations.cancelMediaArchive(current, f.search.value.profileArchiveAttempt)
            f.operations.cancelMediaArchive(current, f.search.value.profileArchiveAttempt)
            assertEquals(1, f.controls.last().cancelled)
            assertTrue(f.search.value.archiving)
            secondFinish.complete(Unit)
            f.idle()
            assertFalse(f.search.value.archiving)
            f.operations.cancelMediaArchive(current, f.search.value.profileArchiveAttempt)
            assertEquals(1, f.controls.last().cancelled)
            assertEquals(2, f.calls.count { it.startsWith("archive:") })
        } finally {
            firstFinish.complete(Unit)
            secondFinish.complete(Unit)
        }
    }

    @Test(timeout = 15_000) fun partialArchiveFailureStillReprojectsCommittedMediaAndRevokesOldToken() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.source = { fixtureProfile() }
        f.archive = { _, _ ->
            started.complete(Unit)
            release.awaitSignal()
            f.archivedMedia = listOf(ProfileMediaItem("IMAGE", "https://example.invalid/changed.jpg", "/fixture/partial.jpg", 0u))
            error("partial archive failure")
        }
        f.operations.loadProfile(4L, 1L) {}
        started.awaitSignal()
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

    @Test(timeout = 15_000) fun failedPostArchiveProjectionRevokesActionsAndRetryReadsOnlyLocalState() = runBlocking {
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
        f.retryCurrent()
        f.idle()
        assertNotNull(f.search.value.detailIdentityToken())
        assertNull(f.search.value.retryProfileListingId)
        assertEquals(listOf("profile:4", "discovery"), f.calls)
    }

    @Test(timeout = 15_000) fun mergeWaitsForCancelledArchiveToActuallyDrainBeforeWrite() = runBlocking {
        val f = Fixture(this)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.source = { fixtureProfile() }
        f.archive = { snapshot, _ -> started.complete(Unit); finish.awaitSignal(); MediaArchiveReport(snapshot, 1u, 0u, 0u, 0u) }
        f.operations.loadProfile(4L, 1L) {}
        started.awaitSignal()
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

    @Test(timeout = 15_000) fun nativeMergeCommitWithCancelledDeliveryStillInvalidatesNotesAndProjections() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        f.oldNotes()
        val reply = CompletableDeferred<CancellableContinuation<Unit>>()
        val ticket = f.tickets.invalidate(1L)
        f.commit = { _, target -> suspendCancellableCoroutine { c -> f.owner = target; reply.complete(c) } }
        f.confirmCurrent()
        reply.awaitSignal().resume(Unit)
        coroutineContext[Job]!!.children.single().cancelAndJoin()
        assertEquals(2L, f.owner)
        assertEquals("confirm:4:2", f.calls.first())
        assertEquals(listOf("discovery", "home", "map", "saved"), f.calls.drop(1).sorted())
        assertFalse(f.tickets.isCurrent(1L, ticket))
        assertNull(f.search.value.profileIdentity)
        assertNull(f.search.value.profile)
        assertEquals(4L, f.search.value.retryProfileListingId)
        assertFalse(f.discovery.value.busy)
    }

    @Test(timeout = 15_000) fun mergeWithPendingReadReconcilesAfterDrainWithoutWritingAgain() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.commit = { _, target -> started.complete(Unit); finish.awaitSignal(); f.owner = target }
        f.confirmCurrent()
        started.awaitSignal()
        f.pending = true
        finish.complete(Unit)
        f.idle()
        assertEquals("confirm:4:2", f.calls.first())
        assertTrue(f.calls.indexOf("project:1") in 1 until f.calls.indexOf("discovery"))
        assertEquals(listOf("discovery", "home", "map", "profile:4", "project:1", "saved"), f.calls.drop(1).sorted())
        assertFalse(f.pending)
        assertFalse(f.discovery.value.busy)
    }
    @Test(timeout = 15_000) fun delayedConfirmCallbackCannotUseCurrentOwnerOfSameListingInNewSession() = runBlocking {
        val f = Fixture(this)
        f.openOffline()
        val oldSession = f.search.value.profileSessionId
        val oldToken = requireNotNull(f.search.value.detailIdentityToken())
        val oldTicket = f.tickets.invalidate(1L)
        f.closeCurrent()
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

    @Test(timeout = 15_000) fun swipeMergeUsesSameNativeWriteWithoutOpeningDetailAndRejectsStaleToken() = runBlocking {
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
        assertEquals("confirm:4:2", f.calls.first())
        assertEquals(listOf("home", "map", "project:1", "saved"), f.calls.drop(1).sorted())
        assertFalse(f.search.value.profileOpen)
        assertNull(f.search.value.profile)
        assertEquals(setOf(3L), f.notes.value.keys)
        assertFalse(f.discovery.value.busy)
    }
    @Test(timeout = 15_000) fun staleBackAndRetryCannotAffectReplacementOfSameListing() = runBlocking {
        val f = Fixture(this)
        f.cached = null
        f.operations.loadProfile(4L, 1L) {}
        f.idle()
        val old = requireNotNull(f.search.value.detailRetryAction())
        assertTrue(old.sourceAllowed)
        f.closeCurrent()
        f.operations.loadProfile(4L, 1L) {}
        f.idle()
        val current = requireNotNull(f.search.value.detailRetryAction())
        assertNotEquals(old.session, current.session)
        f.calls.clear()
        f.operations.closeProfile(old.session)
        f.operations.retryProfileProjection(old)
        // A locally labelled retry cannot inherit the current online permission.
        f.operations.retryProfileProjection(current.copy(sourceAllowed = false))
        f.idle()
        assertTrue(f.calls.isEmpty())
        assertTrue(f.search.value.profileOpen)
        assertEquals(current, f.search.value.detailRetryAction())
        f.source = { fixtureProfile(12L) }
        f.operations.retryProfileProjection(current)
        f.idle()
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        f.operations.closeProfile(current.session)
        assertTrue(f.search.value.profileOpen)
        f.closeCurrent()
        assertFalse(f.search.value.profileOpen)
    }

    @Test(timeout = 15_000) fun failedLocalRetryRevokesOldAttemptWithoutReplayingSource() = runBlocking {
        val f = Fixture(this)
        f.source = { fixtureProfile(12L) }
        f.identityError = IllegalStateException("local read failed")
        f.operations.loadProfile(4L, 1L) {}
        f.idle()
        val old = requireNotNull(f.search.value.detailRetryAction())
        assertFalse(old.sourceAllowed)
        f.operations.retryProfileProjection(old)
        f.idle()
        val current = requireNotNull(f.search.value.detailRetryAction())
        assertEquals(old.session, current.session)
        assertNotEquals(old.attempt, current.attempt)
        f.calls.clear()
        f.operations.retryProfileProjection(old)
        f.idle()
        assertTrue(f.calls.isEmpty())
        f.identityError = null
        f.operations.retryProfileProjection(current)
        f.idle()
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertFalse(f.calls.any { it.startsWith("source:") })
        assertNull(f.search.value.detailRetryAction())
    }

    @Test(timeout = 15_000) fun initialSourceCommitThenLocalReadFailureRetriesOnlyLocalProjection() = runBlocking {
        val f = Fixture(this)
        f.source = { fixtureProfile(12L) }
        f.identityError = IllegalStateException("local read failed")
        f.operations.loadProfile(4L, 1L) {}
        f.idle()
        assertEquals(4L, f.search.value.retryProfileListingId)
        assertNull(f.search.value.profile)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        f.identityError = null
        f.retryCurrent()
        f.idle()
        assertEquals(12L, f.search.value.profile?.snapshotId)
        assertEquals(1, f.calls.count { it.startsWith("source:") })
        assertTrue(f.controls.isEmpty())
    }
}
