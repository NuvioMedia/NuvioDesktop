# Linux playback compiles against host libmpv, WebKitGTK 4.1, GTK 3, and X11.
# CI uses JDK 17. Android and iOS still need their platform SDKs.
{ pkgs ? import <nixpkgs> { } }:

let
  lib = pkgs.lib;
  isLinux = pkgs.stdenv.isLinux;
  isDarwin = pkgs.stdenv.isDarwin;

  # nixpkgs renamed xorg.libX11 to libx11.
  pick = candidates:
    let
      found = builtins.filter (path: lib.hasAttrByPath path pkgs) candidates;
    in
    if found == [ ] then
      throw "Nuvio shell: nixpkgs is missing ${lib.concatMapStringsSep " or " (path: lib.concatStringsSep "." path) candidates}"
    else
      lib.getAttrFromPath (builtins.head found) pkgs;

  jdk = pkgs.jdk17;
  jdkHome = jdk.home or "${jdk}/lib/openjdk";
  gradle = if pkgs ? gradle_9 then pkgs.gradle_9 else null;

  linux = lib.optionalAttrs isLinux {
    gtk3 = pick [ [ "gtk3" ] ];
    webkitgtk = pick [ [ "webkitgtk_4_1" ] ];
    mpv = pick [ [ "mpv-unwrapped" ] ];
    gstreamer = pick [ [ "gst_all_1" "gstreamer" ] ];
    gstBase = pick [ [ "gst_all_1" "gst-plugins-base" ] ];
    gstGood = pick [ [ "gst_all_1" "gst-plugins-good" ] ];
    gstBad = pick [ [ "gst_all_1" "gst-plugins-bad" ] ];
    gstUgly = pick [ [ "gst_all_1" "gst-plugins-ugly" ] ];
    gstLibav = pick [ [ "gst_all_1" "gst-libav" ] ];
    glib = pick [ [ "glib" ] ];
    glib-networking = pick [ [ "glib-networking" ] ];
    dconf = pick [ [ "dconf" ] ];
    gdk-pixbuf = pick [ [ "gdk-pixbuf" ] ];
    gsettings-desktop-schemas = pick [ [ "gsettings-desktop-schemas" ] ];
    shared-mime-info = pick [ [ "shared-mime-info" ] ];
    adwaita-icon-theme = pick [ [ "adwaita-icon-theme" ] ];
    hicolor-icon-theme = pick [ [ "hicolor-icon-theme" ] ];
    libx11 = pick [ [ "libx11" ] [ "xorg" "libX11" ] ];
    libxext = pick [ [ "libxext" ] [ "xorg" "libXext" ] ];
    libxrender = pick [ [ "libxrender" ] [ "xorg" "libXrender" ] ];
    libxtst = pick [ [ "libxtst" ] [ "xorg" "libXtst" ] ];
    libxi = pick [ [ "libxi" ] [ "xorg" "libXi" ] ];
    libxrandr = pick [ [ "libxrandr" ] [ "xorg" "libXrandr" ] ];
    libxcursor = pick [ [ "libxcursor" ] [ "xorg" "libXcursor" ] ];
    libxfixes = pick [ [ "libxfixes" ] [ "xorg" "libXfixes" ] ];
    libxcomposite = pick [ [ "libxcomposite" ] [ "xorg" "libXcomposite" ] ];
    libxinerama = pick [ [ "libxinerama" ] [ "xorg" "libXinerama" ] ];
    libxscrnsaver = pick [ [ "libxscrnsaver" ] [ "xorg" "libXScrnSaver" ] ];
    libxkbcommon = pick [ [ "libxkbcommon" ] ];
    xauth = pick [ [ "xauth" ] [ "xorg" "xauth" ] ];
    xvfb = pick [ [ "xvfb" ] [ "xorg" "xvfb" ] ];
    fontconfig = pick [ [ "fontconfig" ] ];
    freetype = pick [ [ "freetype" ] ];
    alsa-lib = pick [ [ "alsa-lib" ] ];
    libpulseaudio = pick [ [ "libpulseaudio" ] ];
    pipewire = pick [ [ "pipewire" ] ];
    libglvnd = pick [ [ "libglvnd" ] ];
    vulkan-loader = pick [ [ "vulkan-loader" ] ];
    zlib = pick [ [ "zlib" ] ];
    bubblewrap = pick [ [ "bubblewrap" ] ];
    xdg-dbus-proxy = pick [ [ "xdg-dbus-proxy" ] ];
    dpkg = pick [ [ "dpkg" ] ];
    rpm = pick [ [ "rpm" ] ];
    cpio = pick [ [ "cpio" ] ];
    fakeroot = pick [ [ "fakeroot" ] ];
    patchelf = pick [ [ "patchelf" ] ];
    desktop-file-utils = pick [ [ "desktop-file-utils" ] ];
    dejavu_fonts = pick [ [ "dejavu_fonts" ] ];
  };

  # gstreamer's default output is bin, so headers and .pc files need .dev.
  linuxDevPackages = lib.optionals isLinux [
    linux.gtk3.dev
    linux.webkitgtk.dev
    linux.mpv.dev
    linux.gstreamer.dev
    linux.gstBase.dev
    linux.gstGood.dev or linux.gstGood
    linux.gstBad.dev or linux.gstBad
    linux.gstUgly.dev or linux.gstUgly
    linux.gstLibav.dev or linux.gstLibav
    linux.glib.dev
    linux.libx11.dev
    linux.libxext.dev
    linux.libxrender.dev
    linux.libxtst.dev
    linux.libxi.dev
    linux.libxrandr.dev
    linux.libxcursor.dev
    linux.libxfixes.dev
    linux.libxcomposite.dev
    linux.libxinerama.dev
    linux.libxscrnsaver.dev
    linux.libxkbcommon.dev or linux.libxkbcommon
    linux.fontconfig.dev
    linux.freetype.dev
    linux.alsa-lib.dev
    linux.libpulseaudio.dev or linux.libpulseaudio
    linux.gdk-pixbuf.dev or linux.gdk-pixbuf
  ];

  linuxRuntimePackages = lib.optionals isLinux [
    linux.gtk3
    linux.webkitgtk
    linux.mpv
    linux.gstreamer.out
    linux.gstBase
    linux.gstGood
    linux.gstBad
    linux.gstUgly
    linux.gstLibav
    linux.glib.out
    linux.glib-networking
    linux.dconf.lib or linux.dconf
    linux.gdk-pixbuf
    linux.libx11
    linux.libxext
    linux.libxrender
    linux.libxtst
    linux.libxi
    linux.libxrandr
    linux.libxcursor
    linux.libxfixes
    linux.libxcomposite
    linux.libxinerama
    linux.libxscrnsaver
    linux.libxkbcommon
    linux.fontconfig
    linux.freetype
    linux.alsa-lib
    linux.libpulseaudio
    linux.pipewire
    linux.libglvnd
    linux.vulkan-loader
    linux.zlib
    pkgs.stdenv.cc.cc.lib
  ];

  gstPluginPath = lib.optionalString isLinux (lib.concatStringsSep ":" [
    "${linux.gstBase}/lib/gstreamer-1.0"
    "${linux.gstGood}/lib/gstreamer-1.0"
    "${linux.gstBad}/lib/gstreamer-1.0"
    "${linux.gstUgly}/lib/gstreamer-1.0"
    "${linux.gstLibav}/lib/gstreamer-1.0"
  ]);

  pkgConfigPackages = linuxDevPackages ++ linuxRuntimePackages;
  pkgConfigPath = lib.optionalString isLinux (lib.concatStringsSep ":" [
    (lib.makeSearchPathOutput "dev" "lib/pkgconfig" pkgConfigPackages)
    (lib.makeSearchPathOutput "dev" "share/pkgconfig" pkgConfigPackages)
    (lib.makeSearchPathOutput "out" "lib/pkgconfig" pkgConfigPackages)
  ]);

  gioModules = lib.optionalString isLinux (lib.concatStringsSep ":" [
    "${linux.glib-networking}/lib/gio/modules"
    "${linux.dconf.lib or linux.dconf}/lib/gio/modules"
  ]);

  xdgDataDirs = lib.optionalString isLinux (lib.concatStringsSep ":" [
    "${linux.gtk3}/share/gsettings-schemas/${linux.gtk3.name}"
    "${linux.gsettings-desktop-schemas}/share/gsettings-schemas/${linux.gsettings-desktop-schemas.name}"
    "${linux.shared-mime-info}/share"
    "${linux.adwaita-icon-theme}/share"
    "${linux.hicolor-icon-theme}/share"
    "${linux.dejavu_fonts}/share"
  ]);
in
pkgs.mkShell {
  packages = [
    jdk
    pkgs.git
    pkgs.git-lfs
    pkgs.python3
    pkgs.pkg-config
    pkgs.cmake
    pkgs.gnumake
    pkgs.unzip
    pkgs.zip
    pkgs.curl
    pkgs.cacert
    pkgs.which
    pkgs.file
  ]
  ++ lib.optional (gradle != null) gradle
  ++ lib.optionals isLinux [
    linux.bubblewrap
    linux.xdg-dbus-proxy
    linux.xauth
    linux.xvfb
    (pkgs."xvfb-run" or linux.xvfb)
    linux.dpkg
    linux.rpm
    linux.cpio
    linux.fakeroot
    linux.patchelf
    linux.desktop-file-utils
    linux.dejavu_fonts
  ]
  ++ linuxDevPackages
  ++ linuxRuntimePackages;

  shellHook = ''
    export JAVA_HOME=${lib.escapeShellArg jdkHome}
    export PATH="$JAVA_HOME/bin:$PATH"
    export SSL_CERT_FILE=${lib.escapeShellArg "${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt"}
    export NIX_SSL_CERT_FILE="$SSL_CERT_FILE"
    export GIT_SSL_CAINFO="$SSL_CERT_FILE"
  '' + lib.optionalString (pkgs.stdenv.hostPlatform.isLinux && pkgs ? glibcLocales) ''
    export LOCALE_ARCHIVE=${lib.escapeShellArg "${pkgs.glibcLocales}/lib/locale/locale-archive"}
  '' + lib.optionalString isLinux ''
    export PKG_CONFIG_PATH=${lib.escapeShellArg pkgConfigPath}''${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}
    export CMAKE_PREFIX_PATH=${lib.escapeShellArg (lib.makeSearchPathOutput "dev" "" pkgConfigPackages)}''${CMAKE_PREFIX_PATH:+:$CMAKE_PREFIX_PATH}
    export GST_PLUGIN_SCANNER=${lib.escapeShellArg "${linux.gstreamer.out}/libexec/gstreamer-1.0/gst-plugin-scanner"}
    export GST_PLUGIN_SYSTEM_PATH_1_0=${lib.escapeShellArg gstPluginPath}
    export GIO_EXTRA_MODULES=${lib.escapeShellArg gioModules}
    export GDK_PIXBUF_MODULE_FILE=${lib.escapeShellArg "${linux.gdk-pixbuf}/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache"}
    export XDG_DATA_DIRS=${lib.escapeShellArg xdgDataDirs}''${XDG_DATA_DIRS:+:$XDG_DATA_DIRS}
    # The player bridge embeds mpv into an X11 window id.
    export GDK_BACKEND=''${GDK_BACKEND:-x11}

    nix_libs=${lib.escapeShellArg (lib.makeLibraryPath linuxRuntimePackages)}
    gl_prefix=""
    if [ -d /run/opengl-driver/lib ]; then
      gl_prefix="/run/opengl-driver/lib:"
      export LIBGL_DRIVERS_PATH="/run/opengl-driver/lib/dri''${LIBGL_DRIVERS_PATH:+:$LIBGL_DRIVERS_PATH}"
    fi
    export LD_LIBRARY_PATH="''${gl_prefix}''${nix_libs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

    missing=()
    for module in mpv webkit2gtk-4.1 gtk+-3.0 x11 xcomposite xext gstreamer-1.0 gstreamer-app-1.0 gstreamer-video-1.0; do
      if ! pkg-config --exists "$module"; then
        missing+=("$module")
      fi
    done
    if [ "''${#missing[@]}" -gt 0 ]; then
      echo "warning: pkg-config cannot see: ''${missing[*]}" >&2
    fi
    echo "Nuvio Linux shell ready (JDK 17). Run: ./gradlew :composeApp:run"
    echo "If Gradle was started outside this shell, run ./gradlew --stop first."
  '' + lib.optionalString isDarwin ''
    echo "Nuvio macOS shell ready (JDK 17). The player bridge still needs Xcode and the bundled libmpv runtime."
    echo "Run: ./gradlew :composeApp:run"
  '';
}
