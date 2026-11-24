package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.IntSize
import de.justify87.ladiesexplorer.core.ProfileMediaItem

/** One inert outgoing visual; no write token, callback or person payload. */
internal data class SwipeFlight(
    val card: Pair<Long, Long>,
    val session: Long,
    val direction: Float,
    val offset: Float,
    val size: IntSize,
    val top: Int,
    val media: ProfileMediaItem?,
) {
    val destination: Float get() = direction * (size.width.coerceAtLeast(1) * 1.2f)
}

internal fun profileHeroVisible(state: LazyListState, key: String): Boolean {
    val layout = state.layoutInfo
    return layout.visibleItemsInfo.any {
        it.key == key && it.offset < layout.viewportEndOffset &&
            it.offset + it.size > layout.viewportStartOffset
    }
}
