package com.nuvio.app.features.player.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.window_menu_close
import nuvio.composeapp.generated.resources.window_menu_file
import nuvio.composeapp.generated.resources.window_menu_minimize
import nuvio.composeapp.generated.resources.window_menu_window
import org.jetbrains.compose.resources.stringResource

/**
 * macOS binds Cmd+W and Cmd+M to key equivalents of the standard File and Window menus, so without
 * a menu bar the shortcuts have nothing to attach to (#878). Only macOS gets the menu: Windows and
 * Linux keep their in-window title bar behavior.
 */
@Composable
internal fun FrameWindowScope.MacosAppMenuBar(
    onCloseRequest: () -> Unit,
    onMinimizeRequest: () -> Unit,
) {
    if (DesktopHostOs.current != DesktopHostOs.MACOS) return

    MenuBar {
        Menu(stringResource(Res.string.window_menu_file)) {
            Item(
                text = stringResource(Res.string.window_menu_close),
                shortcut = KeyShortcut(Key.W, meta = true),
                onClick = onCloseRequest,
            )
        }
        Menu(stringResource(Res.string.window_menu_window)) {
            Item(
                text = stringResource(Res.string.window_menu_minimize),
                shortcut = KeyShortcut(Key.M, meta = true),
                onClick = onMinimizeRequest,
            )
        }
    }
}
