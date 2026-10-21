# SPDX-License-Identifier: Apache-2.0
"""Synthetic transport fixture only: no native execution, source-build or publisher evidence."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import zipfile

import candidate_manifest as contract
import candidate_oci
import candidate_sbom
import check_native_reproducibility as native
import release_identity


def tar_bytes(files):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w') as archive:
        for name, raw in files.items():
            item = tarfile.TarInfo(name)
            item.size = len(raw)
            archive.addfile(item, io.BytesIO(raw))
    return output.getvalue()


def make_candidate(root: Path, release=True) -> dict:
    root.mkdir(parents=True, exist_ok=True)
    def put(path, raw):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        return target
    def write(path, value):
        return put(path, (json.dumps(value, sort_keys=True) + '\n').encode())
    def record(path):
        return contract.file_record(root, root / path)
    identity = dict.fromkeys(release_identity.KEYS, '')
    identity.update(schemaVersion='1', mode='release' if release else 'development', gitCommit='a' * 40,
                    version='1.2.3-beta.1' if release else '0.0.0-dev.g' + 'a' * 40,
                    channel='beta' if release else 'development')
    if release:
        identity.update(gitTag='v1.2.3-beta.1', gitTagObject='b' * 40,
                        sourceRef='refs/heads/release/beta', sourceRefCommit='a' * 40)
    source = dict(source=b'SYNTHETIC SOURCE ARCHIVE', raw=b'synthetic adapter', portable=b'synthetic JNI')
    recipe = dict(baseImage='ubuntu@sha256:' + 'c' * 64, sourceCommit='d' * 40,
                  sourceArchiveSha256=hashlib.sha256(source['source']).hexdigest(), flags='-D__ADX__ -D__BLST_PORTABLE__', snapshot=[])
    recipe_text = f'FROM {recipe["baseImage"]} AS build\nARG BLST_COMMIT={recipe["sourceCommit"]}\nARG BLST_SOURCE_ARCHIVE_SHA256={recipe["sourceArchiveSha256"]}\nARG BLST_FLAGS="{recipe["flags"]}"\n'
    source_files = {'docker/blst.Dockerfile': recipe_text.encode(), 'blst-native/native/raw-dst.c': source['raw']}
    source_files['blst-native/src/main/resources/supranational/blst/Linux/amd64/libblst.so'] = source['portable']
    for name in native.INPUTS:
        source_files.setdefault(name, b'SYNTHETIC SOURCE INPUT')
    put('source.tar', tar_bytes(source_files))
    source_hashes = {k: hashlib.sha256(v).hexdigest() for k, v in source_files.items()}
    write('evidence/repro/source-manifest.json', dict(gitCommit=identity['gitCommit'], buildIdentity=identity, files=source_hashes))
    metadata = {k: identity[k] for k in ['version', 'gitCommit', 'gitTag', 'gitTagObject', 'sourceRef', 'sourceRefCommit']}
    metadata.update(buildMode=identity['mode'], releaseChannel=identity['channel'])
    jar = root / f'artifacts/scarab-node-{identity["version"]}-assembly.jar'
    jar.parent.mkdir()
    with zipfile.ZipFile(jar, 'w') as archive:
        archive.writestr('META-INF/scarab/build-info.properties', ''.join(f'{k}={v}\n' for k, v in metadata.items()))
        archive.writestr('META-INF/MANIFEST.MF', 'Implementation-Version: ' + identity['version'] + '\n')
        archive.writestr('supranational/blst/Linux/amd64/libblst.so', source['portable'])
    reports = []
    for index in [1, 2]:
        put(f'evidence/repro/build-{index}.jar', jar.read_bytes())
        reports.append(dict(file=f'build-{index}.jar', assemblyName=jar.name, sha256=contract.sha256(jar)))
    write('evidence/repro/artifact-builds.json', reports)
    repro = dict(gitCommit=identity['gitCommit'], buildIdentity=identity, jdkImage='sha256:' + 'e' * 64, artifacts=reports)
    write('evidence/repro/verified-artifacts.json', repro)
    builds = []
    for index in [1, 2]:
        directory = f'evidence/native/build-{index}/'
        files = {'libblst.so': source['portable'], 'blst-source.tar': source['source'],
                 'packages.tsv': b'fixture-package\t1\tamd64\n', 'downloaded-debs.sha256': ('f' * 64 + '  /var/cache/apt/archives/fixture.deb\n').encode(),
                 'blst-commit.txt': recipe['sourceCommit'].encode(), 'blst-tree.txt': b'1' * 40,
                 'raw-dst.sha256': (source_hashes['blst-native/native/raw-dst.c'] + ' /src/raw-dst.c\n').encode()}
        for name in ['exports.txt', 'symbols.txt', 'elf.txt', 'disassembly.txt']:
            files[name] = b'SYNTHETIC DIAGNOSTIC'
        for pocket in ['jammy', 'jammy-updates', 'jammy-security']:
            files[f'apt/{pocket}_InRelease'] = f'Suite: {pocket}\nSYNTHETIC NOT SIGNED\n'.encode()
        for name, raw in files.items():
            put(directory + name, raw)
        builds.append(native.validate_artifact(root / directory, recipe, source_hashes['blst-native/native/raw-dst.c']))
    write('evidence/native/receipt.json', dict(status='passed', repositoryCommit=identity['gitCommit'], worktreeDirty=False,
                                            recipe=recipe, inputs=source_hashes, builds=builds))
    layer = tar_bytes({'opt/scarab/scarab.jar': jar.read_bytes()})
    config = dict(architecture='amd64', os='linux', rootfs=dict(type='layers', diff_ids=['sha256:' + hashlib.sha256(layer).hexdigest()]),
                  config=dict(User='10001:10001', Entrypoint=['java', '-jar', '/opt/scarab/scarab.jar'],
                              Labels={'org.opencontainers.image.revision': identity['gitCommit'], 'org.opencontainers.image.version': identity['version']}))
    docker_archive = put('docker.tar', tar_bytes({'manifest.json': json.dumps([dict(Config='config.json', Layers=['layer.tar'])]).encode(),
                                                'config.json': json.dumps(config).encode(), 'layer.tar': layer}))
    oci = root / 'artifacts/image.oci.tar'
    candidate_oci.convert(docker_archive, oci)
    docker_archive.unlink()
    inspected = candidate_oci.verify(oci, jar, identity)
    portable = 'supranational/blst/Linux/amd64/libblst.so'
    with zipfile.ZipFile(jar) as archive:
        entries = [dict(input=name, outputs=[name], policy='deduplicate', contributors=[dict(component='project:0',
                        sha256=hashlib.sha256(archive.read(name)).hexdigest())]) for name in archive.namelist()]
    coverage = dict(artifactSha256=contract.sha256(jar), components=[dict(ref='pkg:maven/example/synthetic@1',
                    coordinate='example:synthetic:1', sha256='3' * 64, licenses=[dict(name='MIT', url='')], callers=[])],
                    nativeResources=[dict(path=portable, sha256=hashlib.sha256(source['portable']).hexdigest())], entries=entries)
    write('evidence/assembly-coverage.json', coverage)
    write('evidence/jvm-resolution.json', {k: v for k, v in coverage.items() if k != 'entries'})
    legal = put('evidence/legal/license.txt', b'SYNTHETIC LEGAL EVIDENCE')
    image = dict(platform='linux/amd64', manifest={k: inspected['manifest'][k] for k in ['digest', 'mediaType']},
                 configDigest=inspected['configDigest'], baseImage='eclipse-temurin@sha256:' + '2' * 64,
                 reproducibility='build-once-from-verified-jar')
    packages = [dict(name='synthetic', version='1', architecture='amd64', copyrightSha256=contract.sha256(legal))]
    scanner = dict(bomFormat='CycloneDX', specVersion='1.6', components=[dict(type='library', name='synthetic',
                   version='1', purl='pkg:deb/ubuntu/synthetic@1?arch=amd64', **{'bom-ref': 'os:synthetic'}),
                   dict(type='application', name='SYNTHETIC-JAVA', version='21.0.11+10-LTS',
                        properties=[dict(name='syft:package:foundBy', value='java-jvm-cataloger')], **{'bom-ref': 'runtime:java'})])
    java_release = 'JAVA_RUNTIME_VERSION="21.0.11+10-LTS"\n'
    write('evidence/image-scan.cdx.json', scanner)
    write('evidence/image-inventory.json', dict(oci=inspected, baseImage=image['baseImage'], archiveSha256=contract.sha256(oci),
                                               imageId=inspected['configDigest'],
                                               baseConfigDigest='sha256:' + '4' * 64, baseDiffIds=inspected['diffIds'],
                                               scannerBom=record('evidence/image-scan.cdx.json'),
                                               os=dict(packages=packages, javaRelease=java_release,
                                                       retainedLegalFiles=[contract.file_record(root / 'evidence/legal', legal)])))
    verifier_files = ['evidence/image-verifier/ArtifactResourcesCheck.' + extension for extension in ['java', 'class']]
    for name in verifier_files:
        put(name, b'SYNTHETIC VERIFIER, NOT EXECUTED')
    write('evidence/toolchain.json', dict(jdk=dict(buildIdentity=identity, jdkImage=repro['jdkImage']), baseImage=image['baseImage'], nativeRecipe=recipe,
                                         imageVerifier=[record(name) for name in verifier_files]))
    write('evidence/licence-review.json', dict(status='review-required', unresolved=[]))
    manifest = dict(schemaVersion=1, status='verified', assurance=dict(scope='inventory-hash-coverage-subject-binding',
                    licenceReview=dict(status='review-required', unresolvedCount=0)), source=dict(buildIdentity=identity, archive=record('source.tar')),
                    image=image, subjects=[], evidence=[])
    jvm_bom, _ = candidate_sbom.jvm(jar, coverage, identity, contract.read_json(root / 'evidence/native/receipt.json'))
    image_bom = candidate_sbom.image(scanner, image['manifest']['digest'], jar, jvm_bom, packages, java_release)
    for path, kind, bom_path, subject_kind, digest in [(jar, 'jar', 'sbom/jvm.cdx.json', 'jar', 'sha256:' + contract.sha256(jar)),
                                                     (oci, 'oci-archive', 'sbom/image.cdx.json', 'oci-manifest', image['manifest']['digest'])]:
        bom = jvm_bom if kind == 'jar' else image_bom
        write(bom_path, bom)
        manifest['subjects'].append(dict(name=path.name, kind=kind, file=record(path.relative_to(root)),
                                        bom=dict(file=record(bom_path), subject=dict(kind=subject_kind, digest=digest))))
    paths = {'source-export': 'evidence/repro/source-manifest.json', 'reproducibility': 'evidence/repro/verified-artifacts.json',
             'native-reproducibility': 'evidence/native/receipt.json', 'assembly-coverage': 'evidence/assembly-coverage.json',
             'jvm-resolution': 'evidence/jvm-resolution.json', 'image-inventory': 'evidence/image-inventory.json',
             'toolchain': 'evidence/toolchain.json', 'licence-review': 'evidence/licence-review.json'}
    manifest['evidence'] = [dict(kind=kind, file=record(path)) for kind, path in paths.items()]
    write('candidate.json', manifest)
    return contract.validate(root, require_release=release)
