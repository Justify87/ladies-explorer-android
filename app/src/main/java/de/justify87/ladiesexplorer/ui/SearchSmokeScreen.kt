package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Temporary M03 smoke-test UI. Full saved filters, swipe views and profiles belong to M05. */
@Composable
fun SearchSmokeScreen(viewModel: MainViewModel) {
    val state by viewModel.searchUiState.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Online-Suche", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = state.city,
            onValueChange = viewModel::setSearchCity,
            label = { Text("Ort") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !state.loading,
        )
        Text("Umkreis", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (radius in listOf(10u, 20u, 50u)) {
                FilterChip(
                    selected = state.radiusKm == radius,
                    onClick = { viewModel.setSearchRadius(radius) },
                    label = { Text(radius.toString() + " km") },
                    enabled = !state.loading,
                )
            }
        }
        Button(onClick = viewModel::startSearch, enabled = !state.loading && state.city.isNotBlank()) {
            Text("Suche starten")
        }
        if (state.loading) CircularProgressIndicator()
        state.error?.let { message ->
            SelectionContainer {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }
        state.response?.let { response ->
            Text(
                response.profiles.size.toString() + " Anzeigen · " +
                    response.fetchedPages.toString() + "/" + response.totalPages.toString() + " Seiten",
                style = MaterialTheme.typography.titleMedium,
            )
            if (!response.radiusVerified && response.profiles.isNotEmpty()) {
                Text(
                    "Der tatsächliche Suchradius konnte nicht vollständig bestätigt werden.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (response.partial) {
                Text(
                    "Unvollständige Suche: Seitenlimit, vorzeitiges Seitenende oder Radiusabweichung.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            for (card in response.profiles.take(30)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            card.name.ifBlank { "Inserat " + card.advertId },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(card.city)
                        TextButton(
                            onClick = { viewModel.loadProfile(card.listingId) },
                            enabled = !state.loading,
                        ) { Text("Profildaten laden") }
                    }
                }
            }
            if (response.profiles.size > 30) {
                Text("Vorschau auf 30 Ergebnisse begrenzt. Alle Suchergebnisse sind lokal gespeichert.")
            }
        }
        state.profile?.let { profile ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Profil: " + profile.name, style = MaterialTheme.typography.titleLarge)
                    Text(profile.city)
                    Text(profile.description)
                    Text(profile.media.size.toString() + " Medienreferenzen · Snapshot " + profile.snapshotId)
                }
            }
        }
    }
}
