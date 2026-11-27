#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build the libsodium the reference node links, as a shared library for the differential fuzz targets.

    sodium_oracle.py --download --out DIR [--download-dir DIR]
    sodium_oracle.py /abs/path/libsodium-dbb48cce.tar.gz --out DIR

The reference's Ed25519 and VRF answers come from the Cardano fork of libsodium, IntersectMBO/libsodium at
`COMMIT`, which iohk-nix builds as `libsodium-vrf` for both cardano-node 11.1.3 and 11.2.1
(docs/running/fuzzing.md, "Differential targets against libsodium", has the evidence). This script builds that
commit from GitHub's source archive of it, pinned by size and SHA-256, with the configure flags and `CFLAGS` that
`fixtures/witness/archive/pinned-source-oracle/provenance.json` records (no `ED25519_COMPAT`), and checks in the
build log that `open.c` was compiled so: every compile line of it has those CFLAGS and no definition of the macro in
any spelling. That check reads text; the decisive one is the binding's self-test when the fuzz targets load the
library (`Sodium.scala`): the RFC 8032 test-1 signature with L added to S, which a build with `ED25519_COMPAT`
accepts, must be refused. It writes DIR/libsodium.so, a copy of the library it built, and DIR/build.log, and prints
the library's path and SHA-256 and the compiler's version. The library's digest depends on the compiler and is not
pinned; the source is.

Without --download nothing is fetched: the archive must exist locally. With --download the pinned URL is fetched
over HTTPS, every redirect included (to DIR/<file>.part), and the size and SHA-256 are verified before the file is
renamed into place or used; an already present verified file is reused. The archive is read once: the bytes whose
digest is checked are the bytes extracted, and only if every member is a file or a directory under its one top
directory. It needs a C compiler (`gcc`) and `make`, and installs nothing.

The library is a test oracle: nothing of the node, of `crypto`'s main sources or of an ordinary test run loads it,
and nothing of libsodium is kept in the repository.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

COMMIT = "dbb48cce5429cb6585c9034f002568964f1ce567"
ARCHIVE_NAME = "libsodium-dbb48cce.tar.gz"
ARCHIVE_URL = "https://codeload.github.com/IntersectMBO/libsodium/tar.gz/" + COMMIT
ARCHIVE_SIZE = 2073757
ARCHIVE_SHA256 = "e4f29ae3c16037e484bb69e3fa22a5565c42adf497f8f88e61ff8d9486ab863e"
TOP = "libsodium-" + COMMIT
CFLAGS = "-O2 -g0 -UED25519_COMPAT"
CONFIGURE_FLAGS = ("--enable-shared", "--disable-static", "--disable-dependency-tracking")
LIBRARY = Path("src/libsodium/.libs/libsodium.so.23.3.0")
OPEN_C = "crypto_sign/ed25519/ref10/open.c"


def default_download_dir():
    """`${SCARAB_DATA_DIR:-/tmp/scarab}/sodium`, where run data goes (AGENTS.md)."""
    return Path(os.environ.get("SCARAB_DATA_DIR") or "/tmp/scarab") / "sodium"


def verify_archive(data):
    """Raise ValueError unless the archive's bytes have exactly the pinned size and SHA-256."""
    if len(data) != ARCHIVE_SIZE:
        raise ValueError("libsodium archive size mismatch")
    if hashlib.sha256(data).hexdigest() != ARCHIVE_SHA256:
        raise ValueError("libsodium archive checksum mismatch")


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    """Follows a redirect only to an HTTPS URL, so that no hop of the chain is plain HTTP."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not newurl.startswith("https://"):
            raise ValueError("download redirected off HTTPS: " + newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_archive(directory, opener=None):
    """Fetch the pinned URL into `directory` (reused when present) and return the verified archive's bytes."""
    target = directory / ARCHIVE_NAME
    directory.mkdir(parents=True, exist_ok=True)
    if target.exists():
        data = target.read_bytes()
        try:
            verify_archive(data)
            return data
        except ValueError:
            target.unlink()
    opener = opener or urllib.request.build_opener(HttpsRedirects())
    request = urllib.request.Request(ARCHIVE_URL, headers={"User-Agent": "scarab"})
    with opener.open(request, timeout=60) as response:
        if not response.geturl().startswith("https://"):
            raise ValueError("download left HTTPS: " + response.geturl())
        data = response.read(ARCHIVE_SIZE + 1)
    verify_archive(data)
    partial = directory / (ARCHIVE_NAME + ".part")
    partial.write_bytes(data)
    partial.rename(target)
    return data


def safe_members(members):
    """Raise ValueError unless every member is a regular file or a directory under the archive's one top
    directory, with a relative path that has no `..` and no backslash."""
    for member in members:
        path = PurePosixPath(member.name)
        if path.is_absolute() or ".." in path.parts or "\\" in member.name or path.parts[:1] != (TOP,):
            raise ValueError("unsafe archive path: " + member.name)
        if not (member.isfile() or member.isdir()):
            raise ValueError("archive member is neither a file nor a directory: " + member.name)


def extract_source(data, directory):
    """Extract the archive's bytes, verified first, into `directory` and return the source tree."""
    verify_archive(data)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        safe_members(archive.getmembers())
        archive.extractall(directory, filter="data")
    return directory / TOP


def compile_line_without_compat(line):
    """Whether one compile command has the recorded CFLAGS, in order, and names ED25519_COMPAT nowhere but in their
    `-UED25519_COMPAT`: no `-DED25519_COMPAT`, `-D ED25519_COMPAT`, value or quoting defines it. A line that does not
    parse as shell words is refused."""
    try:
        words = shlex.split(line)
    except ValueError:
        return False
    flags = CFLAGS.split()
    ordered = any(words[i:i + len(flags)] == flags for i in range(len(words)))
    return ordered and [w for w in words if "ED25519_COMPAT" in w] == ["-UED25519_COMPAT"]


def compiled_without_compat(log):
    """Whether the build log compiles `open.c`, and every command that compiles it passes
    `compile_line_without_compat`."""
    lines = [line for line in log.splitlines() if OPEN_C in line and " -c " in f" {line} "]
    return bool(lines) and all(compile_line_without_compat(line) for line in lines)


def build(source, work, jobs):
    """Configure and make `source` out of tree in `work`; return the library path and the build log."""
    work.mkdir(parents=True, exist_ok=True)
    env = {k: v for k, v in os.environ.items() if k not in ("CPPFLAGS", "LDFLAGS")}
    env.update(CC="gcc", CFLAGS=CFLAGS)
    configure = [str(source / "configure"), "--prefix=" + str(work / "install"), *CONFIGURE_FLAGS]
    logs = []
    for argv in (configure, ["make", f"-j{jobs}", "V=1"]):
        done = subprocess.run(argv, cwd=work, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        logs.append(done.stdout)
        if done.returncode != 0:
            raise RuntimeError(f"{argv[0]} failed with status {done.returncode}:\n{done.stdout[-4000:]}")
    log = "\n".join(logs)
    if not compiled_without_compat(log):
        raise RuntimeError(f"{OPEN_C} was not compiled with {CFLAGS!r} alone")
    return work / LIBRARY, log


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("archive", type=Path, nargs="?", help="the already downloaded source archive")
    parser.add_argument("--download", action="store_true",
                        help="fetch the pinned URL and verify size and SHA-256 before use")
    parser.add_argument("--download-dir", type=Path, default=default_download_dir(),
                        help="where --download stores the archive (default ${SCARAB_DATA_DIR:-/tmp/scarab}/sodium)")
    parser.add_argument("--out", type=Path, required=True, help="where libsodium.so and build.log are written")
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 2, help="parallel make jobs")
    args = parser.parse_args()
    if args.download == (args.archive is not None):
        parser.error("give either an archive path or --download")
    data = download_archive(args.download_dir) if args.download else args.archive.read_bytes()
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="sodium-oracle-") as scratch:
        root = Path(scratch)
        source = extract_source(data, root / "src")
        library, log = build(source, root / "build", args.jobs)
        (args.out / "build.log").write_text(log)
        shutil.copyfile(library, args.out / "libsodium.so")
    output = (args.out / "libsodium.so").resolve()
    with output.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    compiler = subprocess.run(["gcc", "--version"], stdout=subprocess.PIPE, text=True, check=True).stdout
    print(f"libsodium {COMMIT} built by {compiler.splitlines()[0]}", file=sys.stderr)
    print(f"SHA-256 {digest}", file=sys.stderr)
    print(output)


if __name__ == "__main__":
    main()
