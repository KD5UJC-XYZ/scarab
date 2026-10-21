#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Pin retained baseline bytes, then delegate authentication to the shared real verifier."""
import argparse
from pathlib import Path

from publication_common import digest, load_json, require, safe_path, sha256
from published_receipt import authenticate
from release_identity import channel_for_version


REPOSITORY = "KD5UJC-XYZ/scarab"


def verify(root, authority, gh):
    require(authority.get("kind") == "published-stable" and channel_for_version(authority["version"]) == "prod",
            "published stable authority required")
    descriptor = authority["verification"]
    require(set(descriptor) == {"receiptPath", "receiptSha256"}, "exact original verification receipt identity required")
    relative = descriptor["receiptPath"]
    require(relative.startswith(".release/baselines/"), "retain original verification in reviewed baseline inputs")
    target = safe_path(root, relative)
    require(target.is_file() and target.stat().st_size <= 4 * 1024 * 1024, "missing or oversized baseline verification input")
    require(digest(target.read_bytes()) == sha256(descriptor["receiptSha256"]), "baseline verification bytes changed")
    return authenticate(target, REPOSITORY, authority["source"], authority["version"], gh)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--authority", type=Path, required=True)
    parser.add_argument("--gh", type=Path, required=True)
    args = parser.parse_args()
    verify(args.root, load_json(args.authority), args.gh)
    print("Original baseline receipt bytes and independent public attestation verified")


if __name__ == "__main__":
    main()
