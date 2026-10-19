# SPDX-License-Identifier: Apache-2.0
"""Host-only controls for upstream property outcome column attribution."""

import hashlib
import json
from pathlib import Path
import unittest

from export import PROPERTY_COLUMNS, summarize_property_outcomes


class PropertyOutcomeColumnsTest(unittest.TestCase):
    def test_localsq_codec_cbor_and_valid_cbor_columns_are_distinct(self) -> None:
        rows = []
        for verdicts in (
            ("pass", "pass", "fail"),
            ("pass", "fail", "pass"),
            ("fail", "fail", "pass"),
        ):
            row = [""] * 11
            row[8:11] = verdicts
            rows.append(row)

        summary = summarize_property_outcomes(rows, PROPERTY_COLUMNS["localsq.tsv"])

        self.assertEqual(
            summary["upstreamPropertyOutcomesByProperty"],
            {
                "prop_codec_LocalStateQuery": {"pass": 2, "upstream-failure": 1},
                "prop_codec_cbor": {"pass": 1, "upstream-failure": 2},
                "prop_codec_valid_cbor": {"pass": 2, "upstream-failure": 1},
            },
        )
        self.assertEqual(
            summary["upstreamPropertyOutcomes"],
            {
                "all-recorded-properties-pass": 0,
                "at-least-one-recorded-property-failure": 3,
            },
        )

    def test_peersharing_split_outcomes_are_not_conflated(self) -> None:
        rows = []
        for verdicts in (
            ("pass", "fail", "pass", "fail", "pass"),
            ("pass", "pass", "fail", "pass", "fail"),
            ("fail", "pass", "pass", "pass", "pass"),
        ):
            row = [""] * 15
            row[10:15] = verdicts
            rows.append(row)

        summary = summarize_property_outcomes(rows, PROPERTY_COLUMNS["peersharing.tsv"])

        self.assertEqual(
            summary["upstreamPropertyOutcomesByProperty"],
            {
                "prop_codec_PeerSharing": {"pass": 2, "upstream-failure": 1},
                "prop_codec_cbor": {"pass": 2, "upstream-failure": 1},
                "prop_codec_valid_cbor": {"pass": 2, "upstream-failure": 1},
                "prop_codec_splits2": {"pass": 2, "upstream-failure": 1},
                "prop_codec_splits3": {"pass": 2, "upstream-failure": 1},
            },
        )

    def test_annotated_fixtures_match_pinned_manifests(self) -> None:
        fixture_dir = (
            Path(__file__).resolve().parents[3]
            / "fixtures/reference/ouroboros-network-protocol-codecs"
        )
        for name, seed, outcomes in (
            ("localtxsubmission-annotated.tsv", 496001, (8, 9, 10)),
            ("txsubmission2-annotated.tsv", 497001, (8, 9, 10)),
        ):
            raw = (fixture_dir / name).read_bytes()
            manifest = json.loads((fixture_dir / f"{name}.manifest.json").read_text())
            rows = [line.split("\t") for line in raw.decode("utf-8").splitlines()]

            self.assertEqual(manifest["sourceCommit"], "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776")
            self.assertEqual(manifest["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(manifest["requestedCases"], 24)
            self.assertEqual(manifest["exportedCases"], 24)
            self.assertEqual([int(row[1]) for row in rows], list(range(seed, seed + 24)))
            self.assertTrue(all(len(row) == 11 for row in rows))
            self.assertTrue(all(row[column] == "pass" for row in rows for column in outcomes))

    def test_added_property_outcomes_match_existing_seeded_domains(self) -> None:
        fixture_dir = (
            Path(__file__).resolve().parents[3]
            / "fixtures/reference/ouroboros-network-protocol-codecs"
        )
        cases = (
            ("keepalive.tsv", 490201),
            ("peersharing.tsv", 490201),
            ("localsq.tsv", 492001),
            ("localtxsubmission.tsv", 493001),
            ("localtxmonitor.tsv", 494001),
            ("txsubmission2.tsv", 495001),
        )
        for name, seed in cases:
            raw = (fixture_dir / name).read_bytes()
            manifest = json.loads((fixture_dir / f"{name}.manifest.json").read_text())
            rows = [line.split("\t") for line in raw.decode("utf-8").splitlines()]
            properties = PROPERTY_COLUMNS[name]

            self.assertEqual(manifest["sourceCommit"], "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776")
            self.assertEqual(manifest["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(manifest["exportedCases"], 24)
            self.assertEqual([int(row[1]) for row in rows], list(range(seed, seed + 24)))
            self.assertEqual(summarize_property_outcomes(rows, properties)["upstreamPropertyOutcomes"], {
                "all-recorded-properties-pass": 24,
                "at-least-one-recorded-property-failure": 0,
            })


if __name__ == "__main__":
    unittest.main()
