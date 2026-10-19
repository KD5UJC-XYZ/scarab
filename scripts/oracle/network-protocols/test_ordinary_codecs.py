# SPDX-License-Identifier: Apache-2.0
"""Controls keeping upstream failures and original sample identities visible."""

import copy
import hashlib
import json
from pathlib import Path
import unittest

from ordinary_codecs import PROPERTIES, validate


class OrdinaryCodecObservationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(__file__).resolve().parents[3] / "fixtures/reference/ouroboros-network-protocol-codecs"
        self.rows = [line.split("\t") for line in
                     (self.root / "blockfetch-ordinary.tsv").read_text().splitlines()]

    def test_failure_is_attributed_to_its_original_property(self) -> None:
        rows = copy.deepcopy(self.rows)
        rows[0][12] = "upstream-failure"
        result = validate(rows, "BlockFetch", 64, 509101)
        for name in PROPERTIES["BlockFetch"]:
            expected = {"pass": 63, "upstream-failure": 1} if name == "prop_codec_splits3_BlockFetch" else {"pass": 64, "upstream-failure": 0}
            self.assertEqual(result["upstreamPropertyOutcomesByProperty"][name], expected)

    def test_filtered_or_reseeded_samples_are_not_accepted_as_original(self) -> None:
        for column, replacement in ((1, "509999"), (2, "9"), (3, "30")):
            with self.subTest(column=column):
                rows = copy.deepcopy(self.rows)
                rows[0][column] = replacement
                with self.assertRaisesRegex(ValueError, "original generator identity"):
                    validate(rows, "BlockFetch", 64, 509101)
        with self.assertRaisesRegex(ValueError, "expected 64 original samples"):
            validate(self.rows[:-1], "BlockFetch", 64, 509101)

    def test_corpus_and_native_resource_mirrors_bind_the_actual_observer(self) -> None:
        patch = Path(__file__).with_name("ordinary-codecs-observer.patch")
        native = self.root.parents[2] / "protocol/src/test/resources/upstream/ouroboros-network-protocol-codecs"
        for family, seed in (("BlockFetch", 509101), ("ChainSync", 510101)):
            name = f"{family.lower()}-ordinary.tsv"
            raw = (self.root / name).read_bytes()
            manifest = json.loads((self.root / f"{name}.manifest.json").read_text())
            rows = [line.split("\t") for line in raw.decode().splitlines()]
            self.assertEqual(manifest["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(manifest["observerPatchSha256"], hashlib.sha256(patch.read_bytes()).hexdigest())
            self.assertEqual(validate(rows, family, 64, seed)["upstreamPropertyOutcomesByProperty"], manifest["upstreamPropertyOutcomesByProperty"])
            self.assertEqual((native / name).read_bytes(), raw)
            self.assertEqual((native / f"{name}.manifest.json").read_bytes(), (self.root / f"{name}.manifest.json").read_bytes())
            self.assertTrue(manifest["targetPlanEquivalence"]["sameTargetClosure"])


if __name__ == "__main__":
    unittest.main()
