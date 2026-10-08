#!/usr/bin/env python3
"""Direct JVM network demo/usage regressions; no arbitrary peer addresses."""
import hashlib, json, subprocess
from pathlib import Path
root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / 'docs/network-fixtures.json').read_text())
for fixture in manifest['fixtures']:
    literal = (root / 'fixtures/network' / (fixture['id'] + '.hex')).read_text().strip()
    assert literal == fixture['hex']
    assert hashlib.sha256(bytes.fromhex(literal)).hexdigest() == fixture['sha256']
cp = (root / 'app/target/runtime-classpath.txt').read_text()
results = []
for name, args, expected in [
    ('demo', ['network-demo'], 0),
    ('selftest', ['network-selftest'], 0),
    ('extra-args', ['network-demo', 'example.com'], 2),
    ('endpoint-option', ['network-demo', '--host', '127.0.0.1'], 2),
]:
    p = subprocess.run(['java', '-cp', cp, 'lab.Main'] + args, cwd=root,
                       capture_output=True, text=True, timeout=30)
    (root / f'docs/cli-network-{name}.log').write_text(p.stdout + p.stderr + f'\nexit={p.returncode}; expected={expected}\n')
    assert p.returncode == expected, (name, p.returncode, p.stdout, p.stderr)
    if expected == 0:
        report = json.loads(p.stdout.strip())
        assert report['passed'] and report['checks'] == 4
        assert report['referenceRuntimeChecked'] is False
        assert report['negotiated'] == 3 and report['queryResults'] == 1
    results.append({'case': name, 'exit': p.returncode, 'expected': expected})
(root / 'docs/cli-network-verification.json').write_text(json.dumps(results, indent=2) + '\n')
print(f'{len(results)} direct-Java network CLI cases passed')
