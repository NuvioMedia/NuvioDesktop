package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.posterGridColumnCountForViewport
import kotlin.test.Test
import kotlin.test.assertEquals

class PosterGridTest {

    @Test
    fun `home catalog preview keeps eighteen item baseline at sixteen by nine`() {
        assertEquals(
            18,
            homeCatalogPreviewLimitForWidth(
                maxWidthDp = 1920f,
                sectionPadding = 32.dp,
                basePosterWidthDp = 126,
                useLandscapeMode = false,
                useDesktopSizing = true,
            ),
        )
    }

    @Test
    fun `home catalog preview renders enough posters for super ultrawide`() {
        assertEquals(
            38,
            homeCatalogPreviewLimitForWidth(
                maxWidthDp = 7040f,
                sectionPadding = 32.dp,
                basePosterWidthDp = 126,
                useLandscapeMode = false,
                useDesktopSizing = true,
            ),
        )
    }

    @Test
    fun `discovery keeps its existing columns at sixteen by nine`() {
        val columns = posterGridColumnCountForViewport(
            screenWidth = 1920.dp,
            screenHeight = 1080.dp,
            basePosterWidthDp = 126,
            useDesktopSizing = true,
        )

        assertEquals(7, columns)
    }

    @Test
    fun `discovery adds columns at thirty two by nine using shared poster width`() {
        val defaultColumns = posterGridColumnCountForViewport(
            screenWidth = 3840.dp,
            screenHeight = 1080.dp,
            basePosterWidthDp = 126,
            useDesktopSizing = true,
        )
        val largerPosterColumns = posterGridColumnCountForViewport(
            screenWidth = 3840.dp,
            screenHeight = 1080.dp,
            basePosterWidthDp = 150,
            useDesktopSizing = true,
        )

        assertEquals(20, defaultColumns)
        assertEquals(17, largerPosterColumns)
    }
}
