# SPDX-License-Identifier: Apache-2.0
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from private_cluster import Runner
from private_cluster_nonce_epoch import checked_window, checked_receipt, NonceEpochRunner, PROFILE, KEY_MODE
from private_cluster_nonce_freeze import PINS

def tip(slot, block):
    return {"era":"Conway", "epoch":slot//500, "slot":slot, "slotInEpoch":slot%500,
            "block":block, "hash":"12"*32}

def receipt():
    report={"scope":"nonce-epoch-rotation-observation", "profile":PROFILE,
            "anchorSlot":920,"lastSlot":1055,"capturedBlocks":5}
    for key in ("passed", "epochTickChecked", "rollbackReapplyChecked", "fiveNonceFieldsMatched",
                "finalCountersMatched", "previousEpochNonceKnown", "suppliedRegistrationKeysOnly"):
        report[key]=True
    for key in ("leaderEligibilityChecked", "stateDerivedConsensus", "consensusValidated",
                "fullLedgerValidated", "crossEpochRegistrationContinuityProven",
                "certificateRegistrationAuthorityValidated", "coherentBranchPublished",
                "referenceSnapshotAtomic", "authenticatedSnapshot"):
        report[key]=False
    return report

class FakeRunner(NonceEpochRunner):
    def __init__(self, out, bad_post=False):
        self.out=out;self.events=[];self.deadline=480;self.name="test-only"
        self.args=SimpleNamespace(scala_repo=Path('/isolated/build'));self.bad_post=bad_post
    def query(self, name, node=None): return tip(920,10) if node else tip(1055,15)
    def producers(self, signal): self.events.append(signal)
    def nonce_snapshot(self, label):
        self.events.append(label)
        for suffix in ('tips','protocol-state','ledger-state','parameters'):
            self.save(label+'-'+suffix+'.md','{}')
        return tip(920,10) if label=='pre' else tip(1510 if self.bad_post else 1055,15)
    def save(self, name, content):
        (self.out/name).write_text(content if isinstance(content,str) else json.dumps(content))
    def read(self, name): return '3003' if name.endswith('/port') else '{}'
    def docker(self,*args,**kwargs):
        self.events.append('docker');self.docker_args=args
        return SimpleNamespace(returncode=0,stdout=json.dumps(receipt()),stderr='')

class NonceEpochLauncherTests(unittest.TestCase):
    def test_single_rotation_bounded_endpoints(self):
        result=checked_window(tip(920,10),tip(1055,26))
        self.assertEqual(result['expectedCompleteBlocks'],16)
        self.assertEqual(result['epochBoundarySlot'],1000)
        self.assertEqual(result['keyMode'],KEY_MODE)
        self.assertTrue(result['successorRequiredInBothEpochs'])
        self.assertFalse(result['registrationContinuityProven'])

    def test_reject_same_skipped_epoch_and_outside_bounds(self):
        for pre,post in ((tip(420,10),tip(555,15)),(tip(920,10),tip(955,15)),
                         (tip(920,10),tip(1555,15)),(tip(899,10),tip(1055,15)),
                         (tip(961,10),tip(1055,15)),(tip(920,10),tip(1039,15)),
                         (tip(920,10),tip(1181,15)),(tip(920,10),tip(1055,11)),
                         (tip(920,10),tip(1055,27))):
            with self.assertRaises(ValueError): checked_window(pre,post)

    def test_strict_geometry_types(self):
        for change in ({'epoch':2},{'slotInEpoch':False},{'block':True},{'slot':True},
                       {'block':-1},{'era':'Babbage'}):
            with self.assertRaises(ValueError): checked_window(dict(tip(920,10),**change),tip(1055,15))

    def test_receipt_fails_closed_for_missing_or_inflated_claim(self):
        good=receipt()
        self.assertTrue(checked_receipt(SimpleNamespace(returncode=0,stdout=json.dumps(good)),tip(920,10),tip(1055,15))['passed'])
        for key,value in good.items():
            bad=dict(good);bad.pop(key)
            with self.assertRaises(ValueError): checked_receipt(SimpleNamespace(returncode=0,stdout=json.dumps(bad)),tip(920,10),tip(1055,15))
            if type(value) is bool:
                bad=dict(good);bad[key]=not value
                with self.assertRaises(ValueError): checked_receipt(SimpleNamespace(returncode=0,stdout=json.dumps(bad)),tip(920,10),tip(1055,15))
        with self.assertRaises(ValueError): checked_receipt(SimpleNamespace(returncode=1,stdout=json.dumps(good)),tip(920,10),tip(1055,15))

    def test_producers_resume_before_observer_and_manifest_pins_all_sources(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(Runner,'scala',return_value='handshake'), patch('private_cluster_nonce_epoch.time.monotonic',return_value=0):
            fake=FakeRunner(Path(directory));fake.scala()
            self.assertEqual(fake.events,['STOP','pre','CONT','STOP','post','CONT','docker'])
            manifest=dict(line.split('\t') for line in (fake.out/'nonce-epoch-context.md').read_text().splitlines())
            self.assertEqual(set(manifest),set(PINS)|{'format','keyMode'})
            self.assertEqual(manifest['keyMode'],KEY_MODE)
            self.assertIn('--cpus=1',fake.docker_args);self.assertIn('--memory=1g',fake.docker_args)
            self.assertIn('--read-only',fake.docker_args)

    def test_invalid_window_resumes_producers_without_observer(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(Runner,'scala',return_value='handshake'), patch('private_cluster_nonce_epoch.time.monotonic',return_value=0):
            fake=FakeRunner(Path(directory),bad_post=True)
            with self.assertRaises(ValueError): fake.scala()
            self.assertEqual(fake.events,['STOP','pre','CONT','STOP','post','CONT'])
