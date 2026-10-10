import copy
import tempfile
import unittest
from pathlib import Path
import struct
from types import SimpleNamespace
from unittest.mock import patch
import private_cluster_plutus_early_restart as restart


H = "ab"*32
OTHER = "cd"*32
JOIN = "12"*32
SOURCE = dict(hash=H, slot=10, blockNo=1)
TERMINAL = dict(hash=OTHER, slot=20, blockNo=2)


def pin(point=SOURCE, owner=H, state=H, generation=0):
    return dict(ownerId=owner, generation=generation, point=point, coherentStateId=state,
                ledgerStateId=H, environmentId=H, validationSlot=point["slot"], profileId=restart.two.fixture.PROFILE)


def wire():
    # Structurally decodable wire only; tiny opaque blobs are NOT semantic images.
    raw = bytearray(b"RPLREST1")
    def h(value=H): raw.extend(bytes.fromhex(value))
    def n(value): raw.extend(value.to_bytes(8,"big"))
    def p(value): h(value["hash"]); n(value["slot"]); n(value["blockNo"])
    def blob(): raw.extend(struct.pack(">i", 1)); raw.extend(b"\x80")
    h(); h(OTHER); n(0)
    h(restart.digest(("native-sequence-diagnostic-context-v1\n"+JOIN+"\n").encode()))
    h(JOIN); h(); p(SOURCE); p(TERMINAL); h(); n(1)
    raw.extend(struct.pack(">ii",8,1)); h(); h(); raw.extend(b"\x01"); h()
    for _ in range(4): blob()
    return bytes(raw)


def ready(restored=False, point=SOURCE, owner=H, checkpoint=H):
    return dict(schema="plutus-service-ready-v1", profileId=restart.two.fixture.PROFILE, sourceJoinId=JOIN,
        initialManifestSHA256=H, initialPoint=point, initialEpoch=0,
        boundedRestart=dict(scope=restart.SCOPE, startupRestored=restored, pendingAdmissionRestored=False,
            crashDurable=False, freshCheckpointId=checkpoint, restoredDepth=int(restored), sourceAnchorPoint=SOURCE,
            startupAdmission=dict(pin=pin(point, owner), size=0, byteSize=0, eligibleCount=0, rebuilding=False, closed=False)))


class EarlyRestartTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.raw = wire()
        self.claim = restart.envelope_claim(self.raw)
        self.request = dict(schema="plutus-service-checkpoint-publication-v1", checkpointFile="checkpoint.bin",
            checkpointBytes=len(self.raw), claim=self.claim, restoreAuthorized=False, crashDurable=False, scope=restart.SCOPE)
        self.request_raw = restart.encoded(self.request)
        (self.root/"checkpoint.bin").write_bytes(self.raw)
        (self.root/"checkpoint-request.json").write_bytes(self.request_raw)
        self.result = dict(schema="plutus-service-result-v1", status="stopped", stopReason="blockLimit",
            resourcesFinalized=True, sourceJoinId=JOIN, initialManifestSHA256=H, fullLedgerValidated=False,
            transactionSuccessClaimed=False, transportOpens=1, transportCloses=1, finalPin=pin(TERMINAL,generation=1),
            boundedRestart=dict(checkpointFile="checkpoint.bin", checkpointSHA256=restart.digest(self.raw),
                checkpointRequestSHA256=restart.digest(self.request_raw),checkpointAfter=1))

    def authorize(self, **changes):
        args=dict(store_id=H, session_id=OTHER, generation=0, source_point=SOURCE, manifest=H,
                  acquired_point=TERMINAL, checkpoint_after=1)
        args.update(changes)
        return restart.authorize_pair(self.root, ready(), self.result, **args)

    def test_exact_pair_produces_separate_acceptance_without_writing_it(self):
        raw, authority, claim = self.authorize()
        self.assertEqual(raw,self.raw)
        self.assertEqual(restart.base.decode(authority),dict(schema="plutus-service-restore-authority-v1",decision="accept",claim=claim))
        self.assertEqual(set(p.name for p in self.root.iterdir()),{"checkpoint.bin","checkpoint-request.json"})

    def test_changed_external_identity_source_generation_or_acquisition_rejects(self):
        for change in (dict(store_id=OTHER),dict(session_id=H),dict(generation=1),dict(manifest=OTHER),
                       dict(source_point=TERMINAL),dict(acquired_point=SOURCE),dict(checkpoint_after=2)):
            with self.subTest(change=change),self.assertRaises(ValueError):self.authorize(**change)

    def test_request_cannot_claim_other_binary_or_self_authorize(self):
        for key,value in (("restoreAuthorized",True),("checkpointFile","../checkpoint.bin"),("crashDurable",True),
                          ("claim",dict(self.claim,generation=9)),("checkpointBytes",len(self.raw)+1)):
            with self.subTest(key=key):
                changed=dict(self.request);changed[key]=value
                (self.root/"checkpoint-request.json").write_bytes(restart.encoded(changed))
                with self.assertRaises(ValueError):self.authorize()

    def test_clean_finalized_exact_owner_stop_required(self):
        original=copy.deepcopy(self.result)
        for key,value in (("status","failure"),("stopReason","durationLimit"),("resourcesFinalized",False),
                          ("transportCloses",0),("transportCloses",True),("finalPin",pin(TERMINAL,owner=OTHER,generation=1))):
            self.result=copy.deepcopy(original);self.result[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):self.authorize()

    def test_boolean_revision_cannot_impersonate_binary_integer(self):
        changed=copy.deepcopy(self.request)
        changed["claim"]["historicalRevision"]=True
        (self.root/"checkpoint-request.json").write_bytes(restart.encoded(changed))
        with self.assertRaises(ValueError):self.authorize()

    def test_startup_requires_actual_empty_pool_and_fresh_lineage(self):
        value=ready(True,TERMINAL,OTHER,OTHER)
        self.assertEqual(restart.startup(value,JOIN,H,TERMINAL,True,H,H)["ownerId"],OTHER)
        for key,value2 in (("size",1),("byteSize",1),("eligibleCount",1),("rebuilding",True),("closed",True)):
            changed=copy.deepcopy(value);changed["boundedRestart"]["startupAdmission"][key]=value2
            with self.subTest(key=key),self.assertRaises(ValueError):restart.startup(changed,JOIN,H,TERMINAL,True,H,H)
        with self.assertRaises(ValueError):restart.startup(value,JOIN,H,TERMINAL,True,OTHER,H)
        with self.assertRaises(ValueError):restart.startup(value,JOIN,H,TERMINAL,True,H,OTHER)

    def test_untrusted_wire_bounds_and_trailing_bytes(self):
        variants=[b"", self.raw[:8], self.raw[:-1], self.raw+b"x", b"x"*(restart.MAX_IMAGE+1)]
        for offset,fmt,value in ((312,">i",9),(316,">i",-1),(384,">B",2),(417,">i",-1)):
            changed=bytearray(self.raw);struct.pack_into(fmt,changed,offset,value);variants.append(bytes(changed))
        for raw in variants:
            with self.subTest(length=len(raw)),self.assertRaises(ValueError):restart.envelope_claim(raw)

    def test_pair_missing_and_symlink_input_reject(self):
        (self.root/"checkpoint-request.json").unlink()
        with self.assertRaises(FileNotFoundError):self.authorize()
        (self.root/"other.json").write_bytes(self.request_raw)
        (self.root/"checkpoint-request.json").symlink_to(self.root/"other.json")
        with self.assertRaises(ValueError):self.authorize()

    def test_acceptance_publication_never_overwrites_and_cleans_pending(self):
        path=self.root/"accepted.json"
        restart.publish_new(path,b"first")
        with self.assertRaises(FileExistsError):restart.publish_new(path,b"second")
        self.assertEqual(path.read_bytes(),b"first")
        self.assertFalse(path.with_name(path.name+".pending").exists())

    def test_successor_observation_is_same_fresh_owner_and_strict_next_block(self):
        restored_ready=ready(True,TERMINAL,OTHER,OTHER)
        next_point=dict(slot=30,blockNo=3,hash=H)
        current=pin(next_point,OTHER,generation=1)
        value=dict(schema="plutus-service-publication-v1",index=0,pin=current,sourceJoinId=JOIN,
            initialManifestSHA256=H,profileId=restart.two.fixture.PROFILE,fullLedgerValidated=False)
        phase=self.root/"service-2";phase.mkdir()
        (phase/"publication-0000.json").write_bytes(restart.encoded(value))
        controller=SimpleNamespace(exchange=self.root,out=self.root,deadline=restart.time.monotonic()+2)
        restored=restart.RunningRestored("service-2",restored_ready,self.claim,H,H,H)
        self.assertEqual(restart.wait_checked_successor(controller,restored,1),current)
        evidence=restart.base.decode((self.root/"restart-successor.json").read_bytes())
        self.assertFalse(evidence["transactionInclusionClaimed"])
        self.assertFalse(evidence["wholeStateOracleCompared"])
        value["pin"]=pin(next_point,H,generation=1)
        (phase/"publication-0000.json").write_bytes(restart.encoded(value))
        with self.assertRaises(ValueError):restart.wait_checked_successor(controller,restored,1)

    def test_adapter_authorizes_only_after_clean_exit_and_capture_and_leaves_successor_running(self):
        calls=[]
        exchange=self.root/"exchange";exchange.mkdir()
        initial=exchange/"initial";initial.mkdir()
        manifest=restart.digest(b"manifest")
        (initial/"adapter-inputs.json").write_bytes(b"manifest")
        first_ready=ready();first_ready["initialManifestSHA256"]=manifest
        second_ready=ready(True,TERMINAL,OTHER,OTHER);second_ready["initialManifestSHA256"]=manifest
        raw=bytearray(self.raw);raw[144:176]=bytes.fromhex(manifest);raw=bytes(raw)
        claim=restart.envelope_claim(raw)
        request=dict(self.request,claim=claim)
        request_raw=restart.encoded(request)
        result=copy.deepcopy(self.result);result["initialManifestSHA256"]=manifest
        result["boundedRestart"].update(checkpointSHA256=restart.digest(raw),checkpointRequestSHA256=restart.digest(request_raw))
        controller=SimpleNamespace(exchange=exchange,out=self.root,initial=SOURCE,args=SimpleNamespace(duration_seconds=30,max_blocks=8),
            containers={"reference":H},deadline=restart.time.monotonic()+30)
        def launch(c,live,phase,root,pin,extra,readonly=(),command_builder=None):
            calls.append("launch-"+phase);root.mkdir();c.containers[phase]=phase
            if phase=="service-1":
                (root/"checkpoint.bin").write_bytes(raw);(root/"checkpoint-request.json").write_bytes(request_raw)
            else:
                self.assertTrue((c.out/"restart-authority.json").exists())
                self.assertIn("--restore-authority-sha256",extra)
                self.assertEqual(len(readonly),2)
        def wait(phase,name,seconds):
            calls.append(name+phase)
            return result if name=="result.json" else (first_ready if phase=="service-1" else second_ready)
        def capture(point,phase):
            calls.append("capture")
            self.assertFalse((controller.out/"restart-authority.json").exists())
            return self.root,dict(schema="native-live-acquisition-result-v1",point=point,exactAcquiredPoint=True,
                acquireCount=1,reacquireCount=0,referenceContainerId=H)
        def remove(phase):calls.append("remove-"+phase)
        controller.wait_service=wait;controller.capture=capture;controller.remove_service=remove
        with patch.object(restart,"launch_service",side_effect=launch),patch.object(restart,"wait_exit",side_effect=lambda c,p:calls.append("clean-exit")),\
             patch.object(restart,"peer_ready",side_effect=lambda *args:None):
            value=restart.perform_early_restart(controller,None,manifest,store_id=H,session_id=OTHER,generation=0,
                resume_producer=lambda: calls.append("resume") or 1,freeze_producer=lambda:calls.append("freeze"))
        self.assertLess(calls.index("clean-exit"),calls.index("capture"))
        self.assertLess(calls.index("capture"),calls.index("remove-service-1"))
        self.assertLess(calls.index("remove-service-1"),calls.index("launch-service-2"))
        self.assertEqual(value.phase,"service-2")
        self.assertNotIn("remove-service-2",calls)
        self.assertEqual(calls.count("resume"),2)


if __name__ == "__main__":
    unittest.main()
