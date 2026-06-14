/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.AndroidMultiVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("android")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP backend using ExoPlayer"

android {
    namespace = "org.openani.mediamp.exoplayer"
}

dependencies {
    api(projects.mediampApi)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}

mavenPublishing {
    configure(
        AndroidMultiVariantLibrary(
            sourcesJar = true,
            publishJavadocJar = true,
            includedBuildTypeValues = setOf("debug", "release"),
        ),
    )
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}