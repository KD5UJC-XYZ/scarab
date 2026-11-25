# SPDX-License-Identifier: Apache-2.0
"""The corpora of `refusalconstructors` as recorded: each case was refused with exactly the failures `TAGS` names, in
that order, read from the reference's constructor tags; the order cases' maps arrived out of the reference's key
order, and the reference listed their items in key order (`refusalconstructors.voter_order` and the others)."""
import json
import pathlib
import re
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import ntcquery, refusalconstructors as rc  # noqa: E402
from test_refusalcoverage import failures, walk  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2] / "fixtures" / "reference" / "refusals"
CORPORA = {
    "conway-constructors": ["utxow-signature-invalid", "utxow-native-script-fails", "utxow-metadata-hash-missing",
                            "utxow-metadata-missing", "utxow-metadata-hash-conflicting", "utxow-metadata-invalid",
                            "utxow-unspendable-no-datum", "utxo-collateral-inputs-too-many",
                            "utxo-total-collateral-incorrect", "utxo-reference-input-spent", "mempool-inputs-empty",
                            "pool-reward-account-wrong-network", "pool-metadata-hash-too-big",
                            "govcert-committee-member-resigned"],
    "conway-gov-order": ["gov-votes-on-expired-actions-out-of-order", "gov-votes-on-unknown-actions-out-of-order",
                         "gov-voters-unknown-out-of-order", "gov-pool-votes-disallowed-out-of-order",
                         "gov-treasury-withdrawal-accounts-out-of-order"],
    "conway-mempool": ["mempool-unelected-committee-voter", "mempool-unelected-voters-with-ledger-failures",
                       "mempool-vote-on-admitted-proposal", "mempool-authorization-after-admitted-proposal"],
    "conway-constructors-v11": ["ledger-withdrawal-amounts-wrong-v11", "ledger-withdrawal-accounts-missing-v11",
                                "pool-vrf-key-registered-v11", "pool-cost-too-low-v11",
                                "utxow-script-integrity-hash-mismatch-v11"],
}
# the cases judged after transactions the reference admitted first, and how many
BEFORE = {"mempool-vote-on-admitted-proposal": 2, "mempool-authorization-after-admitted-proposal": 1}


def records(corpus):
    return {r["label"]: r for r in (json.loads(line) for line in
                                    (ROOT / corpus / "cases" / "cases.jsonl").read_text().splitlines())}


def body(record):
    """The transaction body of a case's item, its maps in the order they arrived."""
    [_, wrapped] = ntcquery.decode_all(bytes.fromhex(record["item"]))
    return ntcquery.decode_all(wrapped.value)[0]


def failure(record, index=0):
    return failures(record["reason"])[1][index]


def cast(record):
    """The (voter, action) pairs of a case's voting procedures, in the order they arrived."""
    return [(list(voter), list(action)) for voter, actions in body(record)[19].items() for action in actions]


def by(order):
    return lambda pair: (rc.voter_order(pair[0]), rc.gov_id_order(pair[1]))


class RecordedAsBuilt(unittest.TestCase):
    def test_every_case_is_refused_with_the_failures_it_was_built_for_in_order(self):
        for corpus, labels in CORPORA.items():
            recorded = records(corpus)
            self.assertEqual(list(recorded), labels, corpus)
            for label, record in recorded.items():
                with self.subTest(corpus=corpus, case=label):
                    self.assertEqual(record["expect"], list(rc.expected(label)))
                    era, fs = failures(record["reason"])
                    self.assertEqual(era, rc.CONWAY)
                    self.assertEqual(len(fs), len(rc.TAGS[label]), record["rendered"][:300])
                    for f, (_, path) in zip(fs, rc.TAGS[label]):
                        self.assertIsNotNone(walk(f, path), f"{f!r:.300} is not at {path}")
                    self.assertEqual(len(record.get("before", [])), BEFORE.get(label, 0))

    def test_each_corpus_was_recorded_at_the_protocol_version_its_cases_are_built_for(self):
        for corpus in CORPORA:
            meta = json.loads((ROOT / corpus / "corpus.json").read_text())
            self.assertEqual(meta["protocolVersion"], [rc.VERSIONS[corpus], 0], corpus)
            self.assertEqual(meta.get("referenceBuild", "11.1"), "11.1", corpus)


class KeyOrder(unittest.TestCase):
    """The reference lists a GOV or LEDGER failure's items in its key order, not the body's."""

    def assert_out_of_order_then_sorted(self, arrived, listed, key):
        self.assertNotEqual(arrived, sorted(arrived, key=key), "the body's map arrived in key order")
        self.assertEqual(listed, sorted(arrived, key=key))

    def test_votes_on_unknown_actions_are_listed_by_voter_then_action(self):
        record = records("conway-gov-order")["gov-votes-on-unknown-actions-out-of-order"]
        arrived = cast(record)
        listed = walk(failure(record), (3, 0))[1]
        self.assertEqual(listed, [a for _, a in sorted(arrived, key=by(rc))])
        self.assertNotEqual(listed, [a for _, a in arrived])

    def test_expired_and_disallowed_votes_are_listed_by_voter_then_action(self):
        recorded = records("conway-gov-order")
        for label, tag in (("gov-votes-on-expired-actions-out-of-order", 9),
                           ("gov-pool-votes-disallowed-out-of-order", 5)):
            with self.subTest(case=label):
                record = recorded[label]
                listed = [tuple(p) for p in walk(failure(record), (3, tag))[1]]
                self.assert_out_of_order_then_sorted([tuple(p) for p in cast(record)], listed, by(rc))

    def test_unknown_voters_are_listed_in_the_reference_order_not_the_canonical_one(self):
        record = records("conway-gov-order")["gov-voters-unknown-out-of-order"]
        arrived = [list(v) for v in body(record)[19]]
        listed = walk(failure(record), (3, 14))[1]
        self.assert_out_of_order_then_sorted(arrived, listed, rc.voter_order)
        # a DRep's script sorts before its key, where its CBOR tag (3) sorts after the key's (2)
        self.assertNotEqual(listed, sorted(arrived))

    def test_unregistered_treasury_withdrawal_accounts_are_listed_in_key_order(self):
        record = records("conway-gov-order")["gov-treasury-withdrawal-accounts-out-of-order"]
        [[_, _, action, _]] = body(record)[20]
        arrived = list(action[1])
        listed = walk(failure(record), (3, 17))[1]
        self.assert_out_of_order_then_sorted(arrived, listed, rc.account_order)
        self.assertNotEqual(listed, sorted(arrived), "a script account sorts first, where its header sorts last")

    def test_withdrawal_failures_of_version_11_list_their_accounts_in_key_order(self):
        recorded = records("conway-constructors-v11")
        for label, path in (("ledger-withdrawal-amounts-wrong-v11", rc.INCOMPLETE_WITHDRAWALS),
                            ("ledger-withdrawal-accounts-missing-v11", rc.MISSING_ACCOUNTS)):
            with self.subTest(case=label):
                record = recorded[label]
                listed = list(walk(failure(record), path)[1])
                self.assert_out_of_order_then_sorted(list(body(record)[5]), listed, rc.account_order)

    def test_the_unelected_voters_text_lists_their_hot_keys_in_key_order(self):
        record = records("conway-mempool")["mempool-unelected-voters-with-ledger-failures"]
        arrived = [h.hex() for (tag, h) in body(record)[19]]
        text = failure(record, 2)[1]
        self.assertTrue(text.startswith("Unelected committee members are not allowed to cast votes: "), text)
        listed = re.findall(r'unKeyHash = "([0-9a-f]+)"', text)
        self.assert_out_of_order_then_sorted(arrived, listed, lambda h: h)


if __name__ == "__main__":
    unittest.main()
