package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.Preference
import kotlin.math.abs
import kotlin.math.hypot

internal enum class PhotoAxis { Pending, Horizontal, Vertical, Ambiguous }

/** Resolve once at the platform slop. An ambiguous diagonal never becomes a decision. */
internal fun photoAxis(dx: Float, dy: Float, slop: Float): PhotoAxis {
    if (hypot(dx, dy) <= slop) return PhotoAxis.Pending
    val x = abs(dx)
    val y = abs(dy)
    return when {
        x > y * 1.25f -> PhotoAxis.Horizontal
        y > x * 1.25f -> PhotoAxis.Vertical
        else -> PhotoAxis.Ambiguous
    }
}

internal fun photoSwipePreference(dx: Float, width: Float, minimum: Float): Preference? {
    if (width <= 0f) return null
    val threshold = maxOf(width * .25f, minimum)
    return when {
        dx >= threshold -> Preference.LIKE
        dx <= -threshold -> Preference.DISLIKE
        else -> null
    }
}

/**
 * One touch recognizer on a free photo hit region, never on an ancestor of a
 * button. Sibling controls win native hit testing (including retry and video).
 * The LazyColumn retains unconsumed vertical motion; no synthetic scrolling.
 * The pointer coroutine is the only lifetime owner of the axis/initial pointer.
 */
@Composable
internal fun PhotoGestureZone(
    modifier: Modifier,
    owner: Any?,
    cardWidth: Dp,
    label: String? = null,
    onTap: (() -> Unit)? = null,
    onSwipe: ((Preference) -> Unit)? = null,
    onDrag: (Float) -> Unit = {},
) {
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val drag by rememberUpdatedState(onDrag)
    val canTap = onTap != null
    val canSwipe = onSwipe != null
    val semantics = if (label == null) Modifier else Modifier.semantics {
        contentDescription = label
        role = Role.Button
        if (canTap) onClick {
            tap?.invoke()
            true
        } else disabled()
    }
    // Semantics and keyboard share the tap operation, without adding a second
    // touch recognizer (clickable would introduce its own press detector).
    val keyboard = if (!canTap) Modifier else Modifier.onKeyEvent {
        if (it.key != Key.Enter && it.key != Key.DirectionCenter && it.key != Key.Spacebar) false
        else {
            if (it.type == KeyEventType.KeyUp) tap?.invoke()
            true
        }
    }.focusable()
    val gesture = if (!canTap && !canSwipe) Modifier else Modifier.pointerInput(owner, cardWidth, canTap, canSwipe) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val initial = awaitPointerEvent(PointerEventPass.Final)
            if (initial.changes.count { it.pressed } != 1 ||
                initial.changes.firstOrNull { it.id == down.id }?.isConsumed != false) return@awaitEachGesture
            var horizontal = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any {
                            it.id != down.id && (it.pressed || it.previousPressed)
                        }) break
                    val delta = change.position - down.position
                    if (!horizontal) {
                        if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) break
                        when (photoAxis(delta.x, delta.y, viewConfiguration.touchSlop)) {
                            PhotoAxis.Vertical, PhotoAxis.Ambiguous -> break
                            PhotoAxis.Horizontal -> {
                                // Do not acquire a drag on the release event or in standalone.
                                if (!canSwipe || !change.pressed) break
                                horizontal = true
                            }
                            PhotoAxis.Pending -> Unit
                        }
                    }
                    if (horizontal) {
                        change.consume()
                        if (!change.pressed) {
                            photoSwipePreference(delta.x, cardWidth.toPx(), 72.dp.toPx())?.let { swipe?.invoke(it) }
                            break
                        }
                        drag(delta.x)
                    } else if (!change.pressed) {
                        if (canTap && change.position.x in 0f..size.width.toFloat() &&
                            change.position.y in 0f..size.height.toFloat()) {
                            change.consume()
                            tap?.invoke()
                        }
                        break
                    } else {
                        // A parent may have consumed motion after the child's Main pass.
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.firstOrNull { it.id == down.id }?.isConsumed != false) break
                    }
                }
            } finally {
                if (horizontal) drag(0f)
            }
        }
    }
    Box(modifier.then(semantics).then(keyboard).then(gesture))
}
