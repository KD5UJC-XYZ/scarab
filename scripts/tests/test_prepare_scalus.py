# SPDX-License-Identifier: Apache-2.0
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_scalus as scalus


class ScalusBootstrapTests(unittest.TestCase):
    def test_stable_source_path_cleans_its_own_data_and_preserves_preexisting_data(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(scalus, "SOURCE_ROOT", Path(directory)):
            digest = "a" * 64
            with scalus.fixed_source(digest) as source:
                (source / "owned").write_text("input")
                expected = source
            self.assertFalse(expected.exists())
            with self.assertRaisesRegex(RuntimeError, "controlled failure"):
                with scalus.fixed_source(digest) as source:
                    self.assertEqual(source, expected)
                    raise RuntimeError("controlled failure")
            self.assertFalse(expected.exists())
            expected.mkdir()
            preserved = expected / "unknown-owner"
            preserved.write_text("preserve")
            with self.assertRaises(scalus.BootstrapError):
                with scalus.fixed_source(digest):
                    self.fail("must not enter a pre-existing source directory")
            self.assertEqual(preserved.read_text(), "preserve")

    def test_patch_requires_every_original_line_and_offset(self):
        patch_text = '--- a/Cek.scala\n+++ b/Cek.scala\n@@ -1,2 +1,3 @@\n one\n-two\n+hook\n+two\n'
        self.assertEqual(scalus.apply_patch(b'one\ntwo\n', patch_text, 'Cek.scala'), b'one\nhook\ntwo\n')
        for source, changed in [(b'changed\ntwo\n', patch_text),
                                (b'one\ntwo\n', patch_text.replace('+1,3', '+2,3')),
                                (b'one\ntwo\n', patch_text.replace('a/Cek', 'a/Other'))]:
            with self.assertRaises(scalus.BootstrapError) as failure:
                scalus.apply_patch(source, changed, 'Cek.scala')
            self.assertEqual(failure.exception.kind, scalus.Problem.PATCH)

    def test_every_bootstrap_input_affects_the_coordinate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in scalus.INPUTS:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
            original = scalus.input_digest(root)
            for name in scalus.INPUTS:
                path = root / name
                before = path.read_bytes()
                path.write_bytes(before + b'changed')
                self.assertNotEqual(scalus.input_digest(root), original)
                path.write_bytes(before)
            self.assertEqual(scalus.input_digest(root), original)

    def test_corrupt_download_is_not_replaced_or_used(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.tar.gz'
            path.write_bytes(b'corrupt')
            with patch.object(scalus.urllib.request, 'urlopen') as network:
                with self.assertRaises(scalus.BootstrapError) as failure:
                    scalus.download('https://example.invalid', path, scalus.sha(b'original'), False)
                network.assert_not_called()
            self.assertEqual(failure.exception.kind, scalus.Problem.HASH)
            self.assertEqual(path.read_bytes(), b'corrupt')

    def test_offline_missing_download_never_uses_network(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(scalus.urllib.request, 'urlopen') as network:
                with self.assertRaises(scalus.BootstrapError) as failure:
                    scalus.download('https://example.invalid', Path(directory) / 'missing', '0'*64, True)
                network.assert_not_called()
            self.assertEqual(failure.exception.kind, scalus.Problem.OFFLINE)

    def test_normalization_preserves_every_member_and_is_repeatable(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [Path(directory) / f'{i}.jar' for i in range(2)]
            members = {'b.class': b'bytecode', 'META-INF/MANIFEST.MF': b'manifest', 'a.txt': b'resource'}
            for index, path in enumerate(paths):
                with zipfile.ZipFile(path, 'w') as jar:
                    for name in (list(members) if index == 0 else reversed(members)):
                        info = zipfile.ZipInfo(name, (2000 + index, 1, 1, 0, 0, 0))
                        jar.writestr(info, members[name])
                scalus.normalize_jar(path)
                with zipfile.ZipFile(path) as jar:
                    self.assertEqual({name: jar.read(name) for name in jar.namelist()}, members)
            self.assertEqual(paths[0].read_bytes(), paths[1].read_bytes())

    def test_signed_artifacts_are_not_silently_rewritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'signed.jar'
            with zipfile.ZipFile(path, 'w') as jar:
                jar.writestr('META-INF/SIGNER.RSA', b'signature')
            original = path.read_bytes()
            with self.assertRaises(scalus.BootstrapError):
                scalus.normalize_jar(path)
            self.assertEqual(path.read_bytes(), original)

    def test_cache_requires_exact_coordinate_inventory_and_original_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            version = '1.4.0-scarab-fixture'
            base = f'repository/org/scalus/scalus_3/{version}/scalus_3-{version}'
            files = {base + '.jar': b'jar', base + '.pom': (
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>org.scalus</groupId>'
                f'<artifactId>scalus_3</artifactId><version>{version}</version><dependencies>'
                '<dependency><artifactId>scalus-crypto-jni</artifactId><version>0.1.0</version>'
                '</dependency></dependencies></project>').encode()}
            for name, data in files.items():
                p = root / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_bytes(data)
            receipt = {'inputDigest': 'a'*64, 'version': version,
                       'files': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
            (root / 'receipt.json').write_text(json.dumps(receipt))
            self.assertEqual(scalus.validate_cache(root, 'a'*64, version), receipt)
            with self.assertRaises(scalus.BootstrapError):
                scalus.validate_cache(root, 'b'*64, version)
            (root / (base + '.jar')).write_bytes(b'corrupted')
            with self.assertRaises(scalus.BootstrapError) as failure:
                scalus.validate_cache(root, 'a'*64, version)
            self.assertEqual(failure.exception.kind, scalus.Problem.HASH)

    def test_assembly_replaces_only_the_exact_compilation_family(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original, compiled = root / "original.jar", root / "compiled.jar"
            member = "scalus/uplc/eval/CekMachine.class"
            untouched = "scalus/uplc/eval/CekMachineCosts.class"
            for path, data in [(original, {member: b"old", untouched: b"release", "LICENSE": b"license"}),
                               (compiled, {member: b"patched", untouched: b"nondeterministic", "LICENSE": b"different"})]:
                with zipfile.ZipFile(path, "w") as jar:
                    for name, value in data.items():
                        jar.writestr(name, value)
            replacements = scalus.compose_jar(original, compiled, [member])
            self.assertEqual(set(replacements), {member})
            with zipfile.ZipFile(compiled) as jar:
                self.assertEqual(jar.read(member), b"patched")
                self.assertEqual(jar.read(untouched), b"release")
                self.assertEqual(jar.read("LICENSE"), b"license")
            with zipfile.ZipFile(compiled, "a") as jar:
                jar.writestr("scalus/uplc/eval/CekMachine$Unexpected.class", b"unreviewed")
            with self.assertRaises(scalus.BootstrapError):
                scalus.compose_jar(original, compiled, [member])
            with self.assertRaises(scalus.BootstrapError):
                scalus.compose_jar(original, compiled, [member, "scalus/uplc/eval/CekMachine.tasty"])

    def test_pom_composition_preserves_dependencies_and_license_metadata(self):
        from xml.etree import ElementTree as ET
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original, target = root / "original.pom", root / "patched.pom"
            original.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                                '<groupId>org.scalus</groupId><artifactId>scalus_3</artifactId>'
                                '<version>1.4.0</version><licenses><license><name>Apache-2.0</name>'
                                '</license></licenses><dependencies><dependency><groupId>org.scalus</groupId>'
                                '<artifactId>scalus-crypto-jni</artifactId><version>0.1.0</version>'
                                '<scope>runtime</scope></dependency></dependencies></project>')
            scalus.compose_pom(original, target, "1.4.0-scarab-control")
            scalus.validate_pom(target, "1.4.0-scarab-control")
            before, after = ET.fromstring(original.read_bytes()), ET.fromstring(target.read_bytes())
            after.find("{http://maven.apache.org/POM/4.0.0}version").text = "1.4.0"
            self.assertEqual(ET.tostring(before), ET.tostring(after))

    def test_pom_cannot_resolve_original_version_or_changed_native_crypto(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'artifact.pom'
            for version, crypto in [('1.4.0', '0.1.0'), ('patched', '0.2.0')]:
                path.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'
                                '<groupId>org.scalus</groupId><artifactId>scalus_3</artifactId>'
                                f'<version>{version}</version><dependencies><dependency>'
                                '<artifactId>scalus-crypto-jni</artifactId>'
                                f'<version>{crypto}</version></dependency></dependencies></project>')
                with self.assertRaises(scalus.BootstrapError) as failure:
                    scalus.validate_pom(path, 'patched')
                self.assertEqual(failure.exception.kind, scalus.Problem.PUBLICATION)
