# SPDX-License-Identifier: Apache-2.0
"""The Scala follower of an `e2e` run: its names and ports, the comparisons with the reference (a judgment stands
once made), the run's own stop of it, the judgment of its whole log, `follow --reference-build` reaching the node's
command line, and the removed `--plutus-vm` and `--instance`.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import contextlib
import io
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cli, cluster, compare, eraview, follower, util  # noqa: E402
from devnet.scenarios import e2e as e2e_scenario  # noqa: E402
from devnet.util import SINGLE_FOLLOWER  # noqa: E402
from oracle.finality import Block  # noqa: E402


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
        self.assertEqual((site.final_tip(root), site.stop_record(root)),
                         (root / "follower-tip.json", root / "follower-stop.json"))

    def test_the_port_reading_sees_a_jvm_listener_in_the_ipv6_table_after_the_ipv4_one(self):
        # a JVM's 127.0.0.1 listener is an IPv4-mapped IPv6 socket, listed in tcp6
        tcp = ("  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
               "   0: 0100007F:9C4B 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1 1\n")
        tcp6 = ("  sl  local_address                         remote_address                        st tx_queue\n"
                "   0: 0000000000000000FFFF00000100007F:1BA0 00000000000000000000000000000000:0000 0A 00000000:00000000"
                " 00:00000000 00000000  1000        0 155865501 1\n")
        self.assertEqual(cluster.listening_ports(tcp + tcp6), {0x9C4B, 0x1BA0})

    def test_down_removes_the_follower_and_the_reference_containers(self):
        removed = []
        with tempfile.TemporaryDirectory() as d, \
                patch.object(cluster, "data_root", return_value=pathlib.Path(d) / "run"), \
                patch.object(cluster, "docker", side_effect=lambda *a, **k: removed.append(a)):
            cluster.down(types.SimpleNamespace(name="n"), quiet=True)
        self.assertEqual([a[2] for a in removed],
                         ["n-scala", "n-cli", "n-ref", "n-extract-1", "n-extract-2", "n-extract-3"])


class FollowerProcessTest(unittest.TestCase):
    def follow(self, argv=None):
        """(program arguments, scala_container keywords, frozen directory) of `follow`: called directly, or, with
        `argv` (the arguments after `devnet.py --name n`), parsed by `devnet.py` as a run started by another run
        (`cli.RUN_MARKER`), so neither the free-space check nor the size line runs."""
        seen = {}

        def container(args, root, classpath, main, program, mounts=(), name=None, classes=None):
            seen.update(program=program, mounts=mounts, name=name, classes=classes)
            return ["run"]
        process = Mock(stdout=[], wait=Mock(return_value=0))
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d) / "n"
            (root / "genesis").mkdir(parents=True)
            (root / "ports.json").write_text(json.dumps({"1": 3001}))
            args = types.SimpleNamespace(name="n", node=1, seconds=None, local_socket=True)
            with patch.object(follower, "data_root", return_value=root), \
                    patch.object(follower, "freeze_classes",
                                 side_effect=lambda r, previous, frozen: seen.update(frozen=frozen) or "cp"), \
                    patch.object(follower, "scala_container", side_effect=container), \
                    patch.object(follower.subprocess, "Popen", return_value=process), \
                    patch.dict(os.environ, {cli.RUN_MARKER: "n"}), \
                    patch.object(sys, "argv", ["devnet.py", "--name", "n", *(argv or ())]), \
                    self.assertRaises(SystemExit):
                if argv is None:
                    follower.follow(args)
                else:
                    cli.main()
            return seen, root

    def test_the_follower_runs_with_its_ports_socket_stall_and_classes(self):
        seen, root = self.follow()
        program = seen["program"]
        self.assertEqual(program[program.index("--query-port") + 1], "7071")
        self.assertEqual(program[program.index("--metrics-listen") + 1], "127.0.0.1:12798")
        self.assertEqual(seen["name"], "n-scala")
        self.assertEqual(seen["classes"], root / "classes")
        self.assertEqual(seen["frozen"], root / "classes")
        self.assertIn(f"{root / 'scala-sock'}:/sock", seen["mounts"])
        self.assertIn(f"{root / 'stall'}:/stall", seen["mounts"])
        self.assertNotIn("--plutus-vm", program, "the node has one Plutus evaluator, its own")

    def test_the_reference_build_reaches_the_nodes_command_line_as_given(self):
        # passed on as the node takes it, the value unchecked here: the node parses it (`ReferenceBuildFlag.parse`)
        for build in ("leios-b004fe0c", "11.1", "not-a-build"):
            seen, _ = self.follow(["follow", "--reference-build", build])
            program = seen["program"]
            self.assertEqual(program[:1], ["follow"])
            self.assertEqual(program.count("--reference-build"), 1)
            self.assertEqual(program[program.index("--reference-build") + 1], build)

    def test_without_the_flag_the_node_runs_its_own_default_build(self):
        for argv in (None, ["follow"]):
            seen, _ = self.follow(argv)
            self.assertNotIn("--reference-build", seen["program"])

    def test_the_child_cli_names_no_evaluator_and_no_instance(self):
        with patch.object(follower.subprocess, "Popen") as launch:
            follower.start_follower("audit", pathlib.Path("/tmp/audit"), local_socket=True)
        self.assertEqual(launch.call_args.args[0], [
            sys.executable, str(follower.ENTRY), "--name", "audit", "follow", "--local-socket"])

    def test_a_problem_is_the_followers_error_output_or_exit(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            running = Mock(poll=Mock(return_value=None))
            SINGLE_FOLLOWER.log(root).write_text(json.dumps({"event": "started"}) + "\n")
            self.assertIsNone(follower.follower_problem(root, running))
            self.assertEqual(follower.follower_problem(root, Mock(poll=Mock(return_value=1), returncode=1)),
                             "follower exited with 1")
            SINGLE_FOLLOWER.log(root).write_text(json.dumps({"event": "error", "message": "boom"}) + "\n")
            self.assertEqual(follower.follower_problem(root, running), "follower error: boom")


class RemovedOptionsTest(unittest.TestCase):
    def test_the_cli_refuses_an_evaluator_or_an_instance(self):
        # Scarab's Plutus evaluator is its own, the only one: no scenario takes `--plutus-vm`, nor `follow --instance`
        for arguments in (["e2e", "--plutus-vm", "scarab"], ["follow", "--plutus-vm", "scarab"],
                          ["follow", "--instance", "scarab"], ["pool", "--plutus-vm", "scarab", "--plutus-load"]):
            with self.subTest(arguments=arguments):
                done = subprocess.run([sys.executable, str(follower.ENTRY), "--name", "n", *arguments],
                                      capture_output=True, text=True, timeout=60, check=False)
                self.assertEqual(done.returncode, 2, done.stderr)
                self.assertIn(f"unrecognized arguments: {' '.join(arguments[1:3])}", done.stderr)


class SameBlockComparisonTest(unittest.TestCase):
    """Pre-Conway: the follower is compared with the one reference block an attempt reads, a judgment stands once
    made, and a follower that did not answer is asked again (at the next attempt's block)."""
    STATE = {"epoch": 7, "utxo": {}, "rewardUpdate": None, "params": {}, "ppup": {},
             "mir": {"reserves": {}, "treasury": {}, "deltaReserves": 0, "deltaTreasury": 0}}

    def compare(self, states, attempts=1):
        """(sample, the follower's reads by slot) of one pre-Conway comparison: attempt `n` reads the reference at
        slot 123 + n, and `states` maps a slot to what the follower answers there (absent for no answer)."""
        reads, slots = [], iter(range(123, 123 + attempts))
        session = Mock()
        context = Mock(__enter__=Mock(return_value=session), __exit__=Mock(return_value=False))

        def reference(*args):
            slot = next(slots)
            return {"slot": slot, "hash": f"block-{slot}"}, json.loads(json.dumps(self.STATE))

        def state(name, tip, site=SINGLE_FOLLOWER):
            reads.append(tip["slot"])
            answer = states.get(tip["slot"])
            return None if answer is None else json.loads(json.dumps(answer))
        with patch.object(compare, "protocol_parameters", return_value={}), \
                patch.object(compare, "lsq", return_value=context), \
                patch.object(eraview, "reference_pre_conway", side_effect=reference), \
                patch.object(compare, "scala_state", side_effect=state), \
                patch.object(compare.time, "sleep"):
            samples = compare.compare_pre_conway_followers("n", "alonzo", (SINGLE_FOLLOWER,), attempts=attempts)
        self.assertEqual(len(samples), 1)
        return samples[0], reads

    def test_the_follower_is_asked_at_the_block_the_reference_was_read_at(self):
        sample, reads = self.compare({123: self.STATE})
        self.assertEqual(reads, [123])
        self.assertTrue(sample.result["matched"], sample.result)
        self.assertEqual(sample.result["tip"]["slot"], 123)

    def test_a_follower_that_differs_is_a_mismatch(self):
        sample, _ = self.compare({123: dict(self.STATE, epoch=8)})
        self.assertFalse(sample.result["matched"])

    def test_a_judged_follower_is_not_asked_again(self):
        sample, reads = self.compare({123: self.STATE, 124: self.STATE}, attempts=2)
        self.assertEqual(reads, [123], "a judged follower is not asked again")
        self.assertTrue(sample.result["matched"])

    def test_a_follower_that_never_reaches_the_block_has_an_error(self):
        sample, reads = self.compare({}, attempts=2)
        # each attempt asks the follower a few times while it has not reached the block (`awaited_state`)
        self.assertEqual(sorted(set(reads)), [123, 124], "an attempt the follower did not answer is followed by another")
        self.assertEqual(sample, compare.Sample({"error": "no stable comparison within the attempts"}))

    def test_a_difference_is_never_retried_away(self):
        # the follower answers wrongly at the first block; at the next it would answer rightly: the difference stands
        sample, reads = self.compare({123: dict(self.STATE, epoch=8), 124: self.STATE}, attempts=2)
        self.assertFalse(sample.result["matched"], sample.result)
        self.assertEqual(sample.result["tip"]["slot"], 123)
        self.assertEqual(reads, [123])

    def test_a_follower_that_answers_only_at_a_later_block_is_judged_there(self):
        sample, reads = self.compare({124: self.STATE}, attempts=2)
        self.assertEqual(sorted(set(reads)), [123, 124])
        self.assertTrue(sample.result["matched"])
        self.assertEqual(sample.result["tip"]["slot"], 124)


class ConwayComparisonTest(unittest.TestCase):
    """Conway (`compare_followers_detailed`): the reference read through its query bundle and whole UTxO, the
    follower judged against that block as in the earlier eras."""
    NONCES = {k: "00" for k in ("epochNonce", "evolvingNonce", "candidateNonce", "labNonce", "lastEpochBlockNonce",
                                "oCertCounters")}
    VIEW = {"epoch": 9, "dreps": {}, "blocksCurrent": {}, "blocksBefore": {}, "stakeDistrib": {"total": 1, "pools": {}}}
    STATE = {**VIEW, "utxo": {}, "gov": {}, "protocolState": NONCES, "accounts": {}}

    def compare(self, states, attempts=1):
        """((sample, failures), the follower's reads by slot) of one Conway comparison: attempt `n` reads the reference
        at slot 400 + n, and `states` maps a slot to the follower's answer there."""
        reads, slots = [], iter(range(400, 400 + attempts))

        def bundle(name):
            slot = next(slots)
            tip = {"slot": slot, "block": slot // 2, "epoch": 9, "hash": f"block-{slot}"}
            return {"tip": tip, "ls": {}, "utxo": {}, "protocol": self.NONCES, "gov": {}, "committee": {},
                    "drep": {}}, None

        def state(name, tip, site=SINGLE_FOLLOWER):
            reads.append(tip["slot"])
            answer = states.get(tip["slot"])
            return None if answer is None else json.loads(json.dumps(answer))
        with patch.object(compare, "query_bundle", side_effect=bundle), \
                patch.object(compare, "reference_utxo", return_value=({}, [])), \
                patch.object(compare, "utxo_sources_agree", return_value=True), \
                patch.object(compare, "reference_view", side_effect=lambda ls, utxo: json.loads(json.dumps(self.VIEW))), \
                patch.object(compare.govview, "reference_gov", return_value={}), \
                patch.object(compare.govview, "scala_gov", return_value={"prevGovActionIds": []}), \
                patch.object(compare, "scala_state", side_effect=state), \
                patch.object(compare.time, "sleep"):
            samples, failures = compare.compare_followers_detailed("n", (SINGLE_FOLLOWER,), attempts=attempts,
                                                                   scala_wait=1)
        self.assertEqual(len(samples), 1)
        return (samples[0], failures), reads

    def test_the_follower_is_judged_against_the_block_the_bundle_was_read_at(self):
        (sample, failures), reads = self.compare({400: self.STATE})
        self.assertEqual(failures, [])
        self.assertEqual(reads, [400])
        self.assertTrue(sample.result["matched"], sample.result)
        self.assertEqual(sample.result["tip"]["slot"], 400)
        self.assertEqual(sorted(sample.result["scalaOnlyKeys"]), ["accounts"])

    def test_a_difference_is_never_retried_away(self):
        (sample, _), reads = self.compare({400: dict(self.STATE, epoch=8), 401: self.STATE}, attempts=2)
        self.assertFalse(sample.result["matched"], sample.result)
        self.assertEqual(sample.result["differences"][0]["path"], "/epoch")
        self.assertEqual(sample.result["tip"]["slot"], 400)
        self.assertEqual(reads, [400])

    def test_a_follower_that_never_reaches_a_block_has_an_error(self):
        (sample, _), reads = self.compare({}, attempts=2)
        self.assertEqual(reads, [400, 401])
        self.assertEqual(sample.result["error"], "scala node never reached the reference tip")
        self.assertEqual(sample.result["tip"]["slot"], 401)

    def test_a_follower_that_answers_only_at_a_later_block_is_judged_there(self):
        (sample, _), _ = self.compare({401: self.STATE}, attempts=2)
        self.assertTrue(sample.result["matched"])
        self.assertEqual(sample.result["tip"]["slot"], 401)


class CompareEraTest(unittest.TestCase):
    def test_compare_era_records_the_follower_with_one_reading_and_then_its_whole_state(self):
        recorded = []
        tally = types.SimpleNamespace(site=SINGLE_FOLLOWER, record=lambda era, label, r: recorded.append(r),
                                      record_whole=lambda era, label, r: recorded.append(("whole", r)))
        whole = []
        with patch.object(compare, "compare_followers_detailed",
                          return_value=([compare.Sample({"matched": True})], None)) as reading, \
                patch.object(compare, "whole_state",
                             side_effect=lambda name, era, site, reselections: whole.append((site, reselections))
                             or {}):
            compare.compare_era("n", "conway", "mid", [tally])
        self.assertEqual(reading.call_count, 1)
        self.assertEqual(reading.call_args.args[1], [SINGLE_FOLLOWER])
        self.assertEqual(recorded, [{"matched": True}, ("whole", {})])
        # the follower's same-point state at its own tip, a tip a fork invalidated sampled again within the bound
        self.assertEqual(whole, [(SINGLE_FOLLOWER, compare.WHOLE_STATE_RESELECTIONS)])

    def test_the_tally_writes_compare_jsonl(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(compare.Comparisons(pathlib.Path(d)).file, pathlib.Path(d) / "compare.jsonl")

    def test_the_whole_state_is_asked_on_the_followers_socket(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            with patch.object(compare.util, "data_root", return_value=root):
                self.assertEqual(compare.whole_state("n", "conway"),
                                 {"skipped": "the Scala node has no node-to-client socket"})


class FollowerChainTest(unittest.TestCase):
    """`oracle.devnet.follower_chain`: the follower's final chain, rebuilt from its whole log and checked against the
    final tip the run's stop recorded; a log that is cut or does not end there cannot pass for the chain."""
    CHAIN = blocks(range(10))

    def rebuild(self, log, tip=None):
        from oracle import devnet as oracle_devnet
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            SINGLE_FOLLOWER.log(root).write_text(log)
            if tip is not None:
                SINGLE_FOLLOWER.final_tip(root).write_text(json.dumps(tip))  # as the run's stop records it
            return oracle_devnet.follower_chain(root)

    def assert_refused(self, log, tip, reason):
        from oracle import finality
        with self.assertRaises(finality.ChainRebuildError) as refused:
            self.rebuild(log, tip)
        self.assertEqual(refused.exception.cause.value, reason, str(refused.exception))

    def test_a_whole_log_that_ends_at_its_final_tip_is_the_chain(self):
        chain = self.rebuild(log_text(self.CHAIN), tip_of(self.CHAIN))
        self.assertEqual([b.hash for b in chain.blocks], [b.hash for b in self.CHAIN])

    def test_a_cut_last_line_is_refused(self):
        lines = log_text(self.CHAIN).split("\n")
        self.assert_refused("\n".join(lines[:-2]) + "\n" + lines[-2][:25], tip_of(self.CHAIN), "partialLine")

    def test_a_log_that_ends_before_its_final_tip_is_refused(self):
        self.assert_refused(log_text(self.CHAIN[:-1]), tip_of(self.CHAIN), "finalTip")

    def test_a_log_without_a_recorded_final_tip_is_refused(self):
        self.assert_refused(log_text(self.CHAIN), None, "noFinalTip")

    def test_a_log_that_is_not_one_chain_is_refused(self):
        broken = blocks([0, 1, 3])  # block 2 is missing
        self.assert_refused(log_text(broken), tip_of(broken), "discontinuous")


class StopFollowersTest(unittest.TestCase):
    """`follower.stop_followers`: the run's own stop, after which the follower's log is whole and its tip known."""
    A, B = {"slot": 5, "hash": "a"}, {"slot": 6, "hash": "b"}
    docker_status = 0

    def stop(self, tips, process, pid=42, resumes=True, on_freeze=None):
        """(the follower's record, problems, signals, docker calls, root) of a stop whose `/tip` readings of the
        follower are `tips` (in order); `on_freeze` runs when the node is frozen."""
        from oracle import devnet as oracle_devnet
        signals, calls, readings = [], [], iter(tips)

        def signal(name, pid_, sig):
            signals.append(sig)
            if sig == "STOP" and on_freeze is not None:
                on_freeze()
            return sig == "STOP" or resumes

        def tip(name, site):
            self.assertIs(site, SINGLE_FOLLOWER)
            return next(readings, None)
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        root = pathlib.Path(d)
        with patch.object(oracle_devnet, "node_pid", return_value=pid), \
                patch.object(oracle_devnet, "signal_node", side_effect=signal), \
                patch.object(oracle_devnet, "follower_tip", side_effect=tip), \
                patch.object(follower, "docker", side_effect=lambda *a, **k: calls.append(a) or done(a, self.docker_status)), \
                patch.object(follower.time, "sleep"):
            report, problems = follower.stop_followers("n", root, {SINGLE_FOLLOWER: process})
        self.assertEqual(list(report), [SINGLE_FOLLOWER])
        self.report = report
        return report[SINGLE_FOLLOWER], problems, signals, calls, root

    def test_the_tip_is_final_once_two_readings_agree_and_is_recorded_once_the_follower_ended(self):
        record, problems, signals, calls, root = self.stop([self.A, self.B, self.B], FakeFollower())
        self.assertEqual(problems, [])
        self.assertEqual(signals, ["STOP", "CONT"])
        self.assertEqual(calls, [("stop", "-t", "30", "n-scala")])
        stopped = {"premature": None, "endedBy": "stop", "endedAt": None, "exit": 143}
        self.assertEqual(record, {**stopped, "finalTip": self.B})
        self.assertEqual(json.loads(SINGLE_FOLLOWER.final_tip(root).read_text()), self.B)
        self.assertEqual(json.loads(SINGLE_FOLLOWER.stop_record(root).read_text()), {**stopped, "finalTip": self.B})
        self.assertEqual(follower.stop_failures(self.report), {})

    def test_a_follower_that_had_ended_is_reported_and_not_stopped(self):
        ended = FakeFollower()
        ended.code = 1
        record, _, _, calls, root = self.stop([self.A, self.A], ended)
        self.assertEqual(record, {"premature": 1, "endedBy": "itself", "endedAt": "before the freeze",
                                  "finalTip": None, "exit": None})
        self.assertEqual(calls, [])
        self.assertFalse(SINGLE_FOLLOWER.final_tip(root).exists())
        self.assertEqual(follower.stop_failures(self.report), {}, "an exit with 1 is the follower's own failure")

    def test_a_follower_that_ends_while_frozen_ended_by_itself_and_is_not_stopped(self):
        ending = FakeFollower()
        record, _, _, calls, root = self.stop([self.A, self.A], ending, on_freeze=lambda: setattr(ending, "code", 0))
        self.assertEqual(record, {"premature": 0, "endedBy": "itself", "endedAt": "while frozen",
                                  "finalTip": None, "exit": None})
        self.assertEqual(calls, [])
        self.assertFalse(SINGLE_FOLLOWER.final_tip(root).exists())
        self.assertEqual(follower.stop_failures(self.report)[SINGLE_FOLLOWER],
                         ["the follower ended by itself with 0 while frozen, before the run asked it to stop"])

    def test_a_stop_command_that_fails_is_a_problem(self):
        self.docker_status = 1
        _, problems, _, _, _ = self.stop([self.A, self.A], FakeFollower())
        self.assertEqual(problems, ["docker stop of the followers returned 1: "])

    def test_an_end_on_the_stop_with_another_status_than_a_sigterms_fails(self):
        for status, more in ((1, ""), (0, ""), (137, " (killed after the stop's limit)")):
            with self.subTest(status=status):
                self.stop([self.A, self.A], FakeFollower(stop_status=status))
                self.assertEqual(follower.stop_failures(self.report), {SINGLE_FOLLOWER: [
                    f"the follower ended with {status} on the run's stop{more}, not 143, the end of a SIGTERM"]})

    def test_a_tip_that_never_settles_is_not_recorded(self):
        moving = [{"slot": n, "hash": str(n)} for n in range(20)]
        record, problems, _, _, root = self.stop(moving, FakeFollower())
        self.assertIsNone(record["finalTip"])
        self.assertFalse(SINGLE_FOLLOWER.final_tip(root).exists())
        self.assertEqual(problems, [])  # a missing tip fails where a recorded one is needed (`follower_chain`)

    def test_without_the_node_to_freeze_the_follower_is_still_stopped_but_no_tip_is_recorded(self):
        record, problems, signals, calls, _ = self.stop([self.A, self.A], FakeFollower(), pid=None)
        self.assertEqual((signals, problems), ([], []))
        self.assertEqual(calls, [("stop", "-t", "30", "n-scala")])
        self.assertIsNone(record["finalTip"])

    def test_a_node_that_cannot_be_continued_is_a_problem(self):
        _, problems, signals, _, _ = self.stop([self.A, self.A], FakeFollower(), resumes=False)
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


def green_log(chain=None, extra=()):
    """A follower log that is green on its own: every era entered at its epoch, Byron epoch 0 validated, `chain`
    (`green_blocks()` by default) extended, then the events of `extra`, each line complete."""
    events = [{"event": "started"}, {"event": "connected", "intersection": {"origin": True}}]
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
    container and the reference node's freeze are replaced, the follower's log is written by the test."""

    def run_e2e(self, log=None, on_stop=None, exited=None, after_walk=None, oracle=None, stop_status=143):
        """(summary, exit status) of one mocked run. `log` is the follower's log text, `after_walk` the events it logs
        once the era walk is over (while the run waits for its target), `exited` the status it ends with then, before
        the run stops it, `on_stop` what it logs while it is stopped (events, or raw text) and `stop_status` the
        status it ends with on the stop. `oracle`, when given, is the (summary, failures) of the db-analyser
        comparison of an `--oracle` run, whose own stop of the follower is replaced too (`self.order` records the
        oracle's steps)."""
        processes = {}
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d) / "run"
            site = SINGLE_FOLLOWER

            def start(name, root_, **kwargs):
                self.assertNotIn("plutus_vm", kwargs)
                site.log(root).write_text(log if log is not None else green_log())
                processes[site] = FakeFollower(None if on_stop is None else lambda: self.append(site.log(root), on_stop),
                                               stop_status)
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
                self.append(site.log(root), after_walk or [])
                processes[site].code = exited

            def tip(name, site_=SINGLE_FOLLOWER):
                extended = [json.loads(l) for l in site_.log(root).read_text().splitlines()
                            if l.startswith('{"event": "extended"')]
                return {k: extended[-1]["block"][k] for k in ("slot", "hash")} if extended else None
            args = types.SimpleNamespace(name="n", oracle=oracle is not None, scala_cpus=2, no_load=True,
                                         no_compare=True, keep=False, timeout=60, until_epoch=GREEN_TARGET,
                                         min_comparisons=3)
            from oracle import devnet as oracle_devnet
            self.docker, self.order = [], []

            def oracle_stop(name, root_):
                self.order.append("stop")

            def oracle_run(name, root_, log):
                self.order.append(("run", site.log(root).read_text().count('"event": "error"')))
                return json.loads(json.dumps(oracle[0])), list(oracle[1])
            with patch.object(e2e_scenario, "data_root", return_value=root), \
                    patch.object(e2e_scenario, "up_eras", side_effect=up_eras), \
                    patch.object(e2e_scenario, "start_follower", side_effect=start), \
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

    # the keys of the summary of a run with no load and no comparisons, pinned so that no change adds one; `incomplete`
    # and `verdict` are the run's incomplete gates and its verdict (#924)
    SINGLE_SUMMARY = {"transitions", "expectedTransitions", "blocksPerEra", "blocksApplied", "lastEpoch",
                      "validationErrors", "fatalErrors", "unparsedOutput", "rollbacks", "reconnects", "followerExit",
                      "ledgerComparison", "blockedThreads", "endedThreads", "stoppedBecause", "cpuStarvationWarnings",
                      "targetConwayEpoch", "failures", "incomplete", "verdict", "passed"}

    def test_the_mocked_run_is_green(self):
        summary, code = self.run_e2e()
        self.assertEqual((summary["failures"], summary["passed"], code), ([], True, 0))
        self.assertEqual((summary["verdict"], summary["incomplete"]), ("passed", []))

    def test_an_incomplete_gate_is_not_a_pass(self):
        self.assertEqual(e2e_scenario.run_verdict(False, []), "passed")
        self.assertEqual(e2e_scenario.run_verdict(False, ["node-to-client battery: 1 incomplete rows"]), "incomplete")
        self.assertEqual(e2e_scenario.run_verdict(True, ["node-to-client battery: 1 incomplete rows"]), "failed")

    def test_the_follower_keeps_the_summary_layout(self):
        summary, _ = self.run_e2e()
        self.assertEqual(set(summary), self.SINGLE_SUMMARY)
        for key in ("followers", "crossCheck", "plutusVm", "reachedTarget", "followerStop"):
            self.assertNotIn(key, summary)
        self.assertEqual(summary["followerExit"], None, "the stop the run asked for is no exit of the follower's")
        self.assertIn(("stop", "-t", "30", "n-scala"), self.docker)
        self.assertEqual(self.docker[-2:], [("rm", "-f", "n-scala"), ("rm", "-f", "n-ref")])

    def test_a_failed_run_also_keeps_the_references_evidence(self):
        error = {"event": "error", "kind": "LedgerFailure", "message": "late"}
        summary, code = self.run_e2e(on_stop=[error])
        self.assertEqual(code, 1)
        self.assertEqual(set(summary), self.SINGLE_SUMMARY | {"referenceEvidence"})

    def test_an_error_during_the_walk_or_the_wait_fails_the_run(self):
        error = {"event": "error", "kind": "ValidationError", "message": "bad block"}
        with self.subTest(when="during the era walk"):
            summary, code = self.run_e2e(log=green_log(extra=[error]))
            self.assertEqual(code, 1)
            self.assertEqual(summary["failures"], ["follower error: bad block"])
        with self.subTest(when="while the run waits for its target"):
            summary, code = self.run_e2e(after_walk=[error])
            self.assertEqual(code, 1)
            self.assertEqual(summary["failures"], ["1 validation errors: bad block",
                                                   "stopped without reaching Conway epoch 7: error"])

    def test_an_error_logged_after_the_wait_fails_the_run(self):
        # what the follower logs once the wait for the target is over, up to its stop, is judged with the rest
        summary, code = self.run_e2e(on_stop=[{"event": "error", "kind": "LedgerFailure", "message": "late"}])
        self.assertEqual(code, 1)
        self.assertEqual(summary["failures"], ["1 fatal errors: late"])

    def test_an_oracle_run_stops_its_follower_with_the_oracle_and_judges_its_whole_log_first(self):
        oracle = ({"digests": 3, "compared": 3, "results": [{"slot": 1}]}, [])
        summary, code = self.run_e2e(oracle=oracle)
        self.assertEqual((summary["failures"], code), ([], 0))
        self.assertEqual(self.order, ["stop", ("run", 0)])
        self.assertEqual(summary["oracle"], {"digests": 3, "compared": 3})  # the rows stay in oracle-summary.json
        self.assertFalse(any(c[0] == "stop" for c in self.docker), "the oracle's own stop, not the run's")
        late = [{"event": "error", "kind": "LedgerFailure", "message": "late"}]
        summary, code = self.run_e2e(oracle=({"digests": 3}, ["oracle: 1 digest differs"]), on_stop=late)
        self.assertEqual(code, 1)
        self.assertEqual(summary["failures"], ["1 fatal errors: late", "oracle: 1 digest differs"])

    def test_an_end_on_the_stop_with_another_status_fails_the_run(self):
        for status, expected in (
                (137, ["the follower ended with 137 on the run's stop (killed after the stop's limit), not 143, the "
                       "end of a SIGTERM"]),
                (1, ["the follower ended with 1 on the run's stop, not 143, the end of a SIGTERM"]),
                (0, ["the follower ended with 0 on the run's stop, not 143, the end of a SIGTERM"])):
            with self.subTest(status=status):
                summary, code = self.run_e2e(stop_status=status)
                self.assertEqual((summary["failures"], code), (expected, 1))

    def test_a_last_line_cut_while_stopping_fails_and_is_still_judged(self):
        cut = json.dumps({"event": "error", "kind": "LedgerFailure", "message": "at the end"})  # no newline
        summary, code = self.run_e2e(on_stop=cut)
        self.assertEqual(code, 1)
        self.assertEqual(summary["failures"][:2], [
            "1 fatal errors: at the end", f"the follower's log ends in a line without its newline: {cut}"])

    def test_a_follower_that_ended_before_the_stop_is_a_premature_exit(self):
        summary, code = self.run_e2e(exited=1)
        self.assertEqual(code, 1)
        self.assertEqual(summary["failures"], ["the follower exited with 1",
                                               "stopped without reaching Conway epoch 7: follower exited"])
        self.assertEqual(summary["followerExit"], 1)
        self.assertNotIn(("stop", "-t", "30", "n-scala"), self.docker, "an ended follower is not stopped")


if __name__ == "__main__":
    unittest.main()
