#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the reference image from the checksum-pinned official cardano-node 11.1.3 release archive.

    prepare_reference_image.py /abs/path/cardano-node-11.1.3-linux-amd64.tar.gz
    prepare_reference_image.py --download [--download-dir DIR] [--pull-base]

Without --download nothing is fetched: the archive must already exist locally. With --download the
exact release URL is fetched (HTTPS only, to DIR/<file>.part), the size and SHA-256 are verified
before the file is renamed into place or used, and an already present verified file is reused.
The archive's three executables are checked against pinned SHA-256 values again before they are
copied into an image built FROM the digest-pinned official base image with no network access.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

BINARY_HASHES = {
    "cardano-node": "ee396604345cc07c7169391ef3f5364886d4b7fe0d539aff6919b470e7a336c3",
    "cardano-cli": "0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed",
    "cardano-testnet": "124e10b604f2653ce8e736f46da2b104aa17325e0f23034cb7dbb5f96f95b149",
}

BASE = "ghcr.io/intersectmbo/cardano-node@sha256:b863d5751bf691588995a8e12509057076e207270db65e88bb16bd543aaa9347"
ARCHIVE_SHA256 = "530fb99986fbdccc46f9ee0dd86cabb1393b015feac9173cb731d89fa748365f"
ARCHIVE_SIZE = 233694227
ARCHIVE_NAME = "cardano-node-11.1.3-linux-amd64.tar.gz"
ARCHIVE_URL = "https://github.com/IntersectMBO/cardano-node/releases/download/11.1.3/" + ARCHIVE_NAME


def verify_archive(path):
    """Raise ValueError unless `path` has exactly the pinned size and SHA-256."""
    if path.stat().st_size != ARCHIVE_SIZE:
        raise ValueError("official archive size mismatch")
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != ARCHIVE_SHA256:
            raise ValueError("official archive checksum mismatch")


def download_archive(directory):
    """Fetch the pinned URL into `directory` and return the verified archive path (reused when present)."""
    target = directory / ARCHIVE_NAME
    directory.mkdir(parents=True, exist_ok=True)
    if target.exists():
        try:
            verify_archive(target)
            return target
        except ValueError:
            target.unlink()
    partial = directory / (ARCHIVE_NAME + ".part")
    request = urllib.request.Request(ARCHIVE_URL, headers={"User-Agent": "scarab"})
    with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as out:
        if not response.geturl().startswith("https://"):
            raise ValueError("download left HTTPS: " + response.geturl())
        shutil.copyfileobj(response, out, 1 << 20)
    try:
        verify_archive(partial)
    except ValueError:
        partial.unlink()
        raise
    partial.rename(target)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("archive", type=Path, nargs="?", help="already downloaded official archive")
    parser.add_argument("--tag", default="cardano-reference-11.1.3:local")
    parser.add_argument("--download", action="store_true",
                        help="fetch the pinned official URL and verify size and SHA-256 before use")
    parser.add_argument("--download-dir", type=Path,
                        default=Path(os.environ.get("SCARAB_DATA_DIR") or os.environ.get("CSL_DATA_DIR", "/tmp/cardano-scala-lab")) / "release",
                        help="where --download stores the archive (default ${SCARAB_DATA_DIR:-/tmp/cardano-scala-lab}/release)")
    parser.add_argument("--pull-base", action="store_true", help="docker pull the digest-pinned base image first")
    args = parser.parse_args()
    if args.download == (args.archive is not None):
        parser.error("give either an archive path or --download")
    context = json.loads(subprocess.check_output(["docker", "context", "inspect"], text=True))[0]
    if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT") or context["Endpoints"]["docker"]["Host"] not in (
            "unix:///var/run/docker.sock", "npipe:////./pipe/dockerDesktopLinuxEngine"):
        raise ValueError("explicit local Docker context without overrides required")
    if subprocess.check_output(["docker", "info", "--format", "{{.OSType}}"], text=True).strip() != "linux":
        raise ValueError("Linux containers required")
    if args.pull_base:
        subprocess.run(["docker", "pull", BASE], check=True)
    subprocess.run(["docker", "image", "inspect", BASE], check=True, stdout=subprocess.DEVNULL)
    archive_path = download_archive(args.download_dir) if args.download else args.archive
    verify_archive(archive_path)
    with tempfile.TemporaryDirectory(prefix="cardano-reference-build-") as work:
        root = Path(work)
        (root / "bin").mkdir()
        with tarfile.open(archive_path) as archive:
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
