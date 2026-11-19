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
    loading = true, searchCancelling = false, error = null, profile = null, profileOpen = false,
)

internal fun SearchUiState.endRun(errorMessage: String? = null): SearchUiState =
    copy(loading = false, searchCancelling = false, error = errorMessage)

internal fun SearchUiState.acceptRun(
    run: SearchResponse,
    cards: List<DiscoveryCard>,
): SearchUiState = copy(loading = false, searchCancelling = false, error = null, response = run, activeCards = cards, gridHiddenLikes = emptySet())

internal fun SearchUiState.applyDraftChange(next: SearchUiState): SearchUiState =
    next.copy(response = response, activeCards = activeCards, gridHiddenLikes = gridHiddenLikes, error = null)

/** Typed presentation draft. JSON exists only when crossing the core API. */
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

/** Main-thread owner: cancel invalidates delivery, not native execution. */
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
