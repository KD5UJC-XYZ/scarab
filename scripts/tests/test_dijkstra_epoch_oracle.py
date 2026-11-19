# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the retained-fixture validator of the Dijkstra epoch-boundary oracle
(`scripts/oracle/dijkstra-epoch/run.py`, #54): the committed fixture passes, and changing any field that binds the
pins, the ledger sources, the accepted oracle build, the state files or the facts the reference's rules fix is
refused. No Docker.

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
        "dijkstra_epoch_run", ROOT / "scripts/oracle/dijkstra-epoch/run.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


run = load()
CASES = [case for case, _ in run.inputs()]
PROVENANCE = json.loads(run.PROVENANCE.read_text())
ROWS = run.recorded()
GENESIS = run.recorded_genesis()
CASES_SHA256 = hashlib.sha256(run.CASES_FILE.read_bytes()).hexdigest()
FILES = {name: hashlib.sha256((run.FIXTURE / name).read_bytes()).hexdigest() for name in run.state_files()}
ZERO = "0" * 64
BINARY = run.IMAGE_BINARY


def problems(provenance, rows=ROWS, cases_sha256=CASES_SHA256, files=FILES, genesis=GENESIS):
    return run.validate(provenance, rows, cases_sha256, CASES, files, genesis)


def with_genesis(edit):
    record = copy.deepcopy(GENESIS)
    edit(record)
    return record


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


def derivation_name(row):
    return row["derivation"] if row["derivation"] == "none" else row["derivation"]["name"]


def row_index(name, path, derivation="none"):
    return [(r["name"], r["path"], derivation_name(r)) for r in ROWS].index((name, path, derivation))


def with_tick(name, path, index, edit, derivation="none"):
    rows = copy.deepcopy(ROWS)
    edit(rows[row_index(name, path, derivation)]["ticks"][index])
    return rows


def with_row(name, path, edit, derivation="none"):
    rows = copy.deepcopy(ROWS)
    edit(rows[row_index(name, path, derivation)])
    return rows


def with_derivation(name, derivation, edit):
    """Both rows of a derivation edited alike, so the paths still start from one derived state."""
    rows = copy.deepcopy(ROWS)
    for path in run.PATHS:
        edit(rows[row_index(name, path, derivation)]["derivation"])
    return rows


def counts(role, count):
    return lambda t: t["vrfCounts"][role].update(count=count)


FIRST_STATE_FILE = next(iter(PROVENANCE["stateFiles"]))

MUTATIONS = {
    "schema version": setter("schemaVersion", value=2),
    "extra field": setter("baseImageId", value="sha256:" + ZERO),
    "scope": setter("scope", value="something else"),
    "image": setter("image", value="scarab-leios-testnet:local"),
    "image ID syntax": setter("imageId", value="scarab-dijkstra-epoch-oracle:local"),
    "ledger revision": setter("ledger", "revision", value="f649f975" + "0" * 32),
    "ledger packages": setter("ledger", "packages", value=[]),
    "consensus revision": setter("consensusSourceCommit", value="0" * 40),
    "a ledger source digest": setter("sourceSha256", next(iter(run.PINNED)), value=ZERO),
    "a ledger source missing": remover("sourceSha256", next(iter(run.PINNED))),
    "program digest": setter("programSha256", value=ZERO),
    "package digest": setter("packageSha256", value=ZERO),
    "an input digest": setter("inputs", run.state_path("testnet-2168639"), value=ZERO),
    "an input missing": remover("inputs", run.state_path("dijkstra-203")),
    "a genesis digest": setter("inputs", "fixtures/leios/testnet/config/shelley-genesis.json", value=ZERO),
    "case count": setter("cases", value=2),
    "case file digest": setter("casesSha256", value=ZERO),
    "a state file digest": setter("stateFiles", FIRST_STATE_FILE, value=ZERO),
    "a state file missing": remover("stateFiles", FIRST_STATE_FILE),
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
        self.assertIsNotNone(run.refusal("sha256:" + ZERO, binding, PROVENANCE))
        other_files = dict(binding, imageFilesSha256=dict(binding["imageFilesSha256"], **{BINARY: ZERO}))
        self.assertIsNotNone(run.refusal(PROVENANCE["imageId"], other_files, PROVENANCE))
        self.assertIsNotNone(run.refusal(PROVENANCE["imageId"], binding, changed(remover("oracle"))))

    def test_the_state_files_are_bound(self):
        self.assertNotEqual(problems(PROVENANCE, files={**FILES, FIRST_STATE_FILE: ZERO}), [])
        self.assertNotEqual(problems(PROVENANCE, files={**FILES, "extra.cbor": ZERO}), [])
        self.assertNotEqual(problems(PROVENANCE, files={k: v for k, v in FILES.items() if k != FIRST_STATE_FILE}), [])

    def test_the_rows_are_bound(self):
        self.assertNotEqual(problems(PROVENANCE, rows=ROWS + ROWS), [])
        self.assertNotEqual(problems(PROVENANCE, rows=ROWS[:2]), [])
        self.assertNotEqual(problems(PROVENANCE, rows=[ROWS[1], ROWS[0], *ROWS[2:]]), [])
        self.assertNotEqual(problems(PROVENANCE, cases_sha256=ZERO), [])
        for name, rows in {
            "another tip": with_row("dijkstra-203", "boundaries", lambda r: r.update(tipSlot=204)),
            "another span": with_row("testnet-2168639", "rewards", lambda r: r.update(newEpochStateSpan=[0, 1])),
            "another genesis": with_row(
                "testnet-2168639", "boundaries",
                lambda r: r.update(genesis="fixtures/leios/state/genesis/shelley-genesis.json")),
        }.items():
            with self.subTest(name):
                self.assertNotEqual(problems(PROVENANCE, rows=rows), [], name)

    def test_the_facts_follow_the_reference_rules(self):
        def facts(**fields):
            return lambda t: t["facts"].update(fields)

        def mark(**fields):
            return lambda t: t["facts"]["mark"].update(fields)
        for name, rows in {
            "another version": with_row("dijkstra-203", "rewards", lambda r: r.update(version=11)),
            "another key age in the globals": with_row("testnet-2168639", "rewards",
                                                       lambda r: r["globals"].update(maxKeyAge=375)),
            "another epoch size": with_row("dijkstra-203", "boundaries", lambda r: r["globals"].update(epochSize=100)),
            "another stabilisation window": with_row(
                "testnet-2168639", "boundaries", lambda r: r["globals"].update(randomnessStabilisationWindow=8641)),
            "the input's key age": with_row("dijkstra-203", "boundaries",
                                            lambda r: r["inputFacts"]["mark"].update(maxKeyAge=40177)),
            "another slot": with_tick("dijkstra-203", "boundaries", 0, lambda t: t.update(slot=401)),
            "a boundary not crossed": with_tick("testnet-2168639", "boundaries", 1, facts(epoch=101)),
            "a boundary's mark of another epoch": with_tick("dijkstra-203", "boundaries", 0, mark(epoch=1)),
            "a boundary's mark of another key age": with_tick("testnet-2168639", "rewards", 1, mark(maxKeyAge=373)),
            "a boundary's mark of another committee size": with_tick("dijkstra-203", "rewards", 3,
                                                                     mark(committeeSize=500)),
            "the set not the old mark's committee": with_tick("testnet-2168639", "boundaries", 0,
                                                              facts(set={"seats": 154, "keyedSeats": 153})),
            "a reward update left after a boundary": with_tick("dijkstra-203", "boundaries", 1,
                                                               facts(rewardUpdate="complete")),
            "a reward update claimed without a pulser": with_tick("testnet-2168639", "boundaries", 0,
                                                                  lambda t: t.update(rewardUpdateApplied=True)),
            "a reward update not claimed after a pulser": with_tick("dijkstra-203", "rewards", 1,
                                                                    lambda t: t.update(rewardUpdateApplied=False)),
            "no pulser at the reward start point": with_tick("testnet-2168639", "rewards", 2,
                                                             facts(rewardUpdate="absent")),
            "a pool retired that was not": with_tick("testnet-2168639", "rewards", 1,
                                                     lambda t: t.update(poolsRetired=1)),
            "a boundary state without its file": with_tick("testnet-2168639", "rewards", 1,
                                                           lambda t: t.update(file=None)),
            "a testnet pulsing state with a file": with_tick("testnet-2168639", "rewards", 0,
                                                             lambda t: t.update(file="x.cbor")),
        }.items():
            with self.subTest(name):
                self.assertNotEqual(problems(PROVENANCE, rows=rows), [], name)

    def test_the_derivations_are_bound(self):
        def change(index, **fields):
            return lambda d: d["changes"][index].update(fields)
        for name, rows in {
            "another retiring pool": with_derivation("dijkstra-203", "retire",
                                                     change(0, pool=ROWS[row_index(
                                                         "dijkstra-203", "boundaries", "both")]
                                                         ["derivation"]["pools"]["Q"])),
            "another retirement epoch": with_derivation("testnet-2168639", "both", change(3, epoch=102)),
            "another future VRF": with_derivation("dijkstra-203", "reregister-new-vrf", change(1, vrf="ab" * 32)),
            "another network": with_derivation("testnet-2168639", "both-sharer", change(1, network="Mainnet")),
            "the sharing pool's VRF not P's": with_derivation(
                "dijkstra-203", "both", change(0, vrf=ROWS[row_index("dijkstra-203", "boundaries", "both")]
                                               ["derivation"]["vrfCounts"]["Q"]["vrf"])),
            "a change left out": with_derivation("dijkstra-203", "both", lambda d: d["changes"].pop(2)),
            "pools out of key order": with_derivation(
                "dijkstra-203", "both-sharer", lambda d: d["pools"].update(P=d["pools"]["Q"], Q=d["pools"]["P"])),
            "a derived count not populateVRFKeyHashes's": with_derivation(
                "testnet-2168639", "reregister-new-vrf", lambda d: d["vrfCounts"]["P"].update(count=1)),
            "the derived state's treasury changed": with_derivation(
                "dijkstra-203", "retire", lambda d: d["facts"].update(treasury=1)),
            "no retirement in the derived state": with_derivation(
                "dijkstra-203", "retire", lambda d: d["facts"].update(poolsRetiring=0)),
            "a devnet derived state without its file": with_derivation(
                "dijkstra-203", "both", lambda d: d.update(file=None)),
            "a testnet derived state with a file": with_derivation(
                "testnet-2168639", "both", lambda d: d.update(file="x.cbor")),
            "the paths from different derived states": with_row(
                "dijkstra-203", "rewards", lambda r: r["derivation"].update(stateSha256=ZERO), "both"),
            "the shared hash dropped, not decremented": with_tick(
                "dijkstra-203", "boundaries", 0, counts("P", 0), "reregister-new-vrf"),
            "the shared hash kept after both its pools left it": with_tick(
                "testnet-2168639", "rewards", 1, counts("P", 1), "both-sharer"),
            "the retired pool's hash kept": with_tick("dijkstra-203", "boundaries", 0, counts("P", 1), "retire"),
            "the new hash not counted": with_tick("testnet-2168639", "boundaries", 0, counts("new", 0), "both"),
            "a retiring pool left after the boundary": with_tick(
                "dijkstra-203", "rewards", 1, lambda t: t["facts"].update(pools=3), "both"),
            "future parameters left after the boundary": with_tick(
                "testnet-2168639", "boundaries", 0, lambda t: t["facts"].update(futurePools=1), "reregister-new-vrf"),
            "counts without a derivation": with_tick("dijkstra-203", "boundaries", 0,
                                                     lambda t: t.update(vrfCounts={"P": {"vrf": ZERO, "count": 1}})),
            "another derivation's slots": with_row(
                "dijkstra-203", "boundaries", lambda r: r["ticks"].append(copy.deepcopy(r["ticks"][0])), "retire"),
        }.items():
            with self.subTest(name):
                self.assertNotEqual(problems(PROVENANCE, rows=rows), [], name)

    def test_the_derived_counts_are_those_recorded(self):
        # P shares its active VRF key hash with Q and re-registers with another: POOLREAP releases one reference.
        def at(name, derivation, path, index):
            tick = ROWS[row_index(name, path, derivation)]["ticks"][index]
            return {r: e["count"] for r, e in tick["vrfCounts"].items()}
        for name in run.STATES:
            derived = ROWS[row_index(name, "boundaries", "reregister-new-vrf")]["derivation"]
            self.assertEqual({r: e["count"] for r, e in derived["vrfCounts"].items()}, {"P": 2, "new": 1, "Q": 0})
            self.assertEqual(at(name, "reregister-new-vrf", "boundaries", 0), {"P": 1, "new": 1, "Q": 0})
            self.assertEqual(at(name, "both", "boundaries", 0), {"P": 1, "new": 1, "Q": 0, "R": 0})
            self.assertEqual(at(name, "both-sharer", "rewards", 1), {"P": 0, "new": 1, "Q": 0})
            self.assertEqual(at(name, "retire", "boundaries", 0), {"P": 0})

    def test_the_pledge_cap_and_committee_size_are_bound(self):
        def facts(**fields):
            return lambda t: t["facts"].update(fields)
        uncapped = ROWS[row_index("dijkstra-203", "rewards")]["ticks"][1]["facts"]
        for name, rows in {
            "another cap": with_derivation("dijkstra-203", "pledge-cap", lambda d: d["changes"][0].update(numerator=2)),
            "no cap in the derived state": with_derivation(
                "testnet-2168639", "pledge-cap", lambda d: d["facts"].update(maxPledgeLeverage=None)),
            "the capped reward the uncapped one": with_tick(
                "dijkstra-203", "rewards", 1, facts(accountBalances=uncapped["accountBalances"]), "pledge-cap"),
            "the capped reward larger": with_tick(
                "testnet-2168639", "rewards", 1, facts(accountBalances=10 ** 16), "pledge-cap"),
            "another future size": with_derivation(
                "dijkstra-203", "committee-size", lambda d: d["changes"][0].update(leiosCommitteeSize=3)),
            "no definite future parameters": with_derivation(
                "testnet-2168639", "committee-size", lambda d: d["facts"].update(futurePParams="none")),
            "the old size after the boundary": with_tick(
                "dijkstra-203", "boundaries", 0, facts(leiosCommitteeSize=900), "committee-size"),
            "the new mark of the old size": with_tick(
                "testnet-2168639", "boundaries", 0, lambda t: t["facts"]["mark"].update(committeeSize=900),
                "committee-size"),
            "more seats than the size": with_tick(
                "dijkstra-203", "rewards", 1, lambda t: t["facts"]["mark"].update(seats=3), "committee-size"),
        }.items():
            with self.subTest(name):
                self.assertNotEqual(problems(PROVENANCE, rows=rows), [], name)

    def test_the_new_derivations_are_recorded(self):
        for name in run.STATES:
            capped = ROWS[row_index(name, "rewards", "pledge-cap")]["ticks"][1]["facts"]
            uncapped = ROWS[row_index(name, "rewards")]["ticks"][1]["facts"]
            self.assertLess(capped["accountBalances"], uncapped["accountBalances"])
            self.assertEqual(capped["treasury"], uncapped["treasury"])
            sized = ROWS[row_index(name, "boundaries", "committee-size")]
            self.assertEqual(sized["derivation"]["facts"]["leiosCommitteeSize"], 900)
            self.assertEqual(sized["ticks"][0]["facts"]["mark"]["committeeSize"], run.FUTURE_COMMITTEE_SIZE)
            self.assertEqual(sized["ticks"][0]["facts"]["mark"]["seats"], run.FUTURE_COMMITTEE_SIZE)
        # The devnet's pools pledge nothing, so a cap leaves their pools no reward at all.
        self.assertEqual(ROWS[row_index("dijkstra-203", "rewards", "pledge-cap")]["ticks"][1]["facts"]
                         ["accountBalances"], 0)

    def test_the_genesis_case_is_bound(self):
        self.assertEqual(problems(PROVENANCE, genesis=GENESIS), [])
        self.assertNotEqual(problems(PROVENANCE, genesis=None), [])
        for name, record in {
            "another version": with_genesis(lambda g: g.update(version=11)),
            "equal claimed": with_genesis(lambda g: g.update(equal=True)),
            "another state": with_genesis(lambda g: g["fromByron"].update(stateSha256=ZERO)),
            "another file": with_genesis(lambda g: g["translated"].update(file="x.cbor")),
            "no pool": with_genesis(lambda g: g["fromByron"]["facts"].update(pools=0)),
            "an empty committee": with_genesis(lambda g: g["fromByron"]["facts"]["mark"].update(seats=0)),
            "another committee size": with_genesis(
                lambda g: g["createInitialState"]["facts"]["mark"].update(committeeSize=2)),
            "another key age": with_genesis(lambda g: g["fromByron"]["facts"]["mark"].update(maxKeyAge=40178)),
            "the set not the mark": with_genesis(
                lambda g: g["fromByron"]["facts"].update(set={"seats": 0, "keyedSeats": 0})),
            "another Byron UTxO": with_genesis(lambda g: g.update(byronUtxo="genesis")),
        }.items():
            with self.subTest(name):
                self.assertNotEqual(problems(PROVENANCE, genesis=record), [], name)

    def test_the_genesis_states_differ_in_the_governance_state_only(self):
        # The translations carry the Conway genesis's committee and constitution; createInitialState does not.
        self.assertFalse(GENESIS["equal"])
        self.assertEqual(GENESIS["fromByron"]["facts"], GENESIS["createInitialState"]["facts"])
        self.assertEqual(GENESIS["fromByron"]["utxo"], GENESIS["createInitialState"]["utxo"])

    def test_the_paths_are_the_ones_asked(self):
        for case in CASES:
            length = run.genesis(case["genesis"])["epochLength"]
            first = case["epoch"] * length
            window = run.genesis(case["genesis"])["randomnessStabilisationWindow"]
            self.assertEqual(case["slots"]["boundaries"], [first + length, first + 2 * length])
            self.assertEqual(case["slots"]["rewards"],
                             [first + window + 1, first + length, first + length + window + 1, first + 2 * length])
            self.assertEqual(case["derivedSlots"], {"boundaries": [first + length],
                                                    "rewards": [first + window + 1, first + length]})
        self.assertEqual([(c["name"], c["epoch"], c["tipSlot"]) for c in CASES],
                         [("dijkstra-203", 1, 203), ("testnet-2168639", 100, 2168639)])
        # the same span of each state as the node-to-client query oracle reads
        ntc = {json.loads(line)["name"]: json.loads(line)["newEpochStateSpan"]
               for line in (ROOT / "fixtures/reference/leios-ntc-queries/cases.jsonl").read_text().splitlines()}
        for case in CASES:
            self.assertEqual(case["newEpochStateSpan"], ntc[case["name"]])

    def test_every_kept_state_is_a_whole_cbor_item(self):
        for name in FILES:
            data = (run.FIXTURE / name).read_bytes()
            self.assertEqual(run.CBOR.skip(data, 0), len(data), name)
            self.assertEqual(len(run.CBOR.items(data, 0)), 6, name)


if __name__ == "__main__":
    unittest.main()
