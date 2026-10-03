package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)

/**
 * Registry of active back handlers for platforms without a system back gesture
 * (desktop). The most recently registered enabled handler wins, mirroring
 * Android's BackHandler ordering.
 */
internal class PlatformBackRegistration(
    var enabled: Boolean,
    var onBack: () -> Unit,
)

internal object PlatformBackDispatcher {
    private val registrations = mutableListOf<PlatformBackRegistration>()

    fun register(registration: PlatformBackRegistration) {
        registrations.add(registration)
    }

    fun unregister(registration: PlatformBackRegistration) {
        registrations.remove(registration)
    }

    /** Returns true when an enabled handler consumed the back request. */
    fun dispatch(): Boolean {
        val target = registrations.lastOrNull { it.enabled } ?: return false
        target.onBack()
        return true
    }
}
