# SPDX-License-Identifier: Apache-2.0
"""The corpora of `refusalcoverage` as recorded: each case was refused with exactly one predicate failure, the one
it was built to provoke, read from the reference's constructor tags (the recorder itself only requires the names
it expects to appear in the rendering)."""
import json
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import ntcquery, refusalcoverage  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2] / "fixtures" / "reference" / "refusals"
DELEG_COLLATERAL = ["deleg-reg-deposit-wrong", "deleg-unreg-refund-wrong", "deleg-unreg-reward-balance",
                    "deleg-vote-unregistered-drep", "govcert-drep-unreg-refund-wrong",
                    "utxo-collateral-script-locked", "utxo-collateral-non-ada"]
GOV_PROPOSALS = (["gov-committee-update-conflicting", "gov-committee-update-expired-member",
                  "gov-treasury-withdrawal-unregistered-account", "gov-parameter-change-guardrails-unexpected",
                  "gov-treasury-withdrawal-guardrails-unexpected"]
                 + [f"gov-parameter-change-{field}-zero" for field in refusalcoverage.ZERO_FIELDS]
                 + ["gov-parameter-change-empty"])
PRE_CONWAY = ["utxo-collateral-script-locked", "utxo-collateral-non-ada", "utxo-body-network-id-wrong",
              "utxo-mint-units-too-big"]
V11 = ["deleg-reg-deposit-wrong-v11", "deleg-unreg-refund-wrong-v11", "gov-parameter-change-n-opt-zero-v11",
       "gov-vote-unelected-committee-member-v11"]
CORPORA = {"conway-deleg-collateral": ("conway", DELEG_COLLATERAL), "conway-gov-proposals": ("conway", GOV_PROPOSALS),
           "conway-v11-coverage": ("conway", V11), "alonzo-collateral": ("alonzo", PRE_CONWAY),
           "babbage-collateral": ("babbage", PRE_CONWAY[:3])}
# the protocol version each corpus was recorded at
VERSIONS = {"conway-deleg-collateral": [10, 0], "conway-gov-proposals": [10, 0], "conway-v11-coverage": [11, 0],
            "alonzo-collateral": [6, 0], "babbage-collateral": [8, 0]}


def records(corpus):
    return [json.loads(line) for line in (ROOT / corpus / "cases" / "cases.jsonl").read_text().splitlines()]


def failures(reason):
    value, end = ntcquery.decode(bytes.fromhex(reason))
    assert end == len(bytes.fromhex(reason))
    [[era, failures]] = value
    return era, failures


def walk(failure, path):
    """The innermost failure at `path`, or None when a tag on the way differs."""
    for depth, tag in enumerate(path):
        if not isinstance(failure, list) or failure[0] != tag:
            return None
        if depth < len(path) - 1:
            failure = failure[1]
    return failure


class OneFailureEach(unittest.TestCase):
    def test_every_case_is_refused_with_the_one_failure_it_was_built_for(self):
        for corpus, (era, labels) in CORPORA.items():
            recorded = records(corpus)
            self.assertEqual([r["label"] for r in recorded], labels, corpus)
            for record in recorded:
                with self.subTest(corpus=corpus, case=record["label"]):
                    name, index, path = refusalcoverage.tags(era, record["label"])
                    self.assertEqual(record["expect"], [name])
                    got_era, fs = failures(record["reason"])
                    self.assertEqual(got_era, index)
                    self.assertEqual(len(fs), 1, f"{len(fs)} failures: {record['rendered'][:300]}")
                    self.assertIsNotNone(walk(fs[0], path), f"{fs[0]!r:.300} is not at {path}")

    def test_each_corpus_was_recorded_at_the_protocol_version_its_cases_are_built_for(self):
        for corpus in CORPORA:
            meta = json.loads((ROOT / corpus / "corpus.json").read_text())
            self.assertEqual(meta["protocolVersion"], VERSIONS[corpus], corpus)

    def test_each_malformed_proposal_echoes_the_parameter_change_it_was_built_with(self):
        for corpus, (_, labels) in CORPORA.items():
            for record in records(corpus):
                label = record["label"]
                if not label.startswith("gov-parameter-change-") or "guardrails" in label:
                    continue
                with self.subTest(case=label):
                    field = label.removeprefix("gov-parameter-change-").removesuffix("-v11").removesuffix("-zero")
                    action = walk(failures(record["reason"])[1][0], (3, 1))[1]
                    update = ({} if field == "empty" else {8: 0} if field == "n-opt"
                              else {refusalcoverage.ZERO_FIELDS[field]: 0})
                    self.assertEqual(action[0], 0, "a ParameterChange")
                    self.assertEqual(action[2], update)


if __name__ == "__main__":
    unittest.main()
