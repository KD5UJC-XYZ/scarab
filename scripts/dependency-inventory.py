#!/usr/bin/env python3
"""Record resolved Maven jars; includes build/test tooling, not a runtime-only SBOM."""
import hashlib, json, os, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
cache = pathlib.Path(os.environ.get('LAB_CACHE_DIR', root / '.cache')) / 'cardano-scala-lab' / 'coursier'
records = []
for path in sorted(cache.glob('https/repo.maven.apache.org/maven2/**/*.jar')):
    relative = path.relative_to(cache / 'https/repo.maven.apache.org/maven2')
    records.append({'mavenPath': str(relative), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
if not records:
    raise SystemExit('No resolved artifacts. Run ./scripts/sbtw check first.')
(root / 'docs/dependency-checksums.json').write_text(json.dumps({'scope': 'Resolved build, test and runtime jars; not runtime-only SBOM', 'artifacts': records}, indent=2) + '\n')
print(f'Recorded {len(records)} resolved artifact checksums')
