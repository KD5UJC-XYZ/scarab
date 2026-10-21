#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""CycloneDX 1.6 generation from verified resolver/assembly and image inventories."""
import json
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from candidate_manifest import sha256


def hashes(value):
    return [{'alg': 'SHA-256', 'content': value}]


def properties(**values):
    return [{'name': 'scarab:' + key, 'value': str(value)} for key, value in sorted(values.items())]


def base(subject_name, subject_hash, subject_type='application'):
    return {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
            'metadata': {'component': {'type': subject_type, 'name': subject_name,
                                       'bom-ref': 'subject', 'hashes': hashes(subject_hash)}},
            'components': [], 'dependencies': []}


def jvm(artifact, coverage, source_identity, native_receipt):
    if coverage['artifactSha256'] != sha256(artifact):
        raise ValueError('assembly coverage binds different JAR bytes')
    portable = [n for n in coverage['nativeResources'] if n['path'] == 'supranational/blst/Linux/amd64/libblst.so']
    if (len(portable) != 1 or native_receipt.get('status') != 'passed'
            or len(native_receipt.get('builds', [])) != 2
            or any(b.get('sha256') != portable[0]['sha256'] for b in native_receipt['builds'])):
        raise ValueError('native source rebuild receipt does not bind packaged portable JNI bytes')
    bom = base(artifact.name, sha256(artifact))
    bom['metadata']['component']['version'] = source_identity['version']
    bom['metadata']['component']['licenses'] = [{'license': {'id': 'Apache-2.0'}}]
    bom['metadata']['component']['properties'] = properties(sourceCommit=source_identity['gitCommit'],
                                                          nativePlatforms='resources inventoried; runtime verified Linux/amd64 only')
    modules = coverage['components']
    known = {m['coordinate']: m['ref'] for m in modules}
    if len(known) != len(modules):
        raise ValueError('duplicate resolved component identity')
    links = {'subject': set()}
    reviews = []
    for module in modules:
        group, name, version = module['coordinate'].split(':')
        component = {'type': 'library', 'bom-ref': module['ref'], 'purl': module['ref'],
                     'group': group, 'name': name, 'version': version, 'hashes': hashes(module['sha256'])}
        licenses = []
        for license in module['licenses']:
            if not license['name'].strip():
                raise ValueError('empty resolver licence declaration')
            evidence = {'name': license['name']}
            if license['url']:
                evidence['url'] = license['url']
            licenses.append({'license': evidence})
        if licenses:
            component['licenses'] = licenses
        else:
            reviews.append({'component': module['ref'], 'reason': 'resolver has no licence declaration'})
        bom['components'].append(component)
        links.setdefault(module['ref'], set())
        parents = {known[c] for c in module['callers'] if c in known}
        for parent in parents:
            links.setdefault(parent, set()).add(module['ref'])
        if not parents or any(c not in known for c in module['callers']):
            links['subject'].add(module['ref'])
    by_output = {}
    for row in coverage['entries']:
        for path in row['outputs']:
            by_output.setdefault(path, []).extend(row['contributors'])
    for native in coverage['nativeResources']:
        path = native['path']
        ref = 'native:' + path
        owners = {c['component'] for c in by_output.get(path, []) if c['sha256'] == native['sha256']}
        if not owners:
            raise ValueError(f'unowned native resource: {path}')
        component = {'type': 'file', 'name': path, 'bom-ref': ref, 'hashes': hashes(native['sha256']),
                     'evidence': {'occurrences': [{'location': 'jar!/' + path}]},
                     'properties': properties(runtimeVerified=str(path == portable[0]['path']).lower())}
        if path == portable[0]['path']:
            component['licenses'] = [{'license': {'id': 'Apache-2.0'}}]
            component['version'] = native_receipt['recipe']['sourceCommit']
            component['properties'] += properties(sourceArchiveSha256=native_receipt['recipe']['sourceArchiveSha256'])
        bom['components'].append(component)
        for owner in owners:
            links.setdefault('subject' if owner.startswith('project:') else owner, set()).add(ref)
        links[ref] = set()
    bom['dependencies'] = [{'ref': ref, 'dependsOn': sorted(dependencies)} for ref, dependencies in sorted(links.items())]
    return bom, reviews


def image(raw_bom, manifest_digest, artifact, jvm_bom, packages, java_release):
    """Require every installed dpkg identity, not merely a nonempty scanner result."""
    if raw_bom.get('bomFormat') != 'CycloneDX' or raw_bom.get('specVersion') != '1.6':
        raise ValueError('scanner must produce CycloneDX 1.6')
    found = raw_bom.get('components', [])
    if not packages or not found:
        raise ValueError('empty OS inventory or scanner output')
    for package in packages:
        matches = [c for c in found if c.get('name') == package['name'] and c.get('version') == package['version']
                   and c.get('purl', '').startswith('pkg:deb/')]
        if len(matches) != 1:
            raise ValueError(f'missing/ambiguous installed OS package: {package["name"]}')
        if parse_qs(urlsplit(matches[0]['purl']).query).get('arch') != [package['architecture']]:
            raise ValueError('scanner OS architecture differs from installed package')
        matches[0].setdefault('properties', []).extend(properties(
            installedArchitecture=package['architecture'], copyrightSha256=package['copyrightSha256']))
    bom = base('scarab-node-image', manifest_digest[7:], 'container')
    refs = set()
    for component in found:
        ref = component.get('bom-ref')
        if not ref or ref in refs:
            raise ValueError('scanner component references are missing/duplicated')
        refs.add(ref)
    runtime = dict(line.split('=', 1) for line in java_release.splitlines() if '=' in line)
    expected_java = runtime.get('JAVA_RUNTIME_VERSION', '').strip('"')
    jvm = [c for c in found if any(p == {'name': 'syft:package:foundBy', 'value': 'java-jvm-cataloger'} for p in c.get('properties', []))]
    if not expected_java or len(jvm) != 1 or jvm[0].get('version') != expected_java:
        raise ValueError('scanner omitted or misidentified the actual Java runtime')
    # Replace the assembly's heuristic package only. JRE archives such as jrt-fs.jar
    # remain separate runtime components, including their original scanner evidence.
    replaced = {c['bom-ref'] for c in found if c.get('type') != 'file' and any(
        p['name'].startswith('syft:location:') and p['name'].endswith(':path')
        and p['value'] == '/opt/scarab/scarab.jar' for p in c.get('properties', []))}
    if len(replaced) > 1:
        raise ValueError('ambiguous assembly scanner attribution')
    retained = [c for c in found if c['bom-ref'] not in replaced]
    jar_component = dict(jvm_bom['metadata']['component'], **{'bom-ref': 'application:jar'})
    jar_component['name'] = artifact.name
    jar_component['hashes'] = hashes(sha256(artifact))
    bom['components'] = retained + [jar_component] + jvm_bom['components']
    ids = [c['bom-ref'] for c in bom['components']]
    if len(ids) != len(set(ids)):
        raise ValueError('image/JVM component reference collision')
    links = {'subject': {c['bom-ref'] for c in retained} | {'application:jar'}}
    for component in retained:
        links[component['bom-ref']] = set()
    root_ref = raw_bom.get('metadata', {}).get('component', {}).get('bom-ref')
    def mapped(ref):
        return 'subject' if ref == root_ref else 'application:jar' if ref in replaced else ref
    for row in raw_bom.get('dependencies', []):
        parent = mapped(row['ref'])
        children = {mapped(ref) for ref in row.get('dependsOn', [])}
        if parent not in set(ids) | {'subject'} or not children.issubset(set(ids) | {'subject'}):
            raise ValueError('scanner contains dangling component relationships')
        links.setdefault(parent, set()).update(children)
    for row in jvm_bom['dependencies']:
        parent = 'application:jar' if row['ref'] == 'subject' else row['ref']
        links.setdefault(parent, set()).update(row['dependsOn'])
    bom['dependencies'] = [{'ref': ref, 'dependsOn': sorted(children)} for ref, children in sorted(links.items())]
    return bom


def write(path, value):
    Path(path).write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')
