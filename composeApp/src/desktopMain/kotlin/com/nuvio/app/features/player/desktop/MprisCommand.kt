package com.nuvio.app.features.player.desktop

internal sealed interface MprisCommand {
    data object Play : MprisCommand
    data object Pause : MprisCommand
    data object PlayPause : MprisCommand
    data object Stop : MprisCommand
    data object Next : MprisCommand
    data object Previous : MprisCommand
    data class Seek(val offsetUs: Long) : MprisCommand
    data class SetPosition(val positionUs: Long) : MprisCommand
    data class SetRate(val rate: Double) : MprisCommand
    data class SetVolume(val volume: Double) : MprisCommand
}
