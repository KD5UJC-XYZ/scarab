# SPDX-License-Identifier: Apache-2.0
{
  perSystem = {
    config,
    pkgs,
    scalaToolchain,
    ...
  }: {
    devShells.default = pkgs.mkShell {
      packages = [
        scalaToolchain.jdk
        scalaToolchain.sbt
        pkgs.git
        pkgs.curl
        pkgs.python3
        config.treefmt.build.wrapper
      ];
      JAVA_HOME = "${scalaToolchain.jdk}";
    };
  };
}
