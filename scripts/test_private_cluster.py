# SPDX-License-Identifier: Apache-2.0
"""Guard/evidence unit tests only; these do not simulate reference conformance."""
import copy
import subprocess
import unittest
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from private_cluster import Runner
from private_cluster import profile, assess

class Guards(unittest.TestCase):
    def sample(self):
        genesis = {"epochLength": 500, "slotLength": 0.1, "securityParam": 5,
                   "activeSlotsCoeff": 0.05, "networkMagic": 1082026,
                   "protocolParams": {"protocolVersion": {"major": 10, "minor": 0}}}
        config = {"Test" + era + "HardForkAtEpoch": 0 for era in
                  ["Shelley", "Allegra", "Mary", "Alonzo", "Babbage", "Conway"]}
        config.update({"TraceOptions": {}, "DijkstraGenesisFile": "dijkstra-genesis.json"})
        topology = {"bootstrapPeers": None, "useLedgerAfterSlot": -1, "publicRoots": [],
                    "localRoots": [{"advertise": False,
                        "accessPoints": [{"address": "127.0.0.1", "port": 3001}]}]}
        return genesis, config, [topology]

    def test_actual_pv_is_changed_and_dijkstra_file_removed(self):
        g, c, _ = profile(*self.sample())
        self.assertEqual(g["protocolParams"]["protocolVersion"], {"major": 9, "minor": 0})
        self.assertNotIn("DijkstraGenesisFile", c)
        self.assertFalse(c["PeerSharing"])

    def test_external_peer_and_discovery_rejected(self):
        for change in [lambda t: t.update(bootstrapPeers=[]),
                       lambda t: t.update(useLedgerAfterSlot=0),
                       lambda t: t.update(peerSnapshotFile="peers.json"),
                       lambda t: t["localRoots"][0]["accessPoints"][0].update(address="8.8.8.8"),
                       lambda t: t["publicRoots"].append({"accessPoints": ["external"]})]:
            g, c, ts = self.sample(); change(ts[0])
            with self.assertRaises(ValueError): profile(g, c, ts)

    def test_era_timing_and_destinations_rejected(self):
        for key, value in [("TestConwayHardForkAtEpoch", 1), ("TestDijkstraHardForkAtEpoch", 0),
                           ("TraceForwardTo", "external"), ("TraceOptions", {"external": True})]:
            g, c, ts = self.sample(); c[key] = value
            with self.assertRaises(ValueError): profile(g, c, ts)
        g, c, ts = self.sample(); g["epochLength"] = 100
        with self.assertRaises(ValueError): profile(g, c, ts)

    def test_evidence_requires_live_era_pv_growth_and_convergence(self):
        rows = [[{"era": "Conway", "epoch": e, "block": 10 + e, "hash": str(e)}
                 for _ in range(3)] for e in range(3)]
        params = {"protocolVersion": {"major": 9, "minor": 0}}
        self.assertTrue(assess(rows, params)["passed"])
        for field, value in [("era", "Babbage"), ("epoch", 0), ("block", 10)]:
            bad = copy.deepcopy(rows)
            for row in bad:
                for tip in row: tip[field] = value
            with self.assertRaises(ValueError): assess(bad, params)
        with self.assertRaises(ValueError): assess(rows, {"protocolVersion": {"major": 10, "minor": 0}})
        for row in rows:
            for i, tip in enumerate(row): tip["hash"] = str(i)
        with self.assertRaises(ValueError): assess(rows, params)

class CleanupGuards(unittest.TestCase):
    def test_preflight_timeout_always_attempts_named_container_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = Runner(SimpleNamespace(output=directory + "/evidence", reference_image="local"))
            calls = []
            def docker(*args, **kwargs):
                calls.append(args)
                if args[:2] == ("context", "inspect"):
                    return SimpleNamespace(stdout='[{"Endpoints":{"docker":{"Host":"unix:///var/run/docker.sock"}}}]')
                if args[0] == "info":
                    return SimpleNamespace(stdout='{"OSType":"linux","NCPU":4,"MemTotal":12000000000}')
                if args[0] == "image": return SimpleNamespace(stdout="sha256:local")
                if args[0] == "run": raise subprocess.TimeoutExpired(["docker", "run"], 20)
                return SimpleNamespace(stdout="", stderr="", returncode=0)
            runner.docker = docker
            with patch.dict("os.environ", {}, clear=True):
                with self.assertRaises(subprocess.TimeoutExpired): runner.preflight()
            self.assertEqual(calls[-1], ("rm", "-f", runner.name + "-preflight"))
            self.assertEqual(runner.endpoint, "unix:///var/run/docker.sock")

    def test_environment_override_rejected_before_docker_access(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = Runner(SimpleNamespace(output=directory + "/evidence", reference_image="local"))
            with patch.dict("os.environ", {"DOCKER_HOST":"tcp://remote:2375"}), patch.object(runner, "docker") as docker:
                with self.assertRaises(ValueError): runner.preflight()
                docker.assert_not_called()

    def test_cleanup_timeout_and_cancellation_do_not_skip_later_operations(self):
        for injected in [subprocess.TimeoutExpired(["docker", "rm"], 5), KeyboardInterrupt()]:
            with tempfile.TemporaryDirectory() as directory:
                runner = Runner(SimpleNamespace(output=directory + "/evidence", reference_image="local"))
                calls = []
                def docker(*args, **kwargs):
                    calls.append(args)
                    if len(calls) == 1: raise injected
                    return SimpleNamespace(stdout="", stderr="", returncode=0)
                runner.docker = docker
                runner.cleanup()
                self.assertEqual(len(calls), 7)
                self.assertIn(("rm", "-f", runner.name), calls)
                self.assertIn(("network", "rm", runner.name), calls)
                self.assertTrue((runner.out / "cleanup.md").exists())
                self.assertIn("error", (runner.out / "cleanup.md").read_text())

    def test_verification_timeout_is_preserved_and_other_verification_still_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = Runner(SimpleNamespace(output=directory + "/evidence", reference_image="local"))
            calls = []
            def docker(*args, **kwargs):
                calls.append(args)
                if args[0] == "ps": raise subprocess.TimeoutExpired(["docker", "ps"], 5)
                return SimpleNamespace(stdout="", stderr="", returncode=0)
            runner.docker = docker
            with self.assertRaises(RuntimeError): runner.cleanup()
            self.assertEqual(calls[-1][:2], ("network", "ls"))
            self.assertIn("UNKNOWN", (runner.out / "cleanup.md").read_text())

    def test_watchdog_stops_reference_after_scala_removal_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = Runner(SimpleNamespace(output=directory + "/evidence", reference_image="local"))
            runner.endpoint = "unix:///var/run/docker.sock"
            calls = []
            def run(args, **kwargs):
                calls.append(args)
                if args[-1] == runner.name + "-scala": raise subprocess.TimeoutExpired(["docker", "rm"], 5)
                return SimpleNamespace(stdout="", stderr="", returncode=0)
            with patch("private_cluster.subprocess.run", side_effect=run): runner.watchdog()
            self.assertEqual(calls[-1][-4:], ["stop", "--timeout", "5", runner.name])
            self.assertTrue((runner.out / "watchdog.md").exists())

    def test_partial_or_cross_node_progress_is_not_readiness(self):
        params = {"protocolVersion": {"major":9,"minor":0}}
        rows = [[{"era":"Conway","epoch":i,"block":i+1,"hash":"same"} for i in range(3)]] * 2
        with self.assertRaises(ValueError): assess(rows, params)
        with self.assertRaises(ValueError): assess([rows[0][:2]], params)

class RunFinalization(unittest.TestCase):
    def exercise_capture_failure(self, capture_error, receipt_error=None):
        with tempfile.TemporaryDirectory() as directory:
            runner = Runner(SimpleNamespace(output=directory + "/evidence", reference_image="local",
                                            seconds=240, scala_repo=None))
            calls = 0
            def query(kind, node=1):
                nonlocal calls
                if kind == "protocol-parameters":
                    return {"protocolVersion": {"major": 9, "minor": 0}}
                calls += 1
                return {"era": "Conway", "epoch": 0 if calls <= 4 else 2,
                        "block": calls, "hash": "same"}
            real_save = runner.save
            def save(name, value):
                if name == "capture-error.md" and receipt_error:
                    raise receipt_error
                real_save(name, value)
            def preflight(): runner.endpoint = "unix:///var/run/docker.sock"
            with patch.object(runner, "preflight", side_effect=preflight), \
                 patch.object(runner, "docker", return_value=SimpleNamespace(stdout="", stderr="", returncode=0)), \
                 patch.object(runner, "execute", return_value=SimpleNamespace(stdout="", stderr="")), \
                 patch.object(runner, "read", return_value="{}"), \
                 patch.object(runner, "write_json"), \
                 patch.object(runner, "query", side_effect=query), \
                 patch.object(runner, "capture", side_effect=capture_error) as capture, \
                 patch.object(runner, "cleanup") as cleanup, \
                 patch.object(runner, "save", side_effect=save), \
                 patch("private_cluster.profile", return_value=({}, {}, [])), \
                 patch("private_cluster.time.sleep"), \
                 patch("private_cluster.threading.Timer") as timer, \
                 patch("builtins.print"):
                with self.assertRaises(type(receipt_error or capture_error)):
                    runner.run()
                capture.assert_called_once()
                cleanup.assert_called_once()
                timer.return_value.cancel.assert_called_once()
            self.assertFalse((runner.out / "result.md").exists())

    def test_run_cleans_up_after_capture_keyboard_interrupt(self):
        self.exercise_capture_failure(KeyboardInterrupt())

    def test_run_cleans_up_when_capture_error_receipt_write_fails(self):
        self.exercise_capture_failure(RuntimeError("capture failed"), OSError("receipt disk full"))

if __name__ == "__main__": unittest.main()
