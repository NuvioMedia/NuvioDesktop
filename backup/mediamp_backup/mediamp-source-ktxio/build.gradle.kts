/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP data source implementation for Kotlinx IO"

android {
    namespace = "org.openani.mediamp.source.ktxio"
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(projects.mediampApi)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            compileOnly(libs.androidx.annotation)
        }
        commonTest.dependencies {
            api(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
        }
        desktopMain.dependencies {
        }
        iosMain.dependencies {
            implementation(libs.androidx.annotation)
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true, listOf("debug", "release")))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}