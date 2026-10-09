# SPDX-License-Identifier: Apache-2.0
import hashlib
import copy
import json
import unittest
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace
from private_cluster_native import checked_funding, checked_diagnosis, NativeRunner
from private_cluster_transfer import TransferRunner

class NativeTests(unittest.TestCase):
    def states(self):
        source = "a" * 64 + "#0"; txid = "b" * 64
        old = {source: {"address": "key", "value": {"lovelace": 50000000}},
               "untouched": {"address": "other", "value": {"lovelace": 7}}}
        new = {"untouched": old["untouched"], txid + "#0": {"address": "script", "value": {"lovelace": 20000000}},
               txid + "#1": {"address": "key", "value": {"lovelace": 29800000}}}
        def snap(u, fee):
            return ({}, {"utxo": json.dumps(u), "ledger-state": json.dumps({"stateBefore": {"esLState": {"utxoState": {"fees": fee}}}})})
        return snap(old, 0), snap(new, 200000), source, txid

    def test_funding_is_complete_and_separate_from_scala_validation(self):
        a,b,source,txid = self.states()
        receipt = checked_funding(a,b,source,txid,"script","key",20000000,200000)
        self.assertTrue(receipt["completeReferenceStateChecked"])
        self.assertFalse(receipt["scalaFundingValidated"])

    def test_changed_or_missing_unrelated_funding_output_rejects(self):
        for mutation in (lambda u: u.pop("untouched"), lambda u: u["untouched"]["value"].update(lovelace=8),
                         lambda u: u.update(extra={"address":"extra","value":{"lovelace":1}})):
            a,b,source,txid = self.states(); u=json.loads(b[1]["utxo"]); mutation(u); b[1]["utxo"]=json.dumps(u)
            with self.assertRaises(ValueError): checked_funding(a,b,source,txid,"script","key",20000000,200000)

    def test_wrong_funded_value_and_fee_reject(self):
        for amount, fee in ((19999999,200000),(20000000,199999)):
            a,b,source,txid = self.states()
            with self.assertRaises(ValueError): checked_funding(a,b,source,txid,"script","key",amount,fee)

    def test_explicit_hooks_preserve_legacy_command(self):
        self.assertEqual(TransferRunner.comparison_command,"cluster-transfer")
        self.assertEqual(NativeRunner.comparison_command,"native-spending observe")

    def test_diagnosis_binds_exact_witness_bytes_and_typed_rejection(self):
        raw = b"exact complete transaction"
        row = {"scope":"native-spending-diagnostic", "outcome":"Rejected", "passed":False,
               "predicate":"MissingScripts", "originalTransactionSha256":hashlib.sha256(raw).hexdigest(),
               "fullLedgerValidated":False}
        self.assertEqual(checked_diagnosis(row,"MissingScripts",raw), row)
        for key, value in (("scope","other"),("outcome","InternalFailure"),("passed",True),
                           ("predicate","FailedScripts"),("originalTransactionSha256","0"*64),("fullLedgerValidated",True)):
            with self.assertRaises(ValueError): checked_diagnosis(dict(row,**{key:value}),"MissingScripts",raw)
        with self.assertRaises(ValueError): checked_diagnosis(row,"MissingScripts",raw+b"changed witness")

    def test_each_negative_resumes_before_offline_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            r = object.__new__(NativeRunner); r.out=Path(directory); r.deadline=time.monotonic()+200
            paused=False; calls=[]
            def producers(action):
                nonlocal paused
                paused=action=="STOP"; calls.append(action)
            r.producers=producers; r.read=lambda path: "{}"
            r.save=lambda name,value: (r.out/name).write_text(value if isinstance(value,str) else json.dumps(value))
            def snapshot(label):
                self.assertTrue(paused)
                values={"utxo":"{}","utxo-cbor":"a0","parameters":"{}", "ledger-state":json.dumps({"stateBefore":{"esLState":{"utxoState":{"fees":0}}}})}
                for key,value in values.items(): r.save(label+"-"+key+".md",value)
                r.save(label+"-tips.md","[]")
                return ({"hash":"a"*64,"slot":10,"epoch":0,"era":"Conway"},values)
            r.snapshot=snapshot; r.build_spend=lambda *args: None; r.sign=lambda *args: None
            r.transaction_bytes=lambda path: b"original"
            def execute(*args,**kwargs):
                if "txid" in args: return SimpleNamespace(stdout="b"*64)
                r.last_submission={"digest":hashlib.sha256(b"original").hexdigest(),"evidence":"submission.md"}
                return SimpleNamespace(returncode=1,stdout="",stderr="MissingScriptWitnessesUTXOW ScriptWitnessNotValidatingUTXOW")
            r.execute=execute
            def diagnose(*args):
                self.assertFalse(paused); calls.append("diagnose"); return {"checked":True}
            r.diagnose=diagnose; r.query=lambda *args: {"hash":"c"*64}
            r.run_negatives()
            self.assertEqual(calls,["STOP","CONT","diagnose","diagnose"]*3)
            self.assertEqual(len(list(r.out.glob("negative-*-input/native-spending-pre.md"))),3)
