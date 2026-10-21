#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Convert a local single-platform Docker export to content-addressed OCI, and verify its subjects."""
import hashlib
import gzip
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import tempfile

from candidate_manifest import MEDIA_TYPE, sha256


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def descriptor(raw, media_type):
    return dict(mediaType=media_type, digest='sha256:' + hashlib.sha256(raw).hexdigest(), size=len(raw))


def convert(docker_archive, output):
    with tempfile.TemporaryDirectory(prefix='oci-layout-', dir=output.parent) as scratch:
        layout = Path(scratch)
        blobs = layout / 'blobs/sha256'
        blobs.mkdir(parents=True)
        with tarfile.open(docker_archive) as source:
            manifest = json.load(source.extractfile('manifest.json'))
            if len(manifest) != 1:
                raise ValueError('one platform/image required')
            record = manifest[0]
            config = source.extractfile(record['Config']).read()
            config_descriptor = descriptor(config, 'application/vnd.oci.image.config.v1+json')
            (blobs / config_descriptor['digest'][7:]).write_bytes(config)
            layers = []
            for name in record['Layers']:
                temporary = layout / 'layer'
                with temporary.open('wb') as stream:
                    shutil.copyfileobj(source.extractfile(name), stream)
                layer_hash = sha256(temporary)
                with temporary.open('rb') as stream:
                    compressed = stream.read(2) == b'\x1f\x8b'
                layers.append(dict(mediaType='application/vnd.oci.image.layer.v1.tar' + ('+gzip' if compressed else ''),
                                   digest='sha256:' + layer_hash, size=temporary.stat().st_size))
                temporary.rename(blobs / layer_hash)
        raw_manifest = encoded(dict(schemaVersion=2, mediaType=MEDIA_TYPE, config=config_descriptor, layers=layers))
        manifest_descriptor = descriptor(raw_manifest, MEDIA_TYPE)
        (blobs / manifest_descriptor['digest'][7:]).write_bytes(raw_manifest)
        (layout / 'oci-layout').write_bytes(encoded({'imageLayoutVersion': '1.0.0'}))
        (layout / 'index.json').write_bytes(encoded(dict(schemaVersion=2, manifests=[manifest_descriptor])))
        with tarfile.open(output, 'w') as archive:
            for path in sorted(layout.rglob('*')):
                if path.is_file():
                    info = tarfile.TarInfo(path.relative_to(layout).as_posix())
                    info.size = path.stat().st_size
                    info.mode = 0o644
                    with path.open('rb') as stream:
                        archive.addfile(info, stream)
    return manifest_descriptor, config_descriptor


def verify(archive_path, artifact, identity, expected_manifest=None):
    with tarfile.open(archive_path) as archive:
        members = archive.getmembers()
        names = [m.name for m in members]
        if len(names) != len(set(names)) or any(not m.isfile() for m in members):
            raise ValueError('OCI transport must contain unique regular layout files')
        for member in members:
            if member.name in {'index.json', 'oci-layout'}:
                continue
            if not member.name.startswith('blobs/sha256/') or len(member.name) != len('blobs/sha256/') + 64:
                raise ValueError('unexpected OCI transport member')
            with archive.extractfile(member) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != member.name.rsplit('/', 1)[1]:
                    raise ValueError('OCI blob hash mismatch')
        index = json.load(archive.extractfile('index.json'))
        if len(index.get('manifests', [])) != 1:
            raise ValueError('one OCI manifest required')

        def blob(record):
            digest = record['digest']
            path = 'blobs/sha256/' + digest.removeprefix('sha256:')
            member = archive.getmember(path)
            if not digest.startswith('sha256:') or member.size != record['size']:
                raise ValueError('OCI descriptor size/digest mismatch')
            return archive.extractfile(member)

        manifest_descriptor = index['manifests'][0]
        if manifest_descriptor['mediaType'] != MEDIA_TYPE or expected_manifest is not None and manifest_descriptor['digest'] != expected_manifest:
            raise ValueError('wrong OCI manifest identity/type')
        manifest = json.load(blob(manifest_descriptor))
        config = json.load(blob(manifest['config']))
        if config.get('architecture') != 'amd64' or config.get('os') != 'linux':
            raise ValueError('wrong image platform')
        runtime = config['config']
        labels = runtime['Labels']
        if labels.get('org.opencontainers.image.revision') != identity['gitCommit'] or labels.get('org.opencontainers.image.version') != identity['version']:
            raise ValueError('image labels differ from frozen source identity')
        if runtime.get('Entrypoint') != ['java', '-jar', '/opt/scarab/scarab.jar'] or runtime.get('User') != '10001:10001':
            raise ValueError('candidate image runtime contract changed')
        layers = manifest['layers']
        diff_ids = []
        for record in layers:
            with blob(record) as stream:
                if record['mediaType'] == 'application/vnd.oci.image.layer.v1.tar+gzip':
                    with gzip.GzipFile(fileobj=stream) as uncompressed:
                        diff_ids.append('sha256:' + hashlib.file_digest(uncompressed, 'sha256').hexdigest())
                elif record['mediaType'] == 'application/vnd.oci.image.layer.v1.tar':
                    diff_ids.append(record['digest'])
                else:
                    raise ValueError('unsupported OCI layer encoding')
        if config.get('rootfs', {}).get('diff_ids') != diff_ids:
            raise ValueError('OCI uncompressed layer identities differ from runtime configuration')
        with tarfile.open(fileobj=blob(layers[-1]), mode='r|*') as layer:
            found = []
            for member in layer:
                path = member.name.removeprefix('./').rstrip('/')
                if path.startswith('/') or '..' in PurePosixPath(path).parts:
                    raise ValueError('unsafe application layer path')
                if member.isfile():
                    if path != 'opt/scarab/scarab.jar':
                        raise ValueError('unexpected candidate application layer file')
                    with layer.extractfile(member) as stream:
                        found.append((member.size, hashlib.file_digest(stream, 'sha256').hexdigest()))
                elif not member.isdir() or path not in {'opt', 'opt/scarab'}:
                    raise ValueError('unexpected application layer link or directory')
            if found != [(artifact.stat().st_size, sha256(artifact))]:
                raise ValueError('image does not embed exactly the verified JAR')
    return dict(manifest=manifest_descriptor, configDigest=manifest['config']['digest'],
                layers=layers, diffIds=diff_ids, labels=labels)
