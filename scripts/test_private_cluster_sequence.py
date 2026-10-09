# SPDX-License-Identifier: Apache-2.0
import inspect
import time
import types
import unittest
from unittest.mock import patch
from private_cluster import Runner
from private_cluster_sequence import (SequenceRunner, checked_window, checked_report,
    workload_budget, pair_admissions, PRE_PINS, ORACLE_PINS)


class SequenceLauncherTests(unittest.TestCase):
    def tip(self, slot, block):
        return {"era": "Conway", "epoch": slot//500, "slot": slot,
                "slotInEpoch": slot%500, "block": block}

    def report(self):
        return dict(scope="coherent-sequence-observation", capturedBlocks=3, emptyBlocks=2,
            transactionCount=2, passed=True, scopedSuccess=True, referencePostStateMatched=True,
            wholeTupleRollbackReapply=True, transactionGroupingVerified=True,
            fullLedgerValidated=False, consensusValidated=False, prefixReferenceCompared=False,
            endpointEqualityProvesContinuity=False, finalReferenceCompared=True, preStateSupplied=True,
            finalCountersMatched=True, fiveNonceFieldsMatched=True, stakeRegistrationEndpointsEqual=True,
            parameterEndpointsEqual=True, internalInvariantPrefixes=4, initialRevision=0,
            appliedRevision=3, finalReplayRevision=15, submissionIndexesInBlockOrder=[1,0])

    def test_complete_same_epoch_sequence_bound(self):
        pre = self.tip(1020, 10)
        self.assertEqual(checked_window(pre, self.tip(1300,18))["expectedCompleteBlocks"], 8)
        for post in (self.tip(1520,13), self.tip(1020,12), self.tip(1421,13),
                     self.tip(1300,19), self.tip(1030,11)):
            with self.assertRaises(ValueError): checked_window(pre,post)
        self.assertEqual(checked_window(pre,self.tip(1030,11),minimum=1)["expectedCompleteBlocks"],1)

    def test_window_types_and_geometry(self):
        for change in ({"slot": True},{"slotInEpoch": True},{"epoch": 3},{"era":"Babbage"}):
            with self.assertRaises(ValueError):
                checked_window(dict(self.tip(1020,10),**change),self.tip(1100,13))

    def test_report_requires_actual_grouping_empty_and_exact_pair(self):
        self.assertEqual(checked_report(self.report(),3)["transactionCount"],2)
        for change in ({"transactionGroupingVerified":False},{"emptyBlocks":0},{"emptyBlocks":True},
                       {"transactionCount":3},{"capturedBlocks":4},{"fullLedgerValidated":True},
                       {"wholeTupleRollbackReapply":False},{"referencePostStateMatched":False},
                       {"prefixReferenceCompared":True},{"internalInvariantPrefixes":3},
                       {"finalReplayRevision":3},{"submissionIndexesInBlockOrder":[0,0]},
                       {"endpointEqualityProvesContinuity":True}):
            with self.assertRaises(ValueError): checked_report(dict(self.report(),**change),3)

    def test_each_transaction_requires_relay_and_producer_admission(self):
        ids = ["a"*64,"b"*64]
        def admitted(txid): return {"ns":"Mempool.AddedTx","data":{"tx":{"txid":txid}}}
        evidence = {"1":[admitted(ids[0])],"2":[admitted(ids[1][:8])],
                    "3":[admitted(txid) for txid in ids]}
        self.assertEqual(set(pair_admissions(evidence,ids)),set(ids))
        with self.assertRaises(ValueError):
            pair_admissions(evidence,["a"*64,"a"*8+"b"*56])
        for change in ({"2":[]},{"3":[admitted(ids[0])]}):
            with self.assertRaises(ValueError): pair_admissions(dict(evidence,**change),ids)

    def test_manifest_exact_sources_exclude_post_context(self):
        self.assertEqual(len(PRE_PINS),7)
        self.assertEqual(len(ORACLE_PINS),8)
        self.assertFalse(any(n.startswith("post-") for n in PRE_PINS.values()))
        self.assertEqual(ORACLE_PINS["captureSha256"],"scala-sequence-capture.md")
        self.assertEqual(ORACLE_PINS["transaction1Sha256"],"signed-transaction-1-cbor.md")

    def test_budget_and_inherited_owned_cleanup_resource_limits(self):
        for value in (419,541,True):
            with self.assertRaises(ValueError): workload_budget(value)
        self.assertEqual(workload_budget(540),540)
        self.assertIs(SequenceRunner.run,Runner.run)
        self.assertIs(SequenceRunner.cleanup,Runner.cleanup)
        self.assertIn('started + 600',inspect.getsource(Runner.run))
        self.assertIn('"--cpus=3", "--memory=6g"',inspect.getsource(Runner.run))

    def fake(self):
        runner = object.__new__(SequenceRunner)
        runner.deadline = time.monotonic()+100
        runner.calls = []
        runner.saved = {}
        runner.producers = lambda action: runner.calls.append(action)
        runner.save = lambda name,value: runner.saved.update({name:value})
        return runner

    def test_pause_resumes_and_restores_deadline_on_every_failure(self):
        for error in (ValueError("ordinary"),KeyboardInterrupt()):
            runner=self.fake(); old=runner.deadline
            with self.assertRaises(type(error)):
                with runner.paused("submission"):
                    self.assertLess(runner.deadline,time.monotonic()+9)
                    raise error
            self.assertEqual(runner.calls,["STOP","CONT"])
            self.assertEqual(runner.deadline,old)
            self.assertTrue(runner.saved["submission-pause-timing.md"]["resumeAttempted"])
        runner=self.fake()
        def partial_stop(action):
            runner.calls.append(action)
            if action=="STOP": raise ValueError("partial producer pause")
        runner.producers=partial_stop
        with self.assertRaises(ValueError):
            with runner.paused("pre"): pass
        self.assertEqual(runner.calls,["STOP","CONT"])

    def test_observer_is_bounded_and_preserves_stderr_separately(self):
        runner=self.fake(); runner.name="owned"; runner.out="/evidence"
        runner.args=types.SimpleNamespace(scala_repo="/repo")
        calls=[]
        runner.docker=lambda *args,**kw: (calls.append((args,kw)) or
            types.SimpleNamespace(stdout='{"passed":true}\n',stderr="diagnostic",returncode=0))
        self.assertEqual(runner.observer("observe /evidence /evidence","none","receipt.md"),[{"passed":True}])
        args,kw=calls[0]
        for flag in ("--cpus=1","--memory=1g","--memory-swap=1g","--network=none","--read-only"):
            self.assertIn(flag,args)
        self.assertEqual(kw["timeout"],65)
        self.assertEqual(runner.saved["receipt.md.stderr.md"],"diagnostic")

    def test_producer_submission_rejected_before_any_docker(self):
        runner=self.fake()
        runner.docker=lambda *args,**kwargs: self.fail("must reject before Docker")
        with self.assertRaises(ValueError):
            runner.execute("cardano-cli","conway","transaction","submit","--socket-path",
                           "/work/env/socket/node1/sock")


if __name__ == "__main__": unittest.main()
