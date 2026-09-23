package com.nuvio.app.features.p2p

import co.touchlab.kermit.Logger
import com.nuvio.engine.internal.NuvioEngineLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

actual object P2pStreamingEngine {
    private val log = Logger.withTag("P2pStreamingEngine")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()

    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    actual val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    private var active: DesktopP2pBackend? = null
    private var mirrorJobs: List<Job> = emptyList()

    init {
        scope.launch {
            P2pSettingsRepository.ensureLoaded()
            selectBackend()
            P2pSettingsRepository.uiState.collect {
                selectBackend()
            }
        }
    }

    val nuvioEngineAvailable: Boolean
        get() = NuvioEngineLibrary.isAvailable

    actual suspend fun startStream(request: P2pStreamRequest): String =
        selectBackend().startStream(request)

    actual suspend fun clearCache(): P2pCacheClearResult =
        selectBackend().clearCache()

    actual fun stopStream() {
        val backend = synchronized(lock) { active }
        backend?.stopStream()
    }

    actual fun shutdown() {
        NuvioEngineP2pBackend.shutdown()
        TorrServerP2pBackend.shutdown()
    }

    private fun selectBackend(): DesktopP2pBackend {
        P2pSettingsRepository.ensureLoaded()
        val wanted = resolveWantedBackend()
        val backend = when (wanted) {
            P2pEngineBackend.NUVIO_ENGINE -> NuvioEngineP2pBackend
            P2pEngineBackend.TORRSERVER -> TorrServerP2pBackend
        }

        val previous = synchronized(lock) {
            if (active === backend) {
                return backend
            }
            val old = active
            active = backend
            mirrorJobs.forEach { it.cancel() }
            mirrorJobs = listOf(
                scope.launch {
                    backend.state.collect { _state.value = it }
                },
                scope.launch {
                    backend.cacheState.collect { _cacheState.value = it }
                }
            )
            old
        }

        previous?.stopStream()
        log.i {
            "P2P backend: $wanted" + if (previous != null) " (switched from ${previous::class.simpleName})" else ""
        }
        return backend
    }

    private fun resolveWantedBackend(): P2pEngineBackend {
        val override = System.getProperty("nuvio.p2p.backend")
            ?.trim()
            ?.lowercase(Locale.ROOT)

        val configured = if (override != null) {
            when (override) {
                "nuvio", "nuvioengine", "nuvio_engine" -> P2pEngineBackend.NUVIO_ENGINE
                "torrserver" -> P2pEngineBackend.TORRSERVER
                else -> P2pEngineBackend.NUVIO_ENGINE
            }
        } else {
            P2pSettingsRepository.uiState.value.engineBackend
        }

        if (configured == P2pEngineBackend.NUVIO_ENGINE && !NuvioEngineLibrary.isAvailable) {
            log.w { "Nuvio Engine selected but nuvio_engine.dll is not available; using TorrServer" }
            return P2pEngineBackend.TORRSERVER
        }
        return configured
    }
}
