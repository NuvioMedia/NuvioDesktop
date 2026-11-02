package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

internal actual object AppIconPlatform {
    actual val isSupported: Boolean = true
    actual val requiresCloseConfirmation: Boolean = true

    private val store = DesktopStorage.store("nuvio_app_icon")
    private const val selectedIconKey = "selected_icon"
    private const val selectedIconKeyKey = "selected_icon_key"

    actual fun loadSelectedIconKey(): String? = store.getString(selectedIconKeyKey)

    actual fun saveSelectedIconKey(key: String) = store.putString(selectedIconKeyKey, key)

    actual fun currentIconName(): String? = store.getString(selectedIconKey)

    actual suspend fun activateIcon(name: String?): Boolean {
        store.putString(selectedIconKey, name)
        when (DesktopHostOs.current) {
            DesktopHostOs.WINDOWS -> {
                WindowsAppShortcutIconUpdater.updateAsync(AppIconOption.fromPlatformName(name)) {
                    restartWindowsApp()
                }
            }
            DesktopHostOs.MACOS -> {
                MacAppIconUpdater.updateAsync(AppIconOption.fromPlatformName(name)) {
                    MacAppIconUpdater.restartAsync()
                }
            }
            else -> Unit
        }
        return true
    }

    private fun restartWindowsApp() {
        Thread({
            runCatching {
                Thread.sleep(900)
                val appHome = System.getProperty("compose.application.home")?.let { Paths.get(it) }
                val installedLauncher = appHome?.parent?.resolve("Nuvio.exe")
                val parentLauncher = ProcessHandle.current().parent().orElse(null)?.info()?.command()?.orElse(null)?.let { Paths.get(it) }
                val currentLauncher = ProcessHandle.current().info().command().orElse(null)?.let { Paths.get(it) }
                val launcher = sequenceOf(installedLauncher, parentLauncher, currentLauncher).filterNotNull().firstOrNull(Files::isRegularFile)
                    ?: return@runCatching
                ProcessBuilder(launcher.toString()).start()
                exitProcess(0)
            }
        }, "Nuvio Windows app restart").apply {
            isDaemon = false
            start()
        }
    }
}