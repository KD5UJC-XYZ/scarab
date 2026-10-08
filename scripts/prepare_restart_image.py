#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline test-image packaging only. No credentials, runtime data or downloads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

HELPER = "/opt/reference/libexec/restart-pidfd"


def dockerfile(base, alias):
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", base):
        raise ValueError("exact local base image ID required")
    if not re.fullmatch(r"cardano-restart-test-[a-z0-9-]+:base", alias):
        raise ValueError("task-owned local base alias required")
    return f"# Verified local base: {base}\nFROM {alias}\nCOPY --chown=0:0 --chmod=0555 restart-pidfd {HELPER}\n"


def pinned_file(path, digest, maximum):
    if not re.fullmatch("[0-9a-f]{64}", digest) or path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError("bounded regular input and explicit SHA-256 required")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError("input digest differs from pin")
    return data


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base-image", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--helper", type=Path, required=True)
    p.add_argument("--helper-sha256", required=True)
    p.add_argument("--source-sha256", required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    if not re.fullmatch(r"cardano-restart-test-[a-z0-9-]+:local", args.tag):
        raise ValueError("unique restart-test tag required; shared reference tag forbidden")
    alias = args.tag.removesuffix(":local") + ":base"
    recipe = dockerfile(args.base_image, alias)
    if os.environ.get("DOCKER_HOST") or os.environ.get("DOCKER_CONTEXT"):
        raise ValueError("Docker endpoint overrides unsupported")
    binary = pinned_file(args.helper, args.helper_sha256, 4 * 1024**2)
    if not binary.startswith(b"\x7fELF"):
        raise ValueError("static Linux ELF required")
    pinned_file(Path(__file__).with_name("private_cluster_pidfd.c"), args.source_sha256, 128 * 1024)
    args.output.mkdir(parents=True, exist_ok=False)
    def docker(*parts, check=True):
        result = subprocess.run(["docker", "--host", "unix:///var/run/docker.sock", *parts],
                                capture_output=True, text=True, timeout=120)
        with (args.output / "commands.md").open("a") as log:
            log.write(json.dumps({"args": parts, "returncode": result.returncode,
                                  "stdout": result.stdout, "stderr": result.stderr}) + "\n")
        if check and result.returncode:
            raise RuntimeError("offline image operation failed; see private commands receipt")
        return result
    for tag in (args.tag, alias):
        if docker("image", "ls", "--format", "{{.Repository}}:{{.Tag}}", tag).stdout.strip():
            raise ValueError("refuse replacing an existing tag")
    base = json.loads(docker("image", "inspect", args.base_image).stdout)[0]
    if base["Id"] != args.base_image:
        raise ValueError("base image ID differs")
    docker("tag", args.base_image, alias)
    try:
        if docker("image", "inspect", alias, "--format", "{{.Id}}").stdout.strip() != args.base_image:
            raise ValueError("local base alias differs from pinned image ID")
        with tempfile.TemporaryDirectory(prefix="restart-image-context-") as directory:
            context = Path(directory)
            (context / "Dockerfile").write_text(recipe)
            (context / "restart-pidfd").write_bytes(binary)
            docker("build", "--pull=false", "--network=none", "-t", args.tag, directory)
    finally:
        docker("image", "rm", alias)
    image = json.loads(docker("image", "inspect", args.tag).stdout)[0]
    if image["RootFS"]["Layers"][:-1] != base["RootFS"]["Layers"]:
        raise ValueError("derived image must add exactly one helper layer")
    (args.output / "Dockerfile").write_text(recipe)
    (args.output / "receipt.md").write_text(json.dumps({
        "baseImageId": base["Id"], "imageId": image["Id"], "tag": args.tag,
        "dockerfileSha256": hashlib.sha256(recipe.encode()).hexdigest(),
        "helperSha256": args.helper_sha256, "sourceSha256": args.source_sha256,
        "path": HELPER, "owner": "0:0", "mode": "0555",
        "contextFiles": ["Dockerfile", "restart-pidfd"], "network": "none", "pull": False}, indent=2))
    print(image["Id"])


if __name__ == "__main__": main()
