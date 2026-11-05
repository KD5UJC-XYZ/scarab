# SPDX-License-Identifier: Apache-2.0
"""The live refusal recorder preserves bytes and rejects unstable/partial observations."""
from contextlib import AbstractContextManager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import tempfile
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from devnet import refusalrecording
from devnet.scenarios import v1context
from devnet.cbor import enc


class FakeSession(AbstractContextManager):
    def __init__(self, reasons=(b"reason",) * 4, *, moved=False):
        self.reasons = iter(reasons)
        self.moved = moved
        self.payloads = []
        self.reads = []
        self.last_raw = b""

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def acquire(self):
        pass

    def release(self):
        pass

    def point(self, era):
        return [[41, b"block"]]

    def tip(self, era):
        return [[42 if self.moved else 41, b"block"]]

    def mempool(self):
        return 42, 0

    def cbor(self, era, query):
        self.reads.append(tuple(query))
        return b"state" if query == [12] else b"utxo"

    def submit_item(self, payload):
        self.payloads.append(payload)
        reason = next(self.reasons)
        self.last_raw = b"message:" + (reason or b"accepted")
        return reason


class RefusalRecordingTest(unittest.TestCase):
    def capture(self, client):
        return refusalrecording.capture("owned", "conway", b"signed original bytes",
                                        session=lambda *args: client)

    def test_repeats_original_payload_and_preserves_every_complete_message(self):
        client = FakeSession()
        captured = self.capture(client)
        self.assertEqual(client.payloads, [captured.item] * 4)
        self.assertEqual(captured.transaction, b"signed original bytes")
        self.assertEqual(captured.reasons, (b"reason",) * 4)
        self.assertEqual(captured.messages, (b"message:reason",) * 4)
        self.assertEqual(client.reads.count((12,)), 5)
        self.assertEqual(captured.evidence()["observations"][0]["reasonCbor"], b"reason".hex())

    def test_variability_is_a_typed_incomplete_capture_not_a_normalized_pass(self):
        with self.assertRaises(refusalrecording.RecordingError) as raised:
            self.capture(FakeSession((b"first", b"second", b"first", b"first")))
        self.assertEqual(raised.exception.problem, refusalrecording.Problem.UNSTABLE)
        self.assertEqual(raised.exception.observations[1], b"second")

    def test_unexpected_acceptance_is_never_retried_as_a_refusal(self):
        with self.assertRaises(refusalrecording.RecordingError) as raised:
            self.capture(FakeSession((None,)))
        self.assertEqual(raised.exception.problem, refusalrecording.Problem.ACCEPTED)

    def test_changed_ledger_state_discards_the_entire_attempt(self):
        clients = iter((FakeSession(moved=True), FakeSession()))
        captured = refusalrecording.capture("owned", "conway", b"original",
                                            session=lambda *args: next(clients))
        self.assertEqual(captured.point, (41, b"block"))
        self.assertEqual(captured.reasons, (b"reason",) * 4)

    def test_full_cli_streams_are_kept_without_the_historical_1500_character_cut(self):
        transaction = enc([{}, {}, True, None])
        captured = refusalrecording.Refusal(transaction, b"item", (41, b"block"), 42,
                                            b"state", b"utxo", (b"reason",) * 4,
                                            (b"message",) * 4)
        driver = object.__new__(v1context.Driver)
        driver.name, driver.era, driver.protocol_major = "owned", "conway", 10
        driver.models, driver.cases, driver.log = {}, [], lambda value: None
        stdout, stderr = "prefix\n", "native diagnostic " + "x" * 7000 + "\n"
        driver.sh = lambda *args, **kwargs: SimpleNamespace(returncode=1, stdout=stdout, stderr=stderr)
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(refusalrecording, "capture", return_value=captured), \
                    patch.object(v1context, "data_root", return_value=Path(temporary)):
                accepted, text = driver.submit(transaction, "case")
                driver.record("case", "test", transaction, "id",
                              {"spent": [], "references": [], "collateral": []},
                              accepted, text, True, [], "expected")
        reference = driver.cases[0]["reference"]
        self.assertEqual(reference["cli"], {"exit": 1, "stdout": stdout, "stderr": stderr})
        self.assertGreater(len(reference["error"]), 7000)
        self.assertEqual(reference["reasonCbor"], b"reason".hex())


if __name__ == "__main__":
    unittest.main()
