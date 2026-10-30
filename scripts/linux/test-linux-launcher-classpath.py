#!/usr/bin/env python3
"""Exercise Linux launcher packaging with a small standalone Compose fixture."""

import os
import resource
import shutil
import subprocess
import tempfile
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def run(command, **kwargs):
    return subprocess.run(command, check=True, timeout=600, **kwargs)


def main():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    java_home = Path(os.environ["JAVA_HOME"])
    for tool in ("java", "javac", "jpackage", "jlink"):
        if not (java_home / "bin" / tool).is_file():
            raise RuntimeError(f"JAVA_HOME must contain {tool}")
    versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
    source = (ROOT / "composeApp/build.gradle.kts").read_text()
    marker = "if (isLinuxHost) {\n    // Workaround for JDK-8380085:"
    workaround = ""
    if marker in source:
        start = source.index(marker)
        end = source.index("\n}\n", start) + len("\n}\n")
        workaround = source[start:end]
    # Run the production Gradle block without configuring the native player.
    with tempfile.TemporaryDirectory(prefix="nuvio-launcher-test-") as directory:
        fixture = Path(directory)
        libs = fixture / "libs"
        classes = fixture / "classes"
        libs.mkdir()
        classes.mkdir()
        (fixture / "Main.java").write_text(
            'public class Main { public static void main(String[] args) { '
            'System.out.println(Helper.value()); } }'
        )
        (fixture / "Helper.java").write_text(
            'public class Helper { public static String value() { '
            'return "dependency-loaded"; } }'
        )
        run([str(java_home / "bin/javac"), "-d", str(classes),
             str(fixture / "Main.java"), str(fixture / "Helper.java")])
        for jar_name, class_name in (("main.jar", "Main.class"), ("helper.jar", "Helper.class")):
            with zipfile.ZipFile(libs / jar_name, "w") as jar:
                jar.write(classes / class_name, class_name)
        for number in range(1024):
            name = f"dependency_{number:04d}_{'x' * 60}.jar"
            with zipfile.ZipFile(libs / name, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
        run([str(java_home / "bin/jlink"), "--add-modules", "java.base", "--output",
             str(fixture / "runtime"), "--strip-debug", "--no-header-files", "--no-man-pages"])
        (fixture / "settings.gradle.kts").write_text(
            'pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }\n'
            'rootProject.name = "launcher-regression"\n'
        )
        kotlin_version = versions["kotlin"]
        compose_version = versions["composeMultiplatform"]
        script = f'''import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
plugins {{
    kotlin("jvm") version "{kotlin_version}"
    id("org.jetbrains.kotlin.plugin.compose") version "{kotlin_version}"
    id("org.jetbrains.compose") version "{compose_version}"
}}
repositories {{ mavenCentral() }}
compose.desktop {{ application {{
    disableDefaultConfiguration()
    fromFiles(fileTree("libs") {{ include("*.jar") }})
    mainJar.set(file("libs/main.jar"))
    mainClass = "Main"
    nativeDistributions {{
        targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
        packageName = "LongCp"
        packageVersion = "1.0.0"
        modules("java.base")
    }}
    buildTypes.release.proguard.isEnabled.set(false)
}} }}
val isLinuxHost = true
''' + workaround + r'''
tasks.withType<AbstractJPackageTask>().configureEach {
    if (name in setOf("packageDeb", "packageRpm", "packageReleaseDeb", "packageReleaseRpm")) {
        doFirst {
            val creator = tasks.named<AbstractJPackageTask>(
                if (name.startsWith("packageRelease")) "createReleaseDistributable" else "createDistributable"
            ).get()
            check(appImage.get().asFile == creator.destinationDir.get().asFile.resolve("LongCp")) {
                "$name must package its patched distributable image"
            }
            val cfg = appImage.get().asFile.resolve("lib/app/LongCp.cfg")
            check(cfg.readLines().filter { it.startsWith("app.classpath=") } == listOf("app.classpath=\$APPDIR/*"))
            println("Verified patched input for $name")
            if (targetFormat == TargetFormat.Rpm && !providers.gradleProperty("testRealRpm").isPresent) {
                throw StopExecutionException("RPM input verified without rpmbuild")
            }
        }
    } else if (targetFormat == TargetFormat.AppImage) {
        doLast {
            if (name == "packageAppImage" || name == "packageReleaseAppImage") {
                check(destinationDir.get().asFile.name == "appimage-staging") {
                    "$name must not overwrite the distributable image"
                }
            }
            val appRoot = destinationDir.get().asFile.resolve("LongCp")
            val cfg = appRoot.resolve("lib/app/LongCp.cfg")
            val lines = cfg.readLines()
            check(lines.filter { it.startsWith("app.classpath=") } == listOf("app.classpath=\$APPDIR/*")) {
                "$name did not compact the launcher config"
            }
            check(lines.contains("app.mainclass=Main"))
            val process = ProcessBuilder(appRoot.resolve("bin/LongCp").absolutePath)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0 && output == "dependency-loaded\n") {
                "$name failed to launch: $output"
            }
            println("Verified dependency loading for $name")
        }
    }
}
gradle.projectsEvaluated {
    tasks.withType<AbstractJPackageTask>().configureEach {
        runtimeImage.set(layout.projectDirectory.dir("runtime"))
    }
}
'''
        (fixture / "build.gradle.kts").write_text(script)
        tasks = ["createDistributable", "createReleaseDistributable", "packageDeb", "packageReleaseDeb",
                 "packageRpm", "packageReleaseRpm", "packageAppImage", "packageReleaseAppImage"]
        command = [str(ROOT / "gradlew"), "-p", str(fixture), *tasks,
                   "-x", "createRuntimeImage",
                   "--no-daemon", "--console=plain", "-Dorg.gradle.jvmargs=-Xmx2048m"]
        real_rpm = shutil.which("rpmbuild") is not None
        if real_rpm:
            command.append("-PtestRealRpm=true")
        log = fixture / "gradle.log"
        with log.open("w") as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=600)
        if result.returncode:
            print("\n".join(log.read_text().splitlines()[-35:]))
            raise RuntimeError(f"Gradle fixture exited {result.returncode}")
        packages = sorted((fixture / "build/compose/binaries").rglob("*.deb"))
        assert len(packages) == 2, f"Expected debug and release DEBs, found {len(packages)}"
        for package in packages:
            extracted = fixture / (package.parent.parent.name + "-deb")
            run(["dpkg-deb", "-x", str(package), str(extracted)])
            launcher = next(extracted.rglob("bin/LongCp"))
            result = run([str(launcher)], capture_output=True, text=True)
            assert result.stdout == "dependency-loaded\n", result.stdout
        if real_rpm:
            print("Linux launcher fixture passed: four image launches, two DEB launches, four installer inputs and RPM packaging.")
        else:
            print("Linux launcher fixture passed: four image launches, two DEB launches and four installer inputs. RPM archives skipped (rpmbuild absent).")


if __name__ == "__main__":
    main()
