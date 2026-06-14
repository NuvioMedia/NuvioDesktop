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
    kotlin("plugin.serialization")
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "MediaMP Internal Utils"

android {
    namespace = "org.openani.mediamp.internal.utils"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            api(kotlin("test"))
        }
        getByName("jvmTest").dependencies {
            api(libs.junit)
        }
        desktopMain.dependencies {
        }
        iosMain.dependencies {
        }
        androidMain.dependencies {
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