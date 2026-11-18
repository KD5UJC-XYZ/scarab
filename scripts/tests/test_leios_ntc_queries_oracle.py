# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the retained-fixture validator of the Leios node-to-client query oracle
(`scripts/oracle/leios-ntc-queries/run.py`, #826): the committed fixture passes, and changing any field that binds
the pins, the ledger sources, the accepted oracle build or the answers' arguments is refused. No Docker.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load():
    spec = importlib.util.spec_from_file_location(
        "leios_ntc_queries_run", ROOT / "scripts/oracle/leios-ntc-queries/run.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


run = load()
CASES = [case for case, _ in run.inputs()]
PROVENANCE = json.loads(run.PROVENANCE.read_text())
ROWS = run.recorded()
CASES_SHA256 = hashlib.sha256(run.CASES_FILE.read_bytes()).hexdigest()
ZERO = "0" * 64
BINARY = run.IMAGE_BINARY


def problems(provenance, rows=ROWS, cases_sha256=CASES_SHA256):
    return run.validate(provenance, rows, cases_sha256, CASES)


def changed(edit):
    provenance = copy.deepcopy(PROVENANCE)
    edit(provenance)
    return provenance


def setter(*path, value):
    def edit(provenance):
        target = provenance
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = value
    return edit


def remover(*path):
    def edit(provenance):
        target = provenance
        for key in path[:-1]:
            target = target[key]
        del target[path[-1]]
    return edit


def both_binary_digests(provenance):
    provenance["oracle"]["imageFilesSha256"][BINARY] = ZERO
    provenance["oracle"]["oracleBinarySha256"] = ZERO


MUTATIONS = {
    "schema version": setter("schemaVersion", value=2),
    "extra field": setter("baseImageId", value="sha256:" + ZERO),
    "image": setter("image", value="scarab-leios-testnet:local"),
    "image ID syntax": setter("imageId", value="scarab-leios-ntc-queries-oracle:local"),
    "ledger revision": setter("ledger", "revision", value="f649f975" + "0" * 32),
    "ledger packages": setter("ledger", "packages", value=[]),
    "consensus revision": setter("consensusSourceCommit", value="0" * 40),
    "a ledger source digest": setter("sourceSha256", "Api/State/Query.hs", value=ZERO),
    "a ledger source missing": remover("sourceSha256", "Api/State/Query.hs"),
    "program digest": setter("programSha256", value=ZERO),
    "package digest": setter("packageSha256", value=ZERO),
    "an input digest": setter("inputs", run.state_path("testnet-2168639"), value=ZERO),
    "an input missing": remover("inputs", run.state_path("dijkstra-203")),
    "case count": setter("cases", value=2),
    "case file digest": setter("casesSha256", value=ZERO),
    "measured date": setter("measuredAt", value="yesterday"),
    "network": setter("oracle", "network", value="bridge"),
    "oracle field missing": remover("oracle", "network"),
    "the image's Main.hs": setter("oracle", "imageFilesSha256", run.IMAGE_HARNESS, value=ZERO),
    "the image's package file": setter("oracle", "imageFilesSha256", run.IMAGE_BUILD[0], value=ZERO),
    "the image's cabal project": setter("oracle", "imageFilesSha256", run.IMAGE_BUILD[1], value=ZERO),
    "the image's cabal.project.local": setter("oracle", "imageFilesSha256", run.IMAGE_BUILD[2], value=ZERO),
    "an image file missing": remover("oracle", "imageFilesSha256", run.IMAGE_BUILD[1]),
    "an image digest not a SHA-256": setter("oracle", "imageFilesSha256", BINARY, value="ab"),
    "the executable's digest alone": setter("oracle", "oracleBinarySha256", value=ZERO),
    "a pinned checkout changed": lambda p: p["oracle"]["pinnedSources"].update(
        {next(iter(p["oracle"]["pinnedSources"])): "changed"}),
    "a pinned checkout missing": lambda p: p["oracle"]["pinnedSources"].pop(next(iter(p["oracle"]["pinnedSources"]))),
    "an unpinned checkout": setter("oracle", "pinnedSources", "f" * 40, value="unchanged"),
}


class RetainedFixtureTest(unittest.TestCase):
    def test_the_committed_fixture_is_valid(self):
        self.assertEqual(problems(PROVENANCE), [])

    def test_each_binding_field_changed_is_refused(self):
        for name, edit in MUTATIONS.items():
            with self.subTest(name):
                self.assertNotEqual(problems(changed(edit)), [], name)

    def test_the_executable_digest_changed_consistently_still_validates_offline(self):
        # Offline, a different executable is a build the record accepted; replay refuses it against the image.
        self.assertEqual(problems(changed(both_binary_digests)), [])

    def test_replay_accepts_only_the_recorded_image(self):
        binding = {"imageFilesSha256": PROVENANCE["oracle"]["imageFilesSha256"],
                   "pinnedSources": PROVENANCE["oracle"]["pinnedSources"]}
        self.assertIsNone(run.refusal(PROVENANCE["imageId"], binding, PROVENANCE))
        # another valid immutable ID with the same measured files: its unmeasured layers may differ
        self.assertIsNotNone(run.refusal("sha256:" + ZERO, binding, PROVENANCE))
        other_files = dict(binding, imageFilesSha256=dict(binding["imageFilesSha256"], **{BINARY: ZERO}))
        self.assertIsNotNone(run.refusal(PROVENANCE["imageId"], other_files, PROVENANCE))
        self.assertIsNotNone(run.refusal(PROVENANCE["imageId"], binding, changed(remover("oracle"))))

    def test_the_derived_answers_follow_the_others(self):
        for row in ROWS:
            with self.subTest(row["name"]):
                cases = row["reference"]["cases"]
                self.assertEqual([c["query"] for c in cases], ["GetStakePoolState"] * 5 + ["GetLeiosCommittee"]
                                 + ["GetStakePoolState"] * 2)
                self.assertEqual([c.get("derived") for c in cases], [None] * 6 + ["retiring+future"] * 2)

    def test_the_rows_are_bound(self):
        self.assertNotEqual(problems(PROVENANCE, rows=ROWS + ROWS), [])
        self.assertNotEqual(problems(PROVENANCE, rows=ROWS[:2]), [])
        self.assertNotEqual(problems(PROVENANCE, rows=[dict(ROWS[0], name="other"), *ROWS[1:]]), [])
        self.assertNotEqual(problems(PROVENANCE, rows=[dict(ROWS[0], era="dijkstra"), *ROWS[1:]]), [])
        self.assertNotEqual(problems(PROVENANCE, cases_sha256=ZERO), [])

    def test_the_answers_are_bound_to_their_arguments(self):
        def with_case(index, **fields):
            row = copy.deepcopy(ROWS[2])
            row["reference"]["cases"][index].update(fields)
            return [*ROWS[:2], row]
        every = ROWS[2]["reference"]["cases"][0]["result"]
        some = ROWS[2]["reference"]["cases"][2]["result"]
        for name, rows in {
            "a restricted answer holding every pool": with_case(2, result=every),
            "the full answer holding only some pools": with_case(1, result=some),
            "other pool ids asked for": with_case(2, poolIds=[run.UNREGISTERED]),
            "delegators asked for in another case": with_case(1, includeDelegators=True),
            "another query tag": with_case(5, tag=40),
            "a derived answer for another retirement epoch": with_case(6, retiringEpoch=100),
            "a derived answer on another network": with_case(7, network="Mainnet"),
            "a derived answer without its retirement": with_case(6, result=ROWS[2]["reference"]["cases"][2]["result"]),
            "the derived answers swapped": with_case(6, result=ROWS[2]["reference"]["cases"][7]["result"]),
            "a derived answer for another pool": with_case(7, poolIds=[ROWS[2]["reference"]["cases"][2]["poolIds"][1]]),
        }.items():
            with self.subTest(name):
                self.assertNotEqual(problems(PROVENANCE, rows=rows), [], name)
        wrong_version = copy.deepcopy(ROWS[0])
        wrong_version["reference"]["version"] = 12
        self.assertNotEqual(problems(PROVENANCE, rows=[wrong_version, *ROWS[1:]]), [])

    def test_the_eras_are_read_from_the_telescope(self):
        self.assertEqual([(case["name"], case["era"]) for case in CASES],
                         [("conway-195", "conway"), ("dijkstra-203", "dijkstra"), ("testnet-2168639", "dijkstra")])
        self.assertEqual([case["epoch"] for case in CASES], [0, 1, 100])
        # the same span of conway-195 as the state-translation oracle reads
        self.assertEqual(CASES[0]["newEpochStateSpan"], [119, 12249])


if __name__ == "__main__":
    unittest.main()
