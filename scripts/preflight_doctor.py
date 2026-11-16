#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""`scripts/preflight --doctor`: what the gates would run with on this machine, on the terminal only.

It reports the pinned JDK (the Temurin release whose tag `docker/jdk.Dockerfile` names beside the digest it pins)
against `java -version` inside the JDK image (never a host `java`), whether Docker is reachable, and where the
dependency cache lives.
The report names local paths and hosts, so it is printed and never written anywhere; the doctor creates no
file and always exits 0.

Usage: python3 -B scripts/preflight_doctor.py [--docker COMMAND]
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from devnet.util import JDK_IMAGE, cache_dir  # noqa: E402

DOCKERFILE = "docker/jdk.Dockerfile"
# The Temurin tag the Dockerfile's header names for its pinned digest, `eclipse-temurin:<release>_<build>-jdk-...`;
# the JDK of that release reports `<release>+<build>` and the vendor below.
TEMURIN_TAG = re.compile(r"\beclipse-temurin:(\d+(?:\.\d+)*)_(\d+)-jdk-")
TEMURIN_VENDOR = "Eclipse Adoptium"
PROPERTY = re.compile(r"^\s*(java\.(?:version|vendor|runtime\.version)) = (.+)$", re.MULTILINE)


@dataclass(frozen=True)
class Line:
    """One row of the report; `good` is None for a fact that is neither right nor wrong."""
    topic: str
    text: str
    good: bool | None = None

    def render(self) -> str:
        mark = {True: "ok  ", False: "WARN", None: "info"}[self.good]
        return f"  {mark} {self.topic}: {self.text}"


def run(command: list[str], timeout: int = 120) -> subprocess.CompletedProcess | None:
    """The finished command, or None when it cannot be started or does not finish."""
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None


def docker_line(info: subprocess.CompletedProcess | None) -> Line:
    if info is None:
        return Line("docker", "not found or not answering; preflight would stop INCOMPLETE", False)
    if info.returncode != 0:
        last = (info.stderr.strip().splitlines() or ["no message"])[-1][:200]
        return Line("docker", f"daemon unreachable ({last}); preflight would stop INCOMPLETE", False)
    return Line("docker", f"reachable, server {info.stdout.strip() or 'unknown version'}", True)


@dataclass(frozen=True)
class JdkPin:
    """The JDK the image is built from: its vendor and the `java.runtime.version` prefix of its release."""
    vendor: str
    release: str

    def matches(self, vendor: str | None, runtime_version: str | None) -> bool:
        return vendor == self.vendor and runtime_version is not None and (
            runtime_version == self.release or runtime_version.startswith(f"{self.release}-"))


def jdk_pin(dockerfile: str) -> JdkPin | None:
    """The Temurin release `docker/jdk.Dockerfile` names for its pinned digest, or None when it names none."""
    match = TEMURIN_TAG.search(dockerfile)
    return None if match is None else JdkPin(TEMURIN_VENDOR, f"{match.group(1)}+{match.group(2)}")


def jdk_line(pin: JdkPin | None, reachable: bool, image_java: subprocess.CompletedProcess | None) -> Line:
    if pin is None:
        return Line("jdk", f"{DOCKERFILE} names no eclipse-temurin tag; the pin cannot be read", False)
    pinned = f"{pin.vendor} {pin.release}"
    if not reachable:
        return Line("jdk", f"pinned {pinned}; {JDK_IMAGE} not checked (Docker unavailable)", None)
    if image_java is None or image_java.returncode != 0:
        return Line("jdk", f"pinned {pinned}; {JDK_IMAGE} not available (build it from docker/jdk.Dockerfile)", False)
    found = dict(PROPERTY.findall(image_java.stderr))
    actual = f"{found.get('java.vendor', '?')} {found.get('java.runtime.version', '?')}"
    matches = pin.matches(found.get("java.vendor"), found.get("java.runtime.version"))
    return Line("jdk", f"pinned {pinned}; {JDK_IMAGE} has {actual}", matches)


def cache_line(cache: Path, env: dict) -> Line:
    origin = "SCARAB_CACHE_DIR" if env.get("SCARAB_CACHE_DIR") else "resolved from the checkout"
    state = "populated" if (cache / "scarab").is_dir() else "empty or absent"
    return Line("cache", f"{cache} ({origin}; {state})", None)


def report(root: Path, docker: str, env: dict, runner=run) -> list[Line]:
    pin = jdk_pin((root / DOCKERFILE).read_text(encoding="utf-8"))
    info = runner([docker, "info", "--format", "{{.ServerVersion}}"], 30)
    reachable = info is not None and info.returncode == 0
    image_java = (runner([docker, "run", "--rm", "--network", "none", JDK_IMAGE,
                          "java", "-XshowSettings:properties", "-version"]) if reachable else None)
    cache = cache_dir(root, env)
    return [docker_line(info), jdk_line(pin, reachable, image_java), cache_line(cache, env)]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--docker", default="docker", help="the Docker command (default docker)")
    args = parser.parse_args(argv)
    print("preflight doctor (terminal only; nothing is written)")
    for line in report(ROOT, args.docker, dict(os.environ)):
        print(line.render())
    return 0


if __name__ == "__main__":
    sys.exit(main())
