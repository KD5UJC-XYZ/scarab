#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fail when the Nix shell's JDK is not the JDK image's major version.

Every JDK `flake/toolchain.nix` names (`jdk<major>`, also as `openjdk`, `jre`, `zulu` or `temurin-bin-` and their
variants, its comments left out) must be the major version of the Temurin release `docker/jdk.Dockerfile` pins, read
from the tag its header names with the pattern `scripts/preflight_doctor.py` reads it by; the file must name at least
one, and the Dockerfile exactly one tag. The shell may carry another update of that release, since the gates build in
the image (docs/nix.md), but not another major version. The check reads text, to catch one pin moved without the
other; it does not parse Nix, so it asks nothing of how the flake binds its JDK, and needs no Nix.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys

from preflight_doctor import DOCKERFILE, TEMURIN_TAG

ROOT = Path(__file__).resolve().parents[1]
TOOLCHAIN = "flake/toolchain.nix"
NIX_COMMENT = re.compile(r"/\*.*?\*/|#[^\n]*", re.DOTALL)
NIX_JDK = re.compile(r"(?:jdk|jre|zulu|temurin-(?:jre-)?bin-)(\d+)")


def problems(toolchain: str, dockerfile: str) -> list[str]:
    named = sorted({int(major) for major in NIX_JDK.findall(NIX_COMMENT.sub(" ", toolchain))})
    image = TEMURIN_TAG.findall(dockerfile)
    found = []
    if not named:
        found.append(f"{TOOLCHAIN} names no JDK (`pkgs.jdk<major>`)")
    if len(image) != 1:
        found.append(f"{DOCKERFILE} names {len(image)} eclipse-temurin tags, not one: the image's JDK cannot be read")
    if not found:
        major = int(image[0][0].split(".", 1)[0])
        if other := [n for n in named if n != major]:
            found.append(f"{TOOLCHAIN} names JDK {', '.join(map(str, other))}, the JDK image Temurin "
                         f"{image[0][0]}+{image[0][1]}: move the flake to the image's major version (or both together)")
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
