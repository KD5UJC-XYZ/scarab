# SPDX-License-Identifier: Apache-2.0
"""`eraview.reference_pre_conway` asks the era of the state that was acquired, not the caller's (no devnet)."""
import json
import pathlib
import sys
import unittest
import unittest.mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from unittest.mock import patch  # noqa: E402
from devnet import cbor, compare, eraview, ntcquery  # noqa: E402


class Stop(Exception):
    pass


class FakeSession:
    """An acquired state of era `acquired`; the first block query ends the view, after the eras it named are kept."""

    def __init__(self, acquired):
        self.acquired, self.named = acquired, []

    def acquire(self, point=None):
        pass

    def query_raw(self, query):
        if query == ntcquery.CURRENT_ERA:
            self.last_raw = cbor.enc([4, self.acquired])
            return self.acquired
        raise AssertionError(query)

    def ledger_query(self, era_index, query):
        self.named.append(era_index)
        raise Stop


class AcquiredEraTest(unittest.TestCase):
    def named_by(self, caller, acquired):
        session = FakeSession(acquired)
        with self.assertRaises(Stop):
            eraview.reference_pre_conway(caller, session, {})
        return session.named

    def test_a_state_one_era_later_than_the_callers_is_asked_in_the_acquired_era(self):
        self.assertEqual(self.named_by("alonzo", eraview.ERA_INDEX["babbage"]), [eraview.ERA_INDEX["babbage"]])

    def test_a_state_in_the_callers_era_is_asked_in_it(self):
        self.assertEqual(self.named_by("alonzo", eraview.ERA_INDEX["alonzo"]), [eraview.ERA_INDEX["alonzo"]])

    def test_a_state_that_is_not_shelley_based_is_not_asked(self):
        session = FakeSession(0)
        with self.assertRaises(RuntimeError):
            eraview.reference_pre_conway("alonzo", session, {})
        self.assertEqual(session.named, [])

    def test_a_conway_state_is_not_given_a_pre_conway_view(self):
        session = FakeSession(eraview.ERA_INDEX["conway"])
        with self.assertRaises(eraview.EraLeft):
            eraview.reference_pre_conway("babbage", session, {})
        self.assertEqual(session.named, [])

    def test_a_babbage_round_that_acquires_a_conway_state_is_skipped_as_the_chain_having_left_babbage(self):
        context = unittest.mock.MagicMock()
        context.__enter__.return_value = FakeSession(eraview.ERA_INDEX["conway"])
        with patch.object(compare, "protocol_parameters", return_value={}), \
                patch.object(compare, "lsq", return_value=context), patch.object(compare.time, "sleep"):
            samples = compare.compare_pre_conway_followers("n", "babbage", (compare.SINGLE_FOLLOWER,))
        self.assertEqual(samples[0].result, {"skipped": "the chain left babbage while querying"})

    def test_an_era_reply_that_is_a_simple_value_is_not_an_era(self):
        class Simple(FakeSession):
            def query_raw(self, query):
                self.last_raw = bytes.fromhex("8204e6")  # [4, simple(6)], which the decoder reads as 6
                return 6
        with self.assertRaises(ntcquery.MalformedReply):
            ntcquery.acquired_era(Simple(6))
        with self.assertRaises(ntcquery.MalformedReply):
            ntcquery.acquired_shelley_era(Simple(6))


class TallyTest(unittest.TestCase):
    def tally(self):
        import tempfile
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        return compare.Comparisons(pathlib.Path(self.dir.name))

    def test_a_whole_state_of_a_conway_state_counts_for_conway_whatever_the_callers_era(self):
        tally = self.tally()
        result = {"slot": 1, "era": "conway", "answers": {q: {"matched": True} for q in compare.WHOLE_QUERIES}}
        tally.record_whole("babbage", "x", result)
        for query in compare.WHOLE_QUERIES:
            self.assertEqual(tally.whole[query]["conway"]["matched"], 1, query)
            self.assertEqual(tally.whole[query]["babbage"]["matched"], 0, query)

    STATE = {"epoch": 7, "utxo": {}, "rewardUpdate": None, "params": {}, "ppup": {"current": {}, "future": {}},
             "mir": {"reserves": {}, "treasury": {}, "deltaReserves": 0, "deltaTreasury": 0}}

    def round(self, state_of=lambda site: json.loads(json.dumps(TallyTest.STATE)), parameters=lambda: {}):
        """`compare_era` for an alonzo round whose acquired state is babbage, with two followers: (tallies, agreement)."""
        import tempfile
        from devnet.util import FollowerSite
        sites = (FollowerSite("scalus"), FollowerSite("scarab"))
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = pathlib.Path(directory.name)
        tallies = [compare.Comparisons(root, site) for site in sites]
        agreement = compare.FollowerAgreement(root, sites)
        view = {**json.loads(json.dumps(self.STATE)), "_era": "babbage"}
        context = unittest.mock.MagicMock()
        with patch.object(compare, "protocol_parameters", side_effect=lambda name: parameters()), \
                patch.object(compare, "lsq", return_value=context), \
                patch.object(eraview, "reference_pre_conway", side_effect=lambda *a: ({"slot": 1, "hash": "a"}, dict(view))), \
                patch.object(compare, "awaited_state", side_effect=lambda n, t, site, *a: state_of(site)), \
                patch.object(compare, "whole_state", return_value={"skipped": "none"}), \
                patch.object(compare.time, "sleep"):
            compare.compare_era("n", "alonzo", "x", tallies, agreement)
        return tallies, agreement

    def test_a_round_that_acquires_babbage_counts_and_agrees_in_babbage(self):
        tallies, agreement = self.round()
        for tally in tallies:
            self.assertEqual((tally.per_era["babbage"]["matched"], tally.per_era["alonzo"]["matched"]), (1, 0))
        self.assertEqual((agreement.per_era["babbage"]["agreed"], agreement.per_era["alonzo"]["agreed"]), (1, 0))

    def test_a_failure_after_the_state_was_acquired_counts_for_the_acquired_era(self):
        def broken(site):
            raise ValueError("unreadable")
        tallies, agreement = self.round(broken)
        for tally in tallies:
            self.assertEqual((tally.per_era["babbage"]["errors"], tally.per_era["alonzo"]["errors"]), (1, 0))
        self.assertEqual((agreement.per_era["babbage"]["notCompared"], agreement.per_era["alonzo"]["notCompared"]),
                         (1, 0))

    def after_the_read(self, failure):
        """A round whose second read of the parameters, after the reference state was read, raises `failure`."""
        calls = []

        def parameters():
            calls.append(1)
            if len(calls) % 2 == 0:
                raise failure
            return {}
        return self.round(parameters=parameters)

    def test_a_retryable_failure_after_the_reference_read_counts_for_the_acquired_era(self):
        tallies, agreement = self.after_the_read(RuntimeError("closed"))
        for tally in tallies:
            self.assertEqual((tally.per_era["babbage"]["errors"], tally.per_era["alonzo"]["errors"]), (1, 0))

    def test_an_exit_after_the_reference_read_counts_for_the_acquired_era(self):
        tallies, agreement = self.after_the_read(SystemExit("command failed"))
        for tally in tallies:
            self.assertEqual((tally.per_era["babbage"]["errors"], tally.per_era["alonzo"]["errors"]), (1, 0))

    def test_an_exit_before_the_reference_read_is_not_taken_for_an_era(self):
        with patch.object(compare, "protocol_parameters", side_effect=SystemExit("command failed")), \
                self.assertRaises(SystemExit):
            compare.compare_pre_conway_followers("n", "alonzo", (compare.SINGLE_FOLLOWER,))

    def malformed_then(self, replies, attempts=2):
        view = {**json.loads(json.dumps(self.STATE)), "_era": "babbage"}
        asked = []

        def read(*args):
            asked.append(1)
            reply = replies[len(asked) - 1]
            if reply is None:
                raise RuntimeError("era mismatch or unexpected reply: 'x'")
            return {"slot": 1, "hash": "a"}, dict(view)
        with patch.object(compare, "protocol_parameters", return_value={}), \
                patch.object(compare, "lsq", return_value=unittest.mock.MagicMock()), \
                patch.object(eraview, "reference_pre_conway", side_effect=read), \
                patch.object(compare, "awaited_state", return_value=json.loads(json.dumps(self.STATE))), \
                patch.object(compare.time, "sleep"):
            samples = compare.compare_pre_conway_followers("n", "babbage", (compare.SINGLE_FOLLOWER,), attempts)
        return samples[0].result, len(asked)

    def test_a_malformed_reply_is_an_error_not_a_skip_after_every_attempt(self):
        result, asked = self.malformed_then([None, None])
        self.assertEqual(asked, 2)
        self.assertNotIn("skipped", result)
        self.assertIn("error", result)

    def test_a_malformed_reply_is_retried_and_a_later_read_is_compared(self):
        result, asked = self.malformed_then([None, "ok"])
        self.assertEqual(asked, 2)
        self.assertIs(result["matched"], True)
        self.assertEqual(result["acquiredEra"], "babbage")


if __name__ == "__main__":
    unittest.main()
