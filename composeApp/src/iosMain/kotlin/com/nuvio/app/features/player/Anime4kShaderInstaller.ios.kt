package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object Anime4kShaderInstaller {
    private val mutableState = MutableStateFlow(Anime4kInstallState(status = Anime4kInstallStatus.Unavailable))

    actual val isSupported: Boolean = false
    actual val state: StateFlow<Anime4kInstallState> = mutableState.asStateFlow()

    actual fun refresh() {
        mutableState.value = Anime4kInstallState(status = Anime4kInstallStatus.Unavailable)
    }

    actual fun install(force: Boolean) {
        mutableState.value = Anime4kInstallState(status = Anime4kInstallStatus.Unavailable)
    }
}
