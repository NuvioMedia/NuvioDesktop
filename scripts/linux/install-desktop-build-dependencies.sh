#!/usr/bin/env bash

set -euo pipefail

sudo apt-get update

base_packages=(
    build-essential
    cmake
    dpkg-dev
    libgstreamer-plugins-base1.0-dev
    libgstreamer1.0-dev
    libgtk-3-dev
    libmpv-dev
    libwebkit2gtk-4.1-dev
    libx11-dev
    libxcomposite-dev
    libxext-dev
    pkg-config
    xauth
    xvfb
    patchelf
)

sudo apt-get install --no-install-recommends --yes "${base_packages[@]}" "$@"
