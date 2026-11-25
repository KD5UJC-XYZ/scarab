# SPDX-License-Identifier: Apache-2.0
{
  perSystem = {pkgs, ...}: {
    _module.args.scalaToolchain = rec {
      jdk = pkgs.jdk25;
      sbt = pkgs.sbt.override {jre = jdk;};
    };
  };
}
