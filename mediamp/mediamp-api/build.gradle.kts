plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

description = "Core API for MediaMP"

kotlin {
    jvm("desktop")

    sourceSets {
        val jvmMain by creating {
            dependsOn(commonMain.get())
        }
        val skikoMain by creating {
            dependsOn(commonMain.get())
        }
        val desktopMain by getting {
            dependsOn(jvmMain)
            dependsOn(skikoMain)
        }
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            api(kotlin("test"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        jvmMain.dependencies {
            compileOnly("androidx.annotation:annotation:1.9.1")
        }
        desktopMain.dependencies {
        }
    }
}
