# SPDX-License-Identifier: Apache-2.0
"""The LocalTxMonitor walk of the `e2e` era battery (`eramonitor`, #934), without a devnet."""
import hashlib
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import eramonitor  # noqa: E402
from devnet.cbor import Tag, enc  # noqa: E402

BODY = enc({0: [], 1: [], 2: 0})


def tx(items=4):
    """A transaction of `items` items whose body is `BODY`."""
    return bytes([0x80 + items]) + BODY + b"\xa0" + (b"\xf5" if items == 4 else b"") + b"\xf6"


def item(era, raw):
    return enc([era, Tag(24, raw)])


TXID = hashlib.blake2b(BODY, digest_size=32).hexdigest()


class FakeSession:
    """A LocalTxMonitor session over a fixed snapshot."""

    def __init__(self, items):
        self.replies = [b"\x82\x06" + i for i in items] + [eramonitor.DONE]
        self.calls = []

    def acquire_snapshot(self):
        self.calls.append("acquire")
        return 1

    def next_tx(self):
        return self.replies.pop(0)

    def release_snapshot(self):
        self.calls.append("release")


class Listing(unittest.TestCase):
    def test_a_snapshot_is_walked_to_its_end_and_released(self):
        session = FakeSession([item(5, tx())])
        self.assertEqual(eramonitor.listed(session), {TXID: item(5, tx())})
        self.assertEqual(session.calls, ["acquire", "release"])

    def test_the_era_of_an_item(self):
        self.assertEqual(eramonitor.item_era(item(3, tx(3))), 3)


class Judging(unittest.TestCase):
    def test_the_same_bytes_on_both_nodes_pass(self):
        listing = {TXID: item(4, tx())}
        row = eramonitor.judge("alonzo", "earlier", TXID, listing, dict(listing))
        self.assertEqual(row["verdict"], "passed")
        self.assertEqual(row["listedEra"], 4)

    def test_other_bytes_or_another_era_fail(self):
        row = eramonitor.judge("alonzo", "earlier", TXID, {TXID: item(3, tx(3))}, {TXID: item(4, tx())})
        self.assertEqual(row["verdict"], "failed")
        self.assertIn("differ", row["problems"][0])

    def test_a_transaction_a_node_does_not_list_is_not_observed(self):
        row = eramonitor.judge("mary", "tip", TXID, {}, {TXID: item(3, tx(3))})
        self.assertEqual(row["verdict"], "notObserved")
        self.assertFalse(row["scalaListed"])
        self.assertTrue(row["referenceListed"])

    def test_a_later_era_transaction_must_be_refused_alike(self):
        mismatch = bytes.fromhex("8282066643") + b"onway" + bytes.fromhex("82056742") + b"abbage"
        self.assertEqual(eramonitor.judge("babbage", "later", TXID, {}, {}, mismatch, mismatch)["verdict"], "passed")
        accepted = eramonitor.judge("babbage", "later", TXID, {}, {}, None, mismatch)
        self.assertEqual(accepted["verdict"], "failed")
        other = eramonitor.judge("babbage", "later", TXID, {}, {}, mismatch[:-1] + b"f", mismatch)
        self.assertIn("differ", other["problems"][0])


class Verdicts(unittest.TestCase):
    def test_the_checks_of_each_era(self):
        self.assertEqual(eramonitor.kinds("shelley"), {"tip", "later"})
        self.assertEqual(eramonitor.kinds("mary"), {"tip", "earlier", "later"})
        self.assertEqual(eramonitor.kinds("conway"), {"tip", "earlier"})

    def test_a_failed_check_fails_the_run(self):
        evidence = [{"era": "mary", "kind": "tip", "verdict": "failed", "problems": ["x"]}]
        self.assertEqual(eramonitor.failures(evidence), ["LocalTxMonitor mary tip: x"])

    def test_an_era_without_every_check_passed_is_incomplete_not_failed(self):
        evidence = [{"era": "shelley", "kind": "tip", "verdict": "passed"},
                    {"era": "shelley", "kind": "later", "verdict": "notObserved"},
                    {"era": "allegra", "kind": "tip", "verdict": "passed"},
                    {"era": "allegra", "kind": "earlier", "verdict": "passed"},
                    {"era": "allegra", "kind": "later", "verdict": "passed"}]
        self.assertEqual(eramonitor.failures(evidence), [])
        self.assertEqual(eramonitor.incomplete(evidence, ["shelley", "allegra", "mary"]),
                         ["LocalTxMonitor shelley: no passed check of later",
                          "LocalTxMonitor mary: no passed check of earlier, later, tip"])


if __name__ == "__main__":
    unittest.main()
