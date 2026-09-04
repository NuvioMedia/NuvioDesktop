#!/usr/bin/env bash

set -euo pipefail

usage() {
    echo "Usage: $0 <path-to-rpm>" >&2
}

if [[ $# -ne 1 ]]; then
    usage
    exit 2
fi

require_tool() {
    local tool="$1"
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "$tool is required to post-process a Linux RPM." >&2
        exit 1
    fi
}

# We only need rpm to inspect whether patching is necessary.
require_tool rpm

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=linux-rpm-dependencies.sh
source "$script_dir/linux-rpm-dependencies.sh"
# shellcheck source=linux-shortcut-definition.sh
source "$script_dir/linux-shortcut-definition.sh"

rpm_path="$1"
if [[ ! -f "$rpm_path" ]]; then
    echo "RPM not found: $rpm_path" >&2
    exit 1
fi

existing_requirements="$(rpm -qp --requires "$rpm_path")"
missing_dependencies=()
for dependency in "${NUVIO_LINUX_RPM_RUNTIME_DEPENDENCIES[@]}"; do
    if ! rpm_requirements_has_package "$existing_requirements" "$dependency"; then
        missing_dependencies+=("$dependency")
    fi
done

if (( ${#missing_dependencies[@]} == 0 )); then
    printf 'RPM runtime dependencies already include required entries: %s\n' "$rpm_path"
    exit 0
fi

# Full toolchain is only required when we actually need to rebuild.
for tool in rpm2cpio cpio rpmbuild fakeroot tar find sort awk sed; do
    require_tool "$tool"
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-rpm.XXXXXX")"
rebuilt_rpm="$work_dir/rebuilt.rpm"
cleanup() {
    rm -rf "$work_dir"
}
trap cleanup EXIT

payload_dir="$work_dir/payload"
rpmbuild_dir="$work_dir/rpmbuild"
mkdir -p "$payload_dir" "$rpmbuild_dir/BUILD" "$rpmbuild_dir/RPMS" "$rpmbuild_dir/SOURCES" "$rpmbuild_dir/SPECS" "$rpmbuild_dir/SRPMS"

rpm2cpio "$rpm_path" | (cd "$payload_dir" && cpio -idm --quiet)

# Ensure a launcher is present for desktop integration. Some jpackage/rpm
# combinations may omit it; in that case, synthesize a minimal entry.
if ! nuvio_linux_desktop_entry_exists "$payload_dir"; then
    nuvio_linux_write_desktop_entry "$payload_dir"
fi

name="$(rpm -qp --qf '%{NAME}\n' "$rpm_path")"
version="$(rpm -qp --qf '%{VERSION}\n' "$rpm_path")"
release="$(rpm -qp --qf '%{RELEASE}\n' "$rpm_path")"
arch="$(rpm -qp --qf '%{ARCH}\n' "$rpm_path")"
summary="$(rpm -qp --qf '%{SUMMARY}\n' "$rpm_path")"
license="$(rpm -qp --qf '%{LICENSE}\n' "$rpm_path")"
vendor="$(rpm -qp --qf '%{VENDOR}\n' "$rpm_path")"
url="$(rpm -qp --qf '%{URL}\n' "$rpm_path")"
description="$(rpm -qp --qf '%{DESCRIPTION}\n' "$rpm_path")"

if [[ "$license" == "(none)" || -z "$license" ]]; then
    license="Proprietary"
fi
if [[ "$vendor" == "(none)" || -z "$vendor" ]]; then
    vendor="Nuvio Media"
fi
if [[ "$summary" == "(none)" || -z "$summary" ]]; then
    summary="Nuvio"
fi
if [[ "$url" == "(none)" ]]; then
    url=""
fi
if [[ -z "$description" || "$description" == "(none)" ]]; then
    description="$summary"
fi

payload_tar="$rpmbuild_dir/SOURCES/payload.tar.gz"
file_list="$rpmbuild_dir/SOURCES/filelist.txt"
spec_path="$rpmbuild_dir/SPECS/${name}.spec"

tar -C "$payload_dir" -czf "$payload_tar" .

{
    echo "%defattr(-,root,root,-)"
    find "$payload_dir" -mindepth 1 -printf '%P\n' | LC_ALL=C sort | while IFS= read -r relative_path; do
        [[ -z "$relative_path" ]] && continue
        if [[ -d "$payload_dir/$relative_path" ]]; then
            printf '%%dir /%s\n' "$relative_path"
        else
            printf '/%s\n' "$relative_path"
        fi
    done
} > "$file_list"

{
    printf '%%global debug_package %%{nil}\n'
    printf '%%global __debug_package 0\n'
    printf '%%global _debugsource_packages 0\n'
    printf '%%global __debug_install_post %%{nil}\n'
    printf '\n'
    printf 'Name: %s\n' "$name"
    printf 'Version: %s\n' "$version"
    printf 'Release: %s\n' "$release"
    printf 'Summary: %s\n' "$summary"
    printf 'License: %s\n' "$license"
    printf 'Vendor: %s\n' "$vendor"
    if [[ -n "$url" ]]; then
        printf 'URL: %s\n' "$url"
    fi
    printf 'BuildArch: %s\n' "$arch"
    printf 'Source0: payload.tar.gz\n'
    printf 'Source1: filelist.txt\n'
    printf 'AutoReqProv: yes\n'
    printf '\n'
    for dependency in "${NUVIO_LINUX_RPM_RUNTIME_DEPENDENCIES[@]}"; do
        printf 'Requires: %s\n' "$dependency"
    done
    printf '\n%%description\n%s\n' "$description"
    printf '\n%%prep\n%%setup -q -c -T\n'
    printf 'tar -xzf %%{SOURCE0}\n'
    printf '\n%%build\n:\n'
    printf '\n%%install\n'
    printf 'rm -rf %%{buildroot}\n'
    printf 'mkdir -p %%{buildroot}\n'
    printf 'cp -a . %%{buildroot}/\n'
    printf '\n%%files -f %%{SOURCE1}\n'
} > "$spec_path"

fakeroot rpmbuild \
    --define "_topdir $rpmbuild_dir" \
    --define "_build_id_links none" \
    -bb "$spec_path"

rebuilt_candidates=("$rpmbuild_dir/RPMS/$arch"/*.rpm)
if (( ${#rebuilt_candidates[@]} != 1 )) || [[ ! -f "${rebuilt_candidates[0]}" ]]; then
    echo "Expected exactly one rebuilt RPM under $rpmbuild_dir/RPMS/$arch." >&2
    ls -la "$rpmbuild_dir/RPMS/$arch" >&2 || true
    exit 1
fi

cp "${rebuilt_candidates[0]}" "$rebuilt_rpm"
mv "$rebuilt_rpm" "$rpm_path"

printf 'Patched Linux RPM runtime dependencies: %s\n' "$rpm_path"
