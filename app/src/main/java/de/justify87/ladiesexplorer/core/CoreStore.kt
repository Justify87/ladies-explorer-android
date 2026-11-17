package de.justify87.ladiesexplorer.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The application has one Rust-owned database. Kotlin uses generated UniFFI types
 * and never opens SQLite directly or blocks the UI thread.
 */
class CoreStore(context: Context) {
    private val database = File(context.filesDir, "ladies.sqlite")
    private val engine: LadiesCore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LadiesCore(database.absolutePath)
    }

    suspend fun status(): CoreStatus = withContext(Dispatchers.IO) {
        engine.status()
    }

    suspend fun people(limit: UInt = 50u): List<PersonSummary> = withContext(Dispatchers.IO) {
        engine.listPeople(limit)
    }

    suspend fun setPreference(personId: Long, preference: Preference) = withContext(Dispatchers.IO) {
        engine.setPreference(personId, preference)
    }

    suspend fun setFavorite(personId: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        engine.setFavorite(personId, favorite)
    }
}
