#!/usr/bin/env bash
set -eo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Build Linux release packages (DEB + AppImage).

Usage:
  ./scripts/build-linux-release.sh [options] [-- extra-gradle-args...]

Options:
  --deb-only      Build only the DEB package.
  --appimage-only Build only the AppImage.
  --clean         Clean composeApp before building.
  --skip-mpv      Skip system mpv checks (use bundled libmpv).
  -h, --help      Show this help.

Build requirements (for compiling player_bridge.so):
  - gcc, make, pkg-config, libmpv-dev (headers only)

Runtime requirements (bundled automatically in DEB/AppImage):
  - libmpv and its dependencies are declared as DEB dependencies
  - AppImage bundles libs via linuxdeploy (if available)

Optional for AppImage:
  - linuxdeploy (auto-downloads if missing)
  - appimagetool (for final AppImage creation)
USAGE
}

deb_only=false
appimage_only=false
clean=false
skip_mpv=false
extra_gradle_args=()

while (($#)); do
  case "$1" in
    --deb-only)      deb_only=true ;;
    --appimage-only) appimage_only=true ;;
    --clean)         clean=true ;;
    --skip-mpv)      skip_mpv=true ;;
    -h|--help)       usage; exit 0 ;;
    --)              shift; extra_gradle_args+=("$@"); break ;;
    *)               echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ "$deb_only" == true && "$appimage_only" == true ]]; then
  echo "Both --deb-only and --appimage-only specified; building both." >&2
  deb_only=false
  appimage_only=false
fi

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Linux packages can only be built on Linux." >&2
  exit 1
fi

echo "=== Nuvio Linux Release Build ==="
echo "Repo root: $repo_root"

# ------------------------------------------------------------------
# Check / install system dependencies
# ------------------------------------------------------------------
echo ""
echo "=== Step 1: Check build dependencies ==="
echo ""

if [[ "$skip_mpv" == false ]]; then
  if ! command -v pkg-config &>/dev/null; then
    echo "Installing pkg-config..."
    sudo apt-get update -qq && sudo apt-get install -y -qq pkg-config
  fi

  if ! pkg-config --exists mpv 2>/dev/null; then
    echo "libmpv-dev not found. Installing (needed for player_bridge.so compilation only)..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq libmpv-dev || {
      echo "WARNING: libmpv-dev installation failed."
      echo "  The build will try to use bundled headers in mediamp-mpv/libmpv/include/"
    }
  else
    echo "OK: libmpv-dev ($(pkg-config --modversion mpv))"
  fi
else
  echo "Skipping mpv system check (--skip-mpv)."
fi

for cmd in gcc make; do
  if command -v "$cmd" &>/dev/null; then
    echo "OK: $cmd found"
  else
    echo "Installing $cmd..."
    sudo apt-get install -y -qq "$cmd"
  fi
done

if command -v java &>/dev/null; then
  echo "OK: java ($(java -version 2>&1 | head -1))"
else
  echo "ERROR: Java not found. Install JDK 17+."
  exit 1
fi

# Check optional tools for AppImage
if [[ "$appimage_only" == true || "$deb_only" == false ]]; then
  if command -v linuxdeploy &>/dev/null; then
    echo "OK: linuxdeploy found (will bundle native libs in AppImage)"
  else
    echo "INFO: linuxdeploy not found. AppImage will be built without auto-bundling of system libs."
    echo "  Install for full bundling: https://github.com/linuxdeploy/linuxdeploy/releases"
  fi
  if command -v appimagetool &>/dev/null; then
    echo "OK: appimagetool found"
  else
    echo "WARNING: appimagetool not found. AppImage creation will fail."
    echo "  Install: sudo apt install appimagetool or from https://github.com/AppImage/AppImageKit/releases"
  fi
fi

# Ensure bundled native runtime dirs exist
mkdir -p composeApp/src/desktopMain/native/linux/live

# ------------------------------------------------------------------
# Version
# ------------------------------------------------------------------
echo ""
echo "=== Step 2: Read version ==="

version_file="composeApp/Configuration/DesktopVersion.properties"
if [[ ! -f "$version_file" ]]; then
  echo "ERROR: Version file not found: $version_file" >&2
  exit 1
fi

marketing_version=$(grep '^VERSION_NAME=' "$version_file" | sed 's/^VERSION_NAME=//' | tr -d ' \r')
project_version=$(grep '^VERSION_CODE=' "$version_file" | sed 's/^VERSION_CODE=//' | tr -d ' \r')
desktop_version="${marketing_version}"

echo "Version: $marketing_version"

# ------------------------------------------------------------------
# Build
# ------------------------------------------------------------------
echo ""
echo "=== Step 3: Build Linux release ==="

gradle_tasks=()
gradle_common=(
  "--no-configuration-cache"
)

if [[ "$clean" == true ]]; then
  gradle_tasks+=(":composeApp:clean")
fi

if [[ "$deb_only" == true ]]; then
  echo "Building DEB only..."
  gradle_tasks+=(":composeApp:packageReleaseDeb")
elif [[ "$appimage_only" == true ]]; then
  echo "Building AppImage only..."
  gradle_tasks+=(":composeApp:packageReleaseAppImage")
else
  echo "Building DEB + AppImage..."
  gradle_tasks+=(":composeApp:packageReleaseDeb" ":composeApp:packageReleaseAppImage")
fi

echo "Running: ./gradlew ${gradle_tasks[*]} ${gradle_common[*]} ${extra_gradle_args[*]}"
./gradlew "${gradle_tasks[@]}" "${gradle_common[@]}" "${extra_gradle_args[@]}"

# Patch DEB dependencies for all built DEB packages
deb_dirs=(
  "$repo_root/composeApp/build/compose/binaries/main-release/deb"
  "$repo_root/composeApp/build/compose/binaries/main/deb"
)
for deb_dir in "${deb_dirs[@]}"; do
  if [[ -d "$deb_dir" ]]; then
    for deb in "$deb_dir"/*.deb; do
      [[ -f "$deb" ]] || continue
      echo "Patching DEB deps: $(basename "$deb")"
      bash "$repo_root/scripts/patch-deb-deps.sh" "$deb"
    done
  fi
done

# ------------------------------------------------------------------
# Build AppImage from app image directory using appimagetool
# ------------------------------------------------------------------
app_image_parent="$repo_root/composeApp/build/compose/binaries/main-release/app"
app_image_dir="$app_image_parent/Nuvio"
appimage_output_dir="$repo_root/composeApp/build/compose/binaries/main-release/appimage"
icon_src="$repo_root/composeApp/src/desktopMain/resources/icons/nuvio-app-icon.png"

if [[ -d "$app_image_dir" ]] && command -v appimagetool &>/dev/null; then
  echo ""
  echo "Building AppImage with appimagetool..."

  # appimagetool requires a .desktop file, icon, and AppRun in the parent directory
  desktop_file="$app_image_parent/nuvio-app.desktop"
  cat > "$desktop_file" <<-EOF
[Desktop Entry]
Name=Nuvio
Exec=Nuvio/bin/Nuvio
Icon=nuvio-app
Type=Application
Categories=AudioVideo;Player;
Terminal=false
EOF

  # AppRun is the entry point executed when launching the AppImage
  apprun_file="$app_image_parent/AppRun"
  cat > "$apprun_file" <<-'EOF'
#!/bin/bash
dir="$(dirname "$(readlink -f "$0")")"
export LD_LIBRARY_PATH="$dir/Nuvio/lib:$dir/Nuvio/lib/runtime/lib:${LD_LIBRARY_PATH:-}"
exec "$dir/Nuvio/bin/Nuvio" "$@"
EOF
  chmod +x "$apprun_file"

  # Copy icon to parent directory (appimagetool looks for it alongside the .desktop)
  if [[ -f "$icon_src" ]]; then
    cp "$icon_src" "$app_image_parent/nuvio-app.png"
  fi

  mkdir -p "$appimage_output_dir"
  appimage_name="Nuvio-${marketing_version}-x86_64.AppImage"
  ARCH=x86_64 appimagetool "$app_image_parent" "$appimage_output_dir/$appimage_name"
  echo "AppImage created: $appimage_output_dir/$appimage_name"
fi

# ------------------------------------------------------------------
# Collect artifacts
# ------------------------------------------------------------------
echo ""
echo "=== Step 4: Collect artifacts ==="

release_dir="$repo_root/release-assets/linux"
mkdir -p "$release_dir"

# DEB
deb_src="$repo_root/composeApp/build/compose/binaries/main-release/deb"
if [[ -d "$deb_src" ]]; then
  for deb in "$deb_src"/*.deb; do
    [[ -f "$deb" ]] || continue
    cp "$deb" "$release_dir/"
    echo "  DEB: $(basename "$deb")"
  done
fi

# Also check non-release directory
deb_src_debug="$repo_root/composeApp/build/compose/binaries/main/deb"
if [[ -d "$deb_src_debug" ]]; then
  for deb in "$deb_src_debug"/*.deb; do
    [[ -f "$deb" ]] || continue
    cp "$deb" "$release_dir/"
    echo "  DEB (debug): $(basename "$deb")"
  done
fi

# Rename DEB with version
for deb in "$release_dir"/*.deb; do
  [[ -f "$deb" ]] || continue
  new_name="$release_dir/nuvio_${marketing_version}_amd64.deb"
  if [[ "$(basename "$deb")" != "$(basename "$new_name")" ]]; then
    mv "$deb" "$new_name" 2>/dev/null || true
    echo "  Renamed DEB -> $(basename "$new_name")"
  fi
done

# AppImage
appimage_src="$repo_root/composeApp/build/compose/binaries/main-release/appimage"
if [[ -d "$appimage_src" ]]; then
  for appimg in "$appimage_src"/*.AppImage; do
    [[ -f "$appimg" ]] || continue
    cp "$appimg" "$release_dir/"
    echo "  AppImage: $(basename "$appimg")"
  done
  # Also copy zsync if present
  for zsync in "$appimage_src"/*.AppImage.zsync; do
    [[ -f "$zsync" ]] || continue
    cp "$zsync" "$release_dir/"
  done
fi

# Also check composeApp root for AppImage
for appimg in "$repo_root/composeApp"/*.AppImage; do
  [[ -f "$appimg" ]] || continue
  cp "$appimg" "$release_dir/"
  echo "  AppImage (root): $(basename "$appimg")"
done

# ------------------------------------------------------------------
# Summary
# ------------------------------------------------------------------
echo ""
echo "=== Step 5: Build outputs ==="
echo ""
echo "Release directory: $release_dir"
ls -lh "$release_dir/" 2>/dev/null || echo "(empty)"

echo ""
echo "=== Done ==="
