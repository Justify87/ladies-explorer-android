package de.justify87.ladiesexplorer.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android's single access point to the generated UniFFI bindings.
 * Every blocking Rust call runs on Dispatchers.IO, never on the UI thread.
 */
class CoreStore(context: Context) {
    private val database = File(context.filesDir, "ladies.sqlite")
    private val engine: LadiesCore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LadiesCore(database.absolutePath)
    }

    suspend fun status(): CoreStatus = withContext(Dispatchers.IO) {
        engine.status()
    }

    suspend fun saveTestNote(text: String): CoreStatus = withContext(Dispatchers.IO) {
        engine.saveNote("scaffold", text)
        engine.status()
    }

    suspend fun loadTestNote(): String? = withContext(Dispatchers.IO) {
        engine.loadNote("scaffold")
    }
}
