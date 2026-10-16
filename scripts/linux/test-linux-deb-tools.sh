#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
patch_script="$script_dir/patch-linux-deb.sh"
verify_script="$script_dir/verify-linux-deb.sh"
# shellcheck source=linux-deb-dependencies.sh
source "$script_dir/linux-deb-dependencies.sh"

for command in dpkg-deb truncate; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "$command is required to test Linux DEB tooling." >&2
        exit 1
    fi
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-deb-test.XXXXXX")"
cleanup() {
    rm -rf "$work_dir"
}
trap cleanup EXIT

package_root="$work_dir/package root"
mkdir -p "$package_root/DEBIAN" "$package_root/usr/share/nuvio-test"
cat > "$package_root/DEBIAN/control" <<'CONTROL'
Package: nuvio-test
Version: 1.0-1
Architecture: all
Maintainer: Nuvio Media <contact@nuvio.tv>
Depends: libmpv2 (>= 0.35),
 libwebkit2gtk-4.1-0 | nuvio-webkit-fallback,
 libxcomposite1,
 libxext6
Description: Linux DEB tooling fixture
CONTROL
printf 'fixture\n' > "$package_root/usr/share/nuvio-test/data file.txt"

# jpackage's own generated entry plus the xdg-desktop-menu hooks that
# register it — the patch is supposed to get rid of both.
mkdir -p "$package_root/opt/nuvio/lib"
cat > "$package_root/opt/nuvio/lib/nuvio-Nuvio.desktop" <<'JPACKAGE'
[Desktop Entry]
Name=Nuvio
Comment=Nuvio
Exec=/opt/nuvio/bin/Nuvio
Icon=/opt/nuvio/lib/Nuvio.png
Terminal=false
Type=Application
Categories=Nuvio
MimeType=
JPACKAGE
cat > "$package_root/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e
case "$1" in
    configure)
xdg-desktop-menu install /opt/nuvio/lib/nuvio-Nuvio.desktop
    ;;
esac
exit 0
POSTINST
cat > "$package_root/DEBIAN/prerm" <<'PRERM'
#!/bin/sh
set -e
case "$1" in
    remove|upgrade|deconfigure)
xdg-desktop-menu uninstall /opt/nuvio/lib/nuvio-Nuvio.desktop
    ;;
esac
exit 0
PRERM
chmod 755 "$package_root/DEBIAN/postinst" "$package_root/DEBIAN/prerm"

fixture="$work_dir/Nuvio fixture package.deb"
dpkg-deb --root-owner-group -b "$package_root" "$fixture" >/dev/null

"$patch_script" "$fixture"
"$patch_script" "$fixture"
"$verify_script" "$fixture"

# Both the generated entry and its registration hooks should be gone,
# leaving exactly the one launcher we ship.
stripped_control="$work_dir/stripped-control"
mkdir -p "$stripped_control"
dpkg-deb -e "$fixture" "$stripped_control"
if grep -R "xdg-desktop-menu" "$stripped_control" >/dev/null 2>&1; then
    echo "Maintainer scripts still register a generated desktop entry." >&2
    grep -R "xdg-desktop-menu" "$stripped_control" >&2 || true
    exit 1
fi

stripped_contents="$work_dir/stripped-contents.txt"
dpkg-deb --contents "$fixture" > "$stripped_contents"
if grep -q "opt/nuvio/lib/nuvio-Nuvio.desktop" "$stripped_contents"; then
    echo "DEB still ships the jpackage-generated desktop entry." >&2
    exit 1
fi
launcher_count="$(awk '{print $6}' "$stripped_contents" | grep -c '^\./usr/share/applications/.*\.desktop$' || true)"
if [[ "$launcher_count" -ne 1 ]]; then
    echo "Expected exactly one desktop launcher in the DEB; found $launcher_count." >&2
    awk '{print $6}' "$stripped_contents" | grep -E '^\./usr/share/applications/.*\.desktop$' >&2 || true
    exit 1
fi

depends="$(dpkg-deb -f "$fixture" Depends)"
for dependency in libmpv2 libwebkit2gtk-4.1-0 libxcomposite1 libxext6; do
    count="$(deb_relationship_package_count "$depends" "$dependency")"
    if [[ "$count" -ne 1 ]]; then
        echo "Expected one $dependency relationship after two patch passes; found $count." >&2
        echo "Depends: $depends" >&2
        exit 1
    fi
done

non_root_fixture="$work_dir/non-root.deb"
dpkg-deb -b "$package_root" "$non_root_fixture" >/dev/null 2>&1
if "$verify_script" "$non_root_fixture" >/dev/null 2>&1; then
    echo "Verifier accepted a DEB containing non-root-owned entries." >&2
    exit 1
fi

corrupt_fixture="$work_dir/corrupt-data.deb"
cp "$fixture" "$corrupt_fixture"
truncate -s -128 "$corrupt_fixture"
if ! dpkg-deb -f "$corrupt_fixture" Maintainer >/dev/null 2>&1; then
    echo "Corrupt fixture no longer has readable control metadata." >&2
    exit 1
fi
if "$verify_script" "$corrupt_fixture" >/dev/null 2>&1; then
    echo "Verifier accepted a DEB with a truncated data archive." >&2
    exit 1
fi

printf 'Linux DEB tooling fixture tests passed.\n'
