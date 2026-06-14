plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
    idea
}

description = "MediaMP backend using MPV"

kotlin {
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            api(project(":mediamp:mediamp-api"))
            implementation(project(":mediamp:mediamp-internal-utils"))
        }
        val desktopMain by getting {
            dependencies {
                api("net.java.dev.jna:jna-platform:5.13.0")
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

idea {
    module {
        excludeDirs.add(file("cmake-build-debug"))
        excludeDirs.add(file("cmake-build-release"))
    }
}
