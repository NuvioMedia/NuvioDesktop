package com.nuvio.app.features.player

enum class DesktopBufferPreset(val label: String, val description: String) {
    Metered("Metered", "Smallest buffer, and pausing stops the download instead of filling the buffer. For capped or metered connections; high-bitrate files may stall."),
    LowData("Low Data", "Minimizes network and memory use with a short playback buffer."),
    Balanced("Balanced", "Keeps a moderate buffer for reliable playback without excessive read-ahead."),
    Resilient("Resilient", "Uses a large buffer for unstable or high-latency connections.");
}
