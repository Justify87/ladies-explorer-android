package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DiscoveryChrome(summary: String, availability: AvailabilityMode, availabilityEnabled: Boolean,
    owner: Any?, active: DiscoverMode, onMode: (DiscoverMode) -> Unit,
    onAvailability: (AvailabilityMode) -> Unit, modifier: Modifier = Modifier) {
    var opened by remember(owner, availabilityEnabled) { mutableStateOf(false) }
    val latestOwner by rememberUpdatedState(owner)
    val latestEnabled by rememberUpdatedState(availabilityEnabled)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DiscoveryContextStrip {
            Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box {
                TextButton(onClick = { opened = true }, enabled = availabilityEnabled,
                    modifier = Modifier.heightIn(min = 48.dp).shellFocus()) { Text(availability.label + " ▾") }
                DropdownMenu(expanded = opened && availabilityEnabled, onDismissRequest = { opened = false }) {
                    AvailabilityMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(if (mode == availability) "✓ ${mode.label}" else mode.label) },
                            onClick = {
                                opened = false
                                if (latestEnabled && latestOwner == owner) onAvailability(mode)
                            })
                    }
                }
            }
        }
        DiscoverModeSwitcher(active, onMode, Modifier)
    }
}

@Composable
internal fun DiscoveryContextStrip(content: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
internal fun DiscoverModeSwitcher(active: DiscoverMode, onSelect: (DiscoverMode) -> Unit,
    modifier: Modifier = Modifier.padding(horizontal = 16.dp)) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DiscoverMode.entries.forEach { mode ->
                val selected = mode == active
                Surface(Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight().shellFocus()
                    .selectable(selected = selected, role = Role.Tab, onClick = { if (!selected) onSelect(mode) }),
                    color = if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(mode.title, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface)
                    }
                }
            }
        }
    }
}
