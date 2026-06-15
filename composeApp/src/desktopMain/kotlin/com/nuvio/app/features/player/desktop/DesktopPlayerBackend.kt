package com.nuvio.app.features.player.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerResizeMode
import kotlinx.coroutines.flow.StateFlow

internal interface DesktopPlayerBackend {
    val id: String
    val backendName: String
    val controller: PlayerEngineController
    val state: StateFlow<DesktopPlayerState>

    suspend fun load(request: DesktopPlayerRequest)
    fun setResizeMode(resizeMode: PlayerResizeMode)
    fun releaseSoft()
    fun resetForReuse()
    fun close()

    @Composable
    fun Surface(modifier: Modifier)
}
