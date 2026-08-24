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
    echo "dpkg-deb is required to verify a Linux DEB." >&2
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

expected_maintainer="Nuvio Media <contact@nuvio.tv>"
maintainer="$(dpkg-deb -f "$deb" Maintainer)"
if [[ "$maintainer" != "$expected_maintainer" ]]; then
    echo "Unexpected Maintainer: '$maintainer'" >&2
    echo "Expected: '$expected_maintainer'" >&2
    exit 1
fi

depends="$(dpkg-deb -f "$deb" Depends)"
for dependency in "${NUVIO_LINUX_DEB_RUNTIME_DEPENDENCIES[@]}"; do
    if ! deb_relationship_has_package "$depends" "$dependency"; then
        echo "Missing runtime dependency: $dependency" >&2
        echo "Depends: $depends" >&2
        exit 1
    fi
done

contents_file="$(mktemp "${TMPDIR:-/tmp}/nuvio-deb-contents.XXXXXX")"
cleanup() {
    rm -f "$contents_file"
}
trap cleanup EXIT

if ! dpkg-deb --contents "$deb" > "$contents_file"; then
    echo "Unable to read DEB data archive: $deb" >&2
    exit 1
fi

non_root_entry=""
while read -r _mode owner_group _size _date _time path; do
    if [[ "$owner_group" != "root/root" && -z "$non_root_entry" ]]; then
        non_root_entry="$owner_group $path"
    fi
done < "$contents_file"
if [[ -n "$non_root_entry" ]]; then
    echo "Unexpected package ownership: $non_root_entry" >&2
    echo "All DEB entries must be owned by root/root." >&2
    exit 1
fi

dpkg-deb --info "$deb" >/dev/null

printf 'Verified Linux DEB metadata: %s\n' "$deb"
