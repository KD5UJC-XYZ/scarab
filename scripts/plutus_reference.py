#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""One copy of the Plutus reference data the tests read, with each pinned release as a view of it.

`plutus/src/test/resources/plutus-reference` holds the newest pinned release whole (the base). An
older release keeps only the files that differ from the base, under `overlays/<tag>/`, and lists
the base files it lacks in `releases.json`. A release's view is the base, its overlay over it, minus
its absent files. Nothing is downloaded when tests run; `verify` and `add-release` fetch the codeload
archive of a commit, as `scripts/prepare_scalus.py` does for Scalus. `verify` refuses an archive whose
sha256 is not the recorded one; `add-release` records it.

  check        offline: every view matches the digest and file count recorded in releases.json
  verify       download each release and compare its view with upstream, file by file
  add-release  download a newer release, make it the base and move the older releases to overlays
"""

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = Path("plutus/src/test/resources/plutus-reference")
MANIFEST = "releases.json"
OVERLAYS = "overlays"
ARCHIVE = "https://codeload.github.com/IntersectMBO/plutus/tar.gz/{commit}"
CONFORMANCE = ("conformance", "plutus-conformance/test-cases/uplc/evaluation/")
CONFORMANCE_SUFFIXES = (".uplc", ".uplc.expected", ".budget.expected")
COST_MODEL = ("cost-model", "plutus-core/cost-model/data/")
COST_MODEL_FILES = tuple(f"{kind}{variant}.json" for kind in ("builtinCostModel", "cekMachineCosts")
                         for variant in "ABCDE")
LEDGER_API = ("ledger-api", "plutus-ledger-api/src/PlutusLedgerApi/")
PARAM_NAME_LANGUAGES = ("V1", "V2", "V3")
# Scarab's own files: documentation and the generated sidecars the manifest lists. Not part of any
# upstream view.
OWN = re.compile(r"(.*/)?README\.md|releases\.json")


class ReferenceError(Exception):
    pass


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest(view: dict[str, bytes]) -> str:
    """The identity of a view: sha256 over its sorted `path<TAB>sha256` lines."""
    return sha256("".join(f"{path}\t{sha256(data)}\n" for path, data in sorted(view.items())).encode())


def param_names(source: str) -> bytes:
    """The constructors of `data ParamName`, in declaration order, one per line."""
    match = re.search(r"data ParamName\s*=(.*?)\n\s*deriving", source, re.S)
    if not match:
        raise ReferenceError("no data ParamName declaration")
    names = [name.strip() for name in re.sub(r"--[^\n]*", "", match.group(1)).split("|") if name.strip()]
    return "".join(f"{name}\n" for name in names).encode()


def upstream_view(archive: bytes) -> dict[str, bytes]:
    """The files a release contributes, read from its codeload archive."""
    view = {}
    languages = {f"{LEDGER_API[1]}{language}/ParamName.hs": language for language in PARAM_NAME_LANGUAGES}
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as bundle:
        for member in bundle:
            if not member.isfile():
                continue
            relative = member.name.split("/", 1)[1] if "/" in member.name else member.name
            if relative.startswith(CONFORMANCE[1]) and relative.endswith(CONFORMANCE_SUFFIXES):
                view[CONFORMANCE[0] + "/" + relative.removeprefix(CONFORMANCE[1])] = read_member(bundle, member)
            elif relative.startswith(COST_MODEL[1]) and relative.removeprefix(COST_MODEL[1]) in COST_MODEL_FILES:
                view[COST_MODEL[0] + "/" + relative.removeprefix(COST_MODEL[1])] = read_member(bundle, member)
            elif relative in languages:
                names = param_names(read_member(bundle, member).decode())
                view[f"{LEDGER_API[0]}/{languages[relative]}-param-names.txt"] = names
    expected = {f"{COST_MODEL[0]}/{name}" for name in COST_MODEL_FILES} | {
        f"{LEDGER_API[0]}/{language}-param-names.txt" for language in PARAM_NAME_LANGUAGES}
    missing = sorted(expected - set(view))
    if missing:
        raise ReferenceError(f"archive lacks {missing}")
    return view


def read_member(bundle: tarfile.TarFile, member: tarfile.TarInfo) -> bytes:
    handle = bundle.extractfile(member)
    if handle is None:
        raise ReferenceError(f"unreadable archive member {member.name}")
    return handle.read()


def fetch(commit: str, expected: str | None, cache: Path) -> bytes:
    """The release archive, from the cache or codeload; refused unless its sha256 is the pinned one."""
    path = cache / f"{commit}.tar.gz"
    if path.is_file():
        data = path.read_bytes()
    else:
        with urllib.request.urlopen(ARCHIVE.format(commit=commit), timeout=120) as response:
            data = response.read()
        cache.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    if expected is not None and sha256(data) != expected:
        raise ReferenceError(f"archive {commit} has sha256 {sha256(data)}, pinned {expected}")
    return data


def read_manifest(reference: Path) -> dict:
    manifest = json.loads((reference / MANIFEST).read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ReferenceError("unsupported releases.json schema")
    return manifest


def files_under(directory: Path) -> dict[str, bytes]:
    if not directory.is_dir():
        return {}
    return {path.relative_to(directory).as_posix(): path.read_bytes()
            for path in sorted(directory.rglob("*")) if path.is_file()}


def tree(reference: Path) -> tuple[dict[str, bytes], dict[str, dict[str, bytes]]]:
    """The base files and each overlay's files, Scarab's own files left out."""
    base = {path: data for path, data in files_under(reference).items()
            if not path.startswith(OVERLAYS + "/") and not OWN.fullmatch(path)}
    overlays = {}
    if (reference / OVERLAYS).is_dir():
        for directory in sorted((reference / OVERLAYS).iterdir()):
            overlays[directory.name] = {path: data for path, data in files_under(directory).items()
                                        if not OWN.fullmatch(path)}
    return base, overlays


def release_view(reference: Path, tag: str, manifest: dict | None = None) -> dict[str, Path]:
    """Where each file of a release's view lives: its overlay's copy, else the base's."""
    manifest = manifest or read_manifest(reference)
    release = next((r for r in manifest["releases"] if r["tag"] == tag), None)
    if release is None:
        raise ReferenceError(f"no release {tag}")
    absent = set(release["absent"])
    view = {path.relative_to(reference).as_posix(): path for path in sorted(reference.rglob("*"))
            if path.is_file() and not path.relative_to(reference).as_posix().startswith(OVERLAYS + "/")
            and not OWN.fullmatch(path.relative_to(reference).as_posix())}
    view = {path: file for path, file in view.items() if path not in absent}
    overlay = reference / OVERLAYS / tag
    if overlay.is_dir():
        view.update({path.relative_to(overlay).as_posix(): path for path in sorted(overlay.rglob("*"))
                     if path.is_file() and not OWN.fullmatch(path.relative_to(overlay).as_posix())})
    return view


def upstream_part(view: dict[str, bytes], generated: dict[str, str]) -> dict[str, bytes]:
    return {path: data for path, data in view.items() if path not in generated}


def views(reference: Path, manifest: dict) -> dict[str, dict[str, bytes]]:
    return {release["tag"]: {path: file.read_bytes()
                             for path, file in release_view(reference, release["tag"], manifest).items()}
            for release in manifest["releases"]}


def check(reference: Path) -> list[str]:
    """Problems with the tree against releases.json; empty when every view is as recorded."""
    manifest = read_manifest(reference)
    tags = [release["tag"] for release in manifest["releases"]]
    problems = []
    if manifest["base"] not in tags:
        problems.append(f"base {manifest['base']} is not a release")
    base, overlays = tree(reference)
    generated = {entry["path"]: entry["sha256"] for entry in manifest["generated"]}
    for tag, files in overlays.items():
        if tag not in tags or tag == manifest["base"]:
            problems.append(f"overlay {tag} is not an older release")
        absent = next((set(r["absent"]) for r in manifest["releases"] if r["tag"] == tag), set())
        for path, data in files.items():
            if base.get(path) == data:
                problems.append(f"overlay {tag}/{path} repeats the base")
            if path in generated:
                problems.append(f"overlay {tag}/{path} is a generated file, shared by every release")
            if path in absent:
                problems.append(f"overlay {tag}/{path} is also listed as absent")
    if manifest["base"] in [r["tag"] for r in manifest["releases"] if r["absent"]]:
        problems.append("the base release has absent files")
    for release in manifest["releases"]:
        missing = sorted(set(release["absent"]) - set(base))
        if missing:
            problems.append(f"{release['tag']}: absent files not in the base: {missing[:4]}")
    for tag, view in views(reference, manifest).items():
        release = next(r for r in manifest["releases"] if r["tag"] == tag)
        own = upstream_part(view, generated)
        if len(own) != release["files"] or digest(own) != release["digest"]:
            problems.append(f"{tag}: view has {len(own)} upstream files with digest {digest(own)}, "
                            f"recorded {release['files']} and {release['digest']}")
        for path, expected in generated.items():
            if path not in view or sha256(view[path]) != expected:
                problems.append(f"{tag}: generated {path} is missing or changed")
    for path in generated:
        source = re.sub(r"\.flat\.cbor\.hex$", ".uplc", path)
        programs = {tag: view.get(source) for tag, view in views(reference, manifest).items()}
        if None in programs.values() or len(set(programs.values())) != 1:
            problems.append(f"generated {path}: its program {source} is not the same in every release")
    return problems


def verify(reference: Path, cache: Path) -> list[str]:
    """Differences between each recorded view and the view read from its pinned archive."""
    manifest = read_manifest(reference)
    generated = {entry["path"] for entry in manifest["generated"]}
    problems = []
    for tag, view in views(reference, manifest).items():
        release = next(r for r in manifest["releases"] if r["tag"] == tag)
        expected = upstream_view(fetch(release["commit"], release["archiveSha256"], cache))
        actual = {path: data for path, data in view.items() if path not in generated}
        for path in sorted(set(expected) | set(actual)):
            if expected.get(path) != actual.get(path):
                state = "missing" if path not in actual else "extra" if path not in expected else "differs"
                problems.append(f"{tag}: {path} {state}")
    return problems


def write_files(directory: Path, files: dict[str, bytes]) -> None:
    for path, data in files.items():
        target = directory / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def add_release(reference: Path, tag: str, commit: str, archive: bytes) -> None:
    """Make the release the base; every older release keeps only what differs from it."""
    manifest = read_manifest(reference)
    if any(release["tag"] == tag for release in manifest["releases"]):
        raise ReferenceError(f"release {tag} is already recorded")
    generated = {entry["path"]: entry["sha256"] for entry in manifest["generated"]}
    old = views(reference, manifest)
    new = upstream_view(archive)
    for path in generated:
        source = re.sub(r"\.flat\.cbor\.hex$", ".uplc", path)
        if any(view.get(source) != new.get(source) for view in old.values()):
            raise ReferenceError(f"{source} changed in {tag}: regenerate {path} before adding the release")
    sidecars = {path: (reference / path).read_bytes() for path in generated}
    keep = {path: (reference / path).read_bytes() for path in files_under(reference) if OWN.fullmatch(path)}
    staging = Path(tempfile.mkdtemp(prefix="plutus-reference-", dir=reference.parent))
    try:
        write_files(staging, {**new, **sidecars, **keep})
        releases = []
        for release in manifest["releases"]:
            view = upstream_part(old[release["tag"]], generated)
            overlay = {path: data for path, data in view.items() if new.get(path) != data}
            write_files(staging / OVERLAYS / release["tag"], overlay)
            releases.append(dict(release, absent=sorted(set(new) - set(view))))
        releases.append({"tag": tag, "commit": commit, "archiveSha256": sha256(archive),
                         "files": len(new), "digest": digest(new), "absent": []})
        manifest = dict(manifest, base=tag, releases=releases)
        (staging / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        shutil.rmtree(reference)
        os.replace(staging, reference)
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--cache", type=Path,
                        default=Path(os.environ.get("SCARAB_CACHE_DIR", ROOT / ".cache")) / "scarab/plutus-reference")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("check")
    commands.add_parser("verify")
    adding = commands.add_parser("add-release")
    adding.add_argument("tag")
    adding.add_argument("commit")
    adding.add_argument("--archive", type=Path, help="a local copy of the codeload archive")
    args = parser.parse_args()
    reference = args.root.resolve() / REFERENCE
    try:
        if args.command == "add-release":
            archive = args.archive.read_bytes() if args.archive else fetch(args.commit, None, args.cache)
            add_release(reference, args.tag, args.commit, archive)
            problems = check(reference)
        elif args.command == "verify":
            problems = check(reference) + verify(reference, args.cache)
        else:
            problems = check(reference)
    except (ReferenceError, OSError, KeyError, ValueError, tarfile.TarError) as error:
        print(f"plutus reference: {error}", file=sys.stderr)
        return 1
    for problem in problems:
        print(f"plutus reference: {problem}", file=sys.stderr)
    if problems:
        return 1
    manifest = read_manifest(reference)
    print(f"plutus reference: base {manifest['base']}, releases "
          + ", ".join(f"{r['tag']} ({r['files']} files, {len(r['absent'])} absent)" for r in manifest["releases"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
