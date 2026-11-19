package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.DiscoveryCard
import de.justify87.ladiesexplorer.core.LocalVisibility
import de.justify87.ladiesexplorer.core.SearchResponse
import de.justify87.ladiesexplorer.core.SearchRequest
import org.json.JSONArray
import org.json.JSONObject

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

/** Main-thread-owned generation; invalidated before cancel or replacing a request. */
internal class RequestGeneration {
    var value: Long = 0L
        private set

    fun invalidate() { value++ }
    fun isCurrent(ticket: Long): Boolean = ticket == value
}

internal fun SearchUiState.beginRun(): SearchUiState = copy(
    savedOverviewOpen = false,
    loading = true, error = null, profile = null, profileOpen = false,
    showingSavedSearchId = null, savedResultCards = emptyList(),
    savedResultCursor = null, savedResultsLoadingMore = false,
)

internal fun SearchUiState.endRun(errorMessage: String? = null): SearchUiState =
    copy(loading = false, error = errorMessage)

internal fun SearchUiState.acceptRun(
    run: SearchResponse,
    cards: List<DiscoveryCard>,
): SearchUiState = copy(loading = false, error = null, response = run, activeCards = cards, gridHiddenLikes = emptySet())

internal fun SearchUiState.applyDraftChange(next: SearchUiState): SearchUiState =
    next.copy(response = response, activeCards = activeCards, gridHiddenLikes = gridHiddenLikes, error = null,
        showingSavedSearchId = null, savedResultCards = emptyList(),
        savedResultCursor = null, savedResultsLoadingMore = false)

/** Own the first page before awaiting I/O so the previous cursor cannot be reused. */
internal fun SearchUiState.beginSavedResults(id: String): SearchUiState = copy(
    savedOverviewOpen = true, showingSavedSearchId = id, savedResultCards = emptyList(), savedResultCursor = null,
    savedResultsLoadingMore = true, error = null,
)

// Only draft fields are restored; active results, error state and definitions
// remain owned by the current ViewModel state.
internal data class SearchDraft(val query: SearchRequest, val local: LocalVisibility) {
    fun restore(state: SearchUiState): SearchUiState = state.copy(
        city = query.city, radiusKm = query.radiusKm, category = query.category,
        ageMin = query.ageMin, ageMax = query.ageMax, heightMin = query.heightMin,
        heightMax = query.heightMax, maxPages = query.maxPages,
        selectedFilters = decodeSelectedFilters(query.filtersJson), local = local,
        preview = null, previewError = null, previewLoading = false,
    )
}

internal enum class SearchPane { Filters, SavedList, SavedEditor, SavedResults }
internal fun SearchUiState.searchPane(): SearchPane = when {
    showingSavedSearchId != null -> SearchPane.SavedResults
    editingSavedSearchId != null -> SearchPane.SavedEditor
    savedOverviewOpen -> SearchPane.SavedList
    else -> SearchPane.Filters
}
