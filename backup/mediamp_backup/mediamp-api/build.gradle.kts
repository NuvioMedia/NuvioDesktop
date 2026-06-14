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
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Core API for MediaMP"

android {
    namespace = "org.openani.mediamp.api"
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core) // TODO: 2024/12/16 remove 
            compileOnly(libs.androidx.annotation)
            api(libs.kotlinx.coroutines.core)
            api(compose.runtime)
        }
        commonTest.dependencies {
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.test)
        }
        getByName("jvmTest").dependencies {
            api(libs.junit)
        }
        desktopMain.dependencies {
        }
        iosMain.dependencies {
            implementation(libs.androidx.annotation)
            implementation(projects.mediampInternalUtils)
        }
        androidMain.dependencies {
            api(libs.androidx.annotation)
        }
    }
    androidTarget {
        publishLibraryVariants("release")
    }
}

mavenPublishing {
    configure(KotlinMultiplatform(JavadocJar.Empty(), true, listOf("debug", "release")))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublicationsIfEnabled(project)
    configurePom(project)
}