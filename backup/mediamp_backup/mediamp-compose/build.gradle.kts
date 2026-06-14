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

/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
//    id("org.jetbrains.kotlinx.atomicfu")
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Compose integration for MediaMP"

android {
    namespace = "org.openani.mediamp.compose"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.mediampApi)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.annotation)
        }
        commonTest.dependencies {
            api(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.compose.ui.tooling)
            implementation(libs.androidx.media3.ui)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.hls)
        }
        desktopMain.dependencies {
            api(compose.desktop.currentOs) {
                exclude(compose.material) // We use material3
            }

            api(libs.kotlinx.coroutines.swing)
            implementation(libs.vlcj)
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
        iosMain.dependencies {
            implementation(projects.mediampInternalUtils)
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