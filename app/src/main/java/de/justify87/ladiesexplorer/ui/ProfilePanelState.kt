package de.justify87.ladiesexplorer.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Reads survive scrolling; replacing the projection cancels them. */
internal class ProfilePanels(
    private val scope: CoroutineScope,
    private val personId: Long?,
    private val readFacts: suspend (Long, String) -> ProfileFacts,
    private val readDetail: suspend (Long) -> PersonDetail,
    private val readComparison: suspend (Long, Long, Long) -> List<SnapshotDifference>,
) {
    private data class FactsKey(val snapshot: Long?, val filters: String)
    private var factsKey: FactsKey? = null
    var factsGeneration by mutableLongStateOf(0L)
        private set
    private var detailRequested = false
    var detailGeneration by mutableLongStateOf(0L)
        private set
    private var closed = false
    private var factsRetry: Job? = null
    private var detailRetry: Job? = null
    private var comparisonJob: Job? = null
    var facts by mutableStateOf<ProfileFacts?>(null)
        private set
    var detail by mutableStateOf<PersonDetail?>(null)
        private set
    var factsError by mutableStateOf<String?>(null)
        private set
    var detailError by mutableStateOf<String?>(null)
        private set
    var comparison by mutableStateOf<List<SnapshotDifference>?>(null)
        private set
    var comparisonError by mutableStateOf<String?>(null)
        private set
    var comparing by mutableStateOf(false)
        private set
    var comparisonGeneration by mutableIntStateOf(0)
        private set

    fun retryFacts(renderedGeneration: Long) {
        val key = factsKey ?: return
        if (closed || factsError == null || renderedGeneration != factsGeneration) return
        factsKey = null
        factsRetry = scope.launch(start = CoroutineStart.UNDISPATCHED) { loadFacts(key.snapshot, key.filters) }
    }

    fun retryDetail(renderedGeneration: Long) {
        if (closed || detailError == null || renderedGeneration != detailGeneration) return
        detailRequested = false
        detailRetry = scope.launch(start = CoroutineStart.UNDISPATCHED) { loadDetail() }
    }

    suspend fun loadFacts(snapshot: Long?, filters: String) {
        val key = FactsKey(snapshot, filters)
        if (closed || factsKey == key) return
        factsKey = key
        val generation = ++factsGeneration
        facts = null
        factsError = null
        try {
            val result = if (snapshot != null) readFacts(snapshot, filters)
                else error("Kein gespeicherter Snapshot")
            if (!closed && generation == factsGeneration) facts = result
        } catch (cancel: CancellationException) {
            if (generation == factsGeneration) factsKey = null
            throw cancel
        } catch (failure: Exception) {
            if (!closed && generation == factsGeneration)
                factsError = failure.message ?: "Profilfelder nicht verfügbar"
        }
    }

    suspend fun loadDetail() {
        if (closed || detailRequested) return
        detailRequested = true
        val generation = ++detailGeneration
        detail = null
        detailError = null
        try {
            val result = readDetail(personId ?: error("Personenzuordnung nicht verfügbar"))
            if (!closed && generation == detailGeneration) detail = result
        } catch (cancel: CancellationException) {
            if (generation == detailGeneration) detailRequested = false
            throw cancel
        } catch (failure: Exception) {
            if (!closed && generation == detailGeneration)
                detailError = failure.message ?: "Personendaten nicht verfügbar"
        }
    }

    fun compare(from: Long, to: Long) {
        if (closed) return
        val owner = personId ?: return
        val generation = ++comparisonGeneration
        comparisonJob?.cancel()
        comparing = true
        comparison = null
        comparisonError = null
        comparisonJob = scope.launch {
            try {
                val result = readComparison(owner, from, to)
                if (generation == comparisonGeneration) comparison = result
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == comparisonGeneration) comparisonError = failure.message ?: "Snapshots nicht verfügbar"
            } finally {
                if (generation == comparisonGeneration) comparing = false
            }
        }
    }

    fun dismissComparison(renderedGeneration: Int) {
        if (!closed && renderedGeneration == comparisonGeneration) closeComparison()
    }

    fun closeComparison() {
        comparisonGeneration++
        comparisonJob?.cancel()
        comparisonJob = null
        comparing = false
        comparison = null
        comparisonError = null
    }

    fun close() {
        closed = true
        factsGeneration++
        detailGeneration++
        factsRetry?.cancel()
        detailRetry?.cancel()
        closeComparison()
    }
}

internal class ProfilePanelReads(
    val facts: suspend (Long, String) -> ProfileFacts,
    val detail: suspend (Long) -> PersonDetail,
    val comparison: suspend (Long, Long, Long) -> List<SnapshotDifference>,
)

@Composable
internal fun rememberProfilePanels(
    model: ProfileReadModel?, filters: String, injected: ProfilePanelReads? = null,
): ProfilePanels {
    val reads = injected ?: run {
        val core = (LocalContext.current.applicationContext as LadiesApplication).coreStore
        remember(core) { ProfilePanelReads(core::profileFacts, core::personDetail, core::compareSnapshots) }
    }
    val scope = rememberCoroutineScope()
    val panels = remember(reads, scope, model?.profile?.listingId, model?.profile?.snapshotId,
        model?.identity?.personId, model?.revision, filters) {
        ProfilePanels(scope, model?.identity?.personId, reads.facts, reads.detail, reads.comparison)
    }
    DisposableEffect(panels) { onDispose { panels.close() } }
    return panels
}
