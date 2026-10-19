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

    def test_txsubmission2_behavior_fixture_retains_exact_connect2_choices(self) -> None:
        repo = Path(__file__).resolve().parents[3]
        fixture_dir = (
            repo
            / "network/src/test/resources/upstream/ouroboros-network-protocol-codecs"
        )
        raw = (fixture_dir / "txsubmission2-behavior.tsv").read_bytes()
        manifest = json.loads(
            (fixture_dir / "txsubmission2-behavior.manifest.json").read_text()
        )
        rows = [line.split("\t") for line in raw.decode("utf-8").splitlines()]
        choices = [
            {"seed": int(row[1]), "choices": row[13]}
            for row in rows
        ]

        self.assertEqual(manifest["sourceCommit"], "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776")
        self.assertEqual(manifest["caseTsvSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(manifest["exportedCases"], 24)
        self.assertTrue(all(len(row) == 14 for row in rows))
        self.assertTrue(all(row[0] == "txsubmission2-behavior-v2" for row in rows))
        self.assertTrue(all(row[13] and set(row[13]) <= {"0", "1"} for row in rows))
        self.assertEqual(
            manifest["adapterInputStatuses"]["connect2PipelineChoicesBySeed"], choices
        )
        self.assertEqual(
            manifest["adapterInputStatuses"]["connect2PipelineChoiceTrueValues"], 52
        )
        self.assertEqual(
            manifest["adapterInputStatuses"]["connect2PipelineChoiceFalseValues"], 38
        )

    def test_blockfetch_behavior_fixture_retains_original_chain_and_point_wires(self) -> None:
        repo = Path(__file__).resolve().parents[3]
        fixture = repo / "network/src/test/resources/upstream/ouroboros-network-protocol-codecs/blockfetch-behavior.tsv"
        raw = fixture.read_bytes()
        manifest = json.loads((fixture.parent / "blockfetch-behavior.manifest.json").read_text())
        rows = [line.split("\t") for line in raw.decode("utf-8").splitlines()]

        self.assertEqual(manifest["sourceCommit"], "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776")
        self.assertEqual(manifest["caseTsvSha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(manifest["seed"], 490201)
        self.assertEqual(manifest["exportedCases"], 24)
        self.assertEqual([int(row[1]) for row in rows], list(range(490201, 490225)))
        self.assertTrue(all(len(row) == 14 for row in rows))
        self.assertTrue(all(row[0] == "blockfetch-behavior-v1" for row in rows))
        self.assertTrue(all(row[4] and row[5] and row[6] and row[7] and row[8] for row in rows))
        self.assertTrue(all(row[11:] == ["pass", "pass", "pass"] for row in rows))
        range_fixture = fixture.with_name("blockfetch-behavior-ranges.tsv")
        range_raw = range_fixture.read_bytes()
        range_rows = [line.split("\t") for line in range_raw.decode("utf-8").splitlines()]
        self.assertEqual(manifest["rangeTsvSha256"], hashlib.sha256(range_raw).hexdigest())
        self.assertEqual(manifest["rangeRows"], len(range_rows))
        self.assertTrue(all(len(row) == 7 + int(row[6]) for row in range_rows))
        self.assertTrue(all(row[0] == "blockfetch-range-v1" for row in range_rows))

    def test_chainsync_behavior_fixtures_match_pinned_fork_property_manifest(self) -> None:
        repo = Path(__file__).resolve().parents[3]
        reference_dir = repo / "fixtures/reference/ouroboros-network-protocol-codecs"
        runtime_dir = repo / "network/src/test/resources/upstream/ouroboros-network-protocol-codecs"
        manifest = json.loads((reference_dir / "chainsync-behavior.manifest.json").read_text())
        self.assertEqual(
            manifest["sourceCommit"], "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776"
        )
        for name, expected_hash in (
            ("chainsync-behavior-cases.tsv", "a3e77a7ef66233c8d2360788efa828afd946a7513069cc535317e3751d055a18"),
            ("chainsync-behavior-blocks.tsv", "98d1ee153282f811de0b287bf1c7b3a5e3b5c68482fd8223d7f5536744eda776"),
            ("chainsync-behavior-points.tsv", "4093edd68ddf331771f9857899fcd32e14b4108b1249c400d624ba7d35740518"),
        ):
            raw = (reference_dir / name).read_bytes()
            self.assertEqual(hashlib.sha256(raw).hexdigest(), expected_hash)
            self.assertEqual(raw, (runtime_dir / name).read_bytes())

        cases = [line.split("\t") for line in (reference_dir / "chainsync-behavior-cases.tsv").read_text().splitlines()]
        blocks = [line.split("\t") for line in (reference_dir / "chainsync-behavior-blocks.tsv").read_text().splitlines()]
        points = [line.split("\t") for line in (reference_dir / "chainsync-behavior-points.tsv").read_text().splitlines()]
        self.assertEqual(manifest["exportedCases"], 24)
        self.assertEqual(manifest["seed"], 504001)
        self.assertEqual([int(row[1]) for row in cases], list(range(504001, 504025)))
        self.assertTrue(all(len(row) == 14 and row[0] == "chainsync-behavior-case-v2" for row in cases))
        self.assertTrue(all(row[4:7] == ["pass", "pass", "pass"] for row in cases))
        self.assertEqual(sum(row[13] == "80" for row in cases), 3)
        self.assertEqual(manifest["schema"], "scarab-upstream-network-protocol-behavior-v2")
        self.assertEqual(len(blocks), manifest["blockRows"])
        self.assertEqual(len(points), manifest["pointRows"])
        self.assertTrue(all(len(row) == 7 and row[0] == "chainsync-behavior-block-v1" for row in blocks))
        self.assertTrue(all(len(row) == 5 and row[0] == "chainsync-behavior-point-v1" for row in points))
        self.assertEqual(
            manifest["upstreamPropertyOutcomesByProperty"],
            {
                "propChainSyncDirectST": {"pass": 24, "upstream-failure": 0},
                "propChainSyncConnectST": {"pass": 24, "upstream-failure": 0},
                "propChainSyncDemoST": {"pass": 24, "upstream-failure": 0},
            },
        )

    def test_binary_compat_fixtures_preserve_both_original_directions_and_paired_wires(self) -> None:
        fixture_dir = (
            Path(__file__).resolve().parents[3]
            / "fixtures/reference/ouroboros-network-protocol-codecs"
        )
        cases = (
            ("chainsync-binary.tsv", 491101, 128, "chainsync"),
            ("blockfetch-binary.tsv", 490201, 24, "blockfetch"),
        )
        for name, seed, count, family in cases:
            raw = (fixture_dir / name).read_bytes()
            manifest = json.loads((fixture_dir / f"{name}.manifest.json").read_text())
            rows = [line.split("\t") for line in raw.decode("utf-8").splitlines()]
            directions = {"codec/codecSerialised", "codecSerialised/codec"}
            paired: dict[tuple[str, str], dict[str, list[str]]] = {}

            self.assertEqual(manifest["sourceCommit"], "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776")
            self.assertEqual(manifest["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(manifest["requestedCases"], count)
            self.assertEqual(manifest["exportedCases"], count * 2)
            self.assertEqual(
                manifest["upstreamPropertyOutcomes"],
                {
                    "directional-cases-pass": count * 2,
                    "directional-cases-failure": 0,
                    "paired-generator-ordinals": count,
                    "paired-ordinals-all-directions-pass": count,
                },
            )
            self.assertEqual(len(rows), count * 2)
            for row in rows:
                self.assertEqual(len(row), 10)
                self.assertEqual(row[0], f"{family}-binary-v1")
                ordinal = int(row[2])
                self.assertEqual(int(row[1]), seed + ordinal)
                self.assertEqual(int(row[3]), 30 + ordinal % 21)
                self.assertIn(row[4], directions)
                self.assertEqual(row[9], "pass")
                paired.setdefault((row[2], row[7]), {})[row[4]] = row
            self.assertEqual(len(paired), count)
            for values in paired.values():
                self.assertEqual(set(values), directions)
                self.assertEqual(values["codec/codecSerialised"][8], values["codecSerialised/codec"][8])

    def test_localsq_behavior_fixtures_preserve_complete_generated_cases_and_actions(self) -> None:
        fixture_dir = (
            Path(__file__).resolve().parents[3]
            / "fixtures/reference/ouroboros-network-protocol-codecs"
        )
        case_bytes = (fixture_dir / "localsq-behavior-cases.tsv").read_bytes()
        action_bytes = (fixture_dir / "localsq-behavior-actions.tsv").read_bytes()
        manifest = json.loads((fixture_dir / "localsq-behavior.manifest.json").read_text())
        cases = [line.split("\t") for line in case_bytes.decode("utf-8").splitlines()]
        actions = [line.split("\t") for line in action_bytes.decode("utf-8").splitlines()]

        self.assertEqual(manifest["sourceCommit"], "2fb69829a19b5c04e4fb212bdd78fb63f9d2d776")
        self.assertEqual(manifest["casesTsvSha256"], hashlib.sha256(case_bytes).hexdigest())
        self.assertEqual(manifest["actionsTsvSha256"], hashlib.sha256(action_bytes).hexdigest())
        self.assertEqual(manifest["requestedCases"], 32)
        self.assertEqual(manifest["exportedCases"], 32)
        self.assertEqual(manifest["actionRows"], 40)
        self.assertEqual(manifest["emptyMapCases"], 10)
        self.assertEqual(manifest["allPropertiesPassCases"], 32)
        self.assertEqual(
            manifest["scarabServerReplayCoverage"],
            {
                "totalOriginalSetupDataMaps": 32,
                "wholeMapsAcceptedByTypedBoundary": 19,
                "emptyMapsReplayedByDoneOnly": 10,
                "nonemptyMapsReplayed": 9,
                "serverActionsReplayed": 13,
                "specificPointMockHeaderHashIntActionsUnsupported": 14,
                "representableActionsNotPlayedBecauseTheirMapAlsoContainsUnsupportedTargets": 13,
                "unsupportedMixedMaps": 13,
            },
        )
        self.assertEqual(
            [int(row[1]) for row in cases],
            list(range(513801, 513833)),
        )
        self.assertTrue(all(len(row) == 10 for row in cases))
        self.assertTrue(all(len(row) == 15 for row in actions))
        self.assertEqual(
            manifest["upstreamPropertyOutcomesByProperty"],
            {
                name: {"pass": 32, "upstream-failure": 0}
                for name in (
                    "prop_direct",
                    "prop_connect",
                    "prop_channel_ST",
                    "prop_channel_IO",
                    "prop_pipe_IO",
                )
            },
        )
        by_case = {(row[1], row[2]): row for row in cases}
        action_groups: dict[tuple[str, str], list[list[str]]] = {}
        for row in actions:
            self.assertEqual(row[0], "localsq-behavior-v1")
            key = (row[1], row[2])
            self.assertIn(key, by_case)
            action_groups.setdefault(key, []).append(row)
            self.assertEqual(row[10:15], ["pass"] * 5)
            self.assertTrue(row[6] and row[8] and row[9])
        for key, case in by_case.items():
            group = sorted(action_groups.get(key, []), key=lambda row: int(row[4]))
            self.assertEqual(len(group), int(case[4]))
            self.assertEqual([int(row[4]) for row in group], list(range(len(group))))
            self.assertTrue(all(int(row[5]) == len(group) for row in group))


if __name__ == "__main__":
    unittest.main()
