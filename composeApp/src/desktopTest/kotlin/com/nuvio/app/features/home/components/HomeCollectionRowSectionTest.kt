package com.nuvio.app.features.home.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionDetailScreen
import com.nuvio.app.features.collection.CollectionFolder
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import org.jetbrains.compose.resources.getString
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCollectionRowSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun clickingTheCollectionTitleOpensTheCollection() {
        val collection = Collection(
            id = "staff-picks",
            title = "Staff Picks",
            folders = listOf(
                CollectionFolder(id = "movies", title = "Movies"),
                CollectionFolder(id = "series", title = "Series"),
            ),
        )
        var openedFolderId: String? = null
        compose.setContent {
            var openCollection by remember { mutableStateOf<Collection?>(null) }
            NuvioTheme {
                val selected = openCollection
                if (selected == null) {
                    HomeCollectionRowSection(
                        collection = collection,
                        onCollectionClick = { openCollection = it },
                        onFolderClick = { _, folderId -> openedFolderId = folderId },
                    )
                } else {
                    CollectionDetailScreen(
                        title = selected.title,
                        folders = selected.folders,
                        onBack = { openCollection = null },
                        onFolderClick = { openedFolderId = it.id },
                    )
                }
            }
        }

        compose.onNodeWithText(collection.title).assertHasClickAction().performMouseInput { click() }
        val back = compose.onNodeWithContentDescription(runBlocking { getString(Res.string.action_back) })
        back.assertExists()
        compose.onNodeWithText("Series").performMouseInput { click() }
        compose.runOnIdle { assertEquals("series", openedFolderId) }

        back.performMouseInput { click() }
        back.assertDoesNotExist()
        compose.onNodeWithText("Movies").performMouseInput { click() }
        compose.runOnIdle { assertEquals("movies", openedFolderId) }
    }
}
