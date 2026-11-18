#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Splits node/assembly's JAR in two for ../config-thin-catalog, each entry copied unchanged.

    split_jar.py ASSEMBLY OWN LIBS

OWN gets every entry under xyz/ (Scarab's own classes and resources) and the manifest; LIBS gets every other entry
and the manifest without its Main-Class, so that its multi-release entries still resolve. On one classpath the two
hold exactly what the assembly holds. The script refuses an assembly without entries under xyz/ or with any of
Scarab's classes in META-INF/versions/, where the split would change which class a release resolves, and checks
both outputs against the source before it ends.
"""

import copy
import sys
import zipfile

MANIFEST = "META-INF/MANIFEST.MF"


def without_main_class(manifest: bytes) -> bytes:
    lines = manifest.decode("utf-8").split("\r\n" if b"\r\n" in manifest else "\n")
    kept, skipping = [], False
    for line in lines:
        if line.startswith(" "):
            if not skipping:
                kept.append(line)
            continue
        skipping = line.startswith("Main-Class:")
        if not skipping:
            kept.append(line)
    return ("\r\n" if b"\r\n" in manifest else "\n").join(kept).encode("utf-8")


def main(assembly: str, own: str, libs: str) -> None:
    with zipfile.ZipFile(assembly) as source:
        infos = source.infolist()
        names = [info.filename for info in infos]
        if len(set(names)) != len(names):
            sys.exit("split_jar: the assembly has duplicate entries")
        if not any(name.startswith("xyz/") and name.endswith(".class") for name in names):
            sys.exit("split_jar: the assembly has no classes under xyz/")
        if any(name.startswith("META-INF/versions/") and "/xyz/" in name for name in names):
            sys.exit("split_jar: Scarab classes in META-INF/versions/; the split would change their resolution")
        if MANIFEST not in names:
            sys.exit("split_jar: the assembly has no manifest")
        manifest = source.read(MANIFEST)
        with zipfile.ZipFile(own, "w") as own_jar, zipfile.ZipFile(libs, "w") as libs_jar:
            # writestr records its offsets in the ZipInfo it is given, so each write takes a copy of the source's.
            # The directory entry keeps the assembly's date (or the manifest's), so one assembly gives the same JARs.
            manifest_info = source.getinfo(MANIFEST)
            meta_info = (source.getinfo("META-INF/") if "META-INF/" in names
                         else zipfile.ZipInfo("META-INF/", manifest_info.date_time))
            own_jar.writestr(copy.copy(meta_info), b"")
            libs_jar.writestr(copy.copy(meta_info), b"")
            own_jar.writestr(copy.copy(manifest_info), manifest)
            libs_jar.writestr(copy.copy(manifest_info), without_main_class(manifest))
            counts = {"own": 0, "libs": 0}
            for info in infos:
                if info.filename in (MANIFEST, "META-INF/"):
                    continue
                side = "own" if info.filename.startswith("xyz/") else "libs"
                (own_jar if side == "own" else libs_jar).writestr(copy.copy(info), source.read(info))
                counts[side] += 1
        check(source, own, libs, manifest)
    print(f"split_jar: {counts['own']} entries to {own}, {counts['libs']} to {libs}")


def check(source: zipfile.ZipFile, own: str, libs: str, manifest: bytes) -> None:
    """Every entry of the source, by name and CRC, is in exactly one output, which reads back intact."""
    expected = {info.filename: info.CRC for info in source.infolist()}
    seen: dict[str, int] = {}
    for path in (own, libs):
        with zipfile.ZipFile(path) as jar:
            bad = jar.testzip()
            if bad is not None:
                sys.exit(f"split_jar: {path} fails its check at {bad}")
            for info in jar.infolist():
                if info.filename in seen and info.filename not in (MANIFEST, "META-INF/"):
                    sys.exit(f"split_jar: {info.filename} is in both outputs")
                seen[info.filename] = info.CRC
            if path == own and jar.read(MANIFEST) != manifest:
                sys.exit(f"split_jar: {path}'s manifest differs from the assembly's")
    missing = sorted(name for name in expected if name not in seen)
    changed = sorted(name for name, crc in expected.items() if name != MANIFEST and seen.get(name, crc) != crc)
    if missing or changed:
        sys.exit(f"split_jar: missing {missing[:3]}, changed {changed[:3]}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("usage: split_jar.py ASSEMBLY OWN LIBS")
    main(*sys.argv[1:])
