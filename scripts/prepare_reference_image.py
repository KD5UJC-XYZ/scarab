#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build from an already downloaded, checksum-pinned official archive; never download/install."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import tarfile
import tempfile

BINARY_HASHES = {
    "cardano-node": "ee396604345cc07c7169391ef3f5364886d4b7fe0d539aff6919b470e7a336c3",
    "cardano-cli": "0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed",
    "cardano-testnet": "124e10b604f2653ce8e736f46da2b104aa17325e0f23034cb7dbb5f96f95b149",
}

BASE = "ghcr.io/intersectmbo/cardano-node@sha256:b863d5751bf691588995a8e12509057076e207270db65e88bb16bd543aaa9347"
ARCHIVE_SHA256 = "530fb99986fbdccc46f9ee0dd86cabb1393b015feac9173cb731d89fa748365f"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--tag", default="cardano-reference-11.1.3:local")
    args = parser.parse_args()
    context = json.loads(subprocess.check_output(["docker", "context", "inspect"], text=True))[0]
    if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT") or context["Endpoints"]["docker"]["Host"] not in (
            "unix:///var/run/docker.sock", "npipe:////./pipe/dockerDesktopLinuxEngine"):
        raise ValueError("explicit local Docker context without overrides required")
    if subprocess.check_output(["docker", "info", "--format", "{{.OSType}}"], text=True).strip() != "linux":
        raise ValueError("Linux containers required")
    subprocess.run(["docker", "image", "inspect", BASE], check=True, stdout=subprocess.DEVNULL)
    if args.archive.stat().st_size != 233694227:
        raise ValueError("official archive size mismatch")
    with args.archive.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != ARCHIVE_SHA256:
            raise ValueError("official archive checksum mismatch")
    with tempfile.TemporaryDirectory(prefix="cardano-reference-build-") as work:
        root = Path(work)
        (root / "bin").mkdir()
        with tarfile.open(args.archive) as archive:
            members = archive.getmembers()
            if any(PurePosixPath(m.name).is_absolute() or ".." in PurePosixPath(m.name).parts
                   or "\\" in m.name for m in members):
                raise ValueError("unsafe archive path")
            for name, expected in BINARY_HASHES.items():
                selected = [m for m in members if PurePosixPath(m.name).name == name and m.isfile()]
                if len(selected) != 1:
                    raise ValueError("missing/duplicate binary: " + name)
                data = archive.extractfile(selected[0]).read()
                if hashlib.sha256(data).hexdigest() != expected:
                    raise ValueError("unexpected official binary: " + name)
                target = root / "bin" / name
                target.write_bytes(data)
                target.chmod(0o755)
        (root / "Dockerfile").write_text("FROM " + BASE +
            '\nCOPY bin/ /opt/reference/bin/\nENV PATH="/opt/reference/bin:${PATH}"\nENTRYPOINT []\n')
        subprocess.run(["docker", "build", "--pull=false", "--network=none", "-t", args.tag, work], check=True)
    subprocess.run(["docker", "image", "inspect", args.tag, "--format", "{{.Id}}"], check=True)


if __name__ == "__main__":
    main()
