#!/usr/bin/env python3
"""Derive the Steam Frame (aarch64) Flatpak manifest from nuvio-flatpak.yml.

Steam Frame: Snapdragon 8 Gen 3 / Adreno 750 (Mesa Turnip + freedreno), Qualcomm Iris
stateful V4L2 decoder. Iris cannot export DRM-PRIME frames and Turnip has no Vulkan Video,
so copy-back V4L2 M2M is the only hardware decode path (H.264/HEVC/VP9; AV1 stays software).
The GNOME runtime FFmpeg already ships the *_v4l2m2m decoders.

Deriving instead of copying keeps libmpv and dependency bumps in a single manifest.

Usage: steamframe-flatpak-manifest.py <nuvio-flatpak.yml> <output.yml>
"""
import sys

MPV_OPTION_SWAPS = {
    # NVIDIA/x86-only hardware paths.
    "-Dcuda-hwaccel=enabled": "-Dcuda-hwaccel=disabled",
    "-Dcuda-interop=enabled": "-Dcuda-interop=disabled",
    "-Dvdpau=enabled": "-Dvdpau=disabled",
    "-Dvdpau-gl-x11=enabled": "-Dvdpau-gl-x11=disabled",
}
FINISH_ARGS_ANCHOR = "  - --env=XDG_DATA_DIRS=/app/share:/usr/share\n"
# Read by the Linux player bridge (player_bridge.cpp). auto-safe keeps VA-API if a driver exists.
HWDEC_ARG = "  - --env=NUVIO_MPV_HWDEC=v4l2m2m-copy,auto-safe\n"
# Intel/NVIDIA VA-API extensions are not published for aarch64.
VAAPI_EXT_START = "  org.freedesktop.Platform.VAAPI.Intel:\n"
VAAPI_EXT_END = "build-options:"


def derive(manifest: str) -> str:
    for old, new in MPV_OPTION_SWAPS.items():
        if old not in manifest:
            raise SystemExit(f"nuvio-flatpak.yml changed: {old!r} not found")
        manifest = manifest.replace(old, new)

    if FINISH_ARGS_ANCHOR not in manifest:
        raise SystemExit("nuvio-flatpak.yml changed: XDG_DATA_DIRS finish-arg not found")
    manifest = manifest.replace(FINISH_ARGS_ANCHOR, FINISH_ARGS_ANCHOR + HWDEC_ARG)

    start = manifest.find(VAAPI_EXT_START)
    end = manifest.find(VAAPI_EXT_END)
    if start == -1 or end < start:
        raise SystemExit("nuvio-flatpak.yml changed: VA-API extension block not found")
    manifest = manifest[:start] + manifest[end:]

    if "VAAPI" in manifest or "codecs-extra" not in manifest:
        raise SystemExit("derived manifest failed validation")
    return manifest


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as src:
        derived = derive(src.read())
    with open(sys.argv[2], "w", encoding="utf-8") as dst:
        dst.write(derived)
