#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""No-ADX/BMI2 smoke check of an x86-64 shared library (ADR 0012, ADR 0020).

A library may contain ADX/BMI2 code only behind a run-time CPU switch: every function that holds
such an instruction must be reachable only through references that the switch guards (by default
blst's `__blst_platform_cap`, tested within the two instructions before the reference), directly or
through functions that are themselves reachable only that way, and none may be exported. Any other reference (call, jump, address taken) is an
ungated entry, and the library would die with SIGILL on a processor without the extension.

This reads the disassembly; it does not execute the library and does not simulate an older CPU.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

EXTENSION = frozenset({'adcx', 'adox', 'mulx', 'shlx', 'shrx', 'sarx', 'rorx', 'pdep', 'pext', 'bzhi'})
GATE = '__blst_platform_cap'
FUNCTION = re.compile(r'^[0-9a-f]+ <(.+)>:$')
INSTRUCTION = re.compile(r'^\s+[0-9a-f]+:\t(\S+)\s*(.*)$')
REFERENCE = re.compile(r'<([^>+]+)(?:\+0x[0-9a-f]+)?>')


def functions(disassembly):
    """[(name, [(mnemonic, operands)])] in file order."""
    found = []
    for line in disassembly.splitlines():
        head = FUNCTION.match(line)
        if head:
            found.append((head.group(1), []))
            continue
        instruction = INSTRUCTION.match(line)
        if instruction and found:
            found[-1][1].append((instruction.group(1), instruction.group(2)))
    return found


def analyse(disassembly, exported=frozenset(), gate=GATE):
    """Counts, the extension functions and every ungated entry into them.

    The gated region is the greatest set of functions that are not exported, are referenced at all,
    and are referenced from outside the region only through a gated reference. Every extension
    function must lie in it; an unreferenced function counts as reachable from outside, because a
    data relocation (a function pointer table) does not show in the disassembly.
    """
    listed = functions(disassembly)
    counts = {}
    extension = set()
    edges = []
    for name, body in listed:
        for index, (mnemonic, operands) in enumerate(body):
            if mnemonic in EXTENSION:
                counts[mnemonic] = counts.get(mnemonic, 0) + 1
                extension.add(name)
            for target in REFERENCE.findall(operands):
                if target != name:
                    gated = any(gate in o for _, o in body[max(0, index - 2):index])
                    edges.append((name, target, gated, f'{mnemonic} {operands}'.strip()))
    referenced = {target for _, target, _, _ in edges}
    region = {name for name, _ in listed if name in referenced and name not in exported}
    changed = True
    while changed:
        changed = False
        for source, target, gated, _ in edges:
            if target in region and not gated and source not in region:
                region.discard(target)
                changed = True
    ungated = [{'from': source, 'to': target, 'instruction': text}
               for source, target, gated, text in edges
               if target in extension - region and not gated and source not in region]
    ungated += [{'from': 'dynamic symbol table', 'to': name, 'instruction': 'exported'}
                for name in sorted(extension & exported)]
    ungated += [{'from': 'no reference', 'to': name, 'instruction': 'unreferenced'}
                for name in sorted(extension - referenced - exported)]
    return {'instructions': dict(sorted(counts.items())), 'extensionFunctions': len(extension),
            'gatedRegion': len(region & {name for name, _ in listed}), 'ungatedEntries': ungated}


def exported_symbols(path):
    output = subprocess.run(['objdump', '-T', str(path)], check=True, capture_output=True, text=True).stdout
    return frozenset(line.split()[-1] for line in output.splitlines() if '.text' in line)


def disassemble(path):
    return subprocess.run(['objdump', '-d', '--no-show-raw-insn', str(path)],
                          check=True, capture_output=True, text=True).stdout


def check(path, member=None, gate=GATE):
    """Analyse `path`, or the `member` of the jar at `path`."""
    if member is None:
        return analyse(disassemble(path), exported_symbols(path), gate)
    with tempfile.TemporaryDirectory() as scratch, zipfile.ZipFile(path) as jar:
        library = Path(scratch) / Path(member).name
        library.write_bytes(jar.read(member))
        return analyse(disassemble(library), exported_symbols(library), gate)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('library', type=Path, help='a shared library, or a jar with --member')
    parser.add_argument('--member', help='the library inside the jar')
    parser.add_argument('--gate', default=GATE, help='the CPU capability symbol a gated entry tests')
    args = parser.parse_args()
    result = check(args.library, args.member, args.gate)
    print(json.dumps(result, indent=2))
    return 1 if result['ungatedEntries'] else 0


if __name__ == '__main__':
    sys.exit(main())
