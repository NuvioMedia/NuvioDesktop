#!/usr/bin/env bash

set -euo pipefail

usage() {
    echo "Usage: $0 <path-to-deb>" >&2
}

if [[ $# -ne 1 ]]; then
    usage
    exit 2
fi

if ! command -v dpkg-deb >/dev/null 2>&1; then
    echo "dpkg-deb is required to post-process a Linux DEB." >&2
    exit 1
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=linux-deb-dependencies.sh
source "$script_dir/linux-deb-dependencies.sh"

deb="$1"
if [[ ! -f "$deb" ]]; then
    echo "DEB not found: $deb" >&2
    exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-deb.XXXXXX")"
rebuilt_deb="${deb}.rebuilt"
cleanup() {
    rm -rf "$work_dir"
    rm -f "$rebuilt_deb"
}
trap cleanup EXIT

dpkg-deb -R "$deb" "$work_dir"
control="$work_dir/DEBIAN/control"
if [[ ! -f "$control" ]]; then
    echo "Missing DEBIAN/control in: $deb" >&2
    exit 1
fi

depends="$(dpkg-deb -f "$deb" Depends)"
for dependency in "${NUVIO_LINUX_DEB_RUNTIME_DEPENDENCIES[@]}"; do
    if ! deb_relationship_has_package "$depends" "$dependency"; then
        if [[ -n "$depends" ]]; then
            depends+=", "
        fi
        depends+="$dependency"
    fi
done

depends="${depends//$'\n'/ }"
control_tmp="${control}.tmp"
depends_replaced=false
inside_depends=false
while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == Depends:* ]]; then
        if [[ "$depends_replaced" == false ]]; then
            printf 'Depends: %s\n' "$depends" >> "$control_tmp"
            depends_replaced=true
        fi
        inside_depends=true
    elif [[ "$inside_depends" == true && "$line" =~ ^[[:space:]] ]]; then
        continue
    else
        inside_depends=false
        printf '%s\n' "$line" >> "$control_tmp"
    fi
done < "$control"

if [[ "$depends_replaced" == false ]]; then
    printf 'Depends: %s\n' "$depends" >> "$control_tmp"
fi
chmod --reference="$control" "$control_tmp"
mv "$control_tmp" "$control"

dpkg-deb --root-owner-group -b "$work_dir" "$rebuilt_deb" >/dev/null
mv "$rebuilt_deb" "$deb"

printf 'Patched Linux DEB runtime dependencies: %s\n' "$deb"
