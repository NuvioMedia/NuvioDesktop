package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@Composable
internal expect fun CollectionCardRemoteImage(
    imageUrl: String,
    staticImageUrl: String? = null,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    animateIfPossible: Boolean = false,
    animateWithoutFocus: Boolean = false,
)
