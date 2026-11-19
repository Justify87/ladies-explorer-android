package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Local reads are keyed by the immutable rendered projection. Scrolling away does
 * not cancel a demanded read; a new projection disposes the old reader. */
internal class ProfilePanels(
    private val scope: CoroutineScope,
    private val personId: Long?,
    private val readFacts: suspend (Long, String) -> ProfileFacts,
    private val readDetail: suspend (Long) -> PersonDetail,
    private val readComparison: suspend (Long, Long, Long) -> List<SnapshotDifference>,
) {
    private data class FactsKey(val snapshot: Long?, val filters: String)
    private var factsKey: FactsKey? = null
    private var factsGeneration = 0L
    private var detailRequested = false
    private var detailGeneration = 0L
    private var closed = false
    var facts by mutableStateOf<ProfileFacts?>(null)
        private set
    var detail by mutableStateOf<PersonDetail?>(null)
        private set
    var factsError by mutableStateOf<String?>(null)
        private set
    var detailError by mutableStateOf<String?>(null)
        private set
    var showDescriptionChanges by mutableStateOf(false)
    var comparison by mutableStateOf<List<SnapshotDifference>?>(null)
        private set
    var comparisonError by mutableStateOf<String?>(null)
        private set
    var comparing by mutableStateOf(false)
        private set
    private var comparisonGeneration = 0

    fun retryFacts() {
        val key = factsKey ?: return
        if (closed || factsError == null) return
        factsKey = null
        scope.launch { loadFacts(key.snapshot, key.filters) }
    }

    fun retryDetail() {
        if (closed || detailError == null) return
        detailRequested = false
        scope.launch { loadDetail() }
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
        comparing = true
        comparison = null
        comparisonError = null
        scope.launch {
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

    fun closeComparison() {
        comparisonGeneration++
        comparing = false
        comparison = null
        comparisonError = null
    }

    fun close() {
        closed = true
        factsGeneration++
        detailGeneration++
        closeComparison()
    }
}

internal fun factsDemand(keys: Set<Any>) =
    "profile-facts" in keys || "profile-services" in keys

internal fun detailDemand(keys: Set<Any>) =
    "profile-analysis" in keys || "profile-history-head" in keys ||
        "profile-listings-head" in keys ||
        keys.any { it is String && (it.startsWith("event/") || it.startsWith("listing/")) }

@Composable
internal fun rememberProfilePanels(
    model: ProfileReadModel?,
    filters: String,
    listState: LazyListState,
): ProfilePanels {
    val core = (LocalContext.current.applicationContext as LadiesApplication).coreStore
    val scope = rememberCoroutineScope()
    val panels = remember(core, scope, model?.profile?.listingId, model?.profile?.snapshotId,
        model?.identity?.personId, model?.revision, filters) {
        ProfilePanels(scope, model?.identity?.personId, core::profileFacts, core::personDetail, core::compareSnapshots)
    }
    DisposableEffect(panels) { onDispose { panels.close() } }
    LaunchedEffect(panels, listState) {
        if (model == null) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }.collect { keys ->
            if (factsDemand(keys)) launch { panels.loadFacts(model.profile.snapshotId, filters) }
            if (detailDemand(keys)) launch { panels.loadDetail() }
        }
    }
    return panels
}
