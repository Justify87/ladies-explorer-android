package de.justify87.ladiesexplorer.core

import android.content.Context
import android.os.SystemClock
import co.touchlab.kermit.Logger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android access to the Rust database, online source service and diagnostics. */
class CoreStore(context: Context) {
    private val appFiles = context.filesDir
    private val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val logger = Logger.withTag("LadiesExplorer")
    @Volatile var imageCaptureMode: CaptureMode = CaptureMode.OFF
        private set
    private val captureEpoch = AtomicLong(0)
    fun currentCaptureEpoch(): Long = captureEpoch.get()
    // Only best-effort events are bounded. Control barriers are never rejected.
    private val logBridge = Executors.newSingleThreadExecutor()
    private val pendingEvents = AtomicInteger(0)
    private fun dispatchEvent(work: () -> Unit) {
        if (pendingEvents.incrementAndGet() > 128) {
            pendingEvents.decrementAndGet()
            return
        }
        logBridge.execute {
            try {
                work()
            } finally {
                pendingEvents.decrementAndGet()
            }
        }
    }
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
        // The bounded bridge never blocks a Compose callback or an OkHttp hot path.
        dispatchEvent {
            runCatching { engine.recordKotlinEvent("ANDROID", message, false) }
        }
    }

    fun enqueueImageObservation(observation: HttpObservation, epoch: Long) {
        if (imageCaptureMode == CaptureMode.OFF || epoch != captureEpoch.get()) return
        dispatchEvent {
            // In-flight image calls from a previous recording session cannot leak into the new one.
            if (epoch == captureEpoch.get()) {
                runCatching { engine.recordImageHttp(observation) }
            }
        }
    }

    suspend fun httpCaptureStatus(): CaptureStatus = withContext(Dispatchers.IO) {
        engine.httpCaptureStatus().also { imageCaptureMode = it.mode }
    }

    suspend fun setHttpCaptureMode(mode: CaptureMode): CaptureStatus = withContext(Dispatchers.IO) {
        captureEpoch.incrementAndGet()
        engine.setHttpCaptureMode(mode).also { imageCaptureMode = it.mode }
    }

    suspend fun clearHttpCapture() = withContext(Dispatchers.IO) {
        // Serialize with all pending image observations before deleting the session.
        captureEpoch.incrementAndGet()
        imageCaptureMode = CaptureMode.OFF
        engine.setHttpCaptureMode(CaptureMode.OFF)
        // An unbounded control barrier follows at most 128 pending diagnostic events.
        logBridge.submit(Runnable { }).get()
        engine.clearHttpCapture()
    }

    suspend fun createHarSnapshot(): File = withContext(Dispatchers.IO) {
        val oldestAllowed = System.currentTimeMillis() - 48L * 60L * 60L * 1_000L
        diagnosticExports.listFiles()
            ?.filter { it.isFile && it.name.startsWith("ladies-http-") && it.lastModified() < oldestAllowed }
            ?.forEach(File::delete)
        File(engine.exportHttpHar(diagnosticExports.absolutePath))
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

    suspend fun search(query: SearchRequest, control: RequestControl): SearchResponse = withContext(Dispatchers.IO) {
        recorded("search city=${query.city} radius=${query.radiusKm}") {
            engine.searchSource(query, control)
        }
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

    suspend fun cachedProfile(listingId: Long): ProfileResponse? = withContext(Dispatchers.IO) {
        engine.cachedSourceProfile(listingId)
    }

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

    suspend fun saveSearch(name: String, request: SearchRequest): String =
        withContext(Dispatchers.IO) { engine.saveNativeSearch(name, request) }

    suspend fun updateSavedSearch(id: String, name: String, request: SearchRequest) =
        withContext(Dispatchers.IO) { engine.updateNativeSearch(id, name, request) }

    suspend fun loadSavedSearch(id: String): SearchRequest =
        withContext(Dispatchers.IO) { engine.loadSavedSearch(id) }

    suspend fun savedSearchResults(
        id: String,
        cursor: SavedResultCursor? = null,
    ): SavedSearchResultPage = withContext(Dispatchers.IO) {
        engine.savedSearchResults(id, 100u, cursor)
    }

    suspend fun runSavedSearch(id: String, control: RequestControl): SearchResponse =
        withContext(Dispatchers.IO) { recorded("runSavedSearch") { engine.runSavedSearch(id, control) } }

    suspend fun deleteSavedSearch(id: String) =
        withContext(Dispatchers.IO) { engine.deleteSavedSearch(id) }

    suspend fun acknowledgeSavedSearch(id: String) =
        withContext(Dispatchers.IO) { engine.acknowledgeSavedSearch(id) }
}
