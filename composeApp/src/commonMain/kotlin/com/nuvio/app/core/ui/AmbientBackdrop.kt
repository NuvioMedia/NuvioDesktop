package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val AMBIENT_SAMPLE_LIMIT = 80
private const val AMBIENT_CACHE_LIMIT = 16
private const val AMBIENT_TRANSITION_MS = 720

/**
 * A small set of colors sampled from the focused hero artwork.
 *
 * The palette intentionally carries three related tones so the ambient layer can
 * paint a rich, changing wash instead of a single flat dominant color.
 */
internal data class AmbientPalette(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
)

private val ambientPaletteCache = linkedMapOf<String, AmbientPalette>()

internal data class AmbientBackdropState(
    val palette: AmbientPalette,
    val image: ImageBitmap?,
)

/**
 * Loads the pre-blurred hero artwork and its extracted palette.
 *
 * Both are shared by the ambient glow and by the hero scrim so the whole
 * background can be tinted with the focused title's colors.
 */
@Composable
internal fun rememberAmbientBackdropState(
    imageUrl: String?,
    enabled: Boolean = true,
    onPaletteChange: ((AmbientPalette) -> Unit)? = null,
): AmbientBackdropState {
    val themePalette = MaterialTheme.themePalette
    val colorScheme = MaterialTheme.colorScheme
    val fallbackPalette = remember(themePalette.secondary, colorScheme.background, colorScheme.surface) {
        AmbientPalette(
            primary = colorScheme.background,
            secondary = colorScheme.surface,
            accent = themePalette.secondary,
        )
    }

    val loadedImage = rememberArtworkBackdrop(imageUrl = imageUrl, enabled = enabled)
    var displayedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var palette by remember { mutableStateOf(fallbackPalette) }

    // Keep the previous artwork visible while the next one is decoded so the
    // backdrop never flashes back to the app background during a hero swipe.
    LaunchedEffect(loadedImage) {
        if (loadedImage != null) displayedImage = loadedImage
    }

    LaunchedEffect(imageUrl, loadedImage, enabled) {
        if (!enabled || imageUrl.isNullOrBlank() || loadedImage == null) return@LaunchedEffect
        val cached = ambientPaletteCache[imageUrl]
        if (cached != null) {
            palette = cached
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.Default) {
            runCatching { loadedImage.extractAmbientPalette(fallbackPalette) }
                .getOrDefault(fallbackPalette)
        }
        ambientPaletteCache[imageUrl] = extracted
        while (ambientPaletteCache.size > AMBIENT_CACHE_LIMIT) {
            ambientPaletteCache.remove(ambientPaletteCache.keys.first())
        }
        palette = extracted
    }

    LaunchedEffect(palette) {
        onPaletteChange?.invoke(palette)
    }

    return AmbientBackdropState(palette = palette, image = displayedImage)
}

/**
 * Watched.cx-style atmospheric layer for the home hero.
 *
 * The bitmap is downloaded once at 128px and CPU-blurred by [rememberArtworkBackdrop],
 * then painted with a screen blend and a pair of animated radial glows. There is no
 * continuously running animation: work only happens when the focused title changes
 * (a crossfade plus three short color tweens), which keeps the effect cheap on every
 * platform while still feeling alive as the user swipes the hero carousel.
 */
@Composable
internal fun HeroAmbientBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    intensity: Float = 1f,
    onPaletteChange: ((AmbientPalette) -> Unit)? = null,
) {
    val state = rememberAmbientBackdropState(
        imageUrl = imageUrl,
        enabled = enabled,
        onPaletteChange = onPaletteChange,
    )
    val palette = state.palette

    val targetIntensity = intensity.coerceIn(0f, 1.5f)
    val primary by animateColorAsState(
        targetValue = palette.primary,
        animationSpec = tween(AMBIENT_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = "ambient_primary",
    )
    val secondary by animateColorAsState(
        targetValue = palette.secondary,
        animationSpec = tween(AMBIENT_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = "ambient_secondary",
    )
    val accent by animateColorAsState(
        targetValue = palette.accent,
        animationSpec = tween(AMBIENT_TRANSITION_MS, easing = FastOutSlowInEasing),
        label = "ambient_accent",
    )

    var currentArtwork by remember { mutableStateOf(state.image) }
    var previousArtwork by remember { mutableStateOf<ImageBitmap?>(null) }
    val artworkTransition = remember { Animatable(if (state.image != null) 1f else 0f) }

    LaunchedEffect(state.image) {
        val nextImage = state.image ?: return@LaunchedEffect
        if (currentArtwork == null) {
            currentArtwork = nextImage
            artworkTransition.snapTo(1f)
        } else if (nextImage !== currentArtwork) {
            previousArtwork = currentArtwork
            currentArtwork = nextImage
            artworkTransition.snapTo(0f)
            artworkTransition.animateTo(
                targetValue = 1f,
                animationSpec = tween(AMBIENT_TRANSITION_MS, easing = FastOutSlowInEasing),
            )
            previousArtwork = null
        }
    }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val contrastRamp = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Transparent,
                        0.38f to Color.Transparent,
                        0.68f to Color.Black.copy(alpha = 0.26f),
                        1.00f to Color.Black.copy(alpha = 0.50f),
                    ),
                    startY = 0f,
                    endY = size.height,
                )

                onDrawBehind {
                    if (size.width <= 0f || size.height <= 0f) return@onDrawBehind

                    // 1. Base vertical wash
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to primary.tone(0.84f * targetIntensity),
                                0.26f to secondary.tone(0.40f * targetIntensity),
                                0.55f to accent.tone(0.18f * targetIntensity),
                                1.00f to primary.tone(0.12f * targetIntensity),
                            ),
                            startY = 0f,
                            endY = size.height,
                        ),
                    )

                    // 2. Crossfaded artwork layers
                    val transitionProgress = artworkTransition.value
                    val baseArtworkAlpha = (0.48f * targetIntensity).coerceIn(0f, 1f)

                    val prev = previousArtwork
                    if (prev != null && transitionProgress < 1f) {
                        drawAmbientArtwork(
                            image = prev,
                            alpha = baseArtworkAlpha * (1f - transitionProgress),
                        )
                    }

                    val curr = currentArtwork
                    if (curr != null) {
                        drawAmbientArtwork(
                            image = curr,
                            alpha = baseArtworkAlpha * transitionProgress,
                        )
                    }

                    // 3. Radial ambient key & counter lights
                    val maxDim = size.maxDimension
                    // Warm/top-right key light
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.tone(0.62f * targetIntensity),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.78f, size.height * 0.16f),
                            radius = maxDim * 0.56f,
                        ),
                        blendMode = BlendMode.SrcOver,
                    )
                    // Cooler counter-light
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                secondary.tone(0.36f * targetIntensity),
                                Color.Transparent,
                            ),
                            center = Offset(size.width * 0.16f, size.height * 0.74f),
                            radius = maxDim * 0.52f,
                        ),
                        blendMode = BlendMode.SrcOver,
                    )

                    // 4. Contrast ramp for lower catalogue rows
                    drawRect(brush = contrastRamp)
                }
            },
    )
}

private fun DrawScope.drawAmbientArtwork(
    image: ImageBitmap,
    alpha: Float,
) {
    if (alpha <= 0f || size.width <= 0f || size.height <= 0f) return
    if (image.width <= 0 || image.height <= 0) return

    val scale = max(size.width / image.width, size.height / image.height) * 1.08f
    val drawWidth = image.width * scale
    val drawHeight = image.height * scale
    drawImage(
        image = image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(
            x = ((size.width - drawWidth) * 0.5f).roundToInt(),
            y = ((size.height - drawHeight) * 0.5f).roundToInt(),
        ),
        dstSize = IntSize(
            width = drawWidth.roundToInt(),
            height = drawHeight.roundToInt(),
        ),
        alpha = alpha.coerceIn(0f, 1f),
        blendMode = BlendMode.SrcOver,
        filterQuality = FilterQuality.Low,
    )
}

/**
 * Samples a small, pre-blurred bitmap on a coarse grid and derives a weighted
 * average plus the two most vivid colors. It runs on [Dispatchers.Default] and
 * the result is cached per URL, so scrolling the hero does not repeatedly cost
 * pixel work.
 */
internal fun ImageBitmap.extractAmbientPalette(fallback: AmbientPalette): AmbientPalette {
    if (width <= 0 || height <= 0) return fallback

    val pixels = toPixelMap()
    val step = max(1, max(pixels.width, pixels.height) / AMBIENT_SAMPLE_LIMIT)
    var sumRed = 0.0
    var sumGreen = 0.0
    var sumBlue = 0.0
    var sumWeight = 0.0
    var accent = fallback.accent
    var accentScore = -1.0
    var secondary = fallback.secondary
    var secondaryScore = -1.0

    var y = 0
    while (y < pixels.height) {
        var x = 0
        while (x < pixels.width) {
            val color = pixels[x, y]
            if (color.alpha > 0.08f) {
                val red = color.red
                val green = color.green
                val blue = color.blue
                val maxChannel = max(red, max(green, blue))
                val minChannel = min(red, min(green, blue))
                val saturation = if (maxChannel <= 0f) {
                    0f
                } else {
                    (maxChannel - minChannel) / maxChannel
                }
                val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
                val weight = (0.25 + saturation * 1.15 + luminance * 0.55).toDouble()
                sumRed += red * weight
                sumGreen += green * weight
                sumBlue += blue * weight
                sumWeight += weight

                if (luminance > 0.05f) {
                    val score = (saturation * 1.4 + luminance * 0.55).toDouble()
                    if (score > accentScore) {
                        secondaryScore = accentScore
                        secondary = accent
                        accentScore = score
                        accent = color
                    } else if (score > secondaryScore) {
                        secondaryScore = score
                        secondary = color
                    }
                }
            }
            x += step
        }
        y += step
    }

    if (sumWeight <= 0.0) return fallback

    val average = Color(
        red = (sumRed / sumWeight).toFloat().coerceIn(0f, 1f),
        green = (sumGreen / sumWeight).toFloat().coerceIn(0f, 1f),
        blue = (sumBlue / sumWeight).toFloat().coerceIn(0f, 1f),
        alpha = 1f,
    )
    // Dark artwork must still produce a visible atmospheric wash, so every tone is
    // pushed to a fixed HSV value (brightness) instead of inheriting the artwork's own
    // darkness. Uniform RGB scaling keeps the sampled hue intact.
    val vibrant = accent
        .boostAmbientSaturation(0.95f)
        .ensureAmbientChroma(fallback.accent, 0.22f)
        .ambientValue(0.62f)
    val support = secondary
        .boostAmbientSaturation(0.75f)
        .ensureAmbientChroma(fallback.accent, 0.18f)
        .ambientValue(0.48f)
    val base = average
        .boostAmbientSaturation(0.70f)
        .ensureAmbientChroma(fallback.accent, 0.16f)
        .ambientValue(0.34f)

    return AmbientPalette(
        primary = base,
        secondary = support.blendTowards(vibrant, 0.30f),
        accent = vibrant,
    )
}

/** Clamps a tint alpha so stacked layers can never overflow. */
private fun Color.tone(alpha: Float): Color = copy(alpha = alpha.coerceIn(0f, 1f))

/**
 * Rescales the color so its brightest channel sits at [value]. Uniform scaling keeps
 * hue and saturation while making brightness predictable, which is what keeps the
 * ambient layer visible even when the hero artwork is nearly black.
 */
private fun Color.ambientValue(value: Float): Color {
    val maxChannel = max(red, max(green, blue))
    if (maxChannel <= 0.002f) return Color(0.06f, 0.07f, 0.09f, 1f)
    val scale = value.coerceIn(0.05f, 1f) / maxChannel
    return Color(
        red = (red * scale).coerceIn(0f, 1f),
        green = (green * scale).coerceIn(0f, 1f),
        blue = (blue * scale).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

/**
 * Greys have no hue to amplify, so a mostly-monochrome sample is nudged toward the
 * theme accent. Without this a black-and-white artwork would yield a grey wash.
 */
private fun Color.ensureAmbientChroma(hint: Color, minSaturation: Float): Color {
    val maxChannel = max(red, max(green, blue))
    val minChannel = min(red, min(green, blue))
    val saturation = if (maxChannel <= 0f) 0f else (maxChannel - minChannel) / maxChannel
    if (saturation >= minSaturation) return this
    val blend = ((minSaturation - saturation) / minSaturation).coerceIn(0f, 1f) * 0.75f
    return blendTowards(hint, blend)
}

private fun Color.boostAmbientSaturation(amount: Float): Color {
    val luminance = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    return Color(
        red = (red + (red - luminance) * amount).coerceIn(0f, 1f),
        green = (green + (green - luminance) * amount).coerceIn(0f, 1f),
        blue = (blue + (blue - luminance) * amount).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

private fun Color.blendTowards(target: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (target.red - red) * clamped,
        green = green + (target.green - green) * clamped,
        blue = blue + (target.blue - blue) * clamped,
        alpha = 1f,
    )
}
