#!/usr/bin/env bash
set -eo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Build Linux release packages for Fedora (RPM + AppImage).

Usage:
  ./scripts/build-linux-fedora-release.sh [options] [-- extra-gradle-args...]

Options:
  --rpm-only       Build only the RPM package.
  --appimage-only  Build only the AppImage.
  --clean          Clean composeApp before building.
  --skip-mpv       Skip system mpv checks (use bundled libmpv).
  -h, --help       Show this help.

Build requirements (for compiling player_bridge.so):
  - gcc, make, pkgconf, mpv-devel (headers only)

Runtime requirements (bundled automatically in RPM/AppImage):
  - libmpv and its dependencies are declared as RPM dependencies
  - AppImage bundles libs via linuxdeploy (if available)

Optional for AppImage:
  - linuxdeploy (auto-downloads if missing)
  - appimagetool (for final AppImage creation)
USAGE
}

rpm_only=false
appimage_only=false
clean=false
skip_mpv=false
extra_gradle_args=()

while (($#)); do
  case "$1" in
    --rpm-only)      rpm_only=true ;;
    --appimage-only) appimage_only=true ;;
    --clean)         clean=true ;;
    --skip-mpv)      skip_mpv=true ;;
    -h|--help)       usage; exit 0 ;;
    --)              shift; extra_gradle_args+=("$@"); break ;;
    *)               echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ "$rpm_only" == true && "$appimage_only" == true ]]; then
  echo "Both --rpm-only and --appimage-only specified; building both." >&2
  rpm_only=false
  appimage_only=false
fi

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "Linux packages can only be built on Linux." >&2
  exit 1
fi

echo "=== Nuvio Fedora Release Build ==="
echo "Repo root: $repo_root"

# ------------------------------------------------------------------
# Check / install system dependencies
# ------------------------------------------------------------------
echo ""
echo "=== Step 1: Check build dependencies ==="
echo ""

if [[ "$skip_mpv" == false ]]; then
  if ! command -v pkg-config &>/dev/null; then
    echo "Installing pkgconf..."
    sudo dnf install -y pkgconf
  fi

  if ! pkg-config --exists mpv 2>/dev/null; then
    echo "mpv-devel not found. Installing (needed for player_bridge.so compilation only)..."
    sudo dnf install -y mpv-devel || {
      echo "WARNING: mpv-devel installation failed."
      echo "  The build will try to use bundled headers in mediamp-mpv/libmpv/include/"
    }
  else
    echo "OK: mpv-devel ($(pkg-config --modversion mpv))"
  fi
else
  echo "Skipping mpv system check (--skip-mpv)."
fi

for cmd in gcc make; do
  if command -v "$cmd" &>/dev/null; then
    echo "OK: $cmd found"
  else
    echo "Installing $cmd..."
    sudo dnf install -y "$cmd"
  fi
done

if command -v java &>/dev/null; then
  echo "OK: java ($(java -version 2>&1 | head -1))"
else
  echo "ERROR: Java not found. Install JDK 17+ with: sudo dnf install java-17-openjdk-devel"
  exit 1
fi

# Check optional tools for AppImage
if [[ "$appimage_only" == true || "$rpm_only" == false ]]; then
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
    echo "  Install: sudo dnf install appimagetool or from https://github.com/AppImage/AppImageKit/releases"
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
echo "=== Step 3: Build Fedora release ==="

gradle_tasks=()
gradle_common=(
  "--no-configuration-cache"
  "--no-daemon"
)

if [[ "$clean" == true ]]; then
  gradle_tasks+=(":composeApp:clean")
fi

if [[ "$rpm_only" == true ]]; then
  echo "Building RPM only..."
  gradle_tasks+=(":composeApp:patchLinuxRpmDependencies")
elif [[ "$appimage_only" == true ]]; then
  echo "Building AppImage only..."
  gradle_tasks+=(":composeApp:buildAppImage")
else
  echo "Building RPM + AppImage..."
  gradle_tasks+=(":composeApp:patchLinuxRpmDependencies" ":composeApp:buildAppImage")
fi

echo "Running: ./gradlew ${gradle_tasks[*]} ${gradle_common[*]} ${extra_gradle_args[*]}"
./gradlew "${gradle_tasks[@]}" "${gradle_common[@]}" "${extra_gradle_args[@]}"

# ------------------------------------------------------------------
# Collect artifacts
# ------------------------------------------------------------------
echo ""
echo "=== Step 4: Collect artifacts ==="

release_dir="$repo_root/release-assets/linux-fedora"
mkdir -p "$release_dir"

# RPM
rpm_src="$repo_root/composeApp/build/compose/binaries/main-release/rpm"
if [[ -d "$rpm_src" ]]; then
  for rpm in "$rpm_src"/*.rpm; do
    [[ -f "$rpm" ]] || continue
    cp "$rpm" "$release_dir/"
    echo "  RPM: $(basename "$rpm")"
  done
fi

rpm_src_debug="$repo_root/composeApp/build/compose/binaries/main/rpm"
if [[ -d "$rpm_src_debug" ]]; then
  for rpm in "$rpm_src_debug"/*.rpm; do
    [[ -f "$rpm" ]] || continue
    cp "$rpm" "$release_dir/"
    echo "  RPM (debug): $(basename "$rpm")"
  done
fi

# Rename RPM with version
for rpm in "$release_dir"/*.rpm; do
  [[ -f "$rpm" ]] || continue
  new_name="$release_dir/nuvio-${marketing_version}-1.x86_64.rpm"
  if [[ "$(basename "$rpm")" != "$(basename "$new_name")" ]]; then
    mv "$rpm" "$new_name" 2>/dev/null || true
    echo "  Renamed RPM -> $(basename "$new_name")"
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
  for zsync in "$appimage_src"/*.AppImage.zsync; do
    [[ -f "$zsync" ]] || continue
    cp "$zsync" "$release_dir/"
  done
fi

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
