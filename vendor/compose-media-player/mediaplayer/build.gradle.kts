import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

group = "io.github.kdroidfilter.composemediaplayer"
version = "0.11.4-nuvio"

val generatedNativeResourceRoot = layout.buildDirectory.dir("generated/nativeResources")
val generatedNativeLibraryDir = generatedNativeResourceRoot.map {
    it.dir("composemediaplayer/native")
}

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            api("io.github.vinceglb:filekit-core:0.14.2")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain {
            resources.srcDir(generatedNativeResourceRoot)
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

val buildNativeMacOs by tasks.registering(Exec::class) {
    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/macos")
    enabled = Os.isFamily(Os.FAMILY_MAC)
    inputs.dir(nativeDir)
    outputs.dir(generatedNativeLibraryDir)
    workingDir(nativeDir)
    environment("NATIVE_LIBS_OUTPUT_DIR", generatedNativeLibraryDir.get().asFile.absolutePath)
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/windows")
    enabled = Os.isFamily(Os.FAMILY_WINDOWS)
    inputs.dir(nativeDir)
    outputs.dir(generatedNativeLibraryDir)
    workingDir(nativeDir)
    environment("NATIVE_LIBS_OUTPUT_DIR", generatedNativeLibraryDir.get().asFile.absolutePath)
    commandLine("cmd", "/c", nativeDir.file("build.bat").asFile.absolutePath)
}

val cleanLegacyLinuxNativeResource by tasks.registering(Delete::class) {
    delete(layout.projectDirectory.dir("src/jvmMain/resources/composemediaplayer/native/linux-x86-64"))
}

val buildNativeLinux by tasks.registering(Exec::class) {
    val nativeDir = layout.projectDirectory.dir("src/jvmMain/native/linux")
    enabled = Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC)
    dependsOn(cleanLegacyLinuxNativeResource)
    inputs.dir(nativeDir)
    outputs.dir(generatedNativeLibraryDir)
    workingDir(nativeDir)
    environment("NATIVE_LIBS_OUTPUT_DIR", generatedNativeLibraryDir.get().asFile.absolutePath)
    commandLine("bash", "build.sh")
}

val nativeBuildTasks = listOf(buildNativeMacOs, buildNativeWindows, buildNativeLinux)

tasks.named("assembleJvmMainResources") {
    dependsOn(nativeBuildTasks)
}

tasks.named("jvmProcessResources") {
    dependsOn(nativeBuildTasks)
}
