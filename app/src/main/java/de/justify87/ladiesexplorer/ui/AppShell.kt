package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal object ShellMetrics {
    val touch = 48.dp
    val header = 56.dp
    val navigation = 64.dp
    val inset = 16.dp
    val gap = 4.dp
}

internal fun shellFocusColor(dark: Boolean) = if (dark) Color(0xFFFFC18D) else Color(0xFF8E350B)

internal fun Modifier.shellFocus(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = shellFocusColor(MaterialTheme.colorScheme.surface.luminance() < .5f)
    Modifier.onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) color else Color.Transparent, MaterialTheme.shapes.small)
}

@Composable
internal fun ShellIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(ShellMetrics.touch).shellFocus(),
        content = content)
}

@Composable
internal fun ShellHeaderSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, content = content)
}

@Composable
internal fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    ShellHeaderSurface {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = ShellMetrics.header)
                .padding(start = if (onBack == null) ShellMetrics.inset else ShellMetrics.gap, end = ShellMetrics.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) ShellIconButton(onBack, backEnabled) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Column(Modifier.weight(1f).padding(vertical = ShellMetrics.gap)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            actions()
        }
    }
}

@Composable
internal fun RootNavigation(
    active: RootDestination,
    navigationEnabled: Boolean,
    unreadSearch: Boolean,
    onSelect: (RootDestination) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .height(IntrinsicSize.Min)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RootDestination.entries.forEach { destination ->
                val selected = destination == active
                val enabled = navigationEnabled || selected
                val scheme = MaterialTheme.colorScheme
                val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).heightIn(min = ShellMetrics.navigation).fillMaxHeight()
                        .shellFocus()
                        .selectable(selected = selected, enabled = enabled, role = Role.Tab,
                            onClick = { onSelect(destination) })
                        .padding(vertical = ShellMetrics.gap, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        color = if (selected) scheme.primaryContainer else Color.Transparent,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Box(Modifier.width(52.dp).height(28.dp), contentAlignment = Alignment.Center) {
                            val icon = when (destination) {
                                RootDestination.Discover -> Icons.Default.FavoriteBorder
                                RootDestination.Search -> Icons.Default.Search
                                RootDestination.Saved -> Icons.Default.Favorite
                                RootDestination.Settings -> Icons.Default.Settings
                            }
                            val iconColor = (if (selected) scheme.onPrimaryContainer else tint)
                                .let { if (enabled) it else it.copy(alpha = .38f) }
                            BadgedBox(badge = {
                                if (destination == RootDestination.Search && unreadSearch) Badge()
                            }) {
                                Icon(icon, contentDescription = if (destination == RootDestination.Search && unreadSearch)
                                    "Neue gespeicherte Suchergebnisse" else null, tint = iconColor)
                            }
                        }
                    }
                    Text(destination.title, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (enabled) tint else tint.copy(alpha = .38f))
                }
            }
        }
    }
}
