package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference
import org.json.JSONArray
import org.json.JSONObject

private fun jsonRows(raw: String): List<Pair<String,String>> {
    return runCatching {
        val root = JSONObject(raw)
        val result = mutableListOf<Pair<String,String>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = root.opt(key)
            val visible = when (value) {
                is JSONArray -> (0 until value.length()).map { value.optString(it) }.joinToString(" · ")
                is JSONObject -> value.toString()
                null, JSONObject.NULL -> ""
                else -> value.toString()
            }
            if (visible.isNotBlank()) result.add(key to visible)
        }
        result
    }.getOrDefault(emptyList())
}

@Composable
fun ProfileDetailScreen(
    state: SearchUiState,
    onFavorite: (Long) -> Unit,
    onPreference: (Long, Preference) -> Unit,
    onConfirmIdentity: (Long,Long) -> Unit,
    onCancelArchive: () -> Unit,
    onBack: () -> Unit,
    actionsBusy: Boolean,
    actionError: String?,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    val detailListState = rememberLazyListState()
    var confirmTarget by remember(profile?.listingId) { mutableStateOf<Long?>(null) }

    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("Personen zusammenführen?") },
            text = {
                Text("Das Inserat wird mit Person #$target zusammengeführt. Die Entscheidung verändert Zuordnungen und die gemeinsame Historie. Bitte nur bei sicherer Identität bestätigen.")
            },
            confirmButton = {
                Button(onClick = {
                    val listingId = profile?.listingId
                    confirmTarget = null
                    if (listingId != null) onConfirmIdentity(listingId,target)
                }) { Text("Zusammenführen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("Abbrechen") }
            },
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
    val heroHeight = (maxHeight * .84f).coerceIn(220.dp, 720.dp)
    LazyColumn(
        state = detailListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (profile == null) item {
            TextButton(onClick = onBack) { Text("Zurück") }
        }
        if (state.loading) item { CircularProgressIndicator() }
        state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        (state.actionError ?: actionError)?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        if (profile != null) {
            item(key = "media") {
                ProfileMediaHero(state.profileMedia, Modifier.fillMaxWidth().height(heroHeight),
                    onBack = onBack, originalUrl = state.profileIdentity?.profileUrl)
            }
            item(key = "identity") {
                val age = jsonRows(profile.traitsJson).firstOrNull { it.first.equals("Alter", true) }
                    ?.second?.let { Regex("""^([0-9]{2})(?:\s*Jahre?)?$""").matchEntire(it.trim())?.groupValues?.get(1) }
                ProfileIdentityHeader(
                    name = profile.name,
                    place = listOfNotNull(profile.city, profile.postalCode).filter { it.isNotBlank() }.joinToString(" · "),
                    age = age,
                    identity = state.profileIdentity,
                    person = state.selectedPerson,
                    hasVideo = state.profileMedia.any { it.kind == "VIDEO" },
                )
                profile.hiddenLocation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            item(key = "actions") {
                ProfileActionRail(state.selectedPerson, profile.phoneDisplay,
                    actionsBusy || state.actionBusy, onFavorite, onPreference)
            }
            if (state.archiving) {
                item {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier=Modifier.width(28.dp).height(28.dp))
                        Text("  Medien werden lokal archiviert",modifier=Modifier.weight(1f))
                        TextButton(onClick=onCancelArchive) { Text("Stoppen") }
                    }
                }
            }
            state.mediaArchive?.let { report ->
                item {
                    Text(
                        "Archiv: ${report.downloaded} geladen · ${report.reused} wiederverwendet · ${report.failed} fehlgeschlagen · ${report.deferred} zurückgestellt",
                        style=MaterialTheme.typography.bodySmall,
                    )
                }
            }
            state.mediaWarning?.let {
                item { Text(it,color=MaterialTheme.colorScheme.error) }
            }
            profileContent(state, actionsBusy) { confirmTarget = it }
        }
    }
    }
}

/** Existing detail sections remain shared; tab redesign is M05.6. */
internal fun LazyListScope.profileContent(
    state: SearchUiState,
    actionsBusy: Boolean,
    onConfirm: (Long) -> Unit,
) {
    val profile = state.profile ?: return
    item {
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Über das Profil",style=MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(profile.description.ifBlank { "Keine Beschreibung vorhanden." })
            }
        }
    }
    val sections = listOf(
        "Merkmale" to jsonRows(profile.traitsJson),
        "Services" to jsonRows(profile.servicesJson),
        "Öffnungszeiten" to jsonRows(profile.openingTimesJson),
    )
    sections.forEach { (title,rows) ->
        if (rows.isNotEmpty()) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text(title,style=MaterialTheme.typography.titleLarge)
                    rows.forEach { (key,value) ->
                        Text(key,style=MaterialTheme.typography.labelLarge)
                        Text(value,style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
    if (state.identityCandidates.isNotEmpty()) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text("Mögliche Identitätsübereinstimmungen",style=MaterialTheme.typography.titleLarge)
                Text("Diese Hinweise führen niemals automatisch zu einer Zusammenführung.")
            }
        }
        items(state.identityCandidates,key={it.personId}) { candidate ->
            Card {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text("Person #${candidate.personId} · ${candidate.decision}")
                    Text(
                        listOfNotNull(
                            "Telefon".takeIf { candidate.phoneMatch },
                            "Quellprofil".takeIf { candidate.sourceProfileMatch },
                            "${candidate.strongImageMatches} Bildtreffer".takeIf { candidate.imageMatches>0u },
                        ).joinToString(" · "),
                    )
                    OutlinedButton(
                        onClick={ onConfirm(candidate.personId) },
                        enabled=!state.actionBusy && !actionsBusy,
                    ) { Text("Zusammenführung prüfen") }
                }
            }
        }
    }
    if (state.changeEvents.isNotEmpty()) {
        item {
            HorizontalDivider()
            Text("Änderungshistorie",style=MaterialTheme.typography.titleLarge)
        }
        items(state.changeEvents,key={it.changeEventId}) { event ->
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(
                    "${event.createdAt.take(16)} · ${event.eventType}",
                    style=MaterialTheme.typography.labelMedium,
                )
                event.fieldKey?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
                event.newJson?.let {
                    SelectionContainer {
                        Text(it.take(220),style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
