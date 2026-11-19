package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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

/** Coordinates are layout-owned, not a second gesture state machine. */
private class PhotoHitRegion {
    var host: LayoutCoordinates? = null
    var photo: LayoutCoordinates? = null

    fun bounds(): Rect? {
        val parent = host?.takeIf { it.isAttached } ?: return null
        val child = photo?.takeIf { it.isAttached } ?: return null
        return parent.localBoundingBoxOf(child, clipBounds = false)
    }
}

/**
 * One recognizer for the common hero hit path: a second finger on a different
 * gallery zone or on chrome is visible to the same coroutine. The measured
 * photo region is the only eligible start target; native child clickables
 * (including retry/video) get Main-pass priority and consume their own down.
 * Vertical gestures remain unconsumed for the enclosing LazyColumn.
 */
@Composable
internal fun PhotoGestureSurface(
    owner: Any?,
    modifier: Modifier = Modifier,
    onTap: ((fraction: Float, widthDp: Float) -> Boolean)? = null,
    onSwipe: ((Preference) -> Unit)? = null,
    onDrag: (Float) -> Unit = {},
    content: @Composable (photoRegion: Modifier) -> Unit,
) {
    val region = remember { PhotoHitRegion() }
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val drag by rememberUpdatedState(onDrag)
    val canTap = onTap != null
    val canSwipe = onSwipe != null
    val gesture = if (!canTap && !canSwipe) Modifier else Modifier.pointerInput(owner, canTap, canSwipe) {
        awaitEachGesture {
            // Receive even a consumed down, so a second finger cannot acquire
            // the gesture after the first started on a native button.
            val down = awaitFirstDown(requireUnconsumed = false)
            val bounds = region.bounds()
            if (down.isConsumed || bounds == null || bounds.width <= 0f || !bounds.contains(down.position))
                return@awaitEachGesture
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
                                if (!canSwipe || !change.pressed) break
                                horizontal = true
                            }
                            PhotoAxis.Pending -> Unit
                        }
                    }
                    if (horizontal) {
                        change.consume()
                        if (!change.pressed) {
                            photoSwipePreference(delta.x, bounds.width, 72.dp.toPx())?.let { swipe?.invoke(it) }
                            break
                        }
                        drag(delta.x)
                    } else if (!change.pressed) {
                        if (canTap && bounds.contains(change.position) &&
                            tap?.invoke((down.position.x - bounds.left) / bounds.width, bounds.width.toDp().value) == true)
                            change.consume()
                        break
                    } else {
                        // The LazyColumn may consume motion after the child's Main pass.
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.firstOrNull { it.id == down.id }?.isConsumed != false) break
                    }
                }
            } finally {
                if (horizontal) drag(0f)
            }
        }
    }
    Box(modifier.onGloballyPositioned { region.host = it }.then(gesture)) {
        content(Modifier.onGloballyPositioned { region.photo = it })
    }
}

/** Accessibility/keyboard targets have no independent touch recognizer. */
@Composable
internal fun PhotoTapTarget(label: String, modifier: Modifier, onTap: (() -> Unit)?) {
    val tap by rememberUpdatedState(onTap)
    val semantics = Modifier.semantics {
        contentDescription = label
        role = Role.Button
        if (onTap != null) onClick { tap?.invoke(); true } else disabled()
    }
    val keyboard = if (onTap == null) Modifier else Modifier.onKeyEvent {
        if (it.key != Key.Enter && it.key != Key.DirectionCenter && it.key != Key.Spacebar) false
        else {
            if (it.type == KeyEventType.KeyUp) tap?.invoke()
            true
        }
    }.focusable()
    Box(modifier.then(semantics).then(keyboard))
}
