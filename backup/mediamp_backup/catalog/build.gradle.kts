/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    `version-catalog`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Mediamp Version Catalogs"

catalog {
    versionCatalog {
        version("mediamp", project.version.toString())

        val group = project.group.toString()
        library("mediamp-exoplayer", group, "mediamp-exoplayer").versionRef("mediamp")
        library("mediamp-exoplayer-compose", group, "mediamp-exoplayer-compose").versionRef("mediamp")
        library("mediamp-mpv", group, "mediamp-mpv").versionRef("mediamp")
        library("mediamp-mpv-compose", group, "mediamp-mpv-compose").versionRef("mediamp")
        library("mediamp-avkit", group, "mediamp-avkit").versionRef("mediamp")
        library("mediamp-avkit-compose", group, "mediamp-avkit-compose").versionRef("mediamp")
        library("mediamp-test", group, "mediamp-test").versionRef("mediamp")
        library("mediamp-all", group, "mediamp-all").versionRef("mediamp")

        library("mediamp-api", group, "mediamp-api").versionRef("mediamp")
        library("mediamp-test", group, "mediamp-test").versionRef("mediamp")
        library("mediamp-compose", group, "mediamp-compose").versionRef("mediamp")

        library("mediamp-source-ktxio", group, "mediamp-source-ktxio").versionRef("mediamp")
    }
}

mavenPublishing {
    configure(com.vanniktech.maven.publish.VersionCatalog())
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}
