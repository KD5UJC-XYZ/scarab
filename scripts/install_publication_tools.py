#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Install only checksum-pinned publication binaries into a fresh directory."""
import argparse
import io
from pathlib import Path
import tarfile
import urllib.request

from publication_common import digest, load_json, require, sha256


def install(directory, pins):
    require(pins.get("schemaVersion") == 1 and pins.get("platform") == "linux/amd64",
            "unsupported publication toolchain")
    require(set(pins.get("tools", {})) == {"gh", "oras"}, "both pinned publication tools are required")
    directory.mkdir(parents=True, exist_ok=False)
    for name, tool in pins["tools"].items():
        require(set(tool) == {"version", "url", "sha256", "member"}, "incomplete publication tool pin")
        repository = {"gh": "cli/cli", "oras": "oras-project/oras"}[name]
        expected_url = f"https://github.com/{repository}/releases/download/v{tool['version']}/{name}_{tool['version']}_linux_amd64.tar.gz"
        require(tool["url"] == expected_url, "unexpected publication tool source")
        sha256(tool["sha256"])
        with urllib.request.urlopen(tool["url"], timeout=60) as response:
            archive = response.read(128 * 1024 * 1024 + 1)
        require(len(archive) <= 128 * 1024 * 1024 and digest(archive) == tool["sha256"], "publication tool checksum mismatch")
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as bundle:
            matches = [member for member in bundle.getmembers() if member.name == tool["member"]]
            require(len(matches) == 1 and matches[0].isfile() and matches[0].size <= 128 * 1024 * 1024,
                    "ambiguous or non-regular publication executable")
            with bundle.extractfile(matches[0]) as stream:
                (directory / name).write_bytes(stream.read())
        (directory / name).chmod(0o755)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    install(args.output, load_json(Path(__file__).parent / "release/publication-tools.json"))
    print("Installed pinned gh and oras; no credentials or repository settings changed")


if __name__ == "__main__":
    main()
