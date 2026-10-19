# SPDX-License-Identifier: Apache-2.0
"""Controls for rejecting corrupt or misattributed upstream observations."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("generic_handshake_export", Path(__file__).with_name("export.py"))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ROOT = Path(__file__).resolve().parents[4] / "protocol/src/test/resources/upstream/generic-handshake"


class ExportValidation(unittest.TestCase):
    def test_retained_original_corpora(self):
        for name in MODULE.SCHEMAS:
            with self.subTest(name=name):
                self.assertEqual(MODULE.validate(name, (ROOT / f"{name}.tsv").read_bytes())["rows"], MODULE.SCHEMAS[name][0])

    def test_unknown_verdict_is_not_a_pass(self):
        raw = (ROOT / "codec.tsv").read_bytes().replace(b"pass", b"not-observed", 1)
        with self.assertRaises(ValueError):
            MODULE.validate("codec", raw)

    def test_wrong_generator_seed_is_rejected(self):
        raw = (ROOT / "behavior.tsv").read_bytes().replace(b"490501", b"490502", 1)
        with self.assertRaises(ValueError):
            MODULE.validate("behavior", raw)

    def test_upstream_failure_remains_visible(self):
        raw = (ROOT / "refuse.tsv").read_bytes().replace(b"pass", b"upstream-failure", 1)
        result = MODULE.validate("refuse", raw)
        self.assertEqual(result["upstreamOutcomes"]["prop_codec_RefuseReason"]["upstream-failure"], 1)


if __name__ == "__main__":
    unittest.main()
