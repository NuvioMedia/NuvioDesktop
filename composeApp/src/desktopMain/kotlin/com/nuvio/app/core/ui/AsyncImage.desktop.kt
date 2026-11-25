package com.nuvio.app.core.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.NullRequestDataException
import coil3.size.Precision
import coil3.size.Scale

private val AlwaysPlaying: () -> Boolean = { true }
private val NeverPlaying: () -> Boolean = { false }
private val NeverHovered: State<Boolean> = mutableStateOf(false)
private const val DesktopHeroMaxDecodeSizePx = 2048

@Composable
internal actual fun NuvioAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier,
    placeholder: Painter?,
    error: Painter?,
    fallback: Painter?,
    onLoading: ((AsyncImagePainter.State.Loading) -> Unit)?,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)?,
    onError: ((AsyncImagePainter.State.Error) -> Unit)?,
    alignment: Alignment,
    contentScale: ContentScale,
    alpha: Float,
    colorFilter: ColorFilter?,
    filterQuality: FilterQuality?,
    clipToBounds: Boolean,
    desktopImageScaling: NuvioDesktopImageScaling,
    imageLoader: ImageLoader?,
    desktopAnimation: NuvioDesktopAnimation,
) {
    // Hover tracking only exists for On hover, so the common case pays nothing for it.
    val hoverSource = if (desktopAnimation == NuvioDesktopAnimation.OnHover) {
        remember { MutableInteractionSource() }
    } else {
        null
    }
    val hovered: State<Boolean> = hoverSource?.collectIsHoveredAsState() ?: NeverHovered
    val playing: () -> Boolean = when (desktopAnimation) {
        NuvioDesktopAnimation.Always -> AlwaysPlaying
        NuvioDesktopAnimation.OnHover -> remember(hovered) { { hovered.value } }
        NuvioDesktopAnimation.Off -> NeverPlaying
    }
    val imageModifier =
        if (hoverSource != null) modifier.hoverable(hoverSource) else modifier
    val animated = desktopAnimation != NuvioDesktopAnimation.Off

    val context = LocalPlatformContext.current
    val requestModel = remember(context, model, desktopImageScaling, animated) {
        model.withDesktopImageOptions(
            context = context,
            desktopImageScaling = desktopImageScaling,
            animated = animated,
        )
    }
    val transform: (AsyncImagePainter.State) -> AsyncImagePainter.State = remember(
        placeholder,
        error,
        fallback,
        playing,
    ) {
        transform@{ state ->
            when (state) {
                is AsyncImagePainter.State.Loading -> {
                    placeholder?.let { state.copy(painter = it) } ?: state
                }
                is AsyncImagePainter.State.Success -> {
                    val image = state.result.image
                    if (image is AnimatedSkiaImage) {
                        return@transform state.copy(painter = image.painter(playing))
                    }
                    state
                }
                is AsyncImagePainter.State.Error -> {
                    val fallbackPainter = if (state.result.throwable is NullRequestDataException) {
                        fallback ?: error
                    } else {
                        error
                    }
                    fallbackPainter?.let { state.copy(painter = it) } ?: state
                }
                AsyncImagePainter.State.Empty -> state
            }
        }
    }
    val onState: ((AsyncImagePainter.State) -> Unit)? = remember(onLoading, onSuccess, onError) {
        if (onLoading == null && onSuccess == null && onError == null) {
            null
        } else {
            { state ->
                when (state) {
                    is AsyncImagePainter.State.Loading -> onLoading?.invoke(state)
                    is AsyncImagePainter.State.Success -> onSuccess?.invoke(state)
                    is AsyncImagePainter.State.Error -> onError?.invoke(state)
                    AsyncImagePainter.State.Empty -> Unit
                }
            }
        }
    }

    AsyncImage(
        model = requestModel,
        imageLoader = imageLoader ?: SingletonImageLoader.get(context),
        contentDescription = contentDescription,
        modifier = imageModifier,
        transform = transform,
        onState = onState,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality ?: FilterQuality.Medium,
        clipToBounds = clipToBounds,
    )
}

/**
 * Prepares the request. Animated playback is opt-in; the memory-cache key records it so an animated and a
 * still copy of one URL never share an entry.
 */
private fun Any?.withDesktopImageOptions(
    context: PlatformContext,
    desktopImageScaling: NuvioDesktopImageScaling,
    animated: Boolean,
): Any? {
    if (this == null) return null

    val builder = if (this is ImageRequest) {
        newBuilder()
    } else {
        ImageRequest.Builder(context).data(this)
    }
    builder.apply {
        // Use Scale.FIT so the target bounds the LONG edge (640px for cards, max 2048px for
        // backdrops/Auto, unresized when Disabled) instead of Scale.FILL exploding card RAM or
        // uploading uncompressed 4K 33MB textures on scroll.
        when (desktopImageScaling) {
            NuvioDesktopImageScaling.Card -> {
                size(DesktopCardDecodeSizePx)
                scale(Scale.FIT)
                precision(Precision.INEXACT)
            }
            NuvioDesktopImageScaling.Auto -> {
                size(DesktopHeroMaxDecodeSizePx)
                scale(Scale.FIT)
                precision(Precision.INEXACT)
            }
            NuvioDesktopImageScaling.Disabled -> Unit
        }
        if (animated) {
            extras[NuvioAnimatedImageKey] = true
            memoryCacheKeyExtra("nuvio.animated", "true")
        }
    }
    return builder.build()
}
