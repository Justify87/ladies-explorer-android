package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class ProfileNotice(
    val label: String,
    val message: String,
    val action: String? = null,
    val enabled: Boolean = true,
    val invoke: () -> Unit = {},
    val warning: Boolean = false,
)

internal data class ProfileNoticeIndicator(val label: String, val warning: Boolean)

internal data class ProfileShellPresentation(
    val budget: ProfileShellBudget,
    val heroHeight: Dp,
    val identityInHeader: Boolean,
    val photoShortcut: Boolean,
)

/** Bound fixed chrome; put full messages in a dialog rather than shrinking the scrolling body. */
@Composable
internal fun ProfileShell(
    modifier: Modifier,
    listState: LazyListState,
    heroKey: String,
    rootHeight: Dp = 0.dp,
    hasRail: Boolean,
    notices: List<ProfileNotice>,
    noticeOwner: Any?,
    header: @Composable (ProfileShellPresentation, ProfileNoticeIndicator?, () -> Unit) -> Unit,
    rail: @Composable () -> Unit = {},
    fixedNoticeSlot: Boolean = false,
    heroLeadingHeight: Dp? = null,
    content: @Composable (ProfileShellPresentation) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val width = maxWidth.coerceAtMost(ShellMetrics.maxProfileWidth)
        val height = maxHeight
        val fontScale = LocalDensity.current.fontScale
        val budget = profileShellBudget(width.value, height.value, rootHeight.value, fontScale,
            if (fixedNoticeSlot) false else notices.isNotEmpty(), hasRail)
        val heroHeight = profileHeroHeight(width.value, height.value + rootHeight.value, budget.body, heroLeadingHeight?.value).dp
        // Read measured lazy geometry without recomposing for every scroll pixel.
        val handoff by remember(listState, heroKey) {
            derivedStateOf { listState.headerOwnsIdentity(heroKey) }
        }
        val scrolled by remember(listState) {
            derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
        }
        val measuredBudget = budget.copy(compactGallery = budget.compactGallery || heroHeight < 320.dp)
        val presentation = ProfileShellPresentation(measuredBudget, heroHeight, handoff, scrolled)
        val summary = notices.firstOrNull { it.warning } ?: notices.firstOrNull()
        val indicator = summary?.let { ProfileNoticeIndicator(it.label, it.warning) }
        val signature = notices.map { listOf(it.label, it.message, it.action, it.enabled, it.warning) }
        var dialog by remember(noticeOwner, signature) { mutableStateOf(false) }
        var active by remember { mutableStateOf(true) }
        DisposableEffect(Unit) { onDispose { active = false } }
        val currentNotices by rememberUpdatedState(notices)
        val currentOwner by rememberUpdatedState(noticeOwner)
        val currentSignature by rememberUpdatedState(signature)
        fun runNotice(index: Int) {
            if (!active || currentOwner != noticeOwner || currentSignature != signature) return
            currentNotices.getOrNull(index)?.takeIf { it.enabled }?.invoke?.invoke()
            dialog = false
        }
        if (dialog && notices.isNotEmpty()) {
            AlertDialog(onDismissRequest = { dialog = false },
                title = { Text("Status und Hinweise") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ShellMetrics.inset)) {
                        notices.forEachIndexed { index, item ->
                            Text(item.message, color = if (item.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            item.action?.let { label ->
                                TextButton(onClick = { runNotice(index) }, enabled = item.enabled,
                                    modifier = Modifier.heightIn(min = ShellMetrics.touch)) { Text(label) }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = false }) { Text("Schließen") } })
        }
        Column(Modifier.width(width).fillMaxHeight()) {
            ShellHeaderSurface(Modifier.fillMaxWidth().height(budget.header.dp)) {
                header(presentation, indicator.takeIf { budget.noticeInHeader }, { dialog = true })
            }
            if (budget.notice > 0f) {
                val index = notices.indexOfFirst { it.action != null && it.enabled }
                val item = notices.getOrNull(index)
                Row(Modifier.fillMaxWidth().height(budget.notice.dp).padding(horizontal = ShellMetrics.gap),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { dialog = true }, modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(horizontal = ShellMetrics.small)) {
                        Text(indicator?.label.orEmpty(),
                            color = if (notices.any { it.warning }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (notices.size > 1) Text(" +${notices.size - 1}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (item != null && width >= 300.dp && fontScale < 1.5f) {
                        TextButton(onClick = { runNotice(index) }, modifier = Modifier.fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = ShellMetrics.small)) {
                            Text(item.action.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val bodyHero = profileHeroHeight(width.value, height.value + rootHeight.value,
                    maxHeight.value, heroLeadingHeight?.value).dp
                content(presentation.copy(heroHeight = bodyHero,
                    budget = budget.copy(body = maxHeight.value,
                        compactGallery = budget.compactGallery || bodyHero < 320.dp)))
            }
            if (budget.rail > 0f) Box(Modifier.fillMaxWidth().height(budget.rail.dp)) { rail() }
        }
    }
}

/** Keep header height and line budgets stable across identity modes. */
@Composable
internal fun ProfileShellTitle(title: String, context: String, status: String, short: Boolean,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium) {
    val click = if (onClick == null) Modifier else Modifier.shellFocus().clickable(role = Role.Button, onClick = onClick)
    Column(modifier.then(click).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        if (short && LocalDensity.current.fontScale >= 1.5f) {
            // At large font sizes, expose full identity/status through the context action.
            Text(listOf(context, title).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(title, style = if (short) MaterialTheme.typography.labelLarge else titleStyle,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (context.isNotBlank()) Text(context, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!short && status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun IdentityInfoDialog(identity: HeroIdentity, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Profilinformationen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(ShellMetrics.small)) {
                Text(identity.title, style = MaterialTheme.typography.titleLarge)
                Text(identity.subtitle)
                if (identity.decisions.isNotBlank()) Text(identity.decisions)
            }
        }, confirmButton = { TextButton(onClick = dismiss) { Text("Schließen") } })
}

@Composable
internal fun ProfileStatusButton(indicator: ProfileNoticeIndicator, onClick: () -> Unit) {
    ShellIconButton(onClick = onClick) {
        Icon(if (indicator.warning) Icons.Default.Warning else Icons.Default.Info,
            contentDescription = "Status und Hinweise: ${indicator.label}",
            tint = if (indicator.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
