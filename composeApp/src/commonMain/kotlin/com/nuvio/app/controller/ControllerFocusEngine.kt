/*
 * Ported from NuvioDeck (https://github.com/kllakm/NuvioDeck), GPLv3.
 * Spatial focus engine and controller hint UI for 10-foot navigation.
 */
package com.nuvio.app.controller

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.app.controller.audio.ControllerSoundPlayer
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ControllerFocusZone {
    Navigation,
    Content,
    Overlay,
}

data class ControllerFocusNodeSnapshot(
    val id: String,
    val zone: ControllerFocusZone,
    val groupKey: String?,
    val selectLabel: String,
    val secondaryActionLabel: String?,
    val tertiaryActionLabel: String?,
    val hasSecondaryAction: Boolean,
    val hasTertiaryAction: Boolean,
    val isTextInput: Boolean = false,
)

internal class ControllerFocusNode(
    val id: String,
    var zone: ControllerFocusZone,
    var groupKey: String?,
    var boundsInRoot: Rect = Rect.Zero,
    var unclippedBoundsInRoot: Rect = Rect.Zero,
    var enabled: Boolean = true,
    var isDefaultFocus: Boolean = false,
    var isTextInput: Boolean = false,
    var selectLabel: String = "Select",
    var secondaryActionLabel: String? = null,
    var tertiaryActionLabel: String? = null,
    var onSelect: () -> Unit = {},
    var onSecondaryAction: (() -> Unit)? = null,
    var onTertiaryAction: (() -> Unit)? = null,
    var onFocused: (() -> Unit)? = null,
    var onFocusedWithOption: ((allowVerticalScroll: Boolean) -> Unit)? = null,
    var onMoveUp: (() -> Boolean)? = null,
    var onMoveDown: (() -> Boolean)? = null,
    var onMoveLeft: (() -> Boolean)? = null,
    var onMoveRight: (() -> Boolean)? = null,
    var rowLazyListState: LazyListState? = null,
    var rowItemIndex: Int = -1,
    var rowTotalCount: Int = 0,
    var coroutineScope: CoroutineScope? = null,
) {
    val isVisibleAndValid: Boolean
        get() = enabled && (
            (boundsInRoot.height > 2f && (
                boundsInRoot.width > 2f ||
                (rowLazyListState != null && unclippedBoundsInRoot.width > 2f && boundsInRoot.width >= 0f)
            )) ||
            (zone == ControllerFocusZone.Navigation && unclippedBoundsInRoot.height > 2f && unclippedBoundsInRoot.width > 2f)
        )

    fun toSnapshot(): ControllerFocusNodeSnapshot = ControllerFocusNodeSnapshot(
        id = id,
        zone = zone,
        groupKey = groupKey,
        selectLabel = selectLabel,
        secondaryActionLabel = secondaryActionLabel,
        tertiaryActionLabel = tertiaryActionLabel,
        hasSecondaryAction = onSecondaryAction != null,
        hasTertiaryAction = onTertiaryAction != null,
        isTextInput = isTextInput,
    )
}

data class ControllerGlobalHandlers(
    val onBack: (() -> Boolean)? = null,
    val onBumperLeft: (() -> Boolean)? = null,
    val onBumperRight: (() -> Boolean)? = null,
    val onTriggerLeft: (() -> Boolean)? = null,
    val onTriggerRight: (() -> Boolean)? = null,
    val onActionX: (() -> Boolean)? = null,
    val onActionY: (() -> Boolean)? = null,
    val onViewButton: (() -> Boolean)? = null,
    val onMenuButton: (() -> Boolean)? = null,
    val onEnterNavigationZone: (() -> Unit)? = null,
    val onExitNavigationZone: (() -> Unit)? = null,
)

val LocalControllerVerticalListState = compositionLocalOf<LazyListState?> { null }
val LocalControllerVerticalScrollState = compositionLocalOf<ScrollState?> { null }
val LocalControllerRowLazyListState = compositionLocalOf<LazyListState?> { null }
val LocalControllerGroupKey = compositionLocalOf<String?> { null }
val LocalControllerFocusZone = compositionLocalOf { ControllerFocusZone.Content }

@Composable
fun ProvideControllerRowLazyListState(
    state: LazyListState,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalControllerRowLazyListState provides state,
        content = content,
    )
}

object ControllerFocusEngine {
    private val nodes = LinkedHashMap<String, ControllerFocusNode>()

    private val _focusedNodeId = MutableStateFlow<String?>(null)
    val focusedNodeId: StateFlow<String?> = _focusedNodeId.asStateFlow()

    private val _focusedNodeSnapshot = MutableStateFlow<ControllerFocusNodeSnapshot?>(null)
    val focusedNodeSnapshot: StateFlow<ControllerFocusNodeSnapshot?> = _focusedNodeSnapshot.asStateFlow()

    private val _isNavigationZoneExpanded = MutableStateFlow(false)
    val isNavigationZoneExpanded: StateFlow<Boolean> = _isNavigationZoneExpanded.asStateFlow()

    private val _isTopBarVisibleByScroll = MutableStateFlow(true)
    val isTopBarVisibleByScroll: StateFlow<Boolean> = _isTopBarVisibleByScroll.asStateFlow()

    var isHomeTabActive: Boolean = true

    /**
     * True on routes that are not instrumented with [controllerFocusable] (details, streams, player,
     * settings leaves). Keyboard and D-pad navigation is then handled by the Compose focus bridge in
     * PlatformKeyboardNavigationHost instead of the spatial engine, which would otherwise move its
     * ring across off-screen poster nodes.
     */
    var deferNavigationToCompose: Boolean = false

    private val _routeGeneration = MutableStateFlow(0)
    /** Increments on every route/tab change; used to re-seed Compose focus for the new screen. */
    val routeGeneration: StateFlow<Int> = _routeGeneration.asStateFlow()

    fun setTopBarVisibleByScroll(visible: Boolean) {
        _isTopBarVisibleByScroll.value = visible
    }

    private val _hasInteractiveContent = MutableStateFlow(false)
    val hasInteractiveContent: StateFlow<Boolean> = _hasInteractiveContent.asStateFlow()

    private fun updateInteractiveContentStatus() {
        _hasInteractiveContent.value = nodes.values.any { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
    }

    private var lastFocusedByZone = mutableMapOf<ControllerFocusZone, String>()
    private var lastFocusedByGroup = mutableMapOf<String, String>()

    data class ControllerScrollableEntry(
        val lazyListState: LazyListState? = null,
        val scrollState: ScrollState? = null,
        val lazyGridState: LazyGridState? = null,
        val scope: CoroutineScope,
        /** Viewport bounds in root coordinates, tracked by [RegisterControllerVerticalScroll]. */
        var viewportTopInRoot: Float = Float.NaN,
        var viewportHeight: Float = 0f,
    )

    private val registeredScrollables = mutableListOf<ControllerScrollableEntry>()
    private var activeCoroutineScope: CoroutineScope? = null
    private var activeVerticalScrollJob: Job? = null
    private var activeHorizontalScrollJob: Job? = null
    private val SnappyEaseOut = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)
    private var manualSidebarOpen = false
    private var lastNavigationDirection: ControllerDirection? = null

    private data class RouteFocusMemory(
        val focusedNodeId: String?,
        val lastFocusedByZone: Map<ControllerFocusZone, String>,
        val lastFocusedByGroup: Map<String, String>,
    )

    private val routeFocusHistory = mutableMapOf<String, RouteFocusMemory>()
    private var pendingRestoredNodeId: String? = null
    private var pendingRestoreFallbackJob: Job? = null
    private var isInternalCleanupFocus: Boolean = false

    private val handlerStack = mutableListOf<Pair<String, ControllerGlobalHandlers>>()
    private var customPlayerEventHandler: ((ControllerEvent) -> Boolean)? = null

    fun setPlayerEventHandler(handler: ((ControllerEvent) -> Boolean)?) {
        customPlayerEventHandler = handler
    }

    fun registerGlobalHandlers(key: String, handlers: ControllerGlobalHandlers) {
        handlerStack.removeAll { it.first == key }
        handlerStack.add(key to handlers)
    }

    fun unregisterGlobalHandlers(key: String) {
        handlerStack.removeAll { it.first == key }
    }

    fun registerVerticalScrollable(
        lazyListState: LazyListState? = null,
        scrollState: ScrollState? = null,
        lazyGridState: LazyGridState? = null,
        scope: CoroutineScope? = null,
    ) {
        if (scope != null) activeCoroutineScope = scope
        if (scope == null) return
        registeredScrollables.removeAll {
            (lazyListState != null && it.lazyListState === lazyListState) ||
            (scrollState != null && it.scrollState === scrollState) ||
            (lazyGridState != null && it.lazyGridState === lazyGridState)
        }
        registeredScrollables.add(ControllerScrollableEntry(lazyListState, scrollState, lazyGridState, scope))
    }

    fun unregisterVerticalScrollable(
        lazyListState: LazyListState? = null,
        scrollState: ScrollState? = null,
        lazyGridState: LazyGridState? = null,
    ) {
        registeredScrollables.removeAll {
            (lazyListState != null && it.lazyListState === lazyListState) ||
            (scrollState != null && it.scrollState === scrollState) ||
            (lazyGridState != null && it.lazyGridState === lazyGridState)
        }
    }

    /** Records the on-screen bounds of a registered scroll container. */
    internal fun updateScrollableViewport(
        lazyListState: LazyListState? = null,
        scrollState: ScrollState? = null,
        lazyGridState: LazyGridState? = null,
        topInRoot: Float,
        height: Float,
    ) {
        registeredScrollables.firstOrNull {
            (lazyListState != null && it.lazyListState === lazyListState) ||
            (scrollState != null && it.scrollState === scrollState) ||
            (lazyGridState != null && it.lazyGridState === lazyGridState)
        }?.let { entry ->
            entry.viewportTopInRoot = topInRoot
            entry.viewportHeight = height
        }
    }

    internal fun getActiveScrollable(): ControllerScrollableEntry? {        val valid = registeredScrollables.filter { entry ->
            val l = entry.lazyListState
            val s = entry.scrollState
            val g = entry.lazyGridState
            (l != null && l.layoutInfo.visibleItemsInfo.isNotEmpty()) ||
            (g != null && g.layoutInfo.visibleItemsInfo.isNotEmpty()) ||
            (s != null && s.maxValue > 0) ||
            (l != null) || (g != null)
        }
        return valid.lastOrNull { entry ->
            entry.lazyListState?.layoutInfo?.visibleItemsInfo?.isNotEmpty() == true ||
            entry.lazyGridState?.layoutInfo?.visibleItemsInfo?.isNotEmpty() == true
        } ?: valid.lastOrNull() ?: registeredScrollables.lastOrNull()
    }

    fun setNavigationZoneExpanded(expanded: Boolean) {
        _isNavigationZoneExpanded.value = expanded
    }

    private var activeRouteKey: String? = null
    private var hasUserNavigatedOnCurrentRoute: Boolean = false

    private fun saveCurrentRouteFocusMemory(overrideNodeId: String? = null) {
        val routeKey = activeRouteKey ?: return
        val targetId = overrideNodeId ?: _focusedNodeId.value ?: lastFocusedByZone[ControllerFocusZone.Content]
        if (targetId != null) {
            routeFocusHistory[routeKey] = RouteFocusMemory(
                focusedNodeId = targetId,
                lastFocusedByZone = HashMap(lastFocusedByZone),
                lastFocusedByGroup = HashMap(lastFocusedByGroup),
            )
        }
    }

    fun onRouteOrTabChanged(routeKey: Any? = null) {
        val newRouteStr = routeKey?.toString()
        val routeChanged = activeRouteKey != null && activeRouteKey != newRouteStr
        if (routeChanged) {
            saveCurrentRouteFocusMemory()
            _routeGeneration.value = _routeGeneration.value + 1
        }
        activeRouteKey = newRouteStr

        // Purge nodes whose enabled is false or whose bounds are empty
        val invalidIds = nodes.entries.filter { !it.value.isVisibleAndValid }.map { it.key }
        invalidIds.forEach { nodes.remove(it) }

        if (routeChanged) {
            pendingRestoreFallbackJob?.cancel()
            val savedMemory = newRouteStr?.let { routeFocusHistory[it] }
            if (savedMemory != null && savedMemory.focusedNodeId != null) {
                lastFocusedByZone.clear()
                lastFocusedByZone.putAll(savedMemory.lastFocusedByZone)
                lastFocusedByGroup.clear()
                lastFocusedByGroup.putAll(savedMemory.lastFocusedByGroup)
                manualSidebarOpen = false
                hasUserNavigatedOnCurrentRoute = true
                _isNavigationZoneExpanded.value = false

                val targetId = savedMemory.focusedNodeId
                val existingNode = nodes[targetId]?.takeIf { it.isVisibleAndValid }
                if (existingNode != null) {
                    pendingRestoredNodeId = null
                    focusNodeInternal(
                        existingNode,
                        triggerScroll = true,
                        suppressVerticalScroll = true,
                        suppressHorizontalScroll = false,
                    )
                    updateInteractiveContentStatus()
                    return
                } else {
                    _focusedNodeId.value = null
                    _focusedNodeSnapshot.value = null
                    pendingRestoredNodeId = targetId
                    val scope = activeCoroutineScope ?: nodes.values.firstNotNullOfOrNull { it.coroutineScope }
                    pendingRestoreFallbackJob = scope?.launch {
                        delay(240)
                        if (pendingRestoredNodeId == targetId) {
                            val lateNode = nodes[targetId]?.takeIf { it.isVisibleAndValid }
                            pendingRestoredNodeId = null
                            if (lateNode != null) {
                                focusNodeInternal(
                                    lateNode,
                                    triggerScroll = true,
                                    suppressVerticalScroll = true,
                                    suppressHorizontalScroll = false,
                                )
                            } else {
                                ensureValidFocus()
                            }
                        }
                    }
                    updateInteractiveContentStatus()
                    return
                }
            } else {
                pendingRestoredNodeId = null
                _focusedNodeId.value = null
                _focusedNodeSnapshot.value = null
                lastFocusedByZone.clear()
                lastFocusedByGroup.clear()
                manualSidebarOpen = false
                hasUserNavigatedOnCurrentRoute = false
                _isNavigationZoneExpanded.value = false
            }
        } else {
            val current = _focusedNodeId.value?.let { nodes[it] }
            if (current == null || !current.isVisibleAndValid) {
                _focusedNodeId.value = null
                _focusedNodeSnapshot.value = null
            }
        }
        updateInteractiveContentStatus()
        ensureValidFocus()
    }

    internal fun upsertNode(node: ControllerFocusNode) {
        nodes[node.id] = node
        updateInteractiveContentStatus()
        val currentId = _focusedNodeId.value
        val activeOverlayNodes = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
        if (activeOverlayNodes.isNotEmpty()) {
            val currentNode = currentId?.let { nodes[it] }
            if (currentNode == null || currentNode.zone != ControllerFocusZone.Overlay || !currentNode.isVisibleAndValid) {
                val target = activeOverlayNodes.firstOrNull { it.isDefaultFocus }
                    ?: activeOverlayNodes.minWithOrNull(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = false)
                }
            } else if (currentNode.id == node.id) {
                _focusedNodeSnapshot.value = node.toSnapshot()
            }
            return
        }

        val pendingId = pendingRestoredNodeId
        if (pendingId != null) {
            if (node.id == pendingId && node.isVisibleAndValid) {
                pendingRestoreFallbackJob?.cancel()
                pendingRestoredNodeId = null
                _isNavigationZoneExpanded.value = false
                focusNodeInternal(
                    node,
                    triggerScroll = true,
                    suppressVerticalScroll = true,
                    suppressHorizontalScroll = false,
                )
                return
            }
        }

        if (currentId == node.id) {
            _focusedNodeSnapshot.value = node.toSnapshot()
            return
        }

        val currentNode = currentId?.let { nodes[it] }
        if (node.zone == ControllerFocusZone.Content && node.isVisibleAndValid) {
            val shouldPromoteDefaultNode = node.isDefaultFocus &&
                !hasUserNavigatedOnCurrentRoute &&
                currentNode?.isDefaultFocus != true
            if (shouldPromoteDefaultNode || currentNode == null || !currentNode.isVisibleAndValid || (currentNode.zone == ControllerFocusZone.Navigation && !manualSidebarOpen)) {
                if (node.isDefaultFocus || currentId == null || currentNode?.zone == ControllerFocusZone.Navigation) {
                    val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                    val rememberedId = lastFocusedByZone[ControllerFocusZone.Content]
                    val rememberedNode = rememberedId?.let { id -> contentNodes.firstOrNull { it.id == id } }
                    val settingsSidebarTopNode = contentNodes
                        .filter { it.groupKey == "settings_sidebar" }
                        .minByOrNull { it.boundsInRoot.top }
                    val target = if (hasUserNavigatedOnCurrentRoute && rememberedNode != null) {
                        rememberedNode
                    } else {
                        // Several rows may mark their first card as the default entry point. Pick the
                        // topmost one (then leftmost) so the initial selection is the first card of
                        // the first shelf, not whichever row happened to compose first.
                        contentNodes.filter { it.isDefaultFocus }
                            .minWithOrNull(
                                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                                    .thenBy { it.boundsInRoot.left },
                            )
                            ?: rememberedNode
                            ?: settingsSidebarTopNode
                            ?: contentNodes.minWithOrNull(
                                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                                    .thenBy { it.boundsInRoot.left },
                            )
                    }
                    if (target != null) {
                        _isNavigationZoneExpanded.value = false
                        focusNodeInternal(target, triggerScroll = false)
                        return
                    }
                }
            }
        }
    }

    internal fun removeNode(id: String) {
        val removed = nodes.remove(id)
        if (_focusedNodeId.value == id) {
            _focusedNodeId.value = null
            _focusedNodeSnapshot.value = null
            isInternalCleanupFocus = true
            try {
                // If an overlay just closed, restore Content focus unless Navigation focus is actively selected
                if (removed?.zone == ControllerFocusZone.Overlay) {
                    val remainingOverlay = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
                    if (remainingOverlay.isNotEmpty()) {
                        focusNodeInternal(remainingOverlay.first(), triggerScroll = false)
                        return
                    }
                    val currentNav = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }
                    if (currentNav?.zone == ControllerFocusZone.Navigation) {
                        return
                    }
                    val restoredContentId = lastFocusedByZone[ControllerFocusZone.Content]
                    val restoredContent = restoredContentId?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }
                    if (restoredContent != null) {
                        focusNodeInternal(restoredContent, triggerScroll = false)
                        return
                    }
                }
                if (pendingRestoredNodeId == null) {
                    ensureValidFocus(preferBounds = removed?.boundsInRoot, preferZone = removed?.zone)
                }
            } finally {
                isInternalCleanupFocus = false
            }
        }
        updateInteractiveContentStatus()
    }

    fun requestFocus(id: String, triggerScroll: Boolean = true): Boolean {
        val node = nodes[id]?.takeIf { it.isVisibleAndValid } ?: return false
        hasUserNavigatedOnCurrentRoute = true
        focusNodeInternal(node, triggerScroll = triggerScroll)
        return true
    }

    fun clearFocusInZone(zone: ControllerFocusZone) {
        val current = _focusedNodeId.value?.let { nodes[it] }
        if (current?.zone == zone) {
            _focusedNodeId.value = null
            _focusedNodeSnapshot.value = null
        }
    }

    /** Drops the spatial selection, e.g. when a Compose-driven gate (profile picker) takes over. */
    fun clearFocus() {
        _focusedNodeId.value = null
        _focusedNodeSnapshot.value = null
    }

    fun ensureValidFocus(
        preferBounds: Rect? = null,
        preferZone: ControllerFocusZone? = null,
    ): Boolean {
        val overlayNodes = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
        if (overlayNodes.isNotEmpty()) {
            val current = _focusedNodeId.value?.let { nodes[it] }
            if (current != null && current.zone == ControllerFocusZone.Overlay && current.isVisibleAndValid) {
                return true
            }
            val target = overlayNodes.firstOrNull { it.isDefaultFocus }
                ?: overlayNodes.minWithOrNull(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            if (target != null) {
                focusNodeInternal(target, triggerScroll = false)
                return true
            }
        }

        if (pendingRestoredNodeId != null) {
            val pendingNode = nodes[pendingRestoredNodeId]?.takeIf { it.isVisibleAndValid }
            if (pendingNode != null) {
                pendingRestoreFallbackJob?.cancel()
                pendingRestoredNodeId = null
                focusNodeInternal(
                    pendingNode,
                    triggerScroll = true,
                    suppressVerticalScroll = true,
                    suppressHorizontalScroll = false,
                )
                return true
            }
            return false
        }

        val current = _focusedNodeId.value?.let { nodes[it] }
        if (current != null && current.isVisibleAndValid) {
            return true
        }

        val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
        if (contentNodes.isNotEmpty()) {
            val routeSavedId = activeRouteKey?.let { routeFocusHistory[it]?.focusedNodeId }
            val rememberedId = routeSavedId ?: lastFocusedByZone[ControllerFocusZone.Content]
            val remembered = rememberedId?.let { id -> contentNodes.firstOrNull { it.id == id } }
            if (remembered != null) {
                focusNodeInternal(remembered, triggerScroll = false)
                return true
            }
            if (preferBounds != null && preferZone == ControllerFocusZone.Content) {
                val nearest = contentNodes.minByOrNull {
                    hypot(
                        (it.boundsInRoot.center.x - preferBounds.center.x).toDouble(),
                        (it.boundsInRoot.center.y - preferBounds.center.y).toDouble(),
                    )
                }
                if (nearest != null) {
                    focusNodeInternal(nearest, triggerScroll = false)
                    return true
                }
            }
            val defaultNode = contentNodes.firstOrNull { it.isDefaultFocus }
            val settingsSidebarTopNode = contentNodes
                .filter { it.groupKey == "settings_sidebar" }
                .minByOrNull { it.boundsInRoot.top }
            val topLeftNode = contentNodes.minWithOrNull(
                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                    .thenBy { it.boundsInRoot.left },
            )
            val chosen = defaultNode ?: remembered ?: settingsSidebarTopNode ?: topLeftNode
            if (chosen != null) {
                focusNodeInternal(chosen, triggerScroll = false)
                return true
            }
        }

        // Only fall back to the navigation rail when the user explicitly entered it. Screens that
        // are not instrumented with controllerFocusable must keep using Compose's directional focus
        // search instead of having their arrow keys silently consumed by the sidebar.
        if (manualSidebarOpen) {
            val navNodes = nodes.values.filter { it.zone == ControllerFocusZone.Navigation && it.isVisibleAndValid }
            if (navNodes.isNotEmpty()) {
                val chosen = navNodes.firstOrNull { it.isDefaultFocus } ?: navNodes.first()
                focusNodeInternal(chosen, triggerScroll = false)
                return true
            }
        }
        return false
    }

    fun focusNodeById(id: String, triggerScroll: Boolean = true): Boolean {
        hasUserNavigatedOnCurrentRoute = true
        val node = nodes[id]?.takeIf { it.isVisibleAndValid }
        if (node != null) {
            pendingRestoreFallbackJob?.cancel()
            pendingRestoredNodeId = null
            focusNodeInternal(node, triggerScroll = triggerScroll)
            return true
        }
        pendingRestoredNodeId = id
        return false
    }

    private fun focusNodeInternal(
        node: ControllerFocusNode,
        triggerScroll: Boolean,
        suppressVerticalScroll: Boolean = false,
        suppressHorizontalScroll: Boolean = false,
        direction: ControllerDirection? = null,
    ) {
        val previousId = _focusedNodeId.value
        _focusedNodeId.value = node.id
        _focusedNodeSnapshot.value = node.toSnapshot()
        if (!isInternalCleanupFocus) {
            lastFocusedByZone[node.zone] = node.id
            node.groupKey?.let { group ->
                lastFocusedByGroup[group] = node.id
            }
            if (node.zone == ControllerFocusZone.Content) {
                saveCurrentRouteFocusMemory(node.id)
            }
        }
        if (node.zone == ControllerFocusZone.Navigation) {
            _isNavigationZoneExpanded.value = true
            _isTopBarVisibleByScroll.value = true
            invokeGlobalHandler { it.onEnterNavigationZone?.invoke(); false }
        } else if (node.zone == ControllerFocusZone.Content && _isNavigationZoneExpanded.value) {
            _isNavigationZoneExpanded.value = false
            manualSidebarOpen = false
            invokeGlobalHandler { it.onExitNavigationZone?.invoke(); false }
        }
        if (previousId != null && previousId != node.id) {
            ControllerSoundPlayer.playNavigate()
        }
        if (triggerScroll) {
            node.onFocusedWithOption?.invoke(!suppressVerticalScroll) ?: node.onFocused?.invoke()
            if (!suppressVerticalScroll) {
                scrollVerticalContainerToNode(node, direction)
            }
            if (node.rowLazyListState != null && !suppressHorizontalScroll) {
                val effectiveDirection = direction ?: lastNavigationDirection
                scrollHorizontalContainerToNode(node, effectiveDirection)
            }
        }
    }

    private var isNavigatingLazyRow = false

    fun navigateLazyRowIndex(
        lazyListState: LazyListState,
        targetIndex: Int,
        totalItems: Int,
        isMovingRight: Boolean,
        coroutineScope: CoroutineScope,
        targetFocusId: String,
        edgeMarginPx: Float = 80f,
    ): Boolean {
        if (targetIndex < 0 || targetIndex >= totalItems) return false

        val layoutInfo = lazyListState.layoutInfo
        val viewportStart = layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = layoutInfo.viewportEndOffset.toFloat().takeIf { it > 100f } ?: 1920f
        val leftSafeZone = max(0f, viewportStart + edgeMarginPx)
        val rightSafeZone = (viewportEnd - max(layoutInfo.afterContentPadding.toFloat(), edgeMarginPx)).coerceAtLeast(leftSafeZone + 100f)

        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }

        isNavigatingLazyRow = true
        activeHorizontalScrollJob?.cancel()

        if (targetItem != null) {
            val itemStart = targetItem.offset.toFloat()
            val itemEnd = (targetItem.offset + targetItem.size).toFloat()

            if (isMovingRight) {
                if (itemEnd <= rightSafeZone) {
                    requestFocus(targetFocusId, triggerScroll = false)
                    isNavigatingLazyRow = false
                    return true
                } else {
                    val delta = (itemEnd - rightSafeZone + 24f).coerceAtLeast(0f)
                    requestFocus(targetFocusId, triggerScroll = false)
                    activeHorizontalScrollJob = coroutineScope.launch {
                        try {
                            runCatching {
                                lazyListState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut))
                            }
                            for (attempt in 0..5) {
                                if (requestFocus(targetFocusId, triggerScroll = false)) break
                                delay(30)
                            }
                        } finally {
                            isNavigatingLazyRow = false
                        }
                    }
                    return true
                }
            } else {
                // Moving Left
                if (targetIndex == 0) {
                    requestFocus(targetFocusId, triggerScroll = false)
                    if (lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0) {
                        isNavigatingLazyRow = false
                    } else {
                        activeHorizontalScrollJob = coroutineScope.launch {
                            try {
                                runCatching { lazyListState.animateScrollToItem(0, 0) }
                                for (attempt in 0..5) {
                                    if (requestFocus(targetFocusId, triggerScroll = false)) break
                                    delay(30)
                                }
                            } finally {
                                isNavigatingLazyRow = false
                            }
                        }
                    }
                    return true
                }

                if (itemStart >= leftSafeZone) {
                    requestFocus(targetFocusId, triggerScroll = false)
                    isNavigatingLazyRow = false
                    return true
                } else {
                    val delta = itemStart - leftSafeZone - 24f
                    requestFocus(targetFocusId, triggerScroll = false)
                    activeHorizontalScrollJob = coroutineScope.launch {
                        try {
                            runCatching {
                                lazyListState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut))
                            }
                            for (attempt in 0..5) {
                                if (requestFocus(targetFocusId, triggerScroll = false)) break
                                delay(30)
                            }
                        } finally {
                            isNavigatingLazyRow = false
                        }
                    }
                    return true
                }
            }
        } else {
            // Target item is off-screen (not yet in visibleItemsInfo)
            if (isMovingRight) {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                val avgSize = layoutInfo.visibleItemsInfo.map { it.size }.average().toFloat().takeIf { it > 20f } ?: 220f
                val delta = if (lastVisible != null) {
                    ((lastVisible.offset + lastVisible.size) - rightSafeZone + avgSize + 32f).coerceAtLeast(avgSize)
                } else {
                    avgSize + edgeMarginPx
                }
                activeHorizontalScrollJob = coroutineScope.launch {
                    try {
                        runCatching {
                            lazyListState.animateScrollBy(delta, tween(180, easing = SnappyEaseOut))
                        }
                        for (attempt in 0..6) {
                            if (requestFocus(targetFocusId, triggerScroll = true)) break
                            delay(30)
                        }
                    } finally {
                        isNavigatingLazyRow = false
                    }
                }
                return true
            } else {
                // Moving Left off-screen
                if (targetIndex == 0) {
                    activeHorizontalScrollJob = coroutineScope.launch {
                        try {
                            runCatching { lazyListState.animateScrollToItem(0, 0) }
                            for (attempt in 0..6) {
                                if (requestFocus(targetFocusId, triggerScroll = false)) break
                                delay(30)
                            }
                        } finally {
                            isNavigatingLazyRow = false
                        }
                    }
                } else {
                    val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
                    val avgSize = layoutInfo.visibleItemsInfo.map { it.size }.average().toFloat().takeIf { it > 20f } ?: 220f
                    val delta = if (firstVisible != null) {
                        (firstVisible.offset - leftSafeZone - avgSize - 32f).coerceAtMost(-avgSize)
                    } else {
                        -(avgSize + edgeMarginPx)
                    }
                    activeHorizontalScrollJob = coroutineScope.launch {
                        try {
                            runCatching {
                                lazyListState.animateScrollBy(delta, tween(180, easing = SnappyEaseOut))
                            }
                            for (attempt in 0..6) {
                                if (requestFocus(targetFocusId, triggerScroll = true)) break
                                delay(30)
                            }
                        } finally {
                            isNavigatingLazyRow = false
                        }
                    }
                }
                return true
            }
        }
    }

    private fun scrollHorizontalContainerToNode(
        node: ControllerFocusNode,
        direction: ControllerDirection? = null,
    ) {
        if (isNavigatingLazyRow) return
        val rowState = node.rowLazyListState ?: return
        val scope = node.coroutineScope ?: activeCoroutineScope ?: return

        val layoutInfo = rowState.layoutInfo
        val viewportWidth = layoutInfo.viewportSize.width.toFloat().takeIf { it > 100f }
            ?: (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().takeIf { it > 100f }
            ?: 1920f

        val unclipped = if (node.unclippedBoundsInRoot.width > 2f) node.unclippedBoundsInRoot else node.boundsInRoot
        val cardLeft = unclipped.left
        val cardRight = unclipped.right
        if (cardRight <= 0f && cardLeft <= 0f) return

        val safeLeft = max(layoutInfo.beforeContentPadding.toFloat(), 64f)
        val safeRight = (viewportWidth - max(layoutInfo.afterContentPadding.toFloat(), 64f)).coerceAtLeast(safeLeft + 120f)

        // 1. Vertical navigation (Up / Down) or focus restoration:
        // Keep horizontal offset stationary if card is already fully inside safe bounds; otherwise reveal it completely.
        if (direction == ControllerDirection.Up || direction == ControllerDirection.Down || direction == null) {
            if (cardLeft >= safeLeft && cardRight <= safeRight) {
                return
            }
            if (cardRight > safeRight && rowState.canScrollForward) {
                val delta = (cardRight - safeRight + 24f).coerceAtLeast(0f)
                if (delta > 2f) {
                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut)) }
                    }
                }
            } else if (cardLeft < safeLeft && rowState.canScrollBackward) {
                if (node.rowItemIndex == 0) {
                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollToItem(0, 0) }
                    }
                } else {
                    val delta = (cardLeft - safeLeft - 24f).coerceAtMost(0f)
                    if (delta < -2f) {
                        activeHorizontalScrollJob?.cancel()
                        activeHorizontalScrollJob = scope.launch {
                            runCatching { rowState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut)) }
                        }
                    }
                }
            }
            return
        }

        // 2. Horizontal Left navigation:
        // Bring the target card 100% into view on a single Left press.
        if (direction == ControllerDirection.Left) {
            val rowNodes = nodes.values.filter {
                it.rowLazyListState === rowState && it.isVisibleAndValid && !it.id.startsWith("view_all_pill_")
            }.sortedBy {
                val b = if (it.unclippedBoundsInRoot.width > 2f) it.unclippedBoundsInRoot else it.boundsInRoot
                b.left
            }
            val isLeftmostNode = rowNodes.firstOrNull()?.id == node.id
            val isFirstItemInRow = node.rowItemIndex == 0 ||
                (isLeftmostNode && rowState.firstVisibleItemIndex == 0)

            if (isFirstItemInRow) {
                if (rowState.canScrollBackward || rowState.firstVisibleItemScrollOffset > 0) {
                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollToItem(0, 0) }
                    }
                }
                return
            }

            if (cardLeft < safeLeft && rowState.canScrollBackward) {
                val delta = (cardLeft - safeLeft - 24f).coerceAtMost(0f)
                if (delta < -2f) {
                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut)) }
                    }
                }
            }
            return
        }

        // 3. Horizontal Right navigation:
        // Calculate using full UNCLIPPED right edge so any partially off-screen poster snaps 100% into view on the first press.
        if (cardRight > safeRight && rowState.canScrollForward) {
            val delta = (cardRight - safeRight + 24f).coerceAtLeast(0f)
            if (delta > 2f) {
                activeHorizontalScrollJob?.cancel()
                activeHorizontalScrollJob = scope.launch {
                    runCatching { rowState.animateScrollBy(delta, tween(170, easing = SnappyEaseOut)) }
                }
            }
        }
    }

    private data class FocusContentRow(
        val top: Float,
        val bottom: Float,
        val centerY: Float,
        val nodes: List<ControllerFocusNode>,
        val groupKey: String?,
        val rowLazyListState: LazyListState?,
    )

    private fun getComposedContentRows(): List<FocusContentRow> {
        val contentNodes = nodes.values
            .filter {
                it.zone == ControllerFocusZone.Content &&
                    it.isVisibleAndValid &&
                    !it.id.startsWith("view_all_pill_") &&
                    it.groupKey != "settings_sidebar" &&
                    it.groupKey != "settings_content"
            }
            .sortedBy { it.boundsInRoot.center.y }
        if (contentNodes.isEmpty()) return emptyList()

        val rows = mutableListOf<MutableList<ControllerFocusNode>>()
        for (node in contentNodes) {
            val matchingRow = rows.firstOrNull { cluster ->
                val sample = cluster.first()
                if (node.rowLazyListState != null || sample.rowLazyListState != null) {
                    node.rowLazyListState != null && node.rowLazyListState === sample.rowLazyListState
                } else if (!node.groupKey.isNullOrEmpty() || !sample.groupKey.isNullOrEmpty()) {
                    node.groupKey == sample.groupKey
                } else {
                    val sampleCenterY = cluster.map { it.boundsInRoot.center.y }.average().toFloat()
                    val verticalOverlap = min(node.boundsInRoot.bottom, sample.boundsInRoot.bottom) -
                        max(node.boundsInRoot.top, sample.boundsInRoot.top)
                    val minHeight = min(node.boundsInRoot.height, sample.boundsInRoot.height).coerceAtLeast(1f)
                    val horizontalOverlap = cluster.maxOf { member ->
                        val hOverlap = min(node.boundsInRoot.right, member.boundsInRoot.right) -
                            max(node.boundsInRoot.left, member.boundsInRoot.left)
                        val minW = min(node.boundsInRoot.width, member.boundsInRoot.width).coerceAtLeast(1f)
                        hOverlap / minW
                    }
                    horizontalOverlap < 0.45f && ((verticalOverlap / minHeight) >= 0.35f || abs(node.boundsInRoot.center.y - sampleCenterY) <= 40f)
                }
            }
            if (matchingRow != null) {
                matchingRow.add(node)
            } else {
                rows.add(mutableListOf(node))
            }
        }

        return rows.map { list ->
            val sortedList = list.sortedBy { it.boundsInRoot.left }
            val minTop = sortedList.minOf { it.boundsInRoot.top }
            val maxBottom = sortedList.maxOf { it.boundsInRoot.bottom }
            val avgCenterY = sortedList.map { it.boundsInRoot.center.y }.average().toFloat()
            val sample = sortedList.first()
            FocusContentRow(
                top = minTop,
                bottom = maxBottom,
                centerY = avgCenterY,
                nodes = sortedList,
                groupKey = sample.groupKey,
                rowLazyListState = sample.rowLazyListState,
            )
        }.sortedBy { it.centerY }
    }

    private fun selectBestTargetNodeInRow(
        current: ControllerFocusNode,
        row: FocusContentRow,
    ): ControllerFocusNode? {
        val candidates = row.nodes.filter { !it.id.startsWith("view_all_pill_") }
        if (candidates.isEmpty()) return null

        if (!row.groupKey.isNullOrEmpty()) {
            val rememberedId = lastFocusedByGroup[row.groupKey]
            if (rememberedId != null) {
                val rememberedNode = candidates.firstOrNull { it.id == rememberedId }
                if (rememberedNode != null) return rememberedNode
            }
        }

        if (current.rowItemIndex >= 0) {
            val matchingIndexNode = candidates.firstOrNull { it.rowItemIndex == current.rowItemIndex }
            if (matchingIndexNode != null) return matchingIndexNode
        }

        val currentCenterX = current.boundsInRoot.center.x
        return candidates.minByOrNull { abs(it.boundsInRoot.center.x - currentCenterX) }
    }

    private fun scrollVerticalContainerToNode(
        node: ControllerFocusNode,
        direction: ControllerDirection? = null,
    ) {
        if (node.zone != ControllerFocusZone.Content) return
        val activeScroll = getActiveScrollable()
        val listState = activeScroll?.lazyListState
        val gridState = activeScroll?.lazyGridState
        val scrollState = activeScroll?.scrollState
        val scope = activeScroll?.scope ?: activeCoroutineScope ?: node.coroutineScope ?: return
        val bounds = node.boundsInRoot
        if (bounds.height <= 0f) return

        val isTopOfPageNode = node.groupKey == "home_hero" ||
            node.id.startsWith("home_hero_") ||
            node.id == "search_input_field" ||
            node.groupKey == "recent_searches" ||
            node.groupKey == "discover_filters"
        if (isTopOfPageNode) {
            _isTopBarVisibleByScroll.value = true
            activeVerticalScrollJob?.cancel()
            activeVerticalScrollJob = scope.launch {
                if (listState != null && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
                    runCatching { listState.animateScrollToItem(0, 0) }
                } else if (gridState != null && (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)) {
                    runCatching { gridState.animateScrollToItem(0, 0) }
                } else if (scrollState != null && scrollState.value > 0) {
                    runCatching { scrollState.animateScrollTo(0) }
                }
            }
            return
        }

        if (direction == ControllerDirection.Up) {
            _isTopBarVisibleByScroll.value = true
        } else if (direction == ControllerDirection.Down && isHomeTabActive) {
            _isTopBarVisibleByScroll.value = false
        }

        activeVerticalScrollJob?.cancel()
        activeVerticalScrollJob = scope.launch {
            val vh = listState?.layoutInfo?.viewportSize?.height?.toFloat()
                ?: gridState?.layoutInfo?.viewportSize?.height?.toFloat()
                ?: 900f
            val scaleFactor = (vh / 900f).coerceIn(0.85f, 1.6f)

            val composedRows = getComposedContentRows()
            val targetRow = composedRows.firstOrNull { row -> row.nodes.any { it.id == node.id } }

            val rowCardTop: Float
            val rowCardBottom: Float
            if (targetRow != null) {
                rowCardTop = targetRow.top
                rowCardBottom = targetRow.bottom
            } else if (node.id.startsWith("view_all_pill_")) {
                val rowBelow = composedRows.firstOrNull {
                    it.top >= node.boundsInRoot.bottom - 40f && it.top <= node.boundsInRoot.bottom + 180f
                }
                rowCardTop = rowBelow?.top ?: (node.boundsInRoot.bottom + 15f)
                rowCardBottom = rowBelow?.bottom ?: (rowCardTop + 280f * scaleFactor)
            } else {
                rowCardTop = node.boundsInRoot.top
                rowCardBottom = node.boundsInRoot.bottom
            }

            val isShelfRow = node.rowLazyListState != null || targetRow?.rowLazyListState != null ||
                (!node.groupKey.isNullOrEmpty() && node.groupKey?.startsWith("shelf_") == true) ||
                node.id.startsWith("view_all_pill_")

            val headerClearance = if (isShelfRow) 52f * scaleFactor else 0f

            val rowPill = if (isShelfRow) {
                nodes.values.firstOrNull {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        it.id.startsWith("view_all_pill_") &&
                        it.boundsInRoot.bottom <= rowCardTop + 40f &&
                        it.boundsInRoot.top >= rowCardTop - 200f
                }
            } else null

            val visualTop = if (isShelfRow) {
                minOf(
                    rowPill?.boundsInRoot?.top?.let { it - 12f * scaleFactor } ?: Float.MAX_VALUE,
                    rowCardTop - headerClearance
                )
            } else {
                rowCardTop
            }

            val visualBottom = if (isShelfRow) {
                rowCardBottom + (56f * scaleFactor)
            } else {
                rowCardBottom
            }

            // Visual vertical center of the active row including title header and poster card
            val visualRowCenterY = (visualTop + visualBottom) / 2f

            // Viewport vertical center in root coordinates. The scroll container may start below a
            // header/tab bar, so use its tracked bounds instead of assuming it starts at y = 0.
            val viewportTop = activeScroll?.viewportTopInRoot?.takeIf { it.isFinite() } ?: 0f
            val viewportHeight = activeScroll?.viewportHeight?.takeIf { it > 0f } ?: vh
            val viewportCenterY = viewportTop + viewportHeight * 0.52f

            var delta = visualRowCenterY - viewportCenterY

            // Directional scroll safety:
            // When navigating UP, we must NEVER scroll down (positive delta)
            if (direction == ControllerDirection.Up && delta > 0f) {
                delta = 0f
            }
            // When navigating DOWN, we must NEVER scroll up (negative delta)
            if (direction == ControllerDirection.Down && delta < 0f) {
                delta = 0f
            }

            // If non-shelf vertical content is already fully visible inside viewport safe margins, do not scroll
            val topChromeSafeMargin = viewportTop + 85f * scaleFactor
            val bottomSafeMargin = viewportTop + viewportHeight - (40f * scaleFactor)
            if (!isShelfRow && visualTop >= topChromeSafeMargin && visualBottom <= bottomSafeMargin) {
                delta = 0f
            }

            // When navigating horizontally within the same row, suppress vertical micro-jitter
            if (direction == ControllerDirection.Left || direction == ControllerDirection.Right) {
                if (abs(delta) < 32f) return@launch
            }

            // Boundary clamping at top of container: never overscroll past offset 0
            if (delta < 0f) {
                val maxScrollUp = if (listState?.firstVisibleItemIndex == 0) {
                    listState.firstVisibleItemScrollOffset.toFloat()
                } else if (gridState?.firstVisibleItemIndex == 0) {
                    gridState.firstVisibleItemScrollOffset.toFloat()
                } else if (scrollState != null) {
                    scrollState.value.toFloat()
                } else {
                    Float.MAX_VALUE
                }
                delta = delta.coerceAtLeast(-maxScrollUp)

                if (visualTop >= topChromeSafeMargin && (visualTop - delta) > (topChromeSafeMargin + 80f)) {
                    val clampedDelta = visualTop - topChromeSafeMargin
                    if (clampedDelta > delta) {
                        delta = clampedDelta
                    }
                }
            }

            if (abs(delta) > 4f) {
                val smoothScrollSpec = tween<Float>(
                    durationMillis = 300,
                    easing = FastOutSlowInEasing,
                )
                if (listState != null) {
                    runCatching { listState.animateScrollBy(delta, smoothScrollSpec) }
                } else if (gridState != null) {
                    runCatching { gridState.animateScrollBy(delta, smoothScrollSpec) }
                } else if (scrollState != null) {
                    runCatching { scrollState.animateScrollBy(delta, smoothScrollSpec) }
                }
            }
        }
    }
    fun handleEvent(event: ControllerEvent): Boolean {
        // If native player is active and handles the event directly, delegate first
        val overlayNodes = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
        if (overlayNodes.isEmpty()) {
            val playerHandler = customPlayerEventHandler
            if (playerHandler != null && playerHandler(event)) {
                return true
            }
        }

        // Routes without engine nodes belong to Compose: let the focus bridge move/activate the
        // Compose-focused element instead of dragging the spatial ring over hidden posters.
        if (deferNavigationToCompose) {
            when (event) {
                is ControllerEvent.Navigate -> return false
                is ControllerEvent.ButtonPressed -> if (event.button == XboxButton.A) return false
                else -> Unit
            }
        }

        return when (event) {
            is ControllerEvent.Navigate -> handleNavigate(event.direction)
            is ControllerEvent.ButtonPressed -> handleButtonPressed(event.button)
            is ControllerEvent.ButtonReleased -> false
            is ControllerEvent.AnalogScroll -> handleAnalogScroll(event.dx, event.dy)
        }
    }

    private fun handleAnalogScroll(dx: Float, dy: Float): Boolean {
        val activeScroll = getActiveScrollable()
        val listState = activeScroll?.lazyListState
        val gridState = activeScroll?.lazyGridState
        val scrollState = activeScroll?.scrollState
        val scope = activeScroll?.scope ?: activeCoroutineScope ?: nodes.values.firstNotNullOfOrNull { it.coroutineScope } ?: return false
        if (abs(dy) > 0.05f) {
            _isTopBarVisibleByScroll.value = dy < 0f
            val pixels = dy * 32f
            if (listState != null) {
                scope.launch { runCatching { listState.scrollBy(pixels) } }
                return true
            } else if (gridState != null) {
                scope.launch { runCatching { gridState.scrollBy(pixels) } }
                return true
            } else if (scrollState != null) {
                scope.launch { runCatching { scrollState.scrollBy(pixels) } }
                return true
            }
        }
        if (abs(dx) > 0.1f) {
            val current = _focusedNodeId.value?.let { nodes[it] }
            val rowState = current?.rowLazyListState
            if (rowState != null) {
                val pixels = dx * 24f
                scope.launch { runCatching { rowState.scrollBy(pixels) } }
                return true
            }
        }
        return false
    }

    private fun handleButtonPressed(button: XboxButton): Boolean {
        val current = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }
        return when (button) {
            XboxButton.DPadUp -> handleNavigate(ControllerDirection.Up)
            XboxButton.DPadDown -> handleNavigate(ControllerDirection.Down)
            XboxButton.DPadLeft -> handleNavigate(ControllerDirection.Left)
            XboxButton.DPadRight -> handleNavigate(ControllerDirection.Right)
            XboxButton.A -> {
                ControllerSoundPlayer.playSelect()
                if (current == null) {
                    // Nothing spatially focused: seed the engine when possible, otherwise report the
                    // press as unhandled so the Compose focus bridge can activate the focused button.
                    ensureValidFocus()
                    false
                } else {
                    if (current.zone == ControllerFocusZone.Content) {
                        saveCurrentRouteFocusMemory(current.id)
                    }
                    if (current.isTextInput) {
                        PlatformVirtualKeyboard.show()
                    }
                    current.onSelect()
                    true
                }
            }
            XboxButton.B -> {
                ControllerSoundPlayer.playBack()
                if (PlatformVirtualKeyboard.isKeyboardActive) {
                    PlatformVirtualKeyboard.hide()
                    return true
                }
                PlatformVirtualKeyboard.hide()
                // If currently in Sidebar/Navigation zone, B returns focus to Content
                if (current?.zone == ControllerFocusZone.Navigation) {
                    manualSidebarOpen = false
                    val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                    if (contentNodes.isNotEmpty()) {
                        val remembered = lastFocusedByZone[ControllerFocusZone.Content]?.let { id ->
                            contentNodes.firstOrNull { it.id == id }
                        } ?: contentNodes.first()
                        focusNodeInternal(remembered, triggerScroll = true)
                        return true
                    }
                }
                invokeGlobalHandler { it.onBack?.invoke() == true }
            }
            XboxButton.X -> {
                val secondary = current?.onSecondaryAction
                if (secondary != null) {
                    secondary.invoke()
                    true
                } else {
                    invokeGlobalHandler { it.onActionX?.invoke() == true }
                }
            }
            XboxButton.Y -> {
                // If a visible text input field exists on screen (e.g. search input field),
                // pressing Y immediately focuses it and invokes the virtual keyboard without requiring 'A' press confirmation
                val textInputNode = nodes.values.firstOrNull { it.isTextInput && it.isVisibleAndValid }
                if (textInputNode != null) {
                    ControllerSoundPlayer.playSelect()
                    if (_focusedNodeId.value != textInputNode.id) {
                        focusNodeInternal(textInputNode, triggerScroll = true)
                    }
                    textInputNode.onSelect()
                    true
                } else {
                    val tertiary = current?.onTertiaryAction
                    if (tertiary != null) {
                        tertiary.invoke()
                        true
                    } else {
                        invokeGlobalHandler { it.onActionY?.invoke() == true }
                    }
                }
            }
            XboxButton.LB -> invokeGlobalHandler { it.onBumperLeft?.invoke() == true }
            XboxButton.RB -> invokeGlobalHandler { it.onBumperRight?.invoke() == true }
            XboxButton.LT -> {
                if (invokeGlobalHandler { it.onTriggerLeft?.invoke() == true }) {
                    true
                } else {
                    scrollPage(up = true)
                }
            }
            XboxButton.RT -> {
                if (invokeGlobalHandler { it.onTriggerRight?.invoke() == true }) {
                    true
                } else {
                    scrollPage(up = false)
                }
            }
            XboxButton.View -> {
                if (invokeGlobalHandler { it.onViewButton?.invoke() == true }) {
                    true
                } else {
                    toggleNavigationZoneFocus()
                }
            }
            XboxButton.Menu -> {
                if (invokeGlobalHandler { it.onMenuButton?.invoke() == true }) {
                    true
                } else {
                    toggleNavigationZoneFocus()
                }
            }
            XboxButton.LS, XboxButton.RS -> false
        }
    }

    private fun toggleNavigationZoneFocus(): Boolean {
        val current = _focusedNodeId.value?.let { nodes[it] }
        if (current?.zone == ControllerFocusZone.Navigation) {
            manualSidebarOpen = false
            val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
            val target = lastFocusedByZone[ControllerFocusZone.Content]?.let { id ->
                contentNodes.firstOrNull { it.id == id }
            } ?: contentNodes.firstOrNull()
            if (target != null) {
                focusNodeInternal(target, triggerScroll = true)
                return true
            }
        } else {
            manualSidebarOpen = true
            val navNodes = nodes.values.filter { it.zone == ControllerFocusZone.Navigation && it.isVisibleAndValid }
            val target = lastFocusedByZone[ControllerFocusZone.Navigation]?.let { id ->
                navNodes.firstOrNull { it.id == id }
            } ?: navNodes.firstOrNull { it.isDefaultFocus } ?: navNodes.firstOrNull()
            if (target != null) {
                focusNodeInternal(target, triggerScroll = false)
                return true
            }
        }
        return false
    }

    private fun scrollPage(up: Boolean): Boolean {
        val activeScroll = getActiveScrollable()
        val listState = activeScroll?.lazyListState
        val gridState = activeScroll?.lazyGridState
        val scrollState = activeScroll?.scrollState
        val scope = activeScroll?.scope ?: activeCoroutineScope ?: nodes.values.firstNotNullOfOrNull { it.coroutineScope } ?: return false
        val delta = if (up) -520f else 520f
        if (listState != null) {
            scope.launch {
                runCatching { listState.animateScrollBy(delta, tween(220)) }
                delay(240)
                val visibleContent = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                val target = if (up) {
                    visibleContent.minByOrNull { it.boundsInRoot.top }
                } else {
                    visibleContent.maxByOrNull { it.boundsInRoot.bottom }
                }
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = false)
                }
            }
            return true
        } else if (gridState != null) {
            scope.launch {
                runCatching { gridState.animateScrollBy(delta, tween(220)) }
                delay(240)
                val visibleContent = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                val target = if (up) {
                    visibleContent.minByOrNull { it.boundsInRoot.top }
                } else {
                    visibleContent.maxByOrNull { it.boundsInRoot.bottom }
                }
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = false)
                }
            }
            return true
        } else if (scrollState != null) {
            scope.launch {
                runCatching { scrollState.animateScrollBy(delta, tween(220)) }
            }
            return true
        }
        return false
    }

    private fun invokeGlobalHandler(block: (ControllerGlobalHandlers) -> Boolean): Boolean {
        for (i in handlerStack.indices.reversed()) {
            if (block(handlerStack[i].second)) return true
        }
        return false
    }

    private fun handleContentVerticalNavigation(
        current: ControllerFocusNode,
        direction: ControllerDirection,
    ): Boolean {
        if (direction == ControllerDirection.Up) {
            _isTopBarVisibleByScroll.value = true
        } else if (direction == ControllerDirection.Down && isHomeTabActive) {
            _isTopBarVisibleByScroll.value = false
        }

        // Settings Column Isolation: Up and Down stay strictly within current column
        val isCurrentSettingsSidebar = current.groupKey == "settings_sidebar" || current.id.startsWith("settings_sidebar_")
        val isCurrentSettingsContent = current.groupKey == "settings_content" || (current.id.startsWith("settings_") && !isCurrentSettingsSidebar)

        if (isCurrentSettingsSidebar || isCurrentSettingsContent) {
            val columnNodes = nodes.values.filter {
                it.zone == ControllerFocusZone.Content &&
                    it.isVisibleAndValid &&
                    (if (isCurrentSettingsSidebar) (it.groupKey == "settings_sidebar" || it.id.startsWith("settings_sidebar_"))
                     else (it.groupKey == "settings_content" || (it.id.startsWith("settings_") && !it.id.startsWith("settings_sidebar_")))) &&
                    it.id != current.id
            }
            val target = if (direction == ControllerDirection.Down) {
                columnNodes.filter { it.boundsInRoot.top >= current.boundsInRoot.bottom - 10f || it.boundsInRoot.center.y > current.boundsInRoot.center.y + 10f }
                    .minByOrNull { it.boundsInRoot.top }
            } else {
                columnNodes.filter { it.boundsInRoot.bottom <= current.boundsInRoot.top + 10f || it.boundsInRoot.center.y < current.boundsInRoot.center.y - 10f }
                    .maxByOrNull { it.boundsInRoot.bottom }
            }
            if (target != null) {
                focusNodeInternal(target, triggerScroll = true, direction = direction)
                return true
            }
            return true
        }

        if (direction == ControllerDirection.Up && current.groupKey == "folder_tabs") {
            // Category tabs sit at the very top of a catalog; Up must not scroll the grid below.
            return true
        }

        val composedRows = getComposedContentRows()

        // 1. Strict handling if currently focused on a row's chevron ("See All" arrow):
        if (current.id.startsWith("view_all_pill_")) {
            if (direction == ControllerDirection.Down) {
                // Pressing Down MUST return focus back to the poster cards in that exact same row!
                val sameRowCards = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        !it.id.startsWith("view_all_pill_") &&
                        it.boundsInRoot.top >= current.boundsInRoot.bottom - 10f &&
                        it.boundsInRoot.top <= current.boundsInRoot.bottom + 220f
                }
                val target = sameRowCards.maxByOrNull { it.boundsInRoot.center.x }
                    ?: composedRows.firstOrNull { it.centerY > current.boundsInRoot.center.y }?.nodes?.lastOrNull()
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = true, direction = direction)
                    return true
                }
            } else if (direction == ControllerDirection.Up) {
                // Pressing Up from the chevron moves to a poster card in the row above!
                val rowAbove = composedRows.lastOrNull { it.bottom < current.boundsInRoot.top + 30f }
                if (rowAbove != null) {
                    val target = selectBestTargetNodeInRow(current, rowAbove)
                    if (target != null) {
                        focusNodeInternal(target, triggerScroll = true, direction = direction)
                        return true
                    }
                }
            }
        }

        val rawRowIndex = composedRows.indexOfFirst { row -> row.nodes.any { it.id == current.id } }
        val effectiveRowIndex = if (rawRowIndex != -1) {
            rawRowIndex
        } else {
            val currentCenterY = current.boundsInRoot.center.y
            composedRows.indices.minByOrNull { abs(composedRows[it].centerY - currentCenterY) } ?: 0
        }
        val currentRow = composedRows[effectiveRowIndex]

        // 2. Strict Rule for Row Header Chevrons:
        // A row's chevron can be focused when:
        // (1) The user is currently focused on the very last (rightmost) poster card of that exact same row, AND
        // (2) The user presses Up.
        if (direction == ControllerDirection.Up && !current.id.startsWith("view_all_pill_")) {
            val isFarRightCard = (
                current.id == currentRow.nodes.lastOrNull()?.id ||
                (current.rowItemIndex >= 0 && current.rowTotalCount > 0 && current.rowItemIndex == current.rowTotalCount - 1) ||
                currentRow.nodes.none { it.boundsInRoot.center.x > current.boundsInRoot.center.x + 15f }
            )
            if (isFarRightCard) {
                val chevrons = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && it.id.startsWith("view_all_pill_")
                }
                val chevronAbove = chevrons.firstOrNull {
                    it.boundsInRoot.bottom <= current.boundsInRoot.top + 35f &&
                        it.boundsInRoot.top >= current.boundsInRoot.top - 180f
                }
                if (chevronAbove != null) {
                    focusNodeInternal(chevronAbove, triggerScroll = true, direction = direction)
                    return true
                }
            }
        }

        val activeScroll = getActiveScrollable()
        val listState = activeScroll?.lazyListState
        val gridState = activeScroll?.lazyGridState
        val scrollState = activeScroll?.scrollState
        val scope = activeScroll?.scope ?: activeCoroutineScope ?: current.coroutineScope

        if (direction == ControllerDirection.Down) {
            if (isHomeTabActive) {
                _isTopBarVisibleByScroll.value = false
            }
            if (effectiveRowIndex < composedRows.lastIndex) {
                val nextRow = composedRows[effectiveRowIndex + 1]
                val targetNode = selectBestTargetNodeInRow(current, nextRow)
                if (targetNode != null) {
                    focusNodeInternal(targetNode, triggerScroll = true, direction = direction)
                    return true
                }
            }

            val canScrollDown = (listState != null && listState.canScrollForward) ||
                (gridState != null && gridState.canScrollForward) ||
                (scrollState != null && scrollState.canScrollForward)

            if (canScrollDown && scope != null) {
                // A held direction must not restart an in-flight scroll: cancelling the animation
                // on every key repeat kept focus on a row that was about to be recycled, so the
                // selection vanished and navigation crawled.
                if (activeVerticalScrollJob?.isActive == true) return true
                val vh = listState?.layoutInfo?.viewportSize?.height?.toFloat()
                    ?: gridState?.layoutInfo?.viewportSize?.height?.toFloat()
                    ?: 900f
                val scaleFactor = (vh / 900f).coerceIn(0.85f, 1.6f)
                val scrollDistance = (currentRow.bottom - currentRow.top + 160f * scaleFactor).coerceIn(380f * scaleFactor, 540f * scaleFactor)

                activeVerticalScrollJob?.cancel()
                activeVerticalScrollJob = scope.launch {
                    val fallbackSpec = tween<Float>(200, easing = FastOutSlowInEasing)
                    if (listState != null) {
                        runCatching { listState.animateScrollBy(scrollDistance, fallbackSpec) }
                    } else if (gridState != null) {
                        runCatching { gridState.animateScrollBy(scrollDistance, fallbackSpec) }
                    } else if (scrollState != null) {
                        runCatching { scrollState.animateScrollBy(scrollDistance, fallbackSpec) }
                    }
                    delay(220)
                    val refreshedRows = getComposedContentRows()
                    val currentInRefreshed = refreshedRows.indexOfFirst { r ->
                        (currentRow.rowLazyListState != null && r.rowLazyListState === currentRow.rowLazyListState) ||
                        (!currentRow.groupKey.isNullOrEmpty() && r.groupKey == currentRow.groupKey) ||
                        r.nodes.any { n -> currentRow.nodes.any { it.id == n.id } }
                    }
                    val targetRow = if (currentInRefreshed != -1 && currentInRefreshed < refreshedRows.lastIndex) {
                        refreshedRows[currentInRefreshed + 1]
                    } else {
                        refreshedRows.lastOrNull()
                    }
                    val target = targetRow?.let { selectBestTargetNodeInRow(current, it) }
                    if (target != null) {
                        focusNodeInternal(target, triggerScroll = true, suppressVerticalScroll = false, direction = direction)
                    } else {
                        ensureValidFocus()
                    }
                }
                return true
            }
            return true
        } else if (direction == ControllerDirection.Up) {
            if (effectiveRowIndex > 0) {
                val prevRow = composedRows[effectiveRowIndex - 1]
                val targetNode = selectBestTargetNodeInRow(current, prevRow)
                if (targetNode != null) {
                    focusNodeInternal(targetNode, triggerScroll = true, direction = direction)
                    return true
                }
            }

            val canScrollUp = (listState != null && listState.canScrollBackward) ||
                (gridState != null && gridState.canScrollBackward) ||
                (scrollState != null && scrollState.canScrollBackward)

            if (canScrollUp && scope != null) {
                if (activeVerticalScrollJob?.isActive == true) return true
                val vh = listState?.layoutInfo?.viewportSize?.height?.toFloat()
                    ?: gridState?.layoutInfo?.viewportSize?.height?.toFloat()
                    ?: 900f
                val scaleFactor = (vh / 900f).coerceIn(0.85f, 1.6f)
                val scrollDistance = (currentRow.bottom - currentRow.top + 160f * scaleFactor).coerceIn(380f * scaleFactor, 540f * scaleFactor)

                val isAtTopBoundary = (listState != null && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= scrollDistance) ||
                    (gridState != null && gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset <= scrollDistance) ||
                    (scrollState != null && scrollState.value <= scrollDistance)

                activeVerticalScrollJob?.cancel()
                activeVerticalScrollJob = scope.launch {
                    if (isAtTopBoundary) {
                        if (listState != null) runCatching { listState.animateScrollToItem(0, 0) }
                        else if (gridState != null) runCatching { gridState.animateScrollToItem(0, 0) }
                        else if (scrollState != null) runCatching { scrollState.animateScrollTo(0) }
                    } else {
                        val fallbackSpec = tween<Float>(200, easing = FastOutSlowInEasing)
                        if (listState != null) runCatching { listState.animateScrollBy(-scrollDistance, fallbackSpec) }
                        else if (gridState != null) runCatching { gridState.animateScrollBy(-scrollDistance, fallbackSpec) }
                        else if (scrollState != null) runCatching { scrollState.animateScrollBy(-scrollDistance, fallbackSpec) }
                    }
                    delay(220)
                    val refreshedRows = getComposedContentRows()
                    val currentInRefreshed = refreshedRows.indexOfFirst { r ->
                        (currentRow.rowLazyListState != null && r.rowLazyListState === currentRow.rowLazyListState) ||
                        (!currentRow.groupKey.isNullOrEmpty() && r.groupKey == currentRow.groupKey) ||
                        r.nodes.any { n -> currentRow.nodes.any { it.id == n.id } }
                    }
                    val targetRow = if (currentInRefreshed > 0) {
                        refreshedRows[currentInRefreshed - 1]
                    } else {
                        refreshedRows.firstOrNull()
                    }
                    val target = targetRow?.let { selectBestTargetNodeInRow(current, it) }
                    if (target != null) {
                        focusNodeInternal(target, triggerScroll = true, suppressVerticalScroll = false, direction = direction)
                    } else {
                        ensureValidFocus()
                    }
                }
                return true
            }

            val navNodes = nodes.values.filter { it.zone == ControllerFocusZone.Navigation && it.isVisibleAndValid }
            if (navNodes.isNotEmpty()) {
                val target = navNodes.firstOrNull { it.isDefaultFocus }
                    ?: lastFocusedByZone[ControllerFocusZone.Navigation]?.let { id -> navNodes.firstOrNull { it.id == id } }
                    ?: navNodes.minByOrNull { abs(it.boundsInRoot.center.x - current.boundsInRoot.center.x) }
                if (target != null) {
                    focusNodeInternal(target, triggerScroll = false, direction = direction)
                    return true
                }
            }
            return true
        }

        return false
    }

    private fun handleNavigate(direction: ControllerDirection): Boolean {
        hasUserNavigatedOnCurrentRoute = true
        lastNavigationDirection = direction
        if (PlatformVirtualKeyboard.isKeyboardActive) {
            PlatformVirtualKeyboard.hide()
        }
        val overlayNodes = nodes.values.filter { it.zone == ControllerFocusZone.Overlay && it.isVisibleAndValid }
        val activeZone = if (overlayNodes.isNotEmpty()) {
            ControllerFocusZone.Overlay
        } else {
            val current = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid }
            if (current == null) {
                return ensureValidFocus()
            }
            current.zone
        }

        val current = _focusedNodeId.value?.let { nodes[it] }?.takeIf { it.isVisibleAndValid && it.zone == activeZone }
            ?: run {
                return ensureValidFocus()
            }

        // Check custom node directional overrides first
        val customHandled = when (direction) {
            ControllerDirection.Up -> current.onMoveUp?.invoke() == true
            ControllerDirection.Down -> current.onMoveDown?.invoke() == true
            ControllerDirection.Left -> current.onMoveLeft?.invoke() == true
            ControllerDirection.Right -> current.onMoveRight?.invoke() == true
        }
        if (customHandled) return true

        // Deterministic Netflix/Android TV row navigation for Content zone Up and Down
        if (activeZone == ControllerFocusZone.Content &&
            (direction == ControllerDirection.Down || direction == ControllerDirection.Up)
        ) {
            return handleContentVerticalNavigation(current, direction)
        }

        // Settings Lateral Navigation: Always allow seamless handoff between Sidebar (left) and Content (right)
        if (activeZone == ControllerFocusZone.Content) {
            val isCurrentSettingsSidebar = current.groupKey == "settings_sidebar" || current.id.startsWith("settings_sidebar_")
            val isCurrentSettingsContent = current.groupKey == "settings_content" || (current.id.startsWith("settings_") && !isCurrentSettingsSidebar)

            if (isCurrentSettingsSidebar && direction == ControllerDirection.Right) {
                val contentNodes = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        (it.groupKey == "settings_content" || (it.id.startsWith("settings_") && !it.id.startsWith("settings_sidebar_")))
                }
                if (contentNodes.isNotEmpty()) {
                    val target = contentNodes.minByOrNull { it.boundsInRoot.top }
                        ?: contentNodes.first()
                    focusNodeInternal(target, triggerScroll = true, direction = direction)
                    return true
                }
            } else if (isCurrentSettingsContent && direction == ControllerDirection.Left) {
                // First check if there is an inner sibling item to the left within settings_content (e.g. chips, segmented buttons)
                val leftInnerNodes = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        (it.groupKey == "settings_content" || (it.id.startsWith("settings_") && !it.id.startsWith("settings_sidebar_"))) &&
                        it.boundsInRoot.center.x < current.boundsInRoot.center.x - 10f &&
                        abs(it.boundsInRoot.center.y - current.boundsInRoot.center.y) < 36f
                }
                if (leftInnerNodes.isNotEmpty()) {
                    val nextLeft = leftInnerNodes.maxByOrNull { it.boundsInRoot.center.x }
                    if (nextLeft != null) {
                        focusNodeInternal(nextLeft, triggerScroll = true, direction = direction)
                        return true
                    }
                }

                // If at the left edge of content, transition immediately to settings_sidebar
                val sidebarNodes = nodes.values.filter {
                    it.zone == ControllerFocusZone.Content &&
                        it.isVisibleAndValid &&
                        (it.groupKey == "settings_sidebar" || it.id.startsWith("settings_sidebar_"))
                }
                if (sidebarNodes.isNotEmpty()) {
                    val target = sidebarNodes.minByOrNull { abs(it.boundsInRoot.center.y - current.boundsInRoot.center.y) }
                        ?: sidebarNodes.first()
                    focusNodeInternal(target, triggerScroll = false, direction = direction)
                    return true
                }
            }
        }

        // If currently focused on a View All pill and pressing Left, return to that shelf row's rightmost card
        if (activeZone == ControllerFocusZone.Content && current.id.startsWith("view_all_pill_") && direction == ControllerDirection.Left) {
            val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && !it.id.startsWith("view_all_pill_") }
            val sameRowCard = contentNodes.filter { it.boundsInRoot.top >= current.boundsInRoot.bottom - 10f && it.boundsInRoot.top <= current.boundsInRoot.bottom + 140f }
                .maxByOrNull { it.boundsInRoot.center.x }
            if (sameRowCard != null) {
                focusNodeInternal(sameRowCard, triggerScroll = true, direction = direction)
                return true
            }
        }

        val zoneCandidates = nodes.values.filter {
            it.zone == activeZone && it.isVisibleAndValid && it.id != current.id
        }

        val nextInZone = findBestSpatialCandidate(current, zoneCandidates, direction)
        if (nextInZone != null) {
            val isHorizontal = direction == ControllerDirection.Left || direction == ControllerDirection.Right
            val isSameRow = isHorizontal && (
                (current.rowLazyListState != null && current.rowLazyListState === nextInZone.rowLazyListState) ||
                (current.groupKey != null && current.groupKey == nextInZone.groupKey) ||
                abs(current.boundsInRoot.center.y - nextInZone.boundsInRoot.center.y) < 80f
            )
            focusNodeInternal(nextInZone, triggerScroll = true, suppressVerticalScroll = isSameRow, direction = direction)
            return true
        }

        // If on the rightmost card of a shelf and pressing Right, check if we can focus the row's Explore More / chevron pill
        if (direction == ControllerDirection.Right && !current.id.startsWith("view_all_pill_")) {
            val chevrons = nodes.values.filter {
                it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && it.id.startsWith("view_all_pill_")
            }
            val chevron = chevrons.firstOrNull {
                it.boundsInRoot.bottom <= current.boundsInRoot.top + 35f &&
                    it.boundsInRoot.top >= current.boundsInRoot.top - 180f &&
                    it.boundsInRoot.center.x >= current.boundsInRoot.center.x - 50f
            }
            val rowCardsToRight = nodes.values.filter {
                it.zone == ControllerFocusZone.Content &&
                    it.isVisibleAndValid &&
                    !it.id.startsWith("view_all_pill_") &&
                    it.groupKey == current.groupKey &&
                    it.boundsInRoot.center.x > current.boundsInRoot.center.x + 10f
            }
            if (chevron != null && rowCardsToRight.isEmpty() && (current.rowLazyListState == null || !current.rowLazyListState!!.canScrollForward)) {
                focusNodeInternal(chevron, triggerScroll = true, direction = direction)
                return true
            }
        }

        // If moving Left or Right at the edge of a horizontal LazyRow shelf, scroll the LazyRow to reveal more items!
        if ((direction == ControllerDirection.Right || direction == ControllerDirection.Left) &&
            current.rowLazyListState != null
        ) {
            val rowState = current.rowLazyListState!!
            val canScroll = if (direction == ControllerDirection.Right) {
                rowState.canScrollForward
            } else {
                rowState.canScrollBackward
            }
            if (canScroll) {
                val scope = current.coroutineScope ?: activeCoroutineScope
                if (scope != null) {
                    if (direction == ControllerDirection.Left && (rowState.firstVisibleItemIndex == 0 || rowState.layoutInfo.visibleItemsInfo.firstOrNull()?.index == 0)) {
                        scope.launch {
                            runCatching { rowState.animateScrollToItem(0, 0) }
                        }
                        return true
                    }
                    val groupKey = current.groupKey
                    val layoutInfo = rowState.layoutInfo
                    val viewportWidth = layoutInfo.viewportSize.width.toFloat().takeIf { it > 100f }
                        ?: (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat().takeIf { it > 100f }
                        ?: 1920f
                    val safeLeft = max(layoutInfo.beforeContentPadding.toFloat(), 64f)
                    val safeRight = (viewportWidth - max(layoutInfo.afterContentPadding.toFloat(), 64f)).coerceAtLeast(safeLeft + 120f)
                    val unclippedCurrent = if (current.unclippedBoundsInRoot.width > 2f) current.unclippedBoundsInRoot else current.boundsInRoot

                    val scrollDelta = if (direction == ControllerDirection.Right) {
                        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                        val cardWidth = lastVisible?.size?.toFloat()
                            ?: unclippedCurrent.width.takeIf { it > 50f }
                            ?: 240f
                        val gap = 16f
                        val lastEnd = unclippedCurrent.right.coerceAtLeast(safeRight)
                        val overflow = (lastEnd - safeRight).coerceAtLeast(0f)
                        (overflow + cardWidth + gap + 24f)
                    } else {
                        val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
                        val cardWidth = firstVisible?.size?.toFloat()
                            ?: unclippedCurrent.width.takeIf { it > 50f }
                            ?: 240f
                        val gap = 16f
                        val firstStart = unclippedCurrent.left.coerceAtMost(safeLeft)
                        val underflow = (safeLeft - firstStart).coerceAtLeast(0f)
                        -(underflow + cardWidth + gap + 24f)
                    }

                    activeHorizontalScrollJob?.cancel()
                    activeHorizontalScrollJob = scope.launch {
                        runCatching { rowState.animateScrollBy(scrollDelta, tween(170, easing = SnappyEaseOut)) }
                        for (attempt in 0..6) {
                            delay(30)
                            val refreshed = nodes.values.filter {
                                it.zone == activeZone &&
                                    it.isVisibleAndValid &&
                                    it.id != current.id &&
                                    (groupKey == null || it.groupKey == groupKey)
                            }
                            val newlyVisible = findBestSpatialCandidate(current, refreshed, direction)
                            if (newlyVisible != null) {
                                focusNodeInternal(newlyVisible, triggerScroll = true, suppressVerticalScroll = true, direction = direction)
                                break
                            }
                        }
                    }
                    return true
                }
            }
        }

        // Zone transitions between Navigation (Sidebar / TopBar) and Content
        if (activeZone == ControllerFocusZone.Content) {
            if (direction == ControllerDirection.Left) {
                // At the left edge of the content, hand focus back to the navigation rail. This is
                // the desktop counterpart of a sidebar: Left leaves a poster row and selects the
                // rail item closest to the current card.
                val navigationNodes = nodes.values.filter {
                    it.zone == ControllerFocusZone.Navigation && it.isVisibleAndValid
                }
                if (navigationNodes.isNotEmpty()) {
                    val target = navigationNodes.firstOrNull { it.isDefaultFocus }
                        ?: navigationNodes.minByOrNull {
                            abs(it.boundsInRoot.center.y - current.boundsInRoot.center.y)
                        }
                    if (target != null) {
                        manualSidebarOpen = true
                        focusNodeInternal(target, triggerScroll = false, direction = direction)
                        return true
                    }
                }
                return true
            } else if (direction == ControllerDirection.Up) {
                // First check if vertical list can scroll up to reveal upper content/hero
                val activeScroll = getActiveScrollable()
                val listState = activeScroll?.lazyListState
                val scrollState = activeScroll?.scrollState
                val canScrollUp = (listState != null && listState.canScrollBackward) ||
                    (scrollState != null && scrollState.canScrollBackward)
                if (canScrollUp) {
                    val scope = activeScroll?.scope ?: activeCoroutineScope ?: current.coroutineScope
                    if (scope != null) {
                        scope.launch {
                            if (listState != null) {
                                runCatching { listState.animateScrollBy(-360f, tween(160)) }
                            } else if (scrollState != null) {
                                runCatching { scrollState.animateScrollBy(-360f, tween(160)) }
                            }
                            delay(180)
                            val refreshed = nodes.values.filter {
                                it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && it.id != current.id
                            }
                            val candidate = findBestSpatialCandidate(current, refreshed, ControllerDirection.Up)
                            if (candidate != null) {
                                focusNodeInternal(candidate, triggerScroll = true)
                            }
                        }
                        return true
                    }
                }
                // At the very top of Content: clamp and remain in Content zone
                return true
            } else if (direction == ControllerDirection.Down) {
                // Scroll vertical container down to compose the next shelf/row
                val activeScroll = getActiveScrollable()
                val listState = activeScroll?.lazyListState
                val scrollState = activeScroll?.scrollState
                val canScrollDown = (listState != null && listState.canScrollForward) ||
                    (scrollState != null && scrollState.canScrollForward)
                if (canScrollDown) {
                    val scope = activeScroll?.scope ?: activeCoroutineScope ?: current.coroutineScope
                    if (scope != null) {
                        scope.launch {
                            if (listState != null) {
                                runCatching { listState.animateScrollBy(360f, tween(160)) }
                            } else if (scrollState != null) {
                                runCatching { scrollState.animateScrollBy(360f, tween(160)) }
                            }
                            delay(180)
                            val refreshed = nodes.values.filter {
                                it.zone == ControllerFocusZone.Content && it.isVisibleAndValid && it.id != current.id
                            }
                            val candidate = findBestSpatialCandidate(current, refreshed, ControllerDirection.Down)
                            if (candidate != null) {
                                focusNodeInternal(candidate, triggerScroll = true)
                            }
                        }
                        return true
                    }
                }
            }
        } else if (activeZone == ControllerFocusZone.Navigation) {
            val isTopBar = current.groupKey == "top_nav" || current.id.startsWith("top_nav_")
            if (direction == ControllerDirection.Down || (!isTopBar && direction == ControllerDirection.Right)) {
                val contentNodes = nodes.values.filter { it.zone == ControllerFocusZone.Content && it.isVisibleAndValid }
                if (contentNodes.isNotEmpty()) {
                    val rememberedId = lastFocusedByZone[ControllerFocusZone.Content]
                    val target = rememberedId?.let { id -> contentNodes.firstOrNull { it.id == id } }
                        ?: contentNodes.filter { it.isDefaultFocus }
                            .minWithOrNull(
                                compareBy<ControllerFocusNode> { (it.boundsInRoot.top / 32f).toInt() }
                                    .thenBy { it.boundsInRoot.left },
                            )
                        ?: contentNodes.minWithOrNull(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
                    if (target != null) {
                        focusNodeInternal(target, triggerScroll = true)
                        return true
                    }
                }
            }
            // Left/Up at the rail edge stay in the rail instead of leaking to Compose's focus
            // search, which would select an unrelated focusable.
            if (direction == ControllerDirection.Left || direction == ControllerDirection.Up) {
                return true
            }
        }

        return false
    }

    private fun findBestSpatialCandidate(
        current: ControllerFocusNode,
        candidates: List<ControllerFocusNode>,
        direction: ControllerDirection,
    ): ControllerFocusNode? {
        val from = if (current.rowLazyListState != null && current.unclippedBoundsInRoot.width > 2f) {
            current.unclippedBoundsInRoot
        } else {
            current.boundsInRoot
        }
        val fromCenter = from.center

        if (direction == ControllerDirection.Left || direction == ControllerDirection.Right) {
            // 1. First look for candidates in the same groupKey or vertically overlapping row
            val sameRowCandidates = candidates.filter { candidate ->
                val to = if (candidate.rowLazyListState != null && candidate.unclippedBoundsInRoot.width > 2f) {
                    candidate.unclippedBoundsInRoot
                } else {
                    candidate.boundsInRoot
                }
                val toCenter = to.center
                val isCorrectHorizontalDir = if (direction == ControllerDirection.Right) {
                    toCenter.x > fromCenter.x + 8f
                } else {
                    toCenter.x < fromCenter.x - 8f
                }
                if (!isCorrectHorizontalDir) return@filter false

                val sameGroup = current.groupKey != null && current.groupKey == candidate.groupKey &&
                    abs(toCenter.y - fromCenter.y) < max(from.height, to.height) * 0.85f
                val verticalOverlap = min(from.bottom, to.bottom) - max(from.top, to.top)
                val minHeight = min(from.height, to.height).coerceAtLeast(1f)
                sameGroup || (verticalOverlap / minHeight) >= 0.25f || abs(toCenter.y - fromCenter.y) <= 36f
            }

            if (sameRowCandidates.isNotEmpty()) {
                return sameRowCandidates.minByOrNull { candidate ->
                    val to = if (candidate.rowLazyListState != null && candidate.unclippedBoundsInRoot.width > 2f) {
                        candidate.unclippedBoundsInRoot
                    } else {
                        candidate.boundsInRoot
                    }
                    val toCenter = to.center
                    val dx = abs(toCenter.x - fromCenter.x)
                    val dy = abs(toCenter.y - fromCenter.y)
                    val groupBonus = if (current.groupKey != null && current.groupKey == candidate.groupKey) -50f else 0f
                    dx + dy * 3.5f + groupBonus
                }
            }
            return null
        }

        // Up or Down navigation
        val directionalCandidates = candidates.filter { candidate ->
            val isChevron = candidate.id.startsWith("view_all_pill_")
            if (isChevron) {
                // Chevrons are ONLY reachable when direction is Up AND current is on the far-right (last) card of that row
                if (direction == ControllerDirection.Up) {
                    val isCurrentChevron = current.id.startsWith("view_all_pill_")
                    val isFarRightCard = !isCurrentChevron && (
                        (current.rowItemIndex >= 0 && current.rowTotalCount > 0 && current.rowItemIndex == current.rowTotalCount - 1) ||
                        candidates.none { other ->
                            other.groupKey != null && other.groupKey == current.groupKey &&
                                other.boundsInRoot.center.x > fromCenter.x + 10f
                        }
                    )
                    val to = candidate.boundsInRoot
                    val isDirectlyAbove = to.center.y < fromCenter.y - 14f && abs(to.center.y - fromCenter.y) < 320f
                    if (!isFarRightCard || !isDirectlyAbove) return@filter false
                } else {
                    // Down navigation never targets a chevron
                    return@filter false
                }
            }

            // If current IS a chevron, pressing Down must return straight back to that row's cards
            if (current.id.startsWith("view_all_pill_") && direction == ControllerDirection.Down) {
                if (isChevron) return@filter false
            }

            val to = candidate.boundsInRoot
            val toCenter = to.center
            if (direction == ControllerDirection.Down) {
                toCenter.y > fromCenter.y + 14f && !(current.groupKey != null && current.groupKey == candidate.groupKey && abs(toCenter.y - fromCenter.y) < from.height * 0.6f)
            } else {
                toCenter.y < fromCenter.y - 14f && !(current.groupKey != null && current.groupKey == candidate.groupKey && abs(toCenter.y - fromCenter.y) < from.height * 0.6f)
            }
        }

        if (directionalCandidates.isEmpty()) return null

        // Find the closest vertical row/band in that direction first
        val nearestVerticalDistance = directionalCandidates.minOf { candidate ->
            val to = candidate.boundsInRoot
            if (direction == ControllerDirection.Down) {
                max(0f, to.top - from.bottom) + abs(to.center.y - fromCenter.y) * 0.35f
            } else {
                max(0f, from.top - to.bottom) + abs(fromCenter.y - to.center.y) * 0.35f
            }
        }

        // Consider candidates in the nearest vertical band (within a reasonable vertical tolerance)
        val bandTolerance = 110f
        val bandCandidates = directionalCandidates.filter { candidate ->
            val to = candidate.boundsInRoot
            val vDist = if (direction == ControllerDirection.Down) {
                max(0f, to.top - from.bottom) + abs(to.center.y - fromCenter.y) * 0.35f
            } else {
                max(0f, from.top - to.bottom) + abs(fromCenter.y - to.center.y) * 0.35f
            }
            vDist <= nearestVerticalDistance + bandTolerance
        }

        // If the target band belongs to a groupKey where we have a remembered focused node (and it's a horizontal shelf, not a multi-row grid), prefer the remembered node or closest X
        val bestBandCandidate = bandCandidates.minByOrNull { candidate ->
            val to = candidate.boundsInRoot
            val dy = abs(to.center.y - fromCenter.y)
            val dx = abs(to.center.x - fromCenter.x)
            val horizontalOverlap = max(0f, min(from.right, to.right) - max(from.left, to.left))
            val overlapBonus = if (horizontalOverlap > 0f) -60f else 0f
            dy * 2.2f + dx * 0.85f + overlapBonus
        }

        return bestBandCandidate
    }
}

private var autoControllerNodeCounter = 0L

/**
 * Registers global/screen-level controller action handlers for the active composition scope.
 */
@Composable
fun RegisterControllerHandler(
    key: String = "",
    screenContext: ControllerScreenContext? = null,
    onBack: (() -> Boolean)? = null,
    onBumperLeft: (() -> Boolean)? = null,
    onBumperRight: (() -> Boolean)? = null,
    onShoulderLeft: (() -> Boolean)? = null,
    onShoulderRight: (() -> Boolean)? = null,
    onTriggerLeft: (() -> Boolean)? = null,
    onTriggerRight: (() -> Boolean)? = null,
    onActionX: (() -> Boolean)? = null,
    onActionY: (() -> Boolean)? = null,
    onSecondaryAction: (() -> Boolean)? = null,
    onTertiaryAction: (() -> Boolean)? = null,
    onViewButton: (() -> Boolean)? = null,
    onMenuButton: (() -> Boolean)? = null,
    onViewAction: (() -> Boolean)? = null,
    onMenuAction: (() -> Boolean)? = null,
    onEnterNavigationZone: (() -> Unit)? = null,
    onExitNavigationZone: (() -> Unit)? = null,
) {
    val effectiveKey = remember(key) {
        key.ifEmpty { "controller_handler_${++autoControllerNodeCounter}" }
    }
    val latestBack by rememberUpdatedState(onBack)
    val latestLB by rememberUpdatedState(onBumperLeft ?: onShoulderLeft)
    val latestRB by rememberUpdatedState(onBumperRight ?: onShoulderRight)
    val latestLT by rememberUpdatedState(onTriggerLeft)
    val latestRT by rememberUpdatedState(onTriggerRight)
    val latestX by rememberUpdatedState(onActionX ?: onSecondaryAction)
    val latestY by rememberUpdatedState(onActionY ?: onTertiaryAction)
    val latestView by rememberUpdatedState(onViewButton ?: onViewAction)
    val latestMenu by rememberUpdatedState(onMenuButton ?: onMenuAction)
    val latestEnterNav by rememberUpdatedState(onEnterNavigationZone)
    val latestExitNav by rememberUpdatedState(onExitNavigationZone)

    LaunchedEffect(screenContext) {
        if (screenContext != null) {
            ControllerManager.updateScreenContext(screenContext)
        }
    }

    DisposableEffect(effectiveKey) {
        ControllerFocusEngine.registerGlobalHandlers(
            key = effectiveKey,
            handlers = ControllerGlobalHandlers(
                onBack = { latestBack?.invoke() == true },
                onBumperLeft = { latestLB?.invoke() == true },
                onBumperRight = { latestRB?.invoke() == true },
                onTriggerLeft = { latestLT?.invoke() == true },
                onTriggerRight = { latestRT?.invoke() == true },
                onActionX = { latestX?.invoke() == true },
                onActionY = { latestY?.invoke() == true },
                onViewButton = { latestView?.invoke() == true },
                onMenuButton = { latestMenu?.invoke() == true },
                onEnterNavigationZone = { latestEnterNav?.invoke() },
                onExitNavigationZone = { latestExitNav?.invoke() },
            ),
        )
        onDispose {
            ControllerFocusEngine.unregisterGlobalHandlers(effectiveKey)
        }
    }
}

/**
 * Registers a vertical LazyListState or ScrollState so the controller spatial engine
 * can automatically scroll shelves/rows into view and handle LT/RT page jumps and RS scrolling.
 *
 * The returned modifier reports the container's on-screen viewport to the engine so scroll
 * centring accounts for headers/tab rows above the list (otherwise focused rows could land
 * behind the header and appear to lose focus).
 */
@Composable
fun RegisterControllerVerticalScroll(
    lazyListState: LazyListState? = null,
    scrollState: ScrollState? = null,
    lazyGridState: LazyGridState? = null,
): Modifier {
    val isScreenActive = LocalScreenActive.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lazyListState, scrollState, lazyGridState, isScreenActive) {
        if (isScreenActive) {
            ControllerFocusEngine.registerVerticalScrollable(
                lazyListState = lazyListState,
                scrollState = scrollState,
                lazyGridState = lazyGridState,
                scope = scope,
            )
        }
        onDispose {
            ControllerFocusEngine.unregisterVerticalScrollable(
                lazyListState = lazyListState,
                scrollState = scrollState,
                lazyGridState = lazyGridState,
            )
        }
    }
    LaunchedEffect(lazyListState, scrollState, lazyGridState, isScreenActive) {
        if (!isScreenActive) return@LaunchedEffect
        var lastIndex = lazyListState?.firstVisibleItemIndex ?: lazyGridState?.firstVisibleItemIndex ?: 0
        var lastOffset = lazyListState?.firstVisibleItemScrollOffset
            ?: lazyGridState?.firstVisibleItemScrollOffset
            ?: scrollState?.value
            ?: 0
        snapshotFlow {
            val idx = lazyListState?.firstVisibleItemIndex ?: lazyGridState?.firstVisibleItemIndex ?: 0
            val off = lazyListState?.firstVisibleItemScrollOffset
                ?: lazyGridState?.firstVisibleItemScrollOffset
                ?: scrollState?.value
                ?: 0
            idx to off
        }.collect { (idx, off) ->
            val topThreshold = if (ControllerFocusEngine.isHomeTabActive) 16 else 95
            if (idx == 0 && off <= topThreshold) {
                ControllerFocusEngine.setTopBarVisibleByScroll(true)
            } else if (idx > lastIndex || (idx == lastIndex && off > lastOffset + 8)) {
                ControllerFocusEngine.setTopBarVisibleByScroll(false)
            } else if (idx < lastIndex || (idx == lastIndex && off < lastOffset - 8)) {
                ControllerFocusEngine.setTopBarVisibleByScroll(true)
            }
            lastIndex = idx
            lastOffset = off
        }
    }
    return Modifier.onGloballyPositioned { coordinates ->
        if (!isScreenActive || !coordinates.isAttached) return@onGloballyPositioned
        val bounds = coordinates.boundsInRoot()
        if (bounds.height <= 0f) return@onGloballyPositioned
        ControllerFocusEngine.updateScrollableViewport(
            lazyListState = lazyListState,
            scrollState = scrollState,
            lazyGridState = lazyGridState,
            topInRoot = bounds.top,
            height = bounds.height,
        )
    }
}

/**
 * Makes any UI element focusable and navigable by the Windows 11 Controller Spatial Navigation Engine,
 * automatically scrolling it into view when focused and drawing a high-contrast visual focus ring.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.controllerFocusable(
    id: String = "",
    zone: ControllerFocusZone? = null,
    groupKey: String? = null,
    enabled: Boolean = true,
    isDefaultFocus: Boolean = false,
    isTextInput: Boolean = false,
    shape: Shape? = null,
    cornerRadius: Dp = 12.dp,
    focusScale: Float = 1.0f,
    scaleOnFocus: Float? = null,
    useHaloFocusRing: Boolean = false,
    hideFocusRing: Boolean = false,
    selectLabel: String = "Select",
    actionLabel: String? = null,
    secondaryActionLabel: String? = null,
    tertiaryActionLabel: String? = null,
    onFocused: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onTertiaryAction: (() -> Unit)? = null,
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null,
    onMoveLeft: (() -> Boolean)? = null,
    onMoveRight: (() -> Boolean)? = null,
    onSelect: () -> Unit,
): Modifier = composed {
    val effectiveId = remember(id) {
        id.ifEmpty { "ctrl_node_${++autoControllerNodeCounter}" }
    }
    val effectiveSelectLabel = actionLabel ?: selectLabel
    val effectiveFocusScale = scaleOnFocus ?: focusScale
    val isControllerActive by ControllerManager.isNavigationActive.collectAsState()
    val isScreenActive = LocalScreenActive.current
    val effectiveEnabled = enabled && isScreenActive
    val focusedNodeId by ControllerFocusEngine.focusedNodeId.collectAsState()
    val isFocused = effectiveEnabled && isControllerActive && focusedNodeId == effectiveId

    val ambientZone = LocalControllerFocusZone.current
    val ambientGroupKey = LocalControllerGroupKey.current
    val ambientRowState = LocalControllerRowLazyListState.current
    val effectiveZone = zone ?: ambientZone
    val effectiveGroupKey = groupKey ?: ambientGroupKey

    val coroutineScope = rememberCoroutineScope()

    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnSecondary by rememberUpdatedState(onSecondaryAction)
    val latestOnTertiary by rememberUpdatedState(onTertiaryAction)
    val latestOnFocused by rememberUpdatedState(onFocused)
    val latestMoveUp by rememberUpdatedState(onMoveUp)
    val latestMoveDown by rememberUpdatedState(onMoveDown)
    val latestMoveLeft by rememberUpdatedState(onMoveLeft)
    val latestMoveRight by rememberUpdatedState(onMoveRight)

    var latestBounds by remember { mutableStateOf(Rect.Zero) }
    var latestUnclippedBounds by remember { mutableStateOf(Rect.Zero) }

    val node = remember(effectiveId) {
        ControllerFocusNode(
            id = effectiveId,
            zone = effectiveZone,
            groupKey = effectiveGroupKey,
        )
    }

    node.zone = effectiveZone
    node.groupKey = effectiveGroupKey
    node.enabled = effectiveEnabled
    node.isDefaultFocus = isDefaultFocus
    node.isTextInput = isTextInput
    node.selectLabel = effectiveSelectLabel
    node.secondaryActionLabel = secondaryActionLabel
    node.tertiaryActionLabel = tertiaryActionLabel
    node.onSelect = { latestOnSelect() }
    node.onSecondaryAction = latestOnSecondary
    node.onTertiaryAction = latestOnTertiary
    node.onMoveUp = latestMoveUp
    node.onMoveDown = latestMoveDown
    node.onMoveLeft = latestMoveLeft
    node.onMoveRight = latestMoveRight
    node.rowLazyListState = ambientRowState
    node.coroutineScope = coroutineScope
    node.onFocusedWithOption = { _ ->
        latestOnFocused?.invoke()
    }
    node.onFocused = {
        latestOnFocused?.invoke()
    }

    DisposableEffect(effectiveId, effectiveEnabled) {
        if (effectiveEnabled) {
            ControllerFocusEngine.upsertNode(node)
        } else {
            ControllerFocusEngine.removeNode(effectiveId)
        }
        onDispose {
            ControllerFocusEngine.removeNode(effectiveId)
        }
    }

    LaunchedEffect(
        effectiveId,
        effectiveEnabled,
        effectiveZone,
        effectiveGroupKey,
        effectiveSelectLabel,
        secondaryActionLabel,
        tertiaryActionLabel,
        onSecondaryAction != null,
        onTertiaryAction != null,
    ) {
        if (effectiveEnabled) {
            ControllerFocusEngine.upsertNode(node)
        }
    }

    val focusedSnapshot by ControllerFocusEngine.focusedNodeSnapshot.collectAsState()
    val isSiblingActiveInGroup = !isFocused && isControllerActive && effectiveGroupKey != null &&
        focusedSnapshot?.groupKey == effectiveGroupKey
    val targetAlpha = if (isSiblingActiveInGroup) 0.82f else 1f
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 150),
        label = "controller_sibling_alpha",
    )

    val scale by animateFloatAsState(
        targetValue = if (isFocused) effectiveFocusScale else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "controller_focus_scale",
    )

    val sheenProgress by animateFloatAsState(
        targetValue = if (isFocused && !hideFocusRing) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "controller_focus_sheen",
    )

    this
        .onGloballyPositioned { coordinates ->
            val bounds = if (coordinates.isAttached) coordinates.boundsInRoot() else Rect.Zero
            val unclippedBounds = if (coordinates.isAttached) {
                val pos = coordinates.positionInRoot()
                Rect(pos.x, pos.y, pos.x + coordinates.size.width, pos.y + coordinates.size.height)
            } else {
                Rect.Zero
            }
            if (bounds != latestBounds || unclippedBounds != latestUnclippedBounds) {
                latestBounds = bounds
                latestUnclippedBounds = unclippedBounds
                node.boundsInRoot = bounds
                node.unclippedBoundsInRoot = unclippedBounds
                if (effectiveEnabled) {
                    ControllerFocusEngine.upsertNode(node)
                }
            }
        }
        .zIndex(if (isFocused) 15f else 0f)
        .graphicsLayer {
            if (scale != 1f) {
                scaleX = scale
                scaleY = scale
            }
            if (animatedAlpha != 1f) {
                alpha = animatedAlpha
            }
        }
        .drawWithContent {
            drawContent()
            if (sheenProgress > 0.01f) {
                val sheenShape = shape ?: RoundedCornerShape(cornerRadius)
                val sheenPath = Path().apply {
                    addOutline(sheenShape.createOutline(size, layoutDirection, this@drawWithContent))
                }
                clipPath(sheenPath) {
                    // Subtle artwork brightening + diagonal specular sheen on cover
                    drawRect(
                        color = Color.White.copy(alpha = 0.065f * sheenProgress),
                    )
                    drawRect(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.0f to Color.White.copy(alpha = 0.20f * sheenProgress),
                                0.28f to Color.White.copy(alpha = 0.08f * sheenProgress),
                                0.55f to Color.Transparent,
                                1.0f to Color.White.copy(alpha = 0.04f * sheenProgress),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                }
            }
            if (isFocused && !hideFocusRing) {
                if (useHaloFocusRing) {
                    // Play Button Halo / High-Contrast Focus Ring:
                    // Rendered strictly OUTSIDE the button perimeter so it never encroaches into the white button fill or icon:
                    // 1. Dark separation ring (1.5px solid #090A10) right at the button boundary
                    // 2. Crisp white outline (1.5px solid #FFFFFF) offset outward by 1.5px
                    // 3. Diffused outer white glow (rgba(255, 255, 255, 0.55)) fading outward
                    // 4. Outer subtle drop shadow
                    val sepStrokePx = 1.5f.dp.toPx()
                    val whiteStrokePx = 1.5f.dp.toPx()
                    val glowStrokePx = 3.5f.dp.toPx()
                    val shadowStrokePx = 5.dp.toPx()

                    val outlineOffsetPx = 1.5f.dp.toPx()
                    val glowOffsetPx = 3.dp.toPx()
                    val shadowOffsetPx = 5.5f.dp.toPx()

                    if (shape != null) {
                        // 1. Boundary separation ring
                        drawOutline(
                            outline = shape.createOutline(size, layoutDirection, this),
                            color = Color(0xFF090A10),
                            style = Stroke(width = sepStrokePx),
                        )
                        // 2. White outline offset outward
                        translate(left = -outlineOffsetPx, top = -outlineOffsetPx) {
                            val expandedSize = Size(size.width + outlineOffsetPx * 2, size.height + outlineOffsetPx * 2)
                            drawOutline(
                                outline = shape.createOutline(expandedSize, layoutDirection, this),
                                color = Color.White,
                                style = Stroke(width = whiteStrokePx),
                            )
                        }
                        // 3. Diffused white glow
                        translate(left = -glowOffsetPx, top = -glowOffsetPx) {
                            val expandedSize = Size(size.width + glowOffsetPx * 2, size.height + glowOffsetPx * 2)
                            drawOutline(
                                outline = shape.createOutline(expandedSize, layoutDirection, this),
                                color = Color.White.copy(alpha = 0.55f),
                                style = Stroke(width = glowStrokePx),
                            )
                        }
                        // 4. Subtle outer shadow
                        translate(left = -shadowOffsetPx, top = -shadowOffsetPx) {
                            val expandedSize = Size(size.width + shadowOffsetPx * 2, size.height + shadowOffsetPx * 2)
                            drawOutline(
                                outline = shape.createOutline(expandedSize, layoutDirection, this),
                                color = Color.Black.copy(alpha = 0.50f),
                                style = Stroke(width = shadowStrokePx),
                            )
                        }
                    } else {
                        val radiusPx = cornerRadius.toPx()
                        // 1. Boundary separation ring
                        drawRoundRect(
                            color = Color(0xFF090A10),
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = CornerRadius(radiusPx, radiusPx),
                            style = Stroke(width = sepStrokePx),
                        )
                        // 2. White outline offset outward
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(-outlineOffsetPx, -outlineOffsetPx),
                            size = Size(size.width + outlineOffsetPx * 2, size.height + outlineOffsetPx * 2),
                            cornerRadius = CornerRadius(radiusPx + outlineOffsetPx, radiusPx + outlineOffsetPx),
                            style = Stroke(width = whiteStrokePx),
                        )
                        // 3. Diffused white glow
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.55f),
                            topLeft = Offset(-glowOffsetPx, -glowOffsetPx),
                            size = Size(size.width + glowOffsetPx * 2, size.height + glowOffsetPx * 2),
                            cornerRadius = CornerRadius(radiusPx + glowOffsetPx, radiusPx + glowOffsetPx),
                            style = Stroke(width = glowStrokePx),
                        )
                        // 4. Subtle outer shadow
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.50f),
                            topLeft = Offset(-shadowOffsetPx, -shadowOffsetPx),
                            size = Size(size.width + shadowOffsetPx * 2, size.height + shadowOffsetPx * 2),
                            cornerRadius = CornerRadius(radiusPx + shadowOffsetPx, radiusPx + shadowOffsetPx),
                            style = Stroke(width = shadowStrokePx),
                        )
                    }
                } else {
                    // Subtle Modern Focus Ring across all cards/elements:
                    // 1. Subtle drop shadow
                    // 2. Soft diffused white glow (rgba(255, 255, 255, 0.45))
                    // 3. Sleek thinner 1.6dp white primary border
                    val shadowStrokePx = 5.dp.toPx()
                    val glowStrokePx = 3.5f.dp.toPx()
                    val whiteBorderStrokePx = 1.6f.dp.toPx()

                    if (shape != null) {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        drawOutline(
                            outline = outline,
                            color = Color.Black.copy(alpha = 0.50f),
                            style = Stroke(width = shadowStrokePx),
                        )
                        drawOutline(
                            outline = outline,
                            color = Color.White.copy(alpha = 0.45f),
                            style = Stroke(width = glowStrokePx),
                        )
                        drawOutline(
                            outline = outline,
                            color = Color.White,
                            style = Stroke(width = whiteBorderStrokePx),
                        )
                    } else {
                        val radiusPx = cornerRadius.toPx()
                        val cr = CornerRadius(radiusPx, radiusPx)
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.50f),
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = cr,
                            style = Stroke(width = shadowStrokePx),
                        )
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.45f),
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = cr,
                            style = Stroke(width = glowStrokePx),
                        )
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = cr,
                            style = Stroke(width = whiteBorderStrokePx),
                        )
                    }
                }
            }
        }
}

/**
 * Convenience check for whether a specific controller node currently holds active spatial focus.
 */
@Composable
fun isControllerNodeFocused(id: String): Boolean {
    val isControllerActive by ControllerManager.isNavigationActive.collectAsState()
    val focusedNodeId by ControllerFocusEngine.focusedNodeId.collectAsState()
    return isControllerActive && focusedNodeId == id
}


