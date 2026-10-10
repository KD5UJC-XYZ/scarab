#!/usr/bin/env python3
import copy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import private_cluster_plutus_service as c


class ServiceControllerTest(unittest.TestCase):
    def setUp(self):
        self.transfers = [dict(transactionId=str(i+1)*64, envelopeSHA256='a'*64,
            bodySHA256='b'*64, witnessesSHA256='c'*64, spentInput='d'*64+'#'+str(i*2),
            collateralInput='d'*64+'#'+str(i*2+1)) for i in range(2)]
        self.client = dict(schema='plutus-service-client-result-v1', passed=True, profileId=c.fixture.PROFILE,
            fullLedgerValidated=False, staleReceiptSubmitted=False, serviceAliveAfterSecondInclusion=True,
            sameOwner=True, firstInclusionThenSecondSubmission=True, apiAvailableAfterFirst=True,
            apiAvailableAfterSecond=True, transactions=[dict(t, ordinal=i+1, includedBodySHA256=t['bodySHA256'],
                includedWitnessesSHA256=t['witnessesSHA256'], acceptedResponse=dict(code='Accepted'),
                includedResponse=dict(code='Included'), acceptedObservedNanos=i*10+1, includedObservedNanos=i*10+2)
                for i,t in enumerate(self.transfers)])
        self.point = dict(slot=70,blockNo=7,hash='e'*64)
        self.comparison = dict(schema='plutus-service-endpoint-comparison-v1', terminalPoint=self.point,
            sourceJoinId='f'*64, initialManifestSHA256='a'*64, completeUtxoEqual=True,
            collateralPreserved=True, instantaneousStakeEqual=True, endpointSnapshotsUnchanged=True,
            representedProtocolEqual=True, diagnosticOnly=True, fullLedgerValidated=False, restartSupported=False,
            feesBefore=200000, feesAfter=800000,
            transactions=[dict(t,spent=t['spentInput'],collateral=t['collateralInput']) for t in self.transfers])

    def test_service_limit_integer_bounds(self):
        for duration,blocks in ((1,1),(60,128)): c.service_limits(duration,blocks)
        for duration,blocks in ((True,1),(0,1),(61,1),(30,False),(30,0),(30,129)):
            with self.subTest(duration=duration,blocks=blocks),self.assertRaises(ValueError): c.service_limits(duration,blocks)

    def test_exact_compile_dispatch_no_expected_transaction(self):
        result=c.service_cli_tail(['java','lab.NativeLiveBoundaryMain','initial','pin','123','42','exchange'],30,128)
        self.assertEqual(result,['java','lab.Main','plutus-service','--profile',c.fixture.PROFILE,
            '--initial','initial','--manifest-sha256','pin','--port','123','--magic','42','--output','exchange',
            '--duration-seconds','30','--max-blocks','128'])
        with self.assertRaises(ValueError): c.service_cli_tail(['lab.NativeLiveBoundaryMain','incomplete'],30,128)

    def test_actual_epoch_wallclock_margin(self):
        c.epoch_budget(100000,30,67999)
        for now in (68000,99000,100000):
            with self.assertRaises(TimeoutError): c.epoch_budget(100000,30,now)
        with patch.object(c.time,'time_ns',return_value=68000*1000000):
            with self.assertRaises(TimeoutError): c.epoch_budget(100000,30)

    def test_create_enforces_epoch_budget_and_preserves_client(self):
        class Launcher:
            def create(self,phase,tail):return tail
        kind=c.controller_type(SimpleNamespace(Launcher=Launcher))
        obj=object.__new__(kind);obj.args=SimpleNamespace(duration_seconds=30,max_blocks=128);obj.boundary_ms=100000
        tail=['lab.NativeLiveBoundaryMain','initial','pin','1','42','exchange']
        with patch.object(c.time,'time_ns',return_value=68000*1000000):
            with self.assertRaises(TimeoutError):obj.create('scala',tail)
        self.assertEqual(obj.create('ada-client',['lab.PlutusServiceClientMain','1','30','/exchange']),
            ['lab.PlutusServiceClientMain','1','30','/exchange'])

    def test_valid_two_independent_http_flow(self):self.assertIs(c.client_result(self.client,self.transfers),self.client)

    def test_second_before_first_inclusion_rejected(self):
        self.client['transactions'][1]['acceptedObservedNanos']=1
        with self.assertRaises(ValueError):c.client_result(self.client,self.transfers)

    def test_http_identity_and_inclusion_tampering_rejected(self):
        for field in ('transactionId','envelopeSHA256','includedBodySHA256','includedWitnessesSHA256'):
            value=copy.deepcopy(self.client);value['transactions'][1][field]='f'*64
            with self.subTest(field=field),self.assertRaises(ValueError):c.client_result(value,self.transfers)

    def test_stopped_after_first_or_scope_expansion_rejected(self):
        for field,value in (('apiAvailableAfterFirst',False),('apiAvailableAfterSecond',False),
                            ('serviceAliveAfterSecondInclusion',False),('sameOwner',False),
                            ('staleReceiptSubmitted',True),('fullLedgerValidated',True)):
            changed=dict(self.client,**{field:value})
            with self.subTest(field=field),self.assertRaises(ValueError):c.client_result(changed,self.transfers)

    def test_overlapping_collateral_rejected(self):
        self.transfers[1]['collateralInput']=self.transfers[0]['collateralInput']
        with self.assertRaises(ValueError):c.client_result(self.client,self.transfers)

    def test_exact_endpoint_comparison(self):
        self.assertIs(c.comparison_result(self.comparison,self.transfers,self.point,'f'*64,'a'*64),self.comparison)

    def test_endpoint_stale_point_fee_or_scope_rejected(self):
        for field,value in (('terminalPoint',dict(self.point,slot=71)),('feesAfter',800001),
                            ('collateralPreserved',False),('representedProtocolEqual',False),('restartSupported',True)):
            with self.subTest(field=field),self.assertRaises(ValueError):
                c.comparison_result(dict(self.comparison,**{field:value}),self.transfers,self.point,'f'*64,'a'*64)

    def test_endpoint_original_pair_tampering_rejected(self):
        for field in ('bodySHA256','witnessesSHA256','spent','collateral'):
            value=copy.deepcopy(self.comparison);value['transactions'][1][field]='0'*64
            with self.subTest(field=field),self.assertRaises(ValueError):
                c.comparison_result(value,self.transfers,self.point,'f'*64,'a'*64)

    def test_profile_submit_prohibition_inherited(self):
        class Launcher:
            def execute(self,*args,**kwargs):raise AssertionError('unexpected reference submit')
        obj=object.__new__(c.controller_type(SimpleNamespace(Launcher=Launcher)))
        with self.assertRaises(ValueError):obj.execute('cardano-cli','conway','transaction','submit','--tx-file','test.signed')

    def test_aggregate_resources_remain_bounded(self):
        c.base.budget()
        self.assertEqual(sum(x[0] for x in c.base.RESOURCES.values()),4)
        self.assertEqual(sum(x[1] for x in c.base.RESOURCES.values()),7*c.base.GIB)

    def test_compile_classpath_excludes_test_runtime(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'app/target/test-classes').mkdir(parents=True)
            path=root/'cp';path.write_text('/work/app/target/test-classes')
            with self.assertRaises(ValueError):c.checked_classpath(path,root,True)
            self.assertEqual(c.checked_classpath(path,root),'/work/app/target/test-classes')

    def test_client_inspection_failure_still_removes_owned_client(self):
        class Launcher:pass
        obj=object.__new__(c.controller_type(SimpleNamespace(Launcher=Launcher)))
        obj.args=SimpleNamespace(client_classpath_file=Path('/cp'),scala_build_root=Path('/build'),
            java='java',scala_image='image',duration_seconds=30)
        obj.containers={'reference':'r'*64};obj.exchange=Path('/exchange');obj.api_port=1234
        obj.create=lambda phase,args:'c'*64
        obj.owned=lambda cid:dict()
        removed=[];obj.remove_client=lambda:removed.append(True)
        with patch.object(c,'checked_classpath',return_value='/work/test-classes'), \
             patch.object(c.base,'check_resources',side_effect=ValueError('wrong network')):
            with self.assertRaisesRegex(ValueError,'wrong network'):obj.run_client()
        self.assertEqual(removed,[True])

    def test_publication_originals_cross_bind_client_and_final_result(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            refs=[]
            for index,row in enumerate(self.client['transactions']):
                name=f'publication-{index:04d}.json'
                pin=dict(ownerId='a'*64,generation=index+1,point=self.point)
                publication=dict(schema='plutus-service-publication-v1',index=index,profileId=c.fixture.PROFILE,
                    sourceJoinId='f'*64,initialManifestSHA256='a'*64,pin=pin,diagnosticOnly=True,fullLedgerValidated=False,
                    included=[{k:row[k] for k in ('transactionId','bodySHA256','witnessesSHA256')}])
                c.base.write(root/name,publication)
                digest=c.base.sha(root/name)
                refs.append(dict(file=name,sha256=digest,pin=pin))
                row.update(publicationFile=name,publicationSHA256=digest,publication=publication)
                row['includedResponse']['pin']=pin
            result=dict(publications=refs)
            c.publication_bindings(root,result,self.client,'f'*64,'a'*64)
            self.client['transactions'][1]['includedResponse']['pin']=dict(pin,generation=99)
            with self.assertRaises(ValueError):c.publication_bindings(root,result,self.client,'f'*64,'a'*64)
            self.client['transactions'][1]['includedResponse']['pin']=pin
            (root/refs[0]['file']).write_text('{}')
            with self.assertRaises(ValueError):c.publication_bindings(root,result,self.client,'f'*64,'a'*64)

    def test_publication_traversal_and_duplicate_names_rejected(self):
        for name in ('../publication-0000.json','publication-99999.json','other.json'):
            with self.subTest(name=name),self.assertRaises(ValueError):
                c.publication_bindings(Path('/tmp'),dict(publications=[dict(file=name)]),self.client,'f'*64,'a'*64)


class RuntimeFailureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.exchange = self.root/'exchange'; self.exchange.mkdir()
        self.out = self.root/'out'; self.out.mkdir()
        self.cid = 'c'*64
        self.args = SimpleNamespace(owned_root=self.root, scala_build_root=Path('/build'), scala_image='image')
        self.observed = dict(Id=self.cid, Image='image', State=dict(Running=False),
            Config=dict(Labels={'lab.zero-live.phase':'scala'}), Mounts=[
                dict(Type='bind',Source='/build',Destination='/work',RW=False),
                dict(Type='bind',Source=str(self.exchange),Destination='/exchange',RW=True)])
        self.failure = dict(schema='plutus-service-failure-v1',status='failed',
            errorType='IllegalArgumentException',message='Unsupported(active monetary reward pulser)',
            fullLedgerValidated=False)
        self.source = dict(sourceJoinId='a'*64,initialManifestSHA256='b'*64)
        kind = c.controller_type(SimpleNamespace(Launcher=type('Launcher',(),{})))
        self.obj = object.__new__(kind)
        self.obj.args=self.args; self.obj.exchange=self.exchange; self.obj.out=self.out
        self.obj.deadline=c.time.monotonic()+5; self.obj.containers={'scala':self.cid}
        self.obj.owned=lambda cid:self.observed
        self.obj.runtime_failure_source=self.source

    def save(self, value=None):
        raw=c.base.json.dumps(self.failure if value is None else value).encode()
        (self.exchange/'failure.json').write_bytes(raw)
        return raw

    def evidence(self):
        return c.runtime_failure_evidence(self.exchange,self.args,self.cid,self.observed,self.source)

    def recorded(self):
        return c.base.decode((self.out/'service-runtime-failure.json').read_bytes())

    def test_exact_pulser_failure_source_context_hash_and_original_preserved(self):
        raw=self.save(); value=self.evidence()
        self.assertEqual(value['validation'],'typed-failure')
        self.assertEqual(value['summary']['cause'],'UnsupportedActiveMonetaryRewardPulser')
        self.assertEqual(value['summary']['message'],self.failure['message'])
        self.assertEqual(value['original'],dict(file='exchange/failure.json',bytes=len(raw),sha256=c.fixture.digest(raw)))
        self.assertEqual(value['bootstrapSourceContext'],self.source)
        self.assertFalse(value['runtimeSourceBindingPresent'])
        self.assertFalse(value['passed']); self.assertFalse(value['fullLedgerValidated'])
        self.assertEqual((self.exchange/'failure.json').read_bytes(),raw)

    def test_unknown_exception_text_is_redacted_including_near_match_and_controls(self):
        for error_type,message in (('SecretClass','secret password'),('IllegalArgumentException',
                'Unsupported(active monetary reward pulser)\nsecret'),('IllegalArgumentException','\x1b[31msecret'),
                ('IllegalStateException',self.failure['message'])):
            with self.subTest(error_type=error_type,message=message):
                self.save(dict(self.failure,errorType=error_type,message=message))
                summary=self.evidence()['summary']
                self.assertEqual(summary['cause'],'UnclassifiedRuntimeFailure')
                self.assertTrue(summary['messageRedacted']); self.assertNotIn('message',summary)
                self.assertNotIn('secret',c.base.json.dumps(summary))
                self.assertNotIn('SecretClass',c.base.json.dumps(summary))

    def test_schema_bounds_duplicate_keys_and_scope_fail_closed(self):
        changes=[dict(schema='other'),dict(status='stopped'),dict(fullLedgerValidated=True),
                 dict(fullLedgerValidated=0),dict(errorType='x'*129),dict(message='x'*513),
                 dict(message=[]),dict(sourceJoinId='a'*64)]
        for change in changes:
            with self.subTest(change=change):
                self.save(dict(self.failure,**change)); value=self.evidence()
                self.assertEqual(value['validation'],'rejected'); self.assertNotIn('summary',value)
        for raw in (b'{}',b'null',b'[]',b'{"schema":1,"schema":2}',b'{"value":NaN}',b'\xff',b'['*2000):
            with self.subTest(raw=raw[:30]):
                (self.exchange/'failure.json').write_bytes(raw)
                value=self.evidence()
                self.assertEqual(value['validation'],'rejected'); self.assertNotIn('summary',value)
                self.assertEqual(value['original']['sha256'],c.fixture.digest(raw))

    def test_runtime_file_must_be_bounded_regular_and_no_symlink(self):
        path=self.exchange/'failure.json'
        target=self.root/'external.json'; target.write_bytes(c.base.json.dumps(self.failure).encode())
        path.symlink_to(target)
        with self.assertRaises(OSError):self.evidence()
        path.unlink(); path.mkdir()
        with self.assertRaises((OSError,ValueError)):self.evidence()
        path.rmdir(); c.os.mkfifo(path)
        with self.assertRaises(ValueError):self.evidence()
        path.unlink(); path.write_bytes(b'x'*16385)
        with self.assertRaises(ValueError):self.evidence()

    def test_canonical_owned_exchange_container_image_phase_and_mounts_required(self):
        self.save()
        mutations=[('Id','d'*64),('Image','other'),('Config',dict(Labels={'lab.zero-live.phase':'helper'})),
                   ('Mounts',self.observed['Mounts'][:1]),('Mounts',[
                       self.observed['Mounts'][0],dict(Type='bind',Source='/elsewhere',Destination='/exchange',RW=True)])]
        for key,value in mutations:
            with self.subTest(key=key),self.assertRaises(ValueError):
                c.runtime_failure_evidence(self.exchange,self.args,self.cid,dict(self.observed,**{key:value}))
        with self.assertRaises(ValueError):
            c.runtime_failure_evidence(self.exchange,self.args,'not-a-container',self.observed)
        alias=self.root/'alias'; alias.symlink_to(self.exchange, target_is_directory=True)
        with self.assertRaises(ValueError):
            c.runtime_failure_evidence(alias,self.args,self.cid,self.observed)
        for source in ({'sourceJoinId':'a'*64},dict(self.source,sourceJoinId='secret'),dict(self.source,extra=True)):
            with self.subTest(source=source),self.assertRaises(ValueError):
                c.runtime_failure_evidence(self.exchange,self.args,self.cid,self.observed,source)

    def test_owned_exit_rechecks_failure_published_during_inspection(self):
        reads=[]
        def owned(cid):
            reads.append(cid); self.save(); return self.observed
        self.obj.owned=owned
        with self.assertRaisesRegex(ValueError,'service exited before final result; runtime IllegalArgumentException: '
                                   r'Unsupported\(active monetary reward pulser\)'):
            self.obj.wait_file('result.json',1)
        self.assertEqual(reads,[self.cid])
        self.assertEqual(self.recorded()['validation'],'typed-failure')
        self.assertFalse((self.exchange/'result.json').exists())

    def test_existing_failure_wins_over_even_a_present_result(self):
        self.save(); (self.exchange/'result.json').write_text('{"status":"stopped"}')
        with self.assertRaisesRegex(ValueError,'service reported terminal failure; runtime'):
            self.obj.wait_file('result.json',1)
        self.assertEqual(self.recorded()['summary']['cause'],'UnsupportedActiveMonetaryRewardPulser')

    def test_missing_malformed_or_unowned_diagnostic_never_masks_original_exit(self):
        for mode in ('missing','malformed','unowned','unsafe'):
            with self.subTest(mode=mode):
                path=self.exchange/'failure.json'
                if path.exists():path.unlink()
                if mode=='malformed':path.write_bytes(b'secret invalid bytes')
                if mode=='unowned':
                    self.save(); self.observed['Image']='wrong'
                if mode=='unsafe':self.save(dict(self.failure,message='secret private exception'))
                with self.assertRaises(ValueError) as caught:
                    self.obj.terminal_failure('service exited before final result',self.observed)
                self.assertTrue(str(caught.exception).startswith('service exited before final result'))
                self.assertNotIn('secret',str(caught.exception))
                self.assertNotIn('secret',c.base.json.dumps(self.recorded()))
                self.observed['Image']='image'
                (self.out/'service-runtime-failure.json').unlink()

    def test_recording_failure_does_not_replace_runtime_cause_or_first_evidence(self):
        raw=self.save()
        original=b'{"first":"retained"}'
        (self.out/'service-runtime-failure.json').write_bytes(original)
        with self.assertRaisesRegex(ValueError,'Unsupported'):
            self.obj.terminal_failure('service reported terminal failure',self.observed)
        self.assertEqual((self.out/'service-runtime-failure.json').read_bytes(),original)
        with patch.object(c.base,'write',side_effect=OSError('secret recording error')):
            with self.assertRaisesRegex(ValueError,'Unsupported'):
                self.obj.terminal_failure('service reported terminal failure',self.observed)
        self.assertEqual((self.exchange/'failure.json').read_bytes(),raw)

    def test_result_and_timeout_paths_are_unchanged(self):
        path=self.exchange/'result.json'; path.write_text('{"status":"stopped"}')
        self.assertEqual(self.obj.wait_file('result.json',1),dict(status='stopped'))
        path.unlink(); self.observed['State']['Running']=True
        with patch.object(c.time,'monotonic',side_effect=[0,0,2]),patch.object(c.time,'sleep'):
            with self.assertRaisesRegex(TimeoutError,'bounded service result readiness'):
                self.obj.wait_file('result.json',1)
        self.assertFalse((self.out/'service-runtime-failure.json').exists())


class SequentialClientTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.pin = dict(ownerId='a'*64, profileId=c.fixture.PROFILE, generation=1,
            coherentStateId='b'*64,ledgerStateId='c'*64,environmentId='d'*64,
            point=dict(slot=10,blockNo=1,hash='e'*64),validationSlot=10)
        self.state = dict(code='State',profileId=c.fixture.PROFILE,closed=False,fullLedgerValidated=False,pin=self.pin)
        self.client = dict(transactions=[dict(acceptedResponse=dict(receipt=dict(pin=self.pin)),
            includedResponse=dict(pin=self.pin)) for _ in range(2)],
            betweenStateResponse=dict(pin=self.pin),afterSecondStateResponse=dict(pin=self.pin),
            firstStatusAfterSecondResponse=dict(pin=self.pin))
        c.base.write(self.root/'service-client-result.json',self.client)
        self.report = dict(schema='sequential-devnet-runner-v1',allRequestedPassed=False,
            executedScenariosPassed=True,stop='Exhausted',observationalOnly=True,restoreAuthority=False,rows=[])
        scenarios=('ObserveService','TwoSequentialTransfers','ObserveService','RestartAndRejoin','FollowAcrossEpochs','MultipleNodes')
        requirements=('DurableRestore','RepeatedEpochTransition','MultipleIngressNodes')
        for index,scenario in enumerate(scenarios):
            if index<3:
                raw=(self.root/'service-client-result.json').read_bytes() if index==1 else c.base.json.dumps(self.state).encode()
                (self.root/f'scenario-checkpoint-{index}.json').write_bytes(raw)
                verdict=dict(outcome='completed',checkpointSHA256=c.fixture.digest(raw),checkpointBytes=len(raw),index=index)
            else: verdict=dict(outcome='blocked',missingRequirement=requirements[index-3])
            self.report['rows'].append(dict(scenario=scenario,verdict=verdict))
        self.save_report()

    def save_report(self):
        (self.root/'scenario-runner-result.json').write_text(c.base.json.dumps(self.report))

    def replace_checkpoint(self,index,value):
        raw=c.base.json.dumps(value).encode()
        (self.root/f'scenario-checkpoint-{index}.json').write_bytes(raw)
        self.report['rows'][index]['verdict'].update(checkpointSHA256=c.fixture.digest(raw),checkpointBytes=len(raw))
        self.save_report()

    def test_default_direct_arguments_are_unchanged(self):
        self.assertEqual(c.client_command('direct',1234,30),['lab.PlutusServiceClientMain','1234','30','/exchange'])
        self.assertEqual(c.client_command('sequential',1234,30),['lab.PlutusScenarioRunnerMain','1234','30','/exchange'])

    def test_closed_mode_and_runner_bounds(self):
        for mode,port,duration in (('lab.OtherMain',1234,30),('SEQUENTIAL',1234,30),('sequential',1234,2),
                                  ('sequential',1234,61),('sequential',True,30),('direct',0,30)):
            with self.subTest(mode=mode,port=port,duration=duration),self.assertRaises(ValueError):
                c.client_command(mode,port,duration)
        c.client_command('direct',1234,1)

    def test_typed_report_original_checkpoint_binding(self):
        raw,checkpoints=c.sequential_report(self.root,self.client)
        self.assertEqual(raw,(self.root/'scenario-runner-result.json').read_bytes())
        self.assertEqual(checkpoints[1],(self.root/'service-client-result.json').read_bytes())
        self.assertEqual(len(checkpoints),3)

    def test_all_blocked_cannot_be_a_success(self):
        for row in self.report['rows'][:3]:row['verdict']=dict(outcome='blocked',missingRequirement='DurableRestore')
        self.save_report()
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_success_scope_and_stop_flags_are_strict(self):
        for field,value in (('allRequestedPassed',True),('executedScenariosPassed',False),('restoreAuthority',True),
                            ('observationalOnly',False),('stop','Failed')):
            original=self.report[field];self.report[field]=value;self.save_report()
            with self.subTest(field=field),self.assertRaises(ValueError):c.sequential_report(self.root,self.client)
            self.report[field]=original

    def test_exact_scenario_order_and_blocked_requirements(self):
        self.report['rows'][1]['scenario']='MultipleNodes';self.save_report()
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)
        self.report['rows'][1]['scenario']='TwoSequentialTransfers'
        self.report['rows'][4]['verdict']['missingRequirement']='DurableRestore';self.save_report()
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_checkpoint_index_size_hash_types(self):
        original=copy.deepcopy(self.report['rows'][0]['verdict'])
        for field,value in (('index',True),('index',2),('checkpointBytes',0),('checkpointBytes',65537),
                            ('checkpointSHA256','f'*64),('outcome','blocked')):
            self.report['rows'][0]['verdict']=dict(original,**{field:value});self.save_report()
            with self.subTest(field=field),self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_transfer_checkpoint_cannot_replace_original_client(self):
        self.replace_checkpoint(1,dict(self.client,changed=True))
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_initial_owner_cross_binding_rejects_changed_final_owner(self):
        state=copy.deepcopy(self.state);state['pin']['ownerId']='f'*64
        self.replace_checkpoint(2,state)
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_client_pin_cannot_belong_to_another_owner(self):
        self.client=copy.deepcopy(self.client)
        self.client['transactions'][1]['acceptedResponse']['receipt']['pin']=dict(self.pin,ownerId='f'*64)
        (self.root/'service-client-result.json').write_text(c.base.json.dumps(self.client))
        self.replace_checkpoint(1,self.client)
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_aggregate_checkpoint_budget(self):
        self.replace_checkpoint(0,dict(self.state,padding='x'*33000))
        self.replace_checkpoint(2,dict(self.state,padding='y'*33000))
        with self.assertRaisesRegex(ValueError,'aggregate'):c.sequential_report(self.root,self.client)

    def test_report_and_checkpoint_files_reject_symlinks(self):
        path=self.root/'scenario-checkpoint-0.json'
        target=self.root/'preserved.json';target.write_bytes(path.read_bytes())
        path.unlink();path.symlink_to(target)
        with self.assertRaisesRegex(ValueError,'symlink'):c.sequential_report(self.root,self.client)

    def test_initial_observation_cannot_follow_first_admission(self):
        state=copy.deepcopy(self.state);state['pin']['generation']=2
        self.replace_checkpoint(0,state)
        self.replace_checkpoint(2,state)
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)

    def test_closed_or_stale_final_observation_rejected(self):
        self.replace_checkpoint(2,dict(self.state,closed=True))
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)
        state=copy.deepcopy(self.state);state['pin']['generation']=0
        self.replace_checkpoint(2,state)
        with self.assertRaises(ValueError):c.sequential_report(self.root,self.client)


class HelperFailureTest(unittest.TestCase):
    class FailedProcess(ValueError):
        def __init__(self,code,stdout,stderr):
            self.code,self.stdout,self.stderr=code,stdout,stderr
            super().__init__('original helper failure')

    def test_private_stream_limits_hashes_and_permissions(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            stdout=b'x'*300000;stderr=b'private stderr'
            value=c.retain_helper_failure(self.FailedProcess(2,stdout,stderr),root)
            self.assertEqual(value['exitCode'],2)
            self.assertEqual(value['stdout']['observedBytes'],300000)
            self.assertEqual(value['stdout']['retainedBytes'],262144)
            self.assertTrue(value['stdout']['truncated'])
            self.assertEqual(value['stdout']['originalSHA256'],c.fixture.digest(stdout))
            self.assertEqual(value['stdout']['retainedSHA256'],c.fixture.digest(stdout[:262144]))
            self.assertEqual((root/'client-helper-failure.stderr').read_bytes(),stderr)
            self.assertEqual((root/'client-helper-failure.stderr').stat().st_mode&0o777,0o600)
            self.assertNotIn('private stderr',c.base.json.dumps(value))
            with self.assertRaises(FileExistsError):c.retain_helper_failure(self.FailedProcess(2,b'',b''),root)

    def failed_report(self):
        return dict(schema='sequential-devnet-runner-v1',stop='Failed',allRequestedPassed=False,
            executedScenariosPassed=False,observationalOnly=True,restoreAuthority=False,
            rows=[dict(scenario='ObserveService',verdict=dict(outcome='completed')),
                  dict(scenario='TwoSequentialTransfers',verdict=dict(outcome='failed',reason='ClientFailure',
                    diagnostic=dict(operation='HttpClient',cause='ConnectionFailure',
                        lastObservation=dict(stage='WaitFirstInclusion',responseCode='Pending'))))])

    def test_failure_report_closed_diagnostics_without_raw_text(self):
        report=self.failed_report();report['arbitrary']='private exception text'
        summary=c.typed_failure_summary(c.base.json.dumps(report).encode())
        self.assertEqual(summary['diagnostic']['cause'],'ConnectionFailure')
        self.assertEqual(summary['diagnostic']['lastObservation']['responseCode'],'Pending')
        self.assertNotIn('private exception text',c.base.json.dumps(summary))
        report['executedScenariosPassed']=True
        with self.assertRaises(ValueError):c.typed_failure_summary(c.base.json.dumps(report).encode())

    def test_unknown_cause_never_becomes_public_text(self):
        report=self.failed_report();report['rows'][-1]['verdict']['diagnostic']['cause']='secret text'
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);c.base.write(root/'scenario-runner-result.json',report)
            value=c.retain_helper_failure(self.FailedProcess(2,b'',b''),root)
            self.assertEqual(value['scenarioReport']['validation'],'rejected')
            self.assertNotIn('secret text',c.base.json.dumps(value))

    def test_oversized_upstream_stream_is_not_retained(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                c.retain_helper_failure(self.FailedProcess(2,b'x'*(4*1024*1024+1),b''),Path(folder))
            self.assertEqual(list(Path(folder).iterdir()),[])

    def exercise_run_client_failure(self,recording_failure=False):
        class Launcher:pass
        live=SimpleNamespace(Launcher=Launcher,bounded_process=SimpleNamespace(__globals__={'FailedProcess':self.FailedProcess}))
        obj=object.__new__(c.controller_type(live))
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);submission=root/'submission';submission.mkdir();out=root/'out';out.mkdir()
            obj.exchange=root;obj.out=out;obj.api_port=1234;obj.deadline=c.time.monotonic()+30
            obj.args=SimpleNamespace(client_classpath_file=Path('/cp'),scala_build_root=Path('/build'),
                java='java',scala_image='image',duration_seconds=30)
            obj.containers={'reference':'r'*64};obj.create=lambda phase,args:'c'*64
            obj.owned=lambda cid:dict(Mounts=[dict(Type='bind',Source='/build',Destination='/work',RW=False),
                dict(Type='bind',Source=str(root),Destination='/exchange',RW=False),
                dict(Type='bind',Source=str(submission),Destination='/exchange/submission',RW=True)],Image='image')
            original=self.FailedProcess(7,b'helper output',b'helper error')
            def fail(*args,**kwargs):raise original
            obj.docker=fail;removed=[];diagnostics=[]
            def remove():
                removed.append(True)
                if not recording_failure:self.assertTrue((out/'client-helper-failure.json').exists())
            obj.remove_client=remove
            obj.diagnostic_write=lambda name,value:diagnostics.append((name,value))
            with patch.object(c,'checked_classpath',return_value='/work/test-classes'),patch.object(c.base,'check_resources'):
                if recording_failure:
                    with patch.object(c,'retain_helper_failure',side_effect=OSError('private error text')):
                        with self.assertRaises(self.FailedProcess) as caught:obj.run_client()
                else:
                    with self.assertRaises(self.FailedProcess) as caught:obj.run_client()
            self.assertIs(caught.exception,original)
            self.assertEqual(removed,[True])
            if recording_failure:
                self.assertEqual(diagnostics[0][1],dict(errorType='OSError'))

    def test_failure_is_retained_before_cleanup_and_original_reraised(self):self.exercise_run_client_failure()
    def test_diagnostic_failure_preserves_original_and_cleanup(self):self.exercise_run_client_failure(True)


if __name__=='__main__':unittest.main()
