# SPDX-License-Identifier: Apache-2.0
"""Scripted process responses exercise production adapters; no live validity claim."""
import copy
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from private_cluster import Runner
from private_cluster_transfer import TransferRunner
from private_cluster_scenarios import ScenarioRunner, unchanged

TXID = "a" * 64


def state(present=False):
    return ({"hash": "h", "slot": 1, "epoch": 0, "era": "Conway"}, {
        "utxo": json.dumps({TXID + "#0": {"value": 5}} if present else {}),
        "parameters": "{}", "ledger-state": json.dumps({"stateBefore": {
            "esLState": {"utxoState": {"fees": 200000}}}})})


class ScenarioGuards(unittest.TestCase):
    def runner(self):
        r = object.__new__(ScenarioRunner)
        r.records = {}
        r.save = lambda name, value: r.records.update({name: value})
        r.snapshot = lambda label: state()
        r.name = "owned-test"
        return r

    def process(self, code=1, stderr="BadInputsUTxO", stdout=""):
        def execute(*args, **kwargs):
            if "txid" in args:
                return SimpleNamespace(stdout=TXID)
            if args[0] == "cat":
                return SimpleNamespace(stdout='{"cborHex":"deadbeef"}')
            return SimpleNamespace(returncode=code, stdout=stdout, stderr=stderr)
        return execute

    def test_rejection_retains_streams_state_and_explicit_scope(self):
        r = self.runner()
        with patch.object(TransferRunner, "execute", side_effect=self.process(stdout="diagnostic\n")):
            report = r.reject("conflict", "/work/conflict.signed", "BadInputsUTxO", False)
        self.assertTrue(report["recognizedLedgerRejection"])
        self.assertTrue(report["passed"])
        self.assertTrue(report["stableStateVerified"])
        self.assertEqual(report["invalidBlockRejection"], "unsupported")
        self.assertEqual(report["scalaNegativeComparison"], "unsupported")
        self.assertEqual(r.records["scenario-submission-0.md"]["stdout"], "diagnostic\n")
        self.assertEqual(r.records["scenario-submission-0.md"]["stderr"], "BadInputsUTxO")
        self.assertEqual(r.records["conflict-transaction-cbor.md"], "deadbeef")

    def test_transport_failure_or_success_with_reason_is_not_ledger_rejection(self):
        for code, message in [(1, "socket unavailable"), (0, "BadInputsUTxO")]:
            r = self.runner()
            with patch.object(TransferRunner, "execute", side_effect=self.process(code, message)):
                with self.assertRaisesRegex(ValueError, "expected ledger rejection"):
                    r.reject("conflict", "/work/conflict.signed", "BadInputsUTxO", False)
            self.assertFalse(r.records["conflict-result.md"]["recognizedLedgerRejection"])
            self.assertFalse(r.records["conflict-result.md"]["passed"])

    def test_wrong_key_uses_second_disposable_key_and_same_body(self):
        r = self.runner()
        with patch.object(r, "execute") as execute, patch.object(r, "reject") as reject:
            r.wrong_key()
        self.assertIn("/work/transfer.body", execute.call_args.args)
        self.assertIn("/work/env/utxo-keys/utxo2/utxo.skey", execute.call_args.args)
        reject.assert_called_once_with("wrong-key", "/work/scenario-wrong-key.signed",
                                       "MissingVKeyWitnessesUTXOW", False)

    def test_wrong_key_unexpected_outcome_prevents_valid_submission(self):
        r = self.runner()
        with patch.object(r, "wrong_key", side_effect=ValueError("unexpected acceptance")), \
             patch.object(TransferRunner, "execute") as execute:
            with self.assertRaisesRegex(ValueError, "unexpected acceptance"):
                r.execute("cardano-cli", "conway", "transaction", "submit",
                          "--tx-file", "/work/transfer.signed")
        execute.assert_not_called()

    def test_unexpected_inclusion_and_state_changes_fail_after_receipt(self):
        r = self.runner()
        r.snapshot = lambda label: state(True)
        with patch.object(TransferRunner, "execute", side_effect=self.process()):
            with self.assertRaisesRegex(ValueError, "output presence"):
                r.reject("conflict", "/work/conflict.signed", "BadInputsUTxO", False)
        self.assertIn("conflict-result.md", r.records)
        r.snapshot = lambda label: state(label.endswith("post"))
        with patch.object(TransferRunner, "execute", side_effect=self.process()):
            with self.assertRaisesRegex(ValueError, "state changed"):
                r.reject("conflict", "/work/conflict.signed", "BadInputsUTxO", False)

    def test_repeated_included_tx_requires_existing_outputs(self):
        r = self.runner()
        r.negative_started = True
        r.snapshot = lambda label: state(True)
        with patch.object(TransferRunner, "execute", side_effect=self.process()):
            result = r.reject("repeat", "/work/transfer.signed", "BadInputsUTxO", True)
        self.assertEqual(result["observedOutputs"], [TXID + "#0"])

    def test_exact_first_submission_hook_once_and_success_saved(self):
        r = self.runner()
        with patch.object(r, "wrong_key") as wrong, \
             patch.object(TransferRunner, "execute", side_effect=self.process(0, "", "submitted\n")):
            for _ in range(2):
                r.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", "/work/transfer.signed")
        wrong.assert_called_once()
        self.assertEqual(r.records["scenario-submission-1.md"]["returncode"], 0)

    def test_cancellation_and_timeout_preserve_process_exception(self):
        for error in [KeyboardInterrupt(), subprocess.TimeoutExpired("docker", 2)]:
            r = self.runner()
            with patch.object(TransferRunner, "execute", side_effect=error):
                with self.assertRaises(type(error)):
                    r.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", "/work/negative.signed")
            self.assertEqual(r.records["scenario-submission-0.md"]["outcome"], "process-exception")

    def test_state_comparison_checks_epoch_parameters_and_actual_fees(self):
        for field in ["epoch", "parameters", "fees"]:
            before = state()
            after = copy.deepcopy(before)
            if field == "epoch": after[0][field] = 1
            elif field == "parameters": after[1][field] = '{"fee":1}'
            else: after[1]["ledger-state"] = after[1]["ledger-state"].replace("200000", "200001")
            with self.assertRaises(ValueError): unchanged(before, after)

    def test_live_resource_profile_is_inherited_without_reduction(self):
        r = self.runner()
        with patch.object(Runner, "docker") as docker:
            r.docker("run", "-d", "--name", r.name, "--cpus=3", "--memory=6g", "--memory-swap=6g")
        self.assertIn("--cpus=3", docker.call_args.args)
        self.assertIn("--memory=6g", docker.call_args.args)
        self.assertIn("--memory-swap=6g", docker.call_args.args)

    def test_post_transfer_cancellation_resumes_producers(self):
        r = self.runner()
        with tempfile.TemporaryDirectory() as directory:
            r.out = Path(directory)
            (r.out / "transfer-selection.md").write_text("{}")
            with patch.object(TransferRunner, "scala", return_value={}), \
                 patch.object(r, "producers") as producers, \
                 patch.object(r, "reject", side_effect=KeyboardInterrupt()):
                with self.assertRaises(KeyboardInterrupt): r.scala()
            self.assertEqual([c.args for c in producers.call_args_list], [("STOP",), ("CONT",)])


class LifecycleScenarios(unittest.TestCase):
    def run_script(self, runner, cancel):
        calls = []
        tip_count = 0
        def preflight(): runner.endpoint = "unix:///var/run/docker.sock"
        def docker(*args, **kwargs):
            calls.append(args)
            return SimpleNamespace(stdout="", stderr="", returncode=0)
        def query(kind, node=1):
            nonlocal tip_count
            if kind == "protocol-parameters": return {"protocolVersion": {"major": 9, "minor": 0}}
            tip_count += 1
            if cancel and tip_count == 2: raise KeyboardInterrupt()
            return {"era": "Conway", "hash": "h", "epoch": 0 if tip_count <= 4 else 2, "block": tip_count}
        with patch.object(runner, "preflight", side_effect=preflight), \
             patch.object(runner, "docker", side_effect=docker), \
             patch.object(runner, "execute", return_value=SimpleNamespace(stdout="", stderr="")), \
             patch.object(runner, "read", return_value="{}"), \
             patch.object(runner, "write_json"), patch.object(runner, "capture"), \
             patch.object(runner, "query", side_effect=query), \
             patch("private_cluster.profile", return_value=({}, {}, [])), \
             patch("private_cluster.threading.Timer") as timer, \
             patch("private_cluster.time.sleep"), patch("builtins.print"):
            if cancel:
                with self.assertRaises(KeyboardInterrupt): runner.run()
            else: runner.run()
            timer.return_value.cancel.assert_called_once()
        self.assertIn(("rm", "-f", runner.name), calls)
        self.assertIn(("network", "rm", runner.name), calls)
        self.assertEqual(json.loads((runner.out / "cleanup.md").read_text())["remaining"],
                         {"containers": "", "networks": ""})
        return calls

    def test_cancel_then_fresh_restart_uses_distinct_owned_resources_and_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            def create(suffix):
                return ScenarioRunner(SimpleNamespace(output=directory + "/" + suffix,
                    reference_image="local", seconds=240, scala_repo=None))
            cancelled, restarted = create("cancelled"), create("restarted")
            first = self.run_script(cancelled, True)
            second = self.run_script(restarted, False)
            self.assertNotEqual(cancelled.name, restarted.name)
            self.assertNotIn(cancelled.name, [item for cmd in second for item in cmd])
            self.assertNotIn(restarted.name, [item for cmd in first for item in cmd])
            self.assertTrue((cancelled.out / "failure.md").exists())
            self.assertFalse((cancelled.out / "result.md").exists())
            self.assertTrue((restarted.out / "result.md").exists())


if __name__ == "__main__": unittest.main()
