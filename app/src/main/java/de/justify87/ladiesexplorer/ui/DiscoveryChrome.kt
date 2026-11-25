package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AvailabilityQuickChoice(availability: AvailabilityMode, enabled: Boolean,
    owner: Any?, onAvailability: (AvailabilityMode) -> Unit, modifier: Modifier = Modifier) {
    var opened by remember(owner, enabled) { mutableStateOf(false) }
    val latestOwner by rememberUpdatedState(owner)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestAction by rememberUpdatedState(onAvailability)
    DisposableEffect(Unit) { onDispose { opened = false } }
    Box(modifier) {
        TextButton(onClick = { opened = true }, enabled = enabled,
            modifier = Modifier.sizeIn(minWidth = ShellMetrics.touch, minHeight = ShellMetrics.touch).shellFocus(),
            contentPadding = PaddingValues(horizontal = ShellMetrics.small)) {
            Text(availability.label + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = opened && enabled, onDismissRequest = { opened = false }) {
            AvailabilityMode.entries.forEach { mode ->
                DropdownMenuItem(text = { Text(if (mode == availability) "✓ ${mode.label}" else mode.label) },
                    onClick = {
                        if (opened && latestEnabled && latestOwner == owner) {
                            opened = false
                            latestAction(mode)
                        }
                    })
            }
        }
    }
}

@Composable
internal fun DiscoverModeSwitcher(active: DiscoverMode, onSelect: (DiscoverMode) -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = ShellMetrics.inset)) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = scheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically) {
            DiscoverMode.entries.forEach { mode ->
                val selected = mode == active
                Surface(Modifier.weight(1f).heightIn(min = ShellMetrics.touch).fillMaxHeight().shellFocus()
                    .selectable(selected = selected, role = Role.Tab, onClick = { if (!selected) onSelect(mode) }),
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.small) {
                    Box(Modifier.fillMaxSize().padding(ShellMetrics.gap), contentAlignment = Alignment.Center) {
                        Surface(Modifier.fillMaxSize(), color = if (selected) scheme.primaryContainer else Color.Transparent,
                            shape = MaterialTheme.shapes.extraSmall) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(mode.title, Modifier.padding(ShellMetrics.gap),
                                    textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun hasDiscoverySearchStatus(state: SearchUiState): Boolean =
    state.loading || state.error != null || state.response?.let { it.partial || !it.radiusVerified } == true

@Composable
internal fun DiscoverySearchRunStatus(state: SearchUiState, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val active = state.response
        if (active != null && (active.partial || !active.radiusVerified)) {
            Text("Teilergebnis / Radius nicht bestätigt", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium)
        }
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Suche läuft …", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel, enabled = !state.searchCancelling,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (state.searchCancelling) "Wird beendet …" else "Abbrechen")
                }
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
