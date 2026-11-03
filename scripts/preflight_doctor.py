#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""`scripts/preflight --doctor`: what the gates would run with on this machine, on the terminal only.

It reports the pinned JDK (`patches/scalus/manifest.json`, the JDK `docker/jdk.Dockerfile` pins) against
`java -version` inside the JDK image (never a host `java`), whether the prepared Scalus dependency repository
of the current pins exists in the cache, whether Docker is reachable, and where the dependency cache lives.
The report names local paths and hosts, so it is printed and never written anywhere; the doctor creates no
file and always exits 0.

Usage: python3 -B scripts/preflight_doctor.py [--docker COMMAND]
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from devnet.util import JDK_IMAGE, cache_dir  # noqa: E402
from prepare_scalus import input_digest  # noqa: E402

MANIFEST = "patches/scalus/manifest.json"
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


def jdk_line(manifest: dict, reachable: bool, image_java: subprocess.CompletedProcess | None) -> Line:
    pinned = f"{manifest['javaVendor']} {manifest['javaRuntimeVersion']}"
    if not reachable:
        return Line("jdk", f"pinned {pinned}; {JDK_IMAGE} not checked (Docker unavailable)", None)
    if image_java is None or image_java.returncode != 0:
        return Line("jdk", f"pinned {pinned}; {JDK_IMAGE} not available (build it from docker/jdk.Dockerfile)", False)
    found = dict(PROPERTY.findall(image_java.stderr))
    actual = f"{found.get('java.vendor', '?')} {found.get('java.runtime.version', '?')}"
    matches = (found.get("java.vendor"), found.get("java.runtime.version")) == \
        (manifest["javaVendor"], manifest["javaRuntimeVersion"])
    return Line("jdk", f"pinned {pinned}; {JDK_IMAGE} has {actual}", matches)


def repository_line(cache: Path, digest: str) -> Line:
    entry = cache / "scarab" / "patched-scalus" / digest
    return (Line("dependency repository", f"prepared at {entry}", True) if (entry / "repository").is_dir()
            else Line("dependency repository", f"not prepared for the current pins ({entry} is absent; the first "
                                               "sbt build in the JDK image prepares it)", False))


def cache_line(cache: Path, env: dict) -> Line:
    origin = "SCARAB_CACHE_DIR" if env.get("SCARAB_CACHE_DIR") else "resolved from the checkout"
    state = "populated" if (cache / "scarab").is_dir() else "empty or absent"
    return Line("cache", f"{cache} ({origin}; {state})", None)


def report(root: Path, docker: str, env: dict, runner=run) -> list[Line]:
    manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    info = runner([docker, "info", "--format", "{{.ServerVersion}}"], 30)
    reachable = info is not None and info.returncode == 0
    image_java = (runner([docker, "run", "--rm", "--network", "none", JDK_IMAGE,
                          "java", "-XshowSettings:properties", "-version"]) if reachable else None)
    cache = cache_dir(root, env)
    return [docker_line(info), jdk_line(manifest, reachable, image_java),
            repository_line(cache, input_digest(root)), cache_line(cache, env)]


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
