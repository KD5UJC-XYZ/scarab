import copy
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import private_cluster_plutus_early_restart_check as check
import private_cluster_plutus_early_restart as restart
from test_private_cluster_plutus_early_restart import ready, pin, H, OTHER, JOIN, SOURCE, TERMINAL


class RestartOnlyTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        self.fresh=ready(True,TERMINAL,OTHER,OTHER)
        self.restored=restart.RunningRestored("service-2",self.fresh,dict(sourcePoint=SOURCE,terminalPoint=TERMINAL,sourceJoinId=JOIN),H,H,H)
        self.successor=pin(dict(hash=H,slot=30,blockNo=3),OTHER,generation=1)
        self.publication=dict(schema="plutus-service-publication-v1",index=0,pin=self.successor,included=[],sourceJoinId=JOIN,
                              initialManifestSHA256=H,profileId=check.fixture.PROFILE,diagnosticOnly=True,fullLedgerValidated=False)
        self.terminal=dict(pin=self.successor,sourceJoinId=JOIN,initialManifestSHA256=H,epoch=0,
            schema="plutus-service-terminal-observation-v1",diagnosticOnly=True,restartSupported=False,fullLedgerValidated=False)
        pub=restart.encoded(self.publication);terminal=restart.encoded(self.terminal)
        (self.root/"publication-0000.json").write_bytes(pub);(self.root/"terminal-observation.json").write_bytes(terminal)
        self.result=dict(schema="plutus-service-result-v1",status="stopped",stopReason="blockLimit",resourcesFinalized=True,
            profileId=check.fixture.PROFILE,sourceJoinId=JOIN,initialManifestSHA256=H,initialPoint=TERMINAL,initialEpoch=0,
            fullLedgerValidated=False,transactionSuccessClaimed=False,restartSupported=False,transportOpens=1,transportCloses=1,
            finalPin=self.successor,evaluationReceipts=[],publications=[dict(file="publication-0000.json",sha256=restart.digest(pub),pin=self.successor)],
            terminalObservationFile="terminal-observation.json",terminalObservationSHA256=restart.digest(terminal),
            startedUnixMillis=1000,endedUnixMillis=2000)

    def validate(self):return check.finalized_successor(self.root,self.restored,self.successor,self.result,H)

    def test_exact_finalized_epoch_zero_successor_passes_without_spend_claim(self):
        self.assertEqual(self.validate(),self.successor)
        self.assertEqual(self.result["evaluationReceipts"],[])

    def test_resource_failure_timeout_or_other_mode_cannot_be_success(self):
        original=copy.deepcopy(self.result)
        for key,bad in (("resourcesFinalized",False),("transportCloses",0),("transportCloses",True),("stopReason","durationLimit"),
                        ("epochMode",{}),("fullLedgerValidated",True),("transactionSuccessClaimed",True),("endedUnixMillis",40000)):
            self.result=copy.deepcopy(original);self.result[key]=bad
            with self.subTest(key=key),self.assertRaises(ValueError):self.validate()

    def test_foreign_owner_or_changed_source_or_missing_successor_rejects(self):
        original=copy.deepcopy(self.result)
        for key,bad in (("sourceJoinId",H),("initialManifestSHA256",OTHER),("initialPoint",SOURCE),
                        ("finalPin",pin(self.successor["point"],H,generation=1)),("publications",[])):
            self.result=copy.deepcopy(original);self.result[key]=bad
            with self.subTest(key=key),self.assertRaises(ValueError):self.validate()

    def test_epoch_crossing_cannot_pass_even_with_self_consistent_original_hashes(self):
        self.successor=pin(dict(hash=H,slot=1000,blockNo=3),OTHER,generation=1)
        self.result["finalPin"]=self.successor
        with self.assertRaises(ValueError):self.validate()

    def test_no_unrequested_admission_or_transaction_inclusion(self):
        self.result["evaluationReceipts"]=[dict(file="receipt",sha256=H)]
        with self.assertRaises(ValueError):self.validate()
        self.result["evaluationReceipts"]=[];self.publication["included"]=[dict(transactionId=H)]
        raw=restart.encoded(self.publication);(self.root/"publication-0000.json").write_bytes(raw)
        self.result["publications"][0]["sha256"]=restart.digest(raw)
        with self.assertRaises(ValueError):self.validate()

    def test_raw_publication_and_terminal_substitution_are_rejected(self):
        for filename in ("publication-0000.json","terminal-observation.json"):
            path=self.root/filename;raw=path.read_bytes();path.write_bytes(raw+b" ")
            with self.subTest(filename=filename),self.assertRaises(ValueError):self.validate()
            path.write_bytes(raw)
        (self.root/"other.json").write_bytes((self.root/"terminal-observation.json").read_bytes())
        (self.root/"terminal-observation.json").unlink();(self.root/"terminal-observation.json").symlink_to(self.root/"other.json")
        with self.assertRaises(ValueError):self.validate()

    def test_rehashed_wrong_schema_or_scope_cannot_become_terminal_evidence(self):
        for key,bad in (("schema","other"),("diagnosticOnly",False),("restartSupported",True),("fullLedgerValidated",True)):
            changed=dict(self.terminal,**{key:bad});raw=restart.encoded(changed)
            (self.root/"terminal-observation.json").write_bytes(raw)
            self.result["terminalObservationSHA256"]=restart.digest(raw)
            with self.subTest(key=key),self.assertRaises(ValueError):self.validate()
        raw=restart.encoded(self.terminal);(self.root/"terminal-observation.json").write_bytes(raw)
        self.result["terminalObservationSHA256"]=restart.digest(raw)
        changed=dict(self.publication,diagnosticOnly=False);raw=restart.encoded(changed)
        (self.root/"publication-0000.json").write_bytes(raw);self.result["publications"][0]["sha256"]=restart.digest(raw)
        with self.assertRaises(ValueError):self.validate()

    def test_execute_failure_finalizes_owned_cleanup_watchdog_and_signal_handlers(self):
        events=[];classpath=self.root/"classpath";classpath.write_text("test-path")
        args=SimpleNamespace(restart_profile=check.PROFILE,duration_seconds=30,max_blocks=8,owned_root=self.root,
            support_sha=H,scala_image="image",scala_classpath_file=classpath,client_classpath_file=classpath)
        launch=SimpleNamespace(out=self.root,in_cleanup=False)
        launch.cleanup=lambda:events.append("cleanup")
        def fail(*_args,**_kwargs):raise ValueError("inspection failure")
        launch.docker=fail
        watchdog=SimpleNamespace(start=lambda:events.append("watch-start"),stop=lambda:events.append("watch-stop"))
        live=SimpleNamespace(DiskWatchdog=lambda root:watchdog)
        with patch.object(check,"controller_type",return_value=lambda *args:launch),             patch.object(check.signal,"signal",return_value="old") as handlers,patch.object(check.signal,"alarm") as alarms:
            with self.assertRaises(ValueError):check.execute(live,{},args,"cp")
        self.assertEqual(events,["watch-start","cleanup","watch-stop"])
        self.assertEqual([x.args[0] for x in alarms.call_args_list],[240,0])
        self.assertEqual(len(handlers.call_args_list),8)
        self.assertTrue(all(x.args[1]=="old" for x in handlers.call_args_list[4:]))
        self.assertTrue((self.root/"failure.json").exists())
        self.assertFalse((self.root/"result.json").exists())

    def test_fast_completed_service_receipts_are_read_before_running_state(self):
        class Launcher:pass
        kind=check.two.controller_type(SimpleNamespace(Launcher=Launcher,LABEL="owned"))
        controller=object.__new__(kind);controller.exchange=self.root;controller.out=self.root
        controller.deadline=check.time.monotonic()+2
        controller.owned=lambda _:self.fail("completed files must precede running-state probe")
        controller.containers={"service-2":"already-exited"}
        phase=self.root/"service-2";phase.mkdir()
        (phase/"result.json").write_bytes(restart.encoded(self.result))
        (phase/"publication-0000.json").write_bytes(restart.encoded(self.publication))
        claim=dict(self.restored.claim,manifestSHA256=H)
        restored=restart.RunningRestored("service-2",self.fresh,claim,H,H,H)
        self.assertEqual(restart.wait_checked_successor(controller,restored,1),self.successor)
        self.assertEqual(controller.wait_service("service-2","result.json",1),self.result)

    def test_continuation_changes_only_new_publication_limit(self):
        args=SimpleNamespace(duration_seconds=30,max_blocks=8,scala_build_root=self.root,java="java",scala_image="image")
        rest=(self.root/"out",self.root/"initial","cp",H,3001,42,H)
        expected=check.two.service_args(args,*rest);expected[expected.index("--max-blocks")+1]="1"
        actual=check.continuation_args(args,*rest)
        self.assertEqual(actual,expected);self.assertEqual(args.max_blocks,8)
        self.assertNotIn("--epoch-mode",actual)

    def test_lifecycle_waits_successor_and_final_exit_before_cleanup_without_client(self):
        with patch.object(check.two,"controller_type",return_value=object):cls=check.controller_type(None)
        controller=object.__new__(cls);controller.exchange=self.root;controller.out=self.root;events=[]
        controller.prepare_restart_initial=lambda approval:events.append("prepare") or H
        controller.resume_producer=lambda:1;controller.freeze_producer=lambda:None
        service=self.root/"service-2";service.mkdir();(service/"result.json").write_bytes(restart.encoded(self.result))
        controller.wait_service=lambda *args:events.append("result") or self.result
        controller.stop_node=lambda n:events.append("stop"+str(n));controller.cleanup=lambda:events.append("cleanup")
        with patch.object(restart,"perform_early_restart",side_effect=lambda *a,**kw:events.append("authority-recover") or self.restored),\
             patch.object(restart,"wait_checked_successor",side_effect=lambda *a,**kw:events.append("successor") or self.successor),\
             patch.object(restart,"wait_exit",side_effect=lambda *a:events.append("exit")),\
             patch.object(check,"finalized_successor",side_effect=lambda *a:events.append("validate") or self.successor):
            self.assertEqual(controller.same_epoch_lifecycle(None),self.successor)
        self.assertEqual(events,["prepare","authority-recover","successor","result","exit","validate","stop1","stop2","cleanup"])
        self.assertFalse((self.root/"submission").exists())

    def test_missing_or_invalid_explicit_profile_refuses_before_controller_creation(self):
        args=SimpleNamespace(restart_profile="other",duration_seconds=30,max_blocks=8)
        with patch.object(check,"controller_type") as create:
            with self.assertRaises(ValueError):check.execute(None,None,args,None)
            create.assert_not_called()


if __name__ == "__main__":unittest.main()
