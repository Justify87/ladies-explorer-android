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
            modifier = Modifier.heightIn(min = ShellMetrics.touch).shellFocus(),
            contentPadding = PaddingValues(horizontal = 8.dp)) {
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
    modifier: Modifier = Modifier.padding(horizontal = 16.dp)) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = scheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DiscoverMode.entries.forEach { mode ->
                val selected = mode == active
                Surface(Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight().shellFocus()
                    .selectable(selected = selected, role = Role.Tab, onClick = { if (!selected) onSelect(mode) }),
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.medium) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                        Surface(color = if (selected) scheme.primaryContainer else Color.Transparent,
                            shape = MaterialTheme.shapes.medium) {
                            Text(mode.title, Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}
