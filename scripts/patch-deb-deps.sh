#!/usr/bin/env bash
set -e

DEB_PATH="$1"

TMPDIR=$(mktemp -d)
trap 'rm -rf "$TMPDIR"' EXIT

cd "$TMPDIR"

ar x "$DEB_PATH"

# --- Patch control ---
mkdir ctrl
cd ctrl
tar xf "$TMPDIR/control.tar.xz"

sed -i '/^Depends:/d' control
sed -i '/^Description:/i\Depends: libmpv2 (>= 0.30.0) | libmpv1, libasound2 (>= 1.0) | libasound2t64, libpulse0 (>= 0.9.0) | libpulse-tmd0, libvulkan1, libx11-6, libgl1 | libglx-mesa0 | libglx0, zlib1g (>= 1.2.0) | zlib1g-t64' control

echo "--- control after patch ---"
cat control

tar cf - --owner=root --group=root --numeric-owner . | xz > "$TMPDIR/control.tar.xz"
cd "$TMPDIR"

# --- Patch data (fix .desktop file) ---
mkdir data
cd data
tar xf "$TMPDIR/data.tar.xz"

desktop_files=$(find . -name '*.desktop' 2>/dev/null || true)
for df in $desktop_files; do
    echo "Fixing: $df"
    sed -i 's/^Categories=Unknown/Categories=AudioVideo;Player;/' "$df"
done

tar cf - --owner=root --group=root --numeric-owner . | xz > "$TMPDIR/data.tar.xz"
cd "$TMPDIR"

# Rebuild the .deb
rm -f "$DEB_PATH"
ar rc "$DEB_PATH" debian-binary control.tar.xz data.tar.xz

echo "OK: $DEB_PATH patched"
