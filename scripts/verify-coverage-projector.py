#!/usr/bin/env python3
"""Offline regression checks for the bounded, pinned coverage fixture importer."""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'scripts/project-coverage-fixtures.py'
spec = importlib.util.spec_from_file_location('coverage_projector_tests', SCRIPT)
p = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = p
spec.loader.exec_module(p)


def rejected(action, message):
    try:
        action()
    except (ValueError, OSError):
        return
    raise ValueError('negative unexpectedly accepted: ' + message)


def main():
    inputs = p.read_inputs()
    m = p.load_decoder(inputs)
    first = p.project(inputs, m)
    second = p.project(inputs, m)
    p.require(first == second, 'projection nondeterminism')
    p.require({name: hashlib.sha256(data).hexdigest() for name, data in first.items()} == p.EXPECTED_OUTPUTS,
              'fixed artifact hashes')
    for name, data in first.items():
        p.require((p.BASE / name).read_bytes() == data, 'checked-in artifact: ' + name)
    # Generic decoder suite covers malformed/truncated/trailing CBOR and duplicate
    # map identities. Also explicitly exercise depth and file resource limits here.
    rejected(lambda: m.decode(b'\x81' * 66 + b'\x00'), 'CBOR depth')
    rejected(lambda: m.decode(b'\x00' * 1_000_001), 'CBOR input bytes')
    rejected(lambda: m.decode(bytes.fromhex('a282410000008241000001')), 'duplicate compound map key')
    with tempfile.TemporaryDirectory(prefix='coverage-projector-') as directory:
        path = Path(directory) / 'input'
        path.write_bytes(b'12345')
        rejected(lambda: p.bounded_read(path, 4), 'bounded reader')
        original_root, original_pins = p.ROOT, p.PINNED_INPUTS
        try:
            p.ROOT = Path(directory)
            p.PINNED_INPUTS = {'input': hashlib.sha256(b'12345').hexdigest()}
            p.require(p.read_inputs()['input'] == b'12345', 'pinned input positive')
            path.write_bytes(b'12346')
            rejected(p.read_inputs, 'tampered pinned input')
        finally:
            p.ROOT, p.PINNED_INPUTS = original_root, original_pins
    for options, accepted in ((['--check'], True), (['-O'], False)):
        args = [sys.executable, str(SCRIPT), '--check'] if accepted else [sys.executable, '-O', str(SCRIPT), '--check']
        result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=30)
        p.require((result.returncode == 0) == accepted, 'normal/optimized execution guard')
        if not accepted:
            p.require('Do not disable validation with -O' in result.stderr, 'optimization rejection reason')
    print('Coverage projector deterministic artifacts, immutable-input checks, parser bounds and -O guard passed.')


if __name__ == '__main__':
    main()
