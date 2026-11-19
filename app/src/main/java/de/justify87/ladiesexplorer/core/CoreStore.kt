package de.justify87.ladiesexplorer.core

import android.content.Context
import android.os.SystemClock
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Android access to the Rust database, online source service and diagnostics. */
class CoreStore(context: Context) {
    private val appFiles = context.filesDir
    private val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val explorerPreferences = context.getSharedPreferences("explorer", Context.MODE_PRIVATE)

    fun replayLikes(): Boolean = explorerPreferences.getBoolean("replay_likes", true)
    fun setReplayLikes(value: Boolean) { explorerPreferences.edit().putBoolean("replay_likes", value).apply() }
    private val logger = Logger.withTag("LadiesExplorer")
    // Only best-effort events are bounded. Control barriers are never rejected.
    private val logBridge = DiagnosticEventQueue()
    private val captureControls = Mutex()
    private val database = File(appFiles, "ladies.sqlite")
    private val engine: LadiesCore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LadiesCore(database.absolutePath).also {
            it.setDiagnosticDebugEnabled(preferences.getBoolean("debug", false))
        }
    }

    /** App-private, size-bounded log; shared only through explicit user interaction. */
    private val diagnosticExports = File(context.cacheDir, "diagnostic-exports")

    suspend fun diagnosticStatus(): DiagnosticStatus = withContext(Dispatchers.IO) {
        engine.diagnosticStatus()
    }

    suspend fun createDiagnosticSnapshot(): File = withContext(Dispatchers.IO) {
        val oldestAllowed = System.currentTimeMillis() - 48L * 60L * 60L * 1_000L
        diagnosticExports.listFiles()
            ?.filter { it.isFile && it.name.startsWith("ladies-debug-") && it.lastModified() < oldestAllowed }
            ?.forEach(File::delete)
        File(engine.exportDiagnosticSnapshot(diagnosticExports.absolutePath))
    }

    fun isDebugLoggingEnabled(): Boolean = preferences.getBoolean("debug", false)

    suspend fun setDebugLoggingEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean("debug", enabled).apply()
        engine.setDiagnosticDebugEnabled(enabled)
    }

    private fun event(operation: String, durationMs: Long, failure: Exception? = null) {
        val message = if (failure == null) {
            "$operation completed duration_ms=$durationMs"
        } else {
            "$operation failed duration_ms=$durationMs type=${failure.javaClass.simpleName}"
        }
        if (failure == null) logger.i { message } else logger.e(failure) { message }
        // The bounded bridge never blocks a Compose callback.
        logBridge.offer {
            runCatching { engine.recordKotlinEvent("ANDROID", message, false) }
        }
    }

    suspend fun httpCaptureStatus(): CaptureStatus = withContext(Dispatchers.IO) {
        captureControls.withLock { engine.httpCaptureStatus() }
    }

    suspend fun setHttpCaptureMode(mode: CaptureMode): CaptureStatus = withContext(Dispatchers.IO) {
        captureControls.withLock { engine.setHttpCaptureMode(mode) }
    }

    suspend fun clearHttpCapture() = withContext(Dispatchers.IO) {
        captureControls.withLock { engine.clearHttpCapture() }
    }

    suspend fun createHarSnapshot(): File = withContext(Dispatchers.IO) {
        captureControls.withLock {
            val oldestAllowed = System.currentTimeMillis() - 48L * 60L * 60L * 1_000L
            diagnosticExports.listFiles()
                ?.filter { it.isFile && it.name.startsWith("ladies-http-") && it.lastModified() < oldestAllowed }
                ?.forEach(File::delete)
            File(engine.exportHttpHar(diagnosticExports.absolutePath))
        }
    }

    // Limit concurrent blocking native image requests, independently of profile/search work.
    private val displayImageDispatcher = Dispatchers.IO.limitedParallelism(4)

    suspend fun loadDisplayImage(url: String): DisplayImage = withContext(displayImageDispatcher) {
        suspendCancellableCoroutine { continuation ->
            val control = RequestControl()
            continuation.invokeOnCancellation { control.cancel() }
            if (continuation.isActive) {
                try {
                    continuation.resumeWith(Result.success(engine.loadDisplayImage(url, control)))
                } catch (failure: Exception) {
                    continuation.resumeWith(Result.failure(failure))
                }
            }
        }
    }

    private inline fun <T> recorded(operation: String, work: () -> T): T {
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            return work().also {
                event(operation, (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000)
            }
        } catch (failure: Exception) {
            event(operation, (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000, failure)
            throw failure
        }
    }

    suspend fun status(): CoreStatus = withContext(Dispatchers.IO) {
        recorded("status") { engine.status() }
    }

    suspend fun person(personId: Long): PersonSummary? = withContext(Dispatchers.IO) {
        engine.getPerson(personId)
    }

    suspend fun people(
        limit: UInt = 50u,
        cursor: PersonCursor? = null,
    ): PersonPage = withContext(Dispatchers.IO) {
        recorded("people") { engine.listPeople(limit, cursor) }
    }

    suspend fun setSwipePreference(listingId: Long, personId: Long, expected: Preference?, preference: Preference): Preference =
        withContext(Dispatchers.IO) { engine.setSwipePreference(listingId, personId, expected, preference) }

    suspend fun setPreference(personId: Long, preference: Preference) = withContext(Dispatchers.IO) {
        recorded("setPreference") { engine.setPreference(personId, preference) }
    }

    suspend fun setFavorite(personId: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        recorded("setFavorite") { engine.setFavorite(personId, favorite) }
    }

    suspend fun importLegacyBundle(file: File): ImportReport = withContext(Dispatchers.IO) {
        recorded("importLegacyBundle") {
            engine.importLegacyBundle(file.absolutePath, appFiles.absolutePath)
        }
    }

    suspend fun preview(query: SearchRequest, control: RequestControl): SearchPreview =
        withContext(Dispatchers.IO) {
            recorded("preview city=${query.city} radius=${query.radiusKm}") {
                engine.previewSource(query, control)
            }
        }

    suspend fun search(query: SearchRequest, local: LocalVisibility, control: RequestControl): SearchResponse = withContext(Dispatchers.IO) {
        recorded("search city=${query.city} radius=${query.radiusKm}") {
            engine.searchSource(query, local, control)
        }
    }

    suspend fun activeSearchCards(runId: Long, local: LocalVisibility): List<DiscoveryCard> =
        withContext(Dispatchers.IO) {
            engine.activeSearchCards(runId, local)
        }

    suspend fun archiveProfileMedia(snapshotId: Long, control: RequestControl): MediaArchiveReport =
        withContext(Dispatchers.IO) {
            recorded("archiveProfileMedia snapshotId=$snapshotId") {
                engine.archiveProfileMedia(snapshotId, false, control)
            }
        }

    suspend fun loadProfile(listingId: Long): ProfileResponse = withContext(Dispatchers.IO) {
        recorded("loadProfile listingId=$listingId") {
            engine.loadSourceProfile(listingId)
        }
    }

    // One current/standalone profile request at a time for the swipe window.
    private val swipeProfileMutex = Mutex()

    suspend fun loadSwipeProfile(listingId: Long, control: RequestControl): ProfileResponse =
        swipeProfileMutex.withLock {
            withContext(Dispatchers.IO) {
                recorded("loadSwipeProfile listingId=$listingId") {
                    engine.loadSwipeProfile(listingId, control)
                }
            }
        }

    suspend fun cachedProfile(listingId: Long): ProfileResponse? = withContext(Dispatchers.IO) {
        engine.cachedSourceProfile(listingId)
    }

    suspend fun frequentFilters(): List<FrequentFilter> =
        withContext(Dispatchers.IO) { engine.frequentFilters() }

    suspend fun sourceFilterCatalog(): SourceFilterCatalog = withContext(Dispatchers.IO) {
        engine.sourceFilterCatalog()
    }

    suspend fun discoveryCards(
        favoritesOnly: Boolean = false,
        cursor: DiscoveryCursor? = null,
        excludedPeople: List<Long> = emptyList(),
    ): DiscoveryPage = withContext(Dispatchers.IO) {
        engine.discoveryCards(favoritesOnly, 50u, cursor, excludedPeople)
    }

    suspend fun savedStatusTargets(): List<Long> = withContext(Dispatchers.IO) {
        engine.savedStatusTargets()
    }

    suspend fun checkSavedStatus(listingId: Long, control: RequestControl): String = withContext(Dispatchers.IO) {
        recorded("checkSavedStatus listingId=$listingId") { engine.checkSavedStatus(listingId, control) }
    }

    suspend fun savedPeopleWindow(
        kind: SavedPersonKind,
        sort: SavedPersonSort,
        limits: Pair<UInt, UInt>,
    ): SavedPeopleWindow = withContext(Dispatchers.IO) {
        engine.savedPeopleWindow(kind, sort, limits.first, limits.second)
    }

    suspend fun mapOverview(favoritesOnly: Boolean): MapOverview = withContext(Dispatchers.IO) {
        recorded("mapOverview") { engine.mapOverview(favoritesOnly) }
    }

    suspend fun resolveMapLocations(favoritesOnly: Boolean, control: RequestControl): UInt =
        withContext(Dispatchers.IO) {
            recorded("resolveMapLocations") { engine.resolveMapLocations(favoritesOnly, 15u, control) }
        }

    suspend fun profileFacts(snapshotId: Long, filtersJson: String): ProfileFacts =
        withContext(Dispatchers.IO) { engine.profileFacts(snapshotId, filtersJson) }

    suspend fun personDetail(personId: Long): PersonDetail =
        withContext(Dispatchers.IO) { engine.personDetail(personId) }

    suspend fun personNote(listingId: Long, personId: Long): String? =
        withContext(Dispatchers.IO) { engine.personNote(listingId, personId) }

    suspend fun savePersonNote(listingId: Long, personId: Long, expected: String?, note: String?) =
        withContext(Dispatchers.IO) { engine.savePersonNote(listingId, personId, expected, note) }

    suspend fun compareSnapshots(personId: Long, fromId: Long, toId: Long): List<SnapshotDifference> =
        withContext(Dispatchers.IO) { engine.compareSnapshots(personId, fromId, toId) }

    suspend fun profileIdentity(listingId: Long): ProfileIdentity =
        withContext(Dispatchers.IO) { engine.profileIdentity(listingId) }

    suspend fun profileMedia(snapshotId: Long): List<ProfileMediaItem> =
        withContext(Dispatchers.IO) { engine.profileMedia(snapshotId) }

    suspend fun identityCandidates(listingId: Long): List<IdentityCandidate> =
        withContext(Dispatchers.IO) { engine.listIdentityCandidates(listingId) }

    suspend fun confirmIdentityMatch(listingId: Long, targetPersonId: Long) =
        withContext(Dispatchers.IO) {
            recorded("confirmIdentityMatch listing=$listingId") {
                engine.confirmIdentityMatch(listingId, targetPersonId)
            }
        }

    suspend fun changeEvents(personId: Long): List<ChangeRecord> =
        withContext(Dispatchers.IO) { engine.listChangeEvents(personId, 100u) }

    suspend fun savedSearches(): List<SavedSearchInfo> =
        withContext(Dispatchers.IO) { engine.listSavedSearches() }

    suspend fun saveSearch(name: String, request: SearchRequest, local: LocalVisibility): String =
        withContext(Dispatchers.IO) { engine.saveSearchDefinition(name, request, local) }

    suspend fun updateSavedSearch(id: String, name: String, request: SearchRequest, local: LocalVisibility) =
        withContext(Dispatchers.IO) { engine.updateSearchDefinition(id, name, request, local) }

    suspend fun loadSavedSearch(id: String): SavedSearchDefinition =
        withContext(Dispatchers.IO) { engine.loadSearchDefinition(id) }

    suspend fun savedSearchWindow(id: String, limit: UInt): SavedSearchWindow =
        withContext(Dispatchers.IO) { engine.savedSearchWindow(id, limit) }

    suspend fun runSavedSearch(id: String, control: RequestControl): SearchResponse =
        withContext(Dispatchers.IO) { recorded("runSavedSearch") { engine.runSavedSearch(id, control) } }

    suspend fun renameSavedSearch(id: String, name: String) =
        withContext(Dispatchers.IO) { engine.renameSavedSearch(id, name) }

    suspend fun deleteSavedSearch(id: String) =
        withContext(Dispatchers.IO) { engine.deleteSavedSearch(id) }

    suspend fun acknowledgeSavedSearch(id: String, revision: Long): Boolean =
        withContext(Dispatchers.IO) { engine.acknowledgeSavedSearchRevision(id, revision) }
}
