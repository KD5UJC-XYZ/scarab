#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The BLS12-381 native libraries a JAR carries are jblst's, as pinned (ADR 0025).

Scarab takes libblst from `tech.pegasys:jblst` (blst v0.3.17) and pins the sha-256 of each platform's
library in `Blst.pinned` (`crypto/.../bls/Blst.scala`), which the node checks at start-up. This
script checks a JAR (the assembly, or the jblst jar itself) against those pins, and runs the
ADX/BMI2 check of `check_native_isa.py` on the Linux/amd64 library: its extension code must sit
behind blst's run-time CPU switch, or the node would die with SIGILL on an older x86-64 processor.

    python3 scripts/check_native_identity.py <jar> [--output <dir>]

It writes `receipt.json` to the output directory: the jblst coordinates, the pinned and the packaged
hash of each library, and the ISA result. The release candidate binds that receipt to its JAR.
Pure standard library, plus `objdump` for the ISA check.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import sys
import zipfile

import check_native_isa

ROOT = Path(__file__).resolve().parents[1]
PINS = 'crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/bls/Blst.scala'
DEPENDENCIES = 'project/Dependencies.scala'
PREFIX = 'supranational/blst/'
LINUX_AMD64 = 'Linux/amd64/libblst.so'
LIBRARY = 'tech.pegasys:jblst'
BLST_COMMIT = '54e6e55674722fc2797ebb4bbb71b26d881eb4b8'


def pins(root=ROOT):
    """The pinned sha-256 of each library resource, by its name under `supranational/blst/`."""
    found = dict(re.findall(r'"([A-Za-z]+/[A-Za-z0-9_]+/[A-Za-z0-9_.]+)" -> "([a-f0-9]{64})"',
                            (root / PINS).read_text()))
    if LINUX_AMD64 not in found:
        raise ValueError(f'{PINS} pins no {LINUX_AMD64}')
    return found


def jblst_version(root=ROOT):
    found = re.findall(r'val jblst = "([^"]+)"', (root / DEPENDENCIES).read_text())
    if len(found) != 1:
        raise ValueError(f'{DEPENDENCIES} must declare one jblst version')
    return found[0]


def packaged(jar):
    """The sha-256 of each `supranational/blst/<os>/<arch>/<library>` entry of the JAR."""
    with zipfile.ZipFile(jar) as archive:
        return {name[len(PREFIX):]: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
                if name.startswith(PREFIX) and name.count('/') == 4 and not name.endswith('/')}


def verify(jar, root=ROOT):
    """The receipt; `status` is `passed` only when every pinned library is packaged with its pinned
    bytes, nothing unpinned is, and the Linux/amd64 library has no ungated ADX/BMI2 entry."""
    expected, found = pins(root), packaged(jar)
    receipt = {'schemaVersion': 1, 'status': 'failed', 'library': LIBRARY, 'version': jblst_version(root),
               'blstCommit': BLST_COMMIT, 'jarSha256': hashlib.sha256(Path(jar).read_bytes()).hexdigest(),
               'resources': {name: {'pinned': expected.get(name), 'packaged': found.get(name)}
                             for name in sorted(set(expected) | set(found))}}
    problems = [name for name, pair in receipt['resources'].items() if pair['pinned'] != pair['packaged']]
    isa = check_native_isa.check(Path(jar), member=PREFIX + LINUX_AMD64) if LINUX_AMD64 in found else None
    receipt['isa'] = isa and {k: isa[k] for k in ('instructions', 'extensionFunctions', 'gatedRegion')} | {
        'ungatedEntries': len(isa['ungatedEntries'])}
    if problems:
        receipt['error'] = 'packaged libraries differ from the pins: ' + ', '.join(problems)
    elif isa is None or isa['ungatedEntries']:
        receipt['error'] = f'{LINUX_AMD64} has ADX/BMI2 code outside the CPU switch'
    else:
        receipt['status'] = 'passed'
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('jar', type=Path, help='a JAR carrying supranational/blst/ libraries')
    parser.add_argument('--output', type=Path, help='directory for receipt.json')
    args = parser.parse_args()
    receipt = verify(args.jar)
    if args.output:
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))
    return 0 if receipt['status'] == 'passed' else 1


if __name__ == '__main__':
    sys.exit(main())
