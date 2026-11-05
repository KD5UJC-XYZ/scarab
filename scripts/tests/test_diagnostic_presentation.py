# SPDX-License-Identifier: Apache-2.0
"""Negative controls for inventory discovery, source drift and false coverage claims."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_diagnostic_presentation import constructor_digest, discover, validate


class DiagnosticInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "codec/src/main/scala/example/Problem.scala"
        self.source.parent.mkdir(parents=True)
        self.source.write_text('package example\nenum Problem:\n  case First\n  def message: String = "a"\n')
        pin = self.root / "docs/data/reference-pins.json"
        pin.parent.mkdir(parents=True)
        pin.write_text(json.dumps([{"name": "plutus", "revision": "a" * 40}]))
        self.data = {"schemaVersion": 1, "boundaries": [{
            "id": "test", "use": "needs-exposure-proof", "state": "incomplete",
            "scope": "native exposure pending", "resolution": "prove the actual boundary",
            "sources": [self.source.relative_to(self.root).as_posix()], "tests": [],
            "evidence": "explicit incomplete gate"}], "nativeFamilies": [],
            "producers": [{"source": path, "boundary": "test", **shape}
                          for path, shape in discover(self.root).items()]}

    def test_source_drift_is_not_a_pass(self):
        self.assertEqual(validate(self.root, self.data), [])
        self.source.write_text(self.source.read_text().replace('case First', 'case First, Second'))
        self.assertTrue(any('constructors changed' in e for e in validate(self.root, self.data)))

    def test_new_producer_requires_registration(self):
        self.source.with_name('Other.scala').write_text('package example\nobject Other:\n  def render: String = "x"\n')
        self.assertTrue(any('unregistered diagnostic producer' in e for e in validate(self.root, self.data)))

    def test_new_overload_is_not_hidden_by_an_existing_method_name(self):
        self.source.write_text(self.source.read_text() + '  def message(n: Int): String = "b"\n')
        self.assertTrue(any('presentation methods changed' in e for e in validate(self.root, self.data)))

    def test_comments_and_literals_cannot_register_a_fake_method(self):
        self.source.write_text(self.source.read_text() + '// def render: String = "fake"\n')
        self.assertEqual(validate(self.root, self.data), [])

    def test_verified_needs_independent_observations(self):
        data = copy.deepcopy(self.data)
        data['boundaries'][0]['state'] = 'verified'
        self.assertTrue(any('verified needs' in e for e in validate(self.root, data)))

    def test_native_counterpart_cannot_disappear(self):
        data = copy.deepcopy(self.data)
        data['boundaries'][0]['use'] = 'native-wire'
        self.assertTrue(any('missing pinned native counterpart' in e for e in validate(self.root, data)))

    def test_provider_message_use_is_discovered(self):
        self.source.write_text('package example\nobject Other:\n  def detail(t: Throwable) = t.getMessage\n')
        found = discover(self.root)
        self.assertTrue(any(m.startswith('provider-message:') for row in found.values() for m in row['methods']))

    def test_handshake_refusal_text_is_a_presentation_producer(self):
        self.source.write_text('package example\nobject Codec:\n  def refusalText(code: Int): String = "x"\n')
        found = discover(self.root)
        self.assertTrue(any('refusalText' in m for row in found.values() for m in row['methods']))

    def test_symlinked_evidence_is_not_accepted(self):
        link = self.source.with_name('linked.scala')
        link.symlink_to(self.source)
        data = copy.deepcopy(self.data)
        data['boundaries'][0]['sources'] = [link.relative_to(self.root).as_posix()]
        self.assertTrue(any('nonregular source' in e for e in validate(self.root, data)))

    def native_family(self):
        family = {"family": "NativeError", "source": "Error.hs", "line": 1,
                  "sourceSha256": "b" * 64, "revision": "a" * 40, "state": "incomplete",
                  "constructors": ["One", "Two"], "scalaMapping": {"One": None, "Two": None},
                  "oracleCases": {"One": [], "Two": []}}
        family['constructorDigest'] = constructor_digest(family)
        return family

    def test_native_pin_bump_cannot_reuse_an_old_inventory(self):
        data = copy.deepcopy(self.data)
        data['nativeFamilies'] = [self.native_family()]
        self.assertEqual(validate(self.root, data), [])
        data['nativeFamilies'][0]['revision'] = 'c' * 40
        self.assertTrue(any('pin changed' in e for e in validate(self.root, data)))

    def test_deleted_native_constructor_invalidates_identity(self):
        data = copy.deepcopy(self.data)
        family = self.native_family()
        data['nativeFamilies'] = [family]
        family['constructors'].remove('Two')
        del family['scalaMapping']['Two']
        del family['oracleCases']['Two']
        self.assertTrue(any('constructor identity changed' in e for e in validate(self.root, data)))

    def test_native_family_cannot_be_verified_with_missing_capture(self):
        data = copy.deepcopy(self.data)
        family = self.native_family()
        family['state'] = 'verified'
        data['nativeFamilies'] = [family]
        self.assertTrue(any('mapping/oracle still incomplete' in e for e in validate(self.root, data)))


if __name__ == '__main__':
    unittest.main()
