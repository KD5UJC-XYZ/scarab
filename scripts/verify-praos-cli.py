#!/usr/bin/env python3
"""Direct resolved-JVM CLI checks. No native library, node or remote connection."""
from pathlib import Path
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parent.parent
CP = (ROOT / 'app/target/runtime-classpath.txt').read_text()
CMD = ['java', '-cp', CP, 'lab.Main', 'praos-demo']
def check(name, cwd, wanted, suffix=()):
    result = subprocess.run(CMD + list(suffix), cwd=cwd, capture_output=True, text=True, timeout=30)
    if result.returncode != wanted:
        raise RuntimeError((name, result.returncode, result.stdout, result.stderr))
    if wanted == 0 and ('18/18' not in result.stdout or 'Experimental certificate-only research' not in result.stdout):
        raise RuntimeError((name, result.stdout))
    print(f'PASS: {name}, exit={wanted}')
check('pinned public corpus', ROOT, 0)
check('extra arguments', ROOT, 2, ('extra',))
with tempfile.TemporaryDirectory(prefix='praos-cli-') as tmp:
    work = Path(tmp)
    directory = work / 'fixtures/praos'
    directory.mkdir(parents=True)
    for name in ['certificates.tsv']:
        (directory / name).write_bytes((ROOT / 'fixtures/praos' / name).read_bytes())
    check('portable pinned files', work, 0)
    target = directory / 'certificates.tsv'
    target.write_bytes(target.read_bytes() + b'\n')
    check('mutated fixture', work, 2)
    (directory / 'sha256.json').write_text('{}')
    check('edited manifest cannot bypass embedded pin', work, 2)
    target.write_bytes(b'x' * (64 * 1024 + 1))
    check('oversized fixture', work, 2)
    target.unlink()
    check('missing fixture', work, 2)
print('7 direct-JVM Praos CLI checks passed')
