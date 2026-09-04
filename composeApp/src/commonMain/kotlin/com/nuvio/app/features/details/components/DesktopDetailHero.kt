package com.nuvio.app.features.details.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioDesktopImageScaling
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.DesktopBackdropVerticalBias
import com.nuvio.app.core.ui.StandardDesktopViewportAspectRatio
import com.nuvio.app.core.ui.FullscreenActionButton
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.fullscreenActionHorizontalInsetForWidth
import com.nuvio.app.core.ui.expandingWideArtworkWidthDp
import com.nuvio.app.core.ui.isFullscreenActionSupported
import com.nuvio.app.core.ui.WideDesktopViewportAspectRatio
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.formatRuntimeForDisplay
import com.nuvio.app.features.tmdb.originalTmdbImageUrl
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.detail_logo_content_description
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.hero_remove_from_library
import org.jetbrains.compose.resources.stringResource

@Composable
fun DesktopDetailBackdrop(
    meta: MetaDetails,
    viewportHeight: Dp,
    heroTrailerSourceUrl: String?,
    heroTrailerSourceAudioUrl: String?,
    heroTrailerReady: Boolean,
    heroTrailerPlayWhenReady: Boolean,
    heroTrailerMuted: Boolean,
    heroGradientColor: Color? = null,
    blurBackdrop: Boolean = false,
    onBackdropLoaded: (Painter) -> Unit = {},
    onHeroTrailerReady: () -> Unit,
    onHeroTrailerEnded: () -> Unit,
    onHeroTrailerError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val sideGradientColor = heroGradientColor ?: colorScheme.background
    val opacity = NuvioTokens.Opacity
    val trailerAlpha by animateFloatAsState(
        targetValue = if (heroTrailerReady) 1f else 0f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "desktop_detail_hero_trailer_alpha",
    )
    val gradientIntensity by animateFloatAsState(
        targetValue = if (heroTrailerReady) 0.3f else 1f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "desktop_detail_hero_gradient_intensity",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(sideGradientColor),
    ) {
        val aspectRatio = maxWidth.value / viewportHeight.value
        val useWideArtworkFrame = aspectRatio > StandardDesktopViewportAspectRatio
        val cropSideBackdrop = aspectRatio > WideDesktopViewportAspectRatio
        val backdropWidth = if (useWideArtworkFrame) {
            expandingWideArtworkWidthDp(maxWidth.value, viewportHeight.value).dp
        } else {
            maxWidth
        }
        val backdropModifier = Modifier
            .align(Alignment.CenterEnd)
            .width(backdropWidth)
            .fillMaxHeight()
        val artworkModifier = if (blurBackdrop) backdropModifier.blur(30.dp) else backdropModifier
        val bottomSpreadStrength = ((21f / 9f - aspectRatio) / (5f / 9f)).coerceIn(0f, 1f)
        val baseSideFade = Brush.horizontalGradient(
            colorStops = arrayOf(
                0.00f to sideGradientColor,
                0.12f to sideGradientColor.copy(alpha = 0.98f * gradientIntensity),
                0.34f to sideGradientColor.copy(alpha = opacity.overlayHeavy * gradientIntensity),
                0.62f to sideGradientColor.copy(alpha = opacity.overlayLight * gradientIntensity),
                0.86f to sideGradientColor.copy(alpha = opacity.subtle * gradientIntensity),
                1.00f to Color.Transparent,
            ),
        )
        val imageUrl = meta.background ?: meta.poster
        if (imageUrl != null) {
            AsyncImage(
                model = originalTmdbImageUrl(imageUrl),
                contentDescription = meta.name,
                modifier = artworkModifier,
                alignment = BiasAlignment(0f, DesktopBackdropVerticalBias),
                contentScale = if (useWideArtworkFrame && !cropSideBackdrop) ContentScale.Fit else ContentScale.Crop,
                desktopImageScaling = NuvioDesktopImageScaling.Disabled,
                onSuccess = { state -> onBackdropLoaded(state.painter) },
            )
        } else {
            DesktopStripePlaceholder(modifier = backdropModifier)
        }

        if (heroTrailerSourceUrl != null) {
            HeroTrailerPlayerSurface(
                sourceUrl = heroTrailerSourceUrl,
                sourceAudioUrl = heroTrailerSourceAudioUrl,
                playWhenReady = heroTrailerPlayWhenReady,
                muted = heroTrailerMuted,
                fillFrame = true,
                modifier = artworkModifier.graphicsLayer { alpha = trailerAlpha },
                onReady = onHeroTrailerReady,
                onEnded = onHeroTrailerEnded,
                onError = onHeroTrailerError,
            )
        }

        Box(
            modifier = backdropModifier
                .drawWithCache {
                    val bottomSpread = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to sideGradientColor.copy(alpha = 0.12f * gradientIntensity * bottomSpreadStrength),
                            0.55f to sideGradientColor.copy(alpha = 0.12f * gradientIntensity * bottomSpreadStrength),
                            1.00f to Color.Transparent,
                        ),
                        center = Offset(0f, size.height * 1.15f),
                        radius = size.width * 0.72f,
                    )
                    onDrawBehind {
                        drawRect(baseSideFade)
                        if (bottomSpreadStrength > 0f) drawRect(bottomSpread)
                    }
                },
        )
    }
}

@Composable
fun DesktopDetailHero(
    meta: MetaDetails,
    playButtonLabel: String,
    isSaved: Boolean,
    isWatched: Boolean,
    onHeightChanged: (Int) -> Unit,
    heroTrailerSourceUrl: String?,
    heroTrailerReady: Boolean,
    heroTrailerMuted: Boolean,
    onHeroTrailerMuteToggle: () -> Unit,
    onPlayClick: () -> Unit,
    onPlayLongClick: (() -> Unit)?,
    onWatchedClick: () -> Unit,
    onSaveClick: () -> Unit,
    onSaveLongClick: (() -> Unit)?,
) {
    val colorScheme = MaterialTheme.colorScheme
    val space = NuvioTokens.Space
    val trailerAlpha by animateFloatAsState(
        targetValue = if (heroTrailerReady) 1f else 0f,
        animationSpec = tween(durationMillis = NuvioTokens.Motion.sheetEnterMillis),
        label = "desktop_detail_hero_controls_alpha",
    )
    var logoLoadError by remember(meta.id, meta.logo) { mutableStateOf(false) }
    val logoUrl = meta.logo?.takeIf { it.isNotBlank() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(660.dp)
            .onSizeChanged { onHeightChanged(it.height) },
    ) {
        val actionHorizontalInset = fullscreenActionHorizontalInsetForWidth(maxWidth.value)
        val pageHorizontalPadding = desktopPageHorizontalPaddingForWidth(maxWidth.value)

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .widthIn(max = 760.dp)
                .padding(
                    start = pageHorizontalPadding,
                    end = space.s32,
                    bottom = space.s40,
                ),
        ) {
            if (logoUrl != null && !logoLoadError) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = stringResource(Res.string.detail_logo_content_description, meta.name),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .height(120.dp),
                    alignment = Alignment.CenterStart,
                    contentScale = ContentScale.Fit,
                    onError = { logoLoadError = true },
                )
            } else {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = NuvioTokens.Type.displayMd,
                        lineHeight = NuvioTokens.LineHeight.displayMd,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(space.s20))
            DesktopHeroMetaRow(meta = meta)
            if (meta.externalRatings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(space.s12))
                DetailRatingsRow(
                    ratings = meta.externalRatings,
                    modifier = Modifier.widthIn(max = 520.dp),
                )
            }
            if (meta.genres.isNotEmpty()) {
                Spacer(modifier = Modifier.height(space.s12))
                Text(
                    text = meta.genres.take(4).joinToString(" \u2022 "),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = NuvioTokens.Type.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            meta.description?.takeIf { it.isNotBlank() }?.let { synopsis ->
                Spacer(modifier = Modifier.height(space.s16))
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = NuvioTokens.Type.bodyLg,
                        lineHeight = NuvioTokens.LineHeight.bodyLg,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(space.s28))
            DetailActionButtons(
                modifier = Modifier.widthIn(max = 520.dp),
                playLabel = playButtonLabel,
                secondaryActions = listOf(
                    DetailSecondaryAction(
                        label = if (isWatched) {
                            stringResource(Res.string.hero_mark_unwatched)
                        } else {
                            stringResource(Res.string.hero_mark_watched)
                        },
                        icon = if (isWatched) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.CheckCircleOutline
                        },
                        isActive = isWatched,
                        onClick = onWatchedClick,
                    ),
                    DetailSecondaryAction(
                        label = if (isSaved) {
                            stringResource(Res.string.hero_remove_from_library)
                        } else {
                            stringResource(Res.string.hero_add_to_library)
                        },
                        icon = if (isSaved) {
                            Icons.Default.Check
                        } else {
                            Icons.Default.Add
                        },
                        isActive = isSaved,
                        onClick = onSaveClick,
                        onLongClick = onSaveLongClick,
                    ),
                ),
                isTablet = true,
                onPlayClick = onPlayClick,
                onPlayLongClick = onPlayLongClick,
            )
        }

        if (heroTrailerSourceUrl != null) {
            Surface(
                onClick = onHeroTrailerMuteToggle,
                enabled = heroTrailerReady,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = space.s32,
                        end = actionHorizontalInset + if (isFullscreenActionSupported) 60.dp else 0.dp,
                    )
                    .size(48.dp)
                    .graphicsLayer { alpha = trailerAlpha },
                shape = CircleShape,
                color = colorScheme.surfaceVariant.copy(alpha = 0.82f),
                contentColor = colorScheme.onSurface,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (heroTrailerMuted) {
                            Icons.AutoMirrored.Rounded.VolumeOff
                        } else {
                            Icons.AutoMirrored.Rounded.VolumeUp
                        },
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        if (isFullscreenActionSupported) {
            FullscreenActionButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = space.s32, end = actionHorizontalInset),
                buttonSize = 48.dp,
                iconSize = 24.dp,
                containerColor = colorScheme.surfaceVariant.copy(alpha = 0.82f),
                contentColor = colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DesktopHeroMetaRow(meta: MetaDetails) {
    val colorScheme = MaterialTheme.colorScheme
    val space = NuvioTokens.Space
    val opacity = NuvioTokens.Opacity
    val metaItems = buildList {
        desktopYearLabel(meta)?.let(::add)
        desktopSeasonCountLabel(meta)?.let(::add)
        formatRuntimeForDisplay(meta.runtime)?.let(::add)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.s16),
    ) {
        metaItems.forEach { item ->
            Text(
                text = item,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = NuvioTokens.Type.bodyLg,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = NuvioTokens.LetterSpacing.none,
                ),
                color = colorScheme.onBackground,
                maxLines = 1,
            )
        }
        meta.ageRating?.takeIf { it.isNotBlank() }?.let { rating ->
            Box(
                modifier = Modifier
                    .border(
                        NuvioTokens.Border.thin,
                        colorScheme.onBackground.copy(alpha = opacity.overlayLight),
                        RoundedCornerShape(NuvioTokens.Radius.sm),
                    )
                    .padding(horizontal = space.s8, vertical = space.s2),
            ) {
                Text(
                    text = rating,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = NuvioTokens.Type.bodySm,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = NuvioTokens.LetterSpacing.none,
                    ),
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
