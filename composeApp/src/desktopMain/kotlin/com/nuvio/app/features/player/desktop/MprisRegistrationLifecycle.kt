package com.nuvio.app.features.player.desktop

internal class MprisRegistrationLifecycle<T>(
    private val onRegister: () -> Unit,
    private val onUnregister: () -> Unit,
    private val onUpdateMetadata: (T) -> Unit,
    private val onClear: () -> Unit,
) {
    private val lock = Any()
    private var registered = false
    private var metadata: T? = null

    fun register() {
        synchronized(lock) {
            onRegister()
            registered = true
            metadata?.let(onUpdateMetadata)
        }
    }

    fun unregister() {
        synchronized(lock) {
            if (registered) onUnregister()
            registered = false
            metadata = null
        }
    }

    fun updateMetadata(value: T) {
        synchronized(lock) {
            metadata = value
            if (registered) onUpdateMetadata(value)
        }
    }

    fun clear() {
        synchronized(lock) {
            metadata = null
            if (registered) onClear()
        }
    }
}
