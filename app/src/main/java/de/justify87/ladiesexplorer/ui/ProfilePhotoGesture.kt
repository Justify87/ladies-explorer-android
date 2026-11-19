package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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

/**
 * Admission of a single native touch stream, shared by the entire profile
 * screen. Cancellation is sticky until the last pointer lifts. No gesture,
 * button callback, domain operation or axis decision lives here.
 */
internal class ProfileTouchGate {
    var active by mutableStateOf(false)
        private set
    var revision by mutableLongStateOf(0L)
        private set
    private var blocked = false
    private var stream = 0L

    fun observe(previous: Int, pressed: Int, eligible: Boolean): Boolean {
        if (previous == 0 && pressed > 0) {
            stream++
            active = true
            blocked = false
        } else if (!active && previous > 0) {
            // A replaced observer cannot admit the tail of an old stream.
            stream++
            active = true
            invalidate()
        }
        if (active && (!eligible || previous > 1 || pressed > 1)) invalidate()
        val consume = active && blocked
        if (pressed == 0) {
            active = false
            blocked = false
        }
        return consume
    }

    data class Stamp(val stream: Long, val revision: Long)

    fun capture(): Stamp? = if (active && !blocked) Stamp(stream, revision) else null
    fun current(stamp: Stamp?): Boolean = stamp != null && stamp.stream == stream &&
        stamp.revision == revision && !blocked

    fun invalidate() {
        if (active && !blocked) {
            blocked = true
            revision++
        }
    }
}

/** Initial pass is before any native clickable/scrollable. Consume only a
 * revoked stream, including its last Up, so the rail cannot independently
 * commit after a second finger has cancelled the photo. */
internal fun Modifier.admitProfileTouches(gate: ProfileTouchGate, eligible: () -> Boolean): Modifier =
    pointerInput(gate) {
        try {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (gate.observe(event.changes.count { it.previousPressed },
                            event.changes.count { it.pressed }, eligible())) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        } finally {
            gate.invalidate()
        }
    }

/** Existing Compose coordinates own the hit regions. Each start captures
 * actual bounds, including the native gallery targets and root position. */
internal data class PhotoHitGeometry(val photo: Rect, val rootOrigin: Offset, val gallery: List<Rect>) {
    fun galleryAt(position: Offset): Boolean = gallery.any { it.contains(position) }
}

internal data class PhotoHitModifiers(val photo: Modifier, val previous: Modifier, val next: Modifier)

private class PhotoHitRegion {
    var host: LayoutCoordinates? = null
    var photo: LayoutCoordinates? = null
    var previous: LayoutCoordinates? = null
    var next: LayoutCoordinates? = null

    fun geometry(): PhotoHitGeometry? {
        val parent = host?.takeIf { it.isAttached } ?: return null
        fun bounds(child: LayoutCoordinates?): Rect? = child?.takeIf { it.isAttached }
            ?.let { parent.localBoundingBoxOf(it, clipBounds = false) }
        return PhotoHitGeometry(bounds(photo) ?: return null, parent.localToRoot(Offset.Zero),
            listOfNotNull(bounds(previous), bounds(next)))
    }
}

/**
 * Native gallery clickables own taps (including TalkBack/DPAD, focus and
 * indication). This single ancestor recognizer owns horizontal drag only.
 * Initial allows it to cancel a gallery click upon horizontal lock; Main
 * identifies other native child controls; Final lets LazyColumn have the
 * first opportunity to consume vertical/ambiguous motion.
 */
@Composable
internal fun PhotoGestureSurface(
    owner: Any?,
    modifier: Modifier = Modifier,
    galleryEnabled: Boolean = false,
    onSwipe: ((Preference) -> Unit)? = null,
    onDrag: (Float) -> Unit = {},
    touchGate: ProfileTouchGate? = null,
    content: @Composable (PhotoHitModifiers) -> Unit,
) {
    val region = remember { PhotoHitRegion() }
    val swipe by rememberUpdatedState(onSwipe)
    val drag by rememberUpdatedState(onDrag)
    val canSwipe = onSwipe != null
    val revision = touchGate?.revision
    val gesture = if (!galleryEnabled && !canSwipe) Modifier else
        Modifier.pointerInput(owner, galleryEnabled, canSwipe, touchGate, revision) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val geometry = region.geometry()
                if (down.isConsumed || geometry == null || geometry.photo.width <= 0f ||
                    !geometry.photo.contains(down.position)) return@awaitEachGesture
                val gallery = galleryEnabled && geometry.galleryAt(down.position)
                if (!gallery && !canSwipe) return@awaitEachGesture
                val stamp = touchGate?.capture()
                if (touchGate != null && stamp == null) return@awaitEachGesture
                val swipeOnDown = swipe
                val dragOnDown = drag
                // A consumed down is allowed only on the measured native
                // gallery targets; retry/video/menu remain exclusive.
                val main = awaitPointerEvent(PointerEventPass.Main)
                if (main.changes.count { it.pressed } != 1 ||
                    (!gallery && main.changes.firstOrNull { it.id == down.id }?.isConsumed != false))
                    return@awaitEachGesture
                val finalDown = awaitPointerEvent(PointerEventPass.Final)
                if (!gallery && finalDown.changes.firstOrNull { it.id == down.id }?.isConsumed != false)
                    return@awaitEachGesture
                val resolver = PhotoGestureResolver(viewConfiguration.touchSlop, geometry.photo.width,
                    72.dp.toPx(), viewConfiguration.longPressTimeoutMillis, canSwipe)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.position - down.position
                        val blocked = change.isConsumed || region.geometry() != geometry ||
                            event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) } ||
                            (touchGate != null && !touchGate.current(stamp))
                        when (val result = resolver.step(delta.x, delta.y, change.pressed,
                            change.uptimeMillis - down.uptimeMillis, blocked)) {
                            PhotoGestureResult.Cancel -> {
                                if (gallery) {
                                    // Up must be consumed before the native click.
                                    // Motion may be used by the parent scroll first.
                                    if (!change.pressed || blocked) change.consume()
                                    else awaitPointerEvent(PointerEventPass.Final).changes
                                        .firstOrNull { it.id == down.id }?.consume()
                                }
                                break
                            }
                            PhotoGestureResult.Tap -> break // native clickable
                            PhotoGestureResult.Pending -> {
                                val final = awaitPointerEvent(PointerEventPass.Final)
                                if (final.changes.firstOrNull { it.id == down.id }?.isConsumed != false) break
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
        content(PhotoHitModifiers(
            Modifier.onGloballyPositioned { region.photo = it },
            Modifier.onGloballyPositioned { region.previous = it },
            Modifier.onGloballyPositioned { region.next = it },
        ))
    }
}

@Composable
internal fun PhotoTapTarget(label: String, modifier: Modifier, owner: Any? = null, onTap: (() -> Unit)?) {
    // Native press/keyboard state is disposed with the very target it
    // originated on. No click may be rebound to the next image.
    key(owner) {
        Box(modifier.semantics { contentDescription = label }
            .clickable(enabled = onTap != null, role = Role.Button) { onTap?.invoke() })
    }
}
