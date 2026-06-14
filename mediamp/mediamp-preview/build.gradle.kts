plugins {
    kotlin("multiplatform")
    id("com.android.library")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
//    id("org.jetbrains.kotlinx.atomicfu")
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.mediampApi)
        api(libs.kotlinx.coroutines.core)
        implementation(libs.androidx.annotation)
    }
    sourceSets.commonTest.dependencies {
        api(libs.kotlinx.coroutines.test)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.compose.ui.tooling.preview)
        implementation(libs.androidx.compose.ui.tooling)
        implementation(libs.compose.material3.adaptive.core.get().toString()) {
            exclude("androidx.window.core", "window-core")
        }
        implementation(libs.androidx.media3.ui)
        implementation(libs.androidx.media3.exoplayer)
        implementation(libs.androidx.media3.exoplayer.dash)
        implementation(libs.androidx.media3.exoplayer.hls)
    }
    sourceSets.desktopMain.dependencies {
        api(compose.desktop.currentOs) {
            exclude(compose.material) // We use material3
        }

        api(libs.kotlinx.coroutines.swing)
        implementation(libs.vlcj)
        implementation(libs.jna)
        implementation(libs.jna.platform)
    }
}

android {
    namespace = "org.openani.mediamp.core"
}
