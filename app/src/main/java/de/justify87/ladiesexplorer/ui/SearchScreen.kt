package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.SearchCard
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

@Composable
fun SearchScreen(
    state: SearchUiState,
    onCity: (String) -> Unit,
    onRadius: (UInt) -> Unit,
    onCategory: (String) -> Unit,
    onAge: (UInt,UInt) -> Unit,
    onHeight: (UInt,UInt) -> Unit,
    onPages: (UInt) -> Unit,
    onToggleFilter: (String,String) -> Unit,
    onSearch: () -> Unit,
    onSave: (String) -> Unit,
    onNewPreset: () -> Unit,
    onLoadSaved: (String) -> Unit,
    onRunSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onAcknowledge: (String) -> Unit,
    onProfile: (Long,Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var resultsVisible by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var categoryMenu by remember { mutableStateOf(false) }
    LaunchedEffect(state.editingSavedSearchId) {
        presetName = state.savedSearches.firstOrNull { it.searchId == state.editingSavedSearchId }?.name.orEmpty()
    }
    LaunchedEffect(state.response?.searchRunId) {
        if (state.response != null) resultsVisible = true
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Suche & Filter", style = MaterialTheme.typography.headlineMedium)
                    Text("LADIES · native Suche", style = MaterialTheme.typography.labelMedium)
                }
                if (state.response != null) {
                    TextButton(onClick = { resultsVisible = !resultsVisible }) {
                        Text(if (resultsVisible) "Filter ändern" else "Ergebnisse")
                    }
                }
            }
        }
        if (state.loading) item { CircularProgressIndicator() }
        state.error?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        if (resultsVisible && state.response != null) {
            val response = state.response
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${response.profiles.size} Treffer", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${response.fetchedPages} von ${response.totalPages} Seiten · ${response.city} · ${response.requestedRadiusKm} km",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (response.partial) {
                        Text(
                            "Suche unvollständig: ${response.profiles.size} geladen, Quelle meldet ${response.serverCount ?: "unbekannt"}. Radius bestätigt: ${if (response.radiusVerified) "ja" else "nein"}.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (response.radiusBoundaryStopped) {
                        Text("Weitere Ergebnisse außerhalb des gewählten Radius wurden nicht übernommen.")
                    }
                    OutlinedButton(onClick = { resultsVisible = false }) { Text("Filter anzeigen") }
                }
            }
            items(response.profiles, key = { it.listingId }) { card ->
                SearchResultRow(card, onProfile)
            }
        } else {
            item {
                OutlinedTextField(
                    value = state.city, onValueChange = onCity,
                    label = { Text("Ort") }, singleLine = true,
                    enabled = !state.loading, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Umkreis", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (radius in listOf(5u,10u,20u,30u,50u,70u,100u)) {
                            FilterChip(
                                selected = state.radiusKm == radius,
                                onClick = { onRadius(radius) }, enabled = !state.loading,
                                label = { Text("$radius km") },
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Rubrik", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { categoryMenu = true }, enabled = !state.loading) {
                        Text(categoryLabels[state.category] ?: state.category)
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        state.filterCatalog?.categories.orEmpty().forEach { category ->
                            DropdownMenuItem(
                                text = { Text(categoryLabels[category] ?: category) },
                                onClick = {
                                    onCategory(category)
                                    categoryMenu = false
                                },
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Alter: ${state.ageMin}–${state.ageMax}",style = MaterialTheme.typography.titleSmall)
                    RangeSlider(
                        value = state.ageMin.toFloat()..state.ageMax.toFloat(),
                        onValueChange = {
                            onAge(it.start.roundToInt().toUInt().coerceIn(18u,60u),
                                it.endInclusive.roundToInt().toUInt().coerceIn(18u,60u))
                        },
                        valueRange = 18f..60f, enabled = !state.loading,
                    )
                    Text("Größe: ${state.heightMin}–${state.heightMax} cm",style = MaterialTheme.typography.titleSmall)
                    RangeSlider(
                        value = state.heightMin.toFloat()..state.heightMax.toFloat(),
                        onValueChange = {
                            onHeight(it.start.roundToInt().toUInt().coerceIn(140u,200u),
                                it.endInclusive.roundToInt().toUInt().coerceIn(140u,200u))
                        },
                        valueRange = 140f..200f, enabled = !state.loading,
                    )
                }
            }
            item {
                Text("Weitere Filter",style = MaterialTheme.typography.titleMedium)
            }
            state.filterCatalog?.groups.orEmpty().forEach { group ->
                item(key = group.key) {
                    FilterSection(
                        group=group,
                        selected=state.selectedFilters[group.key] ?: emptySet(),
                        enabled=!state.loading,
                        onToggle = { onToggleFilter(group.key,it) },
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Maximal zu ladende Seiten",style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (pages in listOf(1u,5u,10u,25u,50u)) {
                            FilterChip(
                                selected=state.maxPages==pages,
                                onClick={onPages(pages)},enabled=!state.loading,
                                label={Text("$pages")},
                            )
                        }
                    }
                    Button(
                        onClick=onSearch,
                        enabled=!state.loading && state.city.isNotBlank(),
                        modifier=Modifier.fillMaxWidth(),
                    ) { Text("Jetzt suchen") }
                }
            }
            item { HorizontalDivider() }
            item {
                Text("Gespeicherte Suchen",style = MaterialTheme.typography.titleLarge)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    OutlinedTextField(
                        value=presetName,onValueChange={presetName=it},
                        label={Text("Name der Suche")},singleLine=true,
                        modifier=Modifier.weight(1f),
                    )
                    Button(
                        onClick={
                            onSave(presetName)
                        },
                        enabled=!state.saving && presetName.isNotBlank(),
                    ) { Text(if (state.editingSavedSearchId == null) "Speichern" else "Aktualisieren") }
                }
            }
            if (state.editingSavedSearchId != null) {
                item {
                    TextButton(onClick = {
                        onNewPreset()
                        presetName = ""
                    }) { Text("Als neue Suche speichern") }
                }
            }
            if (state.savedSearches.isEmpty()) {
                item { Text("Noch keine Suche gespeichert.") }
            }
            items(state.savedSearches, key = { it.searchId }) { saved ->
                Card(modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(saved.name,style=MaterialTheme.typography.titleMedium)
                                Text(
                                    if (saved.definitionSupported) "${saved.city} · ${saved.radiusKm} km" else
                                        "Importierte Suchdefinition · bitte neu erstellen",
                                    style=MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (saved.unreadNewResults) {
                                Text("Neu",color=MaterialTheme.colorScheme.primary)
                            }
                        }
                        saved.lastError?.let {
                            Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled=saved.definitionSupported && !state.loading,
                                onClick={ onLoadSaved(saved.searchId) },
                            ) { Text("Bearbeiten") }
                            TextButton(
                                enabled=saved.definitionSupported && !state.loading,
                                onClick={ onRunSaved(saved.searchId) },
                            ) { Text("Ausführen") }
                            if (saved.unreadNewResults) {
                                TextButton(onClick={ onAcknowledge(saved.searchId) }) { Text("Gelesen") }
                            }
                            TextButton(onClick={ onDeleteSaved(saved.searchId) }) { Text("Löschen") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSection(
    group: SourceFilterGroup,
    selected: Set<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        OutlinedButton(
            onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth(),
        ) {
            Text((groupLabels[group.key] ?: group.key) + if (selected.isEmpty()) "" else " (${selected.size})")
        }
        if (expanded) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                group.options.forEach { option ->
                    FilterChip(
                        selected=option in selected,
                        onClick={onToggle(option)},enabled=enabled,
                        label={Text(option)},
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(card: SearchCard, onProfile: (Long,Long) -> Unit) {
    Card(modifier=Modifier.fillMaxWidth(),onClick={onProfile(card.listingId,card.personId)}) {
        Row(
            modifier=Modifier.padding(12.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp),
            verticalAlignment=Alignment.CenterVertically,
        ) {
            ProfileThumbnail(
                url=card.thumbnailUrl,
                modifier=Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)),
            )
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(card.name.ifBlank { "Inserat ${card.advertId}" },style=MaterialTheme.typography.titleMedium)
                Text(card.city,style=MaterialTheme.typography.bodySmall)
                if (card.hasVideo || card.isPremium || card.hasAiContent) {
                    Text(
                        listOfNotNull(
                            "Video".takeIf { card.hasVideo },
                            "Premium".takeIf { card.isPremium },
                            "KI".takeIf { card.hasAiContent },
                        ).joinToString(" · "),
                        style=MaterialTheme.typography.labelSmall,
                    )
                }
                Text("Profil öffnen",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}
