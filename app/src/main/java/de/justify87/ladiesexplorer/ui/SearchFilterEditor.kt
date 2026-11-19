package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.LocalVisibility
import de.justify87.ladiesexplorer.core.SourceFilterGroup
import kotlin.math.roundToInt

private val categoryLabels = mapOf(
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

private val groupLabels = mapOf(
    "figur" to "Figur", "typ" to "Typ", "geschlecht" to "Geschlecht",
    "busen" to "Busen", "intimbereich" to "Intimbereich", "hautton" to "Hautton",
    "haarfarbe" to "Haarfarbe", "haarlaenge" to "Haarlänge",
    "augenfarbe" to "Augenfarbe", "koerperschmuck" to "Körperschmuck",
    "sonstiges" to "Sonstiges", "service" to "Services",
    "treff" to "Treffpunkt", "location" to "Location", "zeiten" to "Zeiten",
)

internal fun LazyListScope.searchFilterEditor(
    state: SearchUiState,
    filterQuery: String,
    onFilterQuery: (String) -> Unit,
    categoryExpanded: Boolean,
    onCategoryExpanded: (Boolean) -> Unit,
    enabled: Boolean,
    onCity: (String) -> Unit,
    onRadius: (UInt) -> Unit,
    onCategory: (String) -> Unit,
    onAge: (UInt, UInt) -> Unit,
    onHeight: (UInt, UInt) -> Unit,
    onPages: (UInt) -> Unit,
    onToggleFilter: (String, String) -> Unit,
    onAvailability: (AvailabilityMode) -> Unit,
    onLocalVisibility: (LocalVisibility) -> Unit,
) {
    item {
        OutlinedTextField(value = filterQuery, onValueChange = onFilterQuery,
            label = { Text("Filter suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    item {
        FilterCard("Standort") {
            OutlinedTextField(value = state.city, onValueChange = onCity,
                label = { Text("Ort") }, singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            Text("Umkreis", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                for (radius in listOf(5u,10u,20u,30u,50u,70u,100u)) {
                    FilterChip(selected = state.radiusKm == radius,
                        onClick = { onRadius(radius) }, enabled = enabled, label = { Text("$radius km") })
                }
            }
        }
    }
    if (state.frequentFilters.isNotEmpty() && filterQuery.isBlank()) item {
        FilterCard("Häufige Filter") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                state.frequentFilters.forEach { filter ->
                    FilterChip(
                        selected = if (filter.group == "category") state.category == filter.value
                            else filter.value in state.selectedFilters[filter.group].orEmpty(),
                        onClick = {
                            if (filter.group == "category") onCategory(if (state.category == filter.value) "" else filter.value)
                            else onToggleFilter(filter.group, filter.value)
                        },
                        enabled = enabled,
                        label = { Text(if (filter.group == "category") categoryLabels[filter.value] ?: filter.value else filter.value) },
                    )
                }
            }
        }
    }
    item {
        FilterCard("Verfügbarkeit") {
            AvailabilitySelector(availabilityMode(state.selectedFilters), enabled, onAvailability)
        }
    }
    item {
        FilterCard("Alter") {
            Text("${state.ageMin}–${state.ageMax} Jahre", style = MaterialTheme.typography.titleSmall)
            RangeSlider(value = state.ageMin.toFloat()..state.ageMax.toFloat(),
                onValueChange = { onAge(it.start.roundToInt().toUInt().coerceIn(18u,60u),
                    it.endInclusive.roundToInt().toUInt().coerceIn(18u,60u)) },
                valueRange = 18f..60f, enabled = enabled)
        }
    }
    state.filterCatalog?.groups?.firstOrNull { it.key == "treff" }?.let { group ->
        if (matchesFilterGroup(group, filterQuery)) item(key = "filter/treff") {
            FilterSection(group, state.selectedFilters[group.key].orEmpty(), enabled,
                filterQuery, true, { onToggleFilter(group.key, it) })
        }
    }
    if (filterQuery.isBlank() || "Rubrik Kategorie".contains(filterQuery, ignoreCase = true) ||
        state.filterCatalog?.categories.orEmpty().any { (categoryLabels[it] ?: it).contains(filterQuery, ignoreCase = true) }) item {
        FilterCard("Rubrik") {
            OutlinedButton(onClick = { onCategoryExpanded(!categoryExpanded) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth()) {
                Text(categoryLabels[state.category] ?: state.category)
            }
            if (categoryExpanded || filterQuery.isNotBlank()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    state.filterCatalog?.categories.orEmpty()
                        .filter { "Rubrik Kategorie".contains(filterQuery, ignoreCase = true) || (categoryLabels[it] ?: it).contains(filterQuery, ignoreCase = true) }
                        .forEach { category ->
                            FilterChip(selected = state.category == category, enabled = enabled,
                                onClick = { onCategory(category) }, label = { Text(categoryLabels[category] ?: category) })
                        }
                }
            }
        }
    }
    if (filterQuery.isBlank() || "Größe Groesse Körpergröße cm".contains(filterQuery, ignoreCase = true)) item {
        FilterCard("Größe") {
            Text("${state.heightMin}–${state.heightMax} cm", style = MaterialTheme.typography.titleSmall)
            RangeSlider(value = state.heightMin.toFloat()..state.heightMax.toFloat(),
                onValueChange = { onHeight(it.start.roundToInt().toUInt().coerceIn(140u,200u),
                    it.endInclusive.roundToInt().toUInt().coerceIn(140u,200u)) },
                valueRange = 140f..200f, enabled = enabled)
        }
    }
    val groupOrder = listOf(
        "Eigenschaften" to setOf("figur", "typ", "geschlecht", "busen", "intimbereich", "hautton",
            "haarfarbe", "haarlaenge", "augenfarbe", "koerperschmuck"),
        "Services" to setOf("service"),
        "Weitere Optionen" to setOf("sonstiges", "location", "zeiten"),
    )
    groupOrder.forEach { (title, keys) ->
        val groups = state.filterCatalog?.groups.orEmpty().filter { group ->
            group.key in keys && matchesFilterGroup(group, filterQuery)
        }
        if (groups.isNotEmpty()) item(key = "section/$title") {
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        groups.forEach { group ->
            item(key = "filter/${group.key}") {
                FilterSection(group, state.selectedFilters[group.key].orEmpty(), enabled,
                    filterQuery, false, { onToggleFilter(group.key, it) })
            }
        }
    }
    if (filterQuery.isBlank() || "Bilddarstellung Bilder Fotos Video Dislikes Likes Natürlich Studio FaceApp KI Betrieb Filter".contains(filterQuery, ignoreCase = true)) item {
        FilterCard("Bilddarstellung und lokale Filter") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("ALL" to "Alle", "NATURAL" to "Natürlich", "AI" to "KI-markiert").forEach { (mode, label) ->
                    FilterChip(selected = state.local.imageMode.ifBlank { "ALL" } == mode, enabled = enabled,
                        onClick = {
                            if (state.local.imageMode.ifBlank { "ALL" } != mode) {
                                onLocalVisibility(state.local.copy(imageMode = mode))
                            }
                        }, label = { Text(label) })
                }
            }
            LocalFilterRow("Nur mit Bildern", state.local.photosOnly, enabled) {
                onLocalVisibility(state.local.copy(photosOnly = it))
            }
            LocalFilterRow("Nur mit Video", state.local.videoOnly, enabled) {
                onLocalVisibility(state.local.copy(videoOnly = it))
            }
            LocalFilterRow("Dislikes ausblenden", state.local.hideDislikes, enabled) {
                onLocalVisibility(state.local.copy(hideDislikes = it))
            }
            LocalFilterRow("Likes ausblenden", state.local.hideLikes, enabled) {
                onLocalVisibility(state.local.copy(hideLikes = it))
            }
            Text("Studio, FaceApp und Betriebsprofile: keine zuverlässigen nativen Klassifikationsdaten.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        FilterCard("Seitenlimit") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                for (pages in listOf(1u,5u,10u,25u,50u)) {
                    FilterChip(selected = state.maxPages == pages, onClick = { onPages(pages) },
                        enabled = enabled, label = { Text("$pages") })
                }
            }
        }
    }
}

@Composable
internal fun AvailabilitySelector(active: AvailabilityMode, enabled: Boolean, onSelect: (AvailabilityMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        AvailabilityMode.entries.forEach { mode ->
            FilterChip(modifier = Modifier.weight(1f), selected = mode == active, enabled = enabled,
                onClick = { onSelect(mode) }, label = { Text(mode.label) })
        }
    }
}

@Composable
private fun FilterCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LocalFilterRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun matchesFilterGroup(group: SourceFilterGroup, query: String): Boolean =
    query.isBlank() || (groupLabels[group.key] ?: group.key).contains(query, ignoreCase = true) ||
        group.options.any { it.contains(query, ignoreCase = true) }

@Composable
private fun FilterSection(
    group: SourceFilterGroup,
    selected: Set<String>,
    enabled: Boolean,
    query: String,
    initiallyExpanded: Boolean,
    onToggle: (String) -> Unit,
) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(initiallyExpanded) }
    FilterCard((groupLabels[group.key] ?: group.key) + if (selected.isEmpty()) "" else " (${selected.size})") {
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Weniger anzeigen" else "Optionen anzeigen")
        }
        if (expanded || query.isNotBlank()) {
            val all = (groupLabels[group.key] ?: group.key).contains(query, ignoreCase = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                group.options.filter { all || it.contains(query, ignoreCase = true) || it in selected }.forEach { option ->
                    FilterChip(selected = option in selected, onClick = { onToggle(option) }, enabled = enabled,
                        label = { Text(option) })
                }
            }
        }
    }
}

