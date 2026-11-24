package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.LocalVisibility
import de.justify87.ladiesexplorer.core.SearchResponse
import de.justify87.ladiesexplorer.core.SearchRequest
import de.justify87.ladiesexplorer.core.SourceFilterCatalog
import de.justify87.ladiesexplorer.core.FrequentFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

internal data class SearchPreviewTarget(val draft: SearchDraft, val editorSession: Long?)

internal fun searchPreviewTarget(search: SearchUiState, saved: SavedSearchUiState): SearchPreviewTarget? {
    if (search.loading || search.profileOpen || saved.blockingNavigation) return null
    val editor = saved.editor
    val draft = when (saved.pane) {
        SearchPane.Filters -> SearchDraft.from(search)
        SearchPane.SavedEditor -> editor?.draft ?: return null
        else -> return null
    }
    return SearchPreviewTarget(
        draft.copy(city = draft.city.trim(), maxPages = 1u, local = defaultLocalVisibility()),
        editor?.session,
    )
}

// Every started filter view has a distinct lease, even for an identical request.
internal class SearchPreviewLease(val target: SearchPreviewTarget)

internal class SearchPreviewOwner {
    var current: SearchPreviewLease? = null
        private set

    fun replace(lease: SearchPreviewLease) { current = lease }
    fun accepts(lease: SearchPreviewLease) = current === lease
    fun release(lease: SearchPreviewLease): Boolean {
        if (!accepts(lease)) return false
        clear()
        return true
    }
    fun clear() { current = null }
}

internal data class SearchDraftEditAction(val generation: Long, val paneGeneration: Long)

internal fun searchDraftEditMatches(
    discovery: DiscoverUiState, search: SearchUiState, saved: SavedSearchUiState, action: SearchDraftEditAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.SEARCH &&
        discovery.actionGeneration == action.generation &&
        saved.pane == SearchPane.Filters && saved.actionGeneration == action.paneGeneration && !saved.busy &&
        !search.loading && !search.actionBusy && !search.profileOpen

internal fun searchDraftResetMatches(
    discovery: DiscoverUiState, search: SearchUiState, saved: SavedSearchUiState,
    action: SearchDraftEditAction, renderedDraft: SearchDraft,
): Boolean =
    searchDraftEditMatches(discovery, search, saved, action) && SearchDraft.from(search) == renderedDraft

internal data class SearchSubmitAction(
    val draft: SearchDraft,
    val generation: Long,
)

internal fun searchSubmitMatches(
    discovery: DiscoverUiState, search: SearchUiState, saved: SavedSearchUiState, action: SearchSubmitAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.SEARCH &&
        discovery.actionGeneration == action.generation &&
        saved.pane == SearchPane.Filters && !saved.busy &&
        !discovery.busy && !search.loading && !search.actionBusy && !search.profileOpen &&
        action.draft.city.isNotBlank() && SearchDraft.from(search) == action.draft

internal data class SearchCancelAction(
    val surface: ProfileActionSurface,
    val generation: Long,
    val attemptId: Long,
    val swipeSession: Long? = null,
)

internal fun searchCancelMatches(
    discovery: DiscoverUiState, search: SearchUiState, saved: SavedSearchUiState,
    swipeVisible: Boolean, action: SearchCancelAction,
): Boolean {
    if (!search.loading || search.searchCancelling || search.profileOpen || search.searchAttemptId != action.attemptId ||
        discovery.actionGeneration != action.generation || discovery.actionSurface != action.surface) return false
    return when (action.surface) {
        ProfileActionSurface.NONE -> swipeVisible && action.swipeSession == discovery.swipeSessionId
        ProfileActionSurface.GRID -> !swipeVisible && action.swipeSession == null
        ProfileActionSurface.SEARCH -> !swipeVisible && action.swipeSession == null && saved.pane == SearchPane.Filters
        else -> false
    }
}

internal data class GridAvailabilityAction(
    val runId: Long?,
    val draft: SearchDraft?,
    val generation: Long,
)

internal fun SearchUiState.gridAvailabilityAction(generation: Long) =
    GridAvailabilityAction(response?.searchRunId, if (response == null) SearchDraft.from(this) else null, generation)

internal fun gridAvailabilityMatches(
    discovery: DiscoverUiState, search: SearchUiState, action: GridAvailabilityAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.GRID &&
        discovery.activeRunId == action.runId &&
        search.gridAvailabilityAction(discovery.actionGeneration) == action &&
        !discovery.loading && !discovery.busy && !search.loading && !search.actionBusy && !search.profileOpen

internal data class SearchResultOpenAction(
    val card: DiscoveryCard,
    val runId: Long,
    val generation: Long,
)

internal fun searchResultOpenMatches(
    search: SearchUiState, discovery: DiscoverUiState, action: SearchResultOpenAction,
): Boolean =
    discovery.actionSurface == ProfileActionSurface.SEARCH &&
        discovery.actionGeneration == action.generation &&
        !discovery.busy && !search.loading && !search.actionBusy && !search.profileOpen &&
        search.response?.searchRunId == action.runId &&
        search.activeCards.any { it == action.card }

internal fun defaultLocalVisibility() = LocalVisibility(
    videoOnly = false, photosOnly = false, hideDislikes = true,
    hideLikes = false, imageMode = "ALL",
)

enum class AvailabilityMode(val label: String, val value: String?) {
    All("Alle", null),
    Now("Jetzt", "jetzt geöffnet"),
    Today("Heute", "heute geöffnet"),
}

private val quickTimes = setOf("jetzt geöffnet", "jetzt-offen", "heute geöffnet", "heute-offen")

internal fun availabilityMode(filters: Map<String, Set<String>>): AvailabilityMode {
    val times = filters["zeiten"].orEmpty()
    return when {
        "jetzt geöffnet" in times || "jetzt-offen" in times -> AvailabilityMode.Now
        "heute geöffnet" in times || "heute-offen" in times -> AvailabilityMode.Today
        else -> AvailabilityMode.All
    }
}

internal fun withAvailability(filters: Map<String, Set<String>>, mode: AvailabilityMode): Map<String, Set<String>> {
    val times = filters["zeiten"].orEmpty().filterNot { it in quickTimes }.toMutableSet()
    mode.value?.let(times::add)
    return if (times.isEmpty()) filters - "zeiten" else filters + ("zeiten" to times.toSet())
}

internal fun decodeSelectedFilters(json: String): Map<String, Set<String>> {
    val selected = JSONObject(json)
    return selected.keys().asSequence().associateWith { key ->
        val array = selected.getJSONArray(key)
        (0 until array.length()).map { array.getString(it) }.toSet()
    }
}

internal fun encodeSelectedFilters(filters: Map<String, Set<String>>): String =
    JSONObject().apply {
        filters.toSortedMap().forEach { (group, values) -> put(group, JSONArray(values.sorted())) }
    }.toString()

internal fun SearchRequest.withAvailabilityMode(mode: AvailabilityMode): SearchRequest =
    copy(filtersJson = encodeSelectedFilters(withAvailability(decodeSelectedFilters(filtersJson), mode)))

internal fun SearchUiState.headerAvailability(): AvailabilityMode =
    response?.query?.let { availabilityMode(decodeSelectedFilters(it.filtersJson)) }
        ?: availabilityMode(selectedFilters)

/** Main-thread generation; invalidate before cancellation or replacement. */
internal class RequestGeneration {
    var value: Long = 0L
        private set

    fun invalidate() { value++ }
    fun isCurrent(ticket: Long): Boolean = ticket == value
}

internal suspend fun refreshSearchFilters(
    state: MutableStateFlow<SearchUiState>,
    current: () -> Boolean,
    readCatalog: suspend () -> SourceFilterCatalog,
    readFrequent: suspend () -> List<FrequentFilter>,
) {
    try {
        // The source catalog is packaged in Rust; only usage counts depend on committed searches.
        val catalog = state.value.filterCatalog ?: readCatalog()
        val frequent = readFrequent()
        if (current()) state.update {
            it.copy(filterCatalog = catalog, frequentFilters = frequent, filterError = null)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (failure: Exception) {
        if (current()) state.update {
            it.copy(filterError = failure.message ?: "Filter konnten nicht geladen werden")
        }
    }
}

internal fun SearchUiState.beginRun(attemptId: Long = searchAttemptId + 1): SearchUiState = copy(
    searchAttemptId = attemptId, loading = true, searchCancelling = false, error = null, profileRead = null, profileOpen = false,
    profileRetryFromArchive = false, profileSourceRetryAllowed = false, retryProfileListingId = null,
)

internal fun SearchUiState.endRun(errorMessage: String? = null): SearchUiState =
    copy(loading = false, searchCancelling = false, error = errorMessage)

internal fun SearchUiState.acceptRun(
    run: SearchResponse,
    cards: List<DiscoveryCard>,
): SearchUiState = copy(loading = false, searchCancelling = false, error = null, response = run, activeCards = cards, gridHiddenLikes = emptySet())

internal fun SearchUiState.applyDraftChange(next: SearchUiState): SearchUiState =
    next.copy(response = response, activeCards = activeCards, gridHiddenLikes = gridHiddenLikes, error = null)

internal data class SearchDraft(
    val city: String = "Leipzig",
    val radiusKm: UInt = 20u,
    val category: String = "",
    val ageMin: UInt = 18u,
    val ageMax: UInt = 60u,
    val heightMin: UInt = 140u,
    val heightMax: UInt = 200u,
    val maxPages: UInt = 10u,
    val selectedFilters: Map<String, Set<String>> = emptyMap(),
    val local: LocalVisibility = defaultLocalVisibility(),
) {
    fun request() = SearchRequest(city.trim(), radiusKm, category, ageMin, ageMax,
        heightMin, heightMax, encodeSelectedFilters(selectedFilters), maxPages)

    fun applyTo(state: SearchUiState): SearchUiState = state.copy(
        city = city, radiusKm = radiusKm, category = category, ageMin = ageMin, ageMax = ageMax,
        heightMin = heightMin, heightMax = heightMax, maxPages = maxPages,
        selectedFilters = selectedFilters, local = local,
    )

    fun availability(mode: AvailabilityMode) = copy(selectedFilters = withAvailability(selectedFilters, mode))

    fun toggle(group: String, value: String): SearchDraft {
        if (group == "zeiten" && value in quickTimes) {
            val mode = if (value.startsWith("jetzt")) AvailabilityMode.Now else AvailabilityMode.Today
            return availability(if (availabilityMode(selectedFilters) == mode) AvailabilityMode.All else mode)
        }
        val selected = selectedFilters[group].orEmpty().toMutableSet()
        if (!selected.add(value)) selected.remove(value)
        return copy(selectedFilters = if (selected.isEmpty()) selectedFilters - group
            else selectedFilters + (group to selected.toSet()))
    }

    companion object {
        fun from(state: SearchUiState) = SearchDraft(state.city, state.radiusKm, state.category,
            state.ageMin, state.ageMax, state.heightMin, state.heightMax, state.maxPages,
            state.selectedFilters, state.local)

        fun from(query: SearchRequest, local: LocalVisibility) = SearchDraft(query.city, query.radiusKm,
            query.category, query.ageMin, query.ageMax, query.heightMin, query.heightMax, query.maxPages,
            decodeSelectedFilters(query.filtersJson), local)
    }
}

/** Main-thread only; cancellation revokes delivery, not native ownership before drain. */
internal class DrainingOperation<T> {
    private var next = 0L
    private var active: Pair<Long, T>? = null
    private var cancelled = false
    val occupied get() = active != null
    fun start(control: T): Long? {
        if (occupied) return null
        val ticket = ++next
        active = ticket to control
        cancelled = false
        return ticket
    }
    fun accepts(ticket: Long) = active?.first == ticket && !cancelled
    fun cancel(signal: (T) -> Unit) {
        val owner = active ?: return
        if (!cancelled) {
            cancelled = true
            signal(owner.second)
        }
    }
    fun finish(ticket: Long): Boolean {
        if (active?.first != ticket) return false
        active = null
        return true
    }
}

internal data class DetailRetryAction(
    val session: Long,
    val listingId: Long,
    val sourceAllowed: Boolean,
    val attempt: Long,
)

internal fun SearchUiState.detailRetryAction(): DetailRetryAction? {
    if (!profileOpen) return null
    val listing = retryProfileListingId ?: return null
    return DetailRetryAction(profileSessionId, listing, profileSourceRetryAllowed, profileRetryAttempt)
}

internal sealed interface ProfileIdentityToken {
    val listingId: Long
    val ownerId: Long
    val snapshotId: Long
    val projectionRevision: Long
    val preference: de.justify87.ladiesexplorer.core.Preference
    val favorite: Boolean
    val candidates: List<de.justify87.ladiesexplorer.core.IdentityCandidate>
}

/** Capture evidence from rendered detail, never at confirmation time. */
internal data class DetailIdentityToken(
    val sessionId: Long,
    override val listingId: Long,
    override val ownerId: Long,
    override val snapshotId: Long,
    override val projectionRevision: Long,
    override val preference: de.justify87.ladiesexplorer.core.Preference,
    override val favorite: Boolean,
    override val candidates: List<de.justify87.ladiesexplorer.core.IdentityCandidate>,
) : ProfileIdentityToken

internal fun SearchUiState.detailIdentityToken(): DetailIdentityToken? {
    if (!profileOpen || loading) return null
    val current = profileRead ?: return null
    return DetailIdentityToken(profileSessionId, current.profile.listingId, current.identity.personId,
        current.profile.snapshotId, current.revision, current.person.preference, current.person.favorite, current.candidates)
}

internal fun SearchUiState.currentIdentityConfirmationOwner(
    token: DetailIdentityToken,
    targetPersonId: Long,
    discoveryBusy: Boolean,
): Long? {
    if (discoveryBusy || actionBusy || detailIdentityToken() != token) return null
    if (token.ownerId == targetPersonId || identityCandidates.none { it.personId == targetPersonId }) return null
    return token.ownerId
}

/** A stale or failed local projection invalidates every rendered capability before recovery. */
internal fun SearchUiState.invalidateMergedProfile(listingId: Long): SearchUiState = copy(
    profileRead = null, profileRetryFromArchive = profileFromArchive,
    mediaArchive = null, mediaWarning = null, retryProfileListingId = listingId, profileSourceRetryAllowed = false,
)
