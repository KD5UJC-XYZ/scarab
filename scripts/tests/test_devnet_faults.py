# SPDX-License-Identifier: Apache-2.0
"""The seeded fault schedule, its replay, the tc commands and the verdicts of the `faults` scenario (issue 224)."""
import hashlib
import json
import pathlib
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import faultplan, netfaults, util
from devnet.scenarios import faults

PARAMS = (5, 0.05, 0.1)   # k, active slot coefficient, slot length of a cardano-testnet environment


class ScheduleTests(unittest.TestCase):
    def test_same_seed_same_bytes(self):
        a = faultplan.render(faultplan.generate(42, 600, *PARAMS))
        b = faultplan.render(faultplan.generate(42, 600, *PARAMS))
        self.assertEqual(a, b)

    def test_stream_is_pinned(self):
        # a change of the generator changes what a recorded seed replays; the literals below were taken from the
        # generator once and this fails when it drifts
        plan = faultplan.generate(1, 600, *PARAMS)
        self.assertEqual([(e["kind"], e["start"], e["end"]) for e in plan["episodes"]][:3],
                         [("crash", 32, 49), ("loss", 124, 154), ("partition", 208, 214)])
        self.assertEqual(plan["actions"][:3],
                         [{"at": 32, "op": "kill", "node": "scala"}, {"at": 49, "op": "start", "node": "scala"},
                          {"at": 124, "op": "shape", "link": "s-p3", "delayMs": 0, "jitterMs": 0, "lossPercent": 20}])
        self.assertEqual(hashlib.sha256(faultplan.render(plan).encode()).hexdigest(),
                         "1b1bd37381e3e724095c1f7f559ddcbf4d194671f7997ad40099edf81b03484c")

    def test_seeds_differ(self):
        plans = {faultplan.render(faultplan.generate(s, 600, *PARAMS)) for s in range(20)}
        self.assertGreater(len(plans), 15)

    def test_invariants_over_many_seeds(self):
        for seed in range(200):
            plan = faultplan.generate(seed, 600, *PARAMS)
            self.assertIsNone(faultplan.validate(plan), seed)
            limit = plan["limits"]["maxPartitionSeconds"]
            self.assertEqual(limit, 10)
            previous_end = 0
            for e in plan["episodes"]:
                self.assertGreaterEqual(e["start"], previous_end + faultplan.GRACE[e["kind"]] if previous_end else 0)
                self.assertLess(e["start"], e["end"])
                previous_end = e["end"]
                if e["kind"] == "partition":
                    self.assertLessEqual(e["end"] - e["start"], limit)
                    self.assertGreaterEqual(e["end"] - e["start"], faultplan.MIN_PARTITION)
                    self.assertTrue(e["links"])
                if e["kind"] == "crash":
                    self.assertLessEqual(e["downSeconds"], faultplan.MAX_CRASH_DOWN)
            self.assertLessEqual(plan["healedBy"], plan["duration"])
            for window in plan["quiet"]:
                self.assertGreaterEqual(window["to"] - window["from"], faultplan.QUIET[0], (seed, window))

    def test_every_shape_is_healed_and_every_kill_started(self):
        for seed in range(100):
            plan = faultplan.generate(seed, 600, *PARAMS)
            shaped, killed = {}, set()
            for a in plan["actions"]:
                if a["op"] == "shape":
                    shaped[a["link"]] = shaped.get(a["link"], 0) + 1
                elif a["op"] == "heal":
                    self.assertGreater(shaped.get(a["link"], 0), 0)
                    shaped[a["link"]] -= 1
                elif a["op"] == "kill":
                    self.assertNotIn(a["node"], killed)
                    killed.add(a["node"])
                elif a["op"] == "start":
                    killed.remove(a["node"])
            self.assertEqual({v for v in shaped.values()}, {0} if shaped else set())
            self.assertFalse(killed)

    def test_partitions_split_the_nodes_into_groups(self):
        for seed in range(100):
            for e in faultplan.generate(seed, 600, *PARAMS)["episodes"]:
                if e["kind"] == "partition":
                    flat = [n for g in e["groups"] for n in g]
                    self.assertEqual(sorted(flat), sorted(faultplan.NODES))
                    self.assertEqual(e["links"], faultplan.links_between(e["groups"]))

    def test_partition_bound_follows_the_genesis(self):
        self.assertEqual(faultplan.max_partition_seconds(5, 0.05, 0.1), 10)
        self.assertEqual(faultplan.max_partition_seconds(2160, 0.05, 1), 43200)
        self.assertEqual(faultplan.max_partition_seconds(1, 0.5, 0.1), faultplan.MIN_PARTITION)

    def test_all_kinds_occur(self):
        kinds = {e["kind"] for s in range(50) for e in faultplan.generate(s, 600, *PARAMS)["episodes"]}
        self.assertEqual(kinds, set(faultplan.KINDS))

    def test_short_duration_is_refused(self):
        with self.assertRaises(ValueError):
            faultplan.generate(1, faultplan.MIN_DURATION - 1, *PARAMS)

    def test_minimum_duration_holds_an_episode(self):
        for seed in range(50):
            self.assertTrue(faultplan.generate(seed, faultplan.MIN_DURATION, *PARAMS)["episodes"])

    def test_file_round_trip_validates(self):
        plan = faultplan.generate(3, 300, *PARAMS)
        self.assertIsNone(faultplan.validate(json.loads(faultplan.render(plan))))

    def test_validate_refuses_malformed(self):
        plan = faultplan.generate(3, 300, *PARAMS)
        self.assertIn("version", faultplan.validate(dict(plan, version=99)))
        self.assertIn("malformed", faultplan.validate({}))
        bad = dict(plan, actions=[{"at": 1, "op": "shape", "link": "nope"}])
        self.assertIn("unknown link", faultplan.validate(bad))
        bad = dict(plan, actions=[{"at": 5, "op": "kill", "node": "p2"}, {"at": 1, "op": "start", "node": "p2"}])
        self.assertIn("time order", faultplan.validate(bad))
        bad = dict(plan, actions=[{"at": 1, "op": "reboot", "node": "p2"}])
        self.assertIn("unknown action", faultplan.validate(bad))
        bad = dict(plan, quiet=[{"from": 10, "to": 5}])
        self.assertIn("quiet", faultplan.validate(bad))

    def test_replay_command_names_the_seed(self):
        plan = faultplan.generate(77, 600, *PARAMS)
        self.assertEqual(faultplan.replay_command("n", plan), "python3 scripts/devnet.py --name n faults --seed 77 --duration 600")


    def test_replay_command_of_a_schedule_file(self):
        plan = faultplan.generate(77, 600, *PARAMS)
        self.assertEqual(faultplan.replay_command("n", plan, "s.json"), "python3 scripts/devnet.py --name n faults --schedule s.json")


class VerdictTests(unittest.TestCase):
    def samples(self, rows):
        return [{"t": t, "heights": h} for t, h in rows]

    def test_growth_passes_and_fails(self):
        quiet = [{"from": 10, "to": 40}]
        grows = self.samples([(10, {"scala": 5, "p2": 5, "p3": 5}), (40, {"scala": 9, "p2": 10, "p3": 9})])
        self.assertEqual(faultplan.growth_failures(grows, quiet), [])
        stuck = self.samples([(10, {"scala": 5, "p2": 5, "p3": 5}), (40, {"scala": 5, "p2": 10, "p3": 9})])
        self.assertEqual(len(faultplan.growth_failures(stuck, quiet)), 1)
        self.assertIn("scala", faultplan.growth_failures(stuck, quiet)[0])

    def test_growth_needs_two_readings(self):
        quiet = [{"from": 10, "to": 40}]
        thin = self.samples([(10, {"scala": 5, "p2": None, "p3": 5}), (40, {"scala": 9, "p2": None, "p3": 9})])
        self.assertIn("p2: fewer than two readings", faultplan.growth_failures(thin, quiet)[0])

    def test_growth_ignores_readings_outside_the_window(self):
        quiet = [{"from": 10, "to": 40}]
        rows = self.samples([(5, {"scala": 1, "p2": 1, "p3": 1}), (12, {"scala": 5, "p2": 5, "p3": 5}),
                             (38, {"scala": 5, "p2": 5, "p3": 5}), (60, {"scala": 50, "p2": 50, "p3": 50})])
        self.assertEqual(len(faultplan.growth_failures(rows, quiet)), 3)

    def test_convergence(self):
        same = {"scala": (9, "a"), "p2": (9, "a"), "p3": (9, "a")}
        self.assertEqual(faultplan.convergence_failures(same), [])
        self.assertIn("differ", faultplan.convergence_failures(dict(same, p3=(9, "b")))[0])
        self.assertIn("p3", faultplan.convergence_failures(dict(same, p3=None))[0])

    def test_rollback_bound(self):
        events = [{"event": "rolledBack", "depth": 5}, {"event": "rolledBack", "depth": 6}, {"event": "extended"}]
        self.assertEqual(len(faultplan.rollback_failures(events, 5)), 1)

    def test_scala_failures(self):
        self.assertEqual(faultplan.scala_failures([{"event": "extended"}], [], "x"), [])
        self.assertEqual(len(faultplan.scala_failures([{"event": "error", "message": "m"}], ["boom"], "a Submarine b")), 3)

    def test_haskell_failures(self):
        logs = {"p2": "fine\n", "p3": "DecoderFailure in ExceptionExit here\n"}
        failures = faultplan.haskell_failures(logs, ["p2"])
        self.assertEqual(len(failures), 2)
        self.assertEqual(faultplan.haskell_failures({"p2": "ok"}, []), [])

    def test_rejoin(self):
        restarts = [{"at": 30.0, "eventsBefore": 2}]
        self.assertEqual(faultplan.rejoin_failures(restarts, [{"event": "extended"}] * 2 + [{"event": "extended"}]), [])
        self.assertEqual(len(faultplan.rejoin_failures(restarts, [{"event": "extended"}] * 2 + [{"event": "reconnecting"}])), 1)


class CrashPairingTests(unittest.TestCase):
    def plan(self, *actions):
        plan = faultplan.generate(3, 600, *PARAMS)
        return {**plan, "actions": [{"at": at, "op": op, "node": node} for at, op, node in actions]}

    def test_a_schedule_ending_in_a_kill_is_refused(self):
        problem = faultplan.validate(self.plan((10, "kill", "scala")))
        self.assertIn("not started again", problem)

    def test_a_start_without_a_kill_and_a_double_kill_are_refused(self):
        self.assertIn("without a crash", faultplan.validate(self.plan((10, "start", "p2"))))
        self.assertIn("already down", faultplan.validate(self.plan((10, "kill", "p2"), (11, "kill", "p2"))))

    def test_a_paired_schedule_is_accepted(self):
        self.assertIsNone(faultplan.validate(self.plan((10, "kill", "p2"), (20, "start", "p2"))))

    def test_progress_needs_a_newer_block_on_every_node(self):
        before = {"scala": (5, "a"), "p2": (5, "a")}
        self.assertEqual(faultplan.progress_failures(before, {"scala": (6, "b"), "p2": (6, "b")}), [])
        self.assertEqual(len(faultplan.progress_failures(before, {"scala": (5, "a"), "p2": (6, "b")})), 1)


class ShapingTests(unittest.TestCase):
    def test_every_link_has_a_band_and_ports(self):
        self.assertEqual(sorted(netfaults.band(l) for l in faultplan.LINKS), [2, 3, 4])
        ports = [p for pair in netfaults.PORTS.values() for p in pair]
        self.assertEqual(len(set(ports)), len(ports))

    def test_setup_has_prio_netem_and_four_filters_per_link(self):
        lines = netfaults.setup_lines()
        self.assertTrue(lines[0].startswith("qdisc add dev lo root handle 1: prio bands 4 priomap 0 0 0"))
        self.assertEqual(sum("netem" in l for l in lines), 3)
        self.assertEqual(sum(l.startswith("filter") for l in lines), 12)
        self.assertEqual(sum(1 for l in lines if l.startswith("filter") and "sport" in l), 6)

    def test_shape_and_heal_lines(self):
        self.assertEqual(netfaults.shape_line("s-p2", 100, 20, 0), "qdisc change dev lo parent 1:2 handle 20: netem delay 100ms 20ms")
        self.assertEqual(netfaults.shape_line("s-p3", 0, 0, 100), "qdisc change dev lo parent 1:3 handle 30: netem loss 100%")
        self.assertEqual(netfaults.shape_line("p2-p3", 50, 0, 5), "qdisc change dev lo parent 1:4 handle 40: netem delay 50ms loss 5%")
        self.assertEqual(netfaults.shape_line("s-p2"), "qdisc change dev lo parent 1:2 handle 20: netem")

    def test_teardown(self):
        self.assertEqual(netfaults.teardown_lines(), ["qdisc del dev lo root"])

    def test_routes_reach_the_other_node(self):
        nodes = {"p2": 4002, "p3": 4003}
        routes = dict(netfaults.routes(nodes))
        for node in faultplan.NODES:
            for other, port in netfaults.peer_ports(node).items():
                self.assertIn(port, routes)
                self.assertEqual(routes[port], {"scala": netfaults.SCALA_PORT, **nodes}[other])
        self.assertEqual(len(routes), 6)

    def test_peer_ports_cover_the_other_two(self):
        for node in faultplan.NODES:
            self.assertEqual(sorted(netfaults.peer_ports(node)), sorted(n for n in faultplan.NODES if n != node))


class ScenarioTests(unittest.TestCase):
    def test_topology_names_the_proxy_ports_only(self):
        ports = netfaults.peer_ports("p2")
        topology = faults.topology(ports)
        points = topology["localRoots"][0]["accessPoints"]
        self.assertEqual(sorted(p["port"] for p in points), sorted(ports.values()))
        self.assertEqual(topology["localRoots"][0]["hotValency"], 2)

    def test_pool_script_records_its_pid_and_appends_on_restart(self):
        first = faults.node_script("n", 2, 4002)
        again = faults.node_script("n", 2, 4002, append=True)
        self.assertIn("echo $$ > /work/node2.pid && exec cardano-node run", first)
        self.assertIn("--port 4002", first)
        self.assertTrue(first.endswith("> /work/node2.log 2>&1"))
        self.assertTrue(again.endswith(">> /work/node2.log 2>&1"))

    def test_a_dead_pool_is_reported_not_raised(self):
        run = faults.Run(SimpleNamespace(name="n"), pathlib.Path("/nonexistent"))
        with patch.object(faults, "docker", return_value=SimpleNamespace(returncode=1, stderr="no such process")) as docker:
            self.assertFalse(run.haskell_alive("p2"))
            self.assertEqual(docker.call_args.kwargs, {"check": False})
            run.crashed.clear()
            run.apply({"at": 5, "op": "kill", "node": "p3"}, 5.0)
        self.assertEqual(len(run.problems), 1)
        self.assertIn("p3", run.problems[0])
        with patch.object(faults, "docker", return_value=SimpleNamespace(returncode=0, stderr="")):
            self.assertTrue(run.haskell_alive("p2"))


class FakeScala:
    """A Scala process that answers `poll()` with None for `alive` calls and then with an exit status."""

    def __init__(self, alive, status=137):
        self.alive, self.returncode, self.calls, self.dead = alive, status, 0, False

    def poll(self):
        self.calls += 1
        return None if self.calls <= self.alive and not self.dead else self.returncode

    def wait(self):
        return self.returncode


class PoolConfigurationTests(unittest.TestCase):
    def test_the_traces_are_added_and_the_rest_is_kept(self):
        raw = json.dumps({"Protocol": "Cardano", "TraceOptions": {"": {"severity": "Notice"},
                                                                 "ChainSync.Client": {"detail": "DMaximum"}}})
        config = json.loads(faults.pool_configuration(raw.encode()))
        self.assertEqual(config["Protocol"], "Cardano")
        self.assertEqual(config["TraceOptions"][""], {"severity": "Notice"})
        self.assertEqual(config["TraceOptions"]["ChainSync.Client"], {"detail": "DMaximum", "severity": "Info"})
        for namespace, option in faults.NATIVE_TRACES.items():
            for key, value in option.items():
                self.assertEqual(config["TraceOptions"][namespace][key], value)


class ExecutionTests(unittest.TestCase):
    """`faults()` itself, with the cluster, Docker and the proxies stubbed out."""

    def execute(self, scala, start_reference=None, actions=(), wedged=False, settle=5, die_while_collecting=False,
                pool1="scala"):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = pathlib.Path(tmp.name) / "run"
        plan = {"seed": 1, "duration": 0, "episodes": [], "actions": list(actions), "quiet": [], "inputs": {"k": 5}}
        args = SimpleNamespace(name="n", schedule=None, seed=1, duration=0, settle=settle, keep=True, pool1=pool1)
        clock = [0]

        def tick():
            clock[0] += 1
            return clock[0]

        def log(*_):
            # a growing log: the Scala node extends its chain at every reading unless it is wedged
            n = 3 if wedged else tick()
            return [{"event": "extended", "block": {"blockNo": i, "hash": "h"}} for i in range(1, n + 1)], []

        def started(name):
            (root / "follower.jsonl").write_text("")

        starts = []

        def collect(_name, command):
            # collecting the logs takes long; the Scala node dies meanwhile when the test says so
            if die_while_collecting and command.startswith("cat /work/node"):
                scala.dead = True
            return SimpleNamespace(stdout="")

        def start_scala(run):
            # the first start is the given process, a restart by the schedule is a live one
            starts.append(1)
            run.scala = scala if len(starts) == 1 else FakeScala(alive=10**6)

        stubs = [patch.object(faults, "data_root", return_value=root),
                 patch.object(faults, "start_reference", side_effect=start_reference or started),
                 patch.object(faults, "pool_environment", return_value=({2: 4002, 3: 4003}, {})),
                 patch.object(faults, "load_plan", return_value=plan),
                 patch.object(faults, "put_file"),
                 patch.object(faults.subprocess, "run", return_value=SimpleNamespace(stdout=b"{}")),
                 patch.object(faults, "write_keys"),
                 patch.object(faults.netfaults, "Shaper"),
                 patch.object(faults.netfaults, "start_proxies"),
                 patch.object(faults, "freeze_classes"),
                 patch.object(faults, "database_mounts", return_value=[]),
                 patch.object(faults, "scala_container", return_value=[]),
                 patch.object(faults.Run, "start_scala", start_scala),
                 patch.object(faults.Run, "haskell_alive", return_value=True),
                 patch.object(faults.Run, "pool1_alive", return_value=True),
                 patch.object(faults, "tip_at", side_effect=lambda *_: {"block": tick(), "hash": "h"}),
                 patch.object(faults, "log_counts", return_value=({"forgeStarted": 1, "extended": 1}, 0, 0)),
                 patch.object(faults, "read_log", side_effect=log),
                 patch.object(faults, "in_ref", side_effect=collect),
                 patch.object(faults, "docker", return_value=SimpleNamespace(returncode=0, stdout="", stderr="")),
                 patch.object(faults.time, "sleep"),
                 patch("sys.stdout"), patch("sys.stderr")]
        for stub in stubs:
            stub.start()
            self.addCleanup(stub.stop)
        return args, root

    def summary(self, root):
        return json.loads((root / "faults-summary.json").read_text())

    def test_a_live_run_passes(self):
        args, root = self.execute(FakeScala(alive=10**6))
        with self.assertRaises(SystemExit) as raised:
            faults.faults(args)
        self.assertEqual(raised.exception.code, 0)
        self.assertEqual(self.summary(root)["failures"], [])
        self.assertTrue(self.summary(root)["passed"])
        # a passing run keeps only the tails of the pools' logs
        self.assertFalse((root / "node2.log.gz").exists())

    def test_the_pools_run_with_the_diagnostic_traces(self):
        args, root = self.execute(FakeScala(alive=10**6))
        with self.assertRaises(SystemExit):
            faults.faults(args)
        written = {call.args[1]: call.args[2] for call in faults.put_file.call_args_list}
        options = json.loads(written[faults.POOL_CONFIG])["TraceOptions"]
        self.assertEqual(options["Net.PeerSelection"], {"severity": "Debug"})
        self.assertEqual(options["BlockFetch.Decision"], {"severity": "Debug", "maxFrequency": 2.0})
        # a non-empty TraceOptions replaces the defaults, so the default selection is listed explicitly
        for namespace in ("Forge", "ChainDB", "Net.ConnectionManager.Remote", "Net.InboundGovernor.Remote", "Startup"):
            self.assertEqual(options[namespace], {"severity": "Info"})
        self.assertIn(f"--config {faults.POOL_CONFIG} ", faults.node_script("n", 2, 4002))

    def test_scala_exiting_after_the_last_action_fails_the_run(self):
        # alive through the schedule loop, gone when the cluster settles; its logged tip still matches the others
        args, root = self.execute(FakeScala(alive=1))
        with self.assertRaises(SystemExit) as raised:
            faults.faults(args)
        self.assertEqual(raised.exception.code, 1)
        summary = self.summary(root)
        self.assertFalse(summary["passed"])
        self.assertTrue(any("Scala node exited with 137" in f for f in summary["failures"]), summary["failures"])

    def test_scala_dead_before_its_scheduled_crash_fails_the_run(self):
        # it died on its own just before the kill at 0 s; the restart then hides the death unless the kill checks
        args, root = self.execute(FakeScala(alive=0),
                                  actions=[{"at": 0, "op": "kill", "node": "scala"}, {"at": 0, "op": "start", "node": "scala"}])
        with self.assertRaises(SystemExit) as raised:
            faults.faults(args)
        self.assertEqual(raised.exception.code, 1)
        self.assertTrue(any("before its scheduled crash" in f for f in self.summary(root)["failures"]))

    def test_a_refused_kill_is_a_problem(self):
        run = faults.Run(SimpleNamespace(name="n"), pathlib.Path("/nonexistent"))
        run.scala = FakeScala(alive=10**6)
        with patch.object(faults, "docker", return_value=SimpleNamespace(returncode=1, stderr="no such container")):
            run.apply({"at": 5, "op": "kill", "node": "scala"}, 5.0)
        self.assertEqual(len(run.problems), 1)
        self.assertIn("refused", run.problems[0])

    def test_a_wedged_scala_node_with_a_matching_tip_fails_the_run(self):
        args, root = self.execute(FakeScala(alive=10**6), wedged=True, settle=0.2)
        with self.assertRaises(SystemExit) as raised:
            faults.faults(args)
        self.assertEqual(raised.exception.code, 1)
        self.assertTrue(any("scala: no new block after healing" in f for f in self.summary(root)["failures"]))
        # a failure keeps the pools' whole logs
        self.assertTrue((root / "node2.log.gz").exists() and (root / "node3.log.gz").exists())

    def test_scala_dying_during_log_collection_fails_the_run(self):
        args, root = self.execute(FakeScala(alive=10**6), die_while_collecting=True)
        with self.assertRaises(SystemExit) as raised:
            faults.faults(args)
        self.assertEqual(raised.exception.code, 1)
        self.assertTrue(any("Scala node exited with 137" in f for f in self.summary(root)["failures"]))

    def test_the_scala_tip_pairs_height_and_hash_across_a_rollback(self):
        ext = lambda n: {"event": "extended", "block": {"blockNo": n, "hash": f"h{n}"}}
        events = [ext(n) for n in range(97, 102)] + [{"event": "rolledBack", "to": {"hash": "h99"}, "depth": 2}]
        self.assertEqual(faults.scala_tip(events), (99, "h99"))
        self.assertIsNone(faults.scala_tip([]))
        unknown = [ext(n) for n in range(97, 102)] + [{"event": "rolledBack", "to": {"hash": "elsewhere"}, "depth": 2}]
        self.assertIsNone(faults.scala_tip(unknown))
        self.assertEqual(faults.scala_tip(unknown + [ext(103)]), (103, "h103"))
        self.assertEqual(faults.scala_tip(events + [ext(100)]), (100, "h100"))

    def test_a_node_without_a_baseline_must_still_advance(self):
        class Silent:
            def __init__(self):
                self.readings = [{"scala": (5, "h"), "p2": None, "p3": (5, "h")}] + [{"scala": (5, "h"), "p2": (5, "h"), "p3": (5, "h")}] * 50

            def tips(self):
                return self.readings.pop(0) if len(self.readings) > 1 else self.readings[0]

            def scala_lost(self):
                return None

        run = Silent()
        with patch.object(faults.time, "sleep"):
            tips, base = faults.settle(run, 0.2, run.tips())
        self.assertEqual(base["p2"], (5, "h"))
        self.assertEqual(len(faultplan.progress_failures(base, tips)), 3)

    def test_an_aborted_run_does_not_report_passed(self):
        args, root = self.execute(FakeScala(alive=10**6),
                                  start_reference=subprocess.TimeoutExpired(["docker"], 60))
        with self.assertRaises(subprocess.TimeoutExpired):
            faults.faults(args)
        summary = self.summary(root)
        self.assertFalse(summary["passed"])
        self.assertFalse(summary["completed"])
        self.assertTrue(any("TimeoutExpired" in f for f in summary["failures"]), summary["failures"])

    def test_setup_failure_survives_missing_pool_logs_and_completes_cleanup(self):
        args, root = self.execute(FakeScala(alive=10**6))
        args.keep = False
        reason = "cannot set up tc netem: required kernel modules are unavailable"
        faults.netfaults.Shaper.return_value.install.side_effect = SystemExit(reason)
        commands = []

        def command(argv, **kwargs):
            commands.append(argv)
            missing = argv[-1].startswith("tail -c ")
            text = kwargs.get("text", False)
            # the cluster's configuration is read (as bytes) to derive the pools' copy
            raw = b"{}" if argv[-1].endswith("/configuration.yaml") else b""
            return subprocess.CompletedProcess(
                argv, 1 if missing else 0, stdout="" if text else raw,
                stderr="tail: cannot open pool log" if missing else ("" if text else b""))

        # Keep the real checked Docker wrappers: an unchecked stub would conceal the original bug.
        with patch.object(faults, "docker", util.docker), \
                patch.object(faults, "in_ref", util.in_ref), \
                patch.object(faults.subprocess, "run", side_effect=command), \
                self.assertRaises(SystemExit) as raised:
            faults.faults(args)
        self.assertEqual(raised.exception.code, 1)
        summary = self.summary(root)
        self.assertEqual(summary["failures"], [reason])
        self.assertFalse(summary["completed"])
        self.assertFalse(summary["passed"])
        self.assertIn("--seed 1", summary["replay"])
        self.assertEqual([p.read_text() for p in (root / "node2.log", root / "node3.log")], ["", ""])
        faults.netfaults.Shaper.return_value.remove.assert_called_once()
        self.assertIn(["docker", "rm", "-f", "n-ref"], commands)


class ControlArmTests(unittest.TestCase):
    """`--pool1 reference`: the same schedule with pool 1 as the reference node."""
    execute = ExecutionTests.execute
    summary = ExecutionTests.summary

    def test_a_control_run_passes_without_the_scala_node(self):
        args, root = self.execute(FakeScala(alive=10**6), pool1="reference")
        with patch.object(faults.faultplan, "scala_failures", side_effect=AssertionError("read the Scala log")), \
                patch.object(faults.faultplan, "rollback_failures", side_effect=AssertionError("read the Scala log")), \
                patch.object(faults.faultplan, "rejoin_failures", side_effect=AssertionError("read the Scala log")), \
                self.assertRaises(SystemExit) as raised:
            faults.faults(args)
        self.assertEqual(raised.exception.code, 0)
        summary = self.summary(root)
        self.assertTrue(summary["passed"])
        self.assertEqual(summary["pool1"], "reference")
        self.assertEqual(summary["notApplicable"], ["rollback depth", "Scala node log", "rejoin after restart"])
        faults.freeze_classes.assert_not_called()

    def test_the_schedule_crashes_and_restarts_the_reference_pool_1(self):
        run = faults.Run(SimpleNamespace(name="n", pool1="reference"), pathlib.Path("/nonexistent"))
        calls = []
        with patch.object(faults, "docker", side_effect=lambda *a, **k: calls.append(a) or SimpleNamespace(returncode=0, stderr="")), \
                patch.object(faults, "in_ref"):
            run.apply({"at": 5, "op": "kill", "node": "scala"}, 5.0)
            self.assertIn("scala", run.crashed)
            self.assertIsNone(run.scala_lost())
            run.apply({"at": 9, "op": "start", "node": "scala"}, 9.0)
        self.assertNotIn("scala", run.crashed)
        self.assertEqual(run.problems, [])
        self.assertIn(f"kill -9 $(cat /work/node{faults.POOL}.pid)", calls[0][-1])
        self.assertIn(f"--port {faults.SCALA_PORT} ", calls[1][-1])
        self.assertIn(f"pools-keys/pool{faults.POOL}/", calls[1][-1])


class ProxyTests(unittest.TestCase):
    """lagproxy.py forwards through both the `--listen/--upstream` pair of peercut and repeated `--route`."""
    LAGPROXY = pathlib.Path(__file__).resolve().parents[1] / "devnet" / "lagproxy.py"

    def free_port(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            return s.getsockname()[1]

    def echo_server(self):
        server = socket.socket()
        server.bind(("127.0.0.1", 0))
        server.listen()

        def serve():
            while True:
                try:
                    conn, _ = server.accept()
                except OSError:
                    return
                with conn:
                    data = conn.recv(64)
                    conn.sendall(data.upper())
        threading.Thread(target=serve, daemon=True).start()
        return server

    def ask(self, port, text):
        import time
        deadline = time.monotonic() + 10
        while True:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=5) as c:
                    c.sendall(text)
                    return c.recv(64)
            except OSError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.1)

    def run_proxy(self, *arguments):
        control = "/nonexistent/freeze"   # never exists: the proxy never freezes
        process = subprocess.Popen([sys.executable, "-I", str(self.LAGPROXY), "--delay", "0", "--control", control, *arguments])
        self.addCleanup(process.wait)
        self.addCleanup(process.terminate)
        return process

    def test_listen_and_upstream(self):
        upstream = self.echo_server()
        self.addCleanup(upstream.close)
        listen = self.free_port()
        self.run_proxy("--listen", str(listen), "--upstream", str(upstream.getsockname()[1]))
        self.assertEqual(self.ask(listen, b"abc"), b"ABC")

    def test_routes(self):
        first, second = self.echo_server(), self.echo_server()
        self.addCleanup(first.close)
        self.addCleanup(second.close)
        a, b = self.free_port(), self.free_port()
        self.run_proxy("--route", f"{a}:{first.getsockname()[1]}", "--route", f"{b}:{second.getsockname()[1]}")
        self.assertEqual(self.ask(a, b"one"), b"ONE")
        self.assertEqual(self.ask(b, b"two"), b"TWO")

    def test_no_route_is_refused(self):
        done = subprocess.run([sys.executable, "-I", str(self.LAGPROXY), "--control", "x"], capture_output=True, text=True)
        self.assertNotEqual(done.returncode, 0)
        self.assertIn("--route", done.stderr)


if __name__ == "__main__":
    unittest.main()
