# SPDX-License-Identifier: Apache-2.0
"""Private reference probes retain wire differences and clean up after failed clients."""
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
import unittest
from types import SimpleNamespace
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import leios_reference as reference  # noqa: E402
from devnet.scenarios import leios_probe as probe  # noqa: E402


class LeiosProbeTest(unittest.TestCase):
    def test_old_votes_can_be_silently_read_as_new_announcement(self):
        captured = probe.rows("noise\n12\t18\tvotes\tas-received:ok renumbered:ok\t820480\n")
        self.assertEqual(probe.check_verdicts("w38a", captured), [])
        self.assertEqual(captured[0][2], "votes")
        self.assertEqual(captured[0][4], "820480")

    def test_new_block_transactions_have_a_known_arity_difference(self):
        row = (0, 19, "block-txs", "as-received:refused WrongArity(4,3,1)", "820480")
        self.assertEqual(probe.check_verdicts("main", [row]), [])
        changed = (*row[:3], "as-received:ok", row[4])
        self.assertTrue(probe.check_verdicts("main", [changed]))

    def test_responder_error_retains_protocol_and_reference_reason(self):
        event = {"ns": "Net.InboundGovernor.Remote.ResponderErrored",
                 "data": {"miniProtocolNum": {"num": 18}, "reason": "unexpected key (3, 1)"}}
        text = "not json\n" + json.dumps(event, separators=(",", ":"))
        self.assertEqual(probe.trace_reasons(text), [(18, "unexpected key (3, 1)")])

    def test_timed_out_docker_client_does_not_leave_probe_container(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with mock.patch.object(reference, "jdk_image", return_value="test-jdk"), \
                    mock.patch.object(reference, "docker") as docker, \
                    mock.patch.object(reference.subprocess, "run", side_effect=subprocess.TimeoutExpired("docker", 1)):
                with self.assertRaises(subprocess.TimeoutExpired):
                    reference.run_probe("test", root, "classpath", "Probe", [], "timeout", 1)
            docker.assert_called_once_with("rm", "-f", "test-probe-timeout", check=False)

    def test_failed_probe_keeps_output_and_cannot_count_as_expected_refusal(self):
        result = subprocess.CompletedProcess("docker", 7, "messages received 0\n", "probe failed\n")
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with mock.patch.object(reference, "jdk_image", return_value="test-jdk"), \
                    mock.patch.object(reference, "docker") as docker, \
                    mock.patch.object(reference.subprocess, "run", return_value=result):
                with self.assertRaises(subprocess.CalledProcessError) as failure:
                    reference.run_probe("test", root, "classpath", "Probe", [], "failed", 1)
            self.assertEqual(failure.exception.returncode, 7)
            self.assertEqual((root / "probes/failed.out").read_text(), result.stdout + result.stderr)
            docker.assert_called_once_with("rm", "-f", "test-probe-failed", check=False)

    def test_failed_listener_cannot_count_as_expected_channel_close(self):
        process = mock.Mock(returncode=7, args=["docker", "probe"])
        process.communicate.return_value = ("connection ended: data on unexpected mini-protocol 18\n", None)
        with tempfile.TemporaryDirectory() as directory:
            run = probe.Run(SimpleNamespace(name="test"), probe.PROFILES["w38a"])
            run.root = pathlib.Path(directory)
            (run.root / "probes").mkdir()
            with mock.patch.object(reference, "docker"), \
                    mock.patch.object(reference, "probe_command", return_value=["probe"]), \
                    mock.patch.object(reference, "start_relay"), \
                    mock.patch.object(probe.time, "sleep"), \
                    mock.patch.object(probe.subprocess, "Popen", return_value=process):
                with self.assertRaises(subprocess.CalledProcessError) as failure:
                    run.listener()
            self.assertEqual(failure.exception.returncode, 7)
            self.assertEqual((run.root / "probes/listen-without.out").read_text(), process.communicate.return_value[0])

    def test_capture_makes_trace_visible_before_follower_exits(self):
        emitted, release = threading.Event(), threading.Event()
        line = '{"ns":"LeiosNotify.Remote.Send.RequestNext","data":{}}\n'

        class Follower:
            def __init__(self):
                self.stdout = self.lines()

            def lines(self):
                yield line
                emitted.set()
                release.wait(timeout=5)

            def terminate(self):
                release.set()

        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with mock.patch.object(reference.subprocess, "Popen", side_effect=lambda *args, **kwargs: Follower()):
                followers = reference.capture_logs("test", root)
            try:
                self.assertTrue(emitted.wait(timeout=5))
                self.assertEqual((root / "logs" / "node1.log").read_text(), line)
            finally:
                reference.stop_capture(followers)
