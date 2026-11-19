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
    private val core: CoreStore,
    private val scope: CoroutineScope,
    private val personId: Long?,
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

    suspend fun load(snapshot: Long?, filters: String) {
        facts = null
        detail = null
        factsError = null
        detailError = null
        if (snapshot == null || personId == null) return
        try {
            facts = core.profileFacts(snapshot, filters)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { factsError = failure.message ?: "Profilfelder nicht verfügbar" }
        try {
            detail = core.personDetail(personId)
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
                val result = core.compareSnapshots(owner, from, to)
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
): ProfilePanels {
    val core = (LocalContext.current.applicationContext as LadiesApplication).coreStore
    val scope = rememberCoroutineScope()
    val panels = remember(core, scope, profile?.listingId, profile?.snapshotId, personId) {
        ProfilePanels(core, scope, personId)
    }
    LaunchedEffect(panels, filters, panels.revision, media) { panels.load(profile?.snapshotId, filters) }
    return panels
}
