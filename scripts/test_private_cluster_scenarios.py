# SPDX-License-Identifier: Apache-2.0
"""Scripted process responses exercise production adapters; no live validity claim."""
import copy
import hashlib
import json
import subprocess
import tempfile
import time
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
        self.assertEqual(report["transactionCborSha256"], hashlib.sha256(bytes.fromhex("deadbeef")).hexdigest())
        self.assertEqual(report["submissionEvidence"], "scenario-submission-0.md")
        self.assertTrue(r.records[report["submissionEvidence"]]["transactionFileUnchanged"])

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
            def positive():
                r.producers("STOP")
                r.snapshot("post")
                r.producers("CONT")
                return {}
            with patch.object(TransferRunner, "scala", side_effect=positive), \
                 patch.object(TransferRunner, "snapshot", return_value=state()), \
                 patch.object(TransferRunner, "producers") as producers, \
                 patch.object(r, "pause_evidence"), \
                 patch.object(r, "reject", side_effect=KeyboardInterrupt()):
                del r.snapshot
                with self.assertRaises(KeyboardInterrupt): r.scala()
            self.assertEqual([c.args for c in producers.call_args_list], [("STOP",), ("CONT",)])
            self.assertFalse(r.hold_post_pause)

    def test_post_snapshot_remains_paused_through_both_negatives(self):
        r = self.runner()
        del r.snapshot
        physical = []
        observed = []
        def positive():
            r.producers("STOP")
            r.producers("CONT")  # Initial submission window must resume for inclusion.
            r.producers("STOP")
            r.snapshot("post")
            r.producers("CONT")  # Inherited finally; must be deferred.
            return {}
        def reject(label, *args):
            self.assertTrue(r.hold_post_pause)
            self.assertEqual(physical, ["STOP", "CONT", "STOP"])
            observed.append(label)
            return {"transactionId": label}
        with tempfile.TemporaryDirectory() as directory:
            r.out = Path(directory)
            (r.out / "transfer-selection.md").write_text(json.dumps({"input": "spent#0",
                "destination": "destination", "amount": 10, "changeAddress": "change", "change": 20, "fee": 2}))
            with patch.object(TransferRunner, "scala", side_effect=positive), \
                 patch.object(TransferRunner, "snapshot", return_value=state()), \
                 patch.object(TransferRunner, "producers", side_effect=physical.append), \
                 patch.object(r, "pause_evidence"), patch.object(r, "execute"), \
                 patch.object(r, "reject", side_effect=reject):
                r.scala()
        self.assertEqual(observed, ["repeated-included", "conflicting-spend"])
        self.assertEqual(physical, ["STOP", "CONT", "STOP", "CONT"])

    def test_positive_failure_after_post_snapshot_still_releases_pause(self):
        r = self.runner()
        del r.snapshot
        def positive():
            r.producers("STOP")
            r.snapshot("post")
            r.producers("CONT")
            raise ValueError("positive comparison failed")
        with patch.object(TransferRunner, "scala", side_effect=positive), \
             patch.object(TransferRunner, "snapshot", return_value=state()), \
             patch.object(TransferRunner, "producers") as producers:
            with self.assertRaisesRegex(ValueError, "positive comparison"):
                r.scala()
        self.assertEqual([c.args for c in producers.call_args_list], [("STOP",), ("CONT",)])

    def test_transaction_mutation_during_submission_preserves_receipt_and_fails(self):
        r = self.runner()
        with patch.object(r, "transaction_bytes", side_effect=[b"before", b"after"]), \
             patch.object(TransferRunner, "execute", side_effect=self.process()):
            with self.assertRaisesRegex(ValueError, "file changed"):
                r.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", "/work/negative.signed")
        receipt = r.records["scenario-submission-0.md"]
        self.assertFalse(receipt["transactionFileUnchanged"])
        self.assertEqual(receipt["transactionCborSha256"], hashlib.sha256(b"before").hexdigest())
        self.assertEqual(receipt["returncode"], 1)

    def test_post_submit_read_failure_retains_completed_process_result(self):
        r = self.runner()
        with patch.object(r, "transaction_bytes", side_effect=[b"before", OSError("read failed")]), \
             patch.object(TransferRunner, "execute", side_effect=self.process()):
            with self.assertRaises(OSError):
                r.execute("cardano-cli", "conway", "transaction", "submit", "--tx-file", "/work/negative.signed")
        receipt = r.records["scenario-submission-0.md"]
        self.assertEqual(receipt["returncode"], 1)
        self.assertEqual(receipt["stderr"], "BadInputsUTxO")
        self.assertEqual(receipt["transactionCborSha256"], hashlib.sha256(b"before").hexdigest())

    def test_saved_bytes_must_match_the_actual_submission_digest(self):
        r = self.runner()
        with patch.object(r, "transaction_bytes", side_effect=[b"saved", b"submitted", b"submitted"]), \
             patch.object(TransferRunner, "execute", side_effect=self.process()):
            with self.assertRaisesRegex(ValueError, "saved transaction differs"):
                r.reject("conflict", "/work/conflict.signed", "BadInputsUTxO", False)
        self.assertFalse(r.records["conflict-result.md"]["passed"])


class LifecycleScenarios(unittest.TestCase):
    def test_actual_transfer_method_keeps_post_pause_until_both_negatives_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            r = ScenarioRunner(SimpleNamespace(output=directory + "/evidence", scala_repo=directory))
            r.deadline = time.monotonic() + 360
            physical = []
            phases = []
            params = {"protocolVersion": {"major": 9, "minor": 0}, "txFeePerByte": 1,
                      "txFeeFixed": 0, "maxTxSize": 16384}
            def snapshot(label):
                phases.append((label, tuple(physical)))
                post = label == "post" or label.startswith(("repeated", "conflicting"))
                tip = {"hash": "b" * 64 if post else "c" * 64, "slot": 2 if post else 1,
                       "epoch": 0, "era": "Conway", "slotInEpoch": 2}
                utxo = {TXID + "#0": {"address": "destination", "value": {"lovelace": 10000000}}} if post else {
                    "d" * 64 + "#0": {"address": "source", "value": {"lovelace": 50000000}}}
                ledger = json.dumps({"stateBefore": {"esLState": {"utxoState": {"fees": 200000 if post else 0}}}})
                outputs = {"utxo": json.dumps(utxo), "parameters": json.dumps(params), "ledger-state": ledger}
                r.save(label + "-tips.md", json.dumps([tip, tip]))
                r.save(label + "-ledger-state.md", ledger)
                return tip, outputs
            def read(path):
                if path == "logs/node3/stdout.log":
                    return "shelleyKESSource = Nothing shelleyVRFFile = Nothing\n"
                if path == "shelley-genesis.json": return "{}"
                if path == "node-data/node3/port": return "3003"
                raise AssertionError(path)
            def execute(*args, **kwargs):
                result = SimpleNamespace(stdout="", stderr="", returncode=0)
                if args[0] == "cat": result.stdout = '{"cborHex":"deadbeef"}'
                elif "build" in args:
                    result.stdout = "source" if any("utxo1" in a for a in args) else "destination"
                elif "txid" in args:
                    result.stdout = "e" * 64 if "/work/scenario-conflict.signed" in args else TXID
                elif "submit" in args:
                    if "/work/scenario-wrong-key.signed" in args:
                        result.returncode, result.stderr = 1, "MissingVKeyWitnessesUTXOW"
                    elif getattr(r, "hold_post_pause", False):
                        result.returncode, result.stderr = 1, "BadInputsUTxO"
                return result
            with patch.object(Runner, "scala", return_value={"negotiated": True}), \
                 patch.object(TransferRunner, "snapshot", side_effect=snapshot), \
                 patch.object(TransferRunner, "execute", side_effect=execute), \
                 patch.object(TransferRunner, "producers", side_effect=physical.append), \
                 patch.object(r, "pause_evidence"), patch.object(r, "read", side_effect=read), \
                 patch.object(r, "query", return_value={"hash": "c" * 64}), \
                 patch.object(r, "relay_query", return_value=json.dumps({TXID + "#0": {}})), \
                 patch.object(r, "docker", return_value=SimpleNamespace(returncode=0, stderr="", stdout=json.dumps({
                     "scope": "cluster-transfer-observation", "passed": True, "transactionId": TXID}))):
                result = r.scala()
            self.assertEqual(physical, ["STOP", "CONT", "STOP", "CONT"])
            post_phases = [(label, actions) for label, actions in phases if label.startswith(("repeated", "conflicting"))]
            self.assertEqual(len(post_phases), 4)
            self.assertTrue(all(actions == ("STOP", "CONT", "STOP") for _, actions in post_phases))
            self.assertTrue(result["repeated"]["passed"])
            self.assertTrue(result["conflict"]["passed"])

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
