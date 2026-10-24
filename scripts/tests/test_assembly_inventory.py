# SPDX-License-Identifier: Apache-2.0
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import assembly_inventory as inventory
import candidate_sbom


class AssemblyInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.project = self.root / 'classes'
        self.project.mkdir()
        native = self.project / inventory.PORTABLE
        native.parent.mkdir(parents=True)
        native.write_bytes(b'portable')
        self.library = self.root / 'library.jar'
        self.source = {'x/Library.class': b'class-bytes', 'META-INF/services/test.Service': b'Library\n',
                       'META-INF/maven/g/a/pom.xml': b'<project/>', 'LICENSE': b'library license',
                       'META-INF/versions/11/x/Library.class': b'versioned',
                       'native/Linux/a.so': b'other-native', inventory.PLUTUS_CRYPTO: b'plutus-crypto',
                       'module-info.class': b'module'}
        self.write_zip(self.library, self.source)
        self.artifact = self.root / 'application.jar'
        self.result = {k: v for k, v in self.source.items() if k not in {'LICENSE', 'module-info.class', 'META-INF/maven/g/a/pom.xml'}}
        self.result.update({'LICENSE_library-1': b'library license', inventory.PORTABLE: b'portable',
                            'META-INF/MANIFEST.MF': b'Main-Class: Node\n'})
        self.result['META-INF/services/test.Service'] = b'Library'
        self.write_zip(self.artifact, self.result)
        self.graph = dict(classpath=[str(self.project), str(self.library)], modules=[
            dict(coordinate='g:library:1', artifacts=[dict(path=str(self.library))],
                 licenses=[dict(name='MIT', url='https://opensource.org/license/mit')], callers=['project:node:1'])])

    def write_zip(self, path, entries):
        with zipfile.ZipFile(path, 'w') as archive:
            for name, raw in entries.items():
                archive.writestr(name, raw)

    def test_all_output_and_original_contributors_have_precise_contracts(self):
        result = inventory.reconcile(self.artifact, self.graph)
        self.assertEqual(result['finalEntries'], len(self.result))
        policies = {row['input']: row['policy'] for row in result['entries']}
        self.assertEqual(policies['module-info.class'], 'discard-module-descriptor')
        self.assertEqual(policies['LICENSE'], 'rename-legal-or-readme')
        self.assertEqual(policies[inventory.PORTABLE], 'project-native-overlay')
        self.assertEqual(len(result['components']), 1)
        self.assertEqual(len(result['nativeResources']), 3)

    def test_an_artifact_without_the_plutus_crypto_library_is_refused(self):
        source = {k: v for k, v in self.source.items() if k != inventory.PLUTUS_CRYPTO}
        self.write_zip(self.library, source)
        self.write_zip(self.artifact, {k: v for k, v in self.result.items() if k != inventory.PLUTUS_CRYPTO})
        with self.assertRaisesRegex(ValueError, 'missing native'):
            inventory.reconcile(self.artifact, self.graph)

    def test_added_lost_modified_and_merged_service_bytes_are_rejected(self):
        for change in ['added', 'lost', 'modified', 'service', 'native', 'multi-release']:
            with self.subTest(change=change):
                changed = dict(self.result)
                if change == 'added':
                    changed['injected.class'] = b'extra'
                elif change == 'lost':
                    del changed['x/Library.class']
                else:
                    target = {'modified': 'x/Library.class', 'service': 'META-INF/services/test.Service',
                              'native': inventory.PORTABLE, 'multi-release': 'META-INF/versions/11/x/Library.class'}[change]
                    changed[target] = b'changed'
                self.write_zip(self.artifact, changed)
                with self.assertRaises(ValueError):
                    inventory.reconcile(self.artifact, self.graph)

    def test_unresolved_artifacts_and_lost_licence_text_fail(self):
        changed = copy.deepcopy(self.graph)
        changed['modules'] = []
        with self.assertRaisesRegex(ValueError, 'unresolved'):
            inventory.reconcile(self.artifact, changed)
        del self.result['LICENSE_library-1']
        self.write_zip(self.artifact, self.result)
        with self.assertRaisesRegex(ValueError, 'legal'):
            inventory.reconcile(self.artifact, self.graph)

    def test_native_receipt_must_match_actual_packaged_bytes(self):
        coverage = inventory.reconcile(self.artifact, self.graph)
        native = dict(status='passed', builds=[dict(sha256='0' * 64)] * 2)
        with self.assertRaisesRegex(ValueError, 'native source rebuild'):
            candidate_sbom.jvm(self.artifact, coverage, dict(version='1', gitCommit='a' * 40), native)

    def test_missing_os_package_cannot_be_hidden_by_nonempty_scanner(self):
        raw = dict(bomFormat='CycloneDX', specVersion='1.6', components=[
            dict(name='some-other-package', version='1', purl='pkg:deb/ubuntu/other@1', **{'bom-ref': 'other'})])
        with self.assertRaisesRegex(ValueError, 'installed OS package'):
            candidate_sbom.image(raw, 'sha256:' + 'a' * 64, self.artifact, {}, [
                dict(name='required-package', version='1', architecture='amd64', copyrightSha256='b' * 64)], '')
