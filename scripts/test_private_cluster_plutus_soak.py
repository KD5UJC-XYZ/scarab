import copy
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import private_cluster_plutus_soak as soak
import private_cluster_plutus_early_restart as restart
from test_private_cluster_plutus_early_restart import pin, H, OTHER, JOIN


class SoakTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def generations(self, count=1):
        root = self.root/"jvm-likelihood"; root.mkdir()
        d = root/"generation-0000"; d.mkdir()
        pool = "11"*28
        rows = [pool+" 1 100 1"] if count else []
        request = ("conway-native-likelihood-v1\n"+H+"\n1000 1 20 0 1\n"+"".join(x+"\n" for x in rows)).encode()
        result = b"conway-jvm-likelihood-result-v1\n"+request+b"--jvm--\n"+(pool+" "+"0"*16+" "+"0"*800+"\n").encode()*count
        execution = ("jvm-likelihood-execution-v1\n"+restart.digest(request)+"\n"+restart.digest(result)+
                     f"\nPureJvm\n{100*count} {count}\n0 0\n").encode()
        for name, raw in (("request.txt", request), ("jvm-result.txt", result), ("execution.txt", execution)):
            (d/name).write_bytes(raw)
        return root

    def test_missing_comparator_refuses_before_controller_or_cluster_creation(self):
        with patch.object(soak,"controller_type") as construct:
            with self.assertRaises(NotImplementedError):soak.execute(None,None,None,None)
            construct.assert_not_called()

    def test_explicit_soak_limits_do_not_widen_existing_modes(self):
        for duration in (120,600): soak.soak_limits(duration,512)
        for duration, blocks in ((60,512),(601,512),(120,128),(True,512)):
            with self.assertRaises(ValueError): soak.soak_limits(duration,blocks)
        with self.assertRaises(ValueError): soak.single.service_limits(120,512)

    def test_pure_command_has_no_native_mount_or_flags_and_keeps_resource_ceiling(self):
        args=SimpleNamespace(duration_seconds=600,max_blocks=512,scala_build_root=self.root,java="java",scala_image="sha256:"+H)
        command=soak.repeated_args(args,self.root/"output",self.root/"initial","cp",H,3001,42,H)
        self.assertEqual(command[-2:],["--epoch-mode","repeated-jvm-v1"])
        self.assertEqual(command[command.index("--duration-seconds")+1],"600")
        self.assertEqual(command[command.index("--max-blocks")+1],"512")
        self.assertIn("--cpus=0.5",command);self.assertIn("--memory=1g",command)
        self.assertFalse(any("native-likelihood" in x or "/oracle-libs" in x for x in command))

    def test_active_clock_and_result_require_complete_requested_interval(self):
        clock=dict(schema="plutus-service-active-v1",epochMode=soak.MODE,durationSeconds=600,startedUnixMillis=1000,deadlineUnixMillis=601000)
        soak.active(clock,600)
        result=dict(schema="plutus-service-result-v1",status="stopped",stopReason="durationLimit",startedUnixMillis=1000,
                    requestedDeadlineUnixMillis=601000,effectiveDeadlineUnixMillis=601000,endedUnixMillis=601001)
        soak.full_interval(result,clock)
        for change in (dict(endedUnixMillis=600999),dict(stopReason="blockLimit"),dict(startedUnixMillis=0),dict(effectiveDeadlineUnixMillis=60000)):
            with self.subTest(change=change),self.assertRaises(ValueError):soak.full_interval(dict(result,**change),clock)
        with self.assertRaises(ValueError):soak.active(dict(clock,deadlineUnixMillis=60000),600)

    def test_active_clock_cannot_belong_to_a_different_or_later_owner_state(self):
        first=pin(dict(hash=H,slot=20,blockNo=2),generation=1)
        final=pin(dict(hash=H,slot=30,blockNo=3),generation=2)
        soak.active_owner(dict(pin=pin()),first,final)
        with self.assertRaises(ValueError):soak.active_owner(dict(pin=pin(owner=OTHER)),first,final)
        with self.assertRaises(ValueError):soak.active_owner(dict(pin=final),first,final)

    def test_waiting_full_intervals_rejects_early_results_even_after_client_success(self):
        # Exercise supervisor method with no Docker/network; a client success
        # cannot convert an early service result into the requested duration.
        live=SimpleNamespace()
        with patch.object(soak.two,"controller_type",return_value=object): cls=soak.controller_type(live)
        controller=object.__new__(cls);controller.deadline=soak.time.monotonic()+1
        controller.service_roots={p:self.root/p for p in soak.SERVICE_PHASES}
        controller.actives={p:dict(startedUnixMillis=1,deadlineUnixMillis=600001) for p in soak.SERVICE_PHASES}
        for root in controller.service_roots.values():
            root.mkdir();(root/"result.json").write_bytes(restart.encoded(dict(schema="plutus-service-result-v1",status="stopped",
                stopReason="durationLimit",startedUnixMillis=1,requestedDeadlineUnixMillis=600001,effectiveDeadlineUnixMillis=600001,endedUnixMillis=5)))
        with self.assertRaises(ValueError):controller.wait_full_intervals()

    def test_nonempty_jvm_originals_are_hash_bound_without_native_parity(self):
        root=self.generations(); row=soak.jvm_generations(root)[0]
        self.assertEqual(row["computedRaw32Words"],100);self.assertEqual(row["nativeComparisons"],0)
        (root/"generation-0000/jvm-result.txt").write_bytes(b"substituted")
        with self.assertRaises(ValueError):soak.jvm_generations(root)

    def test_empty_go_has_valid_zero_computation_without_invented_native_match(self):
        row=soak.jvm_generations(self.generations(0))[0]
        self.assertEqual((row["computedRaw32Words"],row["computedRaw64Words"]),(0,0))

    def test_generation_extra_native_file_or_directory_gap_rejects(self):
        root=self.generations(); d=root/"generation-0000"
        (d/"response.txt").write_bytes(b"native")
        with self.assertRaises(ValueError):soak.jvm_generations(root)
        (d/"response.txt").unlink();d.rename(root/"generation-0001")
        with self.assertRaises(ValueError):soak.jvm_generations(root)

    def test_generation_symlink_cannot_substitute_other_evidence(self):
        root=self.generations(); d=root/"generation-0000"
        (self.root/"original").write_bytes((d/"request.txt").read_bytes());(d/"request.txt").unlink()
        (d/"request.txt").symlink_to(self.root/"original")
        with self.assertRaises(ValueError):soak.jvm_generations(root)

    def test_native_mount_option_requires_independent_complete_library_pin(self):
        binary=self.root/"binary";binary.write_bytes(b"test-only-not-executed");binary.chmod(0o700)
        libs=self.root/"libs";libs.mkdir();(libs/"a.so.1").write_bytes(b"lib");(libs/"a.so").symlink_to("a.so.1")
        record=restart.library_manifest(libs)
        mounts=restart.NativeRuntimeMounts(binary,restart.digest(binary.read_bytes()),libs,restart.digest(restart.encoded(record)))
        self.assertEqual(len(mounts.checked_mounts()),2)
        (libs/"a.so.1").write_bytes(b"changed")
        with self.assertRaises(ValueError):mounts.checked_mounts()

    def test_native_library_symlink_escape_rejects(self):
        libs=self.root/"libs";libs.mkdir();(self.root/"outside").write_bytes(b"x");(libs/"escape").symlink_to("../outside")
        with self.assertRaises(ValueError):restart.library_manifest(libs)

    def test_native_library_traversal_counts_empty_directories_before_sorting(self):
        libs=self.root/"libs";libs.mkdir();(libs/"a.so").write_bytes(b"x")
        for index in range(256):(libs/f"empty-{index:03d}").mkdir()
        with self.assertRaises(ValueError):restart.library_manifest(libs)

    def test_disk_and_log_budgets_fail_closed_and_missing_start_root_is_safe(self):
        soak.tree_budget([self.root/"not-created-yet"])
        (self.root/"x.log").write_bytes(b"12345")
        with patch.object(soak,"MAX_LOG_BYTES",4),self.assertRaises(ValueError):soak.tree_budget([self.root])
        with patch.object(soak,"MAX_TREE_BYTES",4),self.assertRaises(ValueError):soak.tree_budget([self.root])

    def test_stopped_inspection_race_rechecks_full_interval_result(self):
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace())
        controller=object.__new__(cls);controller.deadline=soak.time.monotonic()+2
        controller.service_roots={p:self.root/p for p in soak.SERVICE_PHASES}
        controller.containers={p:p for p in soak.SERVICE_PHASES}
        clock=dict(startedUnixMillis=1000,deadlineUnixMillis=121000)
        controller.actives={p:clock for p in soak.SERVICE_PHASES}
        result=dict(schema="plutus-service-result-v1",status="stopped",stopReason="durationLimit",startedUnixMillis=1000,
                    requestedDeadlineUnixMillis=121000,effectiveDeadlineUnixMillis=121000,endedUnixMillis=121001)
        for root in controller.service_roots.values():root.mkdir()
        def stopped(phase):
            (controller.service_roots[phase]/"result.json").write_bytes(restart.encoded(result))
            return dict(State=dict(Running=False))
        controller.owned=stopped
        self.assertEqual(controller.wait_full_intervals(),[result,result])

    def test_receipt_race_rechecks_failure_precedence(self):
        with patch.object(soak.two,"controller_type",return_value=object):cls=soak.controller_type(SimpleNamespace())
        controller=object.__new__(cls);controller.deadline=soak.time.monotonic()+2
        controller.service_roots={"service-2":self.root};controller.containers={"service-2":"id"}
        def stopped(_):
            (self.root/"result.json").write_bytes(b"{}")
            (self.root/"failure.json").write_bytes(b"{}")
            return dict(State=dict(Running=False))
        controller.owned=stopped
        with self.assertRaises(ValueError):controller.wait_service("service-2","result.json",1)

    def test_monitor_stop_is_safe_before_start_and_releases_waiting_thread(self):
        monitor=soak.BudgetMonitor([self.root]);monitor.stop()
        monitor=soak.BudgetMonitor([self.root]);monitor.start();monitor.stop()
        self.assertFalse(monitor.thread.is_alive());self.assertIsNone(monitor.failure)

    def test_late_scan_failure_cannot_signal_after_monitor_shutdown(self):
        import threading
        entered=threading.Event();release=threading.Event()
        def delayed(_):
            entered.set();release.wait(5);raise ValueError("late failure")
        monitor=soak.BudgetMonitor([self.root])
        with patch.object(soak,"tree_budget",side_effect=delayed),patch.object(soak.os,"kill") as kill:
            monitor.start();self.assertTrue(entered.wait(1))
            # Simulate delayed filesystem I/O surviving the bounded shutdown
            # join; the serialized stop flag prevents a late process signal.
            with patch.object(monitor.thread,"join"):
                monitor.stop()
            release.set();monitor.thread.join(1)
            self.assertFalse(monitor.thread.is_alive());kill.assert_not_called()

    def test_long_fixture_changes_only_expiry_and_requires_explicit_profile(self):
        original=dict(build=("cli","transaction","build-raw","--invalid-hereafter","999","--fee","300000"),sign=("sign",))
        with patch.object(soak.soak_fixture.short,"spend_commands",return_value=original):
            result=soak.soak_fixture.spend_commands(None,None,None,None,0,False,profile="early-restart-two-service-soak-v1")
            self.assertEqual(result["build"],tuple("8000" if x=="999" else x for x in original["build"]))
            self.assertEqual(result["sign"],original["sign"]);self.assertEqual(original["build"][4],"999")
            with self.assertRaises(ValueError):soak.soak_fixture.spend_commands(None,None,None,None,0,False,profile="unknown")

    def test_replaced_slot_cleanup_recovers_interrupted_create_without_touching_old_or_other_ids(self):
        with patch.object(soak.two,"controller_type",return_value=object):
            cls=soak.controller_type(SimpleNamespace(LABEL="owned"))
        controller=object.__new__(cls);controller.out=self.root;controller.token="token";controller.containers={"service-1":"old"}
        events=[];present={"new"}
        def docker(*args,**kwargs):
            if args[0]=="ps":
                if "label=lab.zero-live.phase=service-1" in args:return SimpleNamespace(stdout="new" if "new" in present else "")
                identity=next(a[3:] for a in args if a.startswith("id="))
                return SimpleNamespace(stdout=identity if identity in present else "")
            if args[0]=="logs":return SimpleNamespace(stdout="bounded",stderr="")
            if args[0]=="rm":present.remove(args[-1]);events.append(args[-1]);return SimpleNamespace(stdout="")
            raise AssertionError(args)
        controller.docker=docker;controller.owned=lambda cid:self.assertEqual(cid,"new")
        controller.remove_service("service-1")
        self.assertEqual(events,["new"]);self.assertFalse(present)
        self.assertTrue((self.root/"service-1-new-cleanup.json").exists())

    def test_publications_bind_every_jvm_generation_and_reject_substitution(self):
        evidence_root=self.generations(0)
        record=soak.jvm_generations(evidence_root)[0]
        checked=dict(record,mode="pure-jvm",nativeResponseSHA256=None,nativeValidated=False,diagnosticNativeDependency=False,
                     raw32Comparisons=0,raw64Comparisons=0,jvmMismatchWords=0,preTickTupleId=H,observedSlot=1000,applicationEpoch=1)
        refs=[];observed=[]
        for index,slot in enumerate((20,1001)):
            state=pin(dict(hash=H,slot=slot,blockNo=index+2),generation=index+1)
            value=dict(schema="plutus-service-publication-v1",index=index,pin=state,sourceJoinId=JOIN,initialManifestSHA256=H,
                       profileId=soak.fixture.PROFILE,diagnosticOnly=True,fullLedgerValidated=False,included=[],
                       repeatedEpoch=dict(epoch=slot//1000,componentId=H,nonMyopicId=H,transitions=index,frozenId=H if index else None,
                                          checkedLikelihood=checked if index else None))
            name=f"publication-{index:04d}.json";raw=restart.encoded(value);(self.root/name).write_bytes(raw)
            refs.append(dict(file=name,sha256=restart.digest(raw),pin=state))
            observed.append(dict(publicationFile=name,publicationSHA256=restart.digest(raw),publication=value))
        result=dict(publications=refs,finalPin=refs[-1]["pin"])
        proof=soak.repeated_publications(self.root,result,observed,JOIN,H,{})
        self.assertEqual(proof["epochs"],[0,1]);self.assertFalse(proof["nativeParityChecked"])
        draft=evidence_root/"generation-0001";draft.mkdir()
        request=(evidence_root/"generation-0000/request.txt").read_bytes().replace(H.encode(),OTHER.encode())
        result_bytes=(evidence_root/"generation-0000/jvm-result.txt").read_bytes().replace(H.encode(),OTHER.encode())
        (draft/"request.txt").write_bytes(request);(draft/"jvm-result.txt").write_bytes(result_bytes)
        (draft/"execution.txt").write_text("jvm-likelihood-execution-v1\n"+restart.digest(request)+"\n"+restart.digest(result_bytes)+"\nPureJvm\n0 0\n0 0\n")
        proof=soak.repeated_publications(self.root,result,observed,JOIN,H,{})
        self.assertEqual(len(proof["selectedGenerations"]),1)
        self.assertEqual(len(proof["unselectedDrafts"]),1)
        self.assertEqual(proof["unselectedDrafts"][0]["frozenId"],OTHER)
        changed=copy.deepcopy(observed);changed[1]["publication"]["pin"]["ownerId"]=OTHER
        with self.assertRaises(ValueError):soak.repeated_publications(self.root,result,changed,JOIN,H,{})
        value=copy.deepcopy(observed[1]["publication"]);value["repeatedEpoch"]["checkedLikelihood"]["evidenceSHA256"]=OTHER
        raw=restart.encoded(value);(self.root/refs[1]["file"]).write_bytes(raw);refs[1]["sha256"]=restart.digest(raw)
        with self.assertRaises(ValueError):soak.repeated_publications(self.root,result,[],JOIN,H,{})

    def test_post_boundary_http_guard_rejects_pre_boundary_receipts_and_changed_owner(self):
        from test_private_cluster_plutus_two_service import example
        value,transfers=example()
        value.update(soakProfile=soak.PROFILE,secondSubmittedAfterEpochOne=True,
                     boundaryPins=[row["initialState"]["pin"] for row in value["endpoints"]])
        with self.assertRaises(ValueError):soak.soak_client_result(value,transfers,[31001,31002])
        # The underlying two-owner/original validation still precedes the new
        # epoch observations; a supplied boundary flag never grants admission.
        value["endpoints"][1]["observedOwnerId"]=value["endpoints"][0]["observedOwnerId"]
        with self.assertRaises(ValueError):soak.soak_client_result(value,transfers,[31001,31002])


if __name__ == "__main__":unittest.main()
