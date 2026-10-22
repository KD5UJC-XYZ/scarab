# SPDX-License-Identifier: Apache-2.0
"""Reuse immutable, previously uploaded signature bytes only after authenticating every subject."""
from pathlib import Path
import shutil
import tarfile
import tempfile

from publication_candidate import descriptors
from publication_common import file_digest, load_json, require, safe_path, sha256
from publication_download import download
from verify_published_release import verify_subject

BUNDLES = {'provenance.sigstore.json', 'jar-sbom.sigstore.json',
           'image-provenance.sigstore.json', 'image-sbom.sigstore.json'}


def reuse_bundles(github, release, root, manifest, archive, image_repository, gh):
    """No service writes and no local replacement until all retained bundles have passed."""
    inventory = github.pages(f"/releases/{release['id']}/assets")
    require(len(inventory) == len({row['name'] for row in inventory}), 'ambiguous retained release assets')
    retained = [row for row in inventory if row['name'] in BUNDLES]
    if not retained:
        return
    identity = manifest['source']['buildIdentity']
    authority = github.request('GET', '/repos/' + github.repository)
    jar = next(row for row in manifest['subjects'] if row['kind'] == 'jar')
    image = next(row for row in manifest['subjects'] if row['kind'] == 'oci-archive')
    with tempfile.TemporaryDirectory(prefix='scarab-retained-signatures-', dir=root.parent) as temporary:
        scratch = Path(temporary)
        manifest_path = scratch / 'manifest.json'
        with tarfile.open(safe_path(root, image['file']['path'])) as oci:
            member = oci.getmember('blobs/sha256/' + manifest['image']['manifest']['digest'][7:])
            require(member.isfile() and 0 < member.size <= 4 * 1024 * 1024, 'bounded original OCI manifest required')
            with oci.extractfile(member) as stream:
                manifest_path.write_bytes(stream.read(member.size + 1))
        require('sha256:' + file_digest(manifest_path) == manifest['image']['manifest']['digest'],
                'original OCI manifest differs from verified candidate')
        for asset in retained:
            name = asset['name']
            require(type(asset.get('size')) is int and 0 < asset['size'] <= 32 * 1024 * 1024
                    and isinstance(asset.get('digest'), str) and asset['digest'].startswith('sha256:'),
                    'invalid retained signature descriptor')
            bundle = scratch / name
            download(github.base + github.repo_path(f"/releases/assets/{asset['id']}"), bundle,
                     {'sha256': sha256(asset['digest'][7:]), 'size': asset['size']}, token=github.token,
                     local=github.base.startswith('http://127.0.0.1:') and not github.token)

            def verify(subject, predicate='https://slsa.dev/provenance/v1', expected=None, subject_name=None):
                return verify_subject(subject, file_digest(subject), bundle, authority,
                                      identity['gitCommit'], identity['gitTag'], gh, predicate, expected, subject_name)

            if name == 'provenance.sigstore.json':
                for subject in [root / 'candidate.json', root / 'SHA256SUMS', archive,
                                *[safe_path(root, row['path']) for row in descriptors(manifest)]]:
                    verify(subject)
            elif name == 'jar-sbom.sigstore.json':
                verify(safe_path(root, jar['file']['path']), 'https://cyclonedx.org/bom',
                       load_json(safe_path(root, jar['bom']['file']['path'])))
            elif name == 'image-provenance.sigstore.json':
                verify(manifest_path, subject_name=image_repository)
            else:
                verify(manifest_path, 'https://cyclonedx.org/bom',
                       load_json(safe_path(root, image['bom']['file']['path'])), image_repository)
        # Missing bundles remain newly generated. Original uploaded bytes are never replaced remotely.
        for asset in retained:
            shutil.copyfile(scratch / asset['name'], root / asset['name'])
