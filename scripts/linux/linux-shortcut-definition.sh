#!/usr/bin/env bash

readonly NUVIO_LINUX_SHORTCUT_RELATIVE_PATH="usr/share/applications/nuvio.desktop"
readonly NUVIO_LINUX_SHORTCUT_NAME="Nuvio"
readonly NUVIO_LINUX_SHORTCUT_COMMENT="Nuvio Media Player"
readonly NUVIO_LINUX_SHORTCUT_CATEGORIES="AudioVideo;"
readonly NUVIO_LINUX_SHORTCUT_STARTUP_NOTIFY="true"
readonly NUVIO_LINUX_SHORTCUT_STARTUP_WM_CLASS="com-nuvio-app-MainKt"
readonly NUVIO_LINUX_SHORTCUT_MIME_TYPES="x-scheme-handler/nuvio;x-scheme-handler/stremio;"

nuvio_linux_desktop_entry_exists() {
    if [[ $# -ne 1 ]]; then
        return 2
    fi

    local root_dir="$1"
    find "$root_dir" -type f -path "*/${NUVIO_LINUX_SHORTCUT_RELATIVE_PATH}" | grep -q .
}

nuvio_linux_ensure_startup_wm_class() {
    if [[ $# -ne 1 ]]; then
        return 2
    fi

    local desktop_file="$1/$NUVIO_LINUX_SHORTCUT_RELATIVE_PATH"
    [[ -f "$desktop_file" ]] || return 1

    if grep -q '^StartupWMClass=' "$desktop_file"; then
        sed -i "s|^StartupWMClass=.*$|StartupWMClass=${NUVIO_LINUX_SHORTCUT_STARTUP_WM_CLASS}|" "$desktop_file"
    else
        printf 'StartupWMClass=%s\n' "$NUVIO_LINUX_SHORTCUT_STARTUP_WM_CLASS" >> "$desktop_file"
    fi
}

# This apparently gets stripped during repackaging (that's done to patch the deps for deb/rpm) so we need to add the shortcut again.
nuvio_linux_write_desktop_entry() {
    if [[ $# -ne 1 ]]; then
        return 2
    fi

    local root_dir="$1"
    local desktop_file="$root_dir/$NUVIO_LINUX_SHORTCUT_RELATIVE_PATH"
    mkdir -p "$(dirname "$desktop_file")"
    nuvio_linux_write_desktop_entry_file "$desktop_file" "/opt/nuvio/bin/Nuvio %u" "/opt/nuvio/lib/Nuvio.png"
}

nuvio_linux_ensure_uri_handler() {
    if [[ $# -ne 1 ]]; then
        return 2
    fi

    local desktop_file="$1/$NUVIO_LINUX_SHORTCUT_RELATIVE_PATH"
    [[ -f "$desktop_file" ]] || return 1

    local mime_types
    mime_types="$(sed -n 's/^MimeType=//p' "$desktop_file" | head -n 1)"
    if [[ -n "$mime_types" && "$mime_types" != *';' ]]; then
        mime_types+=';'
    fi
    local handler
    local -a handlers
    IFS=';' read -r -a handlers <<< "$NUVIO_LINUX_SHORTCUT_MIME_TYPES"
    for handler in "${handlers[@]}"; do
        [[ -z "$handler" ]] && continue
        if [[ ";$mime_types" != *";$handler;"* ]]; then
            mime_types+="$handler;"
        fi
    done

    if grep -q '^MimeType=' "$desktop_file"; then
        sed -i "s|^MimeType=.*$|MimeType=${mime_types}|" "$desktop_file"
    else
        printf 'MimeType=%s\n' "$mime_types" >> "$desktop_file"
    fi

    if grep -Eq '^Exec=.*%[fF]' "$desktop_file"; then
        sed -i -E 's|^(Exec=.*)%[fF](.*)$|\1%u\2|' "$desktop_file"
    elif ! grep -Eq '^Exec=.*%[uU]' "$desktop_file"; then
        sed -i -E 's|^(Exec=.*)$|\1 %u|' "$desktop_file"
    fi
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
StartupWMClass=__NUVIO_STARTUP_WM_CLASS__
MimeType=__NUVIO_MIME_TYPES__
EOF

    sed -i \
        -e "s|__NUVIO_NAME__|${NUVIO_LINUX_SHORTCUT_NAME}|g" \
        -e "s|__NUVIO_COMMENT__|${NUVIO_LINUX_SHORTCUT_COMMENT}|g" \
        -e "s|__NUVIO_EXEC__|${exec_path}|g" \
        -e "s|__NUVIO_ICON__|${icon_path}|g" \
        -e "s|__NUVIO_CATEGORIES__|${NUVIO_LINUX_SHORTCUT_CATEGORIES}|g" \
        -e "s|__NUVIO_STARTUP_NOTIFY__|${NUVIO_LINUX_SHORTCUT_STARTUP_NOTIFY}|g" \
        -e "s|__NUVIO_STARTUP_WM_CLASS__|${NUVIO_LINUX_SHORTCUT_STARTUP_WM_CLASS}|g" \
        -e "s|__NUVIO_MIME_TYPES__|${NUVIO_LINUX_SHORTCUT_MIME_TYPES}|g" \
        "$desktop_file"
}
