# SPDX-License-Identifier: Apache-2.0
"""Relay observation of the outbound governor's reuse honours normal ChainSync return delays without
weakening success."""
import pathlib
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet.scenarios import relay

REUSED = [{"event": "connection", "what": "changed", "remote": relay.RELAY_REMOTE,
           "from": "InboundSt Duplex", "to": "DuplexSt"}]


class RelayRepromotionTests(unittest.TestCase):
    def observe(self, timeout, ready_at=None, temperature="hot", connections=1, reused=True):
        now = [0]
        reads = []
        entry = {"remote": relay.RELAY_REMOTE, "state": "DuplexSt"}
        pending = {"connections": [entry], "peerSelection": {"peers": [{"peer": relay.RELAY_REMOTE,
                                                                        "temperature": "warm"}]}}
        promoted = {"connections": [entry] * connections,
                    "peerSelection": {"peers": [{"peer": relay.RELAY_REMOTE, "temperature": temperature}]}}

        def ready():
            return ready_at is not None and now[0] >= ready_at

        def connections_now(_):
            reads.append(now[0])
            return promoted if ready() else pending

        def sleep(seconds):
            now[0] += seconds

        with patch.object(relay.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(relay.time, "sleep", side_effect=sleep), \
                patch.object(relay, "scala_connections", side_effect=connections_now), \
                patch.object(relay, "reuse_events", side_effect=lambda _: REUSED if reused and ready() else []):
            result = relay.wait_reuse(SimpleNamespace(sync_timeout=timeout), pathlib.Path("unused"))
        return result, reads, now[0]

    def test_actual_retry_after_normal_120_second_return_is_observed(self):
        # First discovery/return can itself take time; native's 120-second wait follows it.
        result, reads, elapsed = self.observe(240, ready_at=181)
        self.assertEqual(relay.relay_temperature(result), "hot")
        self.assertEqual(reads, list(range(182)))
        self.assertEqual(elapsed, 181)

    def test_no_success_before_native_repromotion(self):
        result, reads, elapsed = self.observe(120, ready_at=120)
        self.assertEqual(relay.relay_temperature(result), "warm")
        self.assertEqual(reads, list(range(120)))
        self.assertEqual(elapsed, 120)

    def test_missing_reuse_hot_or_single_connection_still_exhausts_configured_budget(self):
        for temperature, connections, reused in (("warm", 1, True), ("hot", 2, True), ("hot", 1, False)):
            with self.subTest(temperature=temperature, connections=connections, reused=reused):
                result, reads, elapsed = self.observe(240, ready_at=121, temperature=temperature,
                                                      connections=connections, reused=reused)
                self.assertEqual(reads, list(range(240)))
                self.assertEqual(elapsed, 240)


if __name__ == "__main__":
    unittest.main()
