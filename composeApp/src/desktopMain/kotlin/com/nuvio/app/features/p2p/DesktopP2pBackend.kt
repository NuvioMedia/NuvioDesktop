package com.nuvio.app.features.p2p

import kotlinx.coroutines.flow.StateFlow

interface DesktopP2pBackend {
    val state: StateFlow<P2pStreamingState>
    val cacheState: StateFlow<P2pCacheUiState>

    suspend fun startStream(request: P2pStreamRequest): String
    suspend fun clearCache(): P2pCacheClearResult
    fun stopStream()
    fun shutdown()
}
