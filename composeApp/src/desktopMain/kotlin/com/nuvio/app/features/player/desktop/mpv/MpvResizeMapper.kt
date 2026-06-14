package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.features.player.PlayerResizeMode
import org.openani.mediamp.mpv.MPVHandle

internal fun MPVHandle.applyResizeMode(resizeMode: PlayerResizeMode) {
    when (resizeMode) {
        PlayerResizeMode.Fit -> {
            setMpvProperty("keepaspect", true)
            setMpvProperty("panscan", 0.0)
        }
        PlayerResizeMode.Fill -> {
            setMpvProperty("keepaspect", false)
            setMpvProperty("panscan", 0.0)
        }
        PlayerResizeMode.Zoom -> {
            setMpvProperty("keepaspect", true)
            setMpvProperty("panscan", 1.0)
        }
    }
}
