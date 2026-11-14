# SPDX-License-Identifier: Apache-2.0
"""Several Scala followers in one devnet run, one per Plutus evaluator (`e2e --plutus-vm scalus --plutus-vm scarab`):
their names and ports, the comparisons that read the reference once for all of them, and the cross-check.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import contextlib
import io
import json
import pathlib
import shutil
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
    """The chain the log of `blocks_` describes, rebuilt by the oracle's rules."""
    from oracle import devnet as oracle_devnet
    from oracle import finality
    return finality.FollowerChain.from_events(finality.parse_log(log_text(blocks_), oracle_devnet.benign))


def tip_of(blocks_):
    return {"slot": blocks_[-1].slot, "hash": blocks_[-1].hash}


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
    """Pre-Conway: every follower is compared with the one reference block an attempt reads, a judgment stands once
    made, and only a follower that did not answer is asked again (at the next attempt's block)."""
    STATE = {"epoch": 7, "utxo": {}, "rewardUpdate": None, "params": {}, "ppup": {},
             "mir": {"reserves": {}, "treasury": {}, "deltaReserves": 0, "deltaTreasury": 0}}

    def compare(self, states, attempts=1):
        """(samples, the follower reads (key, slot)) of one pre-Conway comparison of both followers: attempt `n` reads
        the reference at slot 123 + n, and `states` maps (key, slot) to what the follower answers there (None for
        no answer)."""
        reads, slots = [], iter(range(123, 123 + attempts))
        session = Mock()
        context = Mock(__enter__=Mock(return_value=session), __exit__=Mock(return_value=False))

        def reference(*args):
            slot = next(slots)
            return {"slot": slot, "hash": f"block-{slot}"}, json.loads(json.dumps(self.STATE))

        def state(name, tip, site=SINGLE_FOLLOWER):
            reads.append((site.key, tip["slot"]))
            answer = states.get((site.key, tip["slot"]))
            return None if answer is None else json.loads(json.dumps(answer))
        with patch.object(compare, "protocol_parameters", return_value={}), \
                patch.object(compare, "lsq", return_value=context), \
                patch.object(eraview, "reference_pre_conway", side_effect=reference), \
                patch.object(compare, "scala_state", side_effect=state), \
                patch.object(compare.time, "sleep"):
            samples = compare.compare_pre_conway_followers("n", "alonzo", (SCALUS, SCARAB), attempts=attempts)
        return samples, reads

    def test_both_followers_are_asked_at_the_block_the_reference_was_read_at(self):
        samples, reads = self.compare({(k, 123): self.STATE for k in ("scalus", "scarab")})
        self.assertEqual(reads, [("scalus", 123), ("scarab", 123)])
        self.assertTrue(all(s.result["matched"] for s in samples), [s.result for s in samples])
        self.assertEqual(samples[0].projection, samples[1].projection)

    def test_followers_that_differ_have_different_projections(self):
        samples, _ = self.compare({("scalus", 123): self.STATE, ("scarab", 123): dict(self.STATE, epoch=8)})
        self.assertTrue(samples[0].result["matched"])
        self.assertFalse(samples[1].result["matched"])
        self.assertNotEqual(samples[0].projection, samples[1].projection)

    def test_a_follower_that_never_reaches_the_block_has_its_own_error_and_the_other_its_result(self):
        samples, reads = self.compare({("scalus", 123): self.STATE}, attempts=2)
        self.assertEqual(reads.count(("scalus", 123)), 1, "a judged follower is not asked again")
        self.assertNotIn(("scalus", 124), reads)
        self.assertTrue(samples[0].result["matched"])
        self.assertEqual(samples[1], compare.Sample({"error": "no stable comparison within the attempts"}))

    def test_a_retry_for_one_follower_never_erases_the_difference_of_the_other(self):
        # the scalus follower answers wrongly at the first block while the scarab one has not reached it; at the
        # next both would answer rightly: the difference stands, and the scarab follower is judged at its block
        samples, reads = self.compare({("scalus", 123): dict(self.STATE, epoch=8), ("scalus", 124): self.STATE,
                                       ("scarab", 124): self.STATE}, attempts=2)
        self.assertFalse(samples[0].result["matched"], samples[0].result)
        self.assertEqual(samples[0].result["tip"]["slot"], 123)
        self.assertTrue(samples[1].result["matched"])
        self.assertEqual(samples[1].result["tip"]["slot"], 124)
        self.assertNotIn(("scalus", 124), reads)


class ConwayComparisonTest(unittest.TestCase):
    """Conway (`compare_followers_detailed`): the reference read through its query bundle and whole UTxO, each
    follower judged against that block as in the earlier eras."""
    NONCES = {k: "00" for k in ("epochNonce", "evolvingNonce", "candidateNonce", "labNonce", "lastEpochBlockNonce",
                                "oCertCounters")}
    VIEW = {"epoch": 9, "dreps": {}, "blocksCurrent": {}, "blocksBefore": {}, "stakeDistrib": {"total": 1, "pools": {}}}
    STATE = {**VIEW, "utxo": {}, "gov": {}, "protocolState": NONCES, "accounts": {}}

    def compare(self, states, attempts=1):
        """(samples, follower reads (key, slot)) of one Conway comparison of both followers: attempt `n` reads the
        reference at slot 400 + n, and `states` maps (key, slot) to the follower's answer there."""
        reads, slots = [], iter(range(400, 400 + attempts))

        def bundle(name):
            slot = next(slots)
            tip = {"slot": slot, "block": slot // 2, "epoch": 9, "hash": f"block-{slot}"}
            return {"tip": tip, "ls": {}, "utxo": {}, "protocol": self.NONCES, "gov": {}, "committee": {},
                    "drep": {}}, None

        def state(name, tip, site=SINGLE_FOLLOWER):
            reads.append((site.key, tip["slot"]))
            answer = states.get((site.key, tip["slot"]))
            return None if answer is None else json.loads(json.dumps(answer))
        with patch.object(compare, "query_bundle", side_effect=bundle), \
                patch.object(compare, "reference_utxo", return_value=({}, [])), \
                patch.object(compare, "utxo_sources_agree", return_value=True), \
                patch.object(compare, "reference_view", side_effect=lambda ls, utxo: json.loads(json.dumps(self.VIEW))), \
                patch.object(compare.govview, "reference_gov", return_value={}), \
                patch.object(compare.govview, "scala_gov", return_value={"prevGovActionIds": []}), \
                patch.object(compare, "scala_state", side_effect=state), \
                patch.object(compare.time, "sleep"):
            return compare.compare_followers_detailed("n", (SCALUS, SCARAB), attempts=attempts, scala_wait=1), reads

    def test_both_followers_are_judged_against_the_block_the_bundle_was_read_at(self):
        (samples, failures), reads = self.compare({(k, 400): self.STATE for k in ("scalus", "scarab")})
        self.assertEqual(failures, [])
        self.assertEqual(reads, [("scalus", 400), ("scarab", 400)])
        self.assertEqual([s.result["matched"] for s in samples], [True, True], [s.result for s in samples])
        self.assertEqual([s.result["tip"]["slot"] for s in samples], [400, 400])
        self.assertEqual(samples[0].projection, samples[1].projection)
        self.assertEqual(sorted(samples[0].result["scalaOnlyKeys"]), ["accounts"])

    def test_a_retry_for_one_follower_never_erases_the_difference_of_the_other(self):
        (samples, _), reads = self.compare({("scalus", 400): dict(self.STATE, epoch=8), ("scalus", 401): self.STATE,
                                            ("scarab", 401): self.STATE}, attempts=2)
        self.assertFalse(samples[0].result["matched"], samples[0].result)
        self.assertEqual(samples[0].result["differences"][0]["path"], "/epoch")
        self.assertEqual(samples[0].result["tip"]["slot"], 400)
        self.assertTrue(samples[1].result["matched"])
        self.assertEqual(samples[1].result["tip"]["slot"], 401)
        self.assertNotIn(("scalus", 401), reads)

    def test_a_follower_that_never_reaches_a_block_has_its_own_error(self):
        (samples, _), _ = self.compare({("scalus", 400): self.STATE}, attempts=2)
        self.assertTrue(samples[0].result["matched"])
        self.assertEqual(samples[1].result["error"], "scala node never reached the reference tip")
        self.assertEqual(samples[1].result["tip"]["slot"], 401)


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

    def test_followers_judged_at_different_blocks_are_not_compared_with_each_other(self):
        with tempfile.TemporaryDirectory() as d:
            agreement = compare.FollowerAgreement(pathlib.Path(d), (SCALUS, SCARAB))
            agreement.record("conway", "mid", [compare.Sample({"tip": {"slot": 5, "hash": "a"}}, "x"),
                                               compare.Sample({"tip": {"slot": 6, "hash": "b"}}, "y")])
            row = json.loads((pathlib.Path(d) / "compare-followers.jsonl").read_text())
        self.assertEqual(agreement.per_era["conway"], {"agreed": 0, "differed": 0, "notCompared": 1})
        self.assertEqual(row["blocks"], {"scalus": [5, "a"], "scarab": [6, "b"]})
        self.assertEqual(agreement.differences, [])

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
                patch.object(compare, "whole_state",
                             side_effect=lambda name, era, site, reselections: whole.append((site.key, reselections))
                             or {}):
            compare.compare_era("n", "conway", "mid", tallies, agreement)
        self.assertEqual(reading.call_count, 1)
        self.assertEqual(reading.call_args.args[1], [SCALUS, SCARAB])
        self.assertEqual(recorded, [("scalus", {"matched": True}), ("scarab", {"matched": True})])
        agreement.record.assert_called_once_with("conway", "mid", samples)
        # each follower's same-point state at its own tip, a tip a fork invalidated sampled again within the bound
        self.assertEqual(whole, [("scalus", compare.WHOLE_STATE_RESELECTIONS), ("scarab", compare.WHOLE_STATE_RESELECTIONS)])

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
    K = 3
    COMMON = blocks(range(6))
    # two chains that part after block 5 and each hold four blocks of their own: more than k = 3, so they diverge
    SCALUS_CHAIN = COMMON + blocks(range(6, 10))
    SCARAB_CHAIN = COMMON + blocks(range(6, 10), fork=1)

    def run_check(self, scalus_log, scarab_log, tips=None):
        """The cross-check of two logs, with the final tips the stop recorded (`tips`: key -> tip, None for none)."""
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            (root / "genesis").mkdir()
            (root / "genesis" / "shelley-genesis.json").write_text(json.dumps({"securityParam": self.K}))
            SCALUS.log(root).write_text(scalus_log)
            SCARAB.log(root).write_text(scarab_log)
            for key, tip in (tips or {}).items():
                (root / f"follower-tip-{key}.json").write_text(json.dumps(tip))  # as the run's stop records them
            entries = {"scalus": {"reachedTarget": True, "validationErrors": [], "fatalErrors": []},
                       "scarab": {"reachedTarget": False, "validationErrors": [{}], "fatalErrors": []}}
            return e2e_scenario.followers_cross_check(root, [SCALUS, SCARAB], entries, None)

    def test_the_report_holds_each_follower_and_the_chains_judged_from_their_logs(self):
        report, failures = self.run_check(log_text(blocks(range(9))), log_text(blocks(range(8))),
                                          {"scalus": tip_of(blocks(range(9))), "scarab": tip_of(blocks(range(8)))})
        self.assertEqual(failures, [])
        self.assertEqual(report["reachedTarget"], {"scalus": True, "scarab": False})
        self.assertEqual(report["validationErrors"], {"scalus": 0, "scarab": 1})
        self.assertTrue(report["finalChain"]["pairs"][0]["agreed"])

    def test_a_log_that_is_not_one_chain_fails_the_check_without_raising(self):
        broken = blocks([0, 1, 3])  # block 2 is missing
        report, failures = self.run_check(log_text(blocks(range(4))), log_text(broken),
                                          {"scalus": tip_of(blocks(range(4))), "scarab": tip_of(broken)})
        self.assertEqual(len(failures), 1)
        self.assertIn("the scarab follower's final chain cannot be rebuilt from its log", failures[0])
        self.assertEqual(report["finalChain"], {"error": "not every follower's chain could be rebuilt"})

    def test_chains_that_diverge_deeper_than_k_fail(self):
        _, failures = self.run_check(log_text(self.SCALUS_CHAIN), log_text(self.SCARAB_CHAIN),
                                     {"scalus": tip_of(self.SCALUS_CHAIN), "scarab": tip_of(self.SCARAB_CHAIN)})
        self.assertEqual(len(failures), 1)
        self.assertIn("4 and 4 blocks after their last common block", failures[0])

    def test_a_cut_last_line_cannot_turn_a_divergence_into_agreement(self):
        # the last extension of the scarab log cut short: left out, the tails would be 4 and 3 and agree
        lines = log_text(self.SCARAB_CHAIN).split("\n")
        cut = "\n".join(lines[:-2]) + "\n" + lines[-2][:25]
        _, failures = self.run_check(log_text(self.SCALUS_CHAIN), cut,
                                     {"scalus": tip_of(self.SCALUS_CHAIN), "scarab": tip_of(self.SCARAB_CHAIN)})
        self.assertEqual(len(failures), 1, failures)
        self.assertIn("the scarab follower's final chain cannot be rebuilt from its log: partialLine", failures[0])

    def test_a_log_that_ends_before_its_final_tip_fails(self):
        # every line whole, but the last extension missing: the chain does not end where the follower did
        _, failures = self.run_check(log_text(self.SCALUS_CHAIN), log_text(self.SCARAB_CHAIN[:-1]),
                                     {"scalus": tip_of(self.SCALUS_CHAIN), "scarab": tip_of(self.SCARAB_CHAIN)})
        self.assertEqual(len(failures), 1, failures)
        self.assertIn("the scarab follower's final chain cannot be rebuilt from its log: finalTip", failures[0])

    def test_a_follower_without_a_recorded_final_tip_fails(self):
        _, failures = self.run_check(log_text(self.COMMON), log_text(self.COMMON), {"scalus": tip_of(self.COMMON)})
        self.assertEqual(len(failures), 1, failures)
        self.assertIn("the scarab follower's final chain cannot be rebuilt from its log: noFinalTip", failures[0])


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


class StopFollowersTest(unittest.TestCase):
    """`follower.stop_followers`: the run's own stop, after which every follower's log is whole and its tip known."""
    A, B = {"slot": 5, "hash": "a"}, {"slot": 6, "hash": "b"}
    docker_status = 0

    def stop(self, tips, processes, pid=42, resumes=True, on_freeze=None):
        """(report, problems, signals, docker calls, root) of a stop whose followers' `/tip` readings are `tips`
        (site -> readings in order); `on_freeze` runs when the node is frozen."""
        from oracle import devnet as oracle_devnet
        signals, calls, readings = [], [], {site: iter(r) for site, r in tips.items()}

        def signal(name, pid_, sig):
            signals.append(sig)
            if sig == "STOP" and on_freeze is not None:
                on_freeze()
            return sig == "STOP" or resumes
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        root = pathlib.Path(d)
        with patch.object(oracle_devnet, "node_pid", return_value=pid), \
                patch.object(oracle_devnet, "signal_node", side_effect=signal), \
                patch.object(oracle_devnet, "follower_tip", side_effect=lambda name, site: next(readings[site], None)), \
                patch.object(follower, "docker", side_effect=lambda *a, **k: calls.append(a) or done(a, self.docker_status)), \
                patch.object(follower.time, "sleep"):
            report, problems = follower.stop_followers("n", root, processes)
        return report, problems, signals, calls, root

    def test_each_tip_is_final_once_two_readings_agree_and_is_recorded_once_its_follower_ended(self):
        processes = {SCALUS: FakeFollower(), SCARAB: FakeFollower()}
        report, problems, signals, calls, root = self.stop(
            {SCALUS: [self.A, self.A], SCARAB: [self.A, self.B, self.B]}, processes)
        self.assertEqual(problems, [])
        self.assertEqual(signals, ["STOP", "CONT"])
        self.assertEqual(calls, [("stop", "-t", "30", "n-scala-scalus", "n-scala-scarab")])
        stopped = {"premature": None, "endedBy": "stop", "endedAt": None, "exit": 143}
        self.assertEqual({s.key: r for s, r in report.items()},
                         {"scalus": {**stopped, "finalTip": self.A}, "scarab": {**stopped, "finalTip": self.B}})
        self.assertEqual(json.loads(SCARAB.final_tip(root).read_text()), self.B)
        self.assertEqual(json.loads((root / "follower-stop-scarab.json").read_text()), {**stopped, "finalTip": self.B})
        self.assertEqual(follower.stop_failures(report), {})

    def test_a_follower_that_had_ended_is_reported_and_not_stopped(self):
        ended = FakeFollower()
        ended.code = 1
        report, _, _, calls, root = self.stop({SCALUS: [self.A, self.A]}, {SCALUS: FakeFollower(), SCARAB: ended})
        self.assertEqual(report[SCARAB], {"premature": 1, "endedBy": "itself", "endedAt": "before the freeze",
                                          "finalTip": None, "exit": None})
        self.assertEqual(calls, [("stop", "-t", "30", "n-scala-scalus")])
        self.assertFalse(SCARAB.final_tip(root).exists())

    def test_a_follower_that_ends_while_frozen_ended_by_itself_and_is_not_stopped(self):
        ending = FakeFollower()
        report, _, _, calls, root = self.stop({SCALUS: [self.A, self.A], SCARAB: [self.A, self.A]},
                                              {SCALUS: FakeFollower(), SCARAB: ending}, on_freeze=lambda: setattr(
                                                  ending, "code", 0))
        self.assertEqual(report[SCARAB], {"premature": 0, "endedBy": "itself", "endedAt": "while frozen",
                                          "finalTip": None, "exit": None})
        self.assertEqual(calls, [("stop", "-t", "30", "n-scala-scalus")])
        self.assertFalse(SCARAB.final_tip(root).exists())
        self.assertEqual(follower.stop_failures(report)[SCARAB],
                         ["the follower ended by itself with 0 while frozen, before the run asked it to stop"])

    def test_a_stop_command_that_fails_is_a_problem(self):
        self.docker_status = 1
        _, problems, _, _, _ = self.stop({SCALUS: [self.A, self.A]}, {SCALUS: FakeFollower()})
        self.assertEqual(problems, ["docker stop of the followers returned 1: "])

    def test_an_end_on_the_stop_with_another_status_than_a_sigterms_fails(self):
        for status, more in ((1, ""), (0, ""), (137, " (killed after the stop's limit)")):
            with self.subTest(status=status):
                report, _, _, _, _ = self.stop({SCALUS: [self.A, self.A]}, {SCALUS: FakeFollower(stop_status=status)})
                self.assertEqual(follower.stop_failures(report), {SCALUS: [
                    f"the follower ended with {status} on the run's stop{more}, not 143, the end of a SIGTERM"]})

    def test_a_tip_that_never_settles_is_not_recorded(self):
        moving = [{"slot": n, "hash": str(n)} for n in range(20)]
        report, problems, _, _, root = self.stop({SCALUS: moving}, {SCALUS: FakeFollower()})
        self.assertIsNone(report[SCALUS]["finalTip"])
        self.assertFalse(SCALUS.final_tip(root).exists())
        self.assertEqual(problems, [])  # the missing tip fails where it is needed, the cross-check

    def test_without_the_node_to_freeze_the_followers_are_still_stopped_but_no_tip_is_recorded(self):
        report, problems, signals, calls, _ = self.stop({SCALUS: [self.A, self.A]}, {SCALUS: FakeFollower()},
                                                        pid=None)
        self.assertEqual((signals, problems), ([], []))
        self.assertEqual(calls, [("stop", "-t", "30", "n-scala-scalus")])
        self.assertIsNone(report[SCALUS]["finalTip"])

    def test_a_node_that_cannot_be_continued_is_a_problem(self):
        _, problems, signals, _, _ = self.stop({SCALUS: [self.A, self.A]}, {SCALUS: FakeFollower()}, resumes=False)
        self.assertEqual(signals, ["STOP", "CONT", "CONT", "CONT"])
        self.assertEqual(problems, ["reference node 1 (pid 42) could not be confirmed continued after SIGSTOP"])


ERA_EPOCHS = [("Byron", 0), ("Shelley", 1), ("Allegra", 2), ("Mary", 3), ("Alonzo", 4), ("Babbage", 5), ("Conway", 6)]
GREEN_TARGET = 7  # the Conway epoch a green mocked run reaches


def green_blocks(fork=0, rival_from=None):
    """(era, epoch, Block) of a chain through every era to Conway epoch `GREEN_TARGET`: two Byron blocks, one in
    each later era's first epoch, three in Conway. From block number `rival_from` on, the hashes are those of the
    rival chain `fork`."""
    plan = [("Byron", 0), ("Byron", 0)] + [(era, epoch) for era, epoch in ERA_EPOCHS[1:]] + \
        [("Conway", 6), ("Conway", GREEN_TARGET), ("Conway", GREEN_TARGET)]
    return [(era, epoch, Block(10 * n, h(n + (1000 * fork if rival_from is not None and n >= rival_from else 0)), n))
            for n, (era, epoch) in enumerate(plan)]


def green_log(vm=None, chain=None, extra=()):
    """A follower log that is green on its own: every era entered at its epoch, Byron epoch 0 validated, `chain`
    (`green_blocks()` by default) extended, then the events of `extra`, each line complete."""
    events = [{"event": "started"}, {"event": "connected", "intersection": {"origin": True}}]
    events += [{"event": "plutusVm", "vm": vm}] if vm else []
    previous = None
    for era, epoch, b in chain or green_blocks():
        block = {"slot": b.slot, "blockNo": b.block_no, "hash": b.hash}
        if era != previous:
            events.append({"event": "eraTransition", "from": previous, "to": era, "epoch": epoch, "slot": b.slot,
                           "block": block})
            previous = era
        events.append({"event": "extended", "era": era, "epoch": epoch, "block": block})
        if (era, b.block_no) == ("Byron", 1):
            events.append({"event": "epochValidated", "era": "Byron", "epoch": 0, "headers": 2, "boundary": 1,
                           "signatureVerified": 1, "offSchedule": 0, "transactions": 0})
    return "".join(json.dumps(e) + "\n" for e in [*events, *extra])


def done(command, status=0):
    """The completed `docker` command `command`, with `status`."""
    import subprocess
    return subprocess.CompletedProcess(command, status, "", "")


class FakeFollower:
    """A follower's `follow` process: running until it is waited for after its stop, then ended with `stop_status`
    (143, the end of a SIGTERM, by default). `on_stop` runs when it ends, as what a node logs while it shuts down."""

    def __init__(self, on_stop=None, stop_status=143):
        self.code, self.on_stop, self.stop_status = None, on_stop, stop_status

    def poll(self):
        return self.code

    @property
    def returncode(self):
        return self.code

    def wait(self, timeout=None):
        if self.code is None:
            if self.on_stop is not None:
                self.on_stop()
            self.code = self.stop_status
        return self.code


class GreenRunTest(unittest.TestCase):
    """`e2e()` driven through a mocked run that is green unless a test makes it otherwise: the cluster, the
    containers and the reference node's freeze are replaced, the followers' logs are written by the test."""

    def run_e2e(self, plutus_vm, logs=None, on_stop=None, exited=None, after_walk=None, oracle=None, stop_status=None):
        """(summary, exit status) of one mocked run. Each argument maps a follower key (None for a run's only
        follower) to: `logs` its log text, `after_walk` the events it logs once the era walk is over (while the run
        waits for its target), `exited` the status it ends with then, before the run stops it, and `on_stop` what it
        logs while it is stopped (events, or raw text), `stop_status` the status it ends with on the stop (143 by
        default). `oracle`, when given, is the (summary, failures) of the db-analyser comparison of an
        `--oracle` run, whose own stop of the follower is replaced too (`self.order` records the oracle's steps)."""
        logs, on_stop, exited, after_walk = logs or {}, on_stop or {}, exited or {}, after_walk or {}
        stop_status = stop_status or {}
        processes = {}
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d) / "run"

            def start(name, root_, site=SINGLE_FOLLOWER, plutus_vm=None, **kwargs):
                site.log(root).write_text(logs.get(site.key, green_log(plutus_vm)))
                extra = on_stop.get(site.key)
                processes[site] = FakeFollower(None if extra is None else lambda: self.append(site.log(root), extra),
                                               stop_status.get(site.key, 143))
                return processes[site]

            def up_eras(args, on_nodes_up, on_pools_ready, on_tick):
                (root / "genesis").mkdir(parents=True)
                (root / "eras.json").write_text(json.dumps({"eras": [{"era": e.lower(), "epoch": n}
                                                                     for e, n in ERA_EPOCHS]}))
                (root / "genesis" / "configuration.yaml").write_text(json.dumps(
                    {f"Test{e}HardForkAtEpoch": n for e, n in ERA_EPOCHS[1:]}))
                (root / "genesis" / "shelley-genesis.json").write_text(json.dumps({"securityParam": 3}))
                on_nodes_up(root)
                on_tick({})
                for site, process in processes.items():
                    self.append(site.log(root), after_walk.get(site.key, []))
                    process.code = exited.get(site.key)

            def tip(name, site=SINGLE_FOLLOWER):
                extended = [json.loads(l) for l in site.log(root).read_text().splitlines()
                            if l.startswith('{"event": "extended"')]
                return {k: extended[-1]["block"][k] for k in ("slot", "hash")} if extended else None
            args = types.SimpleNamespace(name="n", plutus_vm=plutus_vm, oracle=oracle is not None, scala_cpus=2,
                                         no_load=True,
                                         no_compare=True, keep=False, timeout=60, until_epoch=GREEN_TARGET,
                                         min_comparisons=3)
            from oracle import devnet as oracle_devnet
            self.docker, self.order = [], []

            def oracle_stop(name, root_):
                self.order.append("stop")

            def oracle_run(name, root_, log):
                self.order.append(("run", SINGLE_FOLLOWER.log(root).read_text().count('"event": "error"')))
                return json.loads(json.dumps(oracle[0])), list(oracle[1])
            with patch.object(e2e_scenario, "data_root", return_value=root), \
                    patch.object(e2e_scenario, "up_eras", side_effect=up_eras), \
                    patch.object(e2e_scenario, "start_follower", side_effect=start), \
                    patch.object(e2e_scenario, "in_ref", return_value=types.SimpleNamespace(stdout="")), \
                    patch.object(e2e_scenario, "keep_native_evidence", return_value={"kept": [], "notKept": {}}), \
                    patch.object(e2e_scenario, "docker", side_effect=lambda *a, **k: self.docker.append(a)), \
                    patch.object(follower, "docker", create=True,
                                 side_effect=lambda *a, **k: self.docker.append(a) or done(a)), \
                    patch.object(oracle_devnet, "node_pid", return_value=42), \
                    patch.object(oracle_devnet, "signal_node", return_value=True), \
                    patch.object(oracle_devnet, "follower_tip", side_effect=tip), \
                    patch.object(oracle_devnet, "stop_follower", side_effect=oracle_stop), \
                    patch.object(oracle_devnet, "run", side_effect=oracle_run), \
                    patch.object(e2e_scenario.time, "sleep"), \
                    contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as ended:
                e2e_scenario.e2e(args)
            summary = json.loads((root / "e2e-summary.json").read_text())
        return summary, ended.exception.code

    @staticmethod
    def append(path, events):
        """Append `events` to a log, each a complete line, or `events` as it is when it is text."""
        with path.open("a") as out:
            out.write(events if isinstance(events, str) else "".join(json.dumps(e) + "\n" for e in events))

    def test_the_mocked_run_is_green_with_one_follower_and_with_two(self):
        for plutus_vm in (None, ["scalus", "scarab"]):
            with self.subTest(plutus_vm=plutus_vm):
                summary, code = self.run_e2e(plutus_vm)
                self.assertEqual((summary["failures"], summary["passed"], code), ([], True, 0))

    def test_the_only_follower_keeps_the_summary_layout(self):
        summary, _ = self.run_e2e(None)
        for key in ("transitions", "blocksPerEra", "cpuStarvationWarnings", "ledgerComparison", "followerExit"):
            self.assertIn(key, summary)
        for key in ("followers", "crossCheck", "plutusVm", "reachedTarget", "followerStop"):
            self.assertNotIn(key, summary)
        self.assertEqual(summary["followerExit"], None, "the stop the run asked for is no exit of the follower's")
        self.assertIn(("stop", "-t", "30", "n-scala"), self.docker)
        self.assertEqual(self.docker[-1], ("rm", "-f", "n-ref"))

    def test_two_followers_are_judged_each_and_against_each_other(self):
        summary, _ = self.run_e2e(["scalus", "scarab"])
        self.assertEqual(sorted(summary["followers"]), ["scalus", "scarab"])
        for key, own in summary["followers"].items():
            self.assertEqual(own["plutusVm"], [key])
            self.assertTrue(own["reachedTarget"], key)
            self.assertEqual(own["followerStop"]["exit"], 143)
            for field in ("transitions", "cpuStarvationWarnings", "ledgerComparison"):
                self.assertIn(field, own, f"{key} lacks {field}")
        self.assertNotIn("transitions", summary)
        cross = summary["crossCheck"]
        self.assertEqual(cross["failures"], [])
        self.assertEqual(cross["finalChain"]["pairs"][0]["beyondCommon"], {"scalus": 0, "scarab": 0})
        self.assertIn(("stop", "-t", "30", "n-scala-scalus", "n-scala-scarab"), self.docker)
        self.assertEqual(self.docker[-3:], [("rm", "-f", "n-scala-scalus"), ("rm", "-f", "n-scala-scarab"),
                                            ("rm", "-f", "n-ref")])

    def test_either_follower_failing_alone_fails_the_run(self):
        error = {"event": "error", "kind": "ValidationError", "message": "bad block"}
        for key in ("scalus", "scarab"):
            with self.subTest(failing=key, when="during the era walk"):
                summary, code = self.run_e2e(["scalus", "scarab"], logs={key: green_log(key, extra=[error])})
                self.assertEqual(code, 1)
                self.assertEqual(summary["failures"], [f"{key} follower error: bad block"])
            with self.subTest(failing=key, when="while the run waits for its target"):
                summary, code = self.run_e2e(["scalus", "scarab"], after_walk={key: [error]})
                self.assertEqual(code, 1)
                self.assertEqual(summary["failures"], [f"{key} follower: 1 validation errors: bad block",
                                                       f"stopped without reaching Conway epoch 7: error: {key}"])

    def test_an_error_logged_after_the_wait_fails_the_run(self):
        # what a follower logs once the wait for the target is over, up to its stop, is judged with the rest
        late = [{"event": "error", "kind": "LedgerFailure", "message": "late"}]
        for plutus_vm, key, prefix in ((None, None, ""), (["scalus", "scarab"], "scarab", "scarab follower: ")):
            with self.subTest(plutus_vm=plutus_vm):
                summary, code = self.run_e2e(plutus_vm, on_stop={key: late})
                self.assertEqual(code, 1)
                self.assertEqual(summary["failures"], [f"{prefix}1 fatal errors: late"])

    def test_an_oracle_run_stops_its_follower_with_the_oracle_and_judges_its_whole_log_first(self):
        oracle = ({"digests": 3, "compared": 3, "results": [{"slot": 1}]}, [])
        summary, code = self.run_e2e(None, oracle=oracle)
        self.assertEqual((summary["failures"], code), ([], 0))
        self.assertEqual(self.order, ["stop", ("run", 0)])
        self.assertEqual(summary["oracle"], {"digests": 3, "compared": 3})  # the rows stay in oracle-summary.json
        self.assertFalse(any(c[0] == "stop" for c in self.docker), "the oracle's own stop, not the run's")
        late = [{"event": "error", "kind": "LedgerFailure", "message": "late"}]
        summary, code = self.run_e2e(None, oracle=({"digests": 3}, ["oracle: 1 digest differs"]), on_stop={None: late})
        self.assertEqual(code, 1)
        self.assertEqual(summary["failures"], ["1 fatal errors: late", "oracle: 1 digest differs"])

    def test_an_end_on_the_stop_with_another_status_fails_the_run(self):
        for plutus_vm, statuses, expected in (
                (["scalus", "scarab"], {"scalus": 1, "scarab": 1},
                 ["scalus follower: the follower ended with 1 on the run's stop, not 143, the end of a SIGTERM",
                  "scarab follower: the follower ended with 1 on the run's stop, not 143, the end of a SIGTERM"]),
                (None, {None: 137}, ["the follower ended with 137 on the run's stop (killed after the stop's limit), "
                                     "not 143, the end of a SIGTERM"]),
                (None, {None: 0}, ["the follower ended with 0 on the run's stop, not 143, the end of a SIGTERM"])):
            with self.subTest(plutus_vm=plutus_vm, statuses=statuses):
                summary, code = self.run_e2e(plutus_vm, stop_status=statuses)
                self.assertEqual((summary["failures"], code), (expected, 1))

    def test_a_last_line_cut_while_stopping_fails_and_is_still_judged(self):
        cut = json.dumps({"event": "error", "kind": "LedgerFailure", "message": "at the end"})  # no newline
        for plutus_vm, key, prefix in ((None, None, ""), (["scalus", "scarab"], "scarab", "scarab follower: ")):
            with self.subTest(plutus_vm=plutus_vm):
                summary, code = self.run_e2e(plutus_vm, on_stop={key: cut})
                self.assertEqual(code, 1)
                self.assertEqual(summary["failures"][:2], [
                    f"{prefix}1 fatal errors: at the end",
                    f"{prefix}the follower's log ends in a line without its newline: {cut}"])

    def test_a_follower_that_ended_before_the_stop_is_a_premature_exit(self):
        summary, code = self.run_e2e(["scalus", "scarab"], exited={"scalus": 1})
        self.assertEqual(code, 1)
        # an ended follower has no tip to record: its chain cannot be checked to its end, which fails explicitly
        self.assertEqual(summary["failures"], [
            "scalus follower: the follower exited with 1",
            "stopped without reaching Conway epoch 7: follower exited: scalus",
            "the scalus follower's final chain cannot be rebuilt from its log: noFinalTip at line 0: no final tip of "
            "the follower was recorded"])
        self.assertEqual(summary["followers"]["scalus"]["followerExit"], 1)

    # the keys of a single-follower run's summary before several followers existed (this run's: no load, no
    # comparisons), pinned so that no change for several followers adds one
    SINGLE_SUMMARY = {"transitions", "expectedTransitions", "blocksPerEra", "blocksApplied", "lastEpoch",
                      "validationErrors", "fatalErrors", "unparsedOutput", "rollbacks", "reconnects", "followerExit",
                      "ledgerComparison", "blockedThreads", "endedThreads", "stoppedBecause", "cpuStarvationWarnings",
                      "targetConwayEpoch", "failures", "passed"}

    def test_one_explicit_evaluator_is_checked_as_each_of_several_is(self):
        # with one `--plutus-vm` the run's only follower must log that evaluator at its start, as each of several
        # must: a wiring slip that left it on another would otherwise pass as a run of the one asked for. Its summary
        # gains `plutusVm` only; a run without the flag is not asked about the evaluator
        plain, _ = self.run_e2e(None)
        self.assertEqual(set(plain), self.SINGLE_SUMMARY)
        for vm, logged, failures in (
                ("scalus", "scalus", []),
                ("scarab", "scarab", []),
                ("scalus", None, ["the follower logged the Plutus evaluators [] at its start, not ['scalus']"]),
                ("scarab", "scalus",
                 ["the follower logged the Plutus evaluators ['scalus'] at its start, not ['scarab']"])):
            with self.subTest(vm=vm, logged=logged):
                summary, code = self.run_e2e([vm], logs={None: green_log(logged)})
                self.assertEqual((summary["failures"], code), (failures, 1 if failures else 0))
                # a failed run also keeps the reference's own evidence
                kept = {"referenceEvidence"} if failures else set()
                self.assertEqual(set(summary), self.SINGLE_SUMMARY | {"plutusVm"} | kept)
                self.assertEqual(summary["plutusVm"], [logged] if logged else [])

    def test_with_two_followers_each_must_have_run_the_evaluator_it_was_given(self):
        summary, code = self.run_e2e(["scalus", "scarab"], logs={"scarab": green_log("scalus")})
        self.assertEqual(code, 1)
        self.assertEqual(summary["failures"],
                         ["scarab follower: the follower logged the Plutus evaluators ['scalus'] at its start, "
                          "not ['scarab']"])


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
