#!/usr/bin/env bash
set -euo pipefail

# setup-macos-libs.sh
# Downloads mpv macOS libraries and headers for the NuvioDesktop native player bridge.
# Uses prebuilt mpv binaries from FengZeng/soia's mpv fork + headers from official mpv.
# Run from the project root directory.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MPVKIT_DIR="$PROJECT_ROOT/MPVKit"

MPV_VERSION="0.41.0-r12"
MPV_SOIA_RELEASE="https://github.com/FengZeng/mpv/releases/download/v${MPV_VERSION}"
MPV_REPO="https://raw.githubusercontent.com/mpv-player/mpv/master/include/mpv"

detect_arch() {
    local arch
    arch="$(uname -m)"
    case "$arch" in
        arm64|aarch64) echo "arm64" ;;
        x86_64|amd64)  echo "x86_64" ;;
        *) echo "Unsupported architecture: $arch" >&2; exit 1 ;;
    esac
}

setup() {
    local arch
    arch="$(detect_arch)"
    echo "[setup-macos] Detected architecture: $arch"

    local target_dir="$MPVKIT_DIR/dist/libmpv/macos/thin/$arch"
    local lib_dir="$target_dir/lib"
    local include_dir="$target_dir/include"
    local pkgconfig_dir="$lib_dir/pkgconfig"

    mkdir -p "$lib_dir" "$include_dir/mpv" "$pkgconfig_dir"

    # Step 1: Download mpv dylib + dependencies from Soia's release
    local archive="libmpv-${MPV_VERSION}-macos-${arch}.tar.gz"
    local archive_url="${MPV_SOIA_RELEASE}/${archive}"

    if [ ! -f "$lib_dir/libmpv.2.dylib" ]; then
        echo "[setup-macos] Downloading $archive_url ..."
        curl -fSL -o "/tmp/${archive}" "$archive_url" || {
            echo "[setup-macos] Failed to download $archive_url" >&2
            exit 1
        }
        echo "[setup-macos] Extracting libraries ..."
        tar xzf "/tmp/${archive}" -C "$lib_dir" --strip-components=2 "lib/"
        rm "/tmp/${archive}"
    else
        echo "[setup-macos] libmpv.2.dylib already present, skipping download."
    fi

    # Step 2: Create a pkg-config .pc file
    if [ ! -f "$pkgconfig_dir/mpv.pc" ]; then
        echo "[setup-macos] Creating mpv.pc for dynamic linking ..."
        cat > "$pkgconfig_dir/mpv.pc" <<-PCEOF
prefix=$target_dir
exec_prefix=\${prefix}
libdir=\${exec_prefix}/lib
includedir=\${prefix}/include

Name: mpv
Description: mpv dynamic library (from Soia prebuilt)
Version: ${MPV_VERSION}
Libs: -L\${libdir} -lmpv.2
Cflags: -I\${includedir}
PCEOF
    fi

    # Step 3: Download mpv C headers from official repo
    if [ ! -f "$include_dir/mpv/client.h" ]; then
        echo "[setup-macos] Downloading mpv headers ..."
        for header in client.h render.h render_gl.h; do
            echo "  -> $header"
            curl -fSL -o "$include_dir/mpv/$header" "${MPV_REPO}/${header}"
        done
    else
        echo "[setup-macos] mpv headers already present, skipping."
    fi

    echo ""
    echo "[setup-macos] Done! Files installed in: $target_dir"
    echo "[setup-macos] libs:  $(ls "$lib_dir"/*.dylib 2>/dev/null | wc -l) dylibs"
    echo "[setup-macos] pc:    $([ -f "$pkgconfig_dir/mpv.pc" ] && echo 'yes' || echo 'no')"
    echo "[setup-macos] hdrs:  $(ls "$include_dir/mpv/"*.h 2>/dev/null | wc -l) headers"
    echo ""
    echo "[setup-macos] Now run: ./gradlew :composeApp:buildMacosPlayerBridge"
}

setup
