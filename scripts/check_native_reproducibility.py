#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Rebuild the committed Linux/amd64 JNI library twice; compare without replacing any input."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
LIBRARY = 'blst-native/src/main/resources/supranational/blst/Linux/amd64/libblst.so'
IDENTITY = 'blst-native/src/main/scala/xyz/kd5ujc/scarab/blst/Blst.scala'
INPUTS = ('docker/blst.Dockerfile', 'docker/blst.Dockerfile.dockerignore',
          'blst-native/native/raw-dst.c', 'scripts/check_native_reproducibility.py', IDENTITY, LIBRARY)


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def recipe_identity(source):
    recipe = (source / 'docker/blst.Dockerfile').read_text()
    def one(pattern):
        found = re.findall(pattern, recipe, re.MULTILINE)
        if len(found) != 1:
            raise ValueError(f'expected exactly one pinned recipe identity: {pattern}')
        return found[0]
    return {'baseImage': one(r'^FROM (ubuntu@sha256:[a-f0-9]{64}) AS build$'),
            'sourceCommit': one(r'^ARG BLST_COMMIT=([a-f0-9]{40})$'),
            'sourceArchiveSha256': one(r'^ARG BLST_SOURCE_ARCHIVE_SHA256=([a-f0-9]{64})$'),
            'flags': one(r'^ARG BLST_FLAGS=(.+)$'),
            'snapshot': sorted(set(re.findall(r'https://snapshot.ubuntu.com/ubuntu/(\d{8}T\d{6}Z)', recipe)))}


def validate_artifact(directory, identity, adapter_hash):
    for name in ('libblst.so', 'blst-source.tar', 'packages.tsv', 'downloaded-debs.sha256',
                 'blst-commit.txt', 'blst-tree.txt', 'raw-dst.sha256', 'exports.txt', 'symbols.txt',
                 'elf.txt', 'disassembly.txt'):
        if not (directory / name).is_file() or (directory / name).is_symlink():
            raise ValueError(f'missing or non-regular build evidence: {name}')
    if sha256(directory / 'blst-source.tar') != identity['sourceArchiveSha256']:
        raise ValueError('native source archive differs from the pinned input')
    if (directory / 'blst-commit.txt').read_text().strip() != identity['sourceCommit']:
        raise ValueError('native source commit differs from the pinned input')
    tree = (directory / 'blst-tree.txt').read_text().strip()
    if not re.fullmatch('[a-f0-9]{40}', tree):
        raise ValueError('invalid native source tree identity')
    if (directory / 'raw-dst.sha256').read_text().split() != [adapter_hash, '/src/raw-dst.c']:
        raise ValueError('compiled raw-DST adapter differs from the frozen input')
    packages = (directory / 'packages.tsv').read_text().splitlines()
    if not packages or packages != sorted(set(packages)):
        raise ValueError('installed package inventory is empty, duplicated or unordered')
    for row in packages:
        if len(row.split('\t')) != 3 or not all(row.split('\t')):
            raise ValueError('invalid installed package identity')
    debs = (directory / 'downloaded-debs.sha256').read_text().splitlines()
    if not debs or any(not re.fullmatch(r'[a-f0-9]{64}  /var/cache/apt/archives/[^/]+\.deb', row)
                       for row in debs):
        raise ValueError('missing or invalid downloaded package hashes')
    if any(path.is_symlink() for path in directory.rglob('*')):
        raise ValueError('build evidence contains a symlink')
    indexes = sorted((directory / 'apt').glob('*InRelease'))
    if len(indexes) != 3 or any(p.is_symlink() for p in indexes):
        raise ValueError('expected all three signed Ubuntu snapshot indexes')
    suites = [re.findall(r'^Suite: (.+)$', path.read_text(), re.MULTILINE) for path in indexes]
    if sorted(suites) != [['jammy'], ['jammy-security'], ['jammy-updates']]:
        raise ValueError('signed snapshot indexes must cover each configured Ubuntu pocket once')
    return {'sha256': sha256(directory / 'libblst.so'),
            'bytes': (directory / 'libblst.so').stat().st_size,
            'sourceTree': tree, 'installedPackageCount': len(packages),
            'installedPackagesSha256': sha256(directory / 'packages.tsv'),
            'downloadedPackagesSha256': sha256(directory / 'downloaded-debs.sha256'),
            'signedIndexes': {p.name: sha256(p) for p in indexes},
            'files': {p.relative_to(directory).as_posix(): sha256(p)
                      for p in sorted(directory.rglob('*')) if p.is_file()}}


def compare_artifacts(reference, rebuilt, destination):
    wanted, found = reference.read_bytes(), rebuilt.read_bytes()
    if wanted == found:
        return
    differences = []
    for offset, (left, right) in enumerate(zip(wanted, found)):
        if left != right:
            differences.append(offset)
        if len(differences) == 32:
            break
    diagnostics = {'referenceSha256': sha256(reference), 'rebuiltSha256': sha256(rebuilt),
                   'referenceBytes': len(wanted), 'rebuiltBytes': len(found),
                   'firstDifferentOffsets': differences}
    destination.write_text(json.dumps(diagnostics, indent=2) + '\n')
    raise ValueError(f'native library bytes differ; see {destination}')


def compare_build_inputs(first, second):
    for key in ('sourceTree', 'installedPackagesSha256', 'downloadedPackagesSha256', 'signedIndexes'):
        if first[key] != second[key]:
            raise ValueError(f'independent builds used different inputs: {key}')


def run_logged(command, log):
    with log.open('w') as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=False)
    if result.returncode:
        raise ValueError(f'command exited {result.returncode}; see {log}')


def verify_signatures(directory, base, log):
    indexes = sorted((directory / 'apt').glob('*InRelease'))
    command = ['docker', 'run', '--rm', '--network', 'none', '--platform', 'linux/amd64',
               '--user', f'{os.getuid()}:{os.getgid()}', '-v', f'{directory / "apt"}:/evidence:ro', base,
               'sh', '-ec', 'for index in /evidence/*InRelease; do '
               'gpgv --status-fd 1 --keyring /usr/share/keyrings/ubuntu-archive-keyring.gpg "$index" || exit 1; done']
    run_logged(command, log)
    if len(indexes) != 3 or log.read_text().count('[GNUPG:] VALIDSIG ') != 3:
        raise ValueError('expected three verified Ubuntu archive signatures')


def verify(root, output):
    if output.exists() or root == output or root in output.parents:
        raise ValueError('output must be fresh and outside the source checkout')
    output.mkdir(parents=True)
    receipt = {'schemaVersion': 1, 'status': 'failed', 'platform': 'linux/amd64',
               'contract': 'two uncached builds equal the committed portable JNI library', 'builds': []}
    try:
        identity = recipe_identity(root)
        receipt['recipe'] = identity
        receipt['dockerVersion'] = json.loads(subprocess.check_output(
            ['docker', 'version', '--format', '{{json .}}'], text=True))
        commit = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
        receipt['repositoryCommit'] = commit
        receipt['worktreeDirty'] = bool(subprocess.check_output(
            ['git', '-C', str(root), 'status', '--porcelain', '--untracked-files=normal'], text=True))
        if any((root / name).is_symlink() or not (root / name).is_file() for name in INPUTS):
            raise ValueError('source inputs must be regular files, not symlinks')
        inputs = {name: sha256(root / name) for name in INPUTS}
        receipt['inputs'] = inputs
        expected = re.findall(r'val portableSha256: String = "([a-f0-9]{64})"', (root / IDENTITY).read_text())
        if expected != [inputs[LIBRARY]]:
            raise ValueError('committed library differs from the startup identity')
        with tempfile.TemporaryDirectory(prefix='scarab-native-source-') as temporary:
            source = Path(temporary)
            for name in INPUTS:
                target = source / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(root / name, target)
                if sha256(target) != inputs[name]:
                    raise ValueError(f'source changed while freezing: {name}')
            for number in (1, 2):
                directory = output / f'build-{number}'
                run_logged(['docker', 'build', '--no-cache', '--platform', 'linux/amd64',
                            '--progress', 'plain', '-f', str(source / 'docker/blst.Dockerfile'),
                            '--target', 'artifact', '--output', f'type=local,dest={directory}', str(source)],
                           output / f'build-{number}.log')
                details = validate_artifact(directory, identity, inputs['blst-native/native/raw-dst.c'])
                verify_signatures(directory, identity['baseImage'], output / f'signatures-{number}.log')
                receipt['builds'].append(details)
                compare_artifacts(source / LIBRARY, directory / 'libblst.so', output / f'mismatch-{number}.json')
            compare_build_inputs(*receipt['builds'])
            compare_artifacts(output / 'build-1/libblst.so', output / 'build-2/libblst.so', output / 'mismatch-pair.json')
            for name, expected_hash in inputs.items():
                if sha256(root / name) != expected_hash:
                    raise ValueError(f'source changed during verification: {name}')
        receipt['status'] = 'passed'
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        receipt['error'] = str(error)
        raise
    finally:
        (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        receipt = verify(args.root.resolve(), args.output.resolve())
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f'native reproducibility failed: {error}\n')
    print(f"Verified both clean native builds: {receipt['builds'][0]['sha256']}")


if __name__ == '__main__':
    main()
