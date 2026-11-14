# SPDX-License-Identifier: Apache-2.0
"""Fetch the pinned inputs of a scenario (`pins.json`), each into a directory of its own, and verify it.

Everything fetched is untrusted data: nothing in it is run, Git checks it out without symbolic links, hooks or
submodules, a tarball is checked against its sha256 before it is opened and unpacked with the `data` filter (no
absolute or escaping paths, no links out, no devices), and a file is written only under its own directory. A
directory is reused only when the record written beside it after its verification names the same pin.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Callable, Sequence

PINS = Path(__file__).resolve().parent / "pins.json"
COMMIT = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
TIMEOUT_SECONDS = 120
USER_AGENT = "scarab-conformance-runner"


class InputError(ValueError):
    """A pin is malformed, or what was fetched does not match it."""


@dataclass(frozen=True)
class Pin:
    name: str
    kind: str
    spec: dict

    @property
    def identity(self) -> str:
        """What the pin fixes: a commit or a sha256."""
        return self.spec.get("commit") or self.spec["sha256"]

    @property
    def directory(self) -> str:
        """The input's directory name under the inputs root: the pin's name and the start of its identity."""
        return f"{self.name}-{self.identity[:12]}"

    def record(self) -> dict:
        """What the provenance of a report says about this input."""
        keep = {key: value for key, value in self.spec.items() if key != "files"}
        if "files" in self.spec:
            keep["files"] = dict(sorted(self.spec["files"].items()))
        return {"name": self.name, **keep}


def _relative(path: str) -> PurePosixPath:
    pure = PurePosixPath(path)
    if pure.is_absolute() or not pure.parts or any(part in ("", ".", "..") for part in pure.parts):
        raise InputError(f"not a plain relative path: {path!r}")
    return pure


def check_pin(name: str, spec: dict) -> Pin:
    kind = spec.get("kind")
    if kind == "git":
        if not REPOSITORY.match(spec.get("repository", "")) or not COMMIT.match(spec.get("commit", "")):
            raise InputError(f"{name}: a git pin needs a repository owner/name and a full commit")
        if not spec.get("paths"):
            raise InputError(f"{name}: a git pin names the paths it checks out")
        for path in spec["paths"]:
            _relative(path)
    elif kind == "files":
        if not REPOSITORY.match(spec.get("repository", "")) or not COMMIT.match(spec.get("commit", "")):
            raise InputError(f"{name}: a files pin needs a repository owner/name and a full commit")
        _relative(spec.get("root", ""))
        if not spec.get("files"):
            raise InputError(f"{name}: a files pin lists its files")
        for path, digest in spec["files"].items():
            _relative(path)
            if not SHA256.match(digest):
                raise InputError(f"{name}: {path} needs a sha256")
    elif kind == "tarball":
        if not spec.get("url", "").startswith("https://") or not SHA256.match(spec.get("sha256", "")):
            raise InputError(f"{name}: a tarball pin needs an https URL and a sha256")
    else:
        raise InputError(f"{name}: unknown kind {kind!r}")
    return Pin(name, kind, spec)


def load_pins(path: Path = PINS) -> dict[str, Pin]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    return {name: check_pin(name, spec) for name, spec in raw.items() if not name.startswith("_")}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


Run = Callable[[Sequence[str]], str]


def run_command(argv: Sequence[str]) -> str:
    """Run a command without a shell or a terminal prompt; its standard output, or `CalledProcessError`."""
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    return subprocess.run(list(argv), check=True, text=True, capture_output=True, env=env).stdout


def git_commands(pin: Pin, target: Path) -> list[list[str]]:
    """The commands that fetch a git pin's paths at its commit into `target`, an empty directory. The fetch is
    shallow and sparse; the checkout writes symbolic links as plain files and runs no hook."""
    git = ["git", "-C", str(target), "-c", "core.symlinks=false", "-c", "core.hooksPath=/dev/null",
           "-c", "credential.helper=", "-c", "protocol.allow=never", "-c", "protocol.https.allow=always",
           "-c", "submodule.recurse=false", "-c", "advice.detachedHead=false"]
    patterns = [f"/{PurePosixPath(path)}/" for path in pin.spec["paths"]]
    return [
        ["git", "init", "-q", str(target)],
        git + ["config", "core.symlinks", "false"],
        git + ["sparse-checkout", "set", "--no-cone", *patterns],
        git + ["fetch", "-q", "--depth", "1", "--filter=blob:none", "--no-tags",
               f"https://github.com/{pin.spec['repository']}", pin.spec["commit"]],
        git + ["checkout", "-q", "FETCH_HEAD"],
        git + ["rev-parse", "HEAD"],
    ]


def fetch_git(pin: Pin, target: Path, run: Run = run_command) -> None:
    commands = git_commands(pin, target)
    head = ""
    for command in commands:
        head = run(command)
    if head.strip() != pin.spec["commit"]:
        raise InputError(f"{pin.name}: checked out {head.strip()!r}, not the pinned {pin.spec['commit']}")
    missing = [path for path in pin.spec["paths"] if not (target / path).is_dir()]
    if missing:
        raise InputError(f"{pin.name}: {', '.join(missing)} absent at {pin.spec['commit']}")


def download(url: str, target: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response, target.open("wb") as out:
        shutil.copyfileobj(response, out)


def fetch_files(pin: Pin, target: Path, get: Callable[[str, Path], None] = download) -> None:
    """Each listed file from its raw URL at the pinned commit, kept under its path below `target` once its sha256
    is the pinned one."""
    base = f"https://raw.githubusercontent.com/{pin.spec['repository']}/{pin.spec['commit']}/{pin.spec['root']}"
    for path, expected in sorted(pin.spec["files"].items()):
        destination = target.joinpath(*_relative(path).parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        partial = destination.with_name(destination.name + ".part")
        get(f"{base}/{path}", partial)
        actual = sha256_file(partial)
        if actual != expected:
            partial.unlink()
            raise InputError(f"{pin.name}: {path} has sha256 {actual}, not the pinned {expected}")
        partial.rename(destination)


def decompress_zstd(source: Path, target: Path) -> None:
    """`source`, a zstd frame, decompressed into `target`: with Python's own zstd from 3.14, else the `zstd`
    command."""
    try:
        from compression import zstd  # type: ignore[import-not-found]
    except ImportError:
        with target.open("wb") as out:
            subprocess.run(["zstd", "-q", "-d", "-c", "--", str(source)], stdout=out, check=True)
        return
    with zstd.open(source, "rb") as stream, target.open("wb") as out:
        shutil.copyfileobj(stream, out)


def extract_tar(archive: Path, target: Path) -> int:
    """Unpack `archive` into `target` with the `data` filter, which refuses absolute and escaping paths, links that
    leave `target` and special files; the number of members."""
    with tarfile.open(archive, "r:") as tar:
        members = tar.getmembers()
        tar.extractall(target, filter="data")
    return len(members)


def fetch_tarball(pin: Pin, target: Path, holding: Path, get: Callable[[str, Path], None] = download) -> None:
    """The tarball, checked against its sha256 before it is opened, unpacked into `target`. The archive is kept in
    `holding`, outside `target`."""
    holding.mkdir(parents=True, exist_ok=True)
    archive = holding / f"{pin.directory}.tar.zst"
    get(pin.spec["url"], archive)
    actual = sha256_file(archive)
    if actual != pin.spec["sha256"]:
        archive.unlink()
        raise InputError(f"{pin.name}: {pin.spec['url']} has sha256 {actual}, not the pinned {pin.spec['sha256']}")
    tar = holding / f"{pin.directory}.tar"
    decompress_zstd(archive, tar)
    extract_tar(tar, target)
    tar.unlink()


def fetch(pin: Pin, root: Path, run: Run = run_command, get: Callable[[str, Path], None] = download) -> Path:
    """The verified directory of `pin` under `root`, fetched unless a record beside it names the same pin."""
    target = root / pin.directory
    record = root / f"{pin.directory}.verified.json"
    if record.is_file() and target.is_dir() and json.loads(record.read_text(encoding="utf-8")) == pin.record():
        return target
    if record.exists():
        record.unlink()
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    if pin.kind == "git":
        fetch_git(pin, target, run)
    elif pin.kind == "files":
        fetch_files(pin, target, get)
    else:
        fetch_tarball(pin, target, root / "archives", get)
    record.write_text(json.dumps(pin.record(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return target
