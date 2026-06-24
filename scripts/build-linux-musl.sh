#!/usr/bin/env bash
set -eo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Build Nuvio for musl-based distros (Alpine, Void musl, etc.)

Usage:
  ./scripts/build-linux-musl.sh [options]

Options:
  --docker         Build inside Alpine Docker container (default).
  --local          Build on local musl system (requires Alpine/Void).
  --clean          Clean composeApp before building.
  -h, --help       Show this help.

How it works:
  1. Uses Gradle with -PlinuxCc=musl-gcc to compile player_bridge.c
  2. Gradle links mpv statically via pkg-config --libs --static mpv
  3. Packages as a standalone AppImage

The Docker approach is recommended — it sets up a clean Alpine
environment automatically.
USAGE
}

docker_mode=true
clean=false

while (($#)); do
  case "$1" in
    --docker)     docker_mode=true ;;
    --local)      docker_mode=false ;;
    --clean)      clean=true ;;
    -h|--help)    usage; exit 0 ;;
    *)            echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

# ------------------------------------------------------------------
# Docker-based build (recommended)
# ------------------------------------------------------------------
if [[ "$docker_mode" == true ]]; then
  echo "=== Building Nuvio for musl via Alpine Docker ==="

  if ! command -v docker &>/dev/null; then
    echo "ERROR: Docker not found. Install Docker or use --local." >&2
    exit 1
  fi

  docker run --rm -it \
    -v "$repo_root":/workspace \
    -w /workspace \
    alpine:latest \
    sh -c "
      set -e
      echo '=== Installing build dependencies ==='
      apk add --no-cache \
        openjdk21-jdk \
        gcc \
        musl-dev \
        make \
        pkgconf \
        mpv-dev \
        mpv-libs-static \
        egl-dev \
        mesa-gl-dev \
        gbm-dev \
        libx11-dev \
        zlib-dev \
        git \
        curl

      export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
      export PATH=\"\$JAVA_HOME/bin:\$PATH\"

      echo '=== Building with Gradle (musl-gcc) ==='
      cd /workspace
      touch local.properties

      gradle_tasks=()
      $( [[ '$clean' == true ]] && echo 'gradle_tasks+=(":composeApp:clean")' )

      gradle_tasks+=(
        \":composeApp:buildLinuxPlayerBridge\"
        \":composeApp:prepareLinuxPlayerRuntime\"
        \":composeApp:generateLinuxPlayerRuntimeIndex\"
        \":composeApp:desktopJar\"
      )

      ./gradlew \"\${gradle_tasks[@]}\" \
        --no-configuration-cache \
        -PlinuxCc=musl-gcc \
        -PlinuxCflags='-static-libgcc'

      echo '=== Creating musl AppDir ==='
      APPDIR=/tmp/nuvio-musl.AppDir
      mkdir -p \"\$APPDIR/usr/bin\" \"\$APPDIR/usr/lib/nuvio\" \"\$APPDIR/usr/share/applications\" \"\$APPDIR/usr/share/icons/hicolor/256x256/apps\"

      # Copy the JAR
      cp composeApp/build/libs/desktop.jar \"\$APPDIR/usr/lib/nuvio/nuvio.jar\"

      # Copy the musl-compiled native bridge
      cp composeApp/build/native/linux/libplayer_bridge.so \"\$APPDIR/usr/lib/nuvio/\"

      # Copy the runtime files (libmpv.so, libmpv.so.2) from the build output
      cp composeApp/build/native/linux-runtime/* \"\$APPDIR/usr/lib/nuvio/\" 2>/dev/null || true

      # Create launcher script
      cat > \"\$APPDIR/usr/bin/nuvio\" << 'LAUNCHER'
#!/bin/sh
APP_DIR=\"\$(dirname \"\$0\")\"
LIB_DIR=\"\$APP_DIR/../lib/nuvio\"
exec java -Djava.library.path=\"\$LIB_DIR\" -jar \"\$LIB_DIR/nuvio.jar\" \"\$@\"
LAUNCHER
      chmod +x \"\$APPDIR/usr/bin/nuvio\"

      # Copy desktop file and icon
      cp composeApp/src/desktopMain/resources/*.desktop \"\$APPDIR/usr/share/applications/\" 2>/dev/null || true
      cp composeApp/src/desktopMain/resources/*.png \"\$APPDIR/usr/share/icons/hicolor/256x256/apps/\" 2>/dev/null || true

      echo '=== AppDir contents ==='
      ls -la \"\$APPDIR/usr/lib/nuvio/\"

      echo '=== Done ==='
      echo 'AppDir: /tmp/nuvio-musl.AppDir'
      echo 'To create AppImage: appimagetool /tmp/nuvio-musl.AppDir Nuvio-musl-x86_64.AppImage'
    "

  echo ""
  echo "=== Docker build complete ==="
  exit 0
fi

# ------------------------------------------------------------------
# Local musl build (for Alpine / Void musl systems)
# ------------------------------------------------------------------
echo "=== Building Nuvio for musl (local) ==="

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Linux packages can only be built on Linux." >&2
  exit 1
fi

# Check for musl-gcc
MUSL_GCC=""
for cc in musl-gcc x86_64-linux-musl-gcc; do
  if command -v "$cc" &>/dev/null; then
    MUSL_GCC="$cc"
    break
  fi
done

if [[ -z "$MUSL_GCC" ]]; then
  echo "ERROR: musl-gcc not found." >&2
  echo "  Alpine: apk add gcc musl-dev" >&2
  echo "  Void:   xbps-install -S musl-cross" >&2
  exit 1
fi

echo "Using: $MUSL_GCC"

for cmd in pkg-config make java; do
  if command -v "$cmd" &>/dev/null; then
    echo "OK: $cmd found"
  else
    echo "ERROR: $cmd not found." >&2
    exit 1
  fi
done

if ! pkg-config --exists mpv 2>/dev/null; then
  echo "ERROR: libmpv-dev not found." >&2
  echo "  Alpine: apk add mpv-dev" >&2
  exit 1
fi
echo "OK: mpv $(pkg-config --modversion mpv)"

echo ""
echo "=== Building with Gradle (musl-gcc) ==="

touch local.properties

gradle_tasks=()
if [[ "$clean" == true ]]; then
  gradle_tasks+=(":composeApp:clean")
fi

gradle_tasks+=(
  ":composeApp:buildLinuxPlayerBridge"
  ":composeApp:prepareLinuxPlayerRuntime"
  ":composeApp:generateLinuxPlayerRuntimeIndex"
  ":composeApp:desktopJar"
)

./gradlew "${gradle_tasks[@]}" \
  --no-configuration-cache \
  -PlinuxCc="$MUSL_GCC" \
  -PlinuxCflags="-static-libgcc"

echo ""
echo "=== Build complete ==="
echo "JAR: composeApp/build/libs/desktop.jar"
echo "Bridge: composeApp/build/native/linux/libplayer_bridge.so"
echo "Runtime: composeApp/build/native/linux-runtime/"
echo ""
echo "To create AppImage:"
echo "  1. Create AppDir structure"
echo "  2. Copy JAR + native libs into AppDir/usr/lib/nuvio/"
echo "  3. appimagetool AppDir Nuvio-musl-x86_64.AppImage"
