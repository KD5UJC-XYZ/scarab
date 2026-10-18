# SPDX-License-Identifier: Apache-2.0
"""Negative controls for the actual two-way reference proof; empty wire exchanges never pass."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet.scenarios.peer_sharing import machine_events, proof, reference_answers, topology


def addr(port):
    return {"address": "127.0.0.1", "port": str(port)}


class PeerSharingProofTests(unittest.TestCase):
    def setUp(self):
        self.peers = [{"peer": "127.0.0.1:3351", "advertise": True, "everConnected": True, "failCount": 0},
                      {"peer": "127.0.0.1:3001", "advertise": True, "everConnected": True, "failCount": 0}]
        self.connection = {"peerSelection": {"peers": self.peers, "counters": {"known": 2, "root": 1}}}
        self.scala = [{"action": "peerShareResults", "added": ["127.0.0.1:3001"]}]
        self.request = {"kind": "PeerShareRequests", "selectedPeers": [addr(3350)], "numRequested": 8}
        self.response = {"kind": "PeerShareResults", "result": [[addr(3350), {"Right": [addr(3001)]}]]}

    def evaluate(self, response=None):
        return proof(self.connection, self.scala, [self.request, response or self.response], [3001, 3002])

    def test_actual_machine_reply_and_source_discovery(self):
        self.assertTrue(self.evaluate()["passed"])
        self.assertEqual(topology([3351])["localRoots"][0]["accessPoints"], [addr(3351) | {"port": 3351}])
        self.assertEqual(machine_events('{"data":{"kind":"PeerShareResults","result":[]}}\nnot json'),
                         [{"kind": "PeerShareResults", "result": []}])

    def test_empty_refused_other_peer_and_unregistered_answers_do_not_count(self):
        for result in ([], [[addr(3350), {"Right": []}]], [[addr(3350), {"Left": "timeout"}]],
                       [[addr(3351), {"Right": [addr(3001)]}]],
                       [[addr(3350), {"Right": "PeerSharingNotRegisteredYet"}]]):
            event = {"kind": "PeerShareResults", "result": result}
            self.assertFalse(self.evaluate(event)["passed"])
            self.assertEqual(reference_answers([event]), [])

    def test_non_advertised_failed_unconnected_unproven_and_duplicate_addresses_fail(self):
        for key, value in (("advertise", False), ("everConnected", False), ("failCount", 1)):
            before = self.peers[1][key]
            self.peers[1][key] = value
            self.assertFalse(self.evaluate()["passed"])
            self.peers[1][key] = before
        for values in ([addr(3002)], [addr(3001), addr(3001)], [None]):
            event = {"kind": "PeerShareResults", "result": [[addr(3350), {"Right": values}]]}
            self.assertFalse(self.evaluate(event)["passed"])

    def test_no_discovery_or_request_and_overshare_do_not_count(self):
        self.scala.clear()
        self.assertFalse(self.evaluate()["passed"])
        self.scala.append({"action": "peerShareResults", "added": ["127.0.0.1:3001"]})
        self.request["selectedPeers"] = [addr(3351)]
        self.assertFalse(self.evaluate()["passed"])
        self.request["selectedPeers"] = [addr(3350)]
        self.request["numRequested"] = 0
        self.assertFalse(self.evaluate()["passed"])


if __name__ == "__main__":
    unittest.main()
