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

class CoreStore(context: Context) {
    private val appFiles = context.filesDir
    private val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val explorerPreferences = context.getSharedPreferences("explorer", Context.MODE_PRIVATE)

    fun replayLikes(): Boolean = explorerPreferences.getBoolean("replay_likes", true)
    fun setReplayLikes(value: Boolean) { explorerPreferences.edit().putBoolean("replay_likes", value).apply() }
    private val logger = Logger.withTag("LadiesExplorer")
    private val logBridge = DiagnosticEventQueue()
    private val captureControls = Mutex()
    private val database = File(appFiles, "ladies.sqlite")
    private val engine: LadiesCore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LadiesCore(database.absolutePath).also {
            it.setDiagnosticDebugEnabled(preferences.getBoolean("debug", false))
        }
    }

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

    // Keep blocking image requests independent of profile/search work.
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

    private val displayVideoDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Clean up abandoned playback files after a crash; never touch the archive.
    private val displayVideoDirectory by lazy {
        File(context.cacheDir, "gallery-playback").apply {
            check(isDirectory || mkdirs()) { "Playback cache unavailable" }
            listFiles()?.filter { it.isFile && it.name.startsWith("video-") }?.forEach(File::delete)
        }
    }

    internal fun createDisplayVideoFile(): File =
        File.createTempFile("video-", ".media", displayVideoDirectory)

    suspend fun loadDisplayVideo(url: String): DisplayVideo = withContext(displayVideoDispatcher) {
        suspendCancellableCoroutine { continuation ->
            val control = RequestControl()
            continuation.invokeOnCancellation { control.cancel() }
            if (continuation.isActive) {
                try {
                    continuation.resumeWith(Result.success(engine.loadDisplayVideo(url, control)))
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

    suspend fun setSwipePreference(listingId: Long, personId: Long, expected: Preference?, preference: Preference): Preference =
        withContext(Dispatchers.IO) { engine.setSwipePreference(listingId, personId, expected, preference) }

    suspend fun setPersonPreferenceIf(personId: Long, expected: Preference, preference: Preference) = withContext(Dispatchers.IO) {
        recorded("setPersonPreferenceIf") { engine.setPersonPreferenceIf(personId, expected, preference) }
    }

    suspend fun setListingFavorite(listingId: Long, personId: Long, expected: Boolean, favorite: Boolean) =
        withContext(Dispatchers.IO) { engine.setListingFavorite(listingId, personId, expected, favorite) }

    suspend fun setPersonFavoriteIf(personId: Long, expected: Boolean, favorite: Boolean) = withContext(Dispatchers.IO) {
        recorded("setPersonFavoriteIf") { engine.setPersonFavoriteIf(personId, expected, favorite) }
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

    // Source profile reads and identity confirmation share native execution ownership.
    private val profileSourceMutex = Mutex()

    suspend fun loadProfile(listingId: Long, control: RequestControl): ProfileResponse =
        profileSourceMutex.withLock {
            withContext(Dispatchers.IO) {
                recorded("loadProfile listingId=$listingId") {
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

    suspend fun mapOverview(listingIds: List<Long>, runId: Long?): MapOverview = withContext(Dispatchers.IO) {
        recorded("mapOverview") { engine.mapOverview(listingIds, runId) }
    }

    suspend fun resolveMapLocations(listingIds: List<Long>, runId: Long?, control: RequestControl): UInt =
        withContext(Dispatchers.IO) {
            recorded("resolveMapLocations") { engine.resolveMapLocations(listingIds, runId, 15u, control) }
        }

    suspend fun profileFacts(snapshotId: Long, contextJson: String): ProfileFacts =
        withContext(Dispatchers.IO) { engine.profileFacts(snapshotId, contextJson) }

    suspend fun personDetail(personId: Long): PersonDetail =
        withContext(Dispatchers.IO) { engine.personDetail(personId) }

    suspend fun personNote(listingId: Long, personId: Long): String? =
        withContext(Dispatchers.IO) { engine.personNote(listingId, personId) }

    suspend fun savePersonNote(listingId: Long, personId: Long, expected: String?, note: String?) =
        withContext(Dispatchers.IO) { engine.savePersonNote(listingId, personId, expected, note) }

    suspend fun encounters(listingId: Long, personId: Long): List<Encounter> =
        withContext(Dispatchers.IO) { engine.encounters(listingId, personId) }

    suspend fun createEncounter(listingId: Long, personId: Long, values: EncounterValues): Encounter =
        withContext(Dispatchers.IO) { engine.createEncounter(listingId, personId, values) }

    suspend fun updateEncounter(
        listingId: Long, personId: Long, expected: Encounter, values: EncounterValues,
    ): Encounter = withContext(Dispatchers.IO) {
        engine.updateEncounter(listingId, personId, expected, values)
    }

    suspend fun deleteEncounter(listingId: Long, personId: Long, expected: Encounter) =
        withContext(Dispatchers.IO) { engine.deleteEncounter(listingId, personId, expected) }

    suspend fun priceOverview(listingId: Long, personId: Long): PriceOverview =
        withContext(Dispatchers.IO) { engine.priceOverview(listingId, personId) }

    suspend fun createPriceList(listingId: Long, personId: Long, values: PriceListValues): PriceList =
        withContext(Dispatchers.IO) { engine.createPriceList(listingId, personId, values) }

    suspend fun updatePriceList(
        listingId: Long, personId: Long, expected: PriceList, values: PriceListValues,
    ): PriceList = withContext(Dispatchers.IO) {
        engine.updatePriceList(listingId, personId, expected, values)
    }

    suspend fun deletePriceList(listingId: Long, personId: Long, expected: PriceList) =
        withContext(Dispatchers.IO) { engine.deletePriceList(listingId, personId, expected) }

    suspend fun compareSnapshots(personId: Long, fromId: Long, toId: Long): List<SnapshotDifference> =
        withContext(Dispatchers.IO) { engine.compareSnapshots(personId, fromId, toId) }

    suspend fun profileProjection(listingId: Long): NativeProfileProjection? =
        withContext(Dispatchers.IO) { engine.profileProjection(listingId) }

    suspend fun profileIdentity(listingId: Long): ProfileIdentity =
        withContext(Dispatchers.IO) { engine.profileIdentity(listingId) }

    suspend fun confirmIdentityMatch(listingId: Long, expectedSourcePersonId: Long, expectedSnapshotId: Long, targetPersonId: Long) =
        profileSourceMutex.withLock {
            withContext(Dispatchers.IO) {
                recorded("confirmIdentityMatch listing=$listingId") {
                    engine.confirmIdentityMatch(listingId, expectedSourcePersonId, expectedSnapshotId, targetPersonId)
                }
            }
        }

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
