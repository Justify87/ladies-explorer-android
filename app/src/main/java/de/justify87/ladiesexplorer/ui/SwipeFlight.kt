package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

/** One inert outgoing visual; no write token, callback or person payload. */
internal data class SwipeFlight(
    val card: Pair<Long, Long>,
    val session: Long,
    val direction: Float,
    val offset: Float,
    val size: IntSize,
    val top: Int,
    val painter: Painter?,
    val left: Int = 0,
    val cropped: Boolean = false,
) {
    val destination: Float get() = direction * (size.width.coerceAtLeast(1) * 1.2f)
}

@Composable
internal fun SwipeFlightImage(painter: Painter, cropped: Boolean, modifier: Modifier = Modifier) {
    Image(painter = painter, contentDescription = null,
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentScale = if (cropped) ContentScale.Crop else ContentScale.Fit)
}

internal fun profileHeroVisible(state: LazyListState, key: String): Boolean {
    val layout = state.layoutInfo
    return layout.visibleItemsInfo.any {
        it.key == key && it.offset < layout.viewportEndOffset &&
            it.offset + it.size > layout.viewportStartOffset
    }
}
