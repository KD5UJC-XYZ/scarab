# SPDX-License-Identifier: Apache-2.0
"""`eraview.reference_pre_conway` asks the era of the state that was acquired, not the caller's (no devnet)."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from devnet import eraview, ntcquery  # noqa: E402


class Stop(Exception):
    pass


class FakeSession:
    """An acquired state of era `acquired`; the first block query ends the view, after the eras it named are kept."""

    def __init__(self, acquired):
        self.acquired, self.named = acquired, []

    def query_raw(self, query):
        if query == ntcquery.CURRENT_ERA:
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


if __name__ == "__main__":
    unittest.main()
