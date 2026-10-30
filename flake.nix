{
  description = "Nuvio Desktop";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  # nixpkgs unstable dropped x86_64-darwin in 26.11.
  inputs.nixpkgs-x86_64-darwin.url = "github:NixOS/nixpkgs/nixpkgs-26.05-darwin";

  outputs = { self, nixpkgs, nixpkgs-x86_64-darwin }:
    let
      # Linux arm64 is not published. Darwin uses the matching DMG.
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forEachSystem = nixpkgs.lib.genAttrs systems;
      pkgsFor = system:
        if system == "x86_64-darwin" then
          import nixpkgs-x86_64-darwin { inherit system; }
        else
          import nixpkgs { inherit system; };
    in
    {
      packages = forEachSystem (system:
        let
          pkgs = pkgsFor system;
          nuvio =
            if system == "x86_64-linux" then
              pkgs.callPackage ./nix/package.nix { }
            else if pkgs.stdenv.hostPlatform.isDarwin then
              pkgs.callPackage ./nix/darwin.nix { }
            else
              null;
        in
        nixpkgs.lib.optionalAttrs (nuvio != null) {
          inherit nuvio;
          default = nuvio;
        }
      );

      apps = forEachSystem (system:
        nixpkgs.lib.optionalAttrs (self.packages.${system} ? default) {
          default = {
            type = "app";
            program = "${self.packages.${system}.default}/bin/nuvio";
          };
          nuvio = self.apps.${system}.default;
        }
      );

      devShells = forEachSystem (system: {
        default = import ./shell.nix { pkgs = pkgsFor system; };
      });
    };
}
