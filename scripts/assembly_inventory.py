#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reconcile resolved runtime inputs with every non-directory assembly entry (sbt-assembly 2.5.0)."""
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import quote
import zipfile

import licence_texts
from reproducible_build import sha256

PORTABLE = 'supranational/blst/Linux/amd64/libblst.so'
# The Linux/amd64 library of the Plutus builtins, pinned and checked at start-up (ADR 0020).
PLUTUS_CRYPTO = 'natives/linux_64/libscalus_crypto.so'
NATIVE = ('.so', '.dll', '.dylib', '.jnilib')
CONFIG = {'reference.conf', 'reference-overrides.conf', 'application.conf', 'rootdoc.txt', 'play.plugins'}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def policy(name):
    """These are the pinned plugin rules plus the explicit overrides in build.sbt."""
    leaf = name.rsplit('/', 1)[-1].lower()
    if name == 'module-info.class' or name.endswith('/module-info.class'):
        return 'discard-module-descriptor'
    if leaf in {'.ds_store', 'thumbs.db'}:
        return 'discard-system-junk'
    if name.startswith('META-INF/scarab/'):
        return 'deduplicate'
    if name == 'META-INF/DEPENDENCIES' or re.fullmatch(r'(readme|about|\._license|license|licence|notice|copying)(\.\w+)?', leaf) and not leaf.endswith('.class'):
        return 'rename-legal-or-readme'
    if name.lower() in CONFIG:
        return 'concat'
    if name.startswith('META-INF/'):
        suffix = name[len('META-INF/'):].lower()
        if (suffix in {'manifest.mf', 'index.list', 'dependencies'}
                or suffix.startswith(('maven/', 'plexus/'))
                or suffix.endswith(('.sf', '.dsa', '.rsa'))
                or '/' not in suffix and (suffix.endswith('.ec') or suffix.startswith('sig-'))):
            return 'discard-dependency-metadata'
        if suffix.startswith('services/') or suffix in {'spring.schemas', 'spring.handlers', 'spring.tooling'}:
            return 'distinct-lines'
    return 'deduplicate'


def entries(path):
    if path.is_dir():
        return [(p.relative_to(path).as_posix(), p.read_bytes()) for p in sorted(path.rglob('*')) if p.is_file()]
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f'duplicate dependency ZIP entries: {path.name}')
        return [(n, jar.read(n)) for n in names if not n.endswith('/')]


def reconcile(artifact, inventory):
    """Fail on unexplained added, lost, transformed or shadowed bytes; preserve original contributors."""
    origins = {}
    components = []
    paths = inventory['classpath']
    if not paths or len(paths) != len(set(paths)):
        raise ValueError('empty or duplicate runtime classpath')
    by_path = {}
    for module in inventory['modules']:
        for item in module['artifacts']:
            if item['path'] in paths:
                if item['path'] in by_path:
                    previous = by_path[item['path']]
                    if previous['coordinate'] != module['coordinate'] or previous['licenses'] != module['licenses']:
                        raise ValueError('ambiguous resolved artifact coordinate')
                    by_path[item['path']] = dict(previous, callers=sorted(set(previous['callers'] + module['callers'])))
                else:
                    by_path[item['path']] = module
    for index, entry in enumerate(paths):
        path = Path(entry)
        if path.is_symlink() or not path.exists():
            raise ValueError('missing or symlinked runtime input')
        if path.is_dir():
            component = f'project:{index}'
            rename_suffix = None
        else:
            if entry not in by_path:
                raise ValueError(f'unresolved runtime JAR: {path.name}')
            module = by_path[entry]
            group, name, version = module['coordinate'].split(':')
            rename_suffix = name + '-' + version
            component = f'pkg:maven/{quote(group, safe="")}/{quote(name, safe="")}@{quote(version, safe="")}'
            components.append(dict(ref=component, coordinate=module['coordinate'], sha256=sha256(path),
                                   licenses=module['licenses'], callers=sorted(module['callers'])))
        for name, raw in entries(path):
            if name.startswith('/') or '..' in Path(name).parts:
                raise ValueError('unsafe runtime resource path')
            origins.setdefault(name, []).append(dict(component=component, sha256=digest(raw), raw=raw,
                                                     renameSuffix=rename_suffix))
    actual = dict(entries(artifact))
    remaining = set(actual)
    output = []
    for name, sources in sorted(origins.items()):
        rule = policy(name)
        targets = []
        if rule.startswith('discard-'):
            if name in actual and name != 'META-INF/MANIFEST.MF':
                raise ValueError(f'discarded entry retained unexpectedly: {name}')
        elif name == 'META-INF/MANIFEST.MF':
            raise ValueError('manifest must be generated, not copied')
        elif rule == 'rename-legal-or-readme':
            for source in sources:
                suffix = source['renameSuffix']
                if suffix is None:
                    raise ValueError('project legal/readme renaming requires explicit project-name evidence')
                extension = re.search(r'\.\w+$', name)
                target = (name[:extension.start()] + '_' + suffix + extension.group()) if extension else name + '_' + suffix
                if target not in actual or digest(actual[target]) != source['sha256']:
                    raise ValueError(f'lost or changed renamed legal/readme input: {name}')
                targets.append(target)
        else:
            if name not in actual:
                raise ValueError(f'lost assembly input: {name}')
            raw = actual[name]
            if rule == 'concat':
                expected = b''.join(s['raw'] + (b'' if s['raw'].endswith(b'\n') else b'\n') for s in sources)
                if raw != expected:
                    raise ValueError(f'changed concatenated resource: {name}')
            elif rule == 'distinct-lines':
                expected = list(dict.fromkeys(line for s in sources for line in s['raw'].decode('utf-8').splitlines()))
                if raw != '\n'.join(expected).encode('utf-8'):
                    raise ValueError(f'changed merged service/registry resource: {name}')
            elif any(s['sha256'] != digest(raw) for s in sources):
                raise ValueError(f'changed or conflicting deduplicated resource: {name}')
            targets = [name]
        remaining.difference_update(targets)
        output.append(dict(input=name, policy=rule, outputs=sorted(set(targets)),
                           contributors=[{k: v for k, v in s.items() if k != 'raw'} for s in sources]))
    # sbt-assembly generates exactly this entry independently of the resolved classpath.
    remaining.discard('META-INF/MANIFEST.MF')
    if remaining:
        raise ValueError(f'unattributed assembly entries: {sorted(remaining)[:20]}')
    native = [dict(path=name, sha256=digest(raw)) for name, raw in sorted(actual.items()) if name.endswith(NATIVE)]
    if not native or not {PORTABLE, PLUTUS_CRYPTO} <= {n['path'] for n in native} or not components:
        raise ValueError('missing native or JVM component coverage')
    return dict(schemaVersion=1, artifactSha256=sha256(artifact), components=components,
                nativeResources=native, entries=output, finalEntries=len(actual),
                generatedEntries=['META-INF/MANIFEST.MF'])


def licence_coverage(artifact, coverage):
    """Fail unless the JAR carries the licence texts of exactly what was resolved into it (licence_texts.py)."""
    rows = licence_texts.verify_artifact(dict(entries(artifact)), coverage)
    if rows:
        raise ValueError('incomplete licence texts: ' + '; '.join(row.render() for row in rows[:20]))


def export(artifact, raw_inventory, destination):
    inventory = json.loads(raw_inventory.read_text())
    coverage = reconcile(artifact, inventory)
    licence_coverage(artifact, coverage)
    destination.mkdir(parents=True)
    (destination / 'assembly-coverage.json').write_text(json.dumps(coverage, sort_keys=True) + '\n')
    # Absolute cache and scratch paths are operational inputs, not portable component identities.
    normalized = {k: v for k, v in coverage.items() if k != 'entries'}
    (destination / 'jvm-resolution.json').write_text(json.dumps(normalized, sort_keys=True) + '\n')
    return coverage
