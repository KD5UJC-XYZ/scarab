#!/usr/bin/env python3
"""Direct JVM admission checks; run after app/runtimeClasspathFile. No native or network oracle."""
from pathlib import Path
import subprocess
import tempfile
import sys

if not __debug__:
    sys.exit('Python optimization is unsupported: assertions are acceptance gates')
ROOT = Path(__file__).resolve().parent.parent
CP = (ROOT / 'app/target/runtime-classpath.txt').read_text()
CMD = ['java', '-cp', CP, 'lab.Main', 'coverage-demo']


def check(name, cwd, wanted, suffix=()):
    result = subprocess.run(CMD + list(suffix), cwd=cwd, capture_output=True, text=True, timeout=30)
    assert result.returncode == wanted, (name, result.returncode, result.stdout, result.stderr)
    if wanted == 0:
        assert result.stdout.count('PASS\t') == 2
        assert 'MissingRequiredKeys' in result.stdout
        assert '3c875ce0f647bdcc64b70e62814680fbd939f8faf0203b98236ede7b' in result.stdout
        assert result.stdout.count('signatures=SignatureVerified') == 2
        assert 'do not establish transaction validity or a ledger transition' in result.stdout
    print(f'PASS: {name}, exit={wanted}')


check('archived acceptance and missing-key pair', ROOT, 0)
check('extra arguments rejected', ROOT, 2, ('extra',))
with tempfile.TemporaryDirectory(prefix='coverage-cli-') as temporary:
    work = Path(temporary)
    fixture = work / 'fixtures/coverage/coverage-vectors.tsv'
    fixture.parent.mkdir(parents=True)
    source = (ROOT / 'fixtures/coverage/coverage-vectors.tsv').read_bytes()
    fixture.write_bytes(source)
    check('same pinned bytes independent cwd', work, 0)
    fixture.write_bytes(source + b'\n')
    check('changed projection rejected', work, 2)
    (fixture.parent / 'SHA256SUMS').write_text('0' * 64 + '  coverage-vectors.tsv\n')
    check('editable manifest cannot replace immutable pin', work, 2)
    fixture.write_bytes(b'x' * 1048577)
    check('oversize rejected', work, 2)
    fixture.unlink()
    check('missing projection rejected', work, 2)
print('7 direct-JVM coverage CLI checks passed')
