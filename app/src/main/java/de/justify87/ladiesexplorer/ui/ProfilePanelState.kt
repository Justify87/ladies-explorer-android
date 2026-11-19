package de.justify87.ladiesexplorer.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Composition owns cancellable, local read projections only. No writes. */
internal class ProfilePanels(
    private val scope: CoroutineScope,
    private val personId: Long?,
    private val readFacts: suspend (Long, String) -> ProfileFacts,
    private val readDetail: suspend (Long) -> PersonDetail,
    private val readComparison: suspend (Long, Long, Long) -> List<SnapshotDifference>,
) {
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
    var revision by mutableIntStateOf(0)
        private set

    fun reload() { revision++ }

    suspend fun loadFacts(snapshot: Long?, filters: String) {
        facts = null
        factsError = null
        if (snapshot == null) {
            factsError = "Kein gespeicherter Snapshot"
            return
        }
        try {
            facts = readFacts(snapshot, filters)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { factsError = failure.message ?: "Profilfelder nicht verfügbar" }
    }

    suspend fun loadDetail() {
        detail = null
        detailError = null
        val owner = personId
        if (owner == null) {
            detailError = "Personenzuordnung nicht verfügbar"
            return
        }
        try {
            detail = readDetail(owner)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { detailError = failure.message ?: "Personendaten nicht verfügbar" }
    }

    fun compare(from: Long, to: Long) {
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
}

@Composable
internal fun rememberProfilePanels(
    profile: ProfileResponse?,
    personId: Long?,
    filters: String,
    media: List<ProfileMediaItem>,
    tab: ProfileTab,
): ProfilePanels {
    val core = (LocalContext.current.applicationContext as LadiesApplication).coreStore
    val scope = rememberCoroutineScope()
    val panels = remember(core, scope, profile?.listingId, profile?.snapshotId, personId) {
        ProfilePanels(scope, personId, core::profileFacts, core::personDetail, core::compareSnapshots)
    }
    val factsVisible = tab == ProfileTab.Info || tab == ProfileTab.Services
    val detailVisible = tab == ProfileTab.Analysis || tab == ProfileTab.History
    LaunchedEffect(panels, filters, panels.revision, factsVisible) {
        if (factsVisible) panels.loadFacts(profile?.snapshotId, filters)
    }
    LaunchedEffect(panels, panels.revision, media, detailVisible) {
        if (detailVisible) panels.loadDetail()
    }
    return panels
}
