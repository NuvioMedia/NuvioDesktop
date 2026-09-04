#!/usr/bin/env bash

set -euo pipefail

usage() {
    echo "Usage: $0 <app-root> <output-appimage> <appimagetool-path> [--update-information <value>] [--website-url <value>]" >&2
}

if [[ $# -lt 3 ]]; then
    usage
    exit 2
fi

app_root="$1"
output_appimage="$2"
appimagetool_path="$3"
shift 3

update_information="${APPIMAGE_UPDATE_INFORMATION:-${UPDATE_INFORMATION:-}}"
website_url="${APPIMAGE_WEBSITE_URL:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --update-information)
            [[ $# -ge 2 ]] || {
                echo "Missing value for --update-information" >&2
                exit 2
            }
            update_information="$2"
            shift 2
            ;;
        --website-url)
            [[ $# -ge 2 ]] || {
                echo "Missing value for --website-url" >&2
                exit 2
            }
            website_url="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage
            exit 2
            ;;
    esac
done

if [[ ! -d "$app_root" ]]; then
    echo "App root not found: $app_root" >&2
    exit 1
fi

if [[ ! -x "$appimagetool_path" ]]; then
    echo "appimagetool is not executable: $appimagetool_path" >&2
    exit 1
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=linux-shortcut-definition.sh
source "$script_dir/linux-shortcut-definition.sh"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-appimage.XXXXXX")"
cleanup() {
    rm -rf "$work_dir"
}
trap cleanup EXIT

app_dir="$work_dir/Nuvio.AppDir"
mkdir -p "$app_dir"
cp -a "$app_root"/. "$app_dir/"

desktop_file="$app_dir/${NUVIO_LINUX_SHORTCUT_NAME}.desktop"
nuvio_linux_write_desktop_entry_file "$desktop_file" "AppRun" "$NUVIO_LINUX_SHORTCUT_NAME"
if [[ -n "$website_url" ]]; then
    printf 'X-AppImage-Website=%s\n' "$website_url" >> "$desktop_file"
fi
if [[ -n "$update_information" ]]; then
    printf 'X-AppImage-UpdateInformation=%s\n' "$update_information" >> "$desktop_file"
fi

icon_source="$app_dir/lib/Nuvio.png"
if [[ ! -f "$icon_source" ]]; then
    echo "Expected AppImage icon at $icon_source" >&2
    exit 1
fi
cp "$icon_source" "$app_dir/${NUVIO_LINUX_SHORTCUT_NAME}.png"
ln -sf "${NUVIO_LINUX_SHORTCUT_NAME}.png" "$app_dir/.DirIcon"

cat > "$app_dir/AppRun" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
executable_path="$here/bin/Nuvio"
if [[ ! -x "$executable_path" ]]; then
    executable_path="$here/bin/nuvio"
fi
exec "$executable_path" "$@"
EOF
chmod +x "$app_dir/AppRun"

mkdir -p "$(dirname "$output_appimage")"
appimagetool_args=("$app_dir" "$output_appimage")
if [[ -n "$update_information" ]]; then
    appimagetool_args=("-u" "$update_information" "${appimagetool_args[@]}")
fi

ARCH=x86_64 "$appimagetool_path" --appimage-extract-and-run "${appimagetool_args[@]}"

if [[ ! -f "$output_appimage" ]]; then
    echo "Expected AppImage output was not produced: $output_appimage" >&2
    exit 1
fi

chmod +x "$output_appimage"
printf 'Built AppImage artifact: %s\n' "$output_appimage"