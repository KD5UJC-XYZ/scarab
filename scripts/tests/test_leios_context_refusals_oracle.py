# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the retained-fixture validator of the Leios context refusals oracle
(`scripts/oracle/leios-testnet/chain/refusals.py`, #85): the committed fixture passes, and a copy whose answers
drop a level's diagnostic, or misstate it, is refused even with its provenance rebound to the changed answers.
No Docker.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load():
    spec = importlib.util.spec_from_file_location(
        "leios_context_refusals", ROOT / "scripts/oracle/leios-testnet/chain/refusals.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


refusals = load()
SUB_REFUSED = "V3 in a sub-transaction"
TOP_REFUSED = "V3 with direct deposits"
COLLECTED = "V1 to V4 minting in one transaction"
ZERO = "0" * 64


class RefusalsFixtureTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp())
        self.fixtures = self.directory / "fixtures"
        shutil.copytree(refusals.DIRECTORY, self.fixtures)

    def tearDown(self):
        shutil.rmtree(self.directory)

    def edited(self, edit):
        """The copy with its answers changed by `edit` and its provenance bound to the changed answers."""
        path = self.fixtures / "results.json"
        results = json.loads(path.read_text())
        edit({r["name"]: r["result"] for r in results})
        path.write_text(json.dumps(results))
        provenance = json.loads((self.fixtures / "provenance.json").read_text())
        provenance["results"]["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        (self.fixtures / "provenance.json").write_text(json.dumps(provenance, indent=1) + "\n")

    def provenance_edited(self, edit):
        """The copy with its provenance changed by `edit`."""
        path = self.fixtures / "provenance.json"
        provenance = json.loads(path.read_text())
        edit(provenance)
        path.write_text(json.dumps(provenance, indent=1) + "\n")

    def test_the_committed_fixture_and_its_copy_pass(self):
        refusals.validate(refusals.DIRECTORY)
        self.edited(lambda results: None)
        refusals.validate(self.fixtures)

    def test_a_sub_transaction_refused_without_its_failure_is_refused(self):
        def drop(results):
            sub = results[SUB_REFUSED]["subtxs"][0]
            self.assertIn("UnsupportedScriptInSubTx", sub["failure"])
            del sub["failure"]
        self.edited(drop)
        with self.assertRaisesRegex(ValueError, "sub-transaction 0: a refused collection without"):
            refusals.validate(self.fixtures)

    def test_a_sub_transaction_refused_with_an_empty_failure_is_refused(self):
        def empty(results):
            results[SUB_REFUSED]["subtxs"][0]["failure"] = ""
        self.edited(empty)
        with self.assertRaisesRegex(ValueError, "a refused collection without"):
            refusals.validate(self.fixtures)

    def test_a_top_level_failure_unlike_the_answers_is_refused(self):
        def misstate(results):
            results[TOP_REFUSED]["top"]["failure"] = "BadTranslation (something else) :| []"
        self.edited(misstate)
        with self.assertRaisesRegex(ValueError, "the top level's failure is not the answer's"):
            refusals.validate(self.fixtures)

    def test_a_sub_transaction_refused_with_a_blank_failure_is_refused(self):
        def blank(results):
            results[SUB_REFUSED]["subtxs"][0]["failure"] = " \t\n"
        self.edited(blank)
        with self.assertRaisesRegex(ValueError, "a refused collection without"):
            refusals.validate(self.fixtures)

    def test_a_context_that_is_not_hex_is_refused(self):
        def garble(results):
            results[COLLECTED]["top"]["contexts"][0] = "not hex"
        self.edited(garble)
        with self.assertRaisesRegex(ValueError, "not the hex of whole bytes"):
            refusals.validate(self.fixtures)

    def test_a_context_of_half_a_byte_is_refused(self):
        def odd(results):
            results[COLLECTED]["top"]["contexts"][0] = "a"
        self.edited(odd)
        with self.assertRaisesRegex(ValueError, "not the hex of whole bytes"):
            refusals.validate(self.fixtures)

    def test_a_level_with_no_scripts_is_collected_with_no_contexts(self):
        results = {r["name"]: r["result"] for r in json.loads((refusals.DIRECTORY / "results.json").read_text())}
        self.assertEqual(results[SUB_REFUSED]["top"], {"collected": True, "contexts": []})
        refusals.validate(refusals.DIRECTORY)

    def test_a_zeroed_harness_digest_is_refused(self):
        self.provenance_edited(
            lambda p: p["oracle"]["imageFilesSha256"].__setitem__(refusals.RUN.IMAGE_HARNESS, ZERO))
        with self.assertRaisesRegex(ValueError, "repository's harness"):
            refusals.validate(self.fixtures)

    def test_a_zeroed_executable_digest_is_refused(self):
        self.provenance_edited(lambda p: p["oracle"].__setitem__("oracleBinarySha256", ZERO))
        with self.assertRaisesRegex(ValueError, "its executable"):
            refusals.validate(self.fixtures)

    def test_an_incomplete_image_file_map_is_refused(self):
        self.provenance_edited(
            lambda p: p["oracle"]["imageFilesSha256"].pop(refusals.RUN.IMAGE_HARNESS))
        with self.assertRaisesRegex(ValueError, "not exactly"):
            refusals.validate(self.fixtures)

    def test_a_collection_without_its_contexts_is_refused(self):
        def empty(results):
            del results["V1 to V4 minting in one transaction"]["top"]["contexts"]
        self.edited(empty)
        with self.assertRaisesRegex(ValueError, "lists no contexts"):
            refusals.validate(self.fixtures)


if __name__ == "__main__":
    unittest.main()
