package com.nuvio.app.core.ui

import coil3.request.ImageRequest

internal actual fun ImageRequest.Builder.applyDesktopCardPrefetchOptions(
    animated: Boolean,
): ImageRequest.Builder = this
