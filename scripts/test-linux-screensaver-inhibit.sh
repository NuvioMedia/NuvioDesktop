#!/usr/bin/env bash
# Isolated regression runner: no Android SDK, Gradle, native player or desktop required.
# Usage: bash scripts/test-linux-screensaver-inhibit.sh /path/to/NuvioDesktop [--unit-only|--dbus]
set -euo pipefail
repo_root=$(cd -- "${1:-.}" && pwd)
mode=${2:---unit-only}
case "$mode" in --unit-only|--dbus) ;; *) echo 'Use --unit-only or --dbus' >&2; exit 2 ;; esac
work_dir=${NUVIO_TEST_WORK_DIR:-$(mktemp -d -t nuvio-inhibit-tests.XXXXXXXX)}
mkdir -p "$work_dir/deps" "$work_dir/classes"
for tool in java curl; do command -v "$tool" >/dev/null; done
while read -r group artifact version; do
    destination="$work_dir/deps/$artifact-$version.jar"
    if [[ ! -s "$destination" ]]; then
        curl -fsSL --retry 2 --connect-timeout 15 --max-time 120 \
            "https://repo.maven.apache.org/maven2/$group/$artifact/$version/$artifact-$version.jar" \
            -o "$destination.part"
        mv -- "$destination.part" "$destination"
    fi
done <<'DEPS'
org/jetbrains/kotlin kotlin-compiler-embeddable 2.4.10
org/jetbrains/kotlin kotlin-build-tools-api 2.4.10
org/jetbrains/kotlin kotlin-stdlib 2.4.10
org/jetbrains/kotlin kotlin-script-runtime 2.4.10
org/jetbrains/kotlin kotlin-reflect 1.6.10
org/jetbrains/kotlin kotlin-daemon-embeddable 2.4.10
org/jetbrains/kotlinx kotlinx-coroutines-core-jvm 1.8.0
org/jetbrains annotations 13.0
net/java/dev/jna jna 5.19.1
co/touchlab kermit-jvm 2.0.5
co/touchlab kermit-core-jvm 2.0.5
org/jetbrains/kotlin kotlin-test 2.4.10
org/jetbrains/kotlin kotlin-test-junit 2.4.10
junit junit 4.13.2
org/hamcrest hamcrest-core 1.3
DEPS
classpath=$(printf '%s:' "$work_dir"/deps/*.jar)
classpath=${classpath%:}
main_dir="$repo_root/composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player"
test_dir="$repo_root/composeApp/src/desktopTest/kotlin/com/nuvio/app/features/player"
java -cp "$classpath" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 11 -classpath "$classpath" \
    -d "$work_dir/classes" \
    "$main_dir/LinuxKeepAwakeController.kt" "$main_dir/LinuxScreenSaverInhibitor.kt" \
    "$test_dir/LinuxKeepAwakeControllerTest.kt" \
    "$test_dir/LinuxScreenSaverDbusMarshallingTest.kt" \
    "$test_dir/LinuxScreenSaverConnectionTest.kt" \
    "$test_dir/LinuxScreenSaverInhibitorDbusTest.kt" 2>&1 | tee "$work_dir/compile.log"
runner=(java -cp "$work_dir/classes:$classpath" org.junit.runner.JUnitCore
    com.nuvio.app.features.player.LinuxKeepAwakeControllerTest
    com.nuvio.app.features.player.LinuxScreenSaverDbusMarshallingTest
    com.nuvio.app.features.player.LinuxScreenSaverConnectionTest)
if [[ "$mode" == --dbus ]]; then
    command -v dbus-run-session >/dev/null
    command -v python3 >/dev/null
    python3 -m venv "$work_dir/venv"
    "$work_dir/venv/bin/python" -m pip install --quiet dbus-next==0.2.3
    runner+=(com.nuvio.app.features.player.LinuxScreenSaverInhibitorDbusTest)
    dbus-run-session -- "$work_dir/venv/bin/python" \
        "$repo_root/scripts/test-linux-screensaver-inhibit.py" -- "${runner[@]}" \
        2>&1 | tee "$work_dir/test.log"
else
    "${runner[@]}" 2>&1 | tee "$work_dir/test.log"
fi
printf 'Verification output: %s\n' "$work_dir"
