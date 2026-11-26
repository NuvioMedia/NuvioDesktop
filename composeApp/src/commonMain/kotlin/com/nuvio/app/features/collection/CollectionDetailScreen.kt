package com.nuvio.app.features.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.controller.RegisterControllerVerticalScroll
import com.nuvio.app.core.ui.NuvioDesktopVerticalScrollbar
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.desktopCatalogShelfPosterBaseWidthDp
import com.nuvio.app.core.ui.desktopPageHorizontalPaddingForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import com.nuvio.app.core.ui.nuvioDesktopDragScroll
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.core.ui.withDuplicateSafeLazyKeys
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.components.CollectionFolderCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.collections_editor_folder_empty_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CollectionDetailScreen(
    title: String,
    folders: List<CollectionFolder>,
    onBack: () -> Unit,
    onFolderClick: (CollectionFolder) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        val pagePadding = desktopPageHorizontalPaddingForWidth(maxWidth.value)
        Column(modifier = Modifier.fillMaxSize()) {
            NuvioScreenHeader(
                title = title,
                modifier = Modifier.padding(horizontal = pagePadding),
                onBack = onBack,
            )

            if (folders.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.collections_editor_folder_empty_title))
                }
            } else {
                val keyedFolders = remember(folders) { folders.withDuplicateSafeLazyKeys { it.id } }
                val gridState = rememberLazyGridState()
                val scrollModifier = RegisterControllerVerticalScroll(lazyGridState = gridState)
                val posterStyle = rememberPosterCardStyleUiState()
                val baseWidth = desktopCatalogShelfPosterBaseWidthDp(posterStyle.widthDp)
                val columnWidth = if (
                    posterStyle.catalogLandscapeModeEnabled || folders.any { it.posterShape == PosterShape.Landscape }
                ) {
                    landscapePosterWidth(baseWidth)
                } else {
                    baseWidth.dp
                }
                Box(modifier = Modifier.weight(1f)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(columnWidth),
                        state = gridState,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(scrollModifier)
                            .nuvioDesktopDragScroll(gridState, Orientation.Vertical),
                        contentPadding = PaddingValues(
                            start = pagePadding,
                            end = pagePadding,
                            top = 8.dp,
                            bottom = nuvioSafeBottomPadding(18.dp),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(keyedFolders, key = { it.lazyKey }) { entry ->
                            val folder = entry.value
                            CollectionFolderCard(folder = folder, onClick = { onFolderClick(folder) })
                        }
                    }
                    NuvioDesktopVerticalScrollbar(
                        state = gridState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }
}
