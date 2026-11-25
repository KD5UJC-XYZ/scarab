# SPDX-License-Identifier: Apache-2.0
{
  perSystem = {
    lib,
    pkgs,
    scalaToolchain,
    ...
  }: let
    networks = {
      preview = "7071";
      preprod = "7072";
      mainnet = "7073";
    };
  in {
    apps =
      lib.mapAttrs (network: port: let
        launcher = pkgs.writeShellApplication {
          name = "scarab-${network}";
          runtimeInputs = [
            scalaToolchain.jdk
            pkgs.coreutils
            pkgs.curl
            pkgs.git
            pkgs.jq
            pkgs.util-linux
          ];
          runtimeEnv = {
            SCARAB_WRAPPER_NETWORK = network;
            SCARAB_WRAPPER_QUERY_PORT = port;
            JAVA_HOME = "${scalaToolchain.jdk}";
          };
          text = builtins.readFile ../scripts/nix-network;
        };
      in {
        type = "app";
        program = lib.getExe launcher;
        meta.description = "Run Scarab on ${network} with isolated network state";
      })
      networks;
  };
}
