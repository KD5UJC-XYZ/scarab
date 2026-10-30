#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Portable, read-only candidate transport checks; publication adds trusted identity/signature policy."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import zipfile

import check_native_identity
import release_identity

EVIDENCE = {'source-export', 'reproducibility', 'native-identity', 'assembly-coverage',
            'jvm-resolution', 'image-inventory', 'toolchain', 'licence-review'}
SHA256 = re.compile(r'[0-9a-f]{64}')
MEDIA_TYPE = 'application/vnd.oci.image.manifest.v1+json'


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f'duplicate JSON field: {key}')
            result[key] = value
        return result
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def file_record(root, path):
    relative = path.relative_to(root).as_posix()
    return dict(path=relative, sha256=sha256(path), size=path.stat().st_size)


def checked_file(root, record):
    if not isinstance(record, dict) or set(record) != {'path', 'sha256', 'size'}:
        raise ValueError('invalid file descriptor')
    name = record['path']
    if (not isinstance(name, str) or '\\' in name or not name
            or PurePosixPath(name).is_absolute() or any(p in {'', '.', '..'} for p in name.split('/'))
            or not SHA256.fullmatch(str(record['sha256'])) or type(record['size']) is not int or record['size'] <= 0):
        raise ValueError('unsafe candidate path or invalid file identity')
    path = root / name
    if any(p.is_symlink() for p in [path, *path.parents] if p != root.parent):
        raise ValueError('symlinked candidate file')
    if not path.is_file() or path.stat().st_size != record['size'] or sha256(path) != record['sha256']:
        raise ValueError(f'missing or changed candidate file: {name}')
    return path


def validate(directory: Path, require_release: bool = False) -> dict:
    """Validate frozen transport and subject bindings, never treat a self-authored manifest as trust."""
    root = directory.resolve()
    manifest = read_json(root / 'candidate.json')
    if set(manifest) != {'schemaVersion', 'status', 'assurance', 'source', 'subjects', 'image', 'evidence'} or type(manifest['schemaVersion']) is not int or manifest['schemaVersion'] != 1 or manifest['status'] != 'verified':
        raise ValueError('unsupported or incomplete candidate manifest')
    assurance = manifest['assurance']
    if (set(assurance) != {'scope', 'licenceReview'}
            or assurance['scope'] != 'inventory-hash-coverage-subject-binding'
            or set(assurance['licenceReview']) != {'status', 'unresolvedCount'}
            or assurance['licenceReview']['status'] != 'review-required'
            or type(assurance['licenceReview']['unresolvedCount']) is not int
            or assurance['licenceReview']['unresolvedCount'] < 0):
        raise ValueError('candidate must preserve separate licence/advisory review boundary')
    source = manifest['source']
    if set(source) != {'buildIdentity', 'archive'}:
        raise ValueError('invalid candidate source')
    identity = release_identity.validate(source['buildIdentity'])
    if require_release and identity['mode'] != 'release':
        raise ValueError('development candidates cannot be published as releases')
    checked_file(root, source['archive'])
    image = manifest['image']
    if (set(image) != {'platform', 'manifest', 'configDigest', 'baseImage', 'reproducibility'}
            or image['platform'] != 'linux/amd64'
            or image['reproducibility'] != 'build-once-from-verified-jar'
            or set(image['manifest']) != {'digest', 'mediaType'}
            or image['manifest']['mediaType'] != MEDIA_TYPE
            or any(not re.fullmatch('sha256:[0-9a-f]{64}', str(d)) for d in [image['manifest']['digest'], image['configDigest']])
            or not re.fullmatch(r'eclipse-temurin@sha256:[0-9a-f]{64}', image['baseImage'])):
        raise ValueError('invalid OCI content identity/platform')
    subjects = manifest['subjects']
    if not isinstance(subjects, list) or sorted(s.get('kind', '') for s in subjects) != ['jar', 'oci-archive']:
        raise ValueError('exactly one JAR and OCI archive are required')
    files = []
    for subject in subjects:
        if set(subject) != {'name', 'kind', 'file', 'bom'} or not isinstance(subject['name'], str) or not subject['name']:
            raise ValueError('invalid candidate subject')
        artifact = checked_file(root, subject['file'])
        if subject['name'] != artifact.name:
            raise ValueError('subject name differs from transport filename')
        bom = subject['bom']
        if set(bom) != {'file', 'subject'}:
            raise ValueError('invalid BOM descriptor')
        expected = {'kind': 'jar', 'digest': 'sha256:' + subject['file']['sha256']} if subject['kind'] == 'jar' else {'kind': 'oci-manifest', 'digest': image['manifest']['digest']}
        if bom['subject'] != expected:
            raise ValueError('SBOM descriptor binds a different subject')
        document = read_json(checked_file(root, bom['file']))
        component = document.get('metadata', {}).get('component', {})
        if (document.get('bomFormat') != 'CycloneDX' or document.get('specVersion') != '1.6'
                or component.get('hashes') != [{'alg': 'SHA-256', 'content': expected['digest'][7:]}]
                or not document.get('components') or not document.get('dependencies')):
            raise ValueError('SBOM actual subject or component inventory is missing/unbound')
        files.extend([subject['file']['path'], bom['file']['path']])
    evidence = manifest['evidence']
    if not isinstance(evidence, list) or {e.get('kind') for e in evidence} != EVIDENCE or len(evidence) != len(EVIDENCE):
        raise ValueError('all mandatory evidence kinds are required exactly once')
    for item in evidence:
        if set(item) != {'kind', 'file'}:
            raise ValueError('invalid evidence descriptor')
        checked_file(root, item['file'])
        files.append(item['file']['path'])
    if len(files) != len(set(files)):
        raise ValueError('ambiguous candidate artifact/evidence paths')
    records = {item['kind']: checked_file(root, item['file']) for item in evidence}
    source_export = read_json(records['source-export'])
    if source_export.get('buildIdentity') != identity or source_export.get('gitCommit') != identity['gitCommit']:
        raise ValueError('source export identity differs from candidate')
    expected_files = dict(source_export['files'])
    expected_files.pop(release_identity.IDENTITY_FILE, None)
    with tarfile.open(checked_file(root, source['archive'])) as archive:
        payloads = [m for m in archive.getmembers() if not m.isdir()]
        if len(payloads) != len(expected_files) or {m.name for m in payloads} != set(expected_files):
            raise ValueError('source archive inventory differs from frozen export')
        for member in payloads:
            if not member.isfile() or member.name.startswith('/') or '..' in PurePosixPath(member.name).parts:
                raise ValueError('unsafe source archive member')
            with archive.extractfile(member) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != expected_files[member.name]:
                    raise ValueError('source archive differs from frozen input hashes')
        # the native pins and the jblst version, as frozen in the verified source
        native_sources = {name: archive.extractfile(name).read().decode()
                          for name in (check_native_identity.PINS, check_native_identity.DEPENDENCIES)}
    from reproducible_build import verify_output_identity
    verified = verify_output_identity(records['reproducibility'].parent, identity)
    jar_subject = next(s for s in subjects if s['kind'] == 'jar')
    if any(row['sha256'] != jar_subject['file']['sha256'] for row in verified):
        raise ValueError('candidate JAR differs from independently reproduced bytes')
    repro = read_json(records['reproducibility'])
    if repro.get('gitCommit') != identity['gitCommit'] or repro.get('buildIdentity') != identity:
        raise ValueError('reproducibility receipt source differs from candidate')
    native = read_json(records['native-identity'])
    jar = checked_file(root, jar_subject['file'])
    # recomputed here, never taken from the receipt: every packaged blst library against the frozen
    # pins, nothing unpinned, and the ADX/BMI2 check of the Linux/amd64 one (ADR 0025)
    recomputed = check_native_identity.verify_texts(jar, native_sources[check_native_identity.PINS],
                                                    native_sources[check_native_identity.DEPENDENCIES])
    if recomputed['status'] != 'passed' or native != recomputed:
        raise ValueError('native identity receipt differs from a check of the candidate JAR against its frozen pins')
    coverage = read_json(records['assembly-coverage'])
    if coverage.get('artifactSha256') != jar_subject['file']['sha256'] or not coverage.get('entries'):
        raise ValueError('assembly coverage binds different or empty contents')
    resolution = read_json(records['jvm-resolution'])
    if resolution != {k: v for k, v in coverage.items() if k != 'entries'}:
        raise ValueError('JVM resolution and assembly coverage differ')
    import candidate_sbom
    expected_jvm_bom, _ = candidate_sbom.jvm(jar, coverage, identity, native)
    actual_jvm_bom = read_json(checked_file(root, jar_subject['bom']['file']))
    if actual_jvm_bom != expected_jvm_bom:
        raise ValueError('JVM SBOM differs from resolved/packaged component evidence')
    review = read_json(records['licence-review'])
    if review.get('status') != 'review-required' or len(review.get('unresolved', [])) != assurance['licenceReview']['unresolvedCount']:
        raise ValueError('licence review report is missing or contradicts manifest')
    import candidate_oci
    oci_subject = next(s for s in subjects if s['kind'] == 'oci-archive')
    inspected = candidate_oci.verify(checked_file(root, oci_subject['file']), jar, identity, image['manifest']['digest'])
    if inspected['configDigest'] != image['configDigest']:
        raise ValueError('OCI configuration digest differs from manifest')
    image_inventory = read_json(records['image-inventory'])
    if (image_inventory.get('oci') != inspected or image_inventory.get('baseImage') != image['baseImage']
            or image_inventory.get('imageId') != image_inventory.get('dockerBinding', {}).get('inspect', {}).get('Id')
            or image_inventory.get('archiveSha256') != oci_subject['file']['sha256']
            or not image_inventory.get('os', {}).get('packages')
            or not image_inventory.get('os', {}).get('retainedLegalFiles')):
        raise ValueError('actual image/OS/legal inventory differs from candidate subjects')
    candidate_oci.verify_docker_binding(image_inventory['dockerBinding'], inspected)
    base_layers = image_inventory.get('baseDiffIds')
    if (not isinstance(base_layers, list) or not base_layers
            or inspected['diffIds'][:len(base_layers)] != base_layers
            or not re.fullmatch(r'sha256:[0-9a-f]{64}', str(image_inventory.get('baseImageId')))):
        raise ValueError('pinned runtime base filesystem identity is absent or differs')
    for legal in image_inventory['os']['retainedLegalFiles']:
        checked_file(records['image-inventory'].parent / 'legal', legal)
    scanner = read_json(checked_file(root, image_inventory['scannerBom']))
    expected_image_bom = candidate_sbom.image(scanner, image['manifest']['digest'], jar,
                                             expected_jvm_bom, image_inventory['os']['packages'], image_inventory['os']['javaRelease'])
    actual_image_bom = read_json(checked_file(root, oci_subject['bom']['file']))
    if actual_image_bom != expected_image_bom:
        raise ValueError('image SBOM differs from actual OS/JVM inventory')
    toolchain = read_json(records['toolchain'])
    if (toolchain.get('jdk', {}).get('buildIdentity') != identity
            or toolchain.get('jdk', {}).get('jdkImage') != repro.get('jdkImage')
            or toolchain.get('baseImage') != image['baseImage']
            or toolchain.get('nativeLibrary') != {k: native.get(k) for k in ('library', 'version', 'blstCommit')}):
        raise ValueError('toolchain receipts bind different source/runtime/native inputs')
    verifier = toolchain.get('imageVerifier', [])
    if len(verifier) != 2 or {Path(r['path']).name for r in verifier} != {'ArtifactResourcesCheck.java', 'ArtifactResourcesCheck.class'}:
        raise ValueError('actual-image resource verifier evidence is missing')
    for record in verifier:
        checked_file(root, record)
    return manifest


def signing_subjects(directory: Path) -> list:
    manifest = validate(directory, require_release=True)
    records = [dict(name='candidate.json', digest={'sha256': sha256(directory / 'candidate.json')})]
    for subject in manifest['subjects']:
        for record in [subject['file'], subject['bom']['file']]:
            records.append(dict(name=record['path'], digest={'sha256': record['sha256']}))
    records.append(dict(name='oci-manifest', digest={'sha256': manifest['image']['manifest']['digest'][7:]}))
    return records
