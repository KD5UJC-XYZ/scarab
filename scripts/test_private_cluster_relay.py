# SPDX-License-Identifier: Apache-2.0
"""Readiness guards, not a simulation of successful reference transaction diffusion."""
import json
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace
from private_cluster_relay import connectivity, transaction_requests, RelayRunner

class RelayGuards(unittest.TestCase):
    def text(self, age=70, duplex=2, delay=60):
        header = f"ncTxSubmissionLogicVersion = TxSubmissionLogicV1, ncTxSubmissionInitDelay = TxSubmissionInitDelay {delay}s"
        rows = [{"at": datetime.fromtimestamp(1000-age, timezone.utc).isoformat(),
                 "ns": "Net.InboundGovernor.Remote.PromotedToHotRemote",
                 "data": {"connectionId": {"localAddress": {"port": "1", "address": "127.0.0.1"},
                                            "remoteAddress": {"port": str(p), "address": "127.0.0.1"}}}}
                for p in (2, 3)]
        rows.append({"at": "1970-01-01T00:16:40Z", "ns": "Net.ConnectionManager.Remote.ConnectionManagerCounters",
                     "data": {"state": {"fullDuplex": duplex}}})
        return header + "\n" + "\n".join(json.dumps(r) for r in rows)

    def test_connected_peers_and_delay_are_both_required(self):
        self.assertTrue(connectivity(self.text(), 1, {2, 3}, 1000)["delayElapsed"])
        self.assertFalse(connectivity(self.text(age=20), 1, {2, 3}, 1000)["delayElapsed"])
        self.assertIsNone(connectivity(self.text(duplex=1), 1, {2, 3}, 1000))
        self.assertIsNone(connectivity(self.text(), 1, {2, 3, 4}, 1000))

    def test_requests_must_cover_each_actual_expected_peer(self):
        def event(port):
            return json.dumps({"at": "1970-01-01T00:16:40Z", "ns": "TxSubmission.Remote.Send.RequestTxIds",
                "data": {"msg": {"kind": "MsgRequestTxIds"},
                         "peer": {"connectionId": "127.0.0.1:1 127.0.0.1:" + str(port)}}})
        self.assertFalse(transaction_requests(event(2), 1, {2, 3}))
        self.assertFalse(transaction_requests(event(2) + "\n" + event(4), 1, {2, 3}))
        self.assertEqual(set(transaction_requests(event(2) + "\n" + event(3), 1, {2, 3})), {2, 3})

    def test_unexpected_delay_and_future_clock_rejected(self):
        with self.assertRaises(ValueError): connectivity(self.text(delay=0), 1, {2, 3}, 1000)
        with self.assertRaises(ValueError): connectivity(self.text(age=-1), 1, {2, 3}, 1000)

    def test_producer_submission_is_forbidden(self):
        r = object.__new__(RelayRunner)
        with self.assertRaisesRegex(ValueError, "forbids producer"):
            r.execute("cardano-cli", "conway", "transaction", "submit", "--socket-path", "/work/env/socket/node1/sock")

    def test_no_direct_submission_fallback(self):
        r = object.__new__(RelayRunner)
        saved = {}
        r.save = lambda name, value: saved.update({name: value})
        r.execute = lambda *a, **k: self.fail("unexpected CLI command")
        r.submit_producer()
        self.assertIn("Relay-only", saved["submission-route.md"])

if __name__ == "__main__": unittest.main()
