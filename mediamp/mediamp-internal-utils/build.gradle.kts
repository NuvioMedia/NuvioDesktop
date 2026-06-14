plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

description = "MediaMP Internal Utils"

kotlin {
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
        }
    }
}
