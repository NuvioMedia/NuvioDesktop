package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertEquals

class DetailScrolledBackgroundTest {

    @Test
    fun `overlay is transparent before scrolling or measuring the hero`() {
        assertEquals(0f, detailScrolledBackgroundProgress(scrollOffsetPx = 0f, heroHeightPx = 660))
        assertEquals(0f, detailScrolledBackgroundAlpha(scrollOffsetPx = 0f, heroHeightPx = 660))
        assertEquals(0f, detailScrolledBackgroundAlpha(scrollOffsetPx = -40f, heroHeightPx = 660))
        assertEquals(0f, detailScrolledBackgroundAlpha(scrollOffsetPx = 200f, heroHeightPx = 0))
    }

    @Test
    fun `scroll transition progresses from sharp to fully blurred`() {
        assertEquals(
            expected = 0.5f,
            actual = detailScrolledBackgroundProgress(scrollOffsetPx = 247.5f, heroHeightPx = 660),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            expected = 1f,
            actual = detailScrolledBackgroundProgress(scrollOffsetPx = 495f, heroHeightPx = 660),
            absoluteTolerance = 0.0001f,
        )
    }

    @Test
    fun `overlay darkens progressively and remains semitransparent`() {
        assertEquals(
            expected = 0.43f,
            actual = detailScrolledBackgroundAlpha(scrollOffsetPx = 247.5f, heroHeightPx = 660),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            expected = 0.86f,
            actual = detailScrolledBackgroundAlpha(scrollOffsetPx = 495f, heroHeightPx = 660),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            expected = 0.86f,
            actual = detailScrolledBackgroundAlpha(scrollOffsetPx = 900f, heroHeightPx = 660),
            absoluteTolerance = 0.0001f,
        )
    }

    @Test
    fun `cinematic overlay uses lighter darkening`() {
        assertEquals(
            expected = 0.18f,
            actual = detailScrolledBackgroundAlpha(
                scrollOffsetPx = 247.5f,
                heroHeightPx = 660,
                maxAlpha = 0.36f,
            ),
            absoluteTolerance = 0.0001f,
        )
        assertEquals(
            expected = 0.36f,
            actual = detailScrolledBackgroundAlpha(
                scrollOffsetPx = 900f,
                heroHeightPx = 660,
                maxAlpha = 0.36f,
            ),
            absoluteTolerance = 0.0001f,
        )
    }
}
