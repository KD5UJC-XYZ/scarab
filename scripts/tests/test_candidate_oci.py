# SPDX-License-Identifier: Apache-2.0
import hashlib
import gzip
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import candidate_manifest
import candidate_oci


class CandidateOciTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.jar = self.root / 'app.jar'
        self.jar.write_bytes(b'verified JAR bytes')
        self.identity = dict(gitCommit='a' * 40, version='1.2.3-beta.1')

    def tar(self, files):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode='w') as archive:
            for name, raw in files.items():
                member = tarfile.TarInfo(name)
                member.size = len(raw)
                archive.addfile(member, io.BytesIO(raw))
        return output.getvalue()

    def image(self, jar=None, version='1.2.3-beta.1', compressed=False):
        layer = self.tar({'opt/scarab/scarab.jar': self.jar.read_bytes() if jar is None else jar})
        config = json.dumps(dict(architecture='amd64', os='linux',
            rootfs=dict(type='layers', diff_ids=['sha256:' + hashlib.sha256(layer).hexdigest()]),
            config=dict(Entrypoint=['java', '--sun-misc-unsafe-memory-access=allow', '-jar', '/opt/scarab/scarab.jar'], User='10001:10001',
                        Labels={'org.opencontainers.image.revision': 'a' * 40,
                                'org.opencontainers.image.version': version}))).encode()
        archive = self.root / 'docker.tar'
        archive.write_bytes(self.tar({'manifest.json': json.dumps([dict(Config='config.json', Layers=['layer.tar'])]).encode(),
                                     'config.json': config, 'layer.tar': gzip.compress(layer) if compressed else layer}))
        output = self.root / 'image.oci.tar'
        candidate_oci.convert(archive, output)
        return output

    def test_actual_oci_descriptor_layers_config_and_jar_are_bound(self):
        result = candidate_oci.verify(self.image(), self.jar, self.identity)
        self.assertEqual(result['manifest']['mediaType'], candidate_manifest.MEDIA_TYPE)
        self.assertEqual(len(result['layers']), 1)
        self.assertRegex(result['configDigest'], '^sha256:[a-f0-9]{64}$')

    def test_gzip_transport_digest_and_uncompressed_filesystem_identity_are_distinct(self):
        result = candidate_oci.verify(self.image(compressed=True), self.jar, self.identity)
        self.assertTrue(result['layers'][0]['mediaType'].endswith('+gzip'))
        self.assertNotEqual(result['diffIds'][0], result['layers'][0]['digest'])

    def test_wrong_embedded_jar_identity_and_manifest_fail(self):
        for change in ['jar', 'version', 'digest']:
            with self.subTest(change=change):
                image = self.image(jar=b'different' if change == 'jar' else None,
                                   version='1.2.3' if change == 'version' else self.identity['version'])
                with self.assertRaises(ValueError):
                    candidate_oci.verify(image, self.jar, self.identity,
                                         expected_manifest='sha256:' + 'b' * 64 if change == 'digest' else None)

    def test_mutated_blob_and_duplicate_layout_member_fail(self):
        for duplicate in [False, True]:
            with self.subTest(duplicate=duplicate):
                image = self.image()
                with tarfile.open(image) as archive:
                    files = [(m.name, archive.extractfile(m).read()) for m in archive.getmembers()]
                with tarfile.open(image, 'w') as archive:
                    for index, (name, raw) in enumerate(files):
                        if name.startswith('blobs/') and not duplicate:
                            raw += b'tampered'
                        member = tarfile.TarInfo(name)
                        member.size = len(raw)
                        archive.addfile(member, io.BytesIO(raw))
                        if index == 0 and duplicate:
                            archive.addfile(member, io.BytesIO(raw))
                with self.assertRaises(ValueError):
                    candidate_oci.verify(image, self.jar, self.identity)

    def test_file_descriptors_reject_traversal_symlink_and_modified_bytes(self):
        record = candidate_manifest.file_record(self.root, self.jar)
        self.assertEqual(candidate_manifest.checked_file(self.root, record), self.jar)
        for path in ['../app.jar', '/app.jar', 'a/../app.jar', './app.jar', 'a\\app.jar']:
            with self.subTest(path=path), self.assertRaises(ValueError):
                candidate_manifest.checked_file(self.root, dict(record, path=path))
        link = self.root / 'link.jar'
        link.symlink_to(self.jar)
        with self.assertRaises(ValueError):
            candidate_manifest.checked_file(self.root, dict(record, path='link.jar'))
        self.jar.write_bytes(b'tampered')
        with self.assertRaises(ValueError):
            candidate_manifest.checked_file(self.root, record)

    def test_duplicate_json_fields_fail_before_later_values_mask_them(self):
        path = self.root / 'candidate.json'
        path.write_text('{"status":"failed","status":"verified"}')
        with self.assertRaisesRegex(ValueError, 'duplicate JSON'):
            candidate_manifest.read_json(path)


    def binding(self, mode='index', ambiguous=False):
        image = self.image(compressed=True)
        expected = candidate_oci.verify(image, self.jar, self.identity)
        archive = self.root / 'docker.tar'
        with tarfile.open(archive) as source:
            files = {member.name: source.extractfile(member).read() for member in source.getmembers()}
        config = json.loads(files['config.json'])
        live = dict(Id=expected['configDigest'], Architecture='amd64', Os='linux', Config=config['config'],
                    RootFS=dict(Type='layers', Layers=expected['diffIds']))
        if mode != 'legacy':
            raw_config = files['config.json']
            config_record = candidate_oci.descriptor(raw_config, candidate_oci.CONFIG_TYPE)
            files['blobs/sha256/' + config_record['digest'][7:]] = raw_config
            raw_manifest = candidate_oci.encoded(dict(schemaVersion=2, config=config_record, layers=expected['layers']))
            manifest = candidate_oci.descriptor(raw_manifest, candidate_manifest.MEDIA_TYPE)
            files['blobs/sha256/' + manifest['digest'][7:]] = raw_manifest
            root = manifest
            if mode == 'index':
                child = dict(manifest, platform=dict(architecture='amd64', os='linux'))
                raw_index = candidate_oci.encoded(dict(schemaVersion=2, manifests=[child, child] if ambiguous else [child]))
                root = candidate_oci.descriptor(raw_index, 'application/vnd.oci.image.index.v1+json')
                files['blobs/sha256/' + root['digest'][7:]] = raw_index
            files['index.json'] = candidate_oci.encoded(dict(schemaVersion=2, manifests=[root]))
            live['Id'] = root['digest']
            live['Descriptor'] = root
        archive.write_bytes(self.tar(files))
        return archive, live, expected

    def test_live_legacy_manifest_and_containerd_index_ids_bind_same_config(self):
        for mode in ['legacy', 'manifest', 'index']:
            with self.subTest(mode=mode):
                archive, live, expected = self.binding(mode)
                binding = candidate_oci.bind_docker_image(archive, live, expected)
                candidate_oci.verify_docker_binding(binding, expected)
                self.assertEqual(binding['inspect']['Id'], live['Id'])
                self.assertNotIn('Env', binding['inspect']['Config'])

    def test_live_index_rejects_ambiguous_platform_and_fake_image_id(self):
        archive, live, expected = self.binding(ambiguous=True)
        with self.assertRaisesRegex(ValueError, 'ambiguous'):
            candidate_oci.bind_docker_image(archive, live, expected)
        archive, live, expected = self.binding()
        with self.assertRaisesRegex(ValueError, 'absent'):
            candidate_oci.bind_docker_image(archive, dict(live, Id='sha256:' + 'f' * 64), expected)

    def test_retained_graph_rejects_tampered_config_and_live_runtime(self):
        import copy
        archive, live, expected = self.binding()
        original = candidate_oci.bind_docker_image(archive, live, expected)
        for field in ['document', 'rootfs', 'runtime', 'descriptor', 'extra']:
            with self.subTest(field=field):
                binding = copy.deepcopy(original)
                if field == 'document':
                    binding['documents'][expected['configDigest']] += 'eA=='
                elif field == 'rootfs':
                    binding['inspect']['RootFS']['Layers'] = ['sha256:' + '0' * 64]
                elif field == 'runtime':
                    binding['inspect']['Config']['User'] = '0'
                elif field == 'descriptor':
                    binding['root']['size'] += 1
                else:
                    binding['documents']['sha256:' + 'f' * 64] = 'eA=='
                with self.assertRaises((ValueError, KeyError)):
                    candidate_oci.verify_docker_binding(binding, expected)
