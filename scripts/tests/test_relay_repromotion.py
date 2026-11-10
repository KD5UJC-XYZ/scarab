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
    def observe(self, timeout, ready_at=None, temperature="hot", connections=1, reused=True,
                intersection="100@abcd", headers=1):
        now = [0]
        reads = []
        entry = {"remote": relay.RELAY_REMOTE, "state": "DuplexSt", "connection": 1}
        pending = {"connections": [entry], "peerSelection": {"peers": [{"peer": relay.RELAY_REMOTE,
                                                                        "temperature": "warm"}]}}
        promoted = {"connections": [entry] * connections,
                    "peerSelection": {"peers": [{"peer": relay.RELAY_REMOTE, "temperature": temperature}]},
                    "sessions": [{"peer": relay.RELAY_REMOTE,
                                  "active": {"connection": 1, "activation": 1, "intersection": intersection,
                                             "headers": headers}}]}

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

    def test_missing_reuse_hot_single_connection_or_following_still_exhausts_configured_budget(self):
        for temperature, connections, reused, intersection, headers in (
                ("warm", 1, True, "100@abcd", 1), ("hot", 2, True, "100@abcd", 1), ("hot", 1, False, "100@abcd", 1),
                ("hot", 1, True, None, 1), ("hot", 1, True, "100@abcd", 0)):
            with self.subTest(temperature=temperature, connections=connections, reused=reused,
                              intersection=intersection, headers=headers):
                result, reads, elapsed = self.observe(240, ready_at=121, temperature=temperature,
                                                      connections=connections, reused=reused,
                                                      intersection=intersection, headers=headers)
                self.assertEqual(reads, list(range(240)))
                self.assertEqual(elapsed, 240)


class ReuseVerdictTests(unittest.TestCase):
    """The end-of-run verdict on the outbound governor's use of the relay's connection."""

    @staticmethod
    def table(headers, tip, intersection="100@abcd", temperature="hot", connection=1, session=(1, 1),
              lifetime=None):
        """One relay connection `connection` in duplex use, and the relay's running session over the connection
        and in the activation `session` (none if `session` is None)."""
        active = None if session is None else {
            "connection": session[0], "activation": session[1], "intersection": intersection, "headers": headers,
            "peerTipBlock": None if tip is None else str(tip)}
        return {"connections": [{"remote": relay.RELAY_REMOTE, "state": "DuplexSt", "connection": connection}],
                "peerSelection": {"peers": [{"peer": relay.RELAY_REMOTE, "temperature": temperature}]},
                "sessions": [{"peer": relay.RELAY_REMOTE, "active": active,
                              "lifetime": lifetime or {"activations": 1, "headers": headers}}]}

    def test_a_session_that_keeps_following_passes(self):
        self.assertEqual(relay.reuse_failures(self.table(10, 50), self.table(90, 200), 201, REUSED, []), [])

    def test_activation_without_chain_sync_progress_fails(self):
        # hot, one connection, a reuse logged: still a failure while the session's headers stand still
        failures = relay.reuse_failures(self.table(10, 50), self.table(10, 50), 201, REUSED, [])
        self.assertTrue(any("did not keep following" in f for f in failures), failures)

    def test_no_intersection_fails(self):
        failures = relay.reuse_failures(self.table(0, None, intersection=None), self.table(0, None, intersection=None),
                                        201, REUSED, [])
        self.assertTrue(any("did not follow" in f for f in failures), failures)

    def test_a_stale_peer_tip_fails(self):
        failures = relay.reuse_failures(self.table(10, 50), self.table(90, 150), 201, REUSED, [])
        self.assertTrue(any("is behind the pool" in f for f in failures), failures)

    def test_a_new_activation_counts_from_its_own_start(self):
        # the relay returned and was promoted again on the same connection: its own headers are progress
        last = self.table(30, 200, session=(1, 2), lifetime={"activations": 2, "headers": 120})
        self.assertEqual(relay.reuse_failures(self.table(90, 50), last, 201, REUSED, []), [])

    def test_a_replacement_with_the_old_connections_report_fails(self):
        # connection 1 followed and ended; connection 2 replaced it and is hot, but the report at the end is still
        # connection 1's, with more headers and a fresh tip
        last = self.table(90, 200, connection=2, session=(1, 1))
        failures = relay.reuse_failures(self.table(10, 50), last, 201, REUSED, [])
        self.assertTrue(any("no running session over the relay's connection" in f for f in failures), failures)

    def test_a_replacement_that_never_intersects_fails_whatever_the_lifetime_totals(self):
        # connection 2's own session found no intersection; the totals still hold connection 1's headers
        last = self.table(0, None, intersection=None, connection=2, session=(2, 2),
                          lifetime={"activations": 2, "headers": 90})
        failures = relay.reuse_failures(self.table(10, 50), last, 201, REUSED, [])
        self.assertTrue(any("did not keep following" in f for f in failures), failures)

    def test_no_running_session_at_the_end_fails(self):
        last = self.table(0, None, session=None, lifetime={"activations": 1, "headers": 90})
        failures = relay.reuse_failures(self.table(10, 50), last, 201, REUSED, [])
        self.assertTrue(any("no running session over the relay's connection" in f for f in failures), failures)


if __name__ == "__main__":
    unittest.main()
