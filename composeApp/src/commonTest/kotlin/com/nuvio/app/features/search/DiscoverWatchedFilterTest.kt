package com.nuvio.app.features.search

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.watchedItemKey
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoverWatchedFilterTest {
    private val movie = MetaPreview(id = "tt1234567", type = "movie", name = "Movie")
    private val series = MetaPreview(id = "tmdb:123", type = "series", name = "Show")

    @Test
    fun `keeps every item when nothing is watched`() {
        val result = listOf(movie, series).filterUnwatchedPosters(
            watchedKeys = emptySet(),
            fullyWatchedSeriesKeys = emptySet(),
        )

        assertEquals(listOf(movie, series), result)
    }

    @Test
    fun `drops watched movies`() {
        val result = listOf(movie, series).filterUnwatchedPosters(
            watchedKeys = setOf(watchedItemKey("movie", movie.id)),
            fullyWatchedSeriesKeys = emptySet(),
        )

        assertEquals(listOf(series), result)
    }

    @Test
    fun `drops fully watched series but keeps partially watched ones`() {
        val partiallyWatchedSeries = MetaPreview(id = "tmdb:456", type = "series", name = "Other Show")

        val result = listOf(series, partiallyWatchedSeries).filterUnwatchedPosters(
            watchedKeys = setOf(
                // A single watched episode of the second show must not hide it.
                watchedItemKey("series", partiallyWatchedSeries.id, season = 1, episode = 1),
            ),
            fullyWatchedSeriesKeys = setOf(watchedItemKey("series", series.id)),
        )

        assertEquals(listOf(partiallyWatchedSeries), result)
    }
}
