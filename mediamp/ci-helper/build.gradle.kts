/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:Suppress("UnstableApiUsage")

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.internal.impldep.com.amazonaws.auth.AWSStaticCredentialsProvider
import org.gradle.internal.impldep.com.amazonaws.auth.BasicAWSCredentials
import org.gradle.internal.impldep.com.amazonaws.client.builder.AwsClientBuilder
import org.gradle.internal.impldep.com.amazonaws.services.s3.AmazonS3ClientBuilder

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

val hostOS: OS by lazy {
    when {
        Os.isFamily(Os.FAMILY_WINDOWS) -> OS.WINDOWS
        Os.isFamily(Os.FAMILY_MAC) -> OS.MACOS
        Os.isFamily(Os.FAMILY_UNIX) -> OS.LINUX
        else -> error("Unsupported OS: ${System.getProperty("os.name")}")
    }
}

val hostArch: String by lazy {
    when (val arch = System.getProperty("os.arch")) {
        "x86_64" -> "x86_64"
        "amd64" -> "x86_64"
        "arm64" -> "aarch64"
        "aarch64" -> "aarch64"
        else -> error("Unsupported host architecture: $arch")
    }
}


enum class OS(
    val isUnix: Boolean,
) {
    WINDOWS(false),
    MACOS(true),
    LINUX(true),
}


val namer = ArtifactNamer()

class ArtifactNamer {
    private val APP_NAME = "ani"

    fun getFullVersionFromTag(tag: String): String {
        return tag.substringAfter("v")
    }

    // fullVersion example: 2.0.0-beta03
    fun androidApp(fullVersion: String, arch: String): String {
        return "$APP_NAME-$fullVersion-$arch.apk"
    }

    fun androidAppQR(fullVersion: String, arch: String, server: String): String {
        return "${androidApp(fullVersion, arch)}.$server.qrcode.png"
    }

    // Ani-2.0.0-beta03-macos-amd64.dmg
    // Ani-2.0.0-beta03-macos-arm64.dmg
    // Ani-2.0.0-beta03-windows-amd64.msi
    // Ani-2.0.0-beta03-debian-amd64.deb
    // Ani-2.0.0-beta03-redhat-amd64.rpm
    fun desktopDistributionFile(
        fullVersion: String,
        osName: String,
        archName: String = hostArch,
        extension: String
    ): String {
        return "$APP_NAME-$fullVersion-$osName-$archName.$extension"
    }

    fun server(fullVersion: String, extension: String): String {
        return "$APP_NAME-server-$fullVersion.$extension"
    }
}

fun getProperty(name: String) =
    System.getProperty(name)
        ?: System.getenv(name)
        ?: properties[name]?.toString()
        ?: getLocalProperty(name)
        ?: ext.get(name).toString()

fun findProperty(name: String) =
    System.getProperty(name)
        ?: System.getenv(name)
        ?: properties[name]?.toString()
        ?: getLocalProperty(name)
        ?: runCatching { ext.get(name) }.getOrNull()?.toString()

// do not use `object`, compiler bug
open class ReleaseEnvironment {
    private val tag: String by lazy {
        (findProperty("CI_TAG") ?: "3.0.0-dev").also { println("tag = $it") }
    }
    private val branch by lazy {
        getProperty("GITHUB_REF").substringAfterLast("/").also { println("branch = $it") }
    }
    private val shaShort by lazy {
        getProperty("GITHUB_SHA").take(8).also { println("shaShort = $it") }
    }
    open val fullVersion by lazy {
        namer.getFullVersionFromTag(tag).also { println("fullVersion = $it") }
    }
    val releaseId by lazy {
        getProperty("CI_RELEASE_ID").also { println("releaseId = $it") }
    }
    val repository by lazy {
        getProperty("GITHUB_REPOSITORY").also { println("repository = $it") }
    }
    val token by lazy {
        getProperty("GITHUB_TOKEN").also { println("token = ${it.isNotEmpty()}") }
    }

    open fun uploadReleaseAsset(
        name: String,
        contentType: String,
        file: File,
    ) {
        check(file.exists()) { "File '${file.absolutePath}' does not exist when attempting to upload '$name'." }
        val tag = getProperty("CI_TAG")
        val fullVersion = namer.getFullVersionFromTag(tag)
        val releaseId = getProperty("CI_RELEASE_ID")
        val repository = getProperty("GITHUB_REPOSITORY")
        val token = getProperty("GITHUB_TOKEN")
        println("tag = $tag")
        return uploadReleaseAsset(repository, releaseId, token, fullVersion, name, contentType, file)
    }

    private val s3Client by lazy {
        AmazonS3ClientBuilder
            .standard()
            .withCredentials(
                AWSStaticCredentialsProvider(
                    BasicAWSCredentials(
                        getProperty("AWS_ACCESS_KEY_ID"),
                        getProperty("AWS_SECRET_ACCESS_KEY"),
                    ),
                ),
            )
            .apply {
                setEndpointConfiguration(
                    AwsClientBuilder.EndpointConfiguration(
                        getProperty("AWS_BASEURL"),
                        getProperty("AWS_REGION"),
                    ),
                )
            }
            .build()
    }

    fun uploadReleaseAsset(
        repository: String,
        releaseId: String,
        token: String,
        fullVersion: String,

        name: String,
        contentType: String,
        file: File,
    ) {
        println("fullVersion = $fullVersion")
        println("releaseId = $releaseId")
        println("token = ${token.isNotEmpty()}")
        println("repository = $repository")

    }

    fun generateDevVersionName(
        base: String,
    ): String {
        return "$base-${branch}-${shaShort}"
    }

    fun generateReleaseVersionName(): String = tag.removePrefix("v")
}

fun ReleaseEnvironment.uploadDesktopDistributions() {
    fun uploadBinary(
        kind: String,

        osName: String,
        archName: String = hostArch,
    ) {
        uploadReleaseAsset(
            name = namer.desktopDistributionFile(
                fullVersion,
                osName,
                archName,
                extension = kind,
            ),
            contentType = "application/octet-stream",
            file = project(":app:desktop").layout.buildDirectory.dir("compose/binaries/main-release/$kind").get().asFile
                .walk()
                .single { it.extension == kind },
        )
    }
    // installers
    when (hostOS) {
        OS.WINDOWS -> {
            uploadReleaseAsset(
                name = namer.desktopDistributionFile(
                    fullVersion,
                    osName = hostOS.name.lowercase(),
                    extension = "zip",
                ),
                contentType = "application/x-zip",
                file = layout.buildDirectory.dir("distributions").get().asFile.walk().single { it.extension == "zip" },
            )
        }

        OS.MACOS -> {
            uploadBinary("dmg", osName = "macos")
        }

        OS.LINUX -> {
            uploadBinary("deb", osName = "debian")
            uploadBinary("rpm", osName = "redhat")
        }
    }
}

// ./gradlew updateDevVersionNameFromGit -DGITHUB_REF=refs/heads/master -DGITHUB_SHA=123456789 --no-configuration-cache
val gradleProperties = rootProject.file("gradle.properties")
tasks.register("updateDevVersionNameFromGit") {
    doLast {
        val properties = file(gradleProperties).readText()
        val baseVersion =
            (Regex("version.name=(.+)").find(properties)
                ?: error("Failed to find base version. Check version.name in gradle.properties"))
                .groupValues[1]
                .substringBefore("-")
        val new = ReleaseEnvironment().generateDevVersionName(base = baseVersion)
        println("New version name: $new")
        file(gradleProperties).writeText(
            properties.replaceFirst(Regex("version.name=(.+)"), "version.name=$new"),
        )
    }
}

// ./gradlew updateReleaseVersionNameFromGit -DGITHUB_REF=refs/heads/master -DGITHUB_SHA=123456789 --no-configuration-cache
tasks.register("updateReleaseVersionNameFromGit") {
    doLast {
        val properties = file(gradleProperties).readText()
        val new = ReleaseEnvironment().generateReleaseVersionName()
        println("New version name: $new")
        file(gradleProperties).writeText(
            properties.replaceFirst(Regex("version.name=(.+)"), "version.name=$new"),
        )
    }
}
