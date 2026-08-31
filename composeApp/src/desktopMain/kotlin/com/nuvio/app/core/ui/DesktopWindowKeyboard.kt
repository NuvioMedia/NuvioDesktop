package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

internal enum class DesktopWindowNavigationKey {
    Escape,
    Backspace,
    Other,
}

internal enum class DesktopWindowKeyAction {
    NavigateBack,
    NavigateHistoryBack,
    None,
}

internal fun resolveDesktopWindowKeyAction(
    key: DesktopWindowNavigationKey,
    isKeyDown: Boolean,
    hasModifier: Boolean,
    canNavigateBack: Boolean,
): DesktopWindowKeyAction {
    if (!isKeyDown || hasModifier) return DesktopWindowKeyAction.None
    if (!canNavigateBack) return DesktopWindowKeyAction.None
    return when (key) {
        DesktopWindowNavigationKey.Escape -> DesktopWindowKeyAction.NavigateBack
        DesktopWindowNavigationKey.Backspace -> DesktopWindowKeyAction.NavigateHistoryBack
        DesktopWindowNavigationKey.Other -> DesktopWindowKeyAction.None
    }
}

internal fun handleDesktopWindowKeyEvent(event: KeyEvent): Boolean {
    val navigationKey = when (event.key) {
        Key.Escape -> DesktopWindowNavigationKey.Escape
        Key.Backspace -> DesktopWindowNavigationKey.Backspace
        else -> DesktopWindowNavigationKey.Other
    }
    val action = resolveDesktopWindowKeyAction(
        key = navigationKey,
        isKeyDown = event.type == KeyEventType.KeyDown,
        hasModifier = event.isAltPressed ||
            event.isCtrlPressed ||
            event.isMetaPressed ||
            event.isShiftPressed,
        canNavigateBack = DesktopBackHandlerDispatcher.hasEnabledHandler(),
    )
    return when (action) {
        DesktopWindowKeyAction.NavigateBack -> DesktopBackHandlerDispatcher.dispatchBack()
        DesktopWindowKeyAction.NavigateHistoryBack ->
            DesktopBackHandlerDispatcher.dispatchBack(useHistory = true)
        DesktopWindowKeyAction.None -> false
    }
}
