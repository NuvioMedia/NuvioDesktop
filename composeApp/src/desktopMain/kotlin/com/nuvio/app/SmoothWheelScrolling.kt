package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.awt.MouseInfo
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.min

// Mouse wheel scrolling as Chromium does it on Linux: each notch scrolls 120 DIP
// (ui/events/x/events_x_utils.cc, wayland_pointer.cc), eased in and out over
// 100-200 ms, and the next notch re-aims the animation without losing speed
// (cc/animation/scroll_offset_animation_curve.cc). Compose scrolls a notch by
// 3 * sqrt(viewport px) in a linear 100 ms instead, which is slower and stops
// abruptly. So notched wheel events are taken before Compose sees them and
// replayed frame by frame as precise ones.
//
// Linux only: Compose on Windows animates every wheel event, precise or not.

private const val NOTCH_DIP = 120.0

@Composable
internal fun SmoothWheelScrolling(content: @Composable () -> Unit) {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) {
        content()
        return
    }
    Box(
        propagateMinConstraints = true,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val horizontal = WheelAnimation()
                val vertical = WheelAnimation()
                lateinit var lastWheel: MouseWheelEvent
                var frames: Job? = null
                coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val wheel = event.awtEventOrNull as? MouseWheelEvent ?: continue
                            // Precise wheels, and the events replayed below, pass through.
                            val notched = wheel.wheelRotation != 0 &&
                                wheel.preciseWheelRotation == wheel.wheelRotation.toDouble()
                            if (event.type != PointerEventType.Scroll || !notched) continue
                            event.changes.forEach { it.consume() }
                            val animation = if (wheel.isShiftDown) horizontal else vertical
                            animation.addNotches(wheel.wheelRotation * NOTCH_DIP * wheel.component.displayScale, System.nanoTime())
                            lastWheel = wheel
                            if (frames?.isActive == true) continue
                            frames = launch {
                                var pendingX = 0.0
                                var pendingY = 0.0
                                do {
                                    withFrameNanos {}
                                    val now = System.nanoTime()
                                    val source = lastWheel
                                    val pointer = MouseInfo.getPointerInfo()?.location ?: break
                                    pendingX += horizontal.advance(now)
                                    pendingY += vertical.advance(now)
                                    val modifiers = source.modifiersEx
                                    pendingX = postPreciseWheel(source.component, pointer, pendingX, horizontal = true, modifiers)
                                    pendingY = postPreciseWheel(source.component, pointer, pendingY, horizontal = false, modifiers)
                                } while (horizontal.isRunning(now) || vertical.isRunning(now))
                            }
                        }
                    }
                }
            },
    ) {
        content()
    }
}

// One axis of Chromium's wheel animation, in pixels.
private class WheelAnimation {
    private var from = 0.0
    private var to = 0.0
    private var startNanos = 0L
    private var durationNanos = 0L
    private var curve = EaseInOut
    private var emitted = 0.0

    fun isRunning(now: Long) = now - startNanos < durationNanos

    // The distance covered since the last call.
    fun advance(now: Long): Double {
        val value = valueAt(now)
        return (value - emitted).also { emitted = value }
    }

    // ScrollOffsetAnimationCurve::UpdateTarget: a new segment from where the
    // animation is now, shaped to start at its current speed.
    fun addNotches(pixels: Double, now: Long) {
        val current = valueAt(now)
        val velocity = velocityAt(now)
        val delta = to + pixels - current
        var seconds = segmentSeconds(delta)
        // Don't let a fast animation take long to cover a short distance.
        if (abs(velocity) > EPSILON && delta / velocity > 0) seconds = min(seconds, delta / velocity * 2.5)
        curve = when {
            abs(delta) < EPSILON || abs(velocity) < EPSILON -> EaseInOut
            else -> CubicBezier(0.42, 0.42 * (velocity * seconds / delta).coerceIn(-1000.0, 1000.0), 0.58, 1.0)
        }
        from = current
        to += pixels
        startNanos = now
        durationNanos = if (abs(delta) < EPSILON) 0 else (seconds * 1e9).toLong()
    }

    private fun progress(now: Long) = (now - startNanos).toDouble() / durationNanos

    private fun valueAt(now: Long) =
        if (isRunning(now)) from + (to - from) * curve.value(progress(now)) else to

    private fun velocityAt(now: Long) =
        if (isRunning(now)) curve.slope(progress(now)) * (to - from) / (durationNanos / 1e9) else 0.0

    private companion object {
        const val EPSILON = 0.01
        val EaseInOut = CubicBezier(0.42, 0.0, 0.58, 1.0)

        // 12 frames at 60 Hz for a single notch, down to 6 for 480 px or more.
        fun segmentSeconds(delta: Double) = (14 - abs(delta) / 60).coerceIn(6.0, 12.0) / 60
    }
}

// CSS-style cubic-bezier timing function from (0, 0) to (1, 1).
private class CubicBezier(
    private val x1: Double,
    private val y1: Double,
    private val x2: Double,
    private val y2: Double,
) {
    fun value(x: Double) = sample(solve(x), y1, y2)

    fun slope(x: Double): Double {
        val t = solve(x)
        return derivative(t, y1, y2) / derivative(t, x1, x2)
    }

    // Finds t for x by bisection; x(t) only rises for the x1/x2 used here.
    private fun solve(x: Double): Double {
        var low = 0.0
        var high = 1.0
        repeat(32) {
            val t = (low + high) / 2
            if (sample(t, x1, x2) < x) low = t else high = t
        }
        return (low + high) / 2
    }

    private fun sample(t: Double, p1: Double, p2: Double) =
        ((1 - 3 * p2 + 3 * p1) * t + (3 * p2 - 6 * p1)) * t * t + 3 * p1 * t

    private fun derivative(t: Double, p1: Double, p2: Double) =
        3 * (1 - 3 * p2 + 3 * p1) * t * t + 2 * (3 * p2 - 6 * p1) * t + 3 * p1
}
