# SPDX-License-Identifier: Apache-2.0
"""Relay observation honours normal ChainSync return delays without weakening success."""
import pathlib
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet.scenarios import relay


class RelayRepromotionTests(unittest.TestCase):
    def observe(self, timeout, ready_at=None, headers=1, intersection=None):
        now = [0]
        reads = []
        pending = {"inboundSessions": [{"intersection": None, "headers": 0}]}
        following = {"inboundSessions": [{"intersection": intersection, "headers": headers}]}

        def connections(_):
            reads.append(now[0])
            return following if ready_at is not None and now[0] >= ready_at else pending

        def sleep(seconds):
            now[0] += seconds

        with patch.object(relay.time, "monotonic", side_effect=lambda: now[0]), \
                patch.object(relay.time, "sleep", side_effect=sleep), \
                patch.object(relay, "scala_connections", side_effect=connections):
            result = relay.wait_inbound_session(SimpleNamespace(sync_timeout=timeout))
        return result, reads, now[0]

    def test_actual_retry_after_normal_120_second_return_is_observed(self):
        # First discovery/return can itself take time; native's 120-second wait follows it.
        result, reads, elapsed = self.observe(240, ready_at=181, intersection={"slot": 100})
        self.assertEqual(result["inboundSessions"][0]["headers"], 1)
        self.assertEqual(reads, list(range(182)))
        self.assertEqual(elapsed, 181)

    def test_no_success_before_native_repromotion(self):
        result, reads, elapsed = self.observe(120, ready_at=120, intersection={"slot": 100})
        self.assertIsNone(result["inboundSessions"][0]["intersection"])
        self.assertEqual(reads, list(range(120)))
        self.assertEqual(elapsed, 120)

    def test_missing_intersection_or_headers_still_exhausts_configured_budget(self):
        for intersection, headers in ((None, 1), ({"slot": 100}, 0), (None, 0)):
            with self.subTest(intersection=intersection, headers=headers):
                result, reads, elapsed = self.observe(240, ready_at=121,
                                                     intersection=intersection, headers=headers)
                self.assertFalse(result["inboundSessions"][0]["intersection"] and
                                 result["inboundSessions"][0]["headers"] > 0)
                self.assertEqual(reads, list(range(240)))
                self.assertEqual(elapsed, 240)
