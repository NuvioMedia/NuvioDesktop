#!/usr/bin/env bash
set -e

RPM_PATH="$1"
WORK_DIR="${2:-$(mktemp -d)}"

if [[ -z "$RPM_PATH" || ! -f "$RPM_PATH" ]]; then
  echo "Usage: $0 <path-to-rpm> [work-dir]" >&2
  exit 1
fi

RPM_DIR="$(dirname "$RPM_PATH")"
RPM_NAME="$(basename "$RPM_PATH")"

mkdir -p "$WORK_DIR"

echo "Patching RPM: $RPM_NAME"

# Use rpmrebuild if available, otherwise fall back to manual approach
if command -v rpmrebuild &>/dev/null; then
  echo "Using rpmrebuild to add dependencies..."

  # Build requires filter to append our runtime deps
  rpmrebuild \
    --change-spec-requires="s/^Requires:.*/&, libmpv, alsa-lib, pulseaudio-libs, vulkan-loader, libX11, mesa-libGL, zlib, wayland-client, wayland-cursor, wayland-egl, libdecor/" \
    --directory="$WORK_DIR" \
    --verbose \
    "$RPM_PATH" 2>&1

  # Find the rebuilt RPM
  rebuilt_rpm=$(find "$WORK_DIR" -name "*.rpm" -type f 2>/dev/null | head -1)
  if [[ -n "$rebuilt_rpm" ]]; then
    cp "$rebuilt_rpm" "$RPM_DIR/$RPM_NAME"
    echo "OK: $RPM_NAME patched with RPM dependencies (via rpmrebuild)"
  else
    echo "WARNING: rpmrebuild finished but no RPM found in $WORK_DIR" >&2
    exit 1
  fi
else
  echo "rpmrebuild not found. Attempting to install it..."
  if command -v dnf &>/dev/null; then
    sudo dnf install -y rpmrebuild
  elif command -v yum &>/dev/null; then
    sudo yum install -y rpmrebuild
  else
    echo "ERROR: rpmrebuild is required. Install it manually:" >&2
    echo "  sudo dnf install rpmrebuild" >&2
    exit 1
  fi

  # Retry with rpmrebuild
  rpmrebuild \
    --change-spec-requires="s/^Requires:.*/&, libmpv, alsa-lib, pulseaudio-libs, vulkan-loader, libX11, mesa-libGL, zlib, wayland-client, wayland-cursor, wayland-egl, libdecor/" \
    --directory="$WORK_DIR" \
    --verbose \
    "$RPM_PATH" 2>&1

  rebuilt_rpm=$(find "$WORK_DIR" -name "*.rpm" -type f 2>/dev/null | head -1)
  if [[ -n "$rebuilt_rpm" ]]; then
    cp "$rebuilt_rpm" "$RPM_DIR/$RPM_NAME"
    echo "OK: $RPM_NAME patched with RPM dependencies"
  else
    echo "ERROR: Failed to rebuild RPM with dependencies." >&2
    exit 1
  fi
fi

echo "Done: $RPM_DIR/$RPM_NAME"
