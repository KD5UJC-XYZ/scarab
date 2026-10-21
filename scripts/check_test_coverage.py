# SPDX-License-Identifier: Apache-2.0
"""Validate fixture identities, corpus documentation and declared comparison scope.

The register is an inventory, never an instruction to ignore a failed assertion.
Refreshing identities does not regenerate any fixture or expected result.
"""
import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INVENTORY = "docs/data/fixture-inventory.json"
REGISTER = "docs/data/test-coverage-register.json"
KINDS = {"native-parity", "regression", "input-boundary", "reference-non-result",
         "canonicalization", "sampling-limit", "external-input", "known-divergence"}
STATES = {"covered", "open", "pending-integration"}


def relative_file(root, value):
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or not value:
        raise ValueError(f"unsafe repository path: {value!r}")
    target = root / path
    if target.is_symlink() or not target.is_file() or root.resolve() not in target.resolve().parents:
        raise ValueError(f"missing or nonregular repository file: {value}")
    return target


def discover_corpora(root):
    corpora = set()
    fixtures = root / "fixtures"
    if fixtures.is_dir():
        for path in fixtures.rglob("*"):
            if not path.is_file():
                continue
            parts = path.relative_to(root).parts
            if len(parts) < 3:
                continue
            depth = 3 if parts[1] in {"reference", "preview"} and len(parts) > 3 else 2
            corpora.add("/".join(parts[:depth]))
    for resources in root.glob("*/src/test/resources"):
        for path in resources.rglob("*"):
            if not path.is_file():
                continue
            parts = path.relative_to(resources).parts
            depth = 2 if parts[0] == "upstream" and len(parts) > 2 else 1
            corpus = resources if len(parts) == 1 else resources.joinpath(*parts[:depth])
            corpora.add(corpus.relative_to(root).as_posix())
    return sorted(corpora)


def fixture_inventory(root):
    corpora = discover_corpora(root)
    payloads = {}
    for corpus in corpora:
        for path in sorted((root / corpus).rglob("*")):
            if path.is_file() and path.suffix.lower() != ".md":
                rel = path.relative_to(root).as_posix()
                relative_file(root, rel)
                payloads[rel] = hashlib.sha256(path.read_bytes()).hexdigest()
    return {"schemaVersion": 1, "corpora": corpora, "files": dict(sorted(payloads.items()))}


def validate_inventory(root, expected):
    if set(expected) != {"schemaVersion", "corpora", "files"} or expected["schemaVersion"] != 1:
        raise ValueError("unsupported fixture inventory schema")
    actual = fixture_inventory(root)
    if expected != actual:
        missing = sorted(set(expected["files"]) - set(actual["files"]))
        added = sorted(set(actual["files"]) - set(expected["files"]))
        changed = sorted(p for p in set(actual["files"]) & set(expected["files"])
                         if actual["files"][p] != expected["files"][p])
        raise ValueError(f"fixture inventory differs: missing={missing[:8]}, added={added[:8]}, "
                         f"changed={changed[:8]}, corpusSetChanged={expected['corpora'] != actual['corpora']}")
    for corpus in actual["corpora"]:
        readme = relative_file(root, f"{corpus}/README.md")
        if not readme.read_text(encoding="utf-8").strip():
            raise ValueError(f"empty corpus README: {corpus}")
    return actual


def validate_register(root, register, candidate=False):
    if set(register) != {"schemaVersion", "entries"} or register["schemaVersion"] != 1:
        raise ValueError("unsupported comparison register schema")
    required = {"id", "classification", "status", "scope", "assertion", "rationale",
                "owner", "tracking", "resolution", "evidence", "candidateRequired"}
    seen = set()
    if not register["entries"]:
        raise ValueError("comparison register has no entries")
    for row in register["entries"]:
        if set(row) != required:
            raise ValueError(f"invalid fields in coverage entry: {row.get('id')}")
        if row["id"] in seen:
            raise ValueError(f"duplicate coverage ID: {row['id']}")
        seen.add(row["id"])
        if row["classification"] not in KINDS or row["status"] not in STATES:
            raise ValueError(f"unknown classification/status: {row['id']}")
        for key in required - {"evidence", "candidateRequired"}:
            if not isinstance(row[key], str) or not row[key].strip():
                raise ValueError(f"empty {key}: {row['id']}")
        if not isinstance(row["candidateRequired"], bool) or not row["evidence"]:
            raise ValueError(f"missing candidate policy/evidence: {row['id']}")
        for path in row["evidence"]:
            relative_file(root, path)
        if candidate and row["candidateRequired"] and row["status"] != "covered":
            raise ValueError(f"candidate has unresolved required coverage: {row['id']}")
    return len(seen)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--refresh-fixtures", action="store_true",
                        help="write reviewed fixture identities; never regenerate expected results")
    parser.add_argument("--candidate", action="store_true", help="also reject unresolved candidate requirements")
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        if args.refresh_fixtures:
            target = root / INVENTORY
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(fixture_inventory(root), indent=2) + "\n", encoding="utf-8")
        inventory = validate_inventory(root, json.loads((root / INVENTORY).read_text()))
        entries = validate_register(root, json.loads((root / REGISTER).read_text()), args.candidate)
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, f"coverage check failed: {error}\n")
    print(f"coverage checked: {len(inventory['corpora'])} documented corpora, "
          f"{len(inventory['files'])} pinned files, {entries} comparison contracts")


if __name__ == "__main__":
    main()
