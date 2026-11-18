package de.justify87.ladiesexplorer.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android access to the Rust database, online source service and diagnostics. */
class CoreStore(context: Context) {
    private val appFiles = context.filesDir
    private val database = File(appFiles, "ladies.sqlite")
    private val engine: LadiesCore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LadiesCore(database.absolutePath)
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

    private inline fun <T> recorded(operation: String, work: () -> T): T {
        runCatching { engine.recordDebugEvent("ANDROID", "$operation started") }
        return try {
            val result = work()
            runCatching { engine.recordDebugEvent("ANDROID", "$operation completed") }
            result
        } catch (failure: Exception) {
            runCatching {
                engine.recordDebugEvent(
                    "ANDROID",
                    "$operation failed: ${failure.stackTraceToString().take(3000)}",
                )
            }
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

    suspend fun search(query: SearchRequest): SearchResponse = withContext(Dispatchers.IO) {
        recorded("search city=${query.city} radius=${query.radiusKm}") {
            engine.searchSource(query)
        }
    }

    suspend fun archiveProfileMedia(snapshotId: Long, control: MediaArchiveControl): MediaArchiveReport =
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

    suspend fun sourceFilterCatalog(): SourceFilterCatalog = withContext(Dispatchers.IO) {
        engine.sourceFilterCatalog()
    }

    suspend fun discoveryCards(favoritesOnly: Boolean = false): List<DiscoveryCard> =
        withContext(Dispatchers.IO) { engine.discoveryCards(favoritesOnly, 200u) }

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

    suspend fun savedSearchResults(id: String): List<SavedSearchCard> =
        withContext(Dispatchers.IO) { engine.savedSearchResults(id) }

    suspend fun runSavedSearch(id: String): SearchResponse =
        withContext(Dispatchers.IO) { recorded("runSavedSearch") { engine.runSavedSearch(id) } }

    suspend fun deleteSavedSearch(id: String) =
        withContext(Dispatchers.IO) { engine.deleteSavedSearch(id) }

    suspend fun acknowledgeSavedSearch(id: String) =
        withContext(Dispatchers.IO) { engine.acknowledgeSavedSearch(id) }
}
