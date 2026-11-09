/*
 * Ported from NuvioDeck (https://github.com/kllakm/NuvioDeck), GPLv3.
 * Spatial focus engine and controller hint UI for 10-foot navigation.
 */
package com.nuvio.app.controller

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Standard Windows 11 Xbox controller button layout.
 * All physical controllers (XInput, DirectInput, DualSense/DualShock, Switch Pro)
 * are normalized into this canonical layout.
 */
enum class XboxButton(val displayLabel: String) {
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),
    LB("LB"),
    RB("RB"),
    LT("LT"),
    RT("RT"),
    LS("LS"),
    RS("RS"),
    DPadUp("D-Pad"),
    DPadDown("D-Pad"),
    DPadLeft("D-Pad"),
    DPadRight("D-Pad"),
    View("View"),
    Menu("Menu"),
}

/**
 * Glyphs rendered in the bottom-right Controller Hint Toolbar.
 */
enum class XboxGlyph(val label: String) {
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),
    LB("LB"),
    RB("RB"),
    LT("LT"),
    RT("RT"),
    LS("LS"),
    RS("RS"),
    DPad("D-Pad"),
    LB_RB("LB/RB"),
    LT_RT("LT/RT"),
    View("View"),
    Menu("Menu"),
}

enum class ControllerDirection {
    Up,
    Down,
    Left,
    Right,
}

sealed interface ControllerEvent {
    data class ButtonPressed(val button: XboxButton) : ControllerEvent
    data class ButtonReleased(val button: XboxButton) : ControllerEvent
    data class Navigate(val direction: ControllerDirection, val isRepeat: Boolean = false) : ControllerEvent
    data class AnalogScroll(val dx: Float, val dy: Float) : ControllerEvent
}

@Immutable
data class ControllerHintItem(
    val glyph: XboxGlyph,
    val actionLabel: String,
)

enum class ControllerScreenType {
    ProfileSelection,
    Home,
    Search,
    Library,
    Settings,
    Catalog,
    Details,
    Streams,
    Player,
    Modal,
    ModalOverlay,
}

@Immutable
data class ControllerScreenContext(
    val screenType: ControllerScreenType = ControllerScreenType.Home,
    val customHints: List<ControllerHintItem>? = null,
    val canNavigateBack: Boolean = false,
    val hasSeasonsOrTabs: Boolean = false,
    val seasonsOrTabsLabel: String? = null,
    val selectLabel: String? = null,
    val backLabel: String? = null,
    val secondaryActionLabel: String? = null,
    val tertiaryActionLabel: String? = null,
    val lbLabel: String? = null,
    val rbLabel: String? = null,
    val viewLabel: String? = null,
    val menuLabel: String? = null,
    val dPadLabel: String? = null,
    val rightStickLabel: String? = null,
)

/**
 * Central state manager for controller activity, input events, and context-aware hints.
 */
object ControllerManager {
    private val _isControllerActive = MutableStateFlow(false)
    val isControllerActive: StateFlow<Boolean> = _isControllerActive.asStateFlow()

    /**
     * True while the user navigates with any non-pointer input (controller or keyboard arrows).
     *
     * Focus rings and sibling dimming follow this flag, while [isControllerActive] additionally
     * drives controller-only chrome such as the Xbox hint toolbar. Keeping them separate lets
     * keyboard users see the same spatial selection without seeing controller button hints.
     */
    private val _isNavigationActive = MutableStateFlow(false)
    val isNavigationActive: StateFlow<Boolean> = _isNavigationActive.asStateFlow()

    private val _connectedControllerCount = MutableStateFlow(0)
    val connectedControllerCount: StateFlow<Int> = _connectedControllerCount.asStateFlow()

    private val _screenContext = MutableStateFlow(ControllerScreenContext())
    val screenContext: StateFlow<ControllerScreenContext> = _screenContext.asStateFlow()

    private val _events = MutableSharedFlow<ControllerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ControllerEvent> = _events.asSharedFlow()

    /**
     * Events the spatial engine could not consume (screens without controllerFocusable nodes).
     * The Compose focus bridge listens here to keep the gamepad usable next to the keyboard.
     */
    private val _unhandledEvents = MutableSharedFlow<ControllerEvent>(extraBufferCapacity = 64)
    val unhandledEvents: SharedFlow<ControllerEvent> = _unhandledEvents.asSharedFlow()

    @Volatile
    private var lastControllerInputEpochMs: Long = 0L

    fun notifyControllerInput(): Boolean {
        lastControllerInputEpochMs = System.currentTimeMillis()
        notifyNavigationInput()
        val transitioned = !_isControllerActive.value
        if (transitioned) {
            _isControllerActive.value = true
        }
        return transitioned
    }

    /** Marks spatial navigation as active without enabling controller-only hint UI. */
    fun notifyNavigationInput(): Boolean {
        lastControllerInputEpochMs = System.currentTimeMillis()
        val transitioned = !_isNavigationActive.value
        if (transitioned) {
            _isNavigationActive.value = true
        }
        return transitioned
    }

    fun notifyMouseOrKeyboardInput() {
        // Guard against synthetic AWT mouse move events fired immediately during layout/cursor changes
        val elapsed = System.currentTimeMillis() - lastControllerInputEpochMs
        if (elapsed < 180L) return
        if (_isControllerActive.value) {
            _isControllerActive.value = false
        }
        if (_isNavigationActive.value) {
            _isNavigationActive.value = false
        }
    }

    fun forceDeactivateController() {
        if (_isControllerActive.value) {
            _isControllerActive.value = false
        }
        if (_isNavigationActive.value) {
            _isNavigationActive.value = false
        }
    }

    fun updateConnectedCount(count: Int) {
        _connectedControllerCount.value = count
    }

    fun updateScreenContext(context: ControllerScreenContext) {
        _screenContext.value = context
    }

    fun updateScreenContext(updater: (ControllerScreenContext) -> ControllerScreenContext) {
        _screenContext.value = updater(_screenContext.value)
    }

    fun dispatchEvent(event: ControllerEvent) {
        val wokeUp = notifyControllerInput()
        if (wokeUp && event is ControllerEvent.Navigate && !ControllerFocusEngine.deferNavigationToCompose) {
            // First movement both wakes the app into controller mode and selects the initial item,
            // so the very first D-pad press responds immediately instead of being swallowed.
            ControllerFocusEngine.ensureValidFocus()
        }
        val handledByEngine = ControllerFocusEngine.handleEvent(event)
        if (!handledByEngine) {
            _events.tryEmit(event)
            _unhandledEvents.tryEmit(event)
        } else if (event is ControllerEvent.ButtonPressed || event is ControllerEvent.Navigate) {
            // Still emit to observers that need raw notifications (e.g. player overlay activity)
            _events.tryEmit(event)
        }
    }

    /**
     * Routes a keyboard arrow into the spatial focus engine.
     *
     * Returns true when the engine consumed the key. The caller can then fall back to Compose's
     * directional focus search for screens that are not instrumented with [controllerFocusable].
     */
    fun dispatchKeyboardNavigation(direction: ControllerDirection): Boolean {
        // Keyboard navigation is intentional movement, so unlike dispatchEvent it must move the
        // selection on the very first press instead of only waking the engine up.
        val handled = ControllerFocusEngine.handleEvent(ControllerEvent.Navigate(direction))
        if (handled) {
            // Only switch into spatial-navigation mode when the engine actually consumed the key.
            // If it did not, Compose's own focus search runs and its focus ring should stay.
            notifyNavigationInput()
        }
        return handled
    }

    /** Routes Enter to the engine's primary (A) action. */
    fun dispatchKeyboardSelect(): Boolean {
        // Only activate when the user has already entered spatial navigation. Otherwise Enter on a
        // freshly opened screen would open the auto-selected item before its ring is ever shown.
        if (!_isNavigationActive.value) return false
        // Deferred routes and screens without controllerFocusable nodes (e.g. details) leave the
        // engine without a focused node. Returning false lets Compose's native Enter handling
        // activate the focused button instead of the engine swallowing the key.
        if (ControllerFocusEngine.deferNavigationToCompose) return false
        if (ControllerFocusEngine.focusedNodeId.value == null) return false
        notifyNavigationInput()
        return ControllerFocusEngine.handleEvent(ControllerEvent.ButtonPressed(XboxButton.A))
    }
}

