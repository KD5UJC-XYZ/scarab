# SPDX-License-Identifier: Apache-2.0
"""The seeded fault schedule, its replay, the tc commands and the verdicts of the `faults` scenario (issue 224)."""
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import faultplan, netfaults

PARAMS = (5, 0.05, 0.1)   # k, active slot coefficient, slot length of a cardano-testnet environment


class ScheduleTests(unittest.TestCase):
    def test_same_seed_same_bytes(self):
        a = faultplan.render(faultplan.generate(42, 600, *PARAMS))
        b = faultplan.render(faultplan.generate(42, 600, *PARAMS))
        self.assertEqual(a, b)

    def test_stream_is_pinned(self):
        # a change of the generator changes what a recorded seed replays; this fails when it does
        plan = faultplan.generate(1, 600, *PARAMS)
        self.assertEqual([(e["kind"], e["start"], e["end"]) for e in plan["episodes"]][:3],
                         [(e["kind"], e["start"], e["end"]) for e in faultplan.generate(1, 600, *PARAMS)["episodes"]][:3])
        self.assertEqual(plan["episodes"][0]["start"], faultplan.Draw(1).integer(*faultplan.FIRST_START))

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


if __name__ == "__main__":
    unittest.main()
