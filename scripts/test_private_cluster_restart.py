# SPDX-License-Identifier: Apache-2.0
"""Scripted restart orchestration only; no reference process is started by these tests."""
import copy
import json
import tempfile
import time
import unittest
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from private_cluster import Runner, BINARY_HASHES
from private_cluster_transfer import TransferRunner
from private_cluster_restart import RestartRunner, ROOT, DATABASE, EXE, PIDFD, launch_options, process_stat, same_point, relay_role
from prepare_restart_image import dockerfile, pinned_file
from private_cluster_restart_prerequisites import INVENTORY

ROLE = "Node configuration: byronKeyFile = Nothing shelleyKESSource = Nothing shelleyVRFFile = Nothing shelleyCertFile = Nothing shelleyBulkCredsFile = Nothing"


def argv():
    return [EXE, "run", "--config", ROOT + "/configuration.yaml", "--topology", ROOT + "/node-data/node3/topology.json",
            "--database-path", DATABASE, "--socket-path", "./socket/node3/sock", "--host-addr", "127.0.0.1", "--port", "3003"]


def process(pid=42, ticks=99):
    return {"pid": pid, "startTicks": ticks, "cwd": ROOT, "exe": EXE, "argv": argv(), "binarySha256": "a" * 64}


def signal_result():
    return SimpleNamespace(returncode=0, stderr="", stdout=json.dumps({"method": "pidfd_send_signal",
        "pid": 42, "startTicks": 99, "signal": "TERM", "sent": True}))


def snapshot():
    return ({"hash": "b" * 64, "slot": 12, "block": 8, "epoch": 0, "era": "Conway"}, {
        "utxo": '{"tx#0":{"value":5}}', "utxo-cbor": "a0", "parameters": '{"fee":1}',
        "ledger-state": '{"stateBefore":{"esLState":{"utxoState":{"fees":7}}}}'})


class OwnershipGuards(unittest.TestCase):
    def test_packaging_rejects_unpinned_bases_shared_aliases_and_mutated_inputs(self):
        for base, alias in [("latest", "cardano-restart-test-owned:base"),
                            ("sha256:" + "a" * 64, "cardano-reference-11.1.3:local"),
                            ("sha256:" + "a" * 64, "owned\nRUN something")]:
            with self.assertRaises(ValueError): dockerfile(base, alias)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input"
            path.write_bytes(b"reviewed")
            digest = hashlib.sha256(b"reviewed").hexdigest()
            self.assertEqual(pinned_file(path, digest, 10), b"reviewed")
            with self.assertRaises(ValueError): pinned_file(path, digest, 2)
            path.write_bytes(b"changed")
            with self.assertRaises(ValueError): pinned_file(path, digest, 10)

    def test_exact_relay_paths_and_argument_order_preserved(self):
        command = argv() + ["+RTS", "-N2", "-RTS"]
        before = command.copy()
        parsed = launch_options(command, ROOT, EXE, 3003)
        self.assertEqual(command, before)
        self.assertEqual(parsed["--database-path"], DATABASE)

    def test_producing_unknown_duplicate_external_and_changed_db_options_rejected(self):
        cases = [argv() + ["--shelley-kes-key", "/work/env/private.skey"], argv() + ["--port", "3003"],
                 argv() + ["+RTS", "-N32", "-RTS"], argv() + ["--unknown"]]
        for old, new in [(DATABASE, "/elsewhere/db"), ("127.0.0.1", "0.0.0.0"),
                         ("3003", "3004"), ("./socket/node3/sock", "./socket/node1/sock")]:
            cases.append([new if x == old else x for x in argv()])
        for command in cases:
            with self.subTest(command=command), self.assertRaises(ValueError):
                launch_options(command, ROOT, EXE, 3003)
        for cwd, exe in [("/tmp", EXE), (ROOT, "/unverified/cardano-node")]:
            with self.assertRaises(ValueError): launch_options(argv(), cwd, exe, 3003)

    def test_process_start_identity_handles_spaces_and_parentheses(self):
        text = "42 (cardano node (worker)) S " + " ".join(["0"] * 18 + ["991"])
        self.assertEqual(process_stat(text), {"state": "S", "startTicks": 991})
        for text in ["", "42 (node) S", "42 node S 1"]:
            with self.assertRaises(ValueError): process_stat(text)

    def test_points_and_observed_nonproducing_role_fail_closed(self):
        self.assertTrue(same_point(snapshot()[0], snapshot()[0]))
        self.assertFalse(same_point({}, {}))
        self.assertFalse(same_point(dict(snapshot()[0], slot=True), snapshot()[0]))
        self.assertEqual(relay_role(ROLE), ROLE)
        for value in ("", ROLE.replace("shelleyVRFFile = Nothing", "shelleyVRFFile = Just private")):
            with self.assertRaises(ValueError): relay_role(value)


class RestartOrchestration(unittest.TestCase):
    def runner(self):
        r = object.__new__(RestartRunner)
        r.name = "cardano-private-restart-test"
        r.pidfd_ready = True
        r.deadline = time.monotonic() + 360
        r.records = {}
        r.actions = []
        r.save = lambda name, value: r.records.update({name: copy.deepcopy(value)})
        r.producers = lambda action: r.actions.append(action)
        r.pause_evidence = lambda: []
        r.snapshot = lambda label: snapshot()
        r.query = lambda *args: snapshot()[0]
        r.read = lambda path: ROLE
        r.process = lambda: process()
        r.identity = lambda: {"genesis": "original", "configuration": "original"}
        r.database_identity = lambda: "1:2"
        r.database_inventory = lambda: {"directoryIdentity": "1:2", "files": {"immutable/00000.chunk": 50},
                                        "immutableChunkSha256": {DATABASE + "/immutable/00000.chunk": "c" * 64}}
        r.hashes = lambda paths: {path: "c" * 64 for path in paths}
        r.stop_relay = lambda original: r.actions.append("stop-relay")
        r.start_relay = lambda original: r.actions.append("start-same-db")
        return r

    def test_retained_restart_orders_stop_restart_rejoin_and_stable_state_before_resume(self):
        r = self.runner()
        result = r.restart()
        self.assertEqual(r.actions, ["STOP", "stop-relay", "start-same-db", "CONT"])
        self.assertTrue(result["sameDatabaseDirectory"])
        self.assertFalse(result["containerRestart"])
        self.assertEqual(result["scalaAcquisitionResume"], "not-tested")
        self.assertEqual(r.records["restart-identity-before.md"], r.records["restart-identity-after.md"])

    def test_changed_genesis_after_stop_prevents_launch_and_resumes_producers(self):
        r = self.runner()
        with patch.object(r, "identity", side_effect=[{"genesis": "before"}, {"genesis": "refreshed"}]):
            with self.assertRaisesRegex(ValueError, "before restart"): r.restart()
        self.assertEqual(r.actions, ["STOP", "stop-relay", "CONT"])
        self.assertNotIn("restart-result.md", r.records)

    def test_cancellation_and_partial_pause_failure_always_resume(self):
        for method in ("producers", "stop_relay", "start_relay", "snapshot"):
            r = self.runner()
            if method == "producers":
                def producers(action):
                    r.actions.append(action)
                    if action == "STOP": raise KeyboardInterrupt()
                r.producers = producers
                with self.assertRaises(KeyboardInterrupt): r.restart()
            else:
                with patch.object(r, method, side_effect=KeyboardInterrupt()):
                    with self.assertRaises(KeyboardInterrupt): r.restart()
            self.assertEqual(r.actions[-1], "CONT")
            self.assertNotIn("restart-result.md", r.records)

    def test_changed_tip_utxo_fees_and_retained_chunks_never_pass(self):
        for mutation in ("point", "utxo", "fees", "chunks", "directory"):
            r = self.runner()
            before, after = snapshot(), snapshot()
            if mutation == "point": after[0]["hash"] = "d" * 64
            if mutation == "utxo": after[1]["utxo-cbor"] = "a1"
            if mutation == "fees": after[1]["ledger-state"] = after[1]["ledger-state"].replace(":7", ":8")
            if mutation == "chunks": r.hashes = lambda paths: {path: "e" * 64 for path in paths}
            if mutation == "directory":
                values = iter(["1:2", "1:3"])
                r.database_identity = lambda: next(values)
            with patch.object(r, "snapshot", side_effect=[before, after]):
                with self.subTest(mutation=mutation), self.assertRaises(ValueError): r.restart()
            self.assertEqual(r.actions[-1], "CONT")

    def test_rejoin_wrong_point_is_bounded_and_preserves_failure(self):
        r = self.runner()
        wrong = dict(snapshot()[0], hash="d" * 64)
        def query(kind, node=3):
            return wrong if node == 3 and "start-same-db" in r.actions else snapshot()[0]
        r.query = query
        with patch("private_cluster_restart.time.sleep"):
            with self.assertRaisesRegex(TimeoutError, "rejoin"): r.restart()
        self.assertEqual(len(r.records["restart-rejoin-wait.md"]), 120)
        self.assertEqual(r.actions[-1], "CONT")

    def test_stop_refuses_changed_pid_identity_without_signalling(self):
        r = self.runner()
        del r.stop_relay
        r.process = lambda: process(ticks=100)
        with patch.object(r, "execute") as execute:
            with self.assertRaisesRegex(ValueError, "identity changed"): r.stop_relay(process())
        execute.assert_not_called()

    def test_producer_socket_queries_only_happen_before_stop(self):
        r = self.runner()
        paused = False
        queries = []
        def producers(action):
            nonlocal paused
            paused = action == "STOP"
            r.actions.append(action)
        def query(kind, node=3):
            if node in (1, 2): self.assertFalse(paused, "SIGSTOP producer cannot answer a socket query")
            queries.append((node, paused))
            return snapshot()[0]
        r.producers, r.query = producers, query
        r.restart()
        self.assertIn((1, False), queries)
        self.assertIn((2, False), queries)
        self.assertIn((3, True), queries)
        self.assertEqual(r.actions[-1], "CONT")

    def test_stop_requires_observed_exit_and_refuses_supervisor_replacement(self):
        r = self.runner()
        del r.stop_relay
        r.stat = lambda pid: None
        r.pid = lambda: 43
        with patch.object(r, "execute", return_value=signal_result()) as execute:
            with self.assertRaisesRegex(ValueError, "supervisor replaced"): r.stop_relay(process())
        self.assertEqual(execute.call_args.args, (PIDFD, "42", "99"))
        self.assertEqual(r.records["restart-stop.md"]["signal"], "TERM")

    def test_stop_timeout_does_not_force_kill_or_start_replacement(self):
        r = self.runner()
        del r.stop_relay
        r.stat = lambda pid: {"state": "S", "startTicks": 99}
        with patch.object(r, "execute", return_value=signal_result()) as execute, \
             patch("private_cluster_restart.time.sleep"):
            with self.assertRaises(TimeoutError): r.stop_relay(process())
        execute.assert_called_once()
        self.assertEqual(execute.call_args.args[0], PIDFD)
        self.assertNotIn("start-same-db", r.actions)

    def test_reused_numeric_pid_is_rejected_even_when_pid_file_is_unchanged(self):
        r = self.runner()
        del r.stop_relay
        r.stat = lambda pid: {"state": "S", "startTicks": 100}
        r.pid = lambda: 42
        with patch.object(r, "execute", return_value=signal_result()):
            with self.assertRaisesRegex(ValueError, "replacement identity"): r.stop_relay(process())
        self.assertNotIn("start-same-db", r.actions)

    def test_late_replacement_before_launch_is_rejected_without_docker_exec(self):
        r = self.runner()
        del r.start_relay
        r.stat = lambda pid: {"state": "S", "startTicks": 100}
        r.pid = lambda: 42
        with patch.object(r, "docker") as docker:
            with self.assertRaisesRegex(ValueError, "replaced before launch"): r.start_relay(process())
        docker.assert_not_called()

    def test_pidfd_failure_never_falls_back_to_numeric_signal(self):
        r = self.runner()
        del r.stop_relay
        with patch.object(r, "execute", return_value=SimpleNamespace(returncode=2, stdout="", stderr="ENOSYS")) as execute:
            with self.assertRaisesRegex(RuntimeError, "no numeric-signal fallback"): r.stop_relay(process())
        execute.assert_called_once()
        self.assertEqual(execute.call_args.args[0], PIDFD)

    def test_image_helper_checks_pin_ownership_and_mounts_without_upload(self):
        r = self.runner()
        r.pidfd_ready = False
        r.args = SimpleNamespace(pidfd_helper_sha256="c" * 64)
        mounts = "1 0 0:1 / / ro - overlay overlay ro\n2 1 0:2 / /work rw,noexec - tmpfs tmpfs rw\n3 1 0:3 / /tmp rw,noexec - tmpfs tmpfs rw\n"
        def verify(path=PIDFD, mode="0:0:555:regular file", mountinfo=mounts):
            with patch.object(r, "execute", side_effect=[SimpleNamespace(stdout=value) for value in (path, mode, mountinfo)]), patch.object(r, "docker") as docker:
                r.verify_pidfd_helper()
                docker.assert_not_called()
        verify()
        self.assertTrue(r.pidfd_ready)
        self.assertFalse(r.records["restart-pidfd-helper.md"]["runtimeUpload"])
        for kwargs in ({"path": "/work/helper"}, {"mode": "1000:1000:555:regular file"},
                       {"mode": "0:0:755:regular file"}, {"mountinfo": mounts.replace("noexec", "exec")},
                       {"mountinfo": mounts.replace("/ ro", "/ rw")},
                       {"mountinfo": mounts + "4 1 0:4 / /opt/reference ro - tmpfs tmpfs ro\n"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError): verify(**kwargs)
        r.args.pidfd_helper_sha256 = "0" * 64
        with self.assertRaisesRegex(ValueError, "explicit pin"): verify()
        r.args.pidfd_helper_sha256 = "invalid"
        with patch.object(r, "execute") as execute:
            with self.assertRaisesRegex(ValueError, "SHA-256 pin"): r.verify_pidfd_helper()
            execute.assert_not_called()

    def test_spawn_reuses_exact_args_cwd_and_no_regenerator_or_volume(self):
        r = self.runner()
        del r.start_relay
        r.process = lambda: process(pid=43, ticks=100)
        r.stat = lambda pid: None
        r.pid = lambda: 42
        with patch.object(r, "docker") as docker, \
             patch.object(r, "execute", return_value=SimpleNamespace(stdout="43")):
            new = r.start_relay(process())
        command = docker.call_args.args
        self.assertEqual(command[:4], ("exec", "-d", r.name, "/bin/sh"))
        self.assertEqual(command[7:], (ROOT, EXE, *argv()[1:]))
        self.assertNotIn("cardano-testnet", " ".join(command))
        self.assertNotIn("--mount", command)
        self.assertEqual(new["pid"], 43)

    def test_replacement_pid_must_match_launch_receipt(self):
        r = self.runner()
        del r.start_relay
        r.process = lambda: process(pid=43, ticks=100)
        r.stat = lambda pid: None
        r.pid = lambda: 42
        with patch.object(r, "docker"), patch.object(r, "execute", return_value=SimpleNamespace(stdout="44")):
            with self.assertRaisesRegex(ValueError, "launch receipt"): r.start_relay(process())

    def test_inventory_requires_nonempty_bounded_immutable_database(self):
        r = self.runner()
        del r.database_inventory
        for listing in ("", "volatile/1.dat\t10\n", "../escape.chunk\t10\n", "immutable/0.chunk\t0\n", "immutable/0.chunk\t600000000\n"):
            with patch.object(r, "execute", return_value=SimpleNamespace(stdout=listing)):
                with self.subTest(listing=listing), self.assertRaises(ValueError): r.database_inventory()
        with patch.object(r, "execute", side_effect=RuntimeError("inventory refused symlink")):
            with self.assertRaisesRegex(RuntimeError, "symlink"): r.database_inventory()


class RestartLifecycle(unittest.TestCase):
    def test_missing_required_utility_fails_before_any_cardano_start_or_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            r = RestartRunner(SimpleNamespace(output=directory + "/evidence", reference_image="local",
                                             seconds=420, scala_repo="unused", capture=False))
            calls = []
            def base_preflight():
                r.endpoint = "unix:///var/run/docker.sock"
                r.image = "sha256:" + "a" * 64
            def docker(*args, **kwargs):
                calls.append(args)
                return SimpleNamespace(returncode=0, stdout="", stderr="")
            def execute(*args, **kwargs):
                if args[-1] == "stat": raise RuntimeError("missing required utility: stat")
                return SimpleNamespace(returncode=0, stdout="/bin/utility", stderr="")
            with patch.object(Runner, "preflight", side_effect=base_preflight), \
                 patch.object(RestartRunner, "docker", side_effect=docker), \
                 patch.object(RestartRunner, "execute", side_effect=execute), \
                 patch.object(r, "scala") as scala, patch("private_cluster.threading.Timer"):
                with self.assertRaisesRegex(RuntimeError, "missing required utility"): r.run()
            scala.assert_not_called()
            self.assertFalse(any(c[:2] == ("run", "-d") and r.name in c for c in calls))
            self.assertFalse(any("cardano-testnet create-env" in str(c) or "pidfd_send_signal" in str(c) for c in calls))
            self.assertIn(("rm", "-f", r.name + "-preflight"), calls)
            self.assertNotIn("result.md", [p.name for p in r.out.iterdir()])

    def test_actual_process_inspection_checks_paths_binary_and_pid_file_fence(self):
        r = object.__new__(RestartRunner)
        r.pid = lambda: 42
        r.stat = lambda pid: {"state": "S", "startTicks": 99}
        r.read = lambda path: "3003"
        r.hashes = lambda paths: {EXE: BINARY_HASHES["cardano-node"]}
        def execute(*args, **kwargs):
            if args == ("cat", "/proc/42/cmdline"):
                return SimpleNamespace(stdout="\0".join(argv()) + "\0")
            target = args[-1]
            return SimpleNamespace(stdout={"/proc/42/exe": EXE, "/proc/42/cwd": ROOT}.get(target, target))
        with patch.object(r, "execute", side_effect=execute):
            observed = r.process()
            self.assertEqual(observed["argv"], argv())
            with patch.object(r, "pid", side_effect=[42, 43]):
                with self.assertRaisesRegex(ValueError, "identity changed"): r.process()
            with patch.object(r, "hashes", return_value={EXE: "0" * 64}):
                with self.assertRaisesRegex(ValueError, "verified binary"): r.process()

    def test_inherited_scala_capture_is_routed_to_restarted_relay_and_flags_reset(self):
        with tempfile.TemporaryDirectory() as directory:
            r = RestartRunner(SimpleNamespace(output=directory + "/evidence", scala_repo=directory, capture=False))
            report = {"scope": "header-block-byte-comparison", "passed": True}
            with patch.object(TransferRunner, "read", return_value="3003") as read, \
                 patch.object(r, "query", return_value=snapshot()[0]), \
                 patch.object(r, "docker", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(report), stderr="")) as docker:
                self.assertEqual(r.probe("restart-after-capture", True), report)
            read.assert_called_once_with("node-data/node3/port")
            self.assertIn("reference-capture 3003 1082026", docker.call_args.args[-1])
            self.assertTrue((r.out / "restart-after-capture.md").exists())
            self.assertFalse(r.probing_relay)
            self.assertFalse(r.args.capture)
            with patch.object(Runner, "scala", side_effect=KeyboardInterrupt()):
                with self.assertRaises(KeyboardInterrupt): r.probe("cancelled", True)
            self.assertFalse(r.probing_relay)
            self.assertFalse(r.args.capture)

    def test_restart_failure_uses_existing_bounded_container_cleanup_without_persistent_volume(self):
        with tempfile.TemporaryDirectory() as directory:
            r = RestartRunner(SimpleNamespace(output=directory + "/evidence", reference_image="local",
                                             seconds=420, scala_repo="unused", capture=False))
            calls = []
            def preflight(): r.endpoint = "unix:///var/run/docker.sock"
            def docker(*args, **kwargs):
                calls.append(args)
                return SimpleNamespace(returncode=0, stdout="", stderr="")
            def query(kind, node=1):
                return {"protocolVersion": {"major": 9, "minor": 0}} if kind == "protocol-parameters" else snapshot()[0]
            with patch.object(r, "preflight", side_effect=preflight), patch.object(r, "docker", side_effect=docker), \
                 patch.object(r, "read", return_value="{}"), patch.object(r, "write_json"), \
                 patch.object(r, "query", side_effect=query), patch.object(r, "capture"), \
                 patch.object(r, "scala", side_effect=TimeoutError("restart rejoin")), \
                 patch("private_cluster.profile", return_value=({}, {}, [])), \
                 patch("private_cluster.threading.Timer") as timer:
                with self.assertRaisesRegex(TimeoutError, "restart rejoin"): r.run()
            self.assertIn(("rm", "-f", r.name), calls)
            self.assertIn(("network", "rm", r.name), calls)
            launch = next(c for c in calls if c[:2] == ("run", "-d"))
            self.assertIn("/work:rw,nosuid,nodev,size=2g,uid=1000,gid=1000,mode=0700", launch)
            self.assertNotIn("--mount", launch)
            self.assertNotIn("-v", launch)
            timer.return_value.cancel.assert_called_once()
            self.assertFalse((r.out / "result.md").exists())
            self.assertEqual(json.loads((r.out / "cleanup.md").read_text())["remaining"], {"containers": "", "networks": ""})


class NativePidfdGuards(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if sys.platform != "linux" or shutil.which("cc") is None:
            raise unittest.SkipTest("native pidfd tests require existing Linux C compiler; no installation attempted")
        cls.work = tempfile.TemporaryDirectory(prefix="cardano-pidfd-tests-")
        cls.source = Path(__file__).with_name("private_cluster_pidfd.c").resolve()
        cls.binary = Path(cls.work.name) / "restart-pidfd"
        subprocess.run(["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-static",
                        str(cls.source), "-o", str(cls.binary)], check=True, capture_output=True, timeout=30)

    @classmethod
    def tearDownClass(cls):
        cls.work.cleanup()

    def child(self):
        return subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def test_native_signal_uses_bound_process_and_rejects_wrong_start_time(self):
        child = self.child()
        try:
            ticks = process_stat(Path(f"/proc/{child.pid}/stat").read_text())["startTicks"]
            bad = subprocess.run([str(self.binary), str(child.pid), str(ticks + 1)], capture_output=True, text=True, timeout=5)
            self.assertNotEqual(bad.returncode, 0)
            self.assertIsNone(child.poll())
            good = subprocess.run([str(self.binary), str(child.pid), str(ticks)], capture_output=True, text=True, timeout=5)
            self.assertEqual(good.returncode, 0, good.stderr)
            self.assertEqual(json.loads(good.stdout)["method"], "pidfd_send_signal")
            self.assertEqual(child.wait(timeout=5), -15)
        finally:
            if child.poll() is None: child.terminate()
            child.wait(timeout=5)

    def test_injected_exit_reuse_races_send_only_to_opened_handle(self):
        driver = Path(self.work.name) / "races.c"
        driver.write_text('#define main pidfd_helper_main\n#include "' + str(self.source) + '"\n#undef main\n' + r'''
#include <assert.h>
static int mode, opened, sent, closed;
static int fake_open(pid_t pid) {
    assert(pid == 42); opened++;
    if (mode == 3) { errno = ENOSYS; return -1; }
    return 7;
}
static int fake_ticks(pid_t pid, unsigned long long *ticks) {
    assert(opened == 1 && pid == 42);
    *ticks = mode == 1 ? 100 : 99; /* Reuse between open and identity read. */
    return 0;
}
static int fake_send(int fd) {
    assert(fd == 7); sent++; /* Never receives numeric PID 42. */
    if (mode == 2) { errno = ESRCH; return -1; } /* Original exits after validation. */
    return 0;
}
static int fake_close(int fd) { assert(fd == 7); closed++; return 0; }
int main(void) {
    const struct operations ops = {fake_open, fake_ticks, fake_send, fake_close};
    for (mode = 0; mode < 4; mode++) {
        opened = sent = closed = 0;
        int result = stop_bound(42, 99, &ops);
        assert((result == 0) == (mode == 0));
        assert(opened == 1);
        assert(sent == (mode == 0 || mode == 2));
        assert(closed == (mode != 3));
    }
    return 0;
}
''')
        binary = Path(self.work.name) / "races"
        subprocess.run(["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-static",
                        str(driver), "-o", str(binary)], capture_output=True, check=True, timeout=30)
        result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)


@unittest.skipUnless(sys.platform == "linux", "POSIX inventory execution requires Linux")
class NativeInventory(unittest.TestCase):
    def run_inventory(self, root):
        return subprocess.run(["/bin/sh", "-c", INVENTORY, "test-inventory", str(root)],
                              capture_output=True, text=True, timeout=15)

    def test_complete_nested_hidden_inventory_and_symlinks_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "immutable").mkdir()
            (root / "immutable" / "0.chunk").write_bytes(b"abc")
            (root / ".hidden").write_bytes(b"xyz")
            result = self.run_inventory(root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(set(result.stdout.splitlines()), {"immutable/0.chunk\t3", ".hidden\t3"})
            for target in ("missing", "immutable", "immutable/0.chunk"):
                link = root / "link"
                link.symlink_to(target)
                self.assertEqual(self.run_inventory(root).returncode, 22)
                link.unlink()

    def test_sparse_oversize_file_file_count_and_depth_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with (root / "oversize").open("wb") as stream: stream.truncate(512 * 1024**2 + 1)
            self.assertEqual(self.run_inventory(root).returncode, 28)
            (root / "oversize").unlink()
            for i in range(4097): (root / str(i)).touch()
            self.assertEqual(self.run_inventory(root).returncode, 26)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            leaf = root
            for _ in range(33):
                leaf = leaf / "d"
                leaf.mkdir()
            self.assertEqual(self.run_inventory(root).returncode, 21)


if __name__ == "__main__": unittest.main()
