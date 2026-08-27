package com.nuvio.app.features.library

import com.nuvio.app.features.watched.watchedItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryWatchlistFilterTest {
    private fun item(id: String, type: String, name: String) = LibraryItem(
        id = id,
        type = type,
        name = name,
        savedAtEpochMs = 0L,
    )

    private fun section(type: String, vararg items: LibraryItem) = LibrarySection(
        type = type,
        displayTitle = type,
        items = items.toList(),
    )

    @Test
    fun `keeps everything when nothing is watched`() {
        val sections = listOf(
            section("movie", item("tt1", "movie", "Movie One")),
            section("series", item("tmdb:1", "series", "Show One")),
        )

        val result = filterLibrarySectionsToUnwatched(
            sections = sections,
            watchedKeys = emptySet(),
            fullyWatchedSeriesKeys = emptySet(),
        )

        assertEquals(sections, result)
    }

    @Test
    fun `drops watched movies and keeps the rest of the section`() {
        val watched = item("tt1", "movie", "Watched Movie")
        val unwatched = item("tt2", "movie", "Unwatched Movie")

        val result = filterLibrarySectionsToUnwatched(
            sections = listOf(section("movie", watched, unwatched)),
            watchedKeys = setOf(watchedItemKey("movie", watched.id)),
            fullyWatchedSeriesKeys = emptySet(),
        )

        assertEquals(1, result.size)
        assertEquals(listOf(unwatched), result.single().items)
    }

    @Test
    fun `drops fully watched series and keeps partially watched ones`() {
        val finished = item("tmdb:1", "series", "Finished Show")
        val inProgress = item("tmdb:2", "series", "In Progress Show")

        val result = filterLibrarySectionsToUnwatched(
            sections = listOf(section("series", finished, inProgress)),
            watchedKeys = setOf(
                // A single watched episode must not hide the show.
                watchedItemKey("series", inProgress.id, season = 1, episode = 1),
            ),
            fullyWatchedSeriesKeys = setOf(watchedItemKey("series", finished.id)),
        )

        assertEquals(listOf(inProgress), result.single().items)
    }

    @Test
    fun `drops sections that end up empty`() {
        val watched = item("tt1", "movie", "Watched Movie")
        val unwatchedShow = item("tmdb:1", "series", "Show One")

        val result = filterLibrarySectionsToUnwatched(
            sections = listOf(
                section("movie", watched),
                section("series", unwatchedShow),
            ),
            watchedKeys = setOf(watchedItemKey("movie", watched.id)),
            fullyWatchedSeriesKeys = emptySet(),
        )

        assertEquals(1, result.size)
        assertEquals("series", result.single().type)
        assertTrue(result.none { it.type == "movie" })
    }
}
