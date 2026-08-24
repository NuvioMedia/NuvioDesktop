#!/usr/bin/env bash

# Shared Linux DEB dependency definitions and relationship parsing.

readonly -a NUVIO_LINUX_DEB_RUNTIME_DEPENDENCIES=(
    libmpv2
    libwebkit2gtk-4.1-0
    libxcomposite1
    libxext6
    gstreamer1.0-plugins-good
    gstreamer1.0-libav
    glib-networking
)

deb_relationship_package_count() {
    if [[ $# -ne 2 ]]; then
        return 2
    fi

    local relationship="${1//$'\n'/ }"
    local required="$2"
    local group alternative candidate
    local count=0
    local -a groups alternatives

    IFS=',' read -r -a groups <<< "$relationship"
    for group in "${groups[@]}"; do
        IFS='|' read -r -a alternatives <<< "$group"
        for alternative in "${alternatives[@]}"; do
            candidate="${alternative#"${alternative%%[![:space:]]*}"}"
            if [[ "$candidate" =~ ^([a-z0-9][a-z0-9+.-]*)(:[a-zA-Z0-9-]+)?($|[[:space:]]|\() ]] &&
                [[ "${BASH_REMATCH[1]}" == "$required" ]]; then
                ((count += 1))
            fi
        done
    done

    printf '%s\n' "$count"
}

deb_relationship_has_package() {
    local count
    count="$(deb_relationship_package_count "$@")" || return
    ((count > 0))
}
