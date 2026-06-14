/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
    idea
}

val hasAndroidSdk = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
if (hasAndroidSdk) {
    apply(plugin = "com.android.library")
}

description = "MediaMP backend using MPV"

val archs = buildList {
    val abis = getPropertyOrNull("ani.android.abis")?.trim()
    if (!abis.isNullOrEmpty()) {
        addAll(abis.split(",").map { it.trim() })
    } else {
        add("arm64-v8a")
        add("armeabi-v7a")
        add("x86_64")
    }
}

kotlin {
    sourceSets {
//        androidMain {
//            kotlin.srcDirs(listOf("gen/java"))
//        }
        commonMain {
            dependencies {
                api(projects.mediampApi)
                implementation(projects.mediampInternalUtils)
            }
        }
        desktopMain.dependencies {
            api(libs.jna.platform)
        }
    }
}

//kotlin.sourceSets.getByName("jvmMain") {
//    java.setSrcDirs(listOf("gen/java"))
//}

if (hasAndroidSdk) {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        namespace = "org.openani.mediamp.mpv"
        defaultConfig {
            ndk {
                abiFilters.clear()
                abiFilters += archs
            }
        }
        splits {
            abi {
                isEnable = true
                reset()
                include(*archs.toTypedArray())
                isUniversalApk = true
            }
        }
        externalNativeBuild {
            cmake {
                path = projectDir.resolve("CMakeLists.txt")
            }
        }
    }
}

val nativeBuildDir = projectDir.resolve("build-ci")
val cmakeDesktopBuildDir = projectDir.resolve("build/cmake-desktop")

val buildDesktopNative = tasks.register("buildDesktopNative", Exec::class.java) {
    notCompatibleWithConfigurationCache("uses external project files")
    group = "mediamp"
    description = "Build mediampv native library via CMake for desktop"

    val buildType = if (getPropertyOrNull("mediamp.buildType") == "debug") "Debug" else "Release"

    val libName = when (getOs()) {
        Os.Windows -> "mediampv.dll"
        Os.MacOS -> "libmediampv.dylib"
        Os.Linux -> "libmediampv.so"
        else -> throw GradleException("Unsupported OS: ${getOs()}")
    }

    val generator = when (getOs()) {
        Os.Windows -> "-G", "Ninja"
        else -> emptyList()
    }

    inputs.dir(projectDir.resolve("src/cpp"))
    inputs.file(projectDir.resolve("CMakeLists.txt"))
    outputs.file(cmakeDesktopBuildDir.resolve(libName))

    commandLine = buildList {
        add("cmake")
        addAll(listOf("-B", cmakeDesktopBuildDir.absolutePath))
        addAll(listOf("-S", projectDir.absolutePath))
        addAll(listOf("-DCMAKE_BUILD_TYPE=$buildType"))
        addAll(generator)
    }

    doLast {
        exec {
            workingDir = cmakeDesktopBuildDir
            commandLine = buildList {
                add("cmake")
                addAll(listOf("--build", "."))
                addAll(listOf("--config", buildType))
            }
        }

        // Copy output to build-ci/
        val srcFile = when (getOs()) {
            Os.Windows -> cmakeDesktopBuildDir.resolve("Release").resolve(libName)
            else -> cmakeDesktopBuildDir.resolve(libName)
        }
        if (!srcFile.exists()) {
            throw GradleException("CMake build did not produce $libName at $srcFile")
        }
        nativeBuildDir.mkdirs()
        srcFile.copyTo(nativeBuildDir.resolve(libName), overwrite = true)
        logger.warn("Copied $libName to ${nativeBuildDir.resolve(libName)}")

        // Copy libmpv and ffmpeg DLLs alongside mediampv.dll
        val libmpvPrebuilt = when (getOs()) {
            Os.Windows -> projectDir.resolve("libmpv/lib/windows/x86_64")
            Os.MacOS -> projectDir.resolve("libmpv/lib/macos").let { base ->
                if (getArch() == Arch.AARCH64) base.resolve("arm64") else base.resolve("x86_64")
            }
            Os.Linux -> projectDir.resolve("libmpv/lib/linux/x86_64")
            else -> return@doLast
        }
        if (libmpvPrebuilt.isDirectory) {
            libmpvPrebuilt.listFiles { f ->
                f.extension.equals("dll", ignoreCase = true) ||
                f.extension.equals("so", ignoreCase = true) ||
                f.extension.equals("dylib", ignoreCase = true)
            }.orEmpty().forEach { dep ->
                dep.copyTo(nativeBuildDir.resolve(dep.name), overwrite = true)
            }
            logger.warn("Copied ${libmpvPrebuilt.name} DLLs to ${nativeBuildDir}")
        }
    }
}

tasks.withType(KotlinJvmCompile::class) {
    dependsOn(buildDesktopNative)
}


val supportedOsTriples = listOf("linux-x64", "macos-aarch64", "macos-x64", "windows-x64")

val nativeJarForCurrentPlatform = tasks.register("nativeJarForCurrentPlatform", Jar::class.java) {
    dependsOn(buildCargoDesktop)

    group = "mediamp"
    description = "Create a jar for the native files for current platform"

    archiveClassifier.set(getOsTriple())

    val libName = when (getOs()) {
        Os.Windows -> "mediampv.dll"
        Os.MacOS -> "libmediampv.dylib"
        Os.Linux -> "libmediampv.so"
        else -> throw GradleException("Unsupported OS: ${getOs()}")
    }

    from(nativeBuildDir.resolve(libName)) {
        rename { libName }
    }
}

val nativeJarsDir = layout.buildDirectory.dir("native-jars")
val copyNativeJarForCurrentPlatform = tasks.register("copyNativeJarForCurrentPlatform", Copy::class.java) {
    dependsOn(nativeJarForCurrentPlatform)
    description = "Copy native jar for current platform"
    group = "mediamp"
    from(nativeJarForCurrentPlatform.flatMap { it.archiveFile })
    into(nativeJarsDir)
}

tasks.named("assemble") {
    dependsOn(copyNativeJarForCurrentPlatform)
}

if (hasAndroidSdk) {
mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true, androidVariantsToPublish = listOf("release", "debug")))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}
} else {
mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}
}

tasks
    .matching { it.name.startsWith("publishDesktopPublicationTo") }
    .all { dependsOn(copyNativeJarForCurrentPlatform) }

if (getPropertyOrNull("mediamp.sign.publications.disabled")?.toBoolean() != true) {
    tasks.getByName("signDesktopPublication") {
        dependsOn(copyNativeJarForCurrentPlatform)
    }
}

afterEvaluate {
    publishing {
        publications {
            getByName("desktop", MavenPublication::class) {
                val platforms = if (getLocalProperty("ani.publishing.onlyHostOS") == "true") {
                    listOf(getOsTriple())
                } else {
                    supportedOsTriples
                }
                platforms.forEach { platform ->
                    artifact(nativeJarsDir.map { it.file("${project.name}-${project.version}-$platform.jar") }) {
                        classifier = platform
                    }
                }
            }
        }
    }
}

val cleanNativeBuild = tasks.register("cleanNativeBuild", Delete::class.java) {
    group = "mediamp"
    // desktop and android build
    delete(nativeBuildDir, projectDir.resolve(".cxx"), projectDir.parentFile.resolve("mediamp-mpv-rust/target"))
}

tasks.named("clean") {
    dependsOn(cleanNativeBuild)
}



idea {
    module {
        excludeDirs.add(nativeBuildDir)
        excludeDirs.add(file("cmake-build-debug"))
        excludeDirs.add(file("cmake-build-release"))
    }
}