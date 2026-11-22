# SPDX-License-Identifier: Apache-2.0
"""Unit tests for `leios-traffic --follow` (`devnet/scenarios/leios_follow.py`, #73): the severities read from
`EventKind.scala`, the follower's selected chain rebuilt from its log, the store tool's lines, the judge of the
follower and the command line. No Docker.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cbor, cli, ntcquery  # noqa: E402
from devnet.scenarios import leios_follow as lf  # noqa: E402
from devnet.scenarios import leios_traffic as lt  # noqa: E402

KINDS = {"extended": "Info", "rolledBack": "Info", "error": "Error", "fatal": "Error", "serverError": "Critical",
         "reconnecting": "Warning"}


def reference(*blocks):
    """Described reference blocks from (announces, certifies) pairs, block numbers from 0, slots 10 apart."""
    out, prev = [], None
    for n, (announces, certifies) in enumerate(blocks):
        out.append({"era": "dijkstra", "blockNo": n, "slot": 10 * n, "hash": f"h{n}", "prevHash": prev,
                    "announces": announces, "ebSize": 70 if announces else None, "certifies": certifies,
                    "signers": 4 if certifies else None})
        prev = f"h{n}"
    return out


def extended(b):
    return {"event": "extended", "block": {"slot": b["slot"], "blockNo": b["blockNo"], "hash": b["hash"]},
            "era": "Dijkstra"}


def matched():
    return {"slot": 30, "era": "dijkstra", "answers": {"DebugNewEpochState": {"matched": True},
                                                       "GetUTxOWhole": {"matched": True}}}


class SeverityTest(unittest.TestCase):
    def test_the_severities_are_the_event_kinds(self):
        source = ("  case Extended extends EventKind(Area.Chain, Severity.Info)\n"
                  "  case BlockNoVerdict extends EventKind(Area.Chain, Severity.Error)\n"
                  "  case ServerError extends EventKind(Area.Net, Severity.Critical)\n")
        self.assertEqual(lf.severities(source),
                         {"extended": "Info", "blockNoVerdict": "Error", "serverError": "Critical"})

    def test_the_node_source_names_its_error_kinds(self):
        table = lf.severities(lf.EVENT_KINDS.read_text())
        self.assertEqual(table["extended"], "Info")
        self.assertIn(table["error"], lf.FAILING)
        self.assertIn(table["blockNoVerdict"], lf.FAILING)


class EffectiveSeverityTest(unittest.TestCase):
    def test_level_override_then_kind(self):
        kinds = {"slf4j": "Info", "ledgerPeers": "Info", "inbound": "Info", "error": "Error"}
        self.assertEqual(lf.effective_severity({"event": "slf4j", "level": "Error"}, kinds), "Error")
        self.assertEqual(lf.effective_severity({"event": "ledgerPeers", "action": "snapshotVerificationFailed"},
                                                kinds), "Error")
        self.assertEqual(lf.effective_severity({"event": "inbound", "state": "refused"}, kinds), "Warning")
        self.assertEqual(lf.effective_severity({"event": "inbound", "state": "closed"}, kinds), "Info")
        self.assertEqual(lf.effective_severity({"event": "error"}, kinds), "Error")

    def test_every_raising_override_of_the_node_is_in_the_table(self):
        """Each `.at(Severity.Error)` (or above) of the node's log code is an entry of `OVERRIDES`: a new one fails
        here until the table holds it."""
        log = lf.REPO / "node/src/main/scala/xyz/kd5ujc/scarab/node/log"
        sites = sum(len(re.findall(r"\.at\(Severity\.(?:Error|Critical|Alert|Emergency)\)", f.read_text()))
                    for f in log.glob("*.scala"))
        self.assertEqual(sites, sum(1 for s in lf.OVERRIDES.values() if s in lf.FAILING))

    def test_live_polling_uses_the_same_severities(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "follower.jsonl").write_text(
                json.dumps({"event": "ledgerPeers", "action": "snapshotVerificationFailed"}) + "\n")

            class Alive:
                def poll(self):
                    return None
            self.assertIn("snapshotVerificationFailed", lf.problem(root, Alive(), {"ledgerPeers": "Info"}))


class StoreCheckCleanupTest(unittest.TestCase):
    def test_a_timeout_removes_the_helper_by_its_id_and_the_class_copies(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            tools = root / "tools-src"
            (tools / "x").mkdir(parents=True)
            (tools / "x" / "LeiosStoreCheckMain.class").write_bytes(b"")
            calls = []

            def runner(argv, **kw):
                calls.append(argv)
                if argv[1] == "run":
                    cid = argv[argv.index("--cidfile") + 1]
                    pathlib.Path(cid).write_text("abc123\n")
                    raise subprocess.TimeoutExpired(argv, kw.get("timeout"))
                return subprocess.CompletedProcess(argv, 0, "", "")

            def frozen(root_, previous, frozen=None):
                frozen.mkdir(parents=True)
                return "cp"

            args = SimpleNamespace(name="n")
            with patch.object(lf, "TOOLS_CLASSES", tools), patch.object(lf, "freeze_classes", frozen), \
                    patch.object(lf, "database_mounts", lambda db: []), \
                    patch.object(lf, "scala_container", lambda *a, **k: ["run", "--rm", "--name", "n-leios-store"]):
                found, problems = lf.store_check(args, root, ["ab"], run=runner)
            self.assertEqual(found, {})
            self.assertIn("did not end within", problems[0])
            self.assertEqual(calls[-1], ["docker", "rm", "-f", "abc123"])
            for left in ("tools-classes", "check-classes", "leios-store.cid"):
                self.assertFalse((root / left).exists(), left)

    def test_an_interruption_before_the_id_removes_the_helper_by_its_name(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            tools = root / "tools-src"
            tools.mkdir()
            (tools / "LeiosStoreCheckMain.class").write_bytes(b"")
            calls = []

            def runner(argv, **kw):
                calls.append(argv)
                if argv[1] == "run":
                    raise KeyboardInterrupt
                return subprocess.CompletedProcess(argv, 0, "", "")

            with patch.object(lf, "TOOLS_CLASSES", tools), \
                    patch.object(lf, "freeze_classes", lambda r, p, frozen=None: frozen.mkdir() or "cp"), \
                    patch.object(lf, "database_mounts", lambda db: []), \
                    patch.object(lf, "scala_container", lambda *a, **k: ["run", "--rm"]):
                with self.assertRaises(KeyboardInterrupt):
                    lf.store_check(SimpleNamespace(name="n"), root, ["ab"], run=runner)
            self.assertEqual(calls[-1], ["docker", "rm", "-f", "n-leios-store"])
            self.assertFalse((root / "check-classes").exists())


class SelectedChainTest(unittest.TestCase):
    def test_a_roll_back_cuts_the_chain_to_its_point(self):
        r = reference((None, False), (None, False), (None, False))
        fork = {"slot": 25, "blockNo": 2, "hash": "f2"}
        events = [extended(r[0]), extended(r[1]), {"event": "extended", "block": fork},
                  {"event": "rolledBack", "from": fork, "to": {"slot": 10, "hash": "h1"}, "depth": 1},
                  extended(r[2])]
        self.assertEqual([b["hash"] for b in lf.selected_chain(events)], ["h0", "h1", "h2"])
        self.assertEqual(lf.tip_block(events), 2)

    def test_a_roll_back_to_a_point_not_on_the_chain_is_reported(self):
        r = reference((None, False), (None, False))
        chain, unknown = lf.follower_chain([extended(r[0]), extended(r[1]),
                                            {"event": "rolledBack", "to": {"slot": 3, "hash": "zz"}}])
        self.assertEqual((chain, unknown), ([], [{"slot": 3, "hash": "zz"}]))

    def test_a_roll_back_to_origin_empties_it(self):
        r = reference((None, False))
        self.assertEqual(lf.selected_chain([extended(r[0]), {"event": "rolledBack", "to": {"slot": None}}]), [])
        self.assertIsNone(lf.tip_block([]))


class StoreLinesTest(unittest.TestCase):
    def test_rows_and_errors(self):
        found, errors = lf.store_lines('{"hash":"ab","body":true,"closureTxs":3}\n'
                                       '{"hash":"cd","body":false,"closureTxs":null}\n{"error":"closed"}\nnoise\n')
        self.assertEqual(found, {"ab": {"body": True, "closureTxs": 3}, "cd": {"body": False, "closureTxs": None}})
        self.assertEqual(errors, ['{"error":"closed"}', "noise"])


class JudgeFollowerTest(unittest.TestCase):
    def setUp(self):
        self.ref = reference((None, False), ("eb1", False), (None, True), (None, False))
        self.store = {"eb1": {"body": True, "closureTxs": 12}}

    def judge(self, scala=None, store=None, events=None, other=(), whole="matched", exit_code=143):
        scala = self.ref if scala is None else scala
        events = [extended(b) for b in scala] if events is None else events
        return lf.judge_follower(self.ref, scala, self.store if store is None else store, events, list(other), KINDS,
                                 5, whole=matched() if whole == "matched" else whole, exit_code=exit_code)

    def test_the_reference_chain_with_its_certified_closure_passes(self):
        v = self.judge()
        self.assertEqual(v["verdict"], "passed", v["problems"])
        self.assertEqual((v["blocksFollowed"], v["announcedEbs"], v["ebClosuresHeld"], v["certifyingBlocksApplied"]),
                         (4, 1, 1, 1))
        self.assertEqual(v["certifying"], [{"block": 2, "slot": 20, "eb": "eb1", "closureTxs": 12}])

    def test_a_certified_closure_missing_from_the_store_fails(self):
        v = self.judge(store={"eb1": {"body": True, "closureTxs": None}})
        self.assertEqual(v["verdict"], "failed")
        self.assertIn("not complete in its store (body held)", v["problems"][0])

    def test_a_tip_off_the_chain_within_k_is_a_fork_at_the_stop_and_incomplete(self):
        scala = self.ref[:3] + [{"slot": 35, "blockNo": 3, "hash": "f3"}]
        v = self.judge(scala=scala)
        self.assertEqual(v["verdict"], "incomplete", v["problems"])
        self.assertEqual(v["forkAtStop"], {"blockNo": 3, "slot": 35, "hash": "f3"})
        self.assertIn("a fork at the stop", v["incomplete"][0])
        self.assertEqual(v["certifyingBlocksApplied"], 1, "the matched prefix holds the certifying block")

    def test_a_missing_certifying_block_is_neither_applied_nor_a_pass(self):
        follower = [self.ref[0], self.ref[1], self.ref[3]]
        v = self.judge(scala=follower)
        self.assertNotEqual(v["verdict"], "passed")
        self.assertIn("a gap in the follower's chain", v["incomplete"][0])
        self.assertEqual((v["certifyingBlocksApplied"], v["certifying"], v["blocksOnReferenceChain"]), (0, [], 2))

    def test_a_chain_matched_by_block_number_but_not_from_origin_is_not_a_pass(self):
        v = self.judge(scala=self.ref[1:])
        self.assertNotEqual(v["verdict"], "passed")
        self.assertEqual(v["blocksOnReferenceChain"], 0)

    def test_a_roll_back_to_a_point_not_held_is_incomplete(self):
        v = lf.judge_follower(self.ref, self.ref, self.store, [extended(b) for b in self.ref], [], KINDS, 5,
                              whole=matched(), exit_code=143, unrecognised=[{"slot": 7, "hash": "x"}])
        self.assertEqual(v["verdict"], "incomplete")
        self.assertIn("a point its log does not show it holding", v["incomplete"][0])

    def test_a_fork_deeper_than_k_fails(self):
        scala = [self.ref[0]] + [{"slot": 10 * n + 5, "blockNo": n, "hash": f"f{n}"} for n in range(1, 8)]
        v = self.judge(scala=scala)
        self.assertEqual(v["verdict"], "failed")
        self.assertIn("is not the reference's, 6 blocks below its tip", v["problems"][0])

    def test_an_error_line_or_unparsed_output_fails(self):
        events = [extended(b) for b in self.ref] + [{"event": "error", "kind": "ValidationError", "message": "m"}]
        self.assertIn("the follower logged error", self.judge(events=events)["problems"][0])
        self.assertIn("unparsed follower output", self.judge(other=["Exception in thread"])["problems"][0])

    def test_a_library_error_and_an_error_override_fail(self):
        base = [extended(b) for b in self.ref]
        library = {"event": "slf4j", "logger": "x", "level": "Error", "message": "m", "thrown": None}
        ledger = {"event": "ledgerPeers", "action": "snapshotVerificationFailed", "reason": "r"}
        for line in (library, ledger):
            v = self.judge(events=base + [line])
            self.assertEqual(v["verdict"], "failed", line)
            self.assertIn(f"the follower logged {line['event']}", v["problems"][0])
        quiet = [{"event": "slf4j", "level": "Info"}, {"event": "ledgerPeers", "action": "snapshotVerified"}]
        self.assertEqual(self.judge(events=base + quiet)["verdict"], "passed")

    def test_warnings_are_counted_not_failed(self):
        events = [extended(b) for b in self.ref] + [{"event": "reconnecting"}] * 2
        v = self.judge(events=events)
        self.assertEqual((v["verdict"], v["warnings"]), ("passed", {"reconnecting": 2}))

    def test_an_exit_other_than_the_stop_fails(self):
        self.assertIn("ended with 1", self.judge(exit_code=1)["problems"][0])

    def test_a_ledger_state_not_compared_is_incomplete_and_a_difference_fails(self):
        v = self.judge(whole={"error": "the acquired state is of era 7", "kind": "defect", "stage": "anchor-query"})
        self.assertEqual(v["verdict"], "incomplete")
        differs = matched()
        differs["answers"]["DebugNewEpochState"] = {"matched": False, "problems": ["bytes differ"]}
        self.assertEqual(self.judge(whole=differs)["verdict"], "failed")

    def test_no_certifying_block_on_the_chain_is_incomplete(self):
        v = self.judge(scala=self.ref[:2])
        self.assertEqual(v["verdict"], "incomplete")
        self.assertEqual(v["certifying"], [])

    def test_no_block_fails(self):
        self.assertIn("applied no block", self.judge(scala=[], events=[])["problems"][0])


class CommonChainTest(unittest.TestCase):
    def test_the_part_every_node_holds(self):
        r = reference((None, False), (None, False), (None, False))
        self.assertEqual(lt.common_chain({1: r, 2: r[:2]}), r[:2])
        self.assertEqual(lt.common_chain({}), [])


class EraTest(unittest.TestCase):
    def test_dijkstra_is_admitted_only_when_asked(self):
        class Session:
            def query_raw(self, query):
                self.last_raw = cbor.enc([4, 7])

        with self.assertRaises(RuntimeError):
            ntcquery.acquired_shelley_era(Session())
        self.assertEqual(ntcquery.acquired_shelley_era(Session(), lf.LEIOS_ERAS), 7)


class CliTest(unittest.TestCase):
    def test_follow_is_off_by_default_and_names_its_node(self):
        seen = {}
        with patch.object(sys, "argv", ["devnet.py", "leios-traffic"]), \
                patch.object(cli, "run", lambda args: seen.update(vars(args))):
            cli.main()
        self.assertEqual((seen["follow"], seen["follow_node"], seen["scala_cpus"]), (False, 1, 2))
        with patch.object(sys, "argv", ["devnet.py", "leios-traffic", "--follow", "--follow-node", "2"]), \
                patch.object(cli, "run", lambda args: seen.update(vars(args))):
            cli.main()
        self.assertEqual((seen["follow"], seen["follow_node"]), (True, 2))

    def test_the_follower_runs_the_leios_build_with_its_store_and_socket(self):
        command = lf.follower_command("n", 2, 2)
        self.assertEqual(command[2:5], ["--name", "n", "follow"])
        for flag in (["--reference-build", "leios-b004fe0c"], ["--db"], ["--local-socket"], ["--node", "2"]):
            self.assertTrue(any(command[i:i + len(flag)] == flag for i in range(len(command))), flag)

    def test_a_voting_follower_listens_and_is_given_the_pools_keys(self):
        command = lf.follower_command("n", 2, 2, ("127.0.0.1:3101", "/run/voter"))
        self.assertEqual(command[-4:], ["--listen", "127.0.0.1:3101", "--voter-keys", "/run/voter"])
        self.assertNotIn("--listen", lf.follower_command("n", 2, 2))


if __name__ == "__main__":
    unittest.main()
