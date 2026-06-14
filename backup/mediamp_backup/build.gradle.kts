/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtension

buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id(libs.plugins.kotlin.multiplatform.get().pluginId) apply false
    id(libs.plugins.kotlin.android.get().pluginId) apply false
    id(libs.plugins.kotlin.jvm.get().pluginId) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply false
    id(libs.plugins.kotlin.plugin.compose.get().pluginId) apply false
//    id("org.jetbrains.kotlinx.atomicfu") version libs.versions.atomicfu apply false
//    alias(libs.plugins.kotlinx.atomicfu) apply false
    id(libs.plugins.compose.get().pluginId) apply false
    id(libs.plugins.android.library.get().pluginId) apply false
    id(libs.plugins.android.application.get().pluginId) apply false
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId) apply false
    idea
}

allprojects {
    group = "org.openani.mediamp"
    version = properties["version.name"].toString()

    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://androidx.dev/storage/compose-compiler/repository/")
    }

    afterEvaluate {
        (runCatching { kotlinExtension }.getOrNull() as? KotlinMultiplatformExtension)?.apply {
            compilerOptions {
                optIn.add("kotlin.ExperimentalSubclassOptIn") // Workaround for IDE bug. This is already stable in Kotlin 2.1.0
            }
        }
    }
}

extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}

idea {
    module {
        excludeDirs.add(file(".kotlin"))
    }
}
