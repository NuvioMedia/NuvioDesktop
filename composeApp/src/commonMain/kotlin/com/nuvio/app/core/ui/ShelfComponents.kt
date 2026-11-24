package com.nuvio.app.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_view_all
import nuvio.composeapp.generated.resources.poster_logo_content_description
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

enum class NuvioPosterShape {
    Poster,
    Square,
    Landscape,
}

enum class NuvioViewAllPillSize {
    Default,
    Compact,
}

/** Stable per-row group key so spatial Up/Down can jump between shelves predictably. */
private var nextShelfGroupId = 1L

@Composable
fun <T> NuvioShelfSection(
    title: String,
    entries: List<T>,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    headerHorizontalPadding: Dp = 0.dp,
    rowContentPadding: PaddingValues = PaddingValues(0.dp),
    itemSpacing: Dp = 10.dp,
    onViewAllClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    viewAllPillSize: NuvioViewAllPillSize = NuvioViewAllPillSize.Default,
    key: ((T) -> Any)? = null,
    animatePlacement: Boolean = false,
    state: LazyListState = rememberLazyListState(),
    itemContent: @Composable (T) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val duplicateSafeEntries = remember(entries, key) {
        key?.let { entries.withDuplicateSafeLazyKeys(it) }
    }
    val shelfGroupKey = rememberSaveable { "shelf_${nextShelfGroupId++}" }

    ScreenActivityEffect(state) { active ->
        if (!active) state.stopScroll()
    }
    com.nuvio.app.controller.ProvideControllerRowLazyListState(state) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.nuvio.app.controller.LocalControllerGroupKey provides shelfGroupKey,
        ) {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap + NuvioTokens.Space.s2),
            ) {
                if (title.isNotBlank()) {
                    NuvioShelfSectionHeader(
                        title = title,
                        modifier = Modifier.padding(horizontal = headerHorizontalPadding),
                        onViewAllClick = onViewAllClick,
                        onTitleClick = onTitleClick,
                        viewAllPillSize = viewAllPillSize,
                    )
                }
                LazyRow(
                    state = state,
                    modifier = rowModifier
                        .nuvioHorizontalScrollBleed(LocalVerticalBarPadding.current)
                        .nuvioDesktopDragScroll(state),
                    contentPadding = rowContentPadding.withVerticalBar(),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                ) {
                    if (duplicateSafeEntries != null) {
                        itemsIndexed(
                            items = duplicateSafeEntries,
                            key = { _, entry -> entry.lazyKey },
                            contentType = { _, _ -> "poster" },
                        ) { _, keyedEntry ->
                            if (animatePlacement) {
                                Box(modifier = Modifier.animateItem()) { itemContent(keyedEntry.value) }
                            } else {
                                itemContent(keyedEntry.value)
                            }
                        }
                    } else {
                        itemsIndexed(
                            items = entries,
                            contentType = { _, _ -> "poster" },
                        ) { _, entry ->
                            if (animatePlacement) {
                                Box(modifier = Modifier.animateItem()) { itemContent(entry) }
                            } else {
                                itemContent(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val DesktopDragScrollTouchSlop = 8.dp
private val DesktopDragScrollMinFlingVelocity = 50.dp
private val DesktopDragScrollMaxFlingVelocity = 8000.dp

private const val DesktopDragStopThresholdMs = 40L
private const val DesktopDragVelocityWindowMs = 100L
private const val DesktopDragDirectionJitterPx = 4f
private const val DesktopDragSampleCapacity = 32

private class DesktopDragVelocityEstimator(
    private val horizontal: Boolean,
) {
    private val velocityTracker = VelocityTracker()
    private val sampleStartTimes = LongArray(DesktopDragSampleCapacity)
    private val sampleEndTimes = LongArray(DesktopDragSampleCapacity)
    private val sampleAxisDeltas = FloatArray(DesktopDragSampleCapacity)
    private var sampleHead = 0
    private var sampleCount = 0
    private var lastEventTimeMs = 0L
    private var cumulativeAxisPos = 0f
    private var lastSignificantSign = 0f
    private var pendingOppositeDelta = 0f

    private fun toOffset(axisPos: Float): Offset =
        if (horizontal) Offset(axisPos, 0f) else Offset(0f, axisPos)

    private fun Velocity.toAxis(): Float = if (horizontal) x else y

    private fun clearSamples() {
        sampleHead = 0
        sampleCount = 0
    }

    private fun pushSample(startTimeMs: Long, endTimeMs: Long, axisDelta: Float) {
        if (sampleCount > 0) {
            val lastIdx = (sampleHead + sampleCount - 1) % DesktopDragSampleCapacity
            if (sampleEndTimes[lastIdx] == endTimeMs) {
                sampleAxisDeltas[lastIdx] += axisDelta
                return
            }
        }
        if (endTimeMs <= startTimeMs) return
        val insertIdx = if (sampleCount < DesktopDragSampleCapacity) {
            (sampleHead + sampleCount).also { sampleCount++ } % DesktopDragSampleCapacity
        } else {
            sampleHead.also { sampleHead = (sampleHead + 1) % DesktopDragSampleCapacity }
        }
        sampleStartTimes[insertIdx] = startTimeMs
        sampleEndTimes[insertIdx] = endTimeMs
        sampleAxisDeltas[insertIdx] = axisDelta
    }

    fun start(timeMs: Long) {
        velocityTracker.resetTracking()
        clearSamples()
        lastEventTimeMs = timeMs
        cumulativeAxisPos = 0f
        lastSignificantSign = 0f
        pendingOppositeDelta = 0f
        velocityTracker.addPosition(timeMs, Offset.Zero)
    }

    fun addDelta(timeMs: Long, axisDelta: Float) {
        if (axisDelta == 0f) return
        val dt = timeMs - lastEventTimeMs
        if (dt < 0L) return
        if (dt >= DesktopDragStopThresholdMs) {
            start(timeMs)
            lastSignificantSign = sign(axisDelta)
            cumulativeAxisPos = axisDelta
            velocityTracker.addPosition(timeMs, toOffset(cumulativeAxisPos))
            return
        }

        val deltaSign = sign(axisDelta)
        val acceptedDelta: Float
        if (lastSignificantSign != 0f && deltaSign != lastSignificantSign) {
            pendingOppositeDelta += axisDelta
            if (abs(pendingOppositeDelta) < DesktopDragDirectionJitterPx) {
                return
            }
            acceptedDelta = pendingOppositeDelta
            pendingOppositeDelta = 0f
            val prevTimeMs = lastEventTimeMs
            velocityTracker.resetTracking()
            clearSamples()
            lastSignificantSign = sign(acceptedDelta)
            velocityTracker.addPosition(prevTimeMs, Offset.Zero)
            cumulativeAxisPos = acceptedDelta
            velocityTracker.addPosition(timeMs, toOffset(cumulativeAxisPos))
            pushSample(prevTimeMs, timeMs, acceptedDelta)
            lastEventTimeMs = timeMs
            return
        } else {
            pendingOppositeDelta = 0f
            acceptedDelta = axisDelta
            if (abs(acceptedDelta) >= DesktopDragDirectionJitterPx || lastSignificantSign == 0f) {
                lastSignificantSign = deltaSign
            }
        }

        val prevTimeMs = lastEventTimeMs
        cumulativeAxisPos += acceptedDelta
        velocityTracker.addPosition(timeMs, toOffset(cumulativeAxisPos))
        pushSample(prevTimeMs, timeMs, acceptedDelta)
        lastEventTimeMs = timeMs
    }

    fun calculateAxisVelocity(
        releaseTimeMs: Long,
        minVelocityPx: Float,
        maxVelocityPx: Float,
    ): Float {
        if (releaseTimeMs - lastEventTimeMs >= DesktopDragStopThresholdMs) {
            return 0f
        }
        val windowStartCutoffMs = releaseTimeMs - DesktopDragVelocityWindowMs
        var totalDelta = 0f
        var windowFirstStartMs = Long.MAX_VALUE
        var windowLastEndMs = Long.MIN_VALUE
        var consistentDirection = true
        var expectedSign = 0f
        var qualifyingSamples = 0
        for (i in 0 until sampleCount) {
            val idx = (sampleHead + i) % DesktopDragSampleCapacity
            val endMs = sampleEndTimes[idx]
            if (endMs < windowStartCutoffMs) continue
            val delta = sampleAxisDeltas[idx]
            if (delta == 0f) continue
            val s = sign(delta)
            if (expectedSign == 0f) {
                expectedSign = s
            } else if (s != expectedSign) {
                consistentDirection = false
            }
            totalDelta += delta
            if (sampleStartTimes[idx] < windowFirstStartMs) {
                windowFirstStartMs = sampleStartTimes[idx]
            }
            if (endMs > windowLastEndMs) {
                windowLastEndMs = endMs
            }
            qualifyingSamples++
        }

        val lsqVelocity = velocityTracker.calculateVelocity(
            Velocity(maxVelocityPx, maxVelocityPx),
        ).toAxis()

        var rawVelocity = lsqVelocity
        if (qualifyingSamples > 0 && windowLastEndMs > windowFirstStartMs) {
            val windowAvgSpeed = (totalDelta * 1000f / (windowLastEndMs - windowFirstStartMs))
                .coerceIn(-maxVelocityPx, maxVelocityPx)
            if (
                consistentDirection &&
                windowAvgSpeed != 0f &&
                (rawVelocity == 0f || sign(rawVelocity) != sign(windowAvgSpeed))
            ) {
                rawVelocity = windowAvgSpeed
            }
        }
        return if (abs(rawVelocity) >= minVelocityPx) rawVelocity else 0f
    }
}

private val NoOpDesktopDragNestedScrollConnection = object : NestedScrollConnection {}

private sealed interface DesktopDragScrollEvent {
    data class Delta(val delta: Float) : DesktopDragScrollEvent
    data class Stop(val velocity: Velocity) : DesktopDragScrollEvent
}

@Composable
internal fun Modifier.nuvioDesktopDragScroll(
    state: ScrollableState,
    orientation: Orientation = Orientation.Horizontal,
): Modifier {
    if (!isDesktop) return this
    val flingBehavior = ScrollableDefaults.flingBehavior()
    val nestedScrollDispatcher = remember { NestedScrollDispatcher() }
    val density = LocalDensity.current
    val horizontal = orientation == Orientation.Horizontal
    val direction = if (horizontal && LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    val mobileTouchSlopPx = with(density) { DesktopDragScrollTouchSlop.toPx() }
    val minFlingVelocityFloorPx = with(density) { DesktopDragScrollMinFlingVelocity.toPx() }
    val maxFlingVelocityCapPx = with(density) { DesktopDragScrollMaxFlingVelocity.toPx() }

    return this
        .nestedScroll(NoOpDesktopDragNestedScrollConnection, nestedScrollDispatcher)
        .pointerInput(
            state,
            flingBehavior,
            direction,
            orientation,
            nestedScrollDispatcher,
            mobileTouchSlopPx,
            minFlingVelocityFloorPx,
            maxFlingVelocityCapPx,
        ) {
            fun toOffset(scalar: Float): Offset =
                if (horizontal) Offset(scalar, 0f) else Offset(0f, scalar)

            fun Offset.toAxis(): Float = if (horizontal) x else y

            fun toVelocity(scalar: Float): Velocity =
                if (horizontal) Velocity(scalar, 0f) else Velocity(0f, scalar)

            fun Velocity.toAxis(): Float = if (horizontal) x else y

            fun dispatchNestedScroll(
                scrollScope: ScrollScope,
                availableDelta: Float,
                source: NestedScrollSource,
            ): Offset {
                val available = toOffset(availableDelta)
                val preConsumed = nestedScrollDispatcher.dispatchPreScroll(available, source)
                val leftForSelf = available - preConsumed
                val selfDelta = leftForSelf.toAxis() * direction
                val selfConsumedScroll = if (selfDelta != 0f) scrollScope.scrollBy(selfDelta) else 0f
                val selfConsumed = toOffset(selfConsumedScroll * direction)
                val leftAfterSelf = leftForSelf - selfConsumed
                val postConsumed = nestedScrollDispatcher.dispatchPostScroll(
                    consumed = preConsumed + selfConsumed,
                    available = leftAfterSelf,
                    source = source,
                )
                return preConsumed + selfConsumed + postConsumed
            }

            suspend fun performNestedFling(availableVelocity: Velocity) {
                val preConsumed = nestedScrollDispatcher.dispatchPreFling(availableVelocity)
                val velocityLeft = availableVelocity - preConsumed
                val velocityLeftAxis = velocityLeft.toAxis()
                var remainingVelocity = velocityLeft
                if (abs(velocityLeftAxis) > 0f) {
                    val initialFlingSpeed = velocityLeftAxis * direction
                    var remainingFlingSpeed = initialFlingSpeed
                    state.scroll(MutatePriority.Default) {
                        val outerScope = this
                        val nestedScope = object : ScrollScope {
                            override fun scrollBy(pixels: Float): Float {
                                if (pixels == 0f) return 0f
                                val consumedOffset = dispatchNestedScroll(
                                    scrollScope = outerScope,
                                    availableDelta = pixels * direction,
                                    source = NestedScrollSource.SideEffect,
                                )
                                return consumedOffset.toAxis() * direction
                            }
                        }
                        remainingFlingSpeed = with(flingBehavior) {
                            nestedScope.performFling(initialFlingSpeed)
                        }
                    }
                    remainingVelocity = toVelocity(remainingFlingSpeed * direction)
                }
                val consumedVelocity = velocityLeft - remainingVelocity
                nestedScrollDispatcher.dispatchPostFling(
                    consumed = consumedVelocity,
                    available = remainingVelocity,
                )
            }

            coroutineScope {
                var activeScrollJob: Job? = null

                fun startDragSession(): Channel<DesktopDragScrollEvent> {
                    activeScrollJob?.cancel()
                    val channel = Channel<DesktopDragScrollEvent>(Channel.UNLIMITED)
                    activeScrollJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        var stopEvent: DesktopDragScrollEvent.Stop? = null
                        try {
                            state.scroll(MutatePriority.UserInput) {
                                for (dragEvent in channel) {
                                    when (dragEvent) {
                                        is DesktopDragScrollEvent.Delta -> {
                                            dispatchNestedScroll(
                                                scrollScope = this,
                                                availableDelta = dragEvent.delta,
                                                source = NestedScrollSource.UserInput,
                                            )
                                        }
                                        is DesktopDragScrollEvent.Stop -> {
                                            stopEvent = dragEvent
                                            break
                                        }
                                    }
                                }
                            }
                        } finally {
                            channel.close()
                        }
                        val stop = stopEvent ?: return@launch
                        performNestedFling(stop.velocity)
                    }
                    return channel
                }

                awaitEachGesture {
                    val initialDown = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    if (
                        initialDown.isConsumed ||
                        initialDown.type != PointerType.Mouse ||
                        !currentEvent.buttons.isPrimaryPressed
                    ) {
                        return@awaitEachGesture
                    }

                    val slopPx = minOf(viewConfiguration.touchSlop, mobileTouchSlopPx)
                    val minFlingVelocityPx = maxOf(viewConfiguration.minimumFlingVelocity, minFlingVelocityFloorPx)
                    val maxFlingVelocityPx = minOf(viewConfiguration.maximumFlingVelocity, maxFlingVelocityCapPx)
                        .coerceAtLeast(1f)

                    val caughtActiveScroll = activeScrollJob?.isActive == true || state.isScrollInProgress
                    val down: PointerInputChange
                    var dragging = false
                    var dragChannel: Channel<DesktopDragScrollEvent>? = null

                    if (caughtActiveScroll) {
                        activeScrollJob?.cancel()
                        activeScrollJob = null
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            state.stopScroll(MutatePriority.UserInput)
                        }
                        initialDown.consume()
                        down = initialDown
                        dragging = true
                        val mainDownEvent = awaitPointerEvent(pass = PointerEventPass.Main)
                        val mainDown = mainDownEvent.changes.firstOrNull { it.id == initialDown.id }
                        if (mainDown == null || !mainDown.pressed) {
                            return@awaitEachGesture
                        }
                    } else {
                        val mainDownEvent = awaitPointerEvent(pass = PointerEventPass.Main)
                        val mainDown = mainDownEvent.changes.firstOrNull { it.id == initialDown.id }
                            ?: return@awaitEachGesture
                        // Note: do NOT reject mainDown.isConsumed here! Child clickable/combinedClickable
                        // modifiers consume down on Main to claim tap presses, while leaving move events
                        // unconsumed so parent scrollables can still detect drags (matching mobile Compose).
                        if (!mainDown.pressed) {
                            return@awaitEachGesture
                        }
                        down = mainDown
                    }

                    val velocityEstimator = DesktopDragVelocityEstimator(horizontal).apply {
                        start(down.uptimeMillis)
                    }
                    var totalDx = 0f
                    var totalDy = 0f
                    var stopDispatched = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            val released = !change.pressed || !event.buttons.isPrimaryPressed

                            if (released) {
                                if (dragging) {
                                    change.consume()
                                    val axisVelocity = velocityEstimator.calculateAxisVelocity(
                                        releaseTimeMs = change.uptimeMillis,
                                        minVelocityPx = minFlingVelocityPx,
                                        maxVelocityPx = maxFlingVelocityPx,
                                    )
                                    dragChannel?.let { channel ->
                                        stopDispatched = true
                                        channel.trySend(DesktopDragScrollEvent.Stop(toVelocity(axisVelocity)))
                                    }
                                }
                                break
                            }

                            val delta = change.position - change.previousPosition
                            val axisDelta = if (horizontal) delta.x else delta.y
                            velocityEstimator.addDelta(change.uptimeMillis, axisDelta)
                            if (!dragging) {
                                totalDx += delta.x
                                totalDy += delta.y
                                val totalAlong = if (horizontal) totalDx else totalDy
                                val alongAxis = abs(totalAlong)
                                val acrossAxis = abs(if (horizontal) totalDy else totalDx)
                                when {
                                    horizontal && acrossAxis >= slopPx && acrossAxis > alongAxis -> break
                                    alongAxis >= slopPx && alongAxis >= acrossAxis -> {
                                        dragging = true
                                        val postSlopDelta = totalAlong - sign(totalAlong) * slopPx
                                        val channel = startDragSession()
                                        dragChannel = channel
                                        if (postSlopDelta != 0f) {
                                            channel.trySend(DesktopDragScrollEvent.Delta(postSlopDelta))
                                        }
                                        change.consume()
                                    }
                                    else -> continue
                                }
                            } else {
                                if (axisDelta != 0f) {
                                    val channel = dragChannel ?: startDragSession().also { dragChannel = it }
                                    channel.trySend(DesktopDragScrollEvent.Delta(axisDelta))
                                }
                                change.consume()
                            }
                        }
                    } finally {
                        val channel = dragChannel
                        if (channel != null && !stopDispatched) {
                            channel.trySend(DesktopDragScrollEvent.Stop(Velocity.Zero))
                            channel.close()
                        }
                    }
                }
            }
        }
}

@Composable
fun NuvioPosterCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    basePosterWidthDp: Int? = null,
    fallbackImageUrl: String? = null,
    shape: NuvioPosterShape = NuvioPosterShape.Poster,
    detailLine: String? = null,
    showTitleBelow: Boolean = true,
    bottomLeftLogoUrl: String? = null,
    bottomLeftText: String? = null,
    isWatched: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val posterCardStyle = rememberPosterCardStyleUiState()
    val tokens = MaterialTheme.nuvio
    val effectiveBasePosterWidthDp = basePosterWidthDp ?: posterCardStyle.widthDp
    val cardWidth = shape.cardWidth(basePosterWidthDp = effectiveBasePosterWidthDp)
    val cardShape = RoundedCornerShape(posterCardStyle.cornerRadiusDp.dp)
    val catalogLogoOverlaySize = catalogLogoOverlaySize(
        basePosterWidthDp = effectiveBasePosterWidthDp,
        shape = shape,
    )
    val shouldShowTitleBelow = showTitleBelow && !posterCardStyle.hideLabelsEnabled

    Column(
        modifier = Modifier
            .desktopPosterHoverScale()
            .then(modifier)
            .width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(shape.aspectRatio)
                // Compose focus is used on Compose-driven routes (details rails), where the spatial
                // engine defers navigation. The border must be applied *before* the clickable so it
                // can observe the focus target that clickable creates.
                .nuvioFocusBorder(cardShape)
                // The engine's focus ring is drawn by posterCardClickable and must sit outside the
                // clip so the rounded card clip cannot cut it away.
                .posterCardClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    zoomImageUrl = imageUrl,
                    zoomCornerRadius = posterCardStyle.cornerRadiusDp.dp,
                    hoverScaleEnabled = false,
                )
                .clip(cardShape)
                .background(tokens.colors.surface)
                .nuvioCardDepth(
                    shape = cardShape,
                    surface = NuvioCardDepthSurface.Posters,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                val platformContext = coil3.compose.LocalPlatformContext.current
                val hasFallback = !fallbackImageUrl.isNullOrBlank() && fallbackImageUrl != imageUrl
                val imageModel = remember(imageUrl, fallbackImageUrl, platformContext) {
                    if (hasFallback) {
                        coil3.request.ImageRequest.Builder(platformContext)
                            .data(imageUrl)
                            .memoryCacheKeyExtras(
                                mapOf(com.nuvio.app.core.poster.CustomPosterFallbackInterceptor.FALLBACK_URL_KEY to fallbackImageUrl!!)
                            )
                            .build()
                    } else {
                        imageUrl
                    }
                }
                NuvioAsyncImage(
                    model = imageModel,
                    contentDescription = title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    desktopAnimation = posterCardStyle.animatedArtworkMode.toDesktopAnimation(),
                )
            } else {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s14),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!bottomLeftLogoUrl.isNullOrBlank() || !bottomLeftText.isNullOrBlank()) {
                // Gradient scrim for readability — matching NuvioTV Modern Home style
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawWithCache {
                            val gradient = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    0.58f to Color.Transparent,
                                    1.0f to Color.Black.copy(alpha = 0.75f)
                                )
                            )
                            onDrawBehind { drawRect(gradient) }
                        }
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s10),
                ) {
                    if (!bottomLeftLogoUrl.isNullOrBlank()) {
                        NuvioAsyncImage(
                            model = bottomLeftLogoUrl,
                            contentDescription = stringResource(Res.string.poster_logo_content_description, title),
                            modifier = Modifier
                                .width(catalogLogoOverlaySize.width)
                                .height(catalogLogoOverlaySize.height),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                        )
                    } else {
                        Text(
                            text = bottomLeftText.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = catalogLogoOverlaySize.textMaxWidth),
                        )
                    }
                }
            }

            NuvioPosterWatchedOverlay(isWatched = isWatched)
        }
        if (shouldShowTitleBelow) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detailLine.isNullOrBlank()) {
                Text(
                    text = detailLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Box(modifier = Modifier.height(NuvioTokens.Space.none))
            }
        } else {
            Box(modifier = Modifier.height(NuvioTokens.Space.none))
        }
    }
}

@Composable
private fun NuvioShelfSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onViewAllClick: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    viewAllPillSize: NuvioViewAllPillSize = NuvioViewAllPillSize.Default,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .then(if (onTitleClick != null) {
                        Modifier.nuvioFocusBorder(RoundedCornerShape(NuvioTokens.Radius.md))
                            .clickable(onClick = onTitleClick)
                    } else Modifier),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val viewAllPlaceholderModifier = if (onViewAllClick == null) {
                Modifier
                    .alpha(0f)
                    .clearAndSetSemantics { }
            } else {
                Modifier
            }
            NuvioViewAllPill(
                onClick = onViewAllClick,
                size = viewAllPillSize,
                modifier = viewAllPlaceholderModifier,
            )
        }
    }
}

@Composable
private fun NuvioViewAllPill(
    onClick: (() -> Unit)?,
    size: NuvioViewAllPillSize,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val actionSize = if (size == NuvioViewAllPillSize.Compact) NuvioTokens.Space.s32 else NuvioTokens.Space.s40
    val iconSize = if (size == NuvioViewAllPillSize.Compact) NuvioTokens.Icon.sm else tokens.icons.md
    val viewAllText = stringResource(Res.string.home_view_all)

    val pillShape = RoundedCornerShape(NuvioTokens.Radius.xl)
    Box(
        modifier = modifier
            .size(actionSize)
            .background(color = tokens.colors.surface, shape = pillShape)
            .then(if (onClick != null) {
                Modifier.nuvioFocusBorder(pillShape).clickable(onClick = onClick)
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = viewAllText,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(iconSize),
        )
    }
}

internal const val NuvioDesktopCatalogShelfPosterScale = 1.4f
private const val DesktopPosterHoverScale = 1.045f

internal fun desktopCatalogShelfPosterBaseWidthDp(
    basePosterWidthDp: Int,
): Int =
    if (isDesktop) {
        (basePosterWidthDp * NuvioDesktopCatalogShelfPosterScale).roundToInt()
    } else {
        basePosterWidthDp
    }

internal fun Modifier.nuvioShelfHoverOverdraw(inset: Dp): Modifier {
    if (inset == 0.dp) return this

    // Expand the measured viewport, then place it negatively so edge items keep
    // their visual alignment while desktop hover scale can draw into the gutter.
    return layout { measurable, constraints ->
        val insetPx = inset.roundToPx()
        val horizontalInset = insetPx * 2
        val verticalInset = insetPx * 2
        val expandedMaxWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth + horizontalInset
        } else {
            constraints.maxWidth
        }
        val expandedMinWidth = if (constraints.hasBoundedWidth) {
            (constraints.minWidth + horizontalInset).coerceAtMost(expandedMaxWidth)
        } else {
            constraints.minWidth
        }
        val expandedMaxHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight + verticalInset
        } else {
            constraints.maxHeight
        }
        val expandedConstraints = constraints.copy(
            minWidth = expandedMinWidth,
            maxWidth = expandedMaxWidth,
            minHeight = 0,
            maxHeight = expandedMaxHeight,
        )
        val placeable = measurable.measure(expandedConstraints)
        val width = (placeable.width - horizontalInset)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeable.height - verticalInset)
            .coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            placeable.placeRelative(-insetPx, -insetPx)
        }
    }
}

private val NuvioPosterShape.aspectRatio: Float
    get() = when (this) {
        NuvioPosterShape.Poster -> 0.675f
        NuvioPosterShape.Square -> 1f
        NuvioPosterShape.Landscape -> PosterLandscapeAspectRatio
    }

private data class CatalogLogoOverlaySize(
    val width: Dp,
    val height: Dp,
    val textMaxWidth: Dp,
)

private fun catalogLogoOverlaySize(
    basePosterWidthDp: Int,
    shape: NuvioPosterShape,
): CatalogLogoOverlaySize =
    if (shape == NuvioPosterShape.Landscape) {
        when {
            basePosterWidthDp <= 108 -> CatalogLogoOverlaySize(width = 92.dp, height = 24.dp, textMaxWidth = 120.dp)
            basePosterWidthDp <= 120 -> CatalogLogoOverlaySize(width = 104.dp, height = 28.dp, textMaxWidth = 132.dp)
            basePosterWidthDp <= 132 -> CatalogLogoOverlaySize(width = 116.dp, height = 30.dp, textMaxWidth = 144.dp)
            else -> CatalogLogoOverlaySize(width = 128.dp, height = 34.dp, textMaxWidth = 156.dp)
        }
    } else {
        when {
            basePosterWidthDp <= 108 -> CatalogLogoOverlaySize(width = 72.dp, height = 18.dp, textMaxWidth = 92.dp)
            basePosterWidthDp <= 120 -> CatalogLogoOverlaySize(width = 80.dp, height = 20.dp, textMaxWidth = 104.dp)
            basePosterWidthDp <= 132 -> CatalogLogoOverlaySize(width = 88.dp, height = 22.dp, textMaxWidth = 112.dp)
            else -> CatalogLogoOverlaySize(width = 96.dp, height = 24.dp, textMaxWidth = 124.dp)
        }
    }

private fun NuvioPosterShape.cardWidth(basePosterWidthDp: Int): Dp =
    when (this) {
        NuvioPosterShape.Poster -> basePosterWidthDp.dp
        NuvioPosterShape.Square -> basePosterWidthDp.dp
        NuvioPosterShape.Landscape -> landscapePosterWidth(basePosterWidthDp)
    }
@Composable
internal fun Modifier.desktopPosterHoverScale(
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
): Modifier {
    if (!enabled || !isDesktop) return this

    val hovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered) DesktopPosterHoverScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "desktop_poster_hover_scale",
    )

    val isScaling = hovered || scale != 1f

    return this
        .then(
            if (isScaling) {
                Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .zIndex(1f)
            } else {
                Modifier
            },
        )
        .hoverable(interactionSource)
}
