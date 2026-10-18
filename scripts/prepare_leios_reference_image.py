#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build a Leios reference image: a pinned build of the Leios team's prototype node.

    prepare_leios_reference_image.py [--profile w38a|main] [--tag TAG] [--download-dir DIR]

Two profiles, each its own image tag, neither touching the 11.1.3 reference image of
`prepare_reference_image.py` that the existing scenarios use:

  w38a  the build the public Leios testnet runs: the release archive of the `prototype-2026w38a`
        release of `ouroboros-leios` (checksum-pinned, fetched over HTTPS and verified before use;
        node revision 8c44d145). Image `cardano-reference-leios-w38a:local`.
  main  the newest published build when this was written (node revision 2d6a2a66, the state of the
        Leios team's `main` of 2026-10-16): the digest-pinned relay image, plus the load generator of
        the digest-pinned devnet image. Image `cardano-reference-leios-2d6a2a66:local`.

Every executable is checked against a pinned SHA-256 before it goes into an image, which is built
without network access. The pins and where they come from are in docs/leios.md section 12.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from devnet.util import data_dir

DEBIAN = "debian:bookworm-slim@sha256:7c7b2c966bc9ee8cedfeef67e0e279108992c77681fa595db4a9d65c06ccc587"
RELAY_IMAGE = ("ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet@"
               "sha256:341462a038a069664ea723976501815e8e8875ea9c7b15946b62bc1994cc0f3b")
DEVNET_IMAGE = ("ghcr.io/input-output-hk/ouroboros-leios/antithesis-cardano-node-devnet@"
                "sha256:e9fed3819512699990042191b1e8b42a3b319773e9f645d51ab6e53f544d7cdf")


@dataclass(frozen=True)
class Profile:
    name: str
    tag: str
    node_revision: str       # `git rev` of the node's version output
    config_revision: str     # the `ouroboros-leios` commit whose demo/proto-devnet configuration this node is run with
    binaries: dict           # executable name -> SHA-256


W38A = Profile(
    name="w38a",
    tag="cardano-reference-leios-w38a:local",
    node_revision="8c44d14542f41e96b657d013c6e183bcee9dfd85",
    # the commit the annotated tag prototype-2026w38a (d0463cbe) points at; its flake.lock names node_revision
    config_revision="f65db01ca1f9462241e844bd3018cc4b78d41a30",
    binaries={
        "cardano-node": "ca35aa211fd2147ba61c9d0782379d04fbd0c83d45f1b11334f354dad043490c",
        "cardano-cli": "6ec529e7f00f7059982267e9c4d4b37b8c88bd32d831c22c129c250ce2433fd0",
        "tx-firehose": "6e2fe9791d2dbd70cbcac7292a86596dae60ac5e19a21e49272bd58e70bfb83f",
    })
MAIN = Profile(
    name="main",
    tag="cardano-reference-leios-2d6a2a66:local",
    node_revision="2d6a2a66415cc1d58c18ed3ee83ce719713fc97d",
    # the revision the relay image is built from (its image label)
    config_revision="49f7c4a2c37577e187e562d196cf8876815918a5",
    binaries={
        "cardano-node": "5734a006d17d6af3b8ee98b305a0b57a9884c5e1c51069709aeb9364e8a13d12",
        "cardano-cli": "4f074c3eb4e727c211ae13817d091e5d610269bf520fadff87f8203da3b4153b",
        "tx-firehose": "57bd35881de0dccf587bbd3d9707693fac21b7f890a9043a69e6c0a7fdb1329f",
    })
PROFILES = {p.name: p for p in (W38A, MAIN)}

ARCHIVE_NAME = "cardano-node-leios-x86_64-linux.tar.gz"
ARCHIVE_URL = "https://github.com/input-output-hk/ouroboros-leios/releases/download/prototype-2026w38a/" + ARCHIVE_NAME
ARCHIVE_SIZE = 87847575
ARCHIVE_SHA256 = "0a5809af1c4f6e8088145f88fbcfb2c3e5f8e774b736c2ebdb0b1119b7a97fbc"


def verify_archive(path):
    """Raise ValueError unless `path` has exactly the pinned size and SHA-256."""
    if path.stat().st_size != ARCHIVE_SIZE:
        raise ValueError("release archive size mismatch")
    with path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != ARCHIVE_SHA256:
            raise ValueError("release archive checksum mismatch")


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


def extract_binaries(archive_path, profile, into):
    """The profile's executables from the archive into `into`, each checked against its pinned SHA-256."""
    into.mkdir()
    with tarfile.open(archive_path) as archive:
        members = archive.getmembers()
        if any(PurePosixPath(m.name).is_absolute() or ".." in PurePosixPath(m.name).parts or "\\" in m.name
               for m in members):
            raise ValueError("unsafe archive path")
        for name, expected in profile.binaries.items():
            selected = [m for m in members if PurePosixPath(m.name).name == name and m.isfile()]
            if len(selected) != 1:
                raise ValueError("missing/duplicate binary: " + name)
            data = archive.extractfile(selected[0]).read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError("unexpected release binary: " + name)
            target = into / name
            target.write_bytes(data)
            target.chmod(0o755)


def check_lines(profile, directory):
    """Shell lines that check the installed executables against the pins (run in the build, offline)."""
    return " && ".join(f"echo '{digest}  {directory}/{name}' | sha256sum -c -"
                       for name, digest in profile.binaries.items())


def dockerfile(profile):
    labels = (f'LABEL scarab.leios.profile="{profile.name}" scarab.node.revision="{profile.node_revision}" '
              f'scarab.config.revision="{profile.config_revision}"')
    tail = ['ENV PATH="/opt/reference/bin:${PATH}"', "ENTRYPOINT []", labels, ""]
    if profile is W38A:
        return "\n".join([f"FROM {DEBIAN}", "COPY bin/ /opt/reference/bin/",
                          f"RUN {check_lines(profile, '/opt/reference/bin')}", *tail])
    return "\n".join([
        f"FROM {RELAY_IMAGE} AS relay", f"FROM {DEVNET_IMAGE} AS devnet", f"FROM {DEBIAN}",
        "COPY --from=relay /usr/local/bin/cardano-node /usr/local/bin/cardano-cli /opt/reference/bin/",
        "COPY --from=devnet /usr/local/bin/tx-firehose /opt/reference/bin/",
        f"RUN {check_lines(profile, '/opt/reference/bin')}", *tail])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--profile", choices=sorted(PROFILES), default="w38a")
    parser.add_argument("--tag", help="image name (default: the profile's)")
    parser.add_argument("--download-dir", type=Path, default=data_dir() / "release-leios",
                        help="where the w38a archive is stored (default ${SCARAB_DATA_DIR:-/tmp/scarab}/release-leios)")
    parser.add_argument("--no-pull", action="store_true", help="use the pinned upstream images from the local cache")
    args = parser.parse_args()
    profile = PROFILES[args.profile]
    context = json.loads(subprocess.check_output(["docker", "context", "inspect"], text=True))[0]
    if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT") or context["Endpoints"]["docker"]["Host"] not in (
            "unix:///var/run/docker.sock", "npipe:////./pipe/dockerDesktopLinuxEngine"):
        raise ValueError("explicit local Docker context without overrides required")
    images = [DEBIAN] if profile is W38A else [DEBIAN, RELAY_IMAGE, DEVNET_IMAGE]
    for image in images:
        if not args.no_pull:
            subprocess.run(["docker", "pull", image], check=True)
        subprocess.run(["docker", "image", "inspect", image], check=True, stdout=subprocess.DEVNULL)
    with tempfile.TemporaryDirectory(prefix="leios-reference-build-") as work:
        root = Path(work)
        if profile is W38A:
            extract_binaries(download_archive(args.download_dir), profile, root / "bin")
        (root / "Dockerfile").write_text(dockerfile(profile))
        subprocess.run(["docker", "build", "--pull=false", "--network=none", "-t", args.tag or profile.tag, work],
                       check=True)
    print(subprocess.check_output(["docker", "image", "inspect", args.tag or profile.tag, "--format", "{{.Id}}"],
                                  text=True).strip())


if __name__ == "__main__":
    main()
