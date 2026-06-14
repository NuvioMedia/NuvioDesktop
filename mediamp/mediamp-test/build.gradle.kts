import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("multiplatform")
    id("com.android.library")

    `mpp-lib-targets`
    id(libs.plugins.vanniktech.mavenPublish.get().pluginId)
}

description = "Test backend for MediaMP"

android {
    namespace = "org.openani.mediamp.test"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    explicitApi()
    jvmToolchain(8)
    sourceSets {
        commonMain.dependencies {
            compileOnly(libs.androidx.annotation)
            api(libs.kotlinx.coroutines.core)
            implementation(projects.mediampApi)
        }
        
        commonTest.dependencies {

            api(kotlin("test-annotations-common", libs.versions.kotlin.get()))
            api(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            api(kotlin("test-junit", libs.versions.kotlin.get()))
        }
        iosTest.dependencies {
            implementation(libs.androidx.annotation)
        }
        androidUnitTest.dependencies {
            implementation(libs.androidx.annotation)
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