#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the reviewed official Mithril verifier; refuse any unexpected executable bytes.

This is the Linux amd64 tool used by import-mithril. Rust and Cargo run only in the pinned
Docker image. Neither a Cardano node nor a network follower is started.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile

REVISION = "769d862dc2d7941192dac9088d1d86df967bf5cc"
IMAGE = "rust@sha256:e12566d5abc17a0da741628782cdb504b68b8961f5bfcf677936bf3e8b32de29"
LOCK_DIGEST = "296de85885c7a74c22a086a280cb1d19a54227f927fc8f83211ee640ac022eed"
PATCH_DIGEST = "468e8abf28d4adcdb481d19e120d592d5562631cc2fc57bc94322126f116fa16"
BINARY_DIGEST = "43e126e151d6407b258e2ab37399b8fae4fe7e953f4e3f5366fdf133c1f2ca46"


def digest(path):
    hasher = hashlib.sha256()
    with pathlib.Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, help="existing clean pinned upstream clone")
    parser.add_argument("--work", type=pathlib.Path,
                        default=pathlib.Path(os.environ.get("SCARAB_DATA_DIR", "/tmp/scarab")) / "mithril-client")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    source = args.source.resolve() if args.source else work / "source"
    if not source.exists():
        run("git", "clone", "--no-checkout", "https://github.com/IntersectMBO/mithril.git", str(source))
        run("git", "-C", str(source), "checkout", "--detach", REVISION)
    revision = run("git", "-C", str(source), "rev-parse", "HEAD", capture_output=True).stdout.strip()
    dirty = run("git", "-C", str(source), "status", "--porcelain", "--untracked-files=no",
                capture_output=True).stdout
    if revision != REVISION or dirty or digest(source / "Cargo.lock") != LOCK_DIGEST:
        raise SystemExit("upstream source or Cargo.lock differs from the reviewed pin")
    patch = pathlib.Path(__file__).with_name("mithril-receipt.patch")
    if digest(patch) != PATCH_DIGEST:
        raise SystemExit("receipt-only patch differs from the reviewed pin")
    build = work / "build"
    build.mkdir(exist_ok=True)
    # Never remove a caller-provided source/work path. Only this owned temporary tree is cleaned.
    with tempfile.TemporaryDirectory(prefix="source-", dir=work) as temporary:
        patched = pathlib.Path(temporary)
        archive = subprocess.Popen(["git", "-C", str(source), "archive", "HEAD"], stdout=subprocess.PIPE)
        try:
            run("tar", "-x", "-C", str(patched), stdin=archive.stdout)
        finally:
            archive.stdout.close()
        if archive.wait() != 0:
            raise SystemExit("could not export the pinned source")
        run("git", "-C", str(patched), "apply", "--unidiff-zero", str(patch.resolve()))
        # Fixed in-container source/build paths and no inherited Rust flags/configuration.
        container = f"scarab-mithril-client-build-{os.getpid()}"
        try:
            run("docker", "run", "--rm", "--platform", "linux/amd64", "--name", container,
                "-e", "CARGO_HOME=/build/cargo", "-v", f"{patched}:/src:ro", "-v", f"{build}:/build",
                "-w", "/src", IMAGE, "cargo", "build", "--locked", "--release", "-p", "mithril-client-cli",
                "--target-dir", "/build/target")
        finally:
            subprocess.run(["docker", "rm", "-f", container], capture_output=True, check=False)
    binary = build / "target/release/mithril-client"
    actual = digest(binary)
    if actual != BINARY_DIGEST:
        raise SystemExit(f"verifier build differs from reviewed executable: {actual}")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binary, args.out)
    args.out.chmod(0o755)
    print(json.dumps({"source": REVISION, "cargoLockSha256": LOCK_DIGEST, "receiptPatchSha256": PATCH_DIGEST, "image": IMAGE,
                      "binarySha256": actual, "executable": str(args.out.resolve())}))


if __name__ == "__main__":
    main()
