package com.nuvio.app.core.ui

import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale

private const val DesktopCardPrefetchSizePx = 640

internal actual fun ImageRequest.Builder.applyDesktopCardPrefetchOptions(
    animated: Boolean,
): ImageRequest.Builder = apply {
    size(DesktopCardPrefetchSizePx)
    scale(Scale.FIT)
    precision(Precision.INEXACT)
    if (animated) {
        extras[NuvioAnimatedImageKey] = true
        memoryCacheKeyExtra("nuvio.animated", "true")
    }
}
