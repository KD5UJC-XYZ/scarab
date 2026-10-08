#!/usr/bin/env python3
"""Audit fail-closed regressions in a temporary root; no synthetic jar is executed."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
ORIGINAL = (ROOT / 'app/target/runtime-classpath.txt').read_text()
with tempfile.TemporaryDirectory(prefix='runtime-audit-') as temporary:
    work = Path(temporary)
    (work / 'scripts').mkdir()
    (work / 'app/target').mkdir(parents=True)
    (work / 'docs').mkdir()
    shutil.copy(ROOT / 'scripts/runtime-inventory.py', work / 'scripts/runtime-inventory.py')
    cases = [('native file', 'unexpected.jar', 'native/test.so', 'Native runtime dependency rejected'),
             ('wrong arithmetic hash', 'curve25519-elisabeth-0.1.3.jar', 'fake.txt', 'differs from inspected immutable pin'),
             ('wrong BC hash', 'bcprov-jdk18on-1.85.2.jar', 'fake.txt', 'Bouncy Castle runtime artifact differs from inspected immutable pin')]
    for label, filename, member, diagnostic in cases:
        jar = work / filename
        with zipfile.ZipFile(jar, 'w') as output:
            output.writestr(member, b'Harmless audit fixture; never executed')
        (work / 'app/target/runtime-classpath.txt').write_text(ORIGINAL + os.pathsep + str(jar))
        result = subprocess.run(['python3', str(work / 'scripts/runtime-inventory.py')],
                                capture_output=True, text=True, timeout=30)
        if result.returncode == 0 or diagnostic not in result.stderr:
            raise RuntimeError((label, result.returncode, result.stdout, result.stderr))
        print(f'PASS: {label} rejected')
    without_weave = os.pathsep.join(p for p in ORIGINAL.split(os.pathsep) if 'curve25519-elisabeth' not in p)
    (work / 'app/target/runtime-classpath.txt').write_text(without_weave)
    result = subprocess.run(['python3', str(work / 'scripts/runtime-inventory.py')],
                            capture_output=True, text=True, timeout=30)
    if result.returncode == 0 or 'Pinned Weavechain runtime artifact absent' not in result.stderr:
        raise RuntimeError(('missing arithmetic artifact', result.returncode, result.stdout, result.stderr))
    print('PASS: missing arithmetic artifact rejected')
    without_bc = os.pathsep.join(p for p in ORIGINAL.split(os.pathsep) if 'bcprov-' not in p)
    (work / 'app/target/runtime-classpath.txt').write_text(without_bc)
    result = subprocess.run(['python3', str(work / 'scripts/runtime-inventory.py')], capture_output=True, text=True, timeout=30)
    if result.returncode == 0 or 'Pinned Bouncy Castle runtime artifact absent' not in result.stderr:
        raise RuntimeError(('missing BC artifact', result.returncode, result.stdout, result.stderr))
    print('PASS: missing BC artifact rejected')
if (ROOT / 'app/target/runtime-classpath.txt').read_text() != ORIGINAL:
    raise RuntimeError('original runtime classpath changed')
print('5 runtime audit negative checks passed; original classpath untouched')
