import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File
import java.util.Properties
import javax.inject.Inject

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @get:Input
    abstract val desktopAppVersionName: Property<String>

    @get:Input
    abstract val desktopAppVersionCode: Property<Int>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${props.getProperty("SUPABASE_URL", "")}" 
                |    const val ANON_KEY = "${props.getProperty("SUPABASE_ANON_KEY", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}" 
                |    const val CLIENT_SECRET = "${props.getProperty("TRAKT_CLIENT_SECRET", "")}" 
                |    const val REDIRECT_URI = "${props.getProperty("TRAKT_REDIRECT_URI", "nuvio://auth/trakt")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/details").apply {
            mkdirs()
            resolve("ImdbEpisodeRatingsConfig.kt").writeText(
                """
                |package com.nuvio.app.features.details
                |
                |object ImdbEpisodeRatingsConfig {
                |    const val IMDB_RATINGS_API_BASE_URL = "${props.getProperty("IMDB_RATINGS_API_BASE_URL", "")}" 
                |    const val IMDB_TAPFRAME_API_BASE_URL = "${props.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/debrid").apply {
            mkdirs()
            resolve("PremiumizeConfig.kt").writeText(
                """
                |package com.nuvio.app.features.debrid
                |
                |object PremiumizeConfig {
                |    const val CLIENT_ID = "${props.getProperty("PREMIUMIZE_CLIENT_ID", "")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |    const val DESKTOP_VERSION_NAME = "${desktopAppVersionName.get()}"
                |    const val DESKTOP_VERSION_CODE = ${desktopAppVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val CONTRIBUTIONS_URL = "${props.getProperty("CONTRIBUTIONS_URL", "")}" 
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}" 
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }
    }
}

abstract class NotarizeMacosDmgWithKeychainTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val dmgDir: DirectoryProperty

    @get:OutputDirectory
    abstract val artifactDir: DirectoryProperty

    @get:Input
    abstract val finalDmgName: Property<String>

    @get:Input
    abstract val defaultDmgName: Property<String>

    @get:Input
    abstract val keychainProfile: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun notarize() {
        val profile = keychainProfile.get().trim()
        require(profile.isNotEmpty()) {
            "Set NUVIO_MACOS_NOTARY_PASSWORD=@keychain:<profile> or NUVIO_MACOS_NOTARY_KEYCHAIN_PROFILE=<profile>."
        }

        val dmg = ensureFinalDmg()
        execOperations.exec {
            commandLine(
                "xcrun",
                "notarytool",
                "submit",
                dmg.absolutePath,
                "--wait",
                "--keychain-profile",
                profile,
            )
        }
        execOperations.exec {
            commandLine("xcrun", "stapler", "staple", dmg.absolutePath)
        }
        publishDmg(dmg)
        logger.lifecycle("Notarized and stapled macOS DMG: ${dmg.absolutePath}")
    }

    private fun ensureFinalDmg(): File {
        val outputDir = dmgDir.get().asFile
        val finalDmg = outputDir.resolve(finalDmgName.get())
        val defaultDmg = outputDir.resolve(defaultDmgName.get())
        val sourceDmg = defaultDmg.takeIf { it.exists() }
            ?: finalDmg.takeIf { it.exists() }
            ?: error("Expected macOS DMG output in ${outputDir.absolutePath}")

        if (sourceDmg != finalDmg) {
            if (finalDmg.exists() && !finalDmg.delete()) {
                error("Could not replace existing DMG: ${finalDmg.absolutePath}")
            }
            if (!sourceDmg.renameTo(finalDmg)) {
                sourceDmg.copyTo(finalDmg, overwrite = true)
                if (!sourceDmg.delete()) {
                    logger.warn("Could not delete old DMG after copy: ${sourceDmg.absolutePath}")
                }
            }
        }

        logger.lifecycle("macOS DMG artifact: ${finalDmg.absolutePath}")
        return finalDmg
    }

    private fun publishDmg(dmg: File) {
        val publishedDir = artifactDir.get().asFile
        publishedDir.mkdirs()
        val publishedDmg = publishedDir.resolve(dmg.name)
        if (dmg.canonicalFile != publishedDmg.canonicalFile) {
            dmg.copyTo(publishedDmg, overwrite = true)
        }
        logger.lifecycle("Published macOS DMG artifact: ${publishedDmg.absolutePath}")
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

fun cmdQuote(value: String): String = "\"${value.replace("\"", "\"\"")}\""

fun psSingleQuote(value: String): String = "'${value.replace("'", "''")}'"

fun semanticVersionSortKey(value: String): String =
    value.split('.', '-', '_')
        .joinToString(".") { part ->
            part.toIntOrNull()?.toString()?.padStart(8, '0') ?: part
        }

fun newestDirectory(root: File): File? =
    root.takeIf(File::exists)
        ?.listFiles(File::isDirectory)
        ?.maxByOrNull { semanticVersionSortKey(it.name) }

fun jpackageCompatibleVersion(version: String): String {
    val versionCore = version.substringBefore('-').substringBefore('+').trim()
    val parts = versionCore.split('.').filter { it.isNotBlank() }
    require(parts.isNotEmpty() && parts.size <= 3) {
        "Desktop package version must use one to three numeric components: $version"
    }
    val numbers = parts.map { part ->
        part.toIntOrNull() ?: error("Desktop package version component is not numeric: $version")
    }.toMutableList()
    require(numbers.all { it >= 0 }) {
        "Desktop package version components must not be negative: $version"
    }
    while (numbers.size < 3) {
        numbers += 0
    }
    numbers[0] = numbers[0].coerceAtLeast(1)
    return numbers.joinToString(".")
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseStoreFile = supabaseProps.getProperty("NUVIO_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = supabaseProps.getProperty("NUVIO_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = supabaseProps.getProperty("NUVIO_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = supabaseProps.getProperty("NUVIO_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = releaseStoreFile?.let(rootProject::file)

fun localOrEnvProperty(name: String): String? =
    (
        providers.gradleProperty(name).orNull
            ?: System.getenv(name)
            ?: supabaseProps.getProperty(name)
        )
        ?.trim()
        ?.takeIf { it.isNotBlank() }

val macosSigningIdentity = localOrEnvProperty("NUVIO_MACOS_SIGNING_IDENTITY")
val macosNotaryAppleId = localOrEnvProperty("NUVIO_MACOS_NOTARY_APPLE_ID")
val macosNotaryTeamId = localOrEnvProperty("NUVIO_MACOS_NOTARY_TEAM_ID")
val macosNotaryPassword = localOrEnvProperty("NUVIO_MACOS_NOTARY_PASSWORD")
val macosNotaryKeychainProfile = localOrEnvProperty("NUVIO_MACOS_NOTARY_KEYCHAIN_PROFILE")
    ?: macosNotaryPassword
        ?.takeIf { it.startsWith("@keychain:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
val macosNotaryAppSpecificPassword = macosNotaryPassword
    ?.takeUnless { it.startsWith("@keychain:", ignoreCase = true) }

val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val desktopVersionConfigFile = rootProject.file("composeApp/Configuration/DesktopVersion.properties")
val desktopVersionProps = Properties().apply {
    if (desktopVersionConfigFile.exists()) {
        desktopVersionConfigFile.inputStream().use { load(it) }
    }
}
val desktopReleaseVersionName = (
    providers.gradleProperty("nuvio.desktop.versionName").orNull
        ?: System.getenv("NUVIO_DESKTOP_VERSION_NAME")
        ?: supabaseProps.getProperty("NUVIO_DESKTOP_VERSION_NAME")
        ?: desktopVersionProps.getProperty("VERSION_NAME")
        ?: "0.1.0"
    ).trim()
require(desktopReleaseVersionName.isNotBlank()) {
    "Desktop version name must not be blank."
}
val desktopReleaseVersionCode = (
    providers.gradleProperty("nuvio.desktop.versionCode").orNull
        ?: System.getenv("NUVIO_DESKTOP_VERSION_CODE")
        ?: supabaseProps.getProperty("NUVIO_DESKTOP_VERSION_CODE")
        ?: desktopVersionProps.getProperty("VERSION_CODE")
    )?.trim()
    ?.takeIf { it.isNotBlank() }
    ?.toIntOrNull()
    ?: 1
val desktopReleasePackageVersion = jpackageCompatibleVersion(desktopReleaseVersionName)
val iosDistribution = (
    providers.gradleProperty("nuvio.ios.distribution").orNull
        ?: System.getenv("NUVIO_IOS_DISTRIBUTION")
        ?: supabaseProps.getProperty("NUVIO_IOS_DISTRIBUTION")
        ?: "appstore"
    ).trim().lowercase()
require(iosDistribution == "appstore" || iosDistribution == "full") {
    "NUVIO_IOS_DISTRIBUTION must be 'appstore' or 'full'."
}
val iosDistributionSourceDir = if (iosDistribution == "full") {
    "src/iosFull/kotlin"
} else {
    "src/iosAppStore/kotlin"
}
val iosFrameworkBundleId = "com.nuvio.media"
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val fullPluginSourceDir = fullCommonSourceDir.resolve("com/nuvio/app/features/plugins")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val requestedGradleTasks = gradle.startParameter.taskNames.map { taskName ->
    taskName.substringAfterLast(':').lowercase()
}
val isAndroidAppBundleBuild = requestedGradleTasks.any { taskName ->
    taskName == "bundle" ||
        taskName == "bundlerelease" ||
        taskName == "bundledebug" ||
        taskName.startsWith("bundleplaystore") ||
        taskName.startsWith("bundlefull") ||
        taskName.endsWith("bundle")
}

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
    desktopAppVersionName.set(desktopReleaseVersionName)
    desktopAppVersionCode.set(desktopReleaseVersionCode)
}

val isMacHost = System.getProperty("os.name").contains("mac", ignoreCase = true)
val isWindowsHost = System.getProperty("os.name").contains("win", ignoreCase = true)
val mpvKitDir = providers.gradleProperty("nuvio.mpvkit.dir")
    .orElse(rootProject.layout.projectDirectory.dir("MPVKit").asFile.absolutePath)
val macosPlayerBridgeSource = layout.projectDirectory.file("src/desktopMain/native/macos/player_bridge.mm")
fun normalizedMacosArch(value: String): String =
    when (value.lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x64", "x86_64" -> "x86_64"
        else -> error("Unsupported macOS architecture '$value'. Use arm64 or x86_64.")
    }
val macosHostJvmArch = normalizedMacosArch(System.getProperty("os.arch"))
val requestedMacosArch = providers.gradleProperty("nuvio.macos.arch").orNull
    ?: System.getenv("NUVIO_MACOS_ARCH")
val macosPlayerBridgeArch = requestedMacosArch?.let(::normalizedMacosArch) ?: macosHostJvmArch
val isMacosDmgBuildRequested = requestedGradleTasks.any { taskName ->
    taskName == "packagedmg" ||
        taskName == "packagereleasedmg" ||
        taskName == "notarizedmg" ||
        taskName == "notarizereleasedmg" ||
        taskName == "notarizereleasedmgwithkeychain"
}
if (isMacHost && isMacosDmgBuildRequested && macosPlayerBridgeArch != macosHostJvmArch) {
    error(
        "macOS DMG architecture '$macosPlayerBridgeArch' must match the Gradle JVM architecture '$macosHostJvmArch'. " +
            "Run Gradle with a $macosPlayerBridgeArch JDK/Rosetta, then pass -Pnuvio.macos.arch=$macosPlayerBridgeArch."
    )
}
val macosPlayerBridgeOutput = layout.buildDirectory.file("native/macos/$macosPlayerBridgeArch/libplayer_bridge.dylib")
val macosDmgArchName = macosPlayerBridgeArch
val isMacosDmgNotarizationRequested = requestedGradleTasks.any { taskName ->
    taskName == "notarizedmg" || taskName == "notarizereleasedmg"
}
val mpvKitRoot = File(mpvKitDir.get())
val mpvKitDistRoot = File(mpvKitRoot, "dist")
val mpvKitLibmpvRoot = File(mpvKitDistRoot, "libmpv/macos/thin/$macosPlayerBridgeArch")
val mpvKitLibmpvPkgConfigFile = File(mpvKitLibmpvRoot, "lib/pkgconfig/mpv.pc")
val mpvKitGeneratedPkgConfigDirs = if (mpvKitDistRoot.exists()) {
    mpvKitDistRoot.walkTopDown()
        .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith("/macos/thin/$macosPlayerBridgeArch/lib/pkgconfig") }
        .toList()
        .sortedBy { it.absolutePath }
} else {
    emptyList()
}
val mpvKitGeneratedLibSearchArgs = mpvKitGeneratedPkgConfigDirs
    .mapNotNull { it.parentFile }
    .distinctBy { it.absolutePath }
    .joinToString(" ") { "-L${shellQuote(it.absolutePath)}" }
val missingMpvKitMacosFrameworks = if (mpvKitLibmpvPkgConfigFile.exists()) emptyList() else listOf("mpv.pc")
val missingMpvKitMacosMessage = """
    MPVKit macOS libmpv artifacts are missing for $macosPlayerBridgeArch: ${missingMpvKitMacosFrameworks.joinToString()}.
    The setup task will auto-download them from Soia's mpv release.
    Or run manually: scripts/setup-macos-libs.sh
    Or pass -Pnuvio.mpvkit.dir=/absolute/path/to/MPVKit.
""".trimIndent()
val missingMpvKitMacosShellMessage = missingMpvKitMacosMessage.replace("'", "'\"'\"'")
val macosPlayerBridgeSourceFile = macosPlayerBridgeSource.asFile
val macosPlayerBridgeOutputFile = macosPlayerBridgeOutput.get().asFile
val macosPlayerBridgeJavaHome = providers.systemProperty("java.home").get()
val mpvKitLibmpvStaticLib = File(mpvKitLibmpvRoot, "lib/libmpv.a")
val mpvKitLibmpvDynamicLib = File(mpvKitLibmpvRoot, "lib/libmpv.2.dylib")
val mpvKitHasValidLib = mpvKitLibmpvStaticLib.exists() || mpvKitLibmpvDynamicLib.exists()

// Auto-setup task: downloads mpv macOS libs + headers if MPVKit is empty
val setupMacosMpvLibs = tasks.register<Exec>("setupMacosMpvLibs") {
    description = "Download mpv macOS libraries from Soia release + headers from official mpv repo"
    notCompatibleWithConfigurationCache("Downloads external binaries")
    onlyIf("macOS host with empty MPVKit") {
        isMacHost && !mpvKitHasValidLib
    }
    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine("bash", "scripts/setup-macos-libs.sh")
}

if (isMacHost) {
    macosPlayerBridgeOutputFile.parentFile.mkdirs()
}
val macosPlayerBridgeCommand = if (!isMacHost) {
    // Not on macOS — task will be disabled, provide a no-op command
    listOf("/bin/sh", "-c", "true")
} else if (missingMpvKitMacosFrameworks.isNotEmpty()) {
    mutableListOf(
        "/bin/sh",
        "-c",
        """
        set -eu
        # Check if setup script already ran (libs present but mpv.pc might not be found yet)
        if [ -f ${shellQuote(mpvKitLibmpvDynamicLib.absolutePath)} ]; then
            echo "[buildMacosPlayerBridge] libmpv.2.dylib found, but mpv.pc not in pkg-config path."
        fi
        # Let the build continue — if pkg-config fails, we'll see the error
        SDKROOT="${'$'}(xcrun --sdk macosx --show-sdk-path)"
        DEFAULT_PC="${'$'}(pkg-config --variable pc_path pkg-config)"
        export PKG_CONFIG_LIBDIR=${shellQuote(mpvKitGeneratedPkgConfigDirs.joinToString(":"))}:"${'$'}{DEFAULT_PC}"
        exec xcrun clang++ \
          -std=c++17 \
          -dynamiclib \
          -fobjc-arc \
          -ObjC++ \
          -arch ${shellQuote(macosPlayerBridgeArch)} \
          -isysroot "${'$'}{SDKROOT}" \
          -mmacosx-version-min=11.0 \
          ${shellQuote(macosPlayerBridgeSourceFile.absolutePath)} \
          -o ${shellQuote(macosPlayerBridgeOutputFile.absolutePath)} \
          -I${shellQuote("$macosPlayerBridgeJavaHome/include")} \
          -I${shellQuote("$macosPlayerBridgeJavaHome/include/darwin")} \
          -I${shellQuote(File(mpvKitLibmpvRoot, "include").absolutePath)} \
          $mpvKitGeneratedLibSearchArgs \
          -framework AppKit \
          -framework WebKit \
          -framework Metal \
          -framework Security \
          -framework IOKit \
          -framework OpenGL \
          -framework QuartzCore \
          -lc++ \
          ${'$'}(pkg-config --libs mpv)
        """.trimIndent(),
    )
} else {
    mutableListOf(
        "/bin/sh",
        "-c",
        """
        set -eu
        SDKROOT="${'$'}(xcrun --sdk macosx --show-sdk-path)"
        DEFAULT_PC="${'$'}(pkg-config --variable pc_path pkg-config)"
        export PKG_CONFIG_LIBDIR=${shellQuote(mpvKitGeneratedPkgConfigDirs.joinToString(":"))}:"${'$'}{DEFAULT_PC}"
        exec xcrun clang++ \
          -std=c++17 \
          -dynamiclib \
          -fobjc-arc \
          -ObjC++ \
          -arch ${shellQuote(macosPlayerBridgeArch)} \
          -isysroot "${'$'}{SDKROOT}" \
          -mmacosx-version-min=11.0 \
          ${shellQuote(macosPlayerBridgeSourceFile.absolutePath)} \
          -o ${shellQuote(macosPlayerBridgeOutputFile.absolutePath)} \
          -I${shellQuote("$macosPlayerBridgeJavaHome/include")} \
          -I${shellQuote("$macosPlayerBridgeJavaHome/include/darwin")} \
          -I${shellQuote(File(mpvKitLibmpvRoot, "include").absolutePath)} \
          $mpvKitGeneratedLibSearchArgs \
          -framework AppKit \
          -framework WebKit \
          -framework Metal \
          -framework Security \
          -framework IOKit \
          -framework OpenGL \
          -framework QuartzCore \
          -lc++ \
          ${'$'}(pkg-config --libs --static mpv)
        """.trimIndent(),
    )
}
val buildMacosPlayerBridge = tasks.register<Exec>("buildMacosPlayerBridge") {
    notCompatibleWithConfigurationCache("Builds a host-local player bridge against MPVKit's macOS libmpv artifacts.")
    enabled = isMacHost
    dependsOn(setupMacosMpvLibs)
    inputs.file(macosPlayerBridgeSource)
    if (mpvKitLibmpvStaticLib.exists()) {
        inputs.file(mpvKitLibmpvStaticLib)
    }
    if (mpvKitLibmpvDynamicLib.exists()) {
        inputs.file(mpvKitLibmpvDynamicLib)
    }
    if (mpvKitLibmpvPkgConfigFile.exists()) {
        inputs.file(mpvKitLibmpvPkgConfigFile)
    }
    inputs.files(mpvKitGeneratedPkgConfigDirs.mapNotNull { it.parentFile?.resolve("lib")?.takeIf(File::exists) })
    outputs.file(macosPlayerBridgeOutput)
    commandLine(macosPlayerBridgeCommand)
}

val windowsPlayerBridgeArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "arm64"
    "x86" -> "x86"
    else -> "x64"
}
val windowsPlayerBridgeSource = layout.projectDirectory.file("src/desktopMain/native/windows/player_bridge.cpp")
val windowsPlayerBridgeOutput = layout.buildDirectory.file("native/windows/player_bridge.dll")
val windowsPlayerBridgeImportLib = layout.buildDirectory.file("native/windows/player_bridge.lib")
val windowsPlayerBridgePdb = layout.buildDirectory.file("native/windows/player_bridge.pdb")
val windowsPlayerBridgeObj = layout.buildDirectory.file("native/windows/player_bridge.obj")
val windowsPlayerBridgeScript = layout.buildDirectory.file("native/windows/build-player-bridge.bat")
val windowsPlayerRuntimeOutput = layout.buildDirectory.dir("native/windows-runtime")
if (isWindowsHost) {
    windowsPlayerBridgeOutput.get().asFile.parentFile.mkdirs()
}
val windowsWebView2Root = providers.gradleProperty("nuvio.webview2.dir").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
    ?: newestDirectory(File(System.getProperty("user.home"), ".nuget/packages/microsoft.web.webview2"))
    ?: File("__missing_webview2__")
val windowsWebView2IncludeDir = File(windowsWebView2Root, "build/native/include")
val windowsWebView2NativeDir = File(windowsWebView2Root, "build/native/$windowsPlayerBridgeArch")
val windowsWebView2LoaderLib = File(windowsWebView2NativeDir, "WebView2Loader.dll.lib")
val windowsWebView2LoaderDll = File(windowsWebView2NativeDir, "WebView2Loader.dll")
val bundledWindowsLibmpvRuntimeDir = layout.projectDirectory.dir("src/desktopMain/native/windows/runtime").asFile
val windowsLibmpvRuntimeDirOverride = providers.gradleProperty("nuvio.windows.libmpv.runtimeDir").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
val windowsLibmpvRuntimeDir = windowsLibmpvRuntimeDirOverride
    ?: bundledWindowsLibmpvRuntimeDir.takeIf { File(it, "libmpv-2.dll").exists() }
val windowsLibmpvDllOverride = providers.gradleProperty("nuvio.windows.libmpv.dll").orNull
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
val windowsLibmpvDll = windowsLibmpvDllOverride
    ?: windowsLibmpvRuntimeDir?.resolve("libmpv-2.dll")
    ?: listOf(
        File("C:/msys64/ucrt64/bin/libmpv-2.dll"),
        File("C:/msys64/mingw64/bin/libmpv-2.dll"),
    ).firstOrNull(File::exists)
val windowsCppRuntimeDllNames = listOf(
    "vcruntime140.dll",
    "vcruntime140_1.dll",
    "msvcp140.dll",
    "msvcp140_1.dll",
    "msvcp140_2.dll",
    "msvcp140_atomic_wait.dll",
    "msvcp140_codecvt_ids.dll",
    "concrt140.dll",
)
val windowsCppRuntimeDlls = if (isWindowsHost) {
    windowsCppRuntimeDllNames
        .map { File("C:/Windows/System32", it) }
        .filter(File::exists)
} else {
    emptyList()
}
val windowsVsWhere = File("C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe")
val windowsVcvarsRelativePath = when (windowsPlayerBridgeArch) {
    "x86" -> "VC\\Auxiliary\\Build\\vcvars32.bat"
    "arm64" -> "VC\\Auxiliary\\Build\\vcvarsarm64.bat"
    else -> "VC\\Auxiliary\\Build\\vcvars64.bat"
}
val windowsVcvarsPath = providers.gradleProperty("nuvio.windows.vcvars.path").orNull
    ?.takeIf { it.isNotBlank() }
val windowsPlayerBridgeJavaHome = providers.systemProperty("java.home").get()
val missingWindowsPlayerBridgeInputs = listOfNotNull(
    "WebView2.h".takeUnless { windowsWebView2IncludeDir.resolve("WebView2.h").exists() },
    "WebView2Loader.dll.lib".takeUnless { windowsWebView2LoaderLib.exists() },
)
val missingWindowsPlayerBridgeMessage = """
    Windows desktop player bridge inputs are missing: ${missingWindowsPlayerBridgeInputs.joinToString()}.
    Install the Microsoft.Web.WebView2 NuGet package or pass -Pnuvio.webview2.dir=C:/path/to/microsoft.web.webview2/version.
    libmpv is loaded at runtime; pass -Pnuvio.windows.libmpv.runtimeDir=C:/path/to/mpv-dlls to bundle it.
""".trimIndent()
val windowsPlayerBridgeCommand = if (missingWindowsPlayerBridgeInputs.isNotEmpty()) {
    listOf(
        "cmd",
        "/c",
        "echo ${missingWindowsPlayerBridgeMessage.replace("\n", " ")} 1>&2 && exit /b 1",
    )
} else {
    val sourceFile = windowsPlayerBridgeSource.asFile
    val outputFile = windowsPlayerBridgeOutput.get().asFile
    val importLibFile = windowsPlayerBridgeImportLib.get().asFile
    val pdbFile = windowsPlayerBridgePdb.get().asFile
    val objFile = windowsPlayerBridgeObj.get().asFile
    val javaIncludeDir = File(windowsPlayerBridgeJavaHome, "include")
    val javaWin32IncludeDir = File(javaIncludeDir, "win32")
    val compileCommand = listOf(
        "cl",
        "/nologo",
        "/EHsc",
        "/std:c++17",
        "/LD",
        "/DUNICODE",
        "/D_UNICODE",
        "/DNOMINMAX",
        "/DWIN32_LEAN_AND_MEAN",
        "/permissive-",
        cmdQuote(sourceFile.absolutePath),
        "/I${cmdQuote(javaIncludeDir.absolutePath)}",
        "/I${cmdQuote(javaWin32IncludeDir.absolutePath)}",
        "/I${cmdQuote(windowsWebView2IncludeDir.absolutePath)}",
        "/Fo${cmdQuote(objFile.absolutePath)}",
        "/Fd${cmdQuote(pdbFile.absolutePath)}",
        "/Fe${cmdQuote(outputFile.absolutePath)}",
        "/link",
        "/NOLOGO",
        "/INCREMENTAL:NO",
        "/IMPLIB:${cmdQuote(importLibFile.absolutePath)}",
        cmdQuote(windowsWebView2LoaderLib.absolutePath),
        "Ole32.lib",
        "User32.lib",
        "Gdi32.lib",
        "Dwmapi.lib",
    ).joinToString(" ")
    val powershellCompileCommand = compileCommand.replace("\"", "__DQ__")
    val powershellCommand = """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}dq = [char]34
        ${'$'}vcvars = ${psSingleQuote(windowsVcvarsPath.orEmpty())}
        if ([string]::IsNullOrWhiteSpace(${'$'}vcvars)) {
          ${'$'}vswhere = ${psSingleQuote(windowsVsWhere.absolutePath)}
          if (Test-Path -LiteralPath ${'$'}vswhere) {
            ${'$'}vcvars = & ${'$'}vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -find ${psSingleQuote(windowsVcvarsRelativePath)} | Select-Object -First 1
          }
        }
        if ([string]::IsNullOrWhiteSpace(${'$'}vcvars) -or -not (Test-Path -LiteralPath ${'$'}vcvars)) {
          Write-Error 'Visual Studio C++ toolchain was not found. Install MSVC or pass -Pnuvio.windows.vcvars.path=C:\path\to\vcvars64.bat.'
          exit 1
        }
        ${'$'}vcvars = ([string]${'$'}vcvars).Trim()
        ${'$'}bat = ${psSingleQuote(windowsPlayerBridgeScript.get().asFile.absolutePath)}
        ${'$'}compile = ${psSingleQuote(powershellCompileCommand)}.Replace('__DQ__', ${'$'}dq)
        ${'$'}lines = @(
          '@echo off',
          ('set {0}VCVARS={1}{0}' -f ${'$'}dq, ${'$'}vcvars),
          ('call {0}%VCVARS%{0} >nul' -f ${'$'}dq),
          'if errorlevel 1 exit /b %errorlevel%',
          ${'$'}compile,
          'exit /b %ERRORLEVEL%'
        )
        Set-Content -LiteralPath ${'$'}bat -Value ${'$'}lines -Encoding ASCII
        & cmd.exe /d /c ${'$'}bat
        ${'$'}code = ${'$'}LASTEXITCODE
        if (${'$'}code -ne 0) { exit ${'$'}code }
    """.trimIndent()
    listOf(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        powershellCommand,
    )
}
val buildWindowsPlayerBridge = tasks.register<Exec>("buildWindowsPlayerBridge") {
    notCompatibleWithConfigurationCache("Builds a host-local player bridge against WebView2 and libmpv for Windows.")
    enabled = isWindowsHost
    inputs.file(windowsPlayerBridgeSource)
    if (windowsWebView2IncludeDir.exists()) {
        inputs.dir(windowsWebView2IncludeDir)
    }
    if (windowsWebView2LoaderLib.exists()) {
        inputs.file(windowsWebView2LoaderLib)
    }
    outputs.file(windowsPlayerBridgeOutput)
    outputs.file(windowsPlayerBridgeImportLib)
    outputs.file(windowsPlayerBridgePdb)
    commandLine(windowsPlayerBridgeCommand)
}

val prepareWindowsPlayerRuntime = tasks.register<Sync>("prepareWindowsPlayerRuntime") {
    enabled = isWindowsHost
    into(windowsPlayerRuntimeOutput)
    if (windowsWebView2LoaderDll.exists()) {
        from(windowsWebView2LoaderDll)
    }
    windowsCppRuntimeDlls.forEach { dllFile ->
        from(dllFile)
    }
    when {
        windowsLibmpvRuntimeDir?.exists() == true -> {
            from(windowsLibmpvRuntimeDir) {
                include("*.dll")
            }
        }
        windowsLibmpvDll?.exists() == true -> {
            from(windowsLibmpvDll)
        }
    }
}

val generateWindowsPlayerRuntimeIndex = tasks.register<GenerateNativeRuntimeIndexTask>("generateWindowsPlayerRuntimeIndex") {
    enabled = isWindowsHost
    dependsOn(prepareWindowsPlayerRuntime)
    runtimeDir.set(windowsPlayerRuntimeOutput)
    indexFile.set(windowsPlayerRuntimeOutput.map { it.file("runtime-files.txt") })
}

abstract class GenerateNativeRuntimeIndexTask : DefaultTask() {
    @get:InputDirectory
    abstract val runtimeDir: DirectoryProperty

    @get:OutputFile
    abstract val indexFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val dir = runtimeDir.get().asFile
        val files = dir
            .listFiles { file -> file.isFile && file.name != indexFile.get().asFile.name }
            .orEmpty()
            .map { it.name }
            .sorted()
        indexFile.get().asFile.writeText(files.joinToString(separator = "\n", postfix = "\n"))
    }
}

val mediampvRustDir = rootDir.resolve("mediamp/mediamp-mpv-rust")
val mediampvBuildCiDir = rootDir.resolve("mediamp/mediamp-mpv/build-ci")

val buildRustMediampv = tasks.register<Exec>("buildRustMediampv") {
    description = "Build mediampv native library via cargo for Windows desktop"
    enabled = isWindowsHost
    workingDir = mediampvRustDir
    commandLine("cargo", "build", "--release")
    inputs.file(mediampvRustDir.resolve("Cargo.toml"))
    inputs.dir(mediampvRustDir.resolve("src"))
    outputs.file(mediampvRustDir.resolve("target/release/mediampv.dll"))
}

val mediampPrebuiltWinDir = rootDir.resolve("mediamp/mediamp-mpv/libmpv/lib/windows/x86_64")

val prepareWindowsRustMediampv = tasks.register<Copy>("prepareWindowsRustMediampv") {
    description = "Copy mediampv.dll, libmpv-2.dll and all ffmpeg DLLs to dev lookup directory"
    enabled = isWindowsHost
    dependsOn(buildRustMediampv)
    into(mediampvBuildCiDir)
    from(mediampvRustDir.resolve("target/release")) {
        include("mediampv.dll")
    }
    if (mediampPrebuiltWinDir.isDirectory) {
        from(mediampPrebuiltWinDir) {
            include("*.dll")
        }
    } else {
        val libmpv = windowsLibmpvDll
        if (libmpv?.exists() == true) {
            from(libmpv)
        }
    }
}

tasks.withType<Jar>().configureEach {
    if (isMacHost && name == "desktopJar") {
        dependsOn(buildMacosPlayerBridge)
        from(macosPlayerBridgeOutput) {
            into("native/macos")
        }
    }
    if (isWindowsHost && name == "desktopJar") {
        dependsOn(buildWindowsPlayerBridge, prepareWindowsPlayerRuntime, generateWindowsPlayerRuntimeIndex)
        from(windowsPlayerBridgeOutput) {
            into("native/windows")
        }
        from(windowsPlayerRuntimeOutput) {
            into("native/windows")
        }
    }
}

if (isWindowsHost) {
    val desktopNativePlayerTasks = setOf(
        "run",
        "runRelease",
        "desktopRun",
        "runDistributable",
        "runReleaseDistributable",
        "desktopRunHot",
        "hotRunDesktop",
        "hotRunDesktopAsync",
        "hotDevDesktop",
        "hotDevDesktopAsync",
        "createDistributable",
        "createReleaseDistributable",
        "createRuntimeImage",
        "package",
        "packageDistributionForCurrentOS",
        "packageMsi",
        "packageUberJarForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
        "packageReleaseMsi",
        "packageReleaseUberJarForCurrentOS",
    )
    tasks.matching { it.name in desktopNativePlayerTasks }.configureEach {
        dependsOn(buildWindowsPlayerBridge, prepareWindowsPlayerRuntime, generateWindowsPlayerRuntimeIndex, prepareWindowsRustMediampv)
    }

    val portableLauncherDir = layout.buildDirectory.dir("portable-launcher")

    val generatePortableLauncher by tasks.registering {
        outputs.dir(portableLauncherDir)
        doLast {
            portableLauncherDir.get().asFile.mkdirs()
            portableLauncherDir.get().asFile.resolve("Run.bat").writeText(
                """@echo off
                |start "" "%~dp0Nuvio.exe" %*
                |""".trimMargin()
            )
        }
    }

    val fixReleaseDistributableConfig by tasks.registering {
        notCompatibleWithConfigurationCache("Modifies jpackage-generated config in place")
        dependsOn("createReleaseDistributable")
        doLast {
            val cfgFile = layout.buildDirectory.file("compose/binaries/main-release/app/Nuvio/app/Nuvio.cfg").get().asFile
            if (cfgFile.exists()) {
                val content = cfgFile.readText()
                val updated = content.replace(
                    Regex("(?m)^java-options=-Djava\\.library\\.path=.*$"),
                    "java-options=-Djava.library.path=\\${'$'}APPDIR/lib/native"
                )
                cfgFile.writeText(updated)
            }
        }
    }

    val packageReleasePortable by tasks.registering(Zip::class) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        dependsOn(
            "createReleaseDistributable",
            fixReleaseDistributableConfig,
            generatePortableLauncher,
            buildWindowsPlayerBridge,
            prepareWindowsPlayerRuntime,
            generateWindowsPlayerRuntimeIndex,
            prepareWindowsRustMediampv,
        )

        val distDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Nuvio")
        from(distDir)

        from(windowsPlayerBridgeOutput) { into("lib/native") }
        from(windowsPlayerBridgePdb) { into("lib/native") }
        from(windowsPlayerRuntimeOutput) { into("lib/native") }
        from(mediampvRustDir.resolve("target/release/mediampv.dll")) { into("lib/native") }
        from(mediampvBuildCiDir) {
            include("*.dll")
            into("lib/native")
        }
        from(portableLauncherDir)

        val portableMarkerFile = layout.buildDirectory.file("compose/tmp/portable-marker/Nuvio.portable")
        doFirst {
            portableMarkerFile.get().asFile.also { it.parentFile.mkdirs() }.writeText("")
        }
        from(portableMarkerFile)

        archiveFileName.set("Nuvio-${desktopReleasePackageVersion}-x86_64-portable.zip")
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/portable"))
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    iosTargets.forEach { iosTarget ->
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
            }

            if (iosDistribution == "full") {
                defaultSourceSet.kotlin.srcDir(fullCommonSourceDir)
            }
            defaultSourceSet.kotlin.srcDir(project.file(iosDistributionSourceDir))
            defaultSourceSet.dependencies {
                implementation(libs.ktor.client.darwin)
                if (iosDistribution == "full") {
                    implementation(libs.quickjs.kt)
                    implementation(libs.ksoup)
                }
            }
        }

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=$iosFrameworkBundleId")
        }
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(libs.coil.gif)
            implementation("androidx.recyclerview:recyclerview:1.4.0")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.google.code.gson:gson:2.11.0")
            implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.smoothstreaming)
            implementation(libs.androidx.media3.exoplayer.rtsp)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.decoder)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.container)
            implementation(libs.androidx.media3.extractor)
            implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
        }
        val desktopMain by getting {
            kotlin.srcDir(fullPluginSourceDir)
            dependencies {
                implementation(project(":mediamp:mediamp-api"))
                implementation(project(":mediamp:mediamp-mpv"))
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation(libs.quickjs.kt)
                implementation(libs.ksoup)
                implementation("net.java.dev.jna:jna-platform:5.13.0")
            }
        }
        commonMain.dependencies {
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.coil.svg)
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

val isLinuxHost = org.gradle.internal.os.OperatingSystem.current().isLinux
val linuxBuildCiDir = file("${rootDir}/mediamp/mediamp-mpv/build-ci")
val linuxRuntimeStagingDir = layout.buildDirectory.dir("native/linux-runtime")

compose.desktop {
    application {
        mainClass = "com.nuvio.app.MainKt"
        val smokePlayerUrl = providers.gradleProperty("nuvio.desktop.smokePlayerUrl").orNull
            ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
        jvmArgs += listOfNotNull(
            "-Dskiko.renderApi=OPENGL",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            if (isMacHost) "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua" else null,
            if (isMacHost) "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED" else null,
            if (isWindowsHost) "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED" else null,
            smokePlayerUrl?.takeIf { it.isNotBlank() }?.let { "-Dnuvio.desktop.smokePlayerUrl=$it" },
            System.getenv("NUVIO_MEDIAMP_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
                ?.let { "-Dnuvio.mediamp.runtime.dir=$it" },
            System.getenv("NUVIO_DEV_PLAYER_LOOKUP")?.takeIf { it.equals("true", ignoreCase = true) }
                ?.let { "-Dnuvio.dev.player.lookup=true" },
        ).toMutableList().apply {
            if (isLinuxHost) {
                add("-Djava.library.path=\$APPDIR/../native")
            }
            val mediampDir = System.getenv("NUVIO_MEDIAMP_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
            if (mediampDir != null) {
                val libmpvDir = "${project.rootDir}/mediamp/mediamp-mpv/libmpv/lib/linux/x86_64"
                add("-Djava.library.path=${mediampDir}:${libmpvDir}")
            }
            if (isWindowsHost) {
                add("-Djava.library.path=" + listOf(
                    mediampvBuildCiDir.absolutePath.replace("\\", "/"),
                    mediampvBuildCiDir.resolve("Release").absolutePath.replace("\\", "/"),
                    rootDir.resolve("mediamp/mediamp-mpv/libmpv/lib/windows/x86_64").absolutePath.replace("\\", "/"),
                ).joinToString(";"))
            }
        }

        nativeDistributions {
            val hostOs = System.getProperty("os.name").lowercase()
            when {
                hostOs.contains("windows") -> targetFormats(TargetFormat.Exe, TargetFormat.Msi)
                hostOs.contains("mac") -> targetFormats(TargetFormat.Dmg)
                else -> targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            }
            packageName = "Nuvio"
            packageVersion = desktopReleasePackageVersion
            vendor = "Nuvio Media"
            modules(
                "java.instrument",
                "java.management",
                "java.net.http",
                "jdk.unsupported",
            )
            macOS {
                bundleID = "com.nuvio.media.desktop"
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon.icns"))
                if (macosSigningIdentity != null) {
                    signing {
                        sign.set(true)
                        identity.set(macosSigningIdentity)
                    }
                }
                if (
                    macosNotaryAppleId != null &&
                    macosNotaryTeamId != null &&
                    macosNotaryAppSpecificPassword != null
                ) {
                    notarization {
                        appleID.set(macosNotaryAppleId)
                        teamID.set(macosNotaryTeamId)
                        password.set(macosNotaryAppSpecificPassword)
                    }
                }
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon.ico"))
                shortcut = true
                menu = true
                menuGroup = "Nuvio"
                exePackageVersion = desktopReleasePackageVersion
                msiPackageVersion = desktopReleasePackageVersion
            }
            linux {
                iconFile.set(project.file("desktop-icons/nuvio_256.png"))
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// ==================== Windows Native Runtime Packaging ====================

val packageWindowsNativeRuntime = tasks.register<Copy>("packageWindowsNativeRuntime") {
    enabled = isWindowsHost
    group = "compose desktop"
    description = "Copies MediaMP/MPV native DLLs into the Windows app image."
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val mediampNativeBuildDir = mediampvBuildCiDir
    val mediampPrebuiltDir = rootDir.resolve("mediamp/mediamp-mpv/libmpv/lib/windows/x86_64")
    val nativeDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Nuvio/lib/native")
    val launcherDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Nuvio")

    from(mediampNativeBuildDir) {
        include("*.dll")
    }
    from(mediampNativeBuildDir.resolve("Release")) {
        include("*.dll")
    }
    from(mediampPrebuiltDir) {
        include("*.dll")
    }
    from(windowsPlayerRuntimeOutput) {
        include("*.dll")
    }
    from(windowsPlayerBridgeOutput.map { it.asFile.parentFile }) {
        include("*.dll", "*.pdb")
    }
    into(nativeDir)

    doLast {
        val launcherDirectory = launcherDir.get().asFile
        val nativeDirectory = nativeDir.get().asFile
        nativeDirectory.listFiles { f -> f.isFile && f.extension.equals("dll", ignoreCase = true) }
            .orEmpty()
            .forEach { dll ->
                dll.copyTo(launcherDirectory.resolve(dll.name), overwrite = true)
            }
    }
}

tasks.matching { it.name == "createReleaseDistributable" }.configureEach {
    finalizedBy(packageWindowsNativeRuntime)
}

packageWindowsNativeRuntime.configure {
    mustRunAfter(tasks.matching { it.name == "createReleaseDistributable" })
}

val packageReleaseInnoExe = tasks.register<Exec>("packageReleaseInnoExe") {
    enabled = isWindowsHost
    group = "compose desktop"
    description = "Builds a Windows installer with Inno Setup (no WiX), using the release app image."
    dependsOn("createReleaseDistributable")
    dependsOn(packageWindowsNativeRuntime)

    val appImageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/Nuvio").get().asFile.absolutePath
    val outputDir = layout.buildDirectory.dir("compose/binaries/main-release/inno").get().asFile.absolutePath
    val scriptPath = layout.projectDirectory.file("scripts/package-release-inno.ps1").asFile.absolutePath
    val setupIcon = layout.projectDirectory.file("src/desktopMain/resources/icons/nuvio-app-icon.ico").asFile.absolutePath
    val appIcon = layout.projectDirectory.file("src/desktopMain/resources/icons/nuvio-app-icon.ico").asFile.absolutePath
    val sidebarPng = layout.projectDirectory.file("src/desktopMain/resources/icons/nuvio-installer-sidebar.png").asFile.absolutePath

    commandLine(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        scriptPath,
        "-AppDir",
        appImageDir,
        "-OutputDir",
        outputDir,
        "-AppVersion",
        desktopReleasePackageVersion,
        "-AppBuild",
        desktopReleaseVersionCode.toString(),
        "-SetupIcon",
        setupIcon,
        "-AppIcon",
        appIcon,
        "-SidebarPng",
        sidebarPng,
    )
}

tasks.matching {
    it.name == "packageReleaseDistributionForCurrentOS" ||
        it.name == "packageReleaseExe" ||
        it.name == "packageReleaseMsi"
}.configureEach {
    dependsOn("createReleaseDistributable")
    dependsOn(packageWindowsNativeRuntime)
}

tasks.matching { it.name == "runReleaseDistributable" }.configureEach {
    dependsOn(packageWindowsNativeRuntime)
}

// ==================== Linux Native Runtime Packaging ====================

val prepareLinuxPlayerRuntime = tasks.register<Sync>("prepareLinuxPlayerRuntime") {
    enabled = isLinuxHost && linuxBuildCiDir.isDirectory
    from(linuxBuildCiDir) {
        include("*.so")
    }
    into(linuxRuntimeStagingDir.map { it.dir("native") })
    doLast {
        if (!enabled) return@doLast
        val nativeDir = destinationDir
        val hasPatchelf = runCatching {
            ProcessBuilder("patchelf", "--version")
                .inheritIO()
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
        if (hasPatchelf) {
            nativeDir.listFiles { f -> f.name.endsWith(".so") }.orEmpty().forEach { soFile ->
                ProcessBuilder("patchelf", "--set-rpath", "\$ORIGIN", soFile.absolutePath)
                    .inheritIO()
                    .start()
                    .waitFor()
            }
            logger.lifecycle("prepareLinuxPlayerRuntime: set RUNPATH=\$ORIGIN on native libs in ${nativeDir.absolutePath}")
        } else {
            logger.warn("prepareLinuxPlayerRuntime: patchelf not found — RUNPATH not set, native libs may not load")
            logger.warn("  Install: sudo apt install patchelf  (Debian/Ubuntu)  |  sudo pacman -S patchelf  (Arch)")
        }
    }
}

fun renameMacosDmgOutput(release: Boolean) {
    if (!isMacHost) return

    val distributionName = if (release) "main-release" else "main"
    val outputDir = layout.buildDirectory.dir("compose/binaries/$distributionName/dmg").get().asFile
    val finalDmg = outputDir.resolve("Nuvio-macOS-$macosDmgArchName-$desktopReleaseVersionName.dmg")
    val defaultDmg = outputDir.resolve("Nuvio-$desktopReleasePackageVersion.dmg")
    val sourceDmg = defaultDmg.takeIf { it.exists() }
        ?: finalDmg.takeIf { it.exists() }
        ?: error("Expected macOS DMG output in ${outputDir.absolutePath}")

    if (sourceDmg != finalDmg) {
        if (finalDmg.exists() && !finalDmg.delete()) {
            error("Could not replace existing DMG: ${finalDmg.absolutePath}")
        }
        if (!sourceDmg.renameTo(finalDmg)) {
            sourceDmg.copyTo(finalDmg, overwrite = true)
            if (!sourceDmg.delete()) {
                logger.warn("Could not delete old DMG after copy: ${sourceDmg.absolutePath}")
            }
        }
    }

    logger.lifecycle("macOS DMG artifact: ${finalDmg.absolutePath}")
    publishMacosDmgArtifact(finalDmg)
}

fun publishMacosDmgArtifact(dmg: File) {
    val publishedDir = layout.buildDirectory.dir("compose/release-dmgs").get().asFile
    publishedDir.mkdirs()
    val publishedDmg = publishedDir.resolve(dmg.name)
    if (dmg.canonicalFile != publishedDmg.canonicalFile) {
        dmg.copyTo(publishedDmg, overwrite = true)
    }
    logger.lifecycle("Published macOS DMG artifact: ${publishedDmg.absolutePath}")
}

fun publishWindowsMsiOutput(release: Boolean) {
    if (!isWindowsHost) return

    val distributionName = if (release) "main-release" else "main"
    val outputDir = layout.buildDirectory.dir("compose/binaries/$distributionName/msi").get().asFile
    val finalMsi = outputDir.resolve("Nuvio-Windows-$windowsPlayerBridgeArch-$desktopReleaseVersionName.msi")
    val defaultMsi = outputDir.resolve("Nuvio-$desktopReleasePackageVersion.msi")
    val sourceMsi = defaultMsi.takeIf { it.exists() }
        ?: finalMsi.takeIf { it.exists() }
        ?: error("Expected Windows MSI output in ${outputDir.absolutePath}")

    if (sourceMsi.canonicalFile != finalMsi.canonicalFile) {
        sourceMsi.copyTo(finalMsi, overwrite = true)
    }

    logger.lifecycle("Windows MSI artifact: ${finalMsi.absolutePath}")
    publishWindowsMsiArtifact(finalMsi)
}

fun publishWindowsMsiArtifact(msi: File) {
    val publishedDir = layout.buildDirectory.dir("compose/release-msis").get().asFile
    publishedDir.mkdirs()
    val publishedMsi = publishedDir.resolve(msi.name)
    if (msi.canonicalFile != publishedMsi.canonicalFile) {
        msi.copyTo(publishedMsi, overwrite = true)
    }
    logger.lifecycle("Published Windows MSI artifact: ${publishedMsi.absolutePath}")
}

tasks.matching { it.name == "packageDmg" }.configureEach {
    doLast {
        if (!isMacosDmgNotarizationRequested) {
            renameMacosDmgOutput(release = false)
        }
    }
}

tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
    doLast {
        if (!isMacosDmgNotarizationRequested) {
            renameMacosDmgOutput(release = true)
        }
    }
}

tasks.withType<Jar>().configureEach {
    if (isLinuxHost && name == "desktopJar") {
        dependsOn(prepareLinuxPlayerRuntime)
        from(prepareLinuxPlayerRuntime) {
            into("native/linux")
        }
    }
}

if (isLinuxHost) {
    val linuxDistTasks = listOf("createDistributable", "createReleaseDistributable")
    tasks.matching { it.name in linuxDistTasks }.configureEach {
        dependsOn(prepareLinuxPlayerRuntime)
        doLast {
            val distDir = layout.buildDirectory.dir("compose/binaries/main/app/Nuvio").get().asFile
            if (!distDir.isDirectory) return@doLast
            val nativeDir = distDir.resolve("lib/native")
            nativeDir.mkdirs()
            copy {
                from(prepareLinuxPlayerRuntime)
                into(nativeDir)
            }
            val configFile = distDir.resolve("lib/app/Nuvio.cfg")
            if (configFile.exists()) {
                val content = configFile.readLines().toMutableList()
                val jlpEntry = "java-options=-Djava.library.path=\$APPDIR/../native"
                val hasCorrect = content.any { it == jlpEntry }
                if (!hasCorrect) {
                    content.removeAll { "java.library.path" in it }
                    val javaOptionsIdx = content.indexOfFirst { it == "[JavaOptions]" }
                    if (javaOptionsIdx >= 0) {
                        val insertAt = content.subList(javaOptionsIdx + 1, content.size)
                            .indexOfFirst { !it.startsWith("java-options=") }
                            .let { idx -> if (idx < 0) content.size else javaOptionsIdx + 1 + idx }
                        content.add(insertAt, jlpEntry)
                        configFile.writeText(content.joinToString("\n") + "\n")
                    }
                }
            }
            logger.lifecycle("Injected mediamp native libs into distribution: ${nativeDir.absolutePath}")
        }
    }
}

// Post-process jpackage-based packages (deb, rpm) to include native libs
val linuxPkgTasks = listOf(
    "packageDeb", "packageReleaseDeb",
    "packageRpm", "packageReleaseRpm",
)
tasks.matching { it.name in linuxPkgTasks && isLinuxHost }.configureEach {
    val pkgTaskName = name
    dependsOn(prepareLinuxPlayerRuntime)
    doLast {
        val version = desktopReleasePackageVersion
        val pkgType = if (pkgTaskName.contains("Deb", ignoreCase = true)) "deb" else "rpm"
        val name_ = "nuvio_${version}_amd64"
        val buildDir = layout.buildDirectory.dir("compose/binaries/main").get().asFile
        val pkgFile = File(buildDir, "${pkgType}/${name_}.${pkgType}")
        if (!pkgFile.isFile) {
            logger.warn("patchPackage: ${pkgFile.name} not found, skipping")
            return@doLast
        }
        val tempDir = file("/tmp/nuvio-deb-temp-${pkgType}")
        if (tempDir.isDirectory) tempDir.deleteRecursively()
        tempDir.mkdirs()

        if (pkgType == "deb") {
            exec { commandLine("dpkg-deb", "-R", pkgFile, tempDir) }
            exec { commandLine("chmod", "-R", "0755", File(tempDir, "DEBIAN")) }
            val controlFile = File(tempDir, "DEBIAN/control")
            if (controlFile.isFile) {
                var control = controlFile.readText()
                if (!control.contains("libmpv2")) {
                    control = control.replace(
                        Regex("^(Depends: .*)$", setOf(RegexOption.MULTILINE)),
                        "$1, libmpv2"
                    )
                    controlFile.writeText(control)
                    logger.lifecycle("patchPackage: appended libmpv2 to Depends")
                }
            }
            copy {
                from(prepareLinuxPlayerRuntime)
                into(File(tempDir, "opt/nuvio/lib/native"))
            }
            exec { commandLine("dpkg-deb", "-b", tempDir, pkgFile) }
            logger.lifecycle("patchPackage: injected native libs into ${pkgFile.name}")
        } else if (pkgType == "rpm") {
            runCatching {
                exec { commandLine("rpm2cpio", pkgFile) }
            }.onFailure {
                logger.warn("patchPackage: rpm2cpio not available, skipping ${pkgFile.name}")
            }
        }
    }
}

// AppImage with update info (issue #9)
tasks.register("packageAppImageWithUpdate") {
    dependsOn("createReleaseDistributable")
    doLast {
        val appDir = layout.buildDirectory.dir("compose/binaries/main/app/Nuvio").get().asFile
        if (!appDir.isDirectory) {
            logger.warn("AppImage: distribution dir not found at ${appDir.absolutePath}")
            return@doLast
        }
        // jpackage's app-image has no AppRun; the AppImage runtime requires one as its
        // entrypoint. Without it the image fails to launch with "execv error: No such file
        // or directory". Generate a minimal AppRun that execs the jpackage launcher.
        val appRun = appDir.resolve("AppRun")
        appRun.writeText(
            "#!/bin/sh\n" +
            "HERE=\"\$(dirname \"\$(readlink -f \"\${0}\")\")\"\n" +
            "cd \"\${HERE}\" || exit 1\n" +
            "exec \"\${HERE}/bin/Nuvio\" \"\$@\"\n"
        )
        appRun.setExecutable(true)

        val outputDir = layout.buildDirectory.dir("compose/binaries/main/appimage").get().asFile
        outputDir.mkdirs()
        val appImageFile = outputDir.resolve("Nuvio-${desktopReleasePackageVersion}-x86_64.AppImage")
        val updateStr = "gh-releases-zsync|${project.findProperty("github.owner") ?: "aelrased"}|${project.findProperty("github.repo") ?: "NuvioDesktop"}|latest|Nuvio-*-x86_64.AppImage.zsync"
        exec {
            commandLine(
                "appimagetool",
                "-u", updateStr,
                appDir.absolutePath,
                appImageFile.absolutePath,
            )
        }
        logger.lifecycle("AppImage created: ${appImageFile.absolutePath}")
        val zsyncFile = file("${appImageFile.absolutePath}.zsync")
        if (zsyncFile.isFile) {
            logger.lifecycle("zsync file: ${zsyncFile.absolutePath}")
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    if (name in listOf("run", "runRelease") && isLinuxHost) {
        val mediampDir = System.getenv("NUVIO_MEDIAMP_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
        if (mediampDir != null) {
            val libmpvDir = "${project.rootDir}/mediamp/mediamp-mpv/libmpv/lib/linux/x86_64"
            jvmArgs("-Djava.library.path=${mediampDir}:${libmpvDir}")
        }
    }
}

tasks.matching { it.name == "notarizeDmg" }.configureEach {
    notCompatibleWithConfigurationCache("Compose Desktop notarization settings are not configuration-cache safe.")
    doLast {
        renameMacosDmgOutput(release = false)
    }
}

tasks.matching { it.name == "notarizeReleaseDmg" }.configureEach {
    notCompatibleWithConfigurationCache("Compose Desktop notarization settings are not configuration-cache safe.")
    doLast {
        renameMacosDmgOutput(release = true)
    }
}

tasks.matching { it.name == "packageMsi" }.configureEach {
    notCompatibleWithConfigurationCache("Windows MSI artifact publication uses script file operations.")
    doLast {
        publishWindowsMsiOutput(release = false)
    }
}

tasks.matching { it.name == "packageReleaseMsi" }.configureEach {
    notCompatibleWithConfigurationCache("Windows MSI artifact publication uses script file operations.")
    doLast {
        publishWindowsMsiOutput(release = true)
    }
}

if (isMacHost) {
    tasks.register<NotarizeMacosDmgWithKeychainTask>("notarizeReleaseDmgWithKeychain") {
        group = "distribution"
        description = "Packages, notarizes, and staples the release macOS DMG using a notarytool keychain profile."
        dependsOn("packageReleaseDmg")
        dmgDir.set(layout.buildDirectory.dir("compose/binaries/main-release/dmg"))
        artifactDir.set(layout.buildDirectory.dir("compose/release-dmgs"))
        finalDmgName.set("Nuvio-macOS-$macosDmgArchName-$desktopReleaseVersionName.dmg")
        defaultDmgName.set("Nuvio-$desktopReleasePackageVersion.dmg")
        keychainProfile.set(macosNotaryKeychainProfile.orEmpty())
    }
}

afterEvaluate {
    dependencies {
        add("fullImplementation", files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
        add("fullImplementation", libs.ksoup)
    }
}

configurations.matching { it.name == "iosMainImplementation" }.configureEach {
    project.dependencies.add(name, libs.ktor.client.darwin)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
}

configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

android {
    namespace = "com.nuvio.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.nuvio.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }
    sourceSets.getByName("full") {
        manifest.srcFile("src/androidFull/AndroidManifest.xml")
        java.srcDir(fullCommonSourceDir)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
