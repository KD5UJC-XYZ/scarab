#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the generated GHC printable predicate; --record repeats the complete native comparison."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
FIXTURE = ROOT / "fixtures/reference/plutus-error-presentation/unicode-isprint.json"
SCALA = ROOT / "codec/src/main/scala/xyz/kd5ujc/scarab/codec/HaskellUnicode.scala"
IMAGE = "sha256:e061568cd4971962854e85f26910eb5094ff1c416567a547feb3ba6eff59c5da"
BASE_URL = "https://raw.githubusercontent.com/ghc/ghc/ghc-9.6.7-release/libraries/base/"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def validate(data):
    if data["unicodeVersion"] != "15.1.0" or data["codePointsCompared"] != 0x110000 or not data["completeClassificationsAgree"]:
        raise ValueError("incomplete native Unicode comparison")
    previous = -2
    for first, last in data["printableRanges"]:
        if not 0 <= first <= last <= 0x10ffff or first <= previous + 1:
            raise ValueError("invalid, overlapping or unmerged printable range")
        previous = last


def render(data):
    pairs = data["printableRanges"]
    rows = [", ".join(f"({first}, {last})" for first, last in pairs[i:i + 4]) for i in range(0, len(pairs), 4)]
    return '''// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import scala.annotation.tailrec

/** GHC's Unicode 15.1 printable predicate, generated from the complete native comparison in
  * fixtures/reference/plutus-error-presentation/unicode-isprint.json. Do not edit the ranges:
  * scripts/oracle/plutus/error-presentation/unicode.py regenerates and checks this view.
  */
private[scarab] object HaskellUnicode:
  private val printable = Vector(
''' + ",\n".join("    " + row for row in rows) + '''
  )

  def isPrint(codePoint: Int): Boolean =
    @tailrec
    def search(low: Int, high: Int): Boolean =
      if low > high then false
      else
        val middle = low + (high - low) / 2
        val (first, last) = printable(middle)
        if codePoint < first then search(low, middle - 1)
        else if codePoint > last then search(middle + 1, high)
        else true
    search(0, printable.size - 1)
'''


def record(data, cache):
    cache.mkdir(parents=True, exist_ok=True)
    files = {row["url"]: row for row in data["sources"]}
    paths = ["GHC/Unicode/Internal/Char/UnicodeData/GeneralCategory.hs", "GHC/Unicode/Internal/Bits.hs"]
    for relative in paths:
        url = BASE_URL + relative
        target = cache / relative
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(urllib.request.urlopen(url, timeout=60).read())
        expected = files.get(url)
        if expected and sha(target.read_bytes()) != expected["sha256"]:
            raise ValueError("native Unicode source identity changed: " + relative)
    with tempfile.TemporaryDirectory(prefix="scarab-796-unicode-") as scratch:
        compat = Path(scratch)
        target = compat / "GHC/Unicode/Internal/Bits.hs"
        target.parent.mkdir(parents=True)
        source = (cache / "GHC/Unicode/Internal/Bits.hs").read_text()
        expected_import = "import {-# SOURCE #-} GHC.ByteOrder"
        if source.count(expected_import) != 1:
            raise ValueError("GHC compatibility import no longer matches")
        target.write_text(source.replace(expected_import, "import GHC.ByteOrder"))
        command = ["docker", "run", "--rm", "-w", "/tmp", "--read-only", "--network", "none", "--tmpfs", "/tmp:exec",
                   "--label", "xyz.kd5ujc.scarab.kind=oracle", "--label", "xyz.kd5ujc.scarab.owner=796-native-oracles",
                   "-v", f"{cache.resolve()}:/source:ro", "-v", f"{compat}:/compat:ro", "-v", f"{HERE}:/probe:ro",
                   "--entrypoint", "sh", IMAGE, "-c",
                   "ghc -O0 -Wall -Werror -i/compat:/source -outputdir /tmp -o /tmp/probe /probe/UnicodeProbe.hs >/tmp/build.log 2>&1 && /tmp/probe || { cat /tmp/build.log; exit 1; }"]
        process = subprocess.run(command, capture_output=True, text=True)
        if process.returncode != 0:
            raise RuntimeError(process.stdout + process.stderr)
        result = process.stdout.splitlines()
    if len(result) != 3 or result[0] != "nativeVersion 15.1.0":
        raise ValueError("unexpected Unicode probe output")
    native = ast.literal_eval(result[1].removeprefix("native "))
    source = ast.literal_eval(result[2].removeprefix("ghc96Source "))
    if native != source:
        raise ValueError("official compiler source and API compiler classification disagree")
    data["printableRanges"] = native
    data["comparison"] = {"probeSha256": sha((HERE / "UnicodeProbe.hs").read_bytes()),
                          "apiImage": IMAGE, "compatibility": "Only the SOURCE boot-import pragma is removed in an isolated copy of the Bits helper; category-table and lookup code are unchanged.",
                          "outputSha256": sha(("\n".join(result) + "\n").encode())}
    FIXTURE.write_text(json.dumps(data, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--cache", type=Path, default=ROOT / ".cache/scarab/oracle-sources/ghc-9.6.7")
    args = parser.parse_args()
    data = json.loads(FIXTURE.read_text())
    if args.record:
        record(data, args.cache)
    validate(data)
    expected = render(data)
    if args.write:
        SCALA.write_text(expected)
    elif SCALA.read_text() != expected:
        raise ValueError("generated HaskellUnicode.scala is stale")
    print(f"{len(data['printableRanges'])} GHC printable ranges checked across {data['codePointsCompared']} code points")


if __name__ == "__main__":
    main()
