# SPDX-License-Identifier: Apache-2.0
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from private_cluster_transfer import TransferRunner
from private_cluster_relay import RelayRunner
from private_cluster_scenarios import ScenarioRunner
from private_cluster_interval import IntervalRunner, PROFILE, MAX_SLOT, interval_plan, interval_flags, check_inclusion_report


class IntervalGuards(unittest.TestCase):
    def test_separated_bounds_do_not_inherit_reversed_positive_interval(self):
        plan = interval_plan(20)
        self.assertEqual(interval_flags(plan["positive"]), ["--invalid-before", "0", "--invalid-hereafter", "1020"])
        self.assertEqual(interval_flags(plan["expired"]), ["--invalid-hereafter", "19"])
        self.assertEqual(interval_flags(plan["not-yet-valid"]), ["--invalid-before", "100020"])
        self.assertFalse(plan["referenceEvaluationSlotEstablished"])
        self.assertFalse(plan["exactReferenceBoundaryProof"])

    def test_slot_forms_overflow_and_invalid_flags_rejected(self):
        for slot in (True, -1, 0, 1, 1.5, "20", MAX_SLOT - 99999):
            with self.assertRaises(ValueError): interval_plan(slot)
        for value in (-1, MAX_SLOT + 1, True):
            with self.assertRaises(ValueError): interval_flags({"lower": value, "upper": None})

    def test_positive_hook_changes_only_exact_build_target(self):
        runner = object.__new__(IntervalRunner)
        runner.plan = interval_plan(200)
        base = ("cardano-cli", "conway", "transaction", "build-raw", "--out-file")
        with patch.object(ScenarioRunner, "execute") as execute:
            runner.execute(*base, "/work/transfer.body")
            self.assertEqual(execute.call_args.args[-4:], ("--invalid-before", "100", "--invalid-hereafter", "1200"))
            runner.execute(*base, "/work/interval-expired.body")
            self.assertEqual(execute.call_args.args, (*base, "/work/interval-expired.body"))
            with self.assertRaisesRegex(ValueError, "already supplied"):
                runner.execute(*base, "/work/transfer.body", "--invalid-before", "0")

    def test_negative_bodies_preserve_input_outputs_fee_and_reference_category(self):
        runner = object.__new__(IntervalRunner)
        runner.plan = interval_plan(200)
        runner.save = lambda *_: None
        with tempfile.TemporaryDirectory() as directory:
            runner.out = Path(directory)
            selection = {"input": "a#0", "destination": "dest", "amount": 10000000,
                         "changeAddress": "source", "change": 20000000, "fee": 200000}
            (runner.out / "transfer-selection.md").write_text(json.dumps(selection))
            with patch.object(runner, "execute") as execute, patch.object(runner, "reject", return_value={}) as reject:
                runner.before_submit()
            builds = [call.args for call in execute.call_args_list if "build-raw" in call.args]
            self.assertEqual(len(builds), 2)
            for args in builds:
                for expected in ("a#0", "dest+10000000", "source+20000000", "200000"):
                    self.assertIn(expected, args)
            self.assertNotIn("--invalid-before", builds[0])
            self.assertNotIn("--invalid-hereafter", builds[1])
            self.assertEqual([call.args[2:] for call in reject.call_args_list],
                             [("OutsideValidityIntervalUTxO", False)] * 2)

    def test_unexpected_negative_prevents_later_submission(self):
        runner = object.__new__(IntervalRunner)
        runner.plan = interval_plan(20)
        with tempfile.TemporaryDirectory() as directory:
            runner.out = Path(directory)
            (runner.out / "transfer-selection.md").write_text(json.dumps({"input": "x#0",
                "destination": "d", "amount": 10, "changeAddress": "c", "change": 20, "fee": 1}))
            with patch.object(runner, "execute") as execute, patch.object(runner, "reject", side_effect=ValueError("unrecognized")):
                with self.assertRaisesRegex(ValueError, "unrecognized"): runner.before_submit()
            self.assertEqual(len(execute.call_args_list), 2)

    def test_inclusion_receipt_requires_actual_slot_bounds_identity_and_outer_profile(self):
        plan = interval_plan(20)
        transfer = {"profile": PROFILE, "transactionId": "a", "validityIntervalChecked": True}
        row = {"record": "transaction-validity-interval", "profile": PROFILE,
               "slotSource": "containing-block", "slot": 25, "lower": 0, "upper": 1020,
               "satisfied": True, "fullLedgerValidated": False, "transactionId": "a"}
        self.assertEqual(check_inclusion_report([row], plan, transfer), row)
        for field, value in (("slot", 1020), ("slot", True), ("slotSource", "post-snapshot"),
                             ("lower", 1), ("upper", 1021), ("profile", "legacy"),
                             ("transactionId", "b"), ("fullLedgerValidated", True)):
            changed = dict(row, **{field: value})
            with self.assertRaises(ValueError): check_inclusion_report([changed], plan, transfer)
        for rows in ([], [row, row]):
            with self.assertRaises(ValueError): check_inclusion_report(rows, plan, transfer)
        with self.assertRaises(ValueError): check_inclusion_report([row], plan, dict(transfer, profile="legacy"))

    def test_interval_route_inherits_relay_only_submission_and_forbids_producer(self):
        runner = object.__new__(IntervalRunner)
        records = {}
        runner.save = lambda name, value: records.update({name: value})
        with patch.object(runner, "execute") as execute:
            runner.submit_producer()
        execute.assert_not_called()
        self.assertIn("No direct producer submission", records["submission-route.md"])
        with self.assertRaisesRegex(ValueError, "forbids producer"):
            RelayRunner.execute(runner, "cardano-cli", "conway", "transaction", "submit",
                                "--tx-file", "/work/transfer.signed", "--socket-path",
                                "/work/env/socket/node1/sock")

    def test_interval_path_disables_wrong_key_and_post_inclusion_scenarios(self):
        runner = object.__new__(IntervalRunner)
        runner.plan = interval_plan(20)
        runner.save = lambda *_: None
        with tempfile.TemporaryDirectory() as directory:
            runner.out = Path(directory)
            (runner.out / "scala-transfer.md").write_text("{}")
            with patch.object(RelayRunner, "scala", return_value={"transfer": {}}) as positive, \
                 patch("private_cluster_interval.check_inclusion_report", return_value={}), \
                 patch.object(ScenarioRunner, "scala") as unrelated:
                runner.scala()
            positive.assert_called_once()
            unrelated.assert_not_called()
            self.assertTrue(runner.negative_started)


if __name__ == "__main__": unittest.main()
