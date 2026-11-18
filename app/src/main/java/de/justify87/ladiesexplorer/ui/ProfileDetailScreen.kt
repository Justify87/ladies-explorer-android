package de.justify87.ladiesexplorer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private fun openExternal(context: Context, link: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))
    }
}

private fun phoneForWhatsApp(phone: String): String? {
    val digits = phone.filter(Char::isDigit)
    if (digits.length < 9) return null
    return when {
        phone.trim().startsWith("+") -> digits
        digits.startsWith("0049") -> digits.drop(2)
        digits.startsWith("0") -> "49${digits.drop(1)}"
        else -> digits
    }
}

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
    onConfirmIdentity: (Long,Long) -> Unit,
    onCancelArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profile = state.profile
    var confirmTarget by remember(profile?.listingId) { mutableStateOf<Long?>(null) }
    var preview by remember(profile?.snapshotId) { mutableStateOf<ProfileMediaItem?>(null) }

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
    preview?.let { item ->
        Dialog(onDismissRequest = { preview = null }) {
            AsyncImage(
                model = item.localPath?.let(::File) ?: item.url,
                contentDescription = "Große Bildansicht",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(500.dp).clickable { preview = null },
            )
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.loading) item { CircularProgressIndicator() }
        state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        state.actionError?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        if (profile != null) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(profile.name,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
                        state.selectedPersonId?.let { id ->
                            OutlinedButton(onClick={ onFavorite(id) },enabled=!state.actionBusy) {
                                androidx.compose.material3.Icon(
                                    if (state.selectedPerson?.favorite == true) Icons.Default.Favorite
                                    else Icons.Default.FavoriteBorder,
                                    contentDescription=null,
                                )
                                Text(if (state.selectedPerson?.favorite == true) " Gespeichert" else " Favorit")
                            }
                        }
                    }
                    Text(listOfNotNull(profile.city,profile.postalCode).filter { it.isNotBlank() }.joinToString(" · "))
                    profile.hiddenLocation?.let { Text(it,style=MaterialTheme.typography.bodyMedium) }
                }
            }
            if (state.profileMedia.isNotEmpty()) {
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Galerie",style=MaterialTheme.typography.titleLarge)
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            items(state.profileMedia, key={it.position.toString()+":"+it.kind}) { media ->
                                Card(
                                    modifier=Modifier.width(250.dp).height(290.dp),
                                    onClick={
                                        if (media.kind == "VIDEO") openExternal(context,media.url)
                                        else preview=media
                                    },
                                ) {
                                    if (media.kind == "VIDEO") {
                                        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                                            Text("Video extern öffnen")
                                        }
                                    } else {
                                        AsyncImage(
                                            model=media.localPath?.let(::File) ?: media.url,
                                            contentDescription="Profilbild ${media.position}",
                                            contentScale=ContentScale.Crop,
                                            modifier=Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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
            item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Über das Profil",style=MaterialTheme.typography.titleLarge)
                    SelectionContainer {
                        Text(profile.description.ifBlank { "Keine Beschreibung vorhanden." })
                    }
                }
            }
            profile.phoneDisplay?.takeIf { it.isNotBlank() }?.let { phone ->
                item {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick={ openExternal(context,"tel:${Uri.encode(phone)}") },
                        ) { Text("Anrufen") }
                        phoneForWhatsApp(phone)?.let { normalized ->
                            OutlinedButton(
                                onClick={ openExternal(context,"https://wa.me/$normalized") },
                            ) { Text("WhatsApp extern") }
                        }
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
                                onClick={ confirmTarget=candidate.personId },
                                enabled=!state.actionBusy,
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
    }
}
