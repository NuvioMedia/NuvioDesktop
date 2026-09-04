#!/usr/bin/env bash

set -euo pipefail

usage() {
    echo "Usage: $0 <path-to-rpm>" >&2
}

if [[ $# -ne 1 ]]; then
    usage
    exit 2
fi

required_tools=(rpm)
for tool in "${required_tools[@]}"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool is required to verify a Linux RPM." >&2
        exit 1
    fi
done

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=linux-rpm-dependencies.sh
source "$script_dir/linux-rpm-dependencies.sh"

rpm_path="$1"
if [[ ! -f "$rpm_path" ]]; then
    echo "RPM not found: $rpm_path" >&2
    exit 1
fi

vendor="$(rpm -qp --qf '%{VENDOR}\n' "$rpm_path")"
if [[ "$vendor" != "Nuvio Media" ]]; then
    echo "Unexpected Vendor: '$vendor'" >&2
    echo "Expected: 'Nuvio Media'" >&2
    exit 1
fi

requirements="$(rpm -qp --requires "$rpm_path")"
for dependency in "${NUVIO_LINUX_RPM_RUNTIME_DEPENDENCIES[@]}"; do
    if ! rpm_requirements_has_package "$requirements" "$dependency"; then
        echo "Missing runtime dependency: $dependency" >&2
        echo "Requires:" >&2
        echo "$requirements" >&2
        exit 1
    fi
done

if ! rpm -qlp "$rpm_path" | grep -Eq '^/usr/share/applications/.+\.desktop$'; then
    echo "RPM does not install a desktop launcher under /usr/share/applications." >&2
    rpm -qlp "$rpm_path" | grep -E '\.desktop$|/opt/' >&2 || true
    exit 1
fi

ownership_lines="$(rpm -qlvp "$rpm_path")"
while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    if [[ ! "$line" =~ ^[drwxsl\-] ]]; then
        continue
    fi

    owner_group="$(awk '{print $3":"$4}' <<< "$line")"
    if [[ "$owner_group" != "root:root" ]]; then
        echo "Unexpected package ownership: $owner_group" >&2
        echo "Offending entry: $line" >&2
        echo "All RPM entries must be owned by root:root." >&2
        exit 1
    fi
done <<< "$ownership_lines"

rpm -qp --info "$rpm_path" >/dev/null

printf 'Verified Linux RPM metadata: %s\n' "$rpm_path"
