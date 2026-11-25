package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun ProgressiveSectionHeader(
    title: String, expanded: Boolean, onToggle: () -> Unit,
    hint: String? = null, hintError: Boolean = false, enabled: Boolean = true, compact: Boolean = false,
) {
    TextButton(onClick = onToggle, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = ShellMetrics.inset, vertical = if (compact) ShellMetrics.gap else ShellMetrics.small),
        modifier = Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch).shellFocus().semantics {
            stateDescription = if (expanded) "Ausgeklappt" else "Eingeklappt"
        }) {
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (hintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(ShellMetrics.small))
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

// Adjacent lazy rows share the same surface without composing an entire history at once.
@Composable
internal fun ProgressiveSectionFrame(
    first: Boolean, last: Boolean, header: Boolean, compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val square = CornerSize(0.dp)
    Column(Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape.copy(
            topStart = if (first) shape.topStart else square,
            topEnd = if (first) shape.topEnd else square,
            bottomStart = if (last) shape.bottomStart else square,
            bottomEnd = if (last) shape.bottomEnd else square))
        .padding(horizontal = if (header) 0.dp else if (compact) ShellMetrics.small else ShellMetrics.inset)
        .padding(top = if (header || compact) 0.dp else ShellMetrics.gap,
            bottom = if (header) 0.dp else if (compact) ShellMetrics.gap else ShellMetrics.inset),
        verticalArrangement = Arrangement.spacedBy(if (compact) ShellMetrics.gap else ShellMetrics.small), content = content)
}

@Composable
internal fun ProgressiveSection(
    title: String, expanded: Boolean, onToggle: () -> Unit,
    hint: String? = null, hintError: Boolean = false, enabled: Boolean = true, compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ProgressiveSectionFrame(first = true, last = !expanded, header = true, compact = compact) {
            ProgressiveSectionHeader(title, expanded, onToggle, hint = hint, hintError = hintError, enabled = enabled, compact = compact)
        }
        if (expanded) ProgressiveSectionFrame(first = false, last = true, header = false, compact = compact, content = content)
    }
}
