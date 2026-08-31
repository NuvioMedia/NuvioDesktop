package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

internal object DesktopBackHandlerDispatcher {
    private data class Registration(
        val isEnabled: () -> Boolean,
        val onBack: () -> Unit,
        val onHistoryBack: () -> Unit,
    )

    private val registrations = mutableListOf<Registration>()

    fun register(
        isEnabled: () -> Boolean,
        onBack: () -> Unit,
        onHistoryBack: () -> Unit,
    ): () -> Unit {
        val registration = Registration(
            isEnabled = isEnabled,
            onBack = onBack,
            onHistoryBack = onHistoryBack,
        )
        registrations += registration
        return { registrations.remove(registration) }
    }

    fun hasEnabledHandler(): Boolean =
        registrations.asReversed().any { it.isEnabled() }

    fun dispatchBack(useHistory: Boolean = false): Boolean {
        val registration = registrations.asReversed().firstOrNull { it.isEnabled() }
            ?: return false
        if (useHistory) registration.onHistoryBack() else registration.onBack()
        return true
    }
}

@Composable
actual fun PlatformBackHandler(
    enabled: Boolean,
    onHistoryBack: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val currentEnabled = rememberUpdatedState(enabled)
    val currentOnBack = rememberUpdatedState(onBack)
    val currentOnHistoryBack = rememberUpdatedState(onHistoryBack)

    DisposableEffect(Unit) {
        val unregister = DesktopBackHandlerDispatcher.register(
            isEnabled = { currentEnabled.value },
            onBack = { currentOnBack.value() },
            onHistoryBack = {
                currentOnHistoryBack.value?.invoke() ?: currentOnBack.value()
            },
        )
        onDispose(unregister)
    }
}
