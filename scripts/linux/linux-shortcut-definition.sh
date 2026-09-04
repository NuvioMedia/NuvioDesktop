#!/usr/bin/env bash

readonly NUVIO_LINUX_SHORTCUT_RELATIVE_PATH="usr/share/applications/nuvio.desktop"
readonly NUVIO_LINUX_SHORTCUT_NAME="Nuvio"
readonly NUVIO_LINUX_SHORTCUT_COMMENT="Nuvio Media Player"
readonly NUVIO_LINUX_SHORTCUT_CATEGORIES="AudioVideo;"
readonly NUVIO_LINUX_SHORTCUT_STARTUP_NOTIFY="true"

nuvio_linux_desktop_entry_exists() {
    if [[ $# -ne 1 ]]; then
        return 2
    fi

    local root_dir="$1"
    find "$root_dir" -type f -path "*/${NUVIO_LINUX_SHORTCUT_RELATIVE_PATH}" | grep -q .
}

# This apparently gets stripped during repackaging (that's done to patch the deps for deb/rpm) so we need to add the shortcut again.
nuvio_linux_write_desktop_entry() {
    if [[ $# -ne 1 ]]; then
        return 2
    fi

    local root_dir="$1"
    local desktop_file="$root_dir/$NUVIO_LINUX_SHORTCUT_RELATIVE_PATH"
    mkdir -p "$(dirname "$desktop_file")"
    nuvio_linux_write_desktop_entry_file "$desktop_file" "/opt/nuvio/bin/Nuvio" "/opt/nuvio/lib/Nuvio.png"
}

nuvio_linux_write_desktop_entry_file() {
    if [[ $# -ne 3 ]]; then
        return 2
    fi

    local desktop_file="$1"
    local exec_path="$2"
    local icon_path="$3"

    cat > "$desktop_file" <<'EOF'
[Desktop Entry]
Type=Application
Name=__NUVIO_NAME__
Comment=__NUVIO_COMMENT__
Exec=__NUVIO_EXEC__
Icon=__NUVIO_ICON__
Terminal=false
Categories=__NUVIO_CATEGORIES__
StartupNotify=__NUVIO_STARTUP_NOTIFY__
EOF

    sed -i \
        -e "s|__NUVIO_NAME__|${NUVIO_LINUX_SHORTCUT_NAME}|g" \
        -e "s|__NUVIO_COMMENT__|${NUVIO_LINUX_SHORTCUT_COMMENT}|g" \
        -e "s|__NUVIO_EXEC__|${exec_path}|g" \
        -e "s|__NUVIO_ICON__|${icon_path}|g" \
        -e "s|__NUVIO_CATEGORIES__|${NUVIO_LINUX_SHORTCUT_CATEGORIES}|g" \
        -e "s|__NUVIO_STARTUP_NOTIFY__|${NUVIO_LINUX_SHORTCUT_STARTUP_NOTIFY}|g" \
        "$desktop_file"
}