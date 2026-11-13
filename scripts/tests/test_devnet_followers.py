# SPDX-License-Identifier: Apache-2.0
"""Several Scala followers in one devnet run, one per Plutus evaluator: their names, directories and ports.

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import json
import pathlib
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import cluster, follower, util  # noqa: E402
from devnet.util import SINGLE_FOLLOWER, FollowerSite, follower_plan  # noqa: E402

SCALUS, SCARAB = FollowerSite("scalus"), FollowerSite("scarab")


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


if __name__ == "__main__":
    unittest.main()
