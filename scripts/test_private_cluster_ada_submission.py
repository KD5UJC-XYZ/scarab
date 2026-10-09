#!/usr/bin/env python3
"""Pure ADA controller tests: no Docker, keys, networking or cluster execution."""
from types import SimpleNamespace
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import private_cluster_ada_submission as ada


class ConfigurationTests(unittest.TestCase):
    def test_lifecycle_preserves_original_before_base_starts(self):
        class FakeBase:
            def lifecycle(self, approval):
                config = ada.base.decode((self.environment / "configuration.yaml").read_bytes())
                if config["TxSubmissionInitDelay"] != 0:
                    raise AssertionError("override must precede base lifecycle")
                return approval
        with patch.object(ada.base, "controller_type", return_value=FakeBase):
            controller = ada.controller_type(SimpleNamespace())()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            controller.args = SimpleNamespace(owned_root=root)
            controller.environment = root / "environment"
            controller.environment.mkdir()
            controller.out = root / "private-evidence"
            controller.out.mkdir()
            controller.active = {}
            original = b'{"EnableP2P":true,"TxSubmissionInitDelay":60}\n'
            (controller.environment / "configuration.yaml").write_bytes(original)
            self.assertEqual(controller.lifecycle("approved-fixture"), "approved-fixture")
            self.assertEqual((controller.out / "original-node-configuration.json").read_bytes(), original)
            receipt = ada.base.decode((controller.out / "txsubmission-init-delay.json").read_bytes())
            self.assertTrue(receipt["otherConfigurationValuesUnchanged"])
            self.assertEqual(receipt["configuredSeconds"], 0)

    def test_absent_default_added_and_other_values_preserved(self):
        original = b'{"EnableP2P":true,"nested":{"xs":[1,null,"text"]},"NetworkMagic":1082026}'
        raw, receipt = ada.immediate_tx_submission(original)
        expected = ada.base.decode(original)
        expected["TxSubmissionInitDelay"] = 0
        self.assertEqual(ada.base.decode(raw), expected)
        self.assertFalse(receipt["originalKeyPresent"])
        self.assertEqual(receipt["beforeSHA256"], ada.hashlib.sha256(original).hexdigest())
        self.assertEqual(receipt["afterSHA256"], ada.hashlib.sha256(raw).hexdigest())
        self.assertEqual(receipt["defaultSeconds"], 60)
        self.assertEqual(receipt["configuredSeconds"], 0)

    def test_explicit_numeric_default_replaced(self):
        for value in (60, 60.0):
            raw, receipt = ada.immediate_tx_submission(ada.base.json.dumps(dict(TxSubmissionInitDelay=value)).encode())
            self.assertEqual(ada.base.decode(raw), dict(TxSubmissionInitDelay=0))
            self.assertTrue(receipt["originalKeyPresent"])

    def test_nondefault_or_wrong_type_refused(self):
        for value in (-1, 0, 5, 59.9, 61, True, False, None, "60", {}, []):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "expected absent or default"):
                ada.immediate_tx_submission(ada.base.json.dumps(dict(TxSubmissionInitDelay=value)).encode())

    def test_invalid_ambiguous_or_unbounded_json_refused(self):
        for raw in (b'[]', b'null', b'{"TxSubmissionInitDelay":60,"TxSubmissionInitDelay":60}',
                    b'{"TxSubmissionInitDelay":NaN}', b'x' * (4 * 1024 * 1024 + 1)):
            with self.subTest(length=len(raw)), self.assertRaises(ValueError):
                ada.immediate_tx_submission(raw)


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.addresses = ["addr_test1" + "a" * 40, "addr_test1" + "b" * 40]
        self.txin = "ab" * 32 + "#0"
        self.utxo = {self.txin: {"address": self.addresses[0], "value": {"lovelace": 50000000}}}

    def test_conserves_input_and_fee(self):
        selected = ada.select_transfer(self.utxo, self.addresses)
        self.assertEqual(selected["amount"] + selected["change"] + selected["fee"], 50000000)
        self.assertEqual(selected["input"], self.txin)

    def test_rejects_multiasset_source(self):
        self.utxo[self.txin]["value"]["asset"] = 1
        with self.assertRaisesRegex(ValueError, "ADA-only"):
            ada.select_transfer(self.utxo, self.addresses)

    def test_rejects_ambiguous_source(self):
        self.utxo["cd" * 32 + "#0"] = self.utxo[self.txin]
        with self.assertRaisesRegex(ValueError, "exactly one"):
            ada.select_transfer(self.utxo, self.addresses)

    def test_rejects_unfunded_source(self):
        self.utxo[self.txin]["value"]["lovelace"] = 10000000
        with self.assertRaisesRegex(ValueError, "sufficient"):
            ada.select_transfer(self.utxo, self.addresses)

    def test_rejects_mainnet_address(self):
        with self.assertRaisesRegex(ValueError, "test addresses"):
            ada.select_transfer(self.utxo, ["addr1" + "a" * 40, self.addresses[1]])

    def test_rejects_bool_coin_and_injected_input(self):
        self.utxo[self.txin]["value"]["lovelace"] = True
        with self.assertRaisesRegex(ValueError, "source coin"):
            ada.select_transfer(self.utxo, self.addresses)
        self.utxo["unsafe input"] = self.utxo.pop(self.txin)
        with self.assertRaisesRegex(ValueError, "input identity"):
            ada.select_transfer(self.utxo, self.addresses)

    def test_signed_bytes_preserved(self):
        self.assertEqual(ada.signed_bytes({"cborHex": "84A0a0f5f6"}), bytes.fromhex("84a0a0f5f6"))

    def test_signed_byte_limits(self):
        for raw in ("", "f", "gg", "00" * 65537, None):
            with self.subTest(raw_type=type(raw)), self.assertRaises(ValueError):
                ada.signed_bytes({"cborHex": raw})

    def test_client_receipt_requires_inclusion_and_exact_id(self):
        descriptor = dict(transactionId="ab" * 32)
        receipt = dict(schema="ada-submission-client-result-v1", passed=True, accepted=True,
                       included=True, ingress="scala-http", transactionId=descriptor["transactionId"])
        self.assertEqual(ada.client_result(receipt, descriptor), receipt)
        for key, value in (("included", False), ("accepted", False), ("transactionId", "cd" * 32),
                           ("ingress", "reference-cli")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                ada.client_result(dict(receipt, **{key: value}), descriptor)


class ControllerTests(unittest.TestCase):
    def controller(self):
        class FakeBase:
            def execute(self, *args, **kwargs):
                return args
            def create(self, phase, tail):
                return phase, tail
            def wait_file(self, name, seconds):
                return self.readiness[name]
        with patch.object(ada.base, "controller_type", return_value=FakeBase):
            return ada.controller_type(SimpleNamespace(process=SimpleNamespace(PORTS={1:5301, 2:5302})))()

    def test_reference_cli_submit_never_delegated(self):
        controller = self.controller()
        for cli in ("cardano-cli", "/opt/reference/bin/cardano-cli"):
            with self.assertRaisesRegex(ValueError, "submission forbidden"):
                controller.execute(cli, "conway", "transaction", "submit", "--tx-file", "x")
        self.assertEqual(controller.execute("cardano-cli", "conway", "transaction", "sign")[-1], "sign")

    def test_main_replacement_only_scala_role(self):
        controller = self.controller()
        self.assertEqual(controller.create("scala", ["lab.NativeLiveBoundaryMain"]),
                         ("scala", ["lab.AdaSubmissionMain"]))
        self.assertEqual(controller.create("projection", ["lab.NativeLiveBoundaryMain"]),
                         ("projection", ["lab.NativeLiveBoundaryMain"]))

    def test_cleanup_recovers_client_create_with_lost_reply(self):
        class FakeBase:
            pass
        live = SimpleNamespace(LABEL="invocation")
        with patch.object(ada.base, "controller_type", return_value=FakeBase):
            controller = ada.controller_type(live)()
        controller.token = "owner"
        controller.containers = {}
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        controller.out = Path(temporary.name)
        controller.diagnostic_write = lambda *args: None
        cid = "ab" * 32
        calls = []
        present = [cid]
        def docker(*args, **kwargs):
            calls.append(args)
            if args[0] == "rm":
                present.clear()
            return SimpleNamespace(stdout="\n".join(present) if args[0] == "ps" else "client failure details", stderr="")
        controller.docker = docker
        controller.owned = lambda observed: self.assertEqual(observed, cid)
        controller.remove_client()
        self.assertIn(("rm", "--force", cid), calls)
        self.assertFalse(present)
        self.assertEqual((controller.out / "client.log").read_text(), "client failure details")
        receipt = ada.base.decode((controller.out / "client-cleanup.json").read_bytes())
        self.assertTrue(receipt["absenceVerified"])
        self.assertEqual(receipt["outcome"], "aborted")
        self.assertFalse(receipt["removedBeforeEndpointCapture"])
        log_index = next(i for i, call in enumerate(calls) if call[0] == "logs")
        remove_index = next(i for i, call in enumerate(calls) if call[0] == "rm")
        self.assertLess(log_index, remove_index)

    def test_cleanup_survives_log_failure_and_records_success(self):
        class FakeBase:
            pass
        with patch.object(ada.base, "controller_type", return_value=FakeBase):
            controller = ada.controller_type(SimpleNamespace(LABEL="invocation"))()
        cid = "ab" * 32
        controller.token = "owner"
        controller.containers = {"ada-client": cid}
        controller.client_completed = True
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        controller.out = Path(temporary.name)
        errors = []
        controller.diagnostic_write = lambda *args: errors.append(args)
        present = [cid]
        def docker(*args, **kwargs):
            if args[0] == "logs":
                raise TimeoutError("bounded diagnostic failure")
            if args[0] == "rm":
                present.clear()
            return SimpleNamespace(stdout="\n".join(present) if args[0] == "ps" else "")
        controller.docker = docker
        controller.owned = lambda actual: self.assertEqual(actual, cid)
        controller.remove_client()
        receipt = ada.base.decode((controller.out / "client-cleanup.json").read_bytes())
        self.assertEqual(receipt["outcome"], "completed")
        self.assertTrue(receipt["removedBeforeEndpointCapture"])
        self.assertTrue(errors)
        controller.remove_client()  # repeat cleanup retains the original receipt
        self.assertEqual(ada.base.decode((controller.out / "client-cleanup.json").read_bytes()), receipt)

    def test_cleanup_refuses_different_client_identity(self):
        class FakeBase:
            pass
        with patch.object(ada.base, "controller_type", return_value=FakeBase):
            controller = ada.controller_type(SimpleNamespace(LABEL="invocation"))()
        controller.token = "owner"
        controller.containers = {"ada-client": "ab" * 32}
        controller.docker = lambda *args: SimpleNamespace(stdout="cd" * 32)
        with self.assertRaisesRegex(ValueError, "immutable identity"):
            controller.remove_client()

    def test_bootstrap_rejects_invalid_or_node_port(self):
        controller = self.controller()
        for port in (True, 0, 65536, 5301, 5302):
            controller.readiness = {"bootstrap-ready.json": dict(apiPort=port)}
            with self.subTest(port=port), self.assertRaises(ValueError):
                controller.wait_file("bootstrap-ready.json", 1)
        controller.readiness = {"bootstrap-ready.json": dict(apiPort=8080)}
        controller.wait_file("bootstrap-ready.json", 1)
        self.assertEqual(controller.api_port, 8080)

    def test_final_result_requires_original_span_agreement(self):
        controller = self.controller()
        controller.transfer = dict(transactionId="ab" * 32)
        controller.client_receipt = dict(includedBodySHA256="cd" * 32, includedWitnessesSHA256="ef" * 32)
        receipt = dict(adaSubmittedViaHttp=True, adaIncluded=True, transactionId="ab" * 32,
                       **controller.client_receipt)
        controller.readiness = {"result.json": receipt}
        self.assertEqual(controller.wait_file("result.json", 1), receipt)
        controller.readiness = {"result.json": dict(receipt, includedWitnessesSHA256="00" * 32)}
        with self.assertRaisesRegex(ValueError, "span binding"):
            controller.wait_file("result.json", 1)


if __name__ == "__main__":
    unittest.main()
