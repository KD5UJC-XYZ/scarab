#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when the Nix shell's JDK is not the JDK image's major version.

The flake names its JDK as `jdk = pkgs.jdk<major>;` in `flake/toolchain.nix`; the JDK image pins a Temurin release,
read from the tag the header of `docker/jdk.Dockerfile` names, as `scripts/preflight_doctor.py` reads it. The shell
may carry another update of that release, since the gates build in the image (docs/nix.md), but not another major
version. Either one missing, or the flake naming its JDK more than once, fails the check too. It reads text only and
needs no Nix.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

from preflight_doctor import DOCKERFILE, jdk_pin

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = "flake/toolchain.nix"
NIX_JDK = re.compile(r"^\s*jdk\s*=\s*pkgs\.jdk(\d+)\s*;", re.MULTILINE)


def problems(toolchain: str, dockerfile: str) -> list[str]:
    nix = NIX_JDK.findall(toolchain)
    pin = jdk_pin(dockerfile)
    found = []
    if len(nix) != 1:
        found.append(f"{TOOLCHAIN} names its JDK {len(nix)} times, not once as `jdk = pkgs.jdk<major>;`")
    if pin is None:
        found.append(f"{DOCKERFILE} names no eclipse-temurin tag; the image's JDK cannot be read")
    if not found and int(nix[0]) != int(pin.release.split(".", 1)[0]):
        found.append(f"{TOOLCHAIN} has JDK {nix[0]}, the JDK image Temurin {pin.release}: "
                     "move the flake to the image's major version (or both together)")
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    found = problems((args.root / TOOLCHAIN).read_text(encoding="utf-8"),
                     (args.root / DOCKERFILE).read_text(encoding="utf-8"))
    for problem in found:
        print(problem)
    if not found:
        print("the Nix shell's JDK is the JDK image's major version")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
