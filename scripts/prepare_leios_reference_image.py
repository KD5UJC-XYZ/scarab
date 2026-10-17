#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the Leios reference image from the digest-pinned relay image of the Leios team.

    prepare_leios_reference_image.py [--tag TAG] [--no-pull]

The image `cardano-reference-leios-2d6a2a66:local` is a separate tag from the 11.1.3 reference image
(`prepare_reference_image.py`), which the existing scenarios keep using. It adds nothing to the pinned
upstream image except a check, at build time and without network access, that the two executables
are the pinned ones, and it clears the entrypoint. The pins and where they come from are in
docs/leios.md section 12.
"""
import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path

UPSTREAM = ("ghcr.io/input-output-hk/ouroboros-leios/cardano-node-testnet@"
            "sha256:341462a038a069664ea723976501815e8e8875ea9c7b15946b62bc1994cc0f3b")
# the image is built by .github/workflows/cardano-node-leios.yaml of ouroboros-leios from this revision
OUROBOROS_LEIOS_REVISION = "49f7c4a2c37577e187e562d196cf8876815918a5"
# the node's own revision (flake.lock of that revision, and the node's version output)
NODE_REVISION = "2d6a2a66415cc1d58c18ed3ee83ce719713fc97d"
BINARY_HASHES = {
    "/usr/local/bin/cardano-node": "5734a006d17d6af3b8ee98b305a0b57a9884c5e1c51069709aeb9364e8a13d12",
    "/usr/local/bin/cardano-cli": "4f074c3eb4e727c211ae13817d091e5d610269bf520fadff87f8203da3b4153b",
}
DEFAULT_TAG = "cardano-reference-leios-2d6a2a66:local"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tag", default=DEFAULT_TAG)
    parser.add_argument("--no-pull", action="store_true", help="use the upstream image from the local cache")
    args = parser.parse_args()
    context = json.loads(subprocess.check_output(["docker", "context", "inspect"], text=True))[0]
    if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT") or context["Endpoints"]["docker"]["Host"] not in (
            "unix:///var/run/docker.sock", "npipe:////./pipe/dockerDesktopLinuxEngine"):
        raise ValueError("explicit local Docker context without overrides required")
    if not args.no_pull:
        subprocess.run(["docker", "pull", UPSTREAM], check=True)
    checks = " && ".join(f"echo '{digest}  {path}' | sha256sum -c -" for path, digest in BINARY_HASHES.items())
    dockerfile = "\n".join([
        f"FROM {UPSTREAM}",
        f"RUN {checks}",
        f'LABEL org.opencontainers.image.revision="{OUROBOROS_LEIOS_REVISION}" scarab.node.revision="{NODE_REVISION}"',
        "ENTRYPOINT []",
        "",
    ])
    with tempfile.TemporaryDirectory(prefix="leios-reference-build-") as work:
        (Path(work) / "Dockerfile").write_text(dockerfile)
        subprocess.run(["docker", "build", "--pull=false", "--network=none", "-t", args.tag, work], check=True)
    print(subprocess.check_output(["docker", "image", "inspect", args.tag, "--format", "{{.Id}}"], text=True).strip())


if __name__ == "__main__":
    main()
