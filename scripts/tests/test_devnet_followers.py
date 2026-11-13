# SPDX-License-Identifier: Apache-2.0
"""Several Scala followers in one devnet run, one per Plutus evaluator (`e2e --plutus-vm scalus --plutus-vm scarab`):
their names and ports, the comparisons that read the reference once for all of them, and the cross-check.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import contextlib
import io
import json
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cluster, compare, eraview, follower, util  # noqa: E402
from devnet.scenarios import e2e as e2e_scenario  # noqa: E402
from devnet.util import SINGLE_FOLLOWER, FollowerSite, follower_plan  # noqa: E402
from oracle.finality import Block  # noqa: E402

SCALUS, SCARAB = FollowerSite("scalus"), FollowerSite("scarab")


def h(n):
    return f"{n:064x}"


def blocks(numbers, fork=0):
    """Blocks with numbers `numbers` at slots 10 * (number + 1), hashed by number (and `fork`, for a rival chain)."""
    return [Block(10 * (n + 1), h(n + 1000 * fork), n) for n in numbers]


def log_text(chain, partial=None):
    """A follower log of one session from Origin that extends `chain`, with non-chain lines, ending with a newline
    (or with the start of a line still being written, `partial`)."""
    lines = [json.dumps({"event": "started"}), "SLF4J: a banner line", json.dumps({"event": "connected",
                                                                                 "intersection": {"origin": True}})]
    lines += [json.dumps({"event": "extended", "era": "Conway",
                          "block": {"slot": b.slot, "blockNo": b.block_no, "hash": b.hash}}) for b in chain]
    return "\n".join(lines) + "\n" + (partial or "")


def chain(blocks_):
    return e2e_scenario.logged_chain(log_text(blocks_), None)


class FollowerSiteTest(unittest.TestCase):
    def test_the_only_follower_keeps_every_name_and_port_a_run_has_had(self):
        root = pathlib.Path("/run")
        site = SINGLE_FOLLOWER
        self.assertEqual(site.container("n"), "n-scala")
        self.assertEqual(site.log(root), root / "follower.jsonl")
        self.assertEqual([site.directory(root, d).name for d in ("scala-sock", "stall", "db", "dump", "classes",
                                                                  "whole-state")],
                         ["scala-sock", "stall", "db", "dump", "classes", "whole-state"])
        self.assertEqual((site.query_port, site.metrics_port), (util.QUERY_PORT, util.METRICS_PORT))
        self.assertEqual((util.QUERY_PORT, util.METRICS_PORT), (7071, 12798))
        self.assertEqual(site.role, "follower")

    def test_keyed_followers_have_names_and_ports_of_their_own(self):
        root = pathlib.Path("/run")
        self.assertEqual([s.container("n") for s in (SCALUS, SCARAB)], ["n-scala-scalus", "n-scala-scarab"])
        self.assertEqual([s.log(root).name for s in (SCALUS, SCARAB)], ["follower-scalus.jsonl", "follower-scarab.jsonl"])
        self.assertEqual(SCARAB.directory(root, "scala-sock"), root / "scala-sock-scarab")
        ports = [p for s in util.ALL_FOLLOWERS for p in (s.query_port, s.metrics_port)]
        self.assertEqual(len(set(ports)), len(ports), f"every follower a run may start has ports of its own: {ports}")
        self.assertEqual(SCARAB.role, "scarab follower")

    def test_a_follower_is_keyed_by_an_evaluator_only(self):
        with self.assertRaises(ValueError):
            FollowerSite("plutus")

    def test_the_plan_has_one_follower_unless_several_evaluators_are_named(self):
        self.assertEqual(follower_plan(None), [(SINGLE_FOLLOWER, None)])
        self.assertEqual(follower_plan(["scarab"]), [(SINGLE_FOLLOWER, "scarab")])
        self.assertEqual(follower_plan(["scalus", "scarab"]), [(SCALUS, "scalus"), (SCARAB, "scarab")])
        with self.assertRaises(SystemExit):
            follower_plan(["scarab", "scarab"])

    def test_the_port_check_sees_a_jvm_listener_in_the_ipv6_table_after_the_ipv4_one(self):
        # `e2e` reads both tables in one text: a JVM's 127.0.0.1 listener is an IPv4-mapped IPv6 socket
        tcp = ("  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
               "   0: 0100007F:9C4B 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1 1\n")
        tcp6 = ("  sl  local_address                         remote_address                        st tx_queue\n"
                "   0: 0000000000000000FFFF00000100007F:1BA0 00000000000000000000000000000000:0000 0A 00000000:00000000"
                " 00:00000000 00000000  1000        0 155865501 1\n")
        self.assertEqual(cluster.listening_ports(tcp + tcp6), {0x9C4B, SCALUS.query_port})

    def test_down_removes_every_follower_a_run_may_have_started(self):
        removed = []
        with tempfile.TemporaryDirectory() as d, \
                patch.object(cluster, "data_root", return_value=pathlib.Path(d) / "run"), \
                patch.object(cluster, "docker", side_effect=lambda *a, **k: removed.append(a)):
            cluster.down(types.SimpleNamespace(name="n"), quiet=True)
        self.assertEqual([a[2] for a in removed],
                         ["n-scala", "n-scala-scalus", "n-scala-scarab", "n-cli", "n-ref"])


class FollowerProcessTest(unittest.TestCase):
    def test_a_keyed_follower_reaches_the_child_cli_with_its_instance(self):
        with patch.object(follower.subprocess, "Popen") as launch:
            follower.start_follower("audit", pathlib.Path("/tmp/audit"), plutus_vm="scarab", site=SCARAB)
        self.assertEqual(launch.call_args.args[0], [
            sys.executable, str(follower.ENTRY), "--name", "audit", "follow", "--plutus-vm", "scarab",
            "--instance", "scarab"])

    def follow(self, instance):
        """(program arguments, scala_container keywords, frozen directory) of `follow` for `instance`."""
        seen = {}

        def container(args, root, classpath, main, program, mounts=(), name=None, classes=None):
            seen.update(program=program, mounts=mounts, name=name, classes=classes)
            return ["run"]
        process = Mock(stdout=[], wait=Mock(return_value=0))
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d) / "n"
            (root / "genesis").mkdir(parents=True)
            (root / "ports.json").write_text(json.dumps({"1": 3001}))
            args = types.SimpleNamespace(name="n", node=1, seconds=None, local_socket=True, instance=instance,
                                         plutus_vm=instance)
            with patch.object(follower, "data_root", return_value=root), \
                    patch.object(follower, "freeze_classes",
                                 side_effect=lambda r, previous, frozen: seen.update(frozen=frozen) or "cp"), \
                    patch.object(follower, "scala_container", side_effect=container), \
                    patch.object(follower.subprocess, "Popen", return_value=process), \
                    self.assertRaises(SystemExit):
                follower.follow(args)
            return seen, root

    def test_a_keyed_follower_runs_with_its_own_ports_socket_stall_and_classes(self):
        seen, root = self.follow("scarab")
        program = seen["program"]
        self.assertEqual(program[program.index("--query-port") + 1], str(SCARAB.query_port))
        self.assertEqual(program[program.index("--metrics-listen") + 1], f"127.0.0.1:{SCARAB.metrics_port}")
        self.assertEqual(seen["name"], "n-scala-scarab")
        self.assertEqual(seen["classes"], root / "classes-scarab")
        self.assertEqual(seen["frozen"], root / "classes-scarab")
        self.assertIn(f"{root / 'scala-sock-scarab'}:/sock", seen["mounts"])
        self.assertIn(f"{root / 'stall-scarab'}:/stall", seen["mounts"])

    def test_the_only_follower_runs_as_it_always_has(self):
        seen, root = self.follow(None)
        program = seen["program"]
        self.assertEqual(program[program.index("--query-port") + 1], "7071")
        self.assertEqual(program[program.index("--metrics-listen") + 1], "127.0.0.1:12798")
        self.assertEqual(seen["name"], "n-scala")
        self.assertEqual(seen["classes"], root / "classes")
        self.assertIn(f"{root / 'scala-sock'}:/sock", seen["mounts"])

    def test_a_problem_names_the_follower_it_is_about(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            SCARAB.log(root).write_text(json.dumps({"event": "error", "message": "boom"}) + "\n")
            SINGLE_FOLLOWER.log(root).write_text(json.dumps({"event": "started"}) + "\n")
            running = Mock(poll=Mock(return_value=None))
            self.assertEqual(follower.follower_problem(root, running, SCARAB), "scarab follower error: boom")
            self.assertIsNone(follower.follower_problem(root, running))


class ProjectionDigestTest(unittest.TestCase):
    def test_equal_views_have_one_digest_whatever_their_key_order(self):
        self.assertEqual(compare.projection_digest({"a": 1, "b": {"c": [1, 2]}}),
                         compare.projection_digest({"b": {"c": [1, 2]}, "a": 1}))

    def test_a_difference_anywhere_and_a_key_of_another_type_change_the_digest(self):
        base = compare.projection_digest({"a": 1, "b": {"c": [1, 2]}})
        self.assertNotEqual(base, compare.projection_digest({"a": 1, "b": {"c": [1, 3]}}))
        self.assertNotEqual(compare.projection_digest({1: "x"}), compare.projection_digest({"1": "x"}))


class SameBlockComparisonTest(unittest.TestCase):
    """Every follower is compared with the one reference block a comparison reads."""
    TIP = {"slot": 123, "hash": "block"}
    STATE = {"epoch": 7, "utxo": {}, "rewardUpdate": None, "params": {}, "ppup": {},
             "mir": {"reserves": {}, "treasury": {}, "deltaReserves": 0, "deltaTreasury": 0}}

    def compare(self, states, attempts=1):
        """(samples, the follower reads (key, slot), reference) of one pre-Conway comparison of both followers."""
        reads = []
        reference = self.STATE
        session = Mock()
        context = Mock(__enter__=Mock(return_value=session), __exit__=Mock(return_value=False))

        def state(name, tip, site=SINGLE_FOLLOWER):
            reads.append((site.key, tip["slot"]))
            return states[site.key]
        with patch.object(compare, "protocol_parameters", return_value={}), \
                patch.object(compare, "lsq", return_value=context), \
                patch.object(eraview, "reference_pre_conway", side_effect=lambda *a: (self.TIP, json.loads(json.dumps(reference)))), \
                patch.object(compare, "scala_state", side_effect=state), \
                patch.object(compare.time, "sleep"):
            samples = compare.compare_pre_conway_followers("n", "alonzo", (SCALUS, SCARAB), attempts=attempts)
        return samples, reads, reference

    def test_both_followers_are_asked_at_the_block_the_reference_was_read_at(self):
        samples, reads, _ = self.compare({k: dict(self.STATE) for k in ("scalus", "scarab")})
        self.assertEqual(reads, [("scalus", 123), ("scarab", 123)])
        self.assertTrue(all(s.result["matched"] for s in samples), [s.result for s in samples])
        self.assertEqual(samples[0].projection, samples[1].projection)

    def test_followers_that_differ_have_different_projections(self):
        samples, _, _ = self.compare({"scalus": dict(self.STATE), "scarab": dict(self.STATE, epoch=8)})
        self.assertTrue(samples[0].result["matched"])
        self.assertFalse(samples[1].result["matched"])
        self.assertNotEqual(samples[0].projection, samples[1].projection)

    def test_a_follower_that_never_reaches_the_block_has_its_own_error_and_the_other_its_result(self):
        samples, reads, _ = self.compare({"scalus": dict(self.STATE), "scarab": None}, attempts=2)
        self.assertEqual(reads.count(("scalus", 123)), 2, "the first attempt is taken again at a new block")
        self.assertTrue(samples[0].result["matched"])
        self.assertEqual(samples[1], compare.Sample({"error": "no stable comparison within the attempts"}))


class FollowerAgreementTest(unittest.TestCase):
    def test_points_are_tallied_by_whether_every_projection_was_taken_and_all_are_equal(self):
        with tempfile.TemporaryDirectory() as d:
            agreement = compare.FollowerAgreement(pathlib.Path(d), (SCALUS, SCARAB))
            tip = {"tip": {"slot": 5, "hash": "a"}}
            agreement.record("shelley", "start", [compare.Sample(tip, "x"), compare.Sample(tip, "x")])
            agreement.record("shelley", "mid", [compare.Sample(tip, "x"), compare.Sample(tip, "y")])
            agreement.record("shelley", "late", [compare.Sample(tip, "x"), compare.Sample({"error": "e"})])
            rows = [json.loads(l) for l in (pathlib.Path(d) / "compare-followers.jsonl").read_text().splitlines()]
        self.assertEqual(agreement.per_era["shelley"], {"agreed": 1, "differed": 1, "notCompared": 1})
        self.assertEqual([r["outcome"] for r in rows], ["agreed", "differed", "notCompared"])
        self.assertEqual(rows[1]["projections"], {"scalus": "x", "scarab": "y"})
        self.assertEqual(agreement.differences, [rows[1]])

    def test_a_difference_and_an_era_without_an_agreed_point_fail(self):
        per_era = {e: {"agreed": 2, "differed": 0, "notCompared": 0} for e in util.ERAS}
        self.assertEqual(compare.agreement_failures(per_era), [])
        per_era["mary"] = {"agreed": 0, "differed": 0, "notCompared": 3}
        per_era["conway"] = {"agreed": 4, "differed": 1, "notCompared": 0}
        failures = compare.agreement_failures(per_era)
        self.assertEqual(len(failures), 2, failures)
        self.assertIn("mary: only 0 reference blocks", failures[0])
        self.assertIn("conway: the followers' projected states differ at 1 of 5", failures[1])

    def test_compare_era_records_each_follower_and_their_agreement_with_one_reading(self):
        recorded = []
        tallies = [types.SimpleNamespace(site=site, record=lambda era, label, r, k=site.key: recorded.append((k, r)),
                                         record_whole=lambda era, label, r: None) for site in (SCALUS, SCARAB)]
        agreement = Mock()
        samples = [compare.Sample({"matched": True}, "x"), compare.Sample({"matched": True}, "x")]
        whole = []
        with patch.object(compare, "compare_followers_detailed", return_value=(samples, None)) as reading, \
                patch.object(compare, "whole_state", side_effect=lambda name, era, site: whole.append(site.key) or {}):
            compare.compare_era("n", "conway", "mid", tallies, agreement)
        self.assertEqual(reading.call_count, 1)
        self.assertEqual(reading.call_args.args[1], [SCALUS, SCARAB])
        self.assertEqual(recorded, [("scalus", {"matched": True}), ("scarab", {"matched": True})])
        agreement.record.assert_called_once_with("conway", "mid", samples)
        self.assertEqual(whole, ["scalus", "scarab"])

    def test_a_keyed_tally_writes_its_own_file(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(compare.Comparisons(pathlib.Path(d), SCARAB).file.name, "compare-scarab.jsonl")
            self.assertEqual(compare.Comparisons(pathlib.Path(d)).file.name, "compare.jsonl")

    def test_the_whole_state_of_a_keyed_follower_is_asked_on_its_own_socket(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "scala-sock").mkdir()
            (root / "scala-sock" / "node.socket").write_text("")  # the only follower's socket, not the keyed one's
            with patch.object(compare.util, "data_root", return_value=root):
                self.assertEqual(compare.whole_state("n", "conway", site=SCARAB),
                                 {"skipped": "the Scala node has no node-to-client socket"})


class LoggedChainTest(unittest.TestCase):
    def test_a_line_still_being_written_and_lines_that_are_not_json_are_left_out(self):
        rebuilt = e2e_scenario.logged_chain(log_text(blocks(range(4)), partial='{"event": "exten'), None)
        self.assertEqual([b.block_no for b in rebuilt.blocks], [0, 1, 2, 3])


class ChainAgreementTest(unittest.TestCase):
    K = 3

    def test_a_follower_that_stopped_earlier_agrees(self):
        report, failures = e2e_scenario.chain_agreement(
            {"scalus": chain(blocks(range(20))), "scarab": chain(blocks(range(12)))}, self.K)
        self.assertEqual(failures, [])
        self.assertEqual(report["pairs"][0]["commonBlocks"], 12)
        self.assertEqual(report["pairs"][0]["beyondCommon"], {"scalus": 8, "scarab": 0})
        self.assertEqual(report["tips"]["scarab"]["blockNo"], 11)

    def test_a_rival_tip_within_k_blocks_agrees(self):
        rival = blocks(range(10)) + blocks(range(10, 13), fork=1)
        report, failures = e2e_scenario.chain_agreement(
            {"scalus": chain(blocks(range(14))), "scarab": chain(rival)}, self.K)
        self.assertEqual(failures, [])
        self.assertEqual(report["pairs"][0]["beyondCommon"], {"scalus": 4, "scarab": 3})

    def test_chains_that_differ_deeper_than_k_blocks_diverge(self):
        rival = blocks(range(10)) + blocks(range(10, 14), fork=1)
        report, failures = e2e_scenario.chain_agreement(
            {"scalus": chain(blocks(range(14))), "scarab": chain(rival)}, self.K)
        self.assertFalse(report["pairs"][0]["agreed"])
        self.assertEqual(len(failures), 1)
        self.assertIn("4 and 4 blocks after their last common block", failures[0])

    def test_a_follower_with_no_chain_fails(self):
        _, failures = e2e_scenario.chain_agreement({"scalus": chain(blocks(range(5))), "scarab": chain([])}, self.K)
        self.assertEqual(failures, ["the scarab follower's log holds no chain"])


class CrossCheckTest(unittest.TestCase):
    def run_check(self, scalus_log, scarab_log):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "genesis").mkdir()
            (root / "genesis" / "shelley-genesis.json").write_text(json.dumps({"securityParam": 3}))
            SCALUS.log(root).write_text(scalus_log)
            SCARAB.log(root).write_text(scarab_log)
            entries = {"scalus": {"reachedTarget": True, "validationErrors": [], "fatalErrors": []},
                       "scarab": {"reachedTarget": False, "validationErrors": [{}], "fatalErrors": []}}
            return e2e_scenario.followers_cross_check(root, [SCALUS, SCARAB], entries, None)

    def test_the_report_holds_each_follower_and_the_chains_judged_from_their_logs(self):
        report, failures = self.run_check(log_text(blocks(range(9))), log_text(blocks(range(8))))
        self.assertEqual(failures, [])
        self.assertEqual(report["reachedTarget"], {"scalus": True, "scarab": False})
        self.assertEqual(report["validationErrors"], {"scalus": 0, "scarab": 1})
        self.assertTrue(report["finalChain"]["pairs"][0]["agreed"])

    def test_a_log_that_is_not_one_chain_fails_the_check_without_raising(self):
        broken = log_text(blocks([0, 1, 3]))  # block 2 is missing
        report, failures = self.run_check(log_text(blocks(range(4))), broken)
        self.assertEqual(len(failures), 1)
        self.assertIn("the scarab follower's final chain cannot be rebuilt from its log", failures[0])
        self.assertEqual(report["finalChain"], {"error": "not every follower's chain could be rebuilt"})


class SummaryEvaluatorTest(unittest.TestCase):
    EXPECTED = [{"era": "byron", "epoch": 0}]

    def failures(self, events, plutus_vm):
        summary, failures = e2e_scenario.summarize_e2e(events, [], self.EXPECTED, None, plutus_vm=plutus_vm)
        return summary, [f for f in failures if "Plutus evaluator" in f]

    def test_the_follower_must_run_the_evaluator_it_was_given(self):
        summary, failures = self.failures([{"event": "plutusVm", "vm": "scarab"}], "scarab")
        self.assertEqual((summary["plutusVm"], failures), (["scarab"], []))
        self.assertEqual(len(self.failures([{"event": "plutusVm", "vm": "scalus"}], "scarab")[1]), 1)
        self.assertEqual(len(self.failures([], "scarab")[1]), 1)

    def test_the_default_follower_is_not_asked_about_its_evaluator(self):
        summary, failures = self.failures([], None)
        self.assertNotIn("plutusVm", summary)
        self.assertEqual(failures, [])


class E2EArgumentsTest(unittest.TestCase):
    def test_the_oracle_is_refused_with_several_followers_before_anything_starts(self):
        args = types.SimpleNamespace(name="n", plutus_vm=["scalus", "scarab"], oracle=True)
        with patch.object(e2e_scenario, "up_eras", side_effect=AssertionError("nothing may start")), \
                patch.object(e2e_scenario, "data_root", side_effect=lambda name: pathlib.Path("/nonexistent/n")), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                e2e_scenario.e2e(args)
        self.assertIn("--oracle", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
