package com.nuvio.app.core.ui

import coil3.request.ImageRequest

internal expect fun ImageRequest.Builder.applyDesktopCardPrefetchOptions(
    animated: Boolean = false,
): ImageRequest.Builder
