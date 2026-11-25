package com.nuvio.app.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.Dp
import com.nuvio.app.controller.controllerFocusable
import com.nuvio.app.isDesktop

internal val LocalPosterClickAnchor = staticCompositionLocalOf<((PosterZoomAnchor) -> Unit)?> { null }

private var nextPosterCardFocusId = 1L

internal class PosterLiftSource(
    private val graphicsContext: GraphicsContext,
    private val eagerLayerRecording: Boolean = true,
) : RememberObserver {
    private var backingLayer: GraphicsLayer? = if (eagerLayerRecording) {
        graphicsContext.createGraphicsLayer()
    } else {
        null
    }
    private var coordinates: LayoutCoordinates? = null
    private var cachedBounds: Rect? = null

    val layer: GraphicsLayer
        get() = backingLayer ?: graphicsContext.createGraphicsLayer().also { backingLayer = it }

    internal val layerOrNull: GraphicsLayer?
        get() = backingLayer

    val bounds: Rect?
        get() {
            val attached = coordinates?.takeIf { it.isAttached } ?: return cachedBounds
            val position = attached.positionInRoot()
            return Rect(
                left = position.x,
                top = position.y,
                right = position.x + attached.size.width,
                bottom = position.y + attached.size.height,
            ).also { cachedBounds = it }
        }

    var isLifted by mutableStateOf(false)
        private set
    private var rememberCount = 0
    private var captureRequestRevision by mutableIntStateOf(0)
    private var lastRecordedRevision = 0
    private var recordedRevision by mutableIntStateOf(0)

    internal fun layerForRecording(): GraphicsLayer {
        val current = backingLayer
        if (current != null && !current.isReleased) return current
        return graphicsContext.createGraphicsLayer().also { backingLayer = it }
    }

    internal fun liftedLayerForDraw(): GraphicsLayer? {
        recordedRevision
        return backingLayer?.takeIf { !it.isReleased && it.size.width > 0 && it.size.height > 0 }
    }

    internal fun requestLiftCapture() {
        if (captureRequestRevision <= lastRecordedRevision) {
            captureRequestRevision = lastRecordedRevision + 1
        }
    }

    internal val needsLiftCapture: Boolean
        get() = captureRequestRevision > lastRecordedRevision

    internal fun markRecorded() {
        if (captureRequestRevision > lastRecordedRevision) {
            lastRecordedRevision = captureRequestRevision
            Snapshot.withoutReadObservation {
                recordedRevision = lastRecordedRevision
            }
        }
    }

    val canLift: Boolean
        get() = backingLayer?.let {
            rememberCount > 0 && !it.isReleased && it.size.width > 0 && it.size.height > 0
        } == true

    val isLiftCaptureReady: Boolean
        get() = canLift && recordedRevision >= captureRequestRevision

    fun updateCoordinates(layoutCoordinates: LayoutCoordinates) {
        coordinates = layoutCoordinates
    }

    fun lift(): Boolean {
        if (!canLift) return false
        isLifted = true
        return true
    }

    fun land() {
        isLifted = false
        if (rememberCount == 0) release()
    }

    override fun onRemembered() {
        rememberCount++
    }

    override fun onForgotten() {
        rememberCount--
        if (rememberCount == 0) {
            coordinates = null
            if (!isLifted) release()
        }
    }

    override fun onAbandoned() {
        if (rememberCount == 0) {
            coordinates = null
            if (!isLifted) release()
        }
    }

    private fun release() {
        val currentLayer = backingLayer ?: return
        if (!currentLayer.isReleased) graphicsContext.releaseGraphicsLayer(currentLayer)
        backingLayer = null
        if (captureRequestRevision != 0) captureRequestRevision = 0
        lastRecordedRevision = 0
        if (recordedRevision != 0) recordedRevision = 0
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.posterCardClickable(
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    zoomImageUrl: String? = null,
    zoomCornerRadius: Dp = NuvioTokens.Radius.poster,
    hoverScaleEnabled: Boolean = true,
    focusId: String? = null,
): Modifier {
    val graphicsContext = LocalGraphicsContext.current
    val onPosterClickAnchor = LocalPosterClickAnchor.current
    val eagerLayerRecording = onPosterClickAnchor != null
    val source = remember(graphicsContext, zoomImageUrl, eagerLayerRecording) {
        PosterLiftSource(graphicsContext, eagerLayerRecording = eagerLayerRecording)
    }
    val controllerFocusId = remember(focusId, zoomImageUrl) {
        focusId ?: zoomImageUrl?.takeIf { it.isNotBlank() }?.let { "poster_${it.hashCode()}" }
            ?: "poster_card_${nextPosterCardFocusId++}"
    }
    val posterModifier = Modifier
        .then(
            if (eagerLayerRecording || onLongClick != null) {
                Modifier.drawWithContent {
                    if (eagerLayerRecording || source.needsLiftCapture) {
                        val recordLayer = source.layerForRecording()
                        recordLayer.record { this@drawWithContent.drawContent() }
                        if (recordLayer.size.width > 0 && recordLayer.size.height > 0) {
                            source.markRecorded()
                        }
                        if (!source.isLifted) drawLayer(recordLayer)
                    } else if (!source.isLifted) {
                        drawContent()
                    }
                }
            } else {
                Modifier
            },
        )
        .onGloballyPositioned { coordinates ->
            source.updateCoordinates(coordinates)
        }
        .then(this)
    if (onClick == null && onLongClick == null) return posterModifier
    val interactionSource = remember { MutableInteractionSource() }
    val handleClick: () -> Unit = {
        if (onClick != null) {
            source.bounds?.let { bounds ->
                onPosterClickAnchor?.invoke(
                    PosterZoomAnchor(bounds, zoomImageUrl, zoomCornerRadius).also { it.source = source },
                )
            }
            onClick()
        }
    }
    val handleLongClick = onLongClick?.let { longClick ->
        {
            source.bounds?.let { cardBounds ->
                PosterZoomAnchorHolder.stash(
                    PosterZoomAnchor(cardBounds, zoomImageUrl, zoomCornerRadius).also {
                        it.source = source
                    },
                )
            }
            longClick()
        }
    }
    return Modifier
        .desktopPosterHoverScale(
            enabled = hoverScaleEnabled,
            interactionSource = interactionSource,
            attachHoverable = false,
        )
        .then(posterModifier)
        .then(
            if (isDesktop) {
                Modifier.controllerFocusable(
                    id = controllerFocusId,
                    enabled = onClick != null,
                    cornerRadius = zoomCornerRadius,
                    focusScale = 1.07f,
                    selectLabel = "Open",
                    secondaryActionLabel = if (handleLongClick != null) "Options" else null,
                    onSecondaryAction = handleLongClick,
                    // The same title can appear in several shelves; keep one focus node per shelf
                    // so the ring never lights up in two rows at once.
                    dedupeByGroup = true,
                    onSelect = handleClick,
                )
            } else {
                Modifier
            },
        )
        .combinedClickable(
            interactionSource = interactionSource,
            indication = if (!isDesktop && onPosterClickAnchor == null) LocalIndication.current else null,
            onClick = handleClick,
            onLongClick = handleLongClick,
        )
        .secondaryClick(handleLongClick)
}

internal fun DrawScope.drawLiftedPoster(source: PosterLiftSource) {
    val layer = source.liftedLayerForDraw() ?: return
    scale(
        scaleX = size.width / layer.size.width,
        scaleY = size.height / layer.size.height,
        pivot = Offset.Zero,
    ) {
        drawLayer(layer)
    }
}
