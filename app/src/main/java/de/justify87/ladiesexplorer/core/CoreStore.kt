package de.justify87.ladiesexplorer.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android access point to generated UniFFI bindings. Rust owns the local database,
 * its import transaction and the archived media files.
 */
class CoreStore(context: Context) {
    private val appFiles = context.filesDir
    private val database = File(appFiles, "ladies.sqlite")
    private val engine: LadiesCore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LadiesCore(database.absolutePath)
    }

    suspend fun status(): CoreStatus = withContext(Dispatchers.IO) {
        engine.status()
    }

    suspend fun people(
        limit: UInt = 50u,
        cursor: PersonCursor? = null,
    ): PersonPage = withContext(Dispatchers.IO) {
        engine.listPeople(limit, cursor)
    }

    suspend fun setPreference(personId: Long, preference: Preference) = withContext(Dispatchers.IO) {
        engine.setPreference(personId, preference)
    }

    suspend fun setFavorite(personId: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        engine.setFavorite(personId, favorite)
    }

    suspend fun importLegacyBundle(file: File): ImportReport = withContext(Dispatchers.IO) {
        engine.importLegacyBundle(file.absolutePath, appFiles.absolutePath)
    }

    suspend fun search(query: SearchRequest): SearchResponse = withContext(Dispatchers.IO) {
        engine.searchSource(query)
    }

    suspend fun loadProfile(listingId: Long): ProfileResponse = withContext(Dispatchers.IO) {
        engine.loadSourceProfile(listingId)
    }
}
