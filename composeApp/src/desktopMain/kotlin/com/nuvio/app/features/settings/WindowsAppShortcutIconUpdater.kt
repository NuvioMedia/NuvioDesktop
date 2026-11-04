package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

internal object WindowsAppShortcutIconUpdater {
    fun updateAsync(icon: AppIconOption, onComplete: () -> Unit) {
        Thread({
            try {
                update(icon)
            } finally {
                onComplete()
            }
        }, "Nuvio Windows app icon updater").apply { isDaemon = false }.start()
    }

    private fun update(icon: AppIconOption) {
        if (DesktopHostOs.current != DesktopHostOs.WINDOWS) return
        runCatching {
            val resource = "icons/app-icon-${icon.key}-transparent.ico"
            val localAppData = knownFolder("LocalApplicationData") ?: return@runCatching
            val iconDirectory = localAppData.resolve("Nuvio/icons")
            Files.createDirectories(iconDirectory)
            val iconFile = iconDirectory.resolve("app-icon-${icon.key}-transparent.ico")
            Thread.currentThread().contextClassLoader.getResourceAsStream(resource)?.use { input ->
                Files.copy(input, iconFile, StandardCopyOption.REPLACE_EXISTING)
            } ?: return@runCatching

            val shortcuts = shortcutPaths().filter(Files::isRegularFile).toList()
            val elevatedRoots = listOfNotNull(
                knownFolder("CommonDesktopDirectory"),
                knownFolder("CommonPrograms"),
            ).map { it.toAbsolutePath().normalize() }
            val elevated = shortcuts.filter { shortcut ->
                elevatedRoots.any { root -> shortcut.toAbsolutePath().normalize().startsWith(root) }
            }
            shortcuts.filterNot(elevated::contains).forEach { setShortcutIcon(it, iconFile) }
            if (elevated.isNotEmpty()) setShortcutIconsElevated(elevated, iconFile)
        }
    }

    private fun shortcutPaths(): Sequence<Path> = sequence {
        val desktop = knownFolder("Desktop")
        val programs = knownFolder("Programs")
        val applicationData = knownFolder("ApplicationData")
        val commonDesktop = knownFolder("CommonDesktopDirectory")
        val commonPrograms = knownFolder("CommonPrograms")

        desktop?.let { yield(it.resolve("DRAME-TV.lnk")) }
        programs?.let {
            yield(it.resolve("DRAME-TV.lnk"))
            yield(it.resolve("DRAME TV/DRAME-TV.lnk"))
        }
        applicationData?.let {
            yield(it.resolve("Microsoft/Internet Explorer/Quick Launch/User Pinned/TaskBar/DRAME-TV.lnk"))
        }
        commonDesktop?.let { yield(it.resolve("DRAME-TV.lnk")) }
        commonPrograms?.let {
            yield(it.resolve("DRAME TV/DRAME-TV.lnk"))
            yield(it.resolve("DRAME-TV.lnk"))
        }
    }

    private fun knownFolder(name: String): Path? {
        val command = "[Environment]::GetFolderPath([Environment+SpecialFolder]::$name)"
        val process = ProcessBuilder(
            "powershell.exe", "-WindowStyle", "Hidden", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-Command", command,
        ).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        return value.takeIf { it.isNotBlank() }?.let(Path::of)
    }

    private fun setShortcutIconsElevated(shortcuts: List<Path>, iconFile: Path) {
        runCatching {
            val dollar = '$'
            val iconPath = iconFile.toString().replace("'", "''")
            val paths = shortcuts.joinToString(",") { "'${it.toString().replace("'", "''")}'" }
            val script = listOf(
                "${dollar}icon = '${iconPath}'",
                "${dollar}shell = New-Object -ComObject WScript.Shell",
                "${dollar}shortcuts = @(${paths})",
                "${dollar}shortcuts | ForEach-Object {",
                "    ${dollar}shortcut = ${dollar}shell.CreateShortcut(${dollar}_)",
                "    ${dollar}shortcut.IconLocation = ${dollar}icon + ',0'",
                "    ${dollar}shortcut.Save()",
                "}",
            ).joinToString("\n")
            val scriptFile = Files.createTempFile("nuvio-icon-update-", ".ps1")
            Files.writeString(scriptFile, script)
            val escapedScriptPath = scriptFile.toString().replace("'", "''")
            val launcher = "Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -ArgumentList " +
                "@('-WindowStyle','Hidden','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File','${escapedScriptPath}')"
            runPowerShell(ProcessBuilder("powershell.exe", "-WindowStyle", "Hidden", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", launcher).start(), 60)
            Files.deleteIfExists(scriptFile)
        }
    }

    private fun setShortcutIcon(shortcut: Path, iconFile: Path) {
        val shortcutArg = shortcut.toString().replace("'", "''")
        val iconArg = (iconFile.toString() + ",0").replace("'", "''")
        val dollar = '$'
        val script = listOf(
            "${dollar}shell = New-Object -ComObject WScript.Shell",
            "${dollar}shortcut = ${dollar}shell.CreateShortcut('${shortcutArg}')",
            "${dollar}shortcut.IconLocation = '${iconArg}'",
            "${dollar}shortcut.Save()",
        ).joinToString("\n")
        runCatching { runPowerShell(ProcessBuilder("powershell.exe", "-WindowStyle", "Hidden", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script).start()) }
    }

    private fun runPowerShell(process: Process, timeoutSeconds: Long = 3) {
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}