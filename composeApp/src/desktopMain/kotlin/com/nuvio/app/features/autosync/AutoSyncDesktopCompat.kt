package com.nuvio.app.features.autosync

internal object SystemClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}

internal object C {
    const val SELECTION_FLAG_FORCED = 2
    const val ROLE_FLAG_COMMENTARY = 8
    const val ROLE_FLAG_SUBTITLE = 128
    const val ROLE_FLAG_DESCRIBES_VIDEO = 512
    const val ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND = 1024
    const val ROLE_FLAG_TRANSCRIBES_DIALOG = 4096
}
