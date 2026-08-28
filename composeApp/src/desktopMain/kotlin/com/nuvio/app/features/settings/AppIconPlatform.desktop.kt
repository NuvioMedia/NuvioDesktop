package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

internal actual object AppIconPlatform {
    actual val isSupported: Boolean = true
    actual val requiresCloseConfirmation: Boolean = true

    private val store = DesktopStorage.store("nuvio_app_icon")
    private const val selectedIconKey = "selected_icon"

    actual fun currentIconName(): String? = store.getString(selectedIconKey)

    actual suspend fun activateIcon(name: String?): Boolean {
        val icon = AppIconOption.fromPlatformName(name)
        return when (DesktopHostOs.current) {
            DesktopHostOs.WINDOWS -> {
                store.putString(selectedIconKey, name)
                WindowsAppShortcutIconUpdater.updateAsync(icon) {
                    restartWindowsApp()
                }
                true
            }
            DesktopHostOs.MACOS -> {
                val changed = withContext(Dispatchers.IO) {
                    MacAppIconUpdater.update(icon)
                }
                if (changed) {
                    store.putString(selectedIconKey, name)
                    MacAppIconUpdater.restartAsync()
                }
                changed
            }
            else -> {
                store.putString(selectedIconKey, name)
                true
            }
        }
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
