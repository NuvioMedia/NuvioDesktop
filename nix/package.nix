# Upstream publishes Linux builds for x86_64 only. The player bridge links
# against system libmpv, WebKitGTK 4.1, GTK 3, and GStreamer.
{
  lib,
  stdenv,
  fetchurl,
  dpkg,
  autoPatchelfHook,
  addDriverRunpath,
  makeWrapper,
  alsa-lib,
  libglvnd,
  libx11,
  libxext,
  libxrender,
  libxtst,
  libxi,
  libxcomposite,
  libxrandr,
  libxcursor,
  libxfixes,
  libxinerama,
  libxkbcommon,
  fontconfig,
  freetype,
  zlib,
  mpv-unwrapped,
  webkitgtk_4_1,
  gtk3,
  glib,
  cairo,
  pango,
  gdk-pixbuf,
  harfbuzz,
  atk,
  gst_all_1,
  glib-networking,
  dconf,
  bubblewrap,
  xdg-dbus-proxy,
  gsettings-desktop-schemas,
  shared-mime-info,
  hicolor-icon-theme,
  adwaita-icon-theme,
  xdg-utils,
  vulkan-loader,
  libpulseaudio,
}:

let
  runtimeLibs = [
    alsa-lib
    libglvnd
    libx11
    libxext
    libxrender
    libxtst
    libxi
    libxcomposite
    libxrandr
    libxcursor
    libxfixes
    libxinerama
    libxkbcommon
    fontconfig
    freetype
    zlib
    mpv-unwrapped
    webkitgtk_4_1
    gtk3
    glib
    cairo
    pango
    gdk-pixbuf
    harfbuzz
    atk
    gst_all_1.gstreamer
    gst_all_1.gst-plugins-base
    gst_all_1.gst-plugins-good
    gst_all_1.gst-plugins-bad
    gst_all_1.gst-plugins-ugly
    gst_all_1.gst-libav
    glib-networking
    vulkan-loader
    libpulseaudio
    stdenv.cc.cc.lib
  ];

  gstPluginPath = lib.concatStringsSep ":" [
    "${gst_all_1.gst-plugins-base}/lib/gstreamer-1.0"
    "${gst_all_1.gst-plugins-good}/lib/gstreamer-1.0"
    "${gst_all_1.gst-plugins-bad}/lib/gstreamer-1.0"
    "${gst_all_1.gst-plugins-ugly}/lib/gstreamer-1.0"
    "${gst_all_1.gst-libav}/lib/gstreamer-1.0"
  ];

  xdgDataDirs = lib.concatStringsSep ":" [
    "${gtk3}/share/gsettings-schemas/${gtk3.name}"
    "${gsettings-desktop-schemas}/share/gsettings-schemas/${gsettings-desktop-schemas.name}"
    "${shared-mime-info}/share"
    "${adwaita-icon-theme}/share"
    "${hicolor-icon-theme}/share"
  ];
in
stdenv.mkDerivation (finalAttrs: {
  pname = "nuvio";
  version = "0.1.33-alpha";

  src = fetchurl {
    url = "https://github.com/NuvioMedia/NuvioDesktop/releases/download/${finalAttrs.version}/Nuvio-Linux-x86_64-${finalAttrs.version}.deb";
    hash = "sha256-FbrG28ax5CkbflPcyVoFR3RVnC+tLTiBCxTPfz+EyvE=";
  };

  dontUnpack = true;

  nativeBuildInputs = [
    dpkg
    autoPatchelfHook
    addDriverRunpath
    makeWrapper
  ];

  buildInputs = runtimeLibs;

  # The bundled JRE ships libfreetype.so without a soname suffix.
  autoPatchelfIgnoreMissingDeps = [ "libfreetype.so" ];

  installPhase = ''
    runHook preInstall

    dpkg-deb -x "$src" unpacked

    mkdir -p "$out/opt" "$out/bin" "$out/share/applications" "$out/share/icons/hicolor/512x512/apps"
    mv unpacked/opt/nuvio "$out/opt/nuvio"

    ln -s "$out/opt/nuvio/lib/Nuvio.png" "$out/share/icons/hicolor/512x512/apps/nuvio.png"
    install -Dm644 unpacked/usr/share/applications/nuvio.desktop "$out/share/applications/nuvio.desktop"
    substituteInPlace "$out/share/applications/nuvio.desktop" \
      --replace-fail "Exec=/opt/nuvio/bin/Nuvio %u" "Exec=$out/bin/nuvio %u" \
      --replace-fail "Icon=/opt/nuvio/lib/Nuvio.png" "Icon=nuvio"

    # jpackage finds its runtime from the real executable path.
    makeWrapper "$out/opt/nuvio/bin/Nuvio" "$out/bin/nuvio" \
      --prefix PATH : ${lib.escapeShellArg (lib.makeBinPath [ bubblewrap xdg-dbus-proxy xdg-utils ])} \
      --prefix LD_LIBRARY_PATH : ${lib.escapeShellArg (lib.makeLibraryPath runtimeLibs)} \
      --prefix XDG_DATA_DIRS : ${lib.escapeShellArg xdgDataDirs} \
      --set GDK_BACKEND x11 \
      --set GST_PLUGIN_SCANNER ${lib.escapeShellArg "${gst_all_1.gstreamer.out}/libexec/gstreamer-1.0/gst-plugin-scanner"} \
      --set GST_PLUGIN_SYSTEM_PATH_1_0 ${lib.escapeShellArg gstPluginPath} \
      --set GIO_EXTRA_MODULES ${lib.escapeShellArg "${glib-networking}/lib/gio/modules:${dconf.lib}/lib/gio/modules"} \
      --set GDK_PIXBUF_MODULE_FILE ${lib.escapeShellArg "${gdk-pixbuf}/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache"} \
      --run ${lib.escapeShellArg ''
        if [ -d /run/opengl-driver/lib ]; then
          export LD_LIBRARY_PATH="/run/opengl-driver/lib''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
          export LIBGL_DRIVERS_PATH="/run/opengl-driver/lib/dri''${LIBGL_DRIVERS_PATH:+:$LIBGL_DRIVERS_PATH}"
        fi
      ''}

    runHook postInstall
  '';

  meta = {
    description = "Desktop media player that turns your own sources into a library";
    homepage = "https://nuvio.tv";
    license = lib.licenses.gpl3Plus;
    platforms = [ "x86_64-linux" ];
    mainProgram = "nuvio";
    sourceProvenance = with lib.sourceTypes; [ binaryNativeCode ];
  };
})
