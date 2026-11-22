# SPDX-License-Identifier: Apache-2.0
"""Unit tests for `leios-traffic --follow` (`devnet/scenarios/leios_follow.py`, #73): the severities read from
`EventKind.scala`, the follower's selected chain rebuilt from its log, the store tool's lines, the judge of the
follower and the command line. No Docker.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import sys
import unittest
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


class SelectedChainTest(unittest.TestCase):
    def test_a_roll_back_cuts_the_chain_to_its_point(self):
        r = reference((None, False), (None, False), (None, False))
        fork = {"slot": 25, "blockNo": 2, "hash": "f2"}
        events = [extended(r[0]), extended(r[1]), {"event": "extended", "block": fork},
                  {"event": "rolledBack", "from": fork, "to": {"slot": 10, "hash": "h1"}, "depth": 1},
                  extended(r[2])]
        self.assertEqual([b["hash"] for b in lf.selected_chain(events)], ["h0", "h1", "h2"])
        self.assertEqual(lf.tip_block(events), 2)

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

    def test_a_tip_off_the_chain_within_k_is_a_fork_at_the_stop(self):
        scala = self.ref[:3] + [{"slot": 35, "blockNo": 3, "hash": "f3"}]
        v = self.judge(scala=scala)
        self.assertEqual(v["verdict"], "passed", v["problems"])
        self.assertEqual(v["forkAtStop"], {"blockNo": 3, "slot": 35, "hash": "f3"})

    def test_a_fork_deeper_than_k_fails(self):
        scala = [self.ref[0]] + [{"slot": 10 * n + 5, "blockNo": n, "hash": f"f{n}"} for n in range(1, 8)]
        v = self.judge(scala=scala)
        self.assertEqual(v["verdict"], "failed")
        self.assertIn("is not the reference's, 6 blocks below its tip", v["problems"][0])

    def test_an_error_line_or_unparsed_output_fails(self):
        events = [extended(b) for b in self.ref] + [{"event": "error", "kind": "ValidationError", "message": "m"}]
        self.assertIn("the follower logged error", self.judge(events=events)["problems"][0])
        self.assertIn("unparsed follower output", self.judge(other=["Exception in thread"])["problems"][0])

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


if __name__ == "__main__":
    unittest.main()
