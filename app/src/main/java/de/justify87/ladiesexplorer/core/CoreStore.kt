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
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Global result policy is independent of stored search definitions and replay. */
data class GlobalResultVisibility(val hideLikes: Boolean = false, val hideDislikes: Boolean = false) {
    fun effective(local: LocalVisibility): LocalVisibility = local.copy(
        hideLikes = local.hideLikes || hideLikes,
        hideDislikes = local.hideDislikes || hideDislikes,
    )
}

/** Session correlation without exporting the source URL or local path. */
internal fun mediaDiagnosticKey(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

// Reference tokens are data, not error evidence (e.g. example.invalid or token=timeout).
private val diagnosticReference = Regex("""[^\s"'<>]*[/=][^\s"'<>]+""")

/** Classify external diagnostics without persisting arbitrary paths, tokens or metadata. */
internal fun mediaDiagnosticReason(text: String): String {
    val value = diagnosticReference.replace(text.take(4096), " ").lowercase(java.util.Locale.ROOT)
    return when {
        "timeout" in value || "timed out" in value -> "timeout"
        "permission denied" in value -> "permission_denied"
        "no space" in value -> "storage_full"
        "not found" in value || "no such file" in value -> "missing_source"
        "unsupported" in value -> "unsupported"
        "invalid" in value || "corrupt" in value || "truncated" in value -> "invalid_data"
        "decode" in value || "decoder" in value -> "decode"
        "demux" in value || "container" in value -> "container"
        "surface" in value || "render" in value -> "surface"
        "memory" in value || "allocation" in value -> "memory"
        "network" in value || "connection" in value -> "transport"
        "cancel" in value -> "cancelled"
        else -> "other"
    }
}

internal fun diagnosticFailure(failure: Throwable, includeMessage: Boolean = false): String {
    val chain = generateSequence(failure) { it.cause?.takeUnless { next -> next === it } }.take(4).toList()
    val causes = chain.joinToString(">") { it.javaClass.simpleName }
    val frames = failure.stackTrace.filter { it.className.startsWith("de.justify87.") }
        .take(3).joinToString(",") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
    val reasons = if (includeMessage) chain.mapNotNull { it.message }.map(::mediaDiagnosticReason).distinct().joinToString(",") else ""
    return "type=$causes frames=$frames" + if (reasons.isNotBlank()) " reason=$reasons" else ""
}

class CoreStore(context: Context) {
    private val appFiles = context.filesDir
    private val preferences = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
    private val explorerPreferences = context.getSharedPreferences("explorer", Context.MODE_PRIVATE)

    fun replayLikes(): Boolean = explorerPreferences.getBoolean("replay_likes", true)
    fun setReplayLikes(value: Boolean) { explorerPreferences.edit().putBoolean("replay_likes", value).apply() }

    fun globalResultVisibility() = GlobalResultVisibility(
        hideLikes = explorerPreferences.getBoolean("hide_search_likes", false),
        hideDislikes = explorerPreferences.getBoolean("hide_search_dislikes", false),
    )

    fun setGlobalResultVisibility(value: GlobalResultVisibility) {
        explorerPreferences.edit()
            .putBoolean("hide_search_likes", value.hideLikes)
            .putBoolean("hide_search_dislikes", value.hideDislikes)
            .apply()
    }
    private val logger = Logger.withTag("LadiesExplorer")
    private val logBridge = DiagnosticEventQueue()
    private val captureControls = Mutex()
    private val debugControls = Mutex()
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
        logBridge.barrier()
        val (dropped, failed) = logBridge.lossSummary()
        if (dropped != 0L || failed != 0L) engine.recordKotlinEvent("DIAGNOSTICS",
            "bridge_loss dropped=$dropped failed=$failed", DiagnosticLevel.WARN)
        File(engine.exportDiagnosticSnapshot(diagnosticExports.absolutePath))
    }

    fun isDebugLoggingEnabled(): Boolean = preferences.getBoolean("debug", false)

    suspend fun setDebugLoggingEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        debugControls.withLock {
            if (enabled) {
                engine.setDiagnosticDebugEnabled(true)
                preferences.edit().putBoolean("debug", true).apply()
            } else {
                synchronized(logBridge) { preferences.edit().putBoolean("debug", false).apply() }
                try {
                    logBridge.barrier()
                } finally {
                    engine.setDiagnosticDebugEnabled(false)
                }
            }
        }
    }

    internal fun diagnostic(component: String, level: DiagnosticLevel, message: String, failure: Throwable? = null) {
        if (level == DiagnosticLevel.DEBUG && !isDebugLoggingEnabled()) return
        val mediaFailure = component in setOf("COIL", "PREPARE", "VIDEO_CACHE", "VIDEO", "MAP_MEDIA", "MPV")
        val occurred = "event_epoch_ms=${System.currentTimeMillis()} event_elapsed_ms=${SystemClock.elapsedRealtime()}"
        val safe = "$occurred $message" + if (failure == null) "" else " ${diagnosticFailure(failure, mediaFailure)}"
        when (level) {
            DiagnosticLevel.DEBUG -> logger.d { "$component $safe" }
            DiagnosticLevel.INFO -> logger.i { "$component $safe" }
            DiagnosticLevel.WARN -> logger.w { "$component $safe" }
            DiagnosticLevel.ERROR -> logger.e { "$component $safe" }
        }
        val critical = level == DiagnosticLevel.WARN || level == DiagnosticLevel.ERROR
        val admitted = synchronized(logBridge) {
            // Serialize the final admission check with disabling DEBUG and the export fence.
            if (level == DiagnosticLevel.DEBUG && !isDebugLoggingEnabled()) return
            logBridge.offer(critical) { engine.recordKotlinEvent(component, safe, level) }
        }
        if (!admitted && critical) {
            logger.w { "Diagnostic bridge saturated; loss reported in export" }
        }
    }

    private fun event(operation: String, durationMs: Long, failure: Exception? = null) {
        when (failure) {
            null -> diagnostic("ANDROID", DiagnosticLevel.INFO, "$operation completed duration_ms=$durationMs")
            is CancellationException -> diagnostic("ANDROID", DiagnosticLevel.DEBUG, "$operation cancelled duration_ms=$durationMs")
            else -> diagnostic("ANDROID", DiagnosticLevel.ERROR, "$operation failed duration_ms=$durationMs", failure)
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

    suspend fun loadDefaultSearch(): SavedSearchDefinition? = withContext(Dispatchers.IO) {
        recorded("loadDefaultSearch") { engine.loadDefaultSearch() }
    }

    suspend fun saveDefaultSearch(query: SearchRequest, local: LocalVisibility): SavedSearchDefinition =
        withContext(Dispatchers.IO) {
            recorded("saveDefaultSearch") { engine.saveDefaultSearch(query, local) }
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

    suspend fun prepareMapLocations(listingIds: List<Long>, runId: Long?, control: RequestControl) =
        withContext(Dispatchers.IO) {
            recorded("prepareMapLocations") { engine.prepareMapLocations(listingIds, runId, control) }
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
