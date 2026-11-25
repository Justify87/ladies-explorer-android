package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.LocalVisibility
import kotlin.math.roundToInt

internal val categoryLabels = mapOf(
    "" to "Alle Rubriken",
    "huren-hostessen" to "Huren & Hostessen",
    "bordelle-laufhaeuser" to "Bordelle & Laufhäuser",
    "escort" to "Escort",
    "transen-shemale" to "Trans",
    "erotische-massage" to "Erotische Massage",
    "domina-sm" to "Domina & SM",
    "fkk-saunaclubs" to "FKK & Saunaclubs",
    "swinger" to "Swinger",
    "telefonsex" to "Telefonsex",
    "onlineservice" to "Online-Service",
)

internal val groupLabels = mapOf(
    "figur" to "Figur", "typ" to "Typ", "geschlecht" to "Geschlecht",
    "busen" to "Busen", "intimbereich" to "Intimbereich", "hautton" to "Hautton",
    "haarfarbe" to "Haarfarbe", "haarlaenge" to "Haarlänge",
    "augenfarbe" to "Augenfarbe", "koerperschmuck" to "Körperschmuck",
    "sonstiges" to "Sonstiges", "service" to "Services",
    "treff" to "Treffpunkt", "location" to "Location", "zeiten" to "Zeiten",
)

private const val LOCAL_FILTER_TITLE = "Bilddarstellung und lokale Filter"
private const val PHOTOS_ONLY_LABEL = "Nur mit Bildern"
private const val VIDEO_ONLY_LABEL = "Nur mit Video"
private const val HIDE_DISLIKES_LABEL = "Dislikes ausblenden"
private const val HIDE_LIKES_LABEL = "Likes ausblenden"
private const val UNSUPPORTED_LABEL = "Studio, FaceApp und Betriebsprofile: keine zuverlässigen nativen Klassifikationsdaten."
private val imageModeLabels = listOf("ALL" to "Alle", "NATURAL" to "Ohne KI-Markierung", "AI" to "KI-markiert")

// A parent match exposes its children; otherwise only matching values are visible.
internal fun filterTextMatches(query: String, vararg labels: String): Boolean =
    query.isBlank() || labels.any { it.contains(query.trim(), ignoreCase = true) }

internal fun <T> matchingFilterOptions(
    query: String, parents: List<String>, options: List<T>, label: (T) -> String,
): List<T> = if (filterTextMatches(query, *parents.toTypedArray())) options
    else options.filter { filterTextMatches(query, label(it)) }

internal fun filterSectionExpanded(key: String, initiallyExpanded: Boolean, flipped: List<String>, query: String): Boolean =
    query.isNotBlank() || (initiallyExpanded != (key in flipped))

internal fun matchesLocalFilterQuery(query: String): Boolean =
    filterTextMatches(query, LOCAL_FILTER_TITLE, PHOTOS_ONLY_LABEL, VIDEO_ONLY_LABEL,
        HIDE_DISLIKES_LABEL, HIDE_LIKES_LABEL, UNSUPPORTED_LABEL, "Fotos", "Natürlich", *imageModeLabels.map { it.second }.toTypedArray())

@Composable
internal fun CompactFilterField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    BasicTextField(value = value, onValueChange = onChange, enabled = enabled, singleLine = true,
        interactionSource = interaction, textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Surface(shape = MaterialTheme.shapes.small, color = colors.surface,
                border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) colors.primary else colors.outline)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    inner()
                }
            }
        })
}

@Composable
private fun FilterChoice(selected: Boolean, enabled: Boolean, label: String, onClick: () -> Unit) {
    ScreenChoice(selected, onClick, enabled, { Text(label, style = MaterialTheme.typography.bodySmall) }, compact = true)
}

@Composable
private fun FilterChoices(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

private fun LazyListScope.filterSection(
    key: String, title: String, query: String, flipped: List<String>, onToggle: (String) -> Unit,
    initiallyExpanded: Boolean = false, hint: String? = null, content: @Composable ColumnScope.() -> Unit,
) {
    item(key = "filter/$key") {
        ProgressiveSection(title, filterSectionExpanded(key, initiallyExpanded, flipped, query),
            { onToggle(key) }, hint = hint.takeIf { query.isBlank() }, enabled = query.isBlank(), compact = true, content = content)
    }
}

internal fun LazyListScope.searchFilterEditor(
    state: SearchUiState,
    filterQuery: String,
    onFilterQuery: (String) -> Unit,
    flippedSections: List<String>,
    onToggleSection: (String) -> Unit,
    enabled: Boolean,
    onCity: (String) -> Unit,
    onRadius: (UInt) -> Unit,
    onCategory: (String) -> Unit,
    onAge: (UInt, UInt) -> Unit,
    onHeight: (UInt, UInt) -> Unit,
    onToggleFilter: (String, String) -> Unit,
    onAvailability: (AvailabilityMode) -> Unit,
    onLocalVisibility: (LocalVisibility) -> Unit,
) {
    val query = filterQuery.trim()
    item(key = "filter-query") { CompactFilterField(filterQuery, onFilterQuery, "Filter suchen") }
    var hasMatch = false
    fun section(key: String, title: String, initiallyExpanded: Boolean = false, hint: String? = null,
        content: @Composable ColumnScope.() -> Unit) {
        hasMatch = true
        filterSection(key, title, query, flippedSections, onToggleSection, initiallyExpanded, hint, content)
    }
    val cityVisible = filterTextMatches(query, "Standort", "Ort", state.city)
    val radii = matchingFilterOptions(query, listOf("Standort", "Umkreis", "Radius"), listOf(5u, 10u, 20u, 30u, 50u, 70u, 100u)) { "$it km" }
    if (cityVisible || radii.isNotEmpty()) section("location-input", "Standort", initiallyExpanded = true) {
        if (cityVisible) CompactFilterField(state.city, onCity, "Ort", enabled)
        if (radii.isNotEmpty()) {
            Text("Umkreis", style = MaterialTheme.typography.labelMedium)
            FilterChoices { radii.forEach { radius ->
                FilterChoice(state.radiusKm == radius, enabled, "$radius km") { onRadius(radius) }
            } }
        }
    }
    val frequent = matchingFilterOptions(query, listOf("Häufige Filter", "Top 5"), state.frequentFilters) {
        if (it.group == "category") categoryLabels[it.value] ?: it.value else it.value
    }
    if (frequent.isNotEmpty()) section("frequent", "Häufige Filter", initiallyExpanded = true) {
        FilterChoices { frequent.forEach { filter ->
            val category = filter.group == "category"
            val selected = if (category) state.category == filter.value else filter.value in state.selectedFilters[filter.group].orEmpty()
            FilterChoice(selected, enabled, if (category) categoryLabels[filter.value] ?: filter.value else filter.value) {
                if (category) onCategory(if (selected) "" else filter.value)
                else onToggleFilter(filter.group, filter.value)
            }
        } }
    }
    val availability = matchingFilterOptions(query, listOf("Verfügbarkeit", "Geöffnet"), AvailabilityMode.entries) { it.label }
    if (availability.isNotEmpty()) section("availability", "Verfügbarkeit", initiallyExpanded = true) {
        FilterChoices { availability.forEach { mode ->
            FilterChoice(mode == availabilityMode(state.selectedFilters), enabled, mode.label) { onAvailability(mode) }
        } }
    }
    val ageLabel = "${state.ageMin}–${state.ageMax} Jahre"
    if (filterTextMatches(query, "Alter", "Jahre", ageLabel)) section("age", "Alter", initiallyExpanded = true) {
        Text(ageLabel, style = MaterialTheme.typography.labelMedium)
        RangeSlider(value = state.ageMin.toFloat()..state.ageMax.toFloat(),
            onValueChange = { onAge(it.start.roundToInt().toUInt().coerceIn(18u,60u),
                it.endInclusive.roundToInt().toUInt().coerceIn(18u,60u)) },
            valueRange = 18f..60f, enabled = enabled)
    }
    fun catalogOptions(group: de.justify87.ladiesexplorer.core.SourceFilterGroup, parent: String): List<String> {
        val title = groupLabels[group.key] ?: group.key
        val aliases = when (group.key) {
            "figur" -> "Figurtyp"
            "busen" -> "Brustgröße"
            "treff" -> "Treffpunkte"
            else -> title
        }
        return matchingFilterOptions(query, listOf(parent, title, aliases), group.options) { it }
    }
    fun catalogGroup(group: de.justify87.ladiesexplorer.core.SourceFilterGroup, parent: String) {
        val title = groupLabels[group.key] ?: group.key
        val options = catalogOptions(group, parent)
        if (options.isEmpty()) return
        val selected = state.selectedFilters[group.key].orEmpty()
        section("catalog/${group.key}", title,
            hint = selected.size.takeIf { it > 0 }?.let { "$it ausgewählt" }) {
            FilterChoices { options.forEach { option ->
                FilterChoice(option in selected, enabled, option) { onToggleFilter(group.key, option) }
            } }
        }
    }
    val groups = state.filterCatalog?.groups.orEmpty()
    groups.firstOrNull { it.key == "treff" }?.let { catalogGroup(it, "Treffpunkte") }
    val categories = matchingFilterOptions(query, listOf("Rubrik", "Kategorie"), state.filterCatalog?.categories.orEmpty()) { categoryLabels[it] ?: it }
    if (categories.isNotEmpty()) section("category", "Rubrik", hint = categoryLabels[state.category] ?: state.category) {
        FilterChoices { categories.forEach { category ->
            FilterChoice(state.category == category, enabled, categoryLabels[category] ?: category) { onCategory(category) }
        } }
    }
    val heightLabel = "${state.heightMin}–${state.heightMax} cm"
    if (filterTextMatches(query, "Größe", "Groesse", "Körpergröße", "cm", heightLabel)) section("height", "Größe") {
        Text(heightLabel, style = MaterialTheme.typography.labelMedium)
        RangeSlider(value = state.heightMin.toFloat()..state.heightMax.toFloat(),
            onValueChange = { onHeight(it.start.roundToInt().toUInt().coerceIn(140u,200u),
                it.endInclusive.roundToInt().toUInt().coerceIn(140u,200u)) },
            valueRange = 140f..200f, enabled = enabled)
    }
    val groupOrder = listOf(
        "Eigenschaften" to setOf("figur", "typ", "geschlecht", "busen", "intimbereich", "hautton",
            "haarfarbe", "haarlaenge", "augenfarbe", "koerperschmuck"),
        "Services" to setOf("service"),
        "Weitere Optionen" to setOf("sonstiges", "location", "zeiten"),
    )
    groupOrder.forEach { (title, keys) ->
        val visibleGroups = groups.filter { it.key in keys && catalogOptions(it, title).isNotEmpty() }
        if (visibleGroups.isNotEmpty() && keys.size > 1) item(key = "heading/$title") {
            Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        }
        visibleGroups.forEach { catalogGroup(it, title) }
    }
    if (matchesLocalFilterQuery(query)) section("local", LOCAL_FILTER_TITLE) {
        val all = filterTextMatches(query, LOCAL_FILTER_TITLE)
        val modes = matchingFilterOptions(query, listOf(LOCAL_FILTER_TITLE, "Bilddarstellung"), imageModeLabels) {
            if (it.first == "NATURAL") it.second + " Natürlich" else it.second
        }
        if (modes.isNotEmpty()) FilterChoices { modes.forEach { (mode, label) ->
            FilterChoice(state.local.imageMode.ifBlank { "ALL" } == mode, enabled, label) {
                if (state.local.imageMode.ifBlank { "ALL" } != mode) onLocalVisibility(state.local.copy(imageMode = mode))
            }
        } }
        if (all || filterTextMatches(query, PHOTOS_ONLY_LABEL, "Fotos")) LocalFilterRow(PHOTOS_ONLY_LABEL, state.local.photosOnly, enabled) {
            onLocalVisibility(state.local.copy(photosOnly = it))
        }
        if (all || filterTextMatches(query, VIDEO_ONLY_LABEL)) LocalFilterRow(VIDEO_ONLY_LABEL, state.local.videoOnly, enabled) {
            onLocalVisibility(state.local.copy(videoOnly = it))
        }
        if (all || filterTextMatches(query, HIDE_DISLIKES_LABEL)) LocalFilterRow(HIDE_DISLIKES_LABEL, state.local.hideDislikes, enabled) {
            onLocalVisibility(state.local.copy(hideDislikes = it))
        }
        if (all || filterTextMatches(query, HIDE_LIKES_LABEL)) LocalFilterRow(HIDE_LIKES_LABEL, state.local.hideLikes, enabled) {
            onLocalVisibility(state.local.copy(hideLikes = it))
        }
        if (all || filterTextMatches(query, UNSUPPORTED_LABEL)) Text(UNSUPPORTED_LABEL,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!hasMatch) item(key = "filter-empty") { Text("Keine passenden Filter", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun LocalFilterRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}
