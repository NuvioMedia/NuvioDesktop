#!/usr/bin/env bash

# Shared Linux RPM dependency definitions and requirement parsing helpers.

readonly -a NUVIO_LINUX_RPM_RUNTIME_DEPENDENCIES=(
    mpv-libs
    webkit2gtk4.1
    libXcomposite
    libXext
    gstreamer1-plugins-good
    gstreamer1-plugin-libav
    glib-networking
)

rpm_requirement_package_name() {
    if [[ $# -ne 1 ]]; then
        return 2
    fi

    local requirement="${1//$'\n'/ }"
    requirement="${requirement#${requirement%%[![:space:]]*}}"
    requirement="${requirement%%[[:space:]]*}"
    requirement="${requirement%%(*}"
    requirement="${requirement%%\[*}"

    if [[ -z "$requirement" ]]; then
        return 1
    fi

    printf '%s\n' "$requirement"
}

rpm_requirements_has_package() {
    if [[ $# -ne 2 ]]; then
        return 2
    fi

    local requirements_text="$1"
    local required_package="$2"
    local requirement package_name

    while IFS= read -r requirement || [[ -n "$requirement" ]]; do
        package_name="$(rpm_requirement_package_name "$requirement")" || continue
        if [[ "$package_name" == "$required_package" ]]; then
            return 0
        fi
    done <<< "$requirements_text"

    return 1
}
