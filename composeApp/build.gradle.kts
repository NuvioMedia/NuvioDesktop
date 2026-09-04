import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.ByteArrayOutputStream
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

    @get:Input
    abstract val supabaseUrl: Property<String>

    @get:Input
    abstract val supabaseAnonKey: Property<String>

    @get:Input
    abstract val supabaseFallbackUrl: Property<String>

    @get:Input
    abstract val sentryDsn: Property<String>

    @get:Input
    abstract val sentryDesktopDsn: Property<String>

    @get:Input
    abstract val sentryEnvironment: Property<String>

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
                |    const val URL = "${supabaseUrl.get()}"
                |    const val ANON_KEY = "${supabaseAnonKey.get()}"
                |    const val FALLBACK_URL = "${supabaseFallbackUrl.get()}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/diagnostics").apply {
            mkdirs()
            resolve("SentryConfig.kt").writeText(
                """
                |package com.nuvio.app.core.diagnostics
                |
                |object SentryConfig {
                |    const val DSN = "${sentryDsn.get()}"
                |    const val DESKTOP_DSN = "${sentryDesktopDsn.get()}"
                |    const val ENVIRONMENT = "${sentryEnvironment.get()}"
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

        outDir.resolve("com/nuvio/app/features/simkl").apply {
            mkdirs()
            resolve("SimklConfig.kt").writeText(
                """
                |package com.nuvio.app.features.simkl
                |
                |object SimklConfig {
                |    const val CLIENT_ID = "${props.getProperty("SIMKL_CLIENT_ID", "")}"
                |    const val REDIRECT_URI = "${props.getProperty("SIMKL_REDIRECT_URI", "nuvio://auth/simkl")}"
                |    const val APP_NAME = "${props.getProperty("SIMKL_APP_NAME", "nuvio")}"
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/discordrpc").apply {
            mkdirs()
            resolve("DiscordConfig.kt").writeText(
                """
                |package com.nuvio.app.features.discordrpc
                |
                |object DiscordConfig {
                |    const val CLIENT_ID = "${props.getProperty("NUVIO_DISCORD_CLIENT_ID", "1538974392376369212")}"
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
                |    const val SUPPORTERS_WALL_URL = "${props.getProperty("SUPPORTERS_WALL_URL", "https://nuvio.tv/api/supporters/wall")}"
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

    @get:Optional
    @get:Input
    abstract val keychainPath: Property<String>

    @get:Input
    abstract val signingIdentity: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun notarize() {
        val profile = keychainProfile.get().trim()
        require(profile.isNotEmpty()) {
            "Set NUVIO_MACOS_NOTARY_PASSWORD=@keychain:<profile> or NUVIO_MACOS_NOTARY_KEYCHAIN_PROFILE=<profile>."
        }
        val identity = signingIdentity.get().trim()
        require(identity.isNotEmpty()) {
            "Set NUVIO_MACOS_SIGNING_IDENTITY to a Developer ID Application identity."
        }

        val dmg = ensureFinalDmg()
        signDmg(dmg, identity)
        val notaryCommand = mutableListOf(
            "xcrun",
            "notarytool",
            "submit",
            dmg.absolutePath,
            "--wait",
            "--keychain-profile",
            profile,
        )
        keychainPath.orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { notaryCommand += listOf("--keychain", it) }
        execOperations.exec {
            commandLine(notaryCommand)
        }
        execOperations.exec {
            commandLine("xcrun", "stapler", "staple", dmg.absolutePath)
        }
        publishDmg(dmg)
        logger.lifecycle("Notarized and stapled macOS DMG: ${dmg.absolutePath}")
    }

    private fun signDmg(dmg: File, identity: String) {
        execOperations.exec {
            commandLine(
                "codesign",
                "--force",
                "--sign",
                identity,
                "--timestamp",
                dmg.absolutePath,
            )
        }
        logger.lifecycle("Signed macOS DMG: ${dmg.absolutePath}")
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

abstract class PrepareMacosTorrServerResourcesTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val signingIdentity: Property<String>

    @TaskAction
    fun prepare() {
        val sourceRoot = sourceDir.get().asFile
        val outputRoot = outputDir.get().asFile
        val resourceRoot = outputRoot.resolve("torrserver")

        outputRoot.deleteRecursively()
        resourceRoot.mkdirs()

        sourceRoot.walkTopDown()
            .filter(File::isFile)
            .forEach { sourceFile ->
                val relativePath = sourceFile.relativeTo(sourceRoot)
                val outputFile = resourceRoot.resolve(relativePath.path)
                outputFile.parentFile.mkdirs()
                sourceFile.copyTo(outputFile, overwrite = true)
                outputFile.setExecutable(sourceFile.canExecute())
            }

        val identity = signingIdentity.get().trim()
        if (identity.isNotEmpty()) {
            resourceRoot.walkTopDown()
                .filter(File::isFile)
                .forEach { binary ->
                    execOperations.exec {
                        commandLine(
                            "codesign",
                            "--force",
                            "--options",
                            "runtime",
                            "--timestamp",
                            "--sign",
                            identity,
                            binary.absolutePath,
                        )
                    }
                }
        }
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
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

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
val macosNotaryKeychainPath = localOrEnvProperty("NUVIO_MACOS_NOTARY_KEYCHAIN_PATH")
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
val windowsMsiUpgradeUuid = "395990ee-9b8a-3548-922c-e7a23a495b8d"
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
val nuvioEngineAppleFramework = rootProject.file("../nuvio-engine/platform/apple/NuvioEngine.xcframework")
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val fullPluginSourceDir = fullCommonSourceDir.resolve("com/nuvio/app/features/plugins")
val fullTrailerSourceDir = fullCommonSourceDir.resolve("com/nuvio/app/features/trailer")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val desktopSentryResourceDir = rootProject.layout.projectDirectory.dir("desktopSentry/build/generated/sentry")
val requestedGradleTasks = gradle.startParameter.taskNames.map { taskName ->
    taskName.substringAfterLast(':').lowercase()
}
val requestedAndroidDistributions = requestedGradleTasks.mapNotNull { taskName ->
    when {
        "playstore" in taskName -> "playstore"
        "full" in taskName -> "full"
        else -> null
    }
}.toSet()
require(requestedAndroidDistributions.size <= 1) {
    "Build Android full and playstore distributions separately, or set -Pnuvio.android.distribution=full|playstore."
}
val configuredAndroidDistribution = providers.gradleProperty("nuvio.android.distribution").orNull
    ?: supabaseProps.getProperty("NUVIO_ANDROID_DISTRIBUTION")
val isAmbiguousAndroidPackageTask = requestedGradleTasks.any { taskName ->
    taskName == "build" ||
        taskName.startsWith("assemble") ||
        taskName.startsWith("bundle")
} && requestedAndroidDistributions.isEmpty()
require(configuredAndroidDistribution != null || !isAmbiguousAndroidPackageTask) {
    "Set -Pnuvio.android.distribution=full|playstore for aggregate Android assemble/bundle tasks."
}
val androidDistribution = (
    configuredAndroidDistribution
        ?: requestedAndroidDistributions.singleOrNull()
        ?: "playstore"
    ).trim().lowercase()
require(androidDistribution == "playstore" || androidDistribution == "full") {
    "nuvio.android.distribution must be 'playstore' or 'full'."
}
val androidDistributionSourceDir = if (androidDistribution == "full") {
    "src/androidFull/kotlin"
} else {
    "src/androidPlaystore/kotlin"
}
val runtimeLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun runtimeConfigValue(key: String, fallback: String = ""): String =
    runtimeLocalProperties.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(key).orNull?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback

fun runtimeConfigBoolean(key: String, default: Boolean): Boolean =
    when (runtimeConfigValue(key).lowercase()) {
        "1", "true", "yes", "y", "on" -> true
        "0", "false", "no", "n", "off" -> false
        else -> default
    }

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
    desktopAppVersionName.set(desktopReleaseVersionName)
    desktopAppVersionCode.set(desktopReleaseVersionCode)
    supabaseUrl.set(runtimeConfigValue("NUVIO_SUPABASE_URL"))
    supabaseAnonKey.set(runtimeConfigValue("NUVIO_SUPABASE_ANON_KEY"))
    supabaseFallbackUrl.set(runtimeConfigValue("NUVIO_SUPABASE_FALLBACK_URL"))
    sentryDsn.set(runtimeConfigValue("SENTRY_DSN"))
    sentryDesktopDsn.set(runtimeConfigValue("SENTRY_DESKTOP_DSN"))
    sentryEnvironment.set(
        when {
            requestedGradleTasks.any { "benchmark" in it } -> "benchmark"
            requestedGradleTasks.any { "debug" in it } -> "debug"
            else -> "production"
        }
    )
}

val isMacHost = System.getProperty("os.name").contains("mac", ignoreCase = true)
val isWindowsHost = System.getProperty("os.name").contains("win", ignoreCase = true)
val prepareMacosTorrServerResources = tasks.register<PrepareMacosTorrServerResourcesTask>("prepareMacosTorrServerResources") {
    enabled = isMacHost
    sourceDir.set(layout.projectDirectory.dir("src/desktopMain/torrserver"))
    outputDir.set(layout.buildDirectory.dir("generated/signed-macos-torrserver-resources"))
    signingIdentity.set(macosSigningIdentity.orEmpty())
}
val macosPlayerBridgeSource = layout.projectDirectory.file("src/desktopMain/native/macos/player_bridge.mm")
val macosLibmpvHeaders = layout.projectDirectory.dir("src/desktopMain/native/macos/include")
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
val macosPlayerRuntimeOutput = layout.buildDirectory.dir("native/macos-runtime/$macosPlayerBridgeArch")
val macosPlayerAppResourcesRoot = layout.buildDirectory.dir("generated/macos-player-app-resources")
val macosDmgArchName = macosPlayerBridgeArch
val isMacosDmgNotarizationRequested = requestedGradleTasks.any { taskName ->
    taskName == "notarizedmg" || taskName == "notarizereleasedmg"
}
val bundledMacosLibmpvRuntimeRoot = layout.projectDirectory.dir("src/desktopMain/native/macos/runtime").asFile
val bundledMacosLibmpvRuntimeDir = File(bundledMacosLibmpvRuntimeRoot, macosPlayerBridgeArch)
val bundledMacosLibmpvDylib = File(bundledMacosLibmpvRuntimeDir, "libmpv.2.dylib")
val missingMacosPlayerBridgeInputs = listOfNotNull(
    "libmpv headers".takeUnless { macosLibmpvHeaders.file("mpv/client.h").asFile.exists() },
    "bundled libmpv.2.dylib".takeUnless { bundledMacosLibmpvDylib.exists() },
)
val missingMacosPlayerBridgeMessage = """
    macOS libmpv inputs are missing for $macosPlayerBridgeArch: ${missingMacosPlayerBridgeInputs.joinToString()}.
    The libmpv headers must be present under ${macosLibmpvHeaders.asFile.absolutePath}.
    The dynamic libmpv runtime must be present under ${bundledMacosLibmpvRuntimeDir.absolutePath}.
""".trimIndent()
val missingMacosPlayerBridgeShellMessage = missingMacosPlayerBridgeMessage.replace("'", "'\"'\"'")
val macosPlayerBridgeSourceFile = macosPlayerBridgeSource.asFile
val macosPlayerBridgeOutputFile = macosPlayerBridgeOutput.get().asFile
val macosPlayerBridgeJavaHome = providers.systemProperty("java.home").get()
if (isMacHost) {
    macosPlayerBridgeOutputFile.parentFile.mkdirs()
}
val macosPlayerBridgeCommand = if (missingMacosPlayerBridgeInputs.isNotEmpty()) {
    listOf(
        "/bin/sh",
        "-c",
        "printf '%s\\n' '$missingMacosPlayerBridgeShellMessage' >&2; exit 1",
    )
} else {
    mutableListOf(
        "/bin/sh",
        "-c",
        """
        set -eu
        SDKROOT="${'$'}(xcrun --sdk macosx --show-sdk-path)"
        SWIFTC="${'$'}(xcrun --toolchain XcodeDefault --find swiftc)"
        SWIFT_TOOLCHAIN="${'$'}{SWIFTC%/usr/bin/swiftc}"
        SWIFT_LIB="${'$'}{SWIFT_TOOLCHAIN}/usr/lib/swift/macosx"
        exec xcrun clang++ \
          -std=c++17 \
          -dynamiclib \
          -fobjc-arc \
          -ObjC++ \
          -arch ${shellQuote(macosPlayerBridgeArch)} \
          -isysroot "${'$'}{SDKROOT}" \
          -mmacosx-version-min=12.0 \
          ${shellQuote(macosPlayerBridgeSourceFile.absolutePath)} \
          -o ${shellQuote(macosPlayerBridgeOutputFile.absolutePath)} \
          -I${shellQuote("$macosPlayerBridgeJavaHome/include")} \
          -I${shellQuote("$macosPlayerBridgeJavaHome/include/darwin")} \
          -I${shellQuote(macosLibmpvHeaders.asFile.absolutePath)} \
          -L"${'$'}{SWIFT_LIB}" \
          -L/usr/lib/swift \
          -framework AppKit \
          -framework IOKit \
          -framework OpenGL \
          -framework QuartzCore \
          -framework WebKit \
          -framework Metal \
          -framework Security \
          -lswiftCompatibility56 \
          -lswiftCompatibilityConcurrency \
          -lswiftCompatibilityPacks \
          -lc++ \
          -Wl,-rpath,@loader_path \
          ${shellQuote(bundledMacosLibmpvDylib.absolutePath)}
        """.trimIndent(),
    )
}
val buildMacosPlayerBridge = tasks.register<Exec>("buildMacosPlayerBridge") {
    notCompatibleWithConfigurationCache("Builds a host-local player bridge against the bundled macOS libmpv runtime.")
    enabled = isMacHost
    inputs.file(macosPlayerBridgeSource)
    inputs.file(bundledMacosLibmpvDylib)
    inputs.dir(macosLibmpvHeaders)
    outputs.file(macosPlayerBridgeOutput)
    commandLine(macosPlayerBridgeCommand)
}

// ---- Linux player bridge (system libmpv, X11 "wid" embedding) ----
// Compiles a single JNI .so against the system libmpv + JDK JNI headers.
// Requires a C++ toolchain, pkg-config, and libmpv development files on the
// build host (all provided by the Nix dev shell).
val isLinuxHost = System.getProperty("os.name").contains("linux", ignoreCase = true)
val linuxPlayerBridgeSource = layout.projectDirectory.file("src/desktopMain/native/linux/player_bridge.cpp")
val linuxPlayerBridgeOutput = layout.buildDirectory.file("native/linux/libplayer_bridge.so")
val linuxPlayerBridgeJavaHome = providers.systemProperty("java.home").get()
val linuxPlayerBridgeSourceFile = linuxPlayerBridgeSource.asFile
val linuxPlayerBridgeOutputFile = linuxPlayerBridgeOutput.get().asFile
val buildLinuxPlayerBridge = tasks.register<Exec>("buildLinuxPlayerBridge") {
    notCompatibleWithConfigurationCache("Builds a host-local player bridge against system libmpv.")
    enabled = isLinuxHost
    inputs.file(linuxPlayerBridgeSourceFile)
    outputs.file(linuxPlayerBridgeOutputFile)
    val src = linuxPlayerBridgeSourceFile.absolutePath
    val out = linuxPlayerBridgeOutputFile.absolutePath
    val outParent = linuxPlayerBridgeOutputFile.parentFile
    val jni = "$linuxPlayerBridgeJavaHome/include"
    doFirst { outParent.mkdirs() }
    commandLine(
        "bash", "-c",
        "c++ -std=c++17 -shared -fPIC -O2 " +
            "-I'$jni' -I'$jni/linux' " +
            "$(pkg-config --cflags mpv webkit2gtk-4.1 gtk+-3.0 x11 xcomposite xext) " +
            "'$src' -o '$out' " +
            "$(pkg-config --libs mpv webkit2gtk-4.1 gtk+-3.0 x11 xcomposite xext) -lpthread",
    )
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
        "Shell32.lib",
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
    onlyIf { !windowsPlayerBridgeOutput.get().asFile.exists() }
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

val prepareMacosPlayerRuntime = tasks.register<Sync>("prepareMacosPlayerRuntime") {
    enabled = isMacHost
    from(bundledMacosLibmpvRuntimeDir) {
        include("*.dylib")
    }
    into(macosPlayerRuntimeOutput)
}

val prepareMacosPlayerAppResources = tasks.register<Sync>("prepareMacosPlayerAppResources") {
    enabled = isMacHost
    dependsOn(buildMacosPlayerBridge, prepareMacosPlayerRuntime)
    from(macosPlayerBridgeOutput)
    from(macosPlayerRuntimeOutput) {
        include("*.dylib")
    }
    into(macosPlayerAppResourcesRoot.map { it.dir("macos/native/macos") })
}

tasks.withType<Jar>().configureEach {
    if (isWindowsHost && name == "desktopJar") {
        dependsOn(buildWindowsPlayerBridge, prepareWindowsPlayerRuntime, generateWindowsPlayerRuntimeIndex)
        from(windowsPlayerBridgeOutput) {
            into("native/windows")
        }
        from(windowsPlayerRuntimeOutput) {
            into("native/windows")
        }
    }
    if (isLinuxHost && name == "desktopJar") {
        dependsOn(buildLinuxPlayerBridge)
        from(linuxPlayerBridgeOutput) {
            into("native/linux")
        }
        // TorrServer ships as a classpath resource so P2P streaming works from
        // any working directory and in packaged builds (macOS does the same via
        // prepareMacosTorrServerResources; Linux needs no signing pass).
        from(layout.projectDirectory.dir("src/desktopMain/torrserver")) {
            include("linux-amd64/**")
            into("torrserver")
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    if (isMacHost) {
        dependsOn(prepareMacosPlayerAppResources)
    }
}

tasks.withType<ProcessResources>().matching { it.name == "desktopProcessResources" }.configureEach {
    if (!isWindowsHost) {
        exclude("torrserver/windows-amd64/**")
    }
    if (isMacHost) {
        dependsOn(prepareMacosTorrServerResources)
        from(prepareMacosTorrServerResources.map { it.outputDir })
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
        dependsOn(buildWindowsPlayerBridge, prepareWindowsPlayerRuntime, generateWindowsPlayerRuntimeIndex)
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

kotlin {
    android {
        namespace = "com.nuvio.app"
        compileSdk {
            version = release(libs.versions.android.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
            }
        }
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        withHostTest {}

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
        val nuvioEngineSlice = if (iosTarget.name == "iosArm64") {
            "ios-arm64"
        } else {
            "ios-arm64_x86_64-simulator"
        }
        val nuvioEngineSliceDirectory = nuvioEngineAppleFramework.resolve(nuvioEngineSlice)
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
                create("appicon") {
                    defFile(project.file("src/nativeInterop/cinterop/appicon.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
                if (iosDistribution == "full") {
                    check(nuvioEngineSliceDirectory.resolve("libCNuvioEngine.a").isFile) {
                        "Build the local Nuvio Engine Apple XCFramework before compiling iOS Full."
                    }
                    create("nuvioengine") {
                        defFile(project.file("src/nativeInterop/cinterop/nuvioengine.def"))
                        compilerOpts("-I${nuvioEngineSliceDirectory.resolve("Headers").absolutePath}")
                        extraOpts("-libraryPath", nuvioEngineSliceDirectory.absolutePath)
                    }
                }
                configureEach {
                    extraOpts("-Xccall-mode", "direct")
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
            if (iosDistribution == "full") {
                linkerOpts(
                    "-lc++",
                    "-framework", "Security",
                    "-framework", "SystemConfiguration",
                    "-framework", "CoreFoundation",
                )
            }
        }
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        androidMain {
            kotlin.srcDir(project.file(androidDistributionSourceDir))
            if (androidDistribution == "full") {
                kotlin.srcDir(fullCommonSourceDir)
            }

            dependencies {
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
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sentry.android)
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
                implementation(libs.mpv.android.lib)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
                if (androidDistribution == "full") {
                    implementation(files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
                    implementation(libs.ksoup)
                }
            }
        }
        val desktopMain by getting {
            kotlin.srcDir(fullPluginSourceDir)
            kotlin.srcDir(fullTrailerSourceDir)
            kotlin.srcDir(
                if (isWindowsHost) {
                    "src/windowsDesktopMain/kotlin"
                } else {
                    "src/nonWindowsDesktopMain/kotlin"
                },
            )
            resources.srcDir(desktopSentryResourceDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                if (!isWindowsHost) {
                    implementation(project(":composeMediaPlayer"))
                }
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.cio)
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation(libs.quickjs.kt)
                implementation(libs.ksoup)
                implementation(libs.sentry.jvm)
            }
        }
        val androidHostTest by getting {
            if (androidDistribution == "full") {
                kotlin.srcDir(project.file("src/androidFullHostTest/kotlin"))
            }
        }
        commonMain.dependencies {
            implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-ktor3:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-network-cache-control:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
            implementation("io.coil-kt.coil3:coil-svg:${libs.versions.coil.get()}") {
                exclude(group = "org.jetbrains.skiko", module = "skiko")
            }
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
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kmpalette.core)
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.supabase.storage)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.matching {
    it.name == "desktopProcessResources" || it.name == "processDesktopMainResources"
}.configureEach {
    dependsOn(":desktopSentry:generateSentryDebugMetaPropertiesjava")
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.MainKt"
        val smokePlayerUrl = providers.gradleProperty("nuvio.desktop.smokePlayerUrl").orNull
            ?: System.getenv("NUVIO_DESKTOP_SMOKE_PLAYER_URL")
        jvmArgs += listOfNotNull(
            "-Dapple.awt.application.appearance=NSAppearanceNameDarkAqua",
            // Keep AWT from loading its own GTK (Swing L&F/file dialogs): the
            // bridge owns the process's GTK via initGtkEarly (skoruppa's fix).
            "-Djdk.gtk.version=0",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
            smokePlayerUrl?.takeIf { it.isNotBlank() }?.let { "-Dnuvio.desktop.smokePlayerUrl=$it" },
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "Nuvio"
            packageVersion = desktopReleasePackageVersion
            vendor = "Nuvio Media"
            if (isMacHost) {
                appResourcesRootDir.set(macosPlayerAppResourcesRoot)
            }
            modules(
                "java.instrument",
                "java.management",
                "java.net.http",
                "jdk.httpserver",
                "jdk.unsupported",
            )
            macOS {
                bundleID = "com.nuvio.media.desktop"
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon-transparent.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>com.nuvio.media.desktop</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>nuvio</string>
                                    <string>stremio</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
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
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon-transparent.ico"))
                upgradeUuid = windowsMsiUpgradeUuid
                shortcut = true
                menu = true
                menuGroup = "Nuvio"
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/nuvio-app-icon-transparent.png"))
                debMaintainer = "contact@nuvio.tv"
                shortcut = true
                menuGroup = "Nuvio"
                appCategory = "AudioVideo"
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
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

fun normalizedLinuxArch(value: String): String =
    when (value.lowercase()) {
        "amd64", "x64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> value.lowercase()
    }

val linuxAppImageArch = normalizedLinuxArch(System.getProperty("os.arch"))

fun findExecutableOnPath(name: String): File? =
    System.getenv("PATH")
        ?.split(File.pathSeparatorChar)
        ?.asSequence()
        ?.map(::File)
        ?.map { it.resolve(name) }
        ?.firstOrNull { it.isFile && it.canExecute() }

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

if (isLinuxHost) {
    val linuxDebPatchScript = rootProject.layout.projectDirectory.file("scripts/linux/patch-linux-deb.sh")
    val linuxDebVerifyScript = rootProject.layout.projectDirectory.file("scripts/linux/verify-linux-deb.sh")
    val linuxRpmPatchScript = rootProject.layout.projectDirectory.file("scripts/linux/patch-linux-rpm.sh")
    val linuxRpmVerifyScript = rootProject.layout.projectDirectory.file("scripts/linux/verify-linux-rpm.sh")
    val linuxAppImageBuildScript = rootProject.layout.projectDirectory.file("scripts/linux/build-appimage.sh")

    fun tailLines(text: String, maxLines: Int = 120): String {
        val lines = text.lines()
        if (lines.size <= maxLines) {
            return text.trimEnd()
        }
        return lines.takeLast(maxLines).joinToString("\n").trimEnd()
    }

    fun runLinuxPackagingScript(command: List<String>, artifactLabel: String) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (output.isNotBlank()) {
            logger.lifecycle(output.trimEnd())
        }
        check(exitCode == 0) {
            buildString {
                appendLine("Linux $artifactLabel command failed with exit code $exitCode: ${command.joinToString(" ")}")
                val trimmed = output.trim()
                if (trimmed.isNotEmpty()) {
                    appendLine("--- command output (tail) ---")
                    appendLine(tailLines(trimmed))
                } else {
                    appendLine("--- command output ---")
                    appendLine("<empty>")
                }
            }
        }
    }

    fun publishLinuxAppImageOutput(
        task: AbstractJPackageTask,
        release: Boolean,
        appImageBuildScript: File,
    ) {
        val outputDir = task.destinationDir.get().asFile
        val effectivePackageName = task.packageName.get().toString()
        val appRootCandidates = listOf(
            outputDir.resolve(effectivePackageName),
            outputDir.resolve(effectivePackageName.lowercase()),
        )
        val appRoot = appRootCandidates.firstOrNull(File::isDirectory)
            ?: error("Expected Linux app-image directory in ${outputDir.absolutePath} for ${task.name}.")

        val appImageTool = providers.gradleProperty("nuvio.appimagetool.path").orNull
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: System.getenv("APPIMAGETOOL")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
            ?: findExecutableOnPath("appimagetool")
            ?: error(
                "AppImage packaging requires appimagetool. Install it on PATH, set APPIMAGETOOL, " +
                    "or pass -Pnuvio.appimagetool.path=/path/to/appimagetool."
            )

        val updateInformation = providers.gradleProperty("nuvio.appimage.updateInformation").orNull
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("APPIMAGE_UPDATE_INFORMATION")?.takeIf { it.isNotBlank() }
            ?: System.getenv("UPDATE_INFORMATION")?.takeIf { it.isNotBlank() }
        val websiteUrl = providers.gradleProperty("nuvio.appimage.websiteUrl").orNull
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("APPIMAGE_WEBSITE_URL")?.takeIf { it.isNotBlank() }

        val distributionName = if (release) "main-release" else "main"
        val appImageName = "Nuvio-Linux-$linuxAppImageArch-$desktopReleaseVersionName.AppImage"
        val outputAppImage = layout.buildDirectory
            .dir("compose/binaries/$distributionName/app")
            .get()
            .asFile
            .resolve(appImageName)

        runLinuxPackagingScript(
            buildList {
                add("bash")
                add(appImageBuildScript.absolutePath)
                add(appRoot.absolutePath)
                add(outputAppImage.absolutePath)
                add(appImageTool.absolutePath)
                if (updateInformation != null) {
                    add("--update-information")
                    add(updateInformation)
                }
                if (websiteUrl != null) {
                    add("--website-url")
                    add(websiteUrl)
                }
            },
            "AppImage",
        )

        val publishedDir = layout.buildDirectory.dir("compose/release-appimages").get().asFile
        publishedDir.mkdirs()
        val publishedAppImage = publishedDir.resolve(appImageName)
        if (outputAppImage.canonicalFile != publishedAppImage.canonicalFile) {
            outputAppImage.copyTo(publishedAppImage, overwrite = true)
        }
        logger.lifecycle("Published Linux AppImage artifact: ${publishedAppImage.absolutePath}")
    }

    tasks.withType<AbstractJPackageTask>()
        .matching { it.name == "packageDeb" || it.name == "packageReleaseDeb" }
        .configureEach {
            doLast {
                val effectiveLinuxPackageName = linuxPackageName.orNull ?: packageName.get().lowercase()
                val effectiveLinuxAppRelease = linuxAppRelease.orNull ?: "1"
                val artifactPrefix =
                    "${effectiveLinuxPackageName}_${packageVersion.get()}-${effectiveLinuxAppRelease}_"
                val debs = destinationDir.get().asFile
                    .listFiles { file ->
                        file.isFile && file.name.startsWith(artifactPrefix) && file.extension == "deb"
                    }
                    ?.sortedBy { it.name }
                    .orEmpty()
                require(debs.size == 1) {
                    "Expected exactly one current Linux DEB matching $artifactPrefix in " +
                        "${destinationDir.get().asFile.absolutePath}, found ${debs.size}."
                }
                val deb = debs.single()
                listOf(linuxDebPatchScript, linuxDebVerifyScript).forEach { script ->
                    val command = listOf("bash", script.asFile.absolutePath, deb.absolutePath)
                    runLinuxPackagingScript(command, "DEB")
                }
            }
        }

    tasks.withType<AbstractJPackageTask>()
        .matching { it.name == "packageRpm" || it.name == "packageReleaseRpm" }
        .configureEach {
            doLast {
                val effectiveLinuxPackageName = linuxPackageName.orNull ?: packageName.get().lowercase()
                val effectiveLinuxAppRelease = linuxAppRelease.orNull ?: "1"
                val artifactPrefix =
                    "${effectiveLinuxPackageName}-${packageVersion.get()}-${effectiveLinuxAppRelease}."
                val rpms = destinationDir.get().asFile
                    .listFiles { file ->
                        file.isFile && file.name.startsWith(artifactPrefix) && file.extension == "rpm"
                    }
                    ?.sortedBy { it.name }
                    .orEmpty()
                require(rpms.size == 1) {
                    "Expected exactly one current Linux RPM matching $artifactPrefix in " +
                        "${destinationDir.get().asFile.absolutePath}, found ${rpms.size}."
                }
                val rpm = rpms.single()
                listOf(linuxRpmPatchScript, linuxRpmVerifyScript).forEach { script ->
                    val command = listOf("bash", script.asFile.absolutePath, rpm.absolutePath)
                    runLinuxPackagingScript(command, "RPM")
                }
            }
        }

    tasks.withType<AbstractJPackageTask>()
        .matching { it.name == "packageAppImage" || it.name == "packageReleaseAppImage" }
        .configureEach {
            notCompatibleWithConfigurationCache("Linux AppImage artifact publication uses script file operations.")
            doLast {
                val packageTask = this as AbstractJPackageTask
                publishLinuxAppImageOutput(
                    task = packageTask,
                    release = packageTask.name == "packageReleaseAppImage",
                    appImageBuildScript = linuxAppImageBuildScript.asFile,
                )
            }
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
        keychainPath.set(macosNotaryKeychainPath.orEmpty())
        signingIdentity.set(macosSigningIdentity.orEmpty())
    }
}
configurations.matching { it.name == "iosMainImplementation" }.configureEach {
    project.dependencies.add(name, libs.ktor.client.darwin)
}

configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}
