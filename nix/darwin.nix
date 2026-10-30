# Each architecture uses its upstream DMG. Leave the signed app intact.
{
  lib,
  stdenvNoCC,
  fetchurl,
  undmg,
}:

let
  version = "0.1.33-alpha";
  sources = {
    aarch64-darwin = {
      arch = "arm64";
      hash = "sha256-Lcqyv1y4QnS2tnf/kjSJUWltUmRwuAtZ9tkHfrKYcko=";
    };
    x86_64-darwin = {
      arch = "x86_64";
      hash = "sha256-qJI3yXjvtZMli6OzLDjSqZ4QHlJC/U6Jdedu+v1htuA=";
    };
  };
  source = sources.${stdenvNoCC.hostPlatform.system} or (throw "No Nuvio macOS build for ${stdenvNoCC.hostPlatform.system}");
in
stdenvNoCC.mkDerivation {
  pname = "nuvio";
  inherit version;

  src = fetchurl {
    url = "https://github.com/NuvioMedia/NuvioDesktop/releases/download/${version}/Nuvio-macOS-${source.arch}-${version}.dmg";
    hash = source.hash;
    name = "Nuvio-macOS-${source.arch}-${version}.dmg";
  };

  nativeBuildInputs = [ undmg ];

  sourceRoot = ".";

  dontBuild = true;
  dontFixup = true;

  installPhase = ''
    runHook preInstall

    mkdir -p "$out/Applications" "$out/bin"
    cp -R Nuvio.app "$out/Applications/Nuvio.app"
    ln -s "$out/Applications/Nuvio.app/Contents/MacOS/Nuvio" "$out/bin/nuvio"

    runHook postInstall
  '';

  meta = {
    description = "Desktop media player that turns your own sources into a library";
    homepage = "https://nuvio.tv";
    license = lib.licenses.gpl3Plus;
    platforms = [ "aarch64-darwin" "x86_64-darwin" ];
    mainProgram = "nuvio";
    sourceProvenance = with lib.sourceTypes; [ binaryNativeCode ];
  };
}
