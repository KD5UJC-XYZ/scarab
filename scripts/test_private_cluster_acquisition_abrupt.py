# SPDX-License-Identifier: Apache-2.0
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest

from private_cluster_acquisition_abrupt import AbruptAcquisitionRunner, verify_ack
from private_cluster_acquisition_restart import PROFILE


def fixture():
    context = {"upstreamSource": "a" * 64, "genesisDigest": "b" * 64, "profile": PROFILE,
               "networkMagic": 1082026, "anchor": {"slot": 1, "hash": "c" * 64}}
    field = lambda b: struct.pack(">i", len(b)) + b
    payload = b"".join(field(str(s).encode()) for s in ("acquisition-checkpoint-v1", PROFILE, "a"*64, "b"*64))
    payload += struct.pack(">q",1082026) + field(b"1") + field(bytes.fromhex("c"*64)) + struct.pack(">qi",4,2)
    originals = [{"record":"original", "stage":"published", "index":i, "headerEnvelopeHex":"01", "blockHex":"02"} for i in range(2)]
    payload += (field(b"\x01") + field(b"\x02"))*2
    digest = hashlib.sha256(payload).hexdigest()
    report = {**context, "scope":"bounded-acquisition-process-phase", "phase":"a", "complete":True,
              "loadedCount":0, "publishedCount":2, "publishedGeneration":4, "publishedRevision":digest,
              "ledgerValidated":False, "consensusValidated":False, "segmentStoreReused":False}
    return context, [*originals, report, {"record":"acknowledged-hold","generation":4,"digest":digest}], payload+digest.encode()


class Scripted(AbruptAcquisitionRunner):
    def __init__(self, out, rows, context, running=True, oom=False):
        self.out, self.rows, self.phase_context = out, rows, context
        self.calls, self.receipts = [], {}
        self.running, self.oom, self.killed = running, oom, False

    def save(self, name, value): self.receipts[name] = value

    def docker(self, *args, **kwargs):
        self.calls.append(args)
        if args[0] == "logs": output = "\n".join(json.dumps(r) for r in self.rows)
        elif args[0] == "inspect": output = json.dumps([{"Id":"owned-immutable-id", "State":{
            "Running":self.running and not self.killed, "Pid":101 if not self.killed else 0, "OOMKilled":self.oom}}])
        elif args[0] == "kill":
            assert "acknowledgement-before-kill.md" in self.receipts
            assert args == ("kill","--signal=KILL","owned-immutable-id")
            self.killed = True
            output = "owned-immutable-id"
        elif args[0] == "wait": output = "137"
        else: raise AssertionError(args)
        return SimpleNamespace(stdout=output, stderr="")


class AbruptGuards(unittest.TestCase):
    def test_ack_binds_originals_revision_and_independent_context(self):
        context, rows, raw = fixture()
        self.assertEqual(verify_ack(rows, raw, context)["generation"], 4)
        for change in ("context", "raw", "original", "revision", "duplicate", "incomplete"):
            c, r, b = copy.deepcopy(context), copy.deepcopy(rows), raw
            if change == "context": c["networkMagic"] += 1
            elif change == "raw": b = b[:-1] + b"0"
            elif change == "original": r[0]["blockHex"] = "03"
            elif change == "revision": r[-1]["generation"] += 1
            elif change == "duplicate": r.append(r[-1])
            else: r[-2]["complete"] = False
            with self.subTest(change=change), self.assertRaises(ValueError): verify_ack(r,b,c)

    def test_controller_verifies_before_immutable_kill_and_records_exit(self):
        context, rows, raw = fixture()
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            (out/"acquisition/state").mkdir(parents=True)
            (out/"acquisition/state/checkpoint.bin").write_bytes(raw)
            runner = Scripted(out, rows, context)
            self.assertEqual(runner.await_phase("owned-immutable-id",["a-hold"],"process-a"),"137")
            self.assertTrue(runner.receipts["acknowledged-kill.md"]["checkpointUnchanged"])
            for running, oom in ((False,False),(True,True)):
                runner = Scripted(out, rows, context, running, oom)
                with self.assertRaises(ValueError): runner.await_phase("owned-immutable-id",["a-hold"],"a")
                if not running: self.assertFalse(runner.killed)
            (out/"acquisition/state/checkpoint.bin").write_bytes(b"corrupt")
            runner = Scripted(out, rows, context)
            with self.assertRaises(ValueError): runner.await_phase("owned-immutable-id",["a-hold"],"a")
            self.assertFalse(runner.killed)


if __name__ == "__main__": unittest.main()
