package com.nuvio.app

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.NuvioNavigator
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals

class OpenPreviewNavigationTest {
    private val ironMan = DetailRoute(type = "movie", id = "tt0371746", title = "Iron Man")

    @Test
    fun openingTheTitleAlreadyOnScreenDoesNotPushADuplicateDetailRoute() {
        val backStack = NavBackStack<NavKey>(ironMan)
        val navigator = NuvioNavigator(backStack)

        navigator.openPreview(MetaPreview(id = "tt0371746", type = "movie", name = "Iron Man"))

        assertEquals(listOf<NavKey>(ironMan), backStack.toList())
    }

    @Test
    fun oneBackReturnsToThePreviousPageAfterReopeningTheSameTitle() {
        val previous = DetailRoute(type = "movie", id = "tt0848228", title = "The Avengers")
        val backStack = NavBackStack<NavKey>(previous, ironMan)
        val navigator = NuvioNavigator(backStack)

        navigator.openPreview(MetaPreview(id = "tt0371746", type = "movie", name = "Iron Man"))
        navigator.popBackStack(expectedRoute = ironMan)

        assertEquals(listOf<NavKey>(previous), backStack.toList())
    }

    @Test
    fun collectionClickOnAPageKeyedByItsTmdbIdDoesNotPushAfterResolution() = runTest {
        val previous = DetailRoute(type = "movie", id = "tt0848228", title = "The Avengers")
        val tmdbIronMan = DetailRoute(type = "movie", id = "tmdb:1726", title = "Iron Man")
        val backStack = NavBackStack<NavKey>(previous, tmdbIronMan)
        val navigator = NuvioNavigator(backStack)

        navigator.openMeta(
            MetaPreview(id = "tmdb:1726", type = "movie", name = "Iron Man"),
            tmdbToImdb = { _, _ -> "tt0371746" },
        )
        navigator.popBackStack(expectedRoute = tmdbIronMan)

        assertEquals(listOf<NavKey>(previous), backStack.toList())
    }

    @Test
    fun collectionClickOnAnotherTmdbTitlePushesTheResolvedRoute() = runTest {
        val backStack = NavBackStack<NavKey>(ironMan)
        val navigator = NuvioNavigator(backStack)

        navigator.openMeta(
            MetaPreview(id = "tmdb:10138", type = "movie", name = "Iron Man 2"),
            tmdbToImdb = { _, _ -> "tt1228705" },
        )

        assertEquals(
            listOf<NavKey>(ironMan, DetailRoute(type = "movie", id = "tt1228705", title = "Iron Man 2")),
            backStack.toList(),
        )
    }

    @Test
    fun openingAnotherTitlePushesItsDetailRoute() {
        val backStack = NavBackStack<NavKey>(ironMan)
        val navigator = NuvioNavigator(backStack)

        navigator.openPreview(MetaPreview(id = "tt1228705", type = "movie", name = "Iron Man 2"))

        assertEquals(
            listOf<NavKey>(ironMan, DetailRoute(type = "movie", id = "tt1228705", title = "Iron Man 2")),
            backStack.toList(),
        )
    }
}
