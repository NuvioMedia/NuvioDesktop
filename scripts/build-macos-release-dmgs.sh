#!/usr/bin/env bash
set -eo pipefail

usage() {
  cat <<'USAGE'
Build both macOS release DMGs.

Usage:
  ./scripts/build-macos-release-dmgs.sh [options] [-- extra-gradle-args...]

Options:
  --notarize       Build, notarize, and staple both DMGs. This is the default.
  --package-only   Build both signed release DMGs without notarizing/stapling.
  --clean          Clean composeApp before the first selected build.
  --skip-arm       Skip the Apple Silicon arm64 DMG.
  --skip-intel     Skip the Intel x86_64 DMG.
  -h, --help       Show this help.

Environment:
  NUVIO_MACOS_X64_JDK_HOME  Optional x86_64 JDK home.
                            Defaults to ~/.nuvio/jdks/temurin-17-x64/Contents/Home.
USAGE
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

task=":composeApp:notarizeReleaseDmgWithKeychain"
clean=false
skip_arm=false
skip_intel=false
extra_gradle_args=()

while (($#)); do
  case "$1" in
    --notarize)
      task=":composeApp:notarizeReleaseDmgWithKeychain"
      ;;
    --package-only)
      task=":composeApp:packageReleaseDmg"
      ;;
    --clean)
      clean=true
      ;;
    --skip-arm)
      skip_arm=true
      ;;
    --skip-intel)
      skip_intel=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      extra_gradle_args+=("$@")
      break
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ "$skip_arm" == true && "$skip_intel" == true ]]; then
  echo "Nothing to build: both --skip-arm and --skip-intel were passed." >&2
  exit 2
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS DMGs can only be built on macOS." >&2
  exit 1
fi

common_gradle_args=(
  "-Pcompose.desktop.packaging.checkJdkVendor=false"
  "--no-configuration-cache"
  "--no-daemon"
  "--rerun-tasks"
)

run_cmd() {
  echo
  printf '==> '
  printf '%q ' "$@"
  echo
  "$@"
}

if [[ "$skip_arm" == false ]]; then
  arm_tasks=()
  if [[ "$clean" == true ]]; then
    arm_tasks+=(":composeApp:clean")
  fi
  arm_tasks+=("$task")

  echo "Building macOS arm64 release DMG..."
  run_cmd ./gradlew "${arm_tasks[@]}" "${common_gradle_args[@]}" "${extra_gradle_args[@]}"
fi

if [[ "$skip_intel" == false ]]; then
  x64_jdk_home="${NUVIO_MACOS_X64_JDK_HOME:-$HOME/.nuvio/jdks/temurin-17-x64/Contents/Home}"
  x64_java="$x64_jdk_home/bin/java"

  if [[ ! -x "$x64_java" ]]; then
    cat >&2 <<EOF
x86_64 JDK not found at:
  $x64_jdk_home

Set NUVIO_MACOS_X64_JDK_HOME to an x86_64 JDK home, or install one at the default path.
EOF
    exit 1
  fi

  if ! arch -x86_64 /usr/bin/true >/dev/null 2>&1; then
    echo "Rosetta is required for the Intel x86_64 build." >&2
    exit 1
  fi

  if ! arch -x86_64 "$x64_java" -version >/dev/null 2>&1; then
    echo "The configured JDK is not runnable as x86_64: $x64_jdk_home" >&2
    exit 1
  fi

  intel_tasks=()
  if [[ "$clean" == true && "$skip_arm" == true ]]; then
    intel_tasks+=(":composeApp:clean")
  fi
  intel_tasks+=("$task")

  echo "Building macOS x86_64 release DMG..."
  run_cmd env \
    "JAVA_HOME=$x64_jdk_home" \
    "PATH=$x64_jdk_home/bin:$PATH" \
    arch -x86_64 ./gradlew \
    "${intel_tasks[@]}" \
    "-Pnuvio.macos.arch=x86_64" \
    "${common_gradle_args[@]}" \
    "${extra_gradle_args[@]}"
fi

echo
echo "macOS release DMGs:"
ls -lh composeApp/build/compose/release-dmgs/*.dmg
