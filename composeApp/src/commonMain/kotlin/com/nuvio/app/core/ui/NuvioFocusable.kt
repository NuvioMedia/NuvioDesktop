package com.nuvio.app.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.isDesktop
import kotlin.concurrent.Volatile

fun Modifier.nuvioFocusBorder(
    shape: Shape,
    borderWidth: Dp = 3.dp,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio
    this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused && isDesktop) Modifier.border(
                width = borderWidth,
                color = tokens.colors.focusRing,
                shape = shape,
            ) else Modifier,
        )
}

/**
 * Moves focus to the first focusable element when a screen first appears.
 *
 * Compose Desktop only delivers key events to the focused node, so a screen entered without a
 * focus target ignores arrow keys until something is focused by pointer. Seeding focus on entry
 * makes every screen keyboard reachable immediately. No-op when the user already focused
 * something, so it never steals an existing selection.
 */
fun Modifier.nuvioAutoFocusFirst(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        if (isDesktop) {
            focusManager.clearFocus()
            // Screens that open with a hero or a header should still start on content, so prefer
            // the first shelf item over whatever happens to be focusable first in the tree.
            repeat(6) {
                if (FirstPosterEntry.request()) return@LaunchedEffect
                withFrameNanos { }
            }
            focusManager.moveFocus(FocusDirection.Next)
        }
    }
    this
}

/**
 * Whether any focus target inside the tracked subtree currently holds focus.
 *
 * Compose offers no "is anything focused" query, but the desktop arrow dispatcher needs one to tell
 * a screen that has not been seeded yet (nothing focused) from a direction that simply has no
 * target (focused item at the edge of a row). Wrapping the app root with [nuvioTrackFocusPresence]
 * keeps the flag current.
 */
internal object FocusPresence {
    @Volatile
    var hasAnyFocus: Boolean = false
}

fun Modifier.nuvioTrackFocusPresence(): Modifier =
    onFocusChanged { FocusPresence.hasAnyFocus = it.hasFocus }

/**
 * Reports whether keyboard focus currently sits inside the desktop navigation rail (sidebar).
 *
 * The rail sits beside the content, but its items do not overlap the poster grid, so Compose's
 * geometric focus search finds no target to the right and the focus would be stuck in the rail.
 * The arrow dispatcher uses this flag to hand focus over to the content area explicitly.
 */
internal object NavRailFocus {
    @Volatile
    var hasFocus: Boolean = false
}

fun Modifier.nuvioTrackNavRailFocus(): Modifier =
    onFocusChanged { NavRailFocus.hasFocus = it.hasFocus }

/** Same as [NavRailFocus] for the floating top navigation bar: Down must enter the content. */
internal object TopBarFocus {
    @Volatile
    var hasFocus: Boolean = false
}

fun Modifier.nuvioTrackTopBarFocus(): Modifier =
    onFocusChanged { TopBarFocus.hasFocus = it.hasFocus }

/**
 * Entry point into the content area, used when focus leaves the navigation rail or the top bar.
 *
 * The content is a dynamic tree, so instead of guessing which item is first on the current screen,
 * the screen root becomes focusable: focusing it hands focus to its first focusable child on the
 * next frame. That keeps the handoff correct on every screen (hero, shelves, settings, details).
 *
 * Apply it to the root of the *selected* screen. Tab hosts keep previously visited tabs composed
 * but unplaced, and forwarding into an unplaced pane leaves focus stranded on the container.
 */
internal object ContentEntry {
    val requester = FocusRequester()

    @Volatile
    var attached: Boolean = false

    /** Returns true when the handoff was actually requested. */
    fun request(): Boolean {
        if (!attached) return false
        return runCatching { requester.requestFocus() }.isSuccess
    }
}

fun Modifier.nuvioContentEntry(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    var entryFocused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        ContentEntry.attached = true
        onDispose { ContentEntry.attached = false }
    }
    // Focusing the container is a request for "the first item of this screen", not a destination
    // the user should ever land on, so immediately delegate to the first focusable child.
    LaunchedEffect(entryFocused) {
        if (entryFocused && !FirstPosterEntry.request()) {
            focusManager.moveFocus(FocusDirection.Next)
        }
    }
    this
        .focusRequester(ContentEntry.requester)
        .onFocusChanged { entryFocused = it.isFocused }
        .focusable()
}

/**
 * The first item of each shelf row, used as the keyboard entry point of a screen.
 *
 * Home opens with a hero, and the first focusable in the tree is not the poster grid. Rows register
 * their first item here, and the topmost registered item of the active screen wins, so a screen
 * starts on content instead of chrome, a hero, or a section header.
 */
internal object FirstPosterEntry {
    private class Candidate(val requester: FocusRequester) {
        var top: Float = Float.MAX_VALUE
        var left: Float = Float.MAX_VALUE
        var placed: Boolean = false
    }

    private val candidates = mutableMapOf<Any, Candidate>()

    fun register(key: Any, requester: FocusRequester) {
        candidates[key] = Candidate(requester)
    }

    fun unregister(key: Any) {
        candidates.remove(key)
    }

    fun updateBounds(key: Any, top: Float, left: Float) {
        candidates[key]?.let {
            it.top = top
            it.left = left
            it.placed = true
        }
    }

    fun request(): Boolean {
        val target = candidates.values
            .filter { it.placed && it.top >= 0f }
            .minWithOrNull(compareBy({ it.top }, { it.left }))
            ?: return false
        return runCatching { target.requester.requestFocus() }.isSuccess
    }
}

/**
 * Marks an item as "first item of a shelf row": it can be the focus entry point of the screen and
 * forwards to its own content as soon as it is focused, so the focus ring lands on the card itself.
 */
fun Modifier.nuvioFirstPosterCandidate(): Modifier = composed {
    val key = remember { Any() }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val screenActive = LocalScreenActive.current
    var focused by remember { mutableStateOf(false) }

    DisposableEffect(key, screenActive) {
        if (screenActive) FirstPosterEntry.register(key, requester)
        onDispose { FirstPosterEntry.unregister(key) }
    }
    LaunchedEffect(focused) {
        if (focused) focusManager.moveFocus(FocusDirection.Next)
    }
    this
        .focusRequester(requester)
        .onFocusChanged { focused = it.isFocused }
        .onGloballyPositioned { coordinates ->
            if (screenActive) {
                val bounds = coordinates.boundsInRoot()
                FirstPosterEntry.updateBounds(key, bounds.top, bounds.left)
            }
        }
        .focusable()
}

/** Focus target of the selected item in the desktop navigation rail, used by Left from content. */
internal object RailEntry {
    private var requester: FocusRequester? = null

    fun register(value: FocusRequester) {
        requester = value
    }

    fun unregister(value: FocusRequester) {
        if (requester === value) requester = null
    }

    fun request(): Boolean {
        val target = requester ?: return false
        return runCatching { target.requestFocus() }.isSuccess
    }
}
