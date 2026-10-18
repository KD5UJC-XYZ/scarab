# SPDX-License-Identifier: Apache-2.0
"""The complete-transaction oracle's stage and input-disposition contract (no Docker)."""
import importlib.util
from pathlib import Path
import unittest
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location(
    "boundary_transactions", Path(__file__).resolve().parents[1] / "oracle/plutus/boundary_transactions.py")
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)


class BoundaryTransactionsTest(unittest.TestCase):
    def test_decoder_refusal_is_phase_one_for_either_validity_flag(self):
        for valid in (True, False):
            self.assertEqual(boundary.expected_failure(boundary.Stage.DECODE, valid), "MalformedScriptWitnesses")

    def test_scope_and_runtime_failure_only_admit_the_collateral_flag(self):
        for stage in (boundary.Stage.SCOPE, boundary.Stage.EVALUATION):
            self.assertEqual(boundary.expected_failure(stage, True), "ValidationTagMismatch")
            self.assertIsNone(boundary.expected_failure(stage, False))

    def test_success_only_admits_the_valid_flag(self):
        self.assertIsNone(boundary.expected_failure(boundary.Stage.SUCCESS, True))
        self.assertEqual(boundary.expected_failure(boundary.Stage.SUCCESS, False), "ValidationTagMismatch")

    def test_both_input_roles_are_checked_for_every_admission_and_validity_combination(self):
        cases = [(False, False, False, False), (False, True, False, False),
                 (True, False, False, True), (True, True, True, False)]
        for accepted, valid, funding, collateral in cases:
            self.assertEqual(boundary.expected_spent(accepted, valid),
                             {"funding": funding, "collateral": collateral})

    def test_utxo_lookup_uses_the_recorded_output_index(self):
        self.assertTrue(boundary.present({("abcd", "2"): {}}, ("abcd", 2, {})))
        self.assertFalse(boundary.present({("abcd", "1"): {}}, ("abcd", 2, {})))


class SetupConfirmationTest(unittest.TestCase):
    outputs = {"fund0": ("setup", 0, {}), "collateral0": ("setup", 1, {}), "change": ("setup", 2, {})}
    present = {("setup", str(i)): {} for i in range(3)}
    absent = {("original", "0"): {}}

    @staticmethod
    def common(block):
        return [{"block": block, "hash": f"block{block}"}] * 3

    def environment(self, snapshots, resubmit_accepted=True):
        clock = SimpleNamespace(seconds=0, frame=0, submitted=[], events=[])
        prepared = SimpleNamespace(transaction=b"identical signed setup", txid="setup",
                                   source=("original", 0), outputs=self.outputs)

        def state():
            return snapshots[min(clock.frame, len(snapshots) - 1)]

        def submit(tx, label):
            clock.submitted.append((tx, label))
            accepted = len(clock.submitted) == 1 or resubmit_accepted
            return accepted, "" if accepted else "mempool duplicate"

        def pause(seconds):
            clock.seconds += seconds
            clock.frame += 1

        driver = SimpleNamespace(prepare_setup=lambda specials, count: prepared, submit=submit,
                                 utxo=lambda: state()[1], log=clock.events.append)
        options = {"now": lambda: clock.seconds, "pause": pause, "tips": lambda: state()[0],
                   "resubmit_after": 0, "timeout": 10}
        return driver, clock, options

    def test_rolled_out_setup_is_resubmitted_identically_and_confirmed_from_new_observation(self):
        snapshots = [(self.common(2), self.present), (self.common(3), self.absent),
                     (self.common(4), self.present), (self.common(6), self.present)]
        driver, clock, options = self.environment(snapshots)
        made, proof = boundary.confirmed_setup(driver, 1, 1, **options)
        self.assertEqual(made, self.outputs)
        self.assertEqual(clock.submitted, [(b"identical signed setup", "setup")] * 2)
        self.assertEqual((proof["firstSharedBlock"], proof["confirmedBlock"], proof["resubmissions"]), (4, 6, 1))

    def test_different_node_tips_do_not_confirm_a_setup(self):
        split = [{"block": 10, "hash": name} for name in ("one", "two", "three")]
        driver, _, options = self.environment([(split, self.present)])
        with self.assertRaisesRegex(RuntimeError, "never reached shared"):
            boundary.confirmed_setup(driver, 1, 1, **options)

    def test_script_outputs_use_the_supplied_utxo_reader_and_original_setup(self):
        driver, _, options = self.environment([(self.common(2), {}), (self.common(4), {})])
        original = driver.prepare_setup({}, 1)
        requested = []
        driver.prepare_setup = lambda specials, count: (requested.append((specials, count)) or original)
        specials = {"script": [b"script address", 10]}
        made, proof = boundary.confirmed_setup(driver, 1, 1, specials=specials,
                                               utxo=lambda: self.present, **options)
        self.assertEqual(requested, [(specials, 1)])
        self.assertEqual(made, self.outputs)
        self.assertEqual(proof["confirmedBlock"], 4)

    def test_split_tips_reset_the_shared_confirmation_window(self):
        split = [{"block": 4, "hash": name} for name in ("one", "two", "three")]
        snapshots = [(self.common(2), self.present), (split, self.present),
                     (self.common(5), self.present), (self.common(7), self.present)]
        driver, _, options = self.environment(snapshots)
        _, proof = boundary.confirmed_setup(driver, 1, 1, **options)
        self.assertEqual((proof["firstSharedBlock"], proof["confirmedBlock"]), (5, 7))

    def test_partial_outputs_or_a_spent_original_input_do_not_trigger_resubmission(self):
        for utxo in ({("setup", "0"): {}}, {}):
            driver, clock, options = self.environment([(self.common(2), utxo)])
            with self.assertRaises(RuntimeError):
                boundary.confirmed_setup(driver, 1, 1, **options)
            self.assertEqual(len(clock.submitted), 1)

    def test_rejected_setup_resubmissions_never_replace_confirmation_and_are_bounded(self):
        driver, clock, options = self.environment([(self.common(2), self.absent)], resubmit_accepted=False)
        with self.assertRaisesRegex(RuntimeError, "never reached shared"):
            boundary.confirmed_setup(driver, 1, 1, **options)
        self.assertEqual(len(clock.submitted), 4)
        self.assertEqual({tx for tx, _ in clock.submitted}, {b"identical signed setup"})
