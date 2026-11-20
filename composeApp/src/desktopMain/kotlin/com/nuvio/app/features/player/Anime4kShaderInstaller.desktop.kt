package com.nuvio.app.features.player

import com.nuvio.app.features.player.desktop.DesktopAnime4kShaders
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

actual object Anime4kShaderInstaller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DesktopAnime4kShaders.verify())
    private val installing = AtomicBoolean(false)

    actual val isSupported: Boolean
        get() = DesktopHostOs.current == DesktopHostOs.WINDOWS

    actual val state: StateFlow<Anime4kInstallState> = mutableState.asStateFlow()

    actual fun refresh() {
        if (!isSupported) {
            mutableState.value = Anime4kInstallState(status = Anime4kInstallStatus.Unavailable)
            return
        }
        mutableState.value = runCatching {
            DesktopAnime4kShaders.verify(DesktopAnime4kShaders.refreshInstallDir())
        }.getOrElse { error ->
            Anime4kInstallState(
                status = Anime4kInstallStatus.Error,
                installPath = DesktopAnime4kShaders.installDir.absolutePath,
                version = DesktopAnime4kShaders.VERSION,
                error = error.message ?: error.javaClass.simpleName,
                message = "Unable to read the shader installation.",
            )
        }
    }

    actual fun install(force: Boolean) {
        if (!isSupported) {
            mutableState.value = Anime4kInstallState(status = Anime4kInstallStatus.Unavailable)
            return
        }
        if (!installing.compareAndSet(false, true)) return
        scope.launch {
            try {
                DesktopAnime4kShaders.install(force) { next -> mutableState.value = next }
            } catch (error: Throwable) {
                mutableState.value = Anime4kInstallState(
                    status = Anime4kInstallStatus.Error,
                    installPath = DesktopAnime4kShaders.installDir.absolutePath,
                    version = DesktopAnime4kShaders.VERSION,
                    error = error.message ?: error.javaClass.simpleName,
                    message = "Installation failed.",
                )
            } finally {
                installing.set(false)
            }
        }
    }
}
