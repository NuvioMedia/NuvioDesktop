package com.nuvio.app.features.player

import java.io.File

internal data class MacosExternalPlayerInstall(
    val id: String,
    val name: String,
    val executablePath: String,
)

internal fun detectMacosExternalPlayers(
    getenv: (String) -> String? = System::getenv,
    fileExists: (String) -> Boolean = { File(it).isFile },
    canExecute: (String) -> Boolean = { File(it).canExecute() },
): List<MacosExternalPlayerInstall> {
    val pathEntries = getenv("PATH")
        ?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        .orEmpty()

    val homebrewDirs = listOf(
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/opt/local/bin",
    )

    val searchDirs = pathEntries + homebrewDirs

    val appDirs = listOfNotNull(
        "/Applications",
        File(System.getProperty("user.home"), "Applications").absolutePath.takeIf {
            File(it).isDirectory
        },
    )

    val binaryPlayers = listOf(
        "vlc" to "VLC",
        "mpv" to "mpv",
    )

    val foundBinaries = binaryPlayers.mapNotNull { (exec, name) ->
        searchDirs.firstNotNullOfOrNull { dir ->
            val file = File(dir, exec)
            if (fileExists(file.absolutePath) && canExecute(file.absolutePath)) file.absolutePath else null
        }?.let { MacosExternalPlayerInstall(id = exec, name = name, executablePath = it) }
    }

    val foundAppBundles = appDirs.flatMap { appDir ->
        val appBundles = mapOf(
            "VLC.app" to ("VLC" to "Contents/MacOS/VLC"),
            "IINA.app" to ("IINA" to "Contents/MacOS/iina-cli"),
            "mpv.app" to ("mpv (App)" to "Contents/MacOS/mpv"),
        )

        appBundles.mapNotNull { (bundle, pair) ->
            val (name, relativeBinary) = pair
            val bundlePath = File(appDir, bundle)
            val executable = File(bundlePath, relativeBinary)
            if (executable.isFile && executable.canExecute()) {
                MacosExternalPlayerInstall(
                    id = bundle.substringBefore(".app").lowercase(),
                    name = name,
                    executablePath = executable.absolutePath,
                )
            } else null
        }
    }

    val foundIinaFromCli = findIinaCli(searchDirs, fileExists, canExecute)

    return foundBinaries + foundAppBundles + foundIinaFromCli
}

private fun findIinaCli(
    searchDirs: List<String>,
    fileExists: (String) -> Boolean,
    canExecute: (String) -> Boolean,
): List<MacosExternalPlayerInstall> {
    val iinaCliPaths = listOf(
        "/Applications/IINA.app/Contents/MacOS/iina-cli",
        File(System.getProperty("user.home"), "Applications/IINA.app/Contents/MacOS/iina-cli").absolutePath,
    )

    val foundInBundle = iinaCliPaths.firstOrNull { path ->
        fileExists(path) && canExecute(path)
    }

    if (foundInBundle != null) {
        return listOf(
            MacosExternalPlayerInstall(
                id = "iina",
                name = "IINA",
                executablePath = foundInBundle,
            )
        )
    }

    val pathIina = searchDirs.firstNotNullOfOrNull { dir ->
        val file = File(dir, "iina-cli")
        if (fileExists(file.absolutePath) && canExecute(file.absolutePath)) file.absolutePath else null
    }

    if (pathIina != null) {
        return listOf(
            MacosExternalPlayerInstall(
                id = "iina",
                name = "IINA",
                executablePath = pathIina,
            )
        )
    }

    return emptyList()
}

internal val macosDesktopPlayerDefinitions: List<DesktopPlayerDefinition> = listOf(
    DesktopPlayerDefinition(id = "vlc", name = "VLC", kind = DesktopPlayerKind.Vlc),
    DesktopPlayerDefinition(id = "mpv", name = "mpv", kind = DesktopPlayerKind.Mpv),
    DesktopPlayerDefinition(id = "iina", name = "IINA", kind = DesktopPlayerKind.Iina),
)

internal fun MacosExternalPlayerInstall.toDesktopPlayerInstall(): DesktopPlayerInstall {
    val kind = when {
        id.contains("iina", ignoreCase = true) -> DesktopPlayerKind.Iina
        id.contains("vlc", ignoreCase = true) -> DesktopPlayerKind.Vlc
        id.contains("mpv", ignoreCase = true) -> DesktopPlayerKind.Mpv
        executablePath.contains("iina", ignoreCase = true) -> DesktopPlayerKind.Iina
        executablePath.contains("vlc", ignoreCase = true) -> DesktopPlayerKind.Vlc
        executablePath.contains("mpv", ignoreCase = true) -> DesktopPlayerKind.Mpv
        else -> DesktopPlayerKind.Mpv
    }
    return DesktopPlayerInstall(
        definition = DesktopPlayerDefinition(id = id, name = name, kind = kind),
        executablePath = executablePath,
    )
}


