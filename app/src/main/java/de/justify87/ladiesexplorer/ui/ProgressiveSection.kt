package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    hint: String? = null, hintError: Boolean = false, enabled: Boolean = true,
) {
    TextButton(onClick = onToggle, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).shellFocus().semantics {
            stateDescription = if (expanded) "Ausgeklappt" else "Eingeklappt"
        }) {
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (hintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

// Adjacent lazy rows share the same surface without composing an entire history at once.
@Composable
internal fun ProgressiveSectionFrame(
    first: Boolean, last: Boolean, header: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val top = if (first) 16.dp else 0.dp
    val bottom = if (last) 16.dp else 0.dp
    Column(Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(
            topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
        .padding(horizontal = if (header) 0.dp else 12.dp)
        .padding(top = if (header) 0.dp else 4.dp, bottom = if (header) 0.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun ProgressiveSection(
    title: String, expanded: Boolean, onToggle: () -> Unit,
    hint: String? = null, hintError: Boolean = false, enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ProgressiveSectionFrame(first = true, last = !expanded, header = true) {
            ProgressiveSectionHeader(title, expanded, onToggle, hint = hint, hintError = hintError, enabled = enabled)
        }
        if (expanded) ProgressiveSectionFrame(first = false, last = true, header = false, content = content)
    }
}
