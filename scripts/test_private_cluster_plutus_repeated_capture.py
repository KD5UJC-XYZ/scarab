#!/usr/bin/env python3
import copy
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import private_cluster_plutus_repeated_capture as capture
from test_private_cluster_plutus_early_restart import pin, H, OTHER, JOIN
import test_private_cluster_plutus_soak as soak_tests


def mode():
    return dict(mode=capture.MODE, jvmComputed=True, researchOnly=True, activeStartsAfterPeerReady=True,
        nativeChecked=False, nativeRuntimeDependency=False, lateCheckpointRestoreSupported=False,
        nativeExecutableSHA256=None, nativeEvidenceDirectory=None,
        generationEvidenceDirectory="jvm-likelihood", maxEpochTransitions=8, startupTimeoutSeconds=30)


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.live = SimpleNamespace(Launcher=type("Launcher", (), {}))
        self.kind = capture.controller_type(self.live)
        self.obj = object.__new__(self.kind)
        self.obj.args = SimpleNamespace(duration_seconds=30, max_blocks=128, terminal_epoch=0,
            epoch_mode=capture.MODE, scala_build_root=Path('/build'), client_classpath_file=Path('/cp'),
            java='java', scala_image='image')
        self.obj.initial = dict(slot=10, blockNo=1, hash=H)
        self.obj.active_clock = dict(schema="plutus-service-active-v1", epochMode=capture.MODE,
            pin=pin(self.obj.initial), startedUnixMillis=1000, deadlineUnixMillis=31000, durationSeconds=30)
        self.obj.deadline = 1000
        self.obj.exchange = self.root
        self.obj.out = self.root / 'out'
        self.obj.out.mkdir()

    def result(self, epoch=0):
        return dict(schema='plutus-service-result-v1', status='stopped', stopReason='durationLimit', epochMode=mode(),
            finalPin=pin(dict(slot=epoch*1000+600, blockNo=30, hash=OTHER), generation=29),
            startedUnixMillis=1000, requestedDeadlineUnixMillis=31000, effectiveDeadlineUnixMillis=31000,
            endedUnixMillis=31100)

    def observation(self, epoch=0, phase='complete'):
        result = self.result(epoch)
        components = dict(epoch=epoch, reward=dict(phase=phase))
        value = {k:None for k in capture.TERMINAL_FIELDS}
        value.update(schema='plutus-repeated-service-terminal-observation-v1', diagnosticOnly=True,
            fullLedgerValidated=False, restartSupported=False, pin=result['finalPin'], sourceJoinId=JOIN,
            initialManifestSHA256=H, epoch=epoch, validationSlot=result['finalPin']['validationSlot'],
            components=components, componentsSHA256=capture.fixture.digest(capture.repeated.restart.encoded(components)),
            repeatedEpoch=dict(generationMode='pure-jvm', transitions=epoch),
            outputMapFile='terminal-output-map.cbor', outputMapSHA256=capture.fixture.digest(b'original output'))
        return value

    def test_explicit_closed_bounds_and_aggregate_resources(self):
        for duration, blocks, epoch in ((10,1,0),(120,128,1)):
            capture.capture_limits(duration,blocks,epoch)
        for duration, blocks, epoch, selected in ((True,128,0,capture.MODE),(9,128,0,capture.MODE),
                (121,128,0,capture.MODE),(30,129,0,capture.MODE),(30,True,0,capture.MODE),
                (30,128,True,capture.MODE),(30,128,2,capture.MODE),(30,128,0,'repeated-native-checked-jvm-v1')):
            with self.subTest(duration=duration,blocks=blocks,epoch=epoch,mode=selected), self.assertRaises(ValueError):
                capture.capture_limits(duration,blocks,epoch,selected)
        self.assertEqual(sum(c for c,_ in capture.base.RESOURCES.values()),4)
        self.assertEqual(sum(m for _,m in capture.base.RESOURCES.values()),7*capture.base.GIB)
        self.assertEqual((capture.MAX_OPERATION,capture.MAX_CLEANUP),(300,30))
        with self.assertRaises(NotImplementedError): capture.repeated.require_comparator()

    def test_init_has_one_absolute_operation_deadline_and_pinned_dependencies(self):
        class Launcher:
            def __init__(self, support):
                self.deadline=240
        self.obj.args.owned_root=self.root/'owned'
        with patch.object(capture.time,'monotonic',return_value=100):
            initialized=capture.controller_type(SimpleNamespace(Launcher=Launcher))({},self.obj.args,'classpath')
        self.assertEqual(initialized.operation_started,100)
        self.assertEqual(initialized.deadline,400)
        evidence=initialized.mode_evidence()
        self.assertEqual(evidence['captureScope'],'epoch-zero-only')
        self.assertEqual(evidence['declaredTerminalEpoch'],0)
        self.assertFalse(evidence['nativeRuntimeDependency'])
        self.assertFalse(evidence['fullSoakValidated'])
        self.assertEqual(evidence['repeatedEvidenceValidatorSHA256'],capture.base.sha(Path(capture.repeated.__file__)))
        self.assertEqual(evidence['twoServiceControllerSHA256'],capture.base.sha(Path(capture.repeated.two.__file__)))
        self.assertEqual(evidence['restartControllerSHA256'],capture.base.sha(Path(capture.repeated.restart.__file__)))

    def test_parser_requires_declared_epoch_and_jvm_mode_without_execution_default(self):
        arguments=[]
        for name in ('support-manifest','owned-root','evidence-root','scala-build-root',
                     'scala-classpath-file','client-classpath-file','projection'):
            arguments.extend(['--'+name,'/tmp/'+name])
        arguments.extend(['--support-sha',H,'--scala-image','sha256:'+H])
        parser=capture.parser()
        parsed=parser.parse_args(arguments+['--epoch-mode',capture.MODE,'--terminal-epoch','0'])
        self.assertFalse(parsed.execute)
        self.assertEqual(parsed.duration_seconds,30)
        self.assertEqual(parsed.max_blocks,128)
        with patch('sys.stderr'):
            for suffix in ([],['--epoch-mode',capture.MODE],['--terminal-epoch','0'],
                           ['--epoch-mode','same-epoch','--terminal-epoch','0'],
                           ['--epoch-mode',capture.MODE,'--terminal-epoch','2']):
                with self.subTest(suffix=suffix),self.assertRaises(SystemExit):parser.parse_args(arguments+suffix)

    def test_cli_is_distinct_and_default_command_unchanged(self):
        original=['java','lab.NativeLiveBoundaryMain','initial',H,'123','42','exchange']
        default=capture.single.service_cli_tail(original,30,128)
        actual=capture.service_cli_tail(original,120,128,1)
        self.assertNotIn('--epoch-mode',default)
        self.assertEqual(actual[-2:],['--epoch-mode',capture.MODE])
        self.assertEqual(actual[actual.index('--duration-seconds')+1],'120')
        self.assertNotIn('--soak-profile',actual)
        self.assertNotIn('--native-likelihood-executable',actual)
        with self.assertRaises(ValueError): capture.single.service_cli_tail(original,120,128)
        with self.assertRaises(ValueError): capture.service_cli_tail(['lab.NativeLiveBoundaryMain','missing'],30,128,0)

    def test_capture_reserves_stages_without_changing_same_epoch_guard(self):
        original=['lab.NativeLiveBoundaryMain','initial',H,'123','42','exchange']
        self.obj.boundary_ms=1
        with patch.object(capture.time,'monotonic',return_value=100):
            self.obj.deadline=263
            self.assertIn(capture.MODE,self.obj.service_command(original))
            self.obj.deadline=262
            with self.assertRaises(ValueError): self.obj.service_command(original)
        default=object.__new__(capture.single.controller_type(self.live))
        default.args=self.obj.args;default.boundary_ms=1
        with self.assertRaises(TimeoutError): default.service_command(original)
        self.obj.args.duration_seconds=120
        self.assertEqual(self.obj.client_duration(),30)
        self.assertEqual(self.obj.oracle_main(),'lab.PlutusRepeatedServiceCompareMain')

    def test_inherited_immutable_cleanup_is_not_reimplemented(self):
        parent=self.kind.__bases__[0]
        for name in ('cleanup','remove_client','owned','capture','construct_transfer','prepare_funding'):
            if hasattr(parent,name): self.assertIs(getattr(self.kind,name),getattr(parent,name))
        self.assertEqual(self.kind.operation_seconds,300)
        self.assertEqual(parent.operation_seconds,240)

    def test_repeated_startup_and_active_clock_are_separate(self):
        ready=dict(epochMode=mode(),deadlineUnixMillis=40000,requestedDeadlineUnixMillis=40000)
        with patch.object(capture.time,'time_ns',return_value=20000*1000000):
            self.assertEqual(self.obj.readiness_deadline(ready),40000)
            for change in (dict(deadlineUnixMillis=True),dict(deadlineUnixMillis=20000),
                           dict(deadlineUnixMillis=50001),dict(requestedDeadlineUnixMillis=39999),
                           dict(soakProfile=capture.repeated.PROFILE),dict(boundedRestart={})):
                with self.subTest(change=change),self.assertRaises(ValueError):
                    self.obj.readiness_deadline(dict(ready,**change))
            for key,value in (('nativeChecked',True),('nativeRuntimeDependency',True),('mode','other')):
                wrong=copy.deepcopy(ready);wrong['epochMode'][key]=value
                with self.subTest(key=key),self.assertRaises(ValueError):self.obj.readiness_deadline(wrong)
        self.obj.wait_file=lambda name,seconds:self.obj.active_clock
        self.obj.peer_started(dict(deadlineUnixMillis=2000),900)
        self.assertTrue((self.obj.out/'service-active.json').exists())
        self.obj.active_clock['startedUnixMillis']=800
        with self.assertRaises(ValueError):self.obj.peer_started(dict(deadlineUnixMillis=2000),900)

    def test_full_duration_and_declared_terminal_epoch_are_required(self):
        result=self.result()
        self.assertEqual(self.obj.terminal_interval(result,99999,900),result['finalPin']['point'])
        for change in (dict(stopReason='blockLimit'),dict(endedUnixMillis=30999),
                       dict(endedUnixMillis=41001),dict(startedUnixMillis=999),
                       dict(effectiveDeadlineUnixMillis=30999),dict(epochMode={}),dict(soakProfile='anything')):
            with self.subTest(change=change),self.assertRaises(ValueError):
                self.obj.terminal_interval(dict(result,**change),99999,900)
        for mutate in (lambda v:v['finalPin'].update(ownerId=OTHER),
                       lambda v:v['finalPin'].update(point=self.obj.initial,validationSlot=10),
                       lambda v:v['finalPin']['point'].update(blockNo=130)):
            wrong=copy.deepcopy(result);mutate(wrong)
            with self.assertRaises(ValueError):self.obj.terminal_interval(wrong,99999,900)
        with self.assertRaises(ValueError):self.obj.terminal_interval(self.result(1),99999,900)
        self.obj.args.terminal_epoch=1
        self.assertEqual(self.obj.terminal_interval(self.result(1),99999,900)['slot'],1600)
        with self.assertRaises(ValueError):self.obj.terminal_interval(result,99999,900)

    def test_terminal_reward_phase_full_pin_epoch_and_hash_are_closed(self):
        for epoch in (0,1):
            for phase in ('absent','complete'):
                value=self.observation(epoch,phase)
                self.assertIs(capture.declared_terminal(value,epoch),value)
        for phase in ('pulsing','unknown',None):
            with self.subTest(phase=phase),self.assertRaisesRegex(ValueError,'pulsing'):
                capture.declared_terminal(self.observation(0,phase),0)
        for mutate in (lambda v:v.update(extra=True),lambda v:v.update(epoch=True),
                       lambda v:v.update(componentsSHA256=OTHER),
                       lambda v:v['pin'].update(validationSlot=599),
                       lambda v:v['repeatedEpoch'].update(transitions=True),
                       lambda v:v['repeatedEpoch'].update(generationMode='checked-jvm'),
                       lambda v:v['components'].update(epoch=1)):
            value=self.observation();mutate(value)
            with self.assertRaises(ValueError):capture.declared_terminal(value,0)
        with self.assertRaises(ValueError):capture.declared_terminal(self.observation(),1)

    def test_terminal_files_hashes_and_scope_are_retained(self):
        value=self.observation()
        capture.base.write(self.root/'terminal-observation.json',value)
        (self.root/'terminal-output-map.cbor').write_bytes(b'original output')
        capture.base.write(self.obj.out/'service-active.json',self.obj.active_clock)
        capture.base.write(self.obj.out/'repeated-publication-proof.json',{})
        result=dict(self.result(),terminalObservationFile='terminal-observation.json',
                    terminalObservationSHA256=capture.base.sha(self.root/'terminal-observation.json'))
        observed=self.obj.terminal_observation(result,dict(sourceJoinId=JOIN),H)
        self.assertEqual(observed,value)
        proof=capture.base.decode((self.obj.out/'terminal-epoch-proof.json').read_bytes())
        self.assertEqual(proof['captureScope'],'epoch-zero-only')
        self.assertFalse(proof['postBoundaryTerminalObserved'])
        self.assertFalse(proof['nativeEndpointAgreement'])
        self.assertFalse(proof['fullSoakValidated'])
        (self.root/'terminal-output-map.cbor').write_bytes(b'changed output')
        with self.assertRaises(ValueError):self.obj.terminal_observation(result,dict(sourceJoinId=JOIN),H)
        wrong=dict(result,terminalObservationSHA256=OTHER)
        with self.assertRaises(ValueError):self.obj.terminal_observation(wrong,dict(sourceJoinId=JOIN),H)

    def test_epoch_zero_comparison_cannot_pass_the_default_soak_validator(self):
        value,transfers,terminal,source,manifest,final,endpoint=soak_tests.SoakTests().comparison_evidence()
        terminal.update(slot=600,blockNo=30)
        final['point']=copy.deepcopy(terminal);final['validationSlot']=600;final['generation']=29
        value.update(terminalPoint=terminal,terminalPin=copy.deepcopy(final),epoch=0)
        arguments=(value,transfers,terminal,source,manifest,final,endpoint)
        self.assertEqual(capture.repeated.soak_comparison_result(*arguments,terminal_epoch=0),value)
        with self.assertRaises(ValueError):capture.repeated.soak_comparison_result(*arguments)
        with self.assertRaises(ValueError):capture.repeated.soak_comparison_result(*arguments,terminal_epoch=1)
        for mutate in (lambda v:v.update(extra=True),lambda v:v.update(epoch=True),
                       lambda v:v.update(endpointManifestSHA256=H),
                       lambda v:v['terminalPin'].update(ownerId=OTHER),
                       lambda v:v['terminalPoint'].update(hash=H),
                       lambda v:v['transactions'][0].update(bodySHA256=OTHER)):
            wrong=copy.deepcopy(arguments);mutate(wrong[0])
            with self.assertRaises(ValueError):capture.repeated.soak_comparison_result(*wrong,terminal_epoch=0)
        with self.assertRaises(NotImplementedError):capture.repeated.require_comparator()

    def test_epoch_zero_publications_allow_no_generation_without_parity_claim(self):
        (self.root/'jvm-likelihood').mkdir()
        state=pin(dict(slot=600,blockNo=30,hash=OTHER),generation=29)
        value=dict(schema='plutus-service-publication-v1',index=0,profileId=capture.fixture.PROFILE,
            sourceJoinId=JOIN,initialManifestSHA256=H,pin=state,diagnosticOnly=True,fullLedgerValidated=False,
            repeatedEpoch=dict(epoch=0,transitions=0,componentId=H,nonMyopicId=H,frozenId=None,checkedLikelihood=None))
        capture.base.write(self.root/'publication-0000.json',value)
        ref=dict(file='publication-0000.json',sha256=capture.base.sha(self.root/'publication-0000.json'),pin=state)
        result=dict(publications=[ref],finalPin=state)
        proof=capture.repeated.repeated_publications(self.root,result,[],JOIN,H,{},terminal_epoch=0)
        self.assertEqual(proof,dict(epochs=[0],selectedGenerations=[],unselectedDrafts=[],nativeParityChecked=False))
        with self.assertRaises(ValueError):capture.repeated.repeated_publications(self.root,result,[],JOIN,H,{})
        with self.assertRaises(ValueError):capture.repeated.repeated_publications(self.root,result,[],JOIN,H,{},terminal_epoch=1)
        (self.root/'publication-0000.json').write_text('{}')
        with self.assertRaises(ValueError):capture.repeated.repeated_publications(self.root,result,[],JOIN,H,{},terminal_epoch=0)

    def test_oracle_dispatch_preserves_serial_helper_cleanup_on_failure(self):
        self.obj.client_completed=True
        self.obj.containers={'scala':'s'*64}
        self.obj.owned=lambda cid:dict(State=dict(Running=False))
        calls=[]
        self.obj.create=lambda phase,args:(calls.append((phase,args)) or 'c'*64)
        self.obj.docker=lambda *args,**kwargs:(calls.append(args) or SimpleNamespace(stdout=''))
        with patch.object(capture.single,'checked_classpath',return_value='/work/tests'), \
             patch.object(capture.base,'check_resources',side_effect=ValueError('inspection failed')):
            with self.assertRaisesRegex(ValueError,'inspection failed'):self.obj.compare_endpoint(H)
        self.assertIn('lab.PlutusRepeatedServiceCompareMain',calls[0][1])
        self.assertNotIn('lab.PlutusServiceCompareMain',calls[0][1])
        self.assertIn(('rm','--force','c'*64),calls)
        self.assertTrue((self.obj.out/'oracle-cleanup.json').exists())
        self.obj.owned=lambda cid:dict(State=dict(Running=True))
        with self.assertRaises(ValueError):self.obj.compare_endpoint(H)


if __name__ == '__main__':
    unittest.main()
