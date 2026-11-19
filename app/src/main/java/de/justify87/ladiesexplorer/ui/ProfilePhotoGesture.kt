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

internal sealed interface PhotoGestureResult {
    data object Pending : PhotoGestureResult
    data object Tap : PhotoGestureResult
    data object Cancel : PhotoGestureResult
    data class Drag(val distance: Float) : PhotoGestureResult
    data class Release(val decision: Preference?) : PhotoGestureResult
}

/** One resolver per down. The Compose coroutine delegates all axis/release
 * decisions here; there is no additional presentation gesture state. */
internal class PhotoGestureResolver(
    private val slop: Float,
    private val width: Float,
    private val minimum: Float,
    private val longPressMillis: Long,
    private val canSwipe: Boolean,
) {
    private var horizontal = false

    fun step(dx: Float, dy: Float, pressed: Boolean, elapsed: Long, blocked: Boolean): PhotoGestureResult {
        if (blocked) return PhotoGestureResult.Cancel
        if (!horizontal) {
            if (elapsed >= longPressMillis) return PhotoGestureResult.Cancel
            when (photoAxis(dx, dy, slop)) {
                PhotoAxis.Vertical, PhotoAxis.Ambiguous -> return PhotoGestureResult.Cancel
                PhotoAxis.Horizontal -> {
                    if (!canSwipe || !pressed) return PhotoGestureResult.Cancel
                    horizontal = true
                }
                PhotoAxis.Pending -> Unit
            }
        }
        return if (horizontal) {
            if (pressed) PhotoGestureResult.Drag(dx)
            else PhotoGestureResult.Release(photoSwipePreference(dx, width, minimum))
        } else if (pressed) PhotoGestureResult.Pending else PhotoGestureResult.Tap
    }
}

/** Passive root-level cancellation fence. Unlike the recognizer, this never
 * consumes an event, resolves an axis, dispatches an action or drives UI.
 * A second finger can target the rail, details or root navigation instead of
 * the hero. Read the fence synchronously, without waiting for recomposition. */
internal class PhotoPointerFence {
    private var epoch = 0L
    private var multiple = false

    fun observe(count: Int) {
        multiple = count > 1
        if (multiple) epoch++
    }
    fun capture(): Long? = epoch.takeUnless { multiple }
    fun current(stamp: Long?): Boolean = stamp != null && !multiple && stamp == epoch
    fun invalidate() { epoch++; multiple = false }
}

internal fun Modifier.observePhotoPointers(fence: PhotoPointerFence): Modifier = pointerInput(fence) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                fence.observe(event.changes.count { it.pressed || it.previousPressed })
            }
        }
    } finally {
        fence.invalidate()
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
    pointerFence: PhotoPointerFence? = null,
    content: @Composable (photoRegion: Modifier) -> Unit,
) {
    val region = remember { PhotoHitRegion() }
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val drag by rememberUpdatedState(onDrag)
    val canTap = onTap != null
    val canSwipe = onSwipe != null
    val gesture = if (!canTap && !canSwipe) Modifier else Modifier.pointerInput(owner, canTap, canSwipe, pointerFence) {
        awaitEachGesture {
            // Receive even a consumed down, so a second finger cannot acquire
            // the gesture after the first started on a native button.
            val down = awaitFirstDown(requireUnconsumed = false)
            val bounds = region.bounds()
            if (down.isConsumed || bounds == null || bounds.width <= 0f || !bounds.contains(down.position))
                return@awaitEachGesture
            val stamp = pointerFence?.capture()
            if (pointerFence != null && stamp == null) return@awaitEachGesture
            // Freeze the actual rendered callbacks at down. A newer callback
            // must never inherit an in-flight old-profile decision.
            val tapOnDown = tap
            val swipeOnDown = swipe
            val dragOnDown = drag
            val initial = awaitPointerEvent(PointerEventPass.Final)
            if (initial.changes.count { it.pressed } != 1 ||
                initial.changes.firstOrNull { it.id == down.id }?.isConsumed != false) return@awaitEachGesture
            val resolver = PhotoGestureResolver(viewConfiguration.touchSlop, bounds.width, 72.dp.toPx(),
                viewConfiguration.longPressTimeoutMillis, canSwipe)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - down.position
                    val blocked = change.isConsumed || event.changes.any {
                        it.id != down.id && (it.pressed || it.previousPressed)
                    } || (pointerFence != null && !pointerFence.current(stamp))
                    when (val result = resolver.step(delta.x, delta.y, change.pressed,
                        change.uptimeMillis - down.uptimeMillis, blocked)) {
                        PhotoGestureResult.Cancel -> break
                        PhotoGestureResult.Pending -> {
                            // Parent LazyColumn consumption after the child's Main pass.
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.firstOrNull { it.id == down.id }?.isConsumed != false) break
                        }
                        PhotoGestureResult.Tap -> {
                            if (canTap && bounds.contains(change.position) &&
                                tapOnDown?.invoke((down.position.x - bounds.left) / bounds.width, bounds.width.toDp().value) == true)
                                change.consume()
                            break
                        }
                        is PhotoGestureResult.Drag -> {
                            change.consume()
                            dragOnDown(result.distance)
                        }
                        is PhotoGestureResult.Release -> {
                            change.consume()
                            result.decision?.let { swipeOnDown?.invoke(it) }
                            break
                        }
                    }
                }
            } finally {
                dragOnDown(0f)
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
