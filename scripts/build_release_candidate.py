#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build verified local candidate artifacts and SBOMs; never publish or create release authority."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import uuid

import assembly_inventory
import candidate_image_inventory
import candidate_manifest
import candidate_oci
import candidate_sbom
import check_native_reproducibility
import release_identity
import reproducible_build

SYFT = 'anchore/syft@sha256:b83763ae05df444f7f768655ce2ea6d7c2f8280d58f9bee221a69d2971d98883'
CYCLONEDX = 'cyclonedx/cyclonedx-cli@sha256:8c6606f62497671f246e30f5894000dbee47167c3ebda127acda04777e881c26'
BASE = 'eclipse-temurin@sha256:9af01c30e85b5a9b15fe249a84fb6dcb19e06ee84fbf23ea2d116b0e195c2ce9'


def run(command, log, **options):
    with log.open('wb') as stream:
        subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True, **options)


def schema_validate(root, path, log):
    run(['docker', 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}',
         '-v', f'{root}:/data:ro', CYCLONEDX, 'validate', '--input-file', '/data/' + path.relative_to(root).as_posix(),
         '--input-format', 'json', '--input-version', 'v1_6', '--fail-on-errors'], log)


def build(args):
    repo = Path(__file__).resolve().parent.parent
    identity = release_identity.resolve(repo, 'HEAD', tag=args.release_tag, channel=args.channel,
                                        expected_commit=args.expected_commit if args.release_tag else None,
                                        expected_tag_object=args.expected_tag_object,
                                        source_ref=args.source_ref)
    if args.expected_commit and identity['gitCommit'] != args.expected_commit:
        raise ValueError('candidate checkout is not the approved source')
    if release_identity.git(repo, 'status', '--porcelain', '--untracked-files=all'):
        raise ValueError('candidate and native proof require the same clean committed source')
    root = args.output.resolve()
    if root.is_relative_to(repo):
        raise ValueError('candidate output must be outside its source checkout')
    root.mkdir(parents=True, exist_ok=False)
    evidence = root / 'evidence'
    evidence.mkdir()
    artifacts = root / 'artifacts'
    artifacts.mkdir()
    sboms = root / 'sbom'
    sboms.mkdir()
    candidate_sbom.write(evidence / 'attempt.json', dict(status='building', source=identity))
    repro = evidence / 'repro'
    command = ['python3', str(repo / 'scripts/reproducible_build.py'), '--revision', identity['gitCommit'],
               '--inventory', '--image', args.image, '--cache', str(args.cache.resolve()), '--output', str(repro)]
    if args.check:
        command.append('--check')
    if identity['mode'] == 'release':
        command += ['--release-tag', identity['gitTag'], '--channel', identity['channel'],
                    '--expected-commit', identity['gitCommit'], '--expected-tag-object', identity['gitTagObject'],
                    '--source-ref', identity['sourceRef']]
    run(command, evidence / 'repro.log', cwd=repo)
    reproducible_build.verify_output_identity(repro, identity)
    first = candidate_manifest.read_json(repro / 'inventory-1/assembly-coverage.json')
    second = candidate_manifest.read_json(repro / 'inventory-2/assembly-coverage.json')
    if first != second:
        raise ValueError('independent builds resolved different dependency/assembly inventories')
    native = evidence / 'native'
    run(['python3', str(repo / 'scripts/check_native_reproducibility.py'), '--output', str(native)],
        evidence / 'native.log', cwd=repo)
    native_receipt = candidate_manifest.read_json(native / 'receipt.json')
    source_manifest = candidate_manifest.read_json(repro / 'source-manifest.json')
    if native_receipt.get('repositoryCommit') != identity['gitCommit'] or native_receipt.get('worktreeDirty'):
        raise ValueError('native proof source is not the clean approved candidate source')
    for name in check_native_reproducibility.INPUTS:
        if native_receipt['inputs'].get(name) != source_manifest['files'].get(name):
            raise ValueError(f'native proof input differs from candidate source: {name}')
    artifact = artifacts / f'scarab-node-{identity["version"]}-assembly.jar'
    shutil.copyfile(repro / 'build-1.jar', artifact)
    jvm_bom, reviews = candidate_sbom.jvm(artifact, first, identity, native_receipt)
    candidate_sbom.write(sboms / 'jvm.cdx.json', jvm_bom)
    schema_validate(root, sboms / 'jvm.cdx.json', evidence / 'jvm-schema.log')
    image_tag = 'scarab-candidate:' + uuid.uuid4().hex
    container = 'scarab-candidate-export-' + uuid.uuid4().hex[:12]
    try:
        with tempfile.TemporaryDirectory(prefix='candidate-context-', dir=root.parent) as temporary:
            context = Path(temporary)
            shutil.copyfile(artifact, context / 'app.jar')
            shutil.copyfile(repo / 'docker/candidate.Dockerfile', context / 'Dockerfile')
            from_lines = [line for line in (context / 'Dockerfile').read_text().splitlines() if line.startswith('FROM ')]
            if from_lines != ['FROM ' + BASE]:
                raise ValueError('candidate recipe and approved runtime base differ')
            run(['docker', 'pull', '--platform', 'linux/amd64', BASE], evidence / 'image-base-pull.log')
            base_inspect = json.loads(subprocess.check_output(['docker', 'image', 'inspect', BASE]))[0]
            run(['docker', 'build', '--platform', 'linux/amd64', '--build-arg', f'SCARAB_VERSION={identity["version"]}',
                 '--build-arg', f'SCARAB_COMMIT={identity["gitCommit"]}', '-t', image_tag, str(context)], evidence / 'image-build.log')
        image_id = subprocess.check_output(['docker', 'image', 'inspect', image_tag, '--format', '{{.Id}}'], text=True).strip()
        verifier = evidence / 'image-verifier'
        verifier.mkdir()
        reproducible_build.resource_check_source(verifier)
        jdk_image = candidate_manifest.read_json(repro / 'build-environment.json')['jdkImage']
        run(['docker', 'run', '--rm', '--network', 'none', '--user', f'{os.getuid()}:{os.getgid()}',
             '-v', f'{verifier}:/checks', '-v', f'{artifact}:/artifact.jar:ro', '-w', '/checks',
             jdk_image, 'javac', '-cp', '/artifact.jar', 'ArtifactResourcesCheck.java'], evidence / 'image-verifier-compile.log')
        run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp:rw,exec,nosuid,size=256m',
             '--cpus=2', '-v', f'{verifier}:/checks:ro', '--entrypoint', 'java', image_id,
             '-cp', '/checks:/opt/scarab/scarab.jar', 'ArtifactResourcesCheck', '/opt/scarab/scarab.jar',
             identity['gitCommit']], evidence / 'image-resource-smoke.log')
        run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp:rw,exec,nosuid,size=256m', '--cpus=2',
             '--entrypoint', 'java', image_id, '-cp', '/opt/scarab/scarab.jar',
             'xyz.kd5ujc.scarab.node.tools.BlstCheckMain'], evidence / 'image-native-smoke.log')
        results = [json.loads(line) for line in (evidence / 'image-native-smoke.log').read_text().splitlines() if line.startswith('{')]
        if not any(r.get('ok') and r.get('library') == 'portable' for r in results):
            raise ValueError('candidate image did not pass actual portable native/Plutus smoke')
        cli = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp:rw,exec,nosuid,size=256m',
                              '--cpus=2', image_id, '--help'], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (evidence / 'image-cli-smoke.log').write_bytes(cli.stdout)
        if cli.returncode != 2 or b'usage: follow' not in cli.stdout:
            raise ValueError('candidate image CLI smoke failed')
        docker_archive = root / 'image.docker.tar'
        subprocess.run(['docker', 'image', 'save', '-o', str(docker_archive), image_id], check=True)
        oci = artifacts / f'scarab-node-{identity["version"]}-linux-amd64.oci.tar'
        candidate_oci.convert(docker_archive, oci)
        inspected = candidate_oci.verify(oci, artifact, identity)
        if inspected['configDigest'] != image_id:
            raise ValueError('exported OCI configuration differs from the actual smoked image')
        base_layers = base_inspect['RootFS']['Layers']
        if not base_layers or inspected['diffIds'][:len(base_layers)] != base_layers:
            raise ValueError('candidate image does not retain the exact pinned runtime base filesystem')
        subprocess.run(['docker', 'create', '--name', container, image_id], check=True, stdout=subprocess.DEVNULL)
        rootfs = root / 'rootfs.tar'
        subprocess.run(['docker', 'export', '-o', str(rootfs), container], check=True)
        os_inventory, os_reviews = candidate_image_inventory.inspect(rootfs, evidence / 'legal')
        rootfs.unlink()
        subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL)
        raw_bom = subprocess.check_output(['docker', 'run', '--rm', '--network', 'none',
                                           '-e', 'SYFT_CHECK_FOR_APP_UPDATE=false',
                                           '-v', f'{root}:/data:ro', SYFT, 'scan',
                                           'docker-archive:/data/image.docker.tar', '-o', 'cyclonedx-json@1.6'])
        (evidence / 'image-scan.cdx.json').write_bytes(raw_bom)
        image_bom = candidate_sbom.image(json.loads(raw_bom), inspected['manifest']['digest'], artifact, jvm_bom, os_inventory['packages'], os_inventory['javaRelease'])
        candidate_sbom.write(sboms / 'image.cdx.json', image_bom)
        schema_validate(root, sboms / 'image.cdx.json', evidence / 'image-schema.log')
        docker_archive.unlink()
        reviews += os_reviews
        for component in image_bom['components']:
            if component['type'] != 'file' and not component.get('licenses'):
                reviews.append(dict(component=component['bom-ref'], reason='component licence interpretation requires review'))
        candidate_sbom.write(evidence / 'licence-review.json', dict(status='review-required', unresolved=reviews,
                            scope='Inventory evidence only; legal and vulnerability/advisory approval are separate.'))
        candidate_sbom.write(evidence / 'image-inventory.json', dict(oci=inspected, os=os_inventory,
                            imageId=image_id, baseImage=BASE, archiveSha256=candidate_manifest.sha256(oci),
                            baseConfigDigest=base_inspect['Id'], baseDiffIds=base_layers,
                            scannerBom=candidate_manifest.file_record(root, evidence / 'image-scan.cdx.json')))
        candidate_sbom.write(evidence / 'toolchain.json', dict(jdk=candidate_manifest.read_json(repro / 'build-environment.json'),
                            nativeRecipe=native_receipt['recipe'], syftImage=SYFT, cycloneDxImage=CYCLONEDX,
                            imageRecipeSha256=candidate_manifest.sha256(repo / 'docker/candidate.Dockerfile'),
                            imageVerifier=[candidate_manifest.file_record(root, verifier / name) for name in
                                           ['ArtifactResourcesCheck.java', 'ArtifactResourcesCheck.class']],
                            baseImage=BASE, dockerVersion=subprocess.check_output(['docker', 'version', '--format', '{{json .}}'], text=True).strip()))
        def record(path):
            return candidate_manifest.file_record(root, path)
        manifest = dict(schemaVersion=1, status='verified', assurance=dict(scope='inventory-hash-coverage-subject-binding',
                        licenceReview=dict(status='review-required', unresolvedCount=len(reviews))),
                        source=dict(buildIdentity=identity, archive=record(repro / 'source.tar')),
                        image=dict(platform='linux/amd64', manifest={k: inspected['manifest'][k] for k in ['digest', 'mediaType']},
                                   configDigest=inspected['configDigest'], baseImage=BASE, reproducibility='build-once-from-verified-jar'),
                        subjects=[], evidence=[])
        for path, kind, bom, subject_kind, digest in [
            (artifact, 'jar', sboms / 'jvm.cdx.json', 'jar', 'sha256:' + candidate_manifest.sha256(artifact)),
            (oci, 'oci-archive', sboms / 'image.cdx.json', 'oci-manifest', inspected['manifest']['digest'])]:
            manifest['subjects'].append(dict(name=path.name, kind=kind, file=record(path),
                                             bom=dict(file=record(bom), subject=dict(kind=subject_kind, digest=digest))))
        for kind, path in [
            ('source-export', repro / 'source-manifest.json'), ('reproducibility', repro / 'verified-artifacts.json'),
            ('native-reproducibility', native / 'receipt.json'), ('assembly-coverage', repro / 'inventory-1/assembly-coverage.json'),
            ('jvm-resolution', repro / 'inventory-1/jvm-resolution.json'), ('image-inventory', evidence / 'image-inventory.json'),
            ('toolchain', evidence / 'toolchain.json'), ('licence-review', evidence / 'licence-review.json')]:
            manifest['evidence'].append(dict(kind=kind, file=record(path)))
        candidate_sbom.write(root / 'candidate.json', manifest)
        candidate_manifest.validate(root)
        candidate_sbom.write(evidence / 'attempt.json', dict(status='verified', source=identity,
                            manifestSha256=candidate_manifest.sha256(root / 'candidate.json')))
    finally:
        subprocess.run(['docker', 'rm', '-f', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['docker', 'image', 'rm', image_tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print(f'PASS candidate manifest: {root / "candidate.json"}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='fresh directory outside source checkout')
    parser.add_argument('--cache', type=Path, default=Path('.cache'))
    parser.add_argument('--image', default='scarab-jdk:local')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--release-tag')
    parser.add_argument('--channel', choices=['alpha', 'beta', 'preprod', 'prod'])
    parser.add_argument('--expected-commit')
    parser.add_argument('--expected-tag-object')
    parser.add_argument('--source-ref')
    build(parser.parse_args())


if __name__ == '__main__':
    main()
